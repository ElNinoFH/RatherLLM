// =============================================================================
//  ratherllm :: jni_bridge.cpp  (Stage 1 skeleton)
//
//  Honest stub for com.kotlin.ratherllm.NativeBridge. The real inference engine
//  (llama.cpp) is integrated in Stage 2; until then every entry point returns a
//  well-defined "not implemented" result so the app builds, installs, runs, and
//  surfaces a truthful "engine not available yet" state instead of hanging.
// =============================================================================
#include <jni.h>

#include <android/log.h>

#define RLLM_TAG "ratherllm.jni"
#define RLLM_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  RLLM_TAG, __VA_ARGS__)
#define RLLM_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  RLLM_TAG, __VA_ARGS__)

// Mirror of com.kotlin.ratherllm.Rc.NOT_IMPLEMENTED.
static constexpr jint RC_NOT_IMPLEMENTED = -1000;

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    RLLM_LOGI("libratherllm loaded (Stage 1 skeleton, engine not implemented)");
    return JNI_VERSION_1_6;
}

// loadModel(path, nCtx, nThreads, nThreadsBatch, nGpuLayers, useMlock) -> handle/rc
JNIEXPORT jlong JNICALL
Java_com_kotlin_ratherllm_NativeBridge_loadModel(JNIEnv* /*env*/, jobject /*thiz*/,
                                                 jstring /*path*/, jint /*nCtx*/,
                                                 jint /*nThreads*/, jint /*nThreadsBatch*/,
                                                 jint /*nGpuLayers*/, jboolean /*useMlock*/) {
    RLLM_LOGW("loadModel: engine not implemented (Stage 1 skeleton)");
    return static_cast<jlong>(RC_NOT_IMPLEMENTED);
}

// freeModel(handle) -> void
JNIEXPORT void JNICALL
Java_com_kotlin_ratherllm_NativeBridge_freeModel(JNIEnv* /*env*/, jobject /*thiz*/,
                                                 jlong /*handle*/) {
}

// getModelInfo(path) -> String? (JSON) — null until Stage 2.
JNIEXPORT jstring JNICALL
Java_com_kotlin_ratherllm_NativeBridge_getModelInfo(JNIEnv* /*env*/, jobject /*thiz*/,
                                                    jstring /*path*/) {
    return nullptr;
}

// generate(handle, requestJson, callback) -> rc
JNIEXPORT jint JNICALL
Java_com_kotlin_ratherllm_NativeBridge_generate(JNIEnv* /*env*/, jobject /*thiz*/,
                                                jlong /*handle*/, jstring /*requestJson*/,
                                                jobject /*callback*/) {
    RLLM_LOGW("generate: engine not implemented (Stage 1 skeleton)");
    return RC_NOT_IMPLEMENTED;
}

// cancel(handle) -> void
JNIEXPORT void JNICALL
Java_com_kotlin_ratherllm_NativeBridge_cancel(JNIEnv* /*env*/, jobject /*thiz*/,
                                              jlong /*handle*/) {
}

// systemInfo() -> String
JNIEXPORT jstring JNICALL
Java_com_kotlin_ratherllm_NativeBridge_systemInfo(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("engine not built yet (Stage 1 skeleton)");
}

} // extern "C"
