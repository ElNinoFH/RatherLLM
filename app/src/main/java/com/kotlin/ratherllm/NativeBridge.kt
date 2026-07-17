package com.kotlin.ratherllm

import androidx.annotation.Keep

/**
 * JNI facade for the native libratherllm.so inference engine (llama.cpp).
 *
 * Stage 1: these are honest stubs — [loadModel] returns [Rc.NOT_IMPLEMENTED].
 * Stage 2 fills in the real llama.cpp-backed implementation behind the same
 * surface, so the Kotlin/service layers do not have to change shape.
 */
object NativeBridge {

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("ratherllm")
            loaded = true
        } catch (t: UnsatisfiedLinkError) {
            loaded = false
            loadError = t.message
        }
    }

    /** True once libratherllm.so has been successfully loaded. */
    val isLibraryLoaded: Boolean get() = loaded

    /**
     * Memory-map + prepare a GGUF model for inference.
     * @return a positive opaque handle on success, or a negative [Rc] code.
     */
    external fun loadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        nGpuLayers: Int,
        useMlock: Boolean,
    ): Long

    /** Release a handle returned by [loadModel]. Idempotent for handle 0. */
    external fun freeModel(handle: Long)

    /** Read GGUF metadata without loading weights. Returns a JSON string or null. */
    external fun getModelInfo(path: String): String?

    /**
     * Run a generation. [requestJson] carries the templated conversation and
     * sampling parameters; [callback] receives token pieces on the calling
     * thread. Blocks until generation ends, is cancelled, or errors.
     * @return 0 on success or a negative [Rc] code.
     */
    external fun generate(handle: Long, requestJson: String, callback: TokenCallback): Int

    /** Cooperatively cancel an in-flight [generate] on [handle]. Thread-safe. */
    external fun cancel(handle: Long)

    /** JSON timings for the most recent [generate] on [handle] (tok/s, counts), or null. */
    external fun lastTimings(handle: Long): String?

    /** llama.cpp/ggml build + detected CPU features, for diagnostics. */
    external fun systemInfo(): String
}

/**
 * Per-token sink invoked by native code on the thread that called [NativeBridge.generate].
 * Kept (R8) because it is resolved reflectively across the JNI boundary.
 */
@Keep
fun interface TokenCallback {
    /** @return true to keep decoding, false to request a cooperative stop. */
    @Keep
    fun onToken(piece: String): Boolean
}
