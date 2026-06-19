// =============================================================================
//  ratherllm :: jni_bridge.cpp
//
//  JNI orchestration boundary for com.kotlin.ratherllm.NativeBridge. Constructs
//  and wires the four native modules into libratherllm.so:
//
//      MllmEngine --(set_sink)--> UdsServer::publish --(SPSC ring/epoll)--> UDS
//      UdsServer  --(on_request)--> MllmEngine::submit
//      UdsServer  --(on_cancel)---> MllmEngine::cancel
//      NeuropilotAdapter : optional APU offload (transparent CPU fallback)
//
//  Teardown order is strict (stopEngine): the engine/decode thread is stopped
//  and joined (and its memory arena released) BEFORE the UdsServer is closed,
//  so a producer can never publish() into a destroyed server (no use-after-free,
//  no dangling-fd panic).
// =============================================================================
#include <jni.h>

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>

#include "hardware_affinity.h"
#include "mllm_engine.h"
#include "neuropilot_adapter.h"
#include "uds_server.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define RLLM_JNI_TAG "ratherllm.jni"
#define RLLM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, RLLM_JNI_TAG, __VA_ARGS__)
#define RLLM_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  RLLM_JNI_TAG, __VA_ARGS__)
#define RLLM_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  RLLM_JNI_TAG, __VA_ARGS__)
#else
#define RLLM_LOGE(...) do { std::fprintf(stderr, "[ratherllm.jni][E] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define RLLM_LOGW(...) do { std::fprintf(stderr, "[ratherllm.jni][W] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define RLLM_LOGI(...) do { std::fprintf(stderr, "[ratherllm.jni][I] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

using namespace ratherllm;

namespace {

// Single global engine instance protected by a coarse lifecycle mutex. Start /
// stop are rare, serialized control-plane operations; the data plane (tokens)
// runs lock-free through the engine + UDS server.
    std::mutex                          g_mtx;
    std::unique_ptr<UdsServer>          g_uds;
    std::unique_ptr<MllmEngine>         g_engine;
    std::unique_ptr<NeuropilotAdapter>  g_npu;
    std::atomic<bool>                   g_running{false};

    std::string jstr(JNIEnv* env, jstring s) {
        if (s == nullptr) return {};
        const char* c = env->GetStringUTFChars(s, nullptr);
        std::string out = (c != nullptr) ? std::string(c) : std::string();
        if (c != nullptr) env->ReleaseStringUTFChars(s, c);
        return out;
    }

// Tear down everything that was partially built (used on start failure and stop).
// Honors the strict order: engine first, then server, then APU.
    void teardown_locked() {
        if (g_engine) { g_engine->shutdown(); g_engine.reset(); } // join decode thread + free arena
        if (g_uds)    { g_uds->stop();        g_uds.reset();    } // join IO thread + close fds
        if (g_npu)    { g_npu->unload();      g_npu.reset();    } // release APU + dlclose
        g_running.store(false, std::memory_order_release);
    }

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    return JNI_VERSION_1_6;
}

// -----------------------------------------------------------------------------
//  startEngine(String modelPath, boolean useNeuropilot) -> int (0 / -errno)
// -----------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_kotlin_ratherllm_NativeBridge_startEngine(JNIEnv* env, jobject /*thiz*/,
                                                   jstring jModelPath,
                                                   jboolean jUseNeuropilot) {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_running.load(std::memory_order_acquire)) {
        RLLM_LOGW("startEngine: already running");
        return 0;
    }

    const std::string model_path = jstr(env, jModelPath);
    if (model_path.empty()) {
        RLLM_LOGE("startEngine: empty model path");
        return -EINVAL;
    }

    // (a) Optional APU bring-up. Failure is non-fatal: CPU KleidiAI path remains.
    if (jUseNeuropilot == JNI_TRUE) {
        g_npu = std::make_unique<NeuropilotAdapter>();
        if (!g_npu->initialize()) {
            RLLM_LOGW("NeuroPilot APU unavailable; using CPU KleidiAI/Vulkan path");
        } else {
            ApuExecConfig acfg;
            acfg.allow_8w16a_affine_operators = true;
            acfg.prefer_sram_fusion           = true;
            g_npu->set_exec_config(acfg);
        }
    }

    // (b) Construct the engine.
    EngineConfig ecfg;
    ecfg.model_path     = model_path;
    ecfg.lock_weights   = true;
    ecfg.use_neuropilot = (g_npu && g_npu->is_available());
    ecfg.pin_decode     = true;
    g_engine = std::make_unique<MllmEngine>(ecfg);

    // (c) Construct + init the UDS server (abstract name "poco_mllm_uds_pipe").
    UdsConfig ucfg; // default abstract_name == "poco_mllm_uds_pipe"
    g_uds = std::make_unique<UdsServer>(ucfg);
    int rc = g_uds->init();
    if (rc != 0) {
        RLLM_LOGE("UdsServer::init failed: %d", rc);
        teardown_locked();
        return rc;
    }

    // (d) Link engine token output -> UDS publish (the lock-free SPSC ring path).
    UdsServer* uds = g_uds.get();
    g_engine->set_sink([uds](uint64_t rid, int32_t tid,
                             const std::string& txt, bool fin) -> bool {
        return uds->publish(TokenChunk{ rid, tid, txt, fin });
    });

    // (e) Load weights (mmap + mlock + pre-fault) and spawn the pinned decode thread.
    rc = g_engine->load();
    if (rc != 0) {
        RLLM_LOGE("MllmEngine::load failed: %d", rc);
        teardown_locked();
        return rc;
    }

    // (f) Start the epoll IO loop: inbound requests -> engine; cancels -> engine.
    MllmEngine* eng = g_engine.get();
    rc = g_uds->start(
            /*on_request=*/[eng](int /*client_fd*/, GenerateRequest&& req) {
                GenParams gp;
                gp.max_tokens  = req.max_tokens;
                gp.temperature = req.temperature;
                gp.top_k       = req.top_k;
                eng->submit(req.request_id, req.prompt, req.image_uri, gp);
            },
            /*on_cancel=*/[eng](uint64_t request_id) {
                eng->cancel(request_id);
            });
    if (rc != 0) {
        RLLM_LOGE("UdsServer::start failed: %d", rc);
        teardown_locked();
        return rc;
    }

    g_running.store(true, std::memory_order_release);
    RLLM_LOGI("engine started (model=%s, apu=%d)",
              model_path.c_str(), (int)ecfg.use_neuropilot);
    return 0;
}

// -----------------------------------------------------------------------------
//  stopEngine() -> void  (strict teardown order)
// -----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_kotlin_ratherllm_NativeBridge_stopEngine(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (!g_running.load(std::memory_order_acquire) && !g_engine && !g_uds) {
        return; // idempotent
    }
    RLLM_LOGI("stopEngine: tearing down (engine -> server -> apu)");
    teardown_locked();
    RLLM_LOGI("stopEngine: complete");
}

// -----------------------------------------------------------------------------
//  isRunning() -> boolean
// -----------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_kotlin_ratherllm_NativeBridge_isRunning(JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_running.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
