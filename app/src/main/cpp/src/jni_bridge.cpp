// =============================================================================
//  ratherllm :: jni_bridge.cpp
//
//  JNI boundary for com.kotlin.ratherllm.NativeBridge over the llama.cpp engine.
//  Handles are integer ids into a shared_ptr registry so a freeModel() that
//  races an in-flight generate() cannot free the engine out from under it — the
//  running generate holds its own shared_ptr for the call's duration.
// =============================================================================
#include <jni.h>
#include <android/log.h>

#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "llama.h"
#include "ggml.h"
#include "llama_engine.h"

#include "nlohmann/json.hpp"

using json = nlohmann::json;
using namespace rllm;

#define TAG "ratherllm.jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

namespace {

std::mutex g_mtx;
std::unordered_map<jlong, std::shared_ptr<LlamaEngine>> g_engines;
jlong g_next_handle = 1;

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? std::string(c) : std::string();
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

std::shared_ptr<LlamaEngine> lookup(jlong handle) {
    std::lock_guard<std::mutex> lk(g_mtx);
    auto it = g_engines.find(handle);
    return it == g_engines.end() ? nullptr : it->second;
}

// Convert (already UTF-8-complete) bytes to UTF-16 for NewString. NewStringUTF
// uses *modified* UTF-8 and mangles 4-byte codepoints (emoji), so we don't use it.
std::u16string utf8_to_utf16(const std::string& s) {
    std::u16string out;
    out.reserve(s.size());
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp; int len;
        if      (c < 0x80)          { cp = c;        len = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; len = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; len = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; len = 4; }
        else                         { cp = 0xFFFD;   len = 1; }
        if (i + static_cast<size_t>(len) > n) { cp = 0xFFFD; len = 1; }
        else for (int k = 1; k < len; ++k) cp = (cp << 6) | (static_cast<unsigned char>(s[i + k]) & 0x3F);
        i += len;
        if (cp <= 0xFFFF) {
            out.push_back(static_cast<char16_t>(cp));
        } else {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 | (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 | (cp & 0x3FF)));
        }
    }
    return out;
}

void log_cb(ggml_log_level level, const char* text, void* /*ud*/) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: return; // suppress per-tensor load spam
        default:                   prio = ANDROID_LOG_INFO;  break;
    }
    __android_log_print(prio, "ratherllm.llama", "%s", text ? text : "");
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    llama_log_set(log_cb, nullptr);
    ggml_log_set(log_cb, nullptr);
    llama_backend_init();
    LOGI("libratherllm loaded; %s", llama_print_system_info());
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_kotlin_ratherllm_NativeBridge_loadModel(JNIEnv* env, jobject, jstring jpath,
                                                 jint nCtx, jint nThreads, jint nThreadsBatch,
                                                 jint nGpuLayers, jboolean useMlock,
                                                 jobject progressCb) {
    const std::string path = jstr(env, jpath);
    LoadParams lp;
    lp.n_ctx           = nCtx > 0 ? nCtx : 4096;
    lp.n_threads       = nThreads > 0 ? nThreads : 4;
    lp.n_threads_batch = nThreadsBatch > 0 ? nThreadsBatch : lp.n_threads;
    lp.n_gpu_layers    = nGpuLayers;
    lp.use_mlock       = (useMlock == JNI_TRUE);

    // Optional load-progress callback (fires on this calling thread during load).
    std::function<void(float)> on_prog;
    if (progressCb) {
        jclass pc = env->GetObjectClass(progressCb);
        jmethodID pm = env->GetMethodID(pc, "onProgress", "(F)V");
        if (pm) {
            on_prog = [env, progressCb, pm](float p) {
                env->CallVoidMethod(progressCb, pm, static_cast<jfloat>(p));
                if (env->ExceptionCheck()) env->ExceptionClear();
            };
        }
    }

    int rc = RC_OK;
    LlamaEngine* raw = LlamaEngine::load(path, lp, rc, on_prog);
    if (!raw) return static_cast<jlong>(rc);

    std::lock_guard<std::mutex> lk(g_mtx);
    const jlong handle = g_next_handle++;
    g_engines[handle] = std::shared_ptr<LlamaEngine>(raw);
    return handle;
}

JNIEXPORT void JNICALL
Java_com_kotlin_ratherllm_NativeBridge_freeModel(JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<LlamaEngine> e;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        auto it = g_engines.find(handle);
        if (it != g_engines.end()) { e = it->second; g_engines.erase(it); }
    }
    if (e) e->cancel(); // let any in-flight generate exit; engine frees on last ref
}

JNIEXPORT jstring JNICALL
Java_com_kotlin_ratherllm_NativeBridge_getModelInfo(JNIEnv* env, jobject, jstring jpath) {
    const std::string path = jstr(env, jpath);
    ModelInfo mi;
    if (LlamaEngine::read_info(path, mi) != RC_OK || !mi.ok) return nullptr;
    json j = {
        {"arch", mi.arch}, {"name", mi.name}, {"desc", mi.desc}, {"quant", mi.quant},
        {"nParams", mi.n_params}, {"sizeBytes", mi.size_bytes},
        {"nCtxTrain", mi.n_ctx_train}, {"hasTemplate", mi.has_template},
    };
    return env->NewStringUTF(j.dump().c_str());
}

JNIEXPORT jint JNICALL
Java_com_kotlin_ratherllm_NativeBridge_generate(JNIEnv* env, jobject, jlong handle,
                                                jstring jrequest, jobject callback) {
    std::shared_ptr<LlamaEngine> e = lookup(handle);
    if (!e) return RC_NO_MODEL;

    std::vector<GenMessage> msgs;
    GenParams gp;
    try {
        json j = json::parse(jstr(env, jrequest));
        for (const auto& m : j.at("messages")) {
            msgs.push_back(GenMessage{ m.at("role").get<std::string>(),
                                       m.at("content").get<std::string>() });
        }
        if (j.contains("params")) {
            const auto& p = j["params"];
            gp.max_tokens     = p.value("maxTokens", gp.max_tokens);
            gp.temperature    = p.value("temperature", gp.temperature);
            gp.top_k          = p.value("topK", gp.top_k);
            gp.top_p          = p.value("topP", gp.top_p);
            gp.min_p          = p.value("minP", gp.min_p);
            gp.repeat_penalty = p.value("repeatPenalty", gp.repeat_penalty);
            gp.seed           = p.value("seed", gp.seed);
        }
    } catch (const std::exception& ex) {
        LOGE("bad request json: %s", ex.what());
        return RC_BAD_PARAMS;
    }
    if (msgs.empty()) return RC_BAD_PARAMS;

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    if (!onToken) { LOGE("TokenCallback.onToken not found"); return RC_BAD_PARAMS; }

    auto piece_cb = [&](const std::string& piece) -> bool {
        std::u16string u16 = utf8_to_utf16(piece);
        jstring js = env->NewString(reinterpret_cast<const jchar*>(u16.data()),
                                    static_cast<jsize>(u16.size()));
        jboolean keep = env->CallBooleanMethod(callback, onToken, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return keep == JNI_TRUE;
    };

    return e->generate(msgs, gp, piece_cb);
}

JNIEXPORT void JNICALL
Java_com_kotlin_ratherllm_NativeBridge_cancel(JNIEnv*, jobject, jlong handle) {
    if (auto e = lookup(handle)) e->cancel();
}

JNIEXPORT jstring JNICALL
Java_com_kotlin_ratherllm_NativeBridge_lastTimings(JNIEnv* env, jobject, jlong handle) {
    auto e = lookup(handle);
    if (!e) return nullptr;
    GenStats s = e->last_stats();
    const double pf_tps = s.prefill_ms > 0 ? s.n_prompt  * 1000.0 / s.prefill_ms : 0.0;
    const double dc_tps = s.decode_ms  > 0 ? s.n_decoded * 1000.0 / s.decode_ms  : 0.0;
    json j = {
        {"nPrompt", s.n_prompt}, {"nDecoded", s.n_decoded},
        {"prefillMs", s.prefill_ms}, {"decodeMs", s.decode_ms},
        {"prefillTokPerSec", pf_tps}, {"decodeTokPerSec", dc_tps},
        {"stoppedEog", s.stopped_eog}, {"cancelled", s.cancelled},
    };
    return env->NewStringUTF(j.dump().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_kotlin_ratherllm_NativeBridge_systemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
