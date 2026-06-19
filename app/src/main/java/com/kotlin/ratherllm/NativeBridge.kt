package com.kotlin.ratherllm

/**
 * JNI facade for the native libratherllm.so inference engine.
 *
 * Lifecycle:
 *   1. [startEngine] — mmap + mlock the GGUF weights, spin up the prime-core
 *      pinned decode thread, and start the abstract-namespace UDS epoll loop.
 *   2. Stream tokens via [RatherLlmClient] (connects over the UDS socket).
 *   3. [stopEngine] — stop/join the decode thread and release the memory arena
 *      BEFORE the UDS server is closed (strict, leak-free teardown order).
 */
object NativeBridge {

    @Volatile
    private var loaded: Boolean = false

    init {
        try {
            System.loadLibrary("ratherllm")
            loaded = true
        } catch (t: UnsatisfiedLinkError) {
            loaded = false
        }
    }

    /** True once libratherllm.so has been successfully loaded. */
    val isLibraryLoaded: Boolean get() = loaded

    /**
     * Initialize and start the native engine + UDS server.
     *
     * @param modelPath     absolute path to the GGUF weight file.
     * @param useNeuropilot request MediaTek APU offload (falls back to CPU if absent).
     * @return 0 on success, or a negative errno on failure.
     */
    external fun startEngine(modelPath: String, useNeuropilot: Boolean): Int

    /** Stop the engine and close the UDS server (idempotent, strict order). */
    external fun stopEngine()

    /** @return true while the engine + IO loop are running. */
    external fun isRunning(): Boolean
}
