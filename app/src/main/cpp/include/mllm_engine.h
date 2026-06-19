// =============================================================================
//  ratherllm :: mllm_engine.h
//
//  On-device MLLM inference core: owns the memory-locked GGUF weight arena,
//  the KleidiAI-accelerated quantized matmul facade, the KV cache, tokenizer,
//  sampler, and the prime-core-pinned autoregressive decode loop. Generated
//  tokens are pushed straight into the UDS publish() path via TokenSink.
// =============================================================================
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace ratherllm {

// -----------------------------------------------------------------------------
// MemoryArena : mmap + mlock + madvise residency manager (anti-LMK).
// -----------------------------------------------------------------------------
    class MemoryArena {
    public:
        MemoryArena() = default;
        ~MemoryArena();

        MemoryArena(const MemoryArena&)            = delete;
        MemoryArena& operator=(const MemoryArena&) = delete;

        // mmap a file read-only (private). Returns 0 / -errno.
        int map_file(const std::string& path);

        // mlock + MADV_WILLNEED + MADV_DONTDUMP over [base,len). On mlock failure
        // (e.g. RLIMIT_MEMLOCK / EPERM) performs a defensive page-touch pre-fault.
        // Returns 0 when locked, -errno when only pre-faulted (degraded residency).
        int lock_and_prefault(void* base, size_t len);

        // Lock + pre-fault the entire mapped region.
        int lock_all();

        // Idempotent teardown: munlock + munmap + close fd.
        void release();

        void*  base() const noexcept { return base_; }
        size_t size() const noexcept { return size_; }
        bool   locked() const noexcept { return locked_; }

        // Best-effort raise of RLIMIT_MEMLOCK to its hard limit. Returns 0 / -errno.
        static int raise_memlock_limit();

    private:
        int     fd_     = -1;
        void*   base_   = nullptr;
        size_t  size_   = 0;
        void*   locked_base_ = nullptr;
        size_t  locked_len_  = 0;
        bool    locked_ = false;
    };

// -----------------------------------------------------------------------------
// KleidiMatmul : clean facade over KleidiAI SME2/i8mm INT4/INT8 micro-kernels,
// with a correct scalar fallback when RLLM_HAVE_KLEIDIAI is not defined.
// -----------------------------------------------------------------------------
    enum class QuantType : uint8_t {
        F32  = 0,
        F16  = 1,
        Q4_0 = 2, // INT4, 32-wide blocks, single f16 scale, zero-point 8
        Q8_0 = 8, // INT8, 32-wide blocks, single f16 scale
    };

// A weight matrix W with logical shape [n, k] (row-major), y = x * W^T.
    struct QWeight {
        const void* data   = nullptr; // GGUF block data (scalar) or packed (KleidiAI)
        void*       packed = nullptr; // KleidiAI-packed buffer (owned by arena/heap)
        QuantType   qt     = QuantType::Q4_0;
        int         n      = 0;       // output features (rows)
        int         k      = 0;       // input features (cols), multiple of 32
        bool        is_packed = false;
    };

    class KleidiMatmul {
    public:
        KleidiMatmul();

        bool sme2_available() const noexcept { return sme2_; }
        bool i8mm_available() const noexcept { return i8mm_; }

        // Bytes needed to pre-pack `w` for the accelerated path (0 in scalar mode).
        size_t packed_size(const QWeight& w) const;

        // Pre-pack weights into `dst` (size >= packed_size). No-op in scalar mode.
        // Returns 0 / -errno. On success sets w.packed / w.is_packed.
        int prepare(QWeight& w, void* dst) const;

        // out[m*n] = clamp( lhs[m*k] * W^T ), W = `w` (shape [n,k]).
        // Returns 0 / -errno.
        int matmul(const QWeight& w, int m, const float* lhs, float* out,
                   float clamp_min, float clamp_max) const;

    private:
        int  matmul_scalar(const QWeight& w, int m, const float* lhs, float* out,
                           float lo, float hi) const;
        bool sme2_ = false;
        bool i8mm_ = false;
    };

// -----------------------------------------------------------------------------
// Engine configuration / parameters.
// -----------------------------------------------------------------------------
    struct EngineConfig {
        std::string model_path;                 // GGUF weight file (mmap-able)
        bool        lock_weights    = true;     // mlock the weight arena
        bool        use_neuropilot  = false;    // (wired by neuropilot_adapter later)
        uint32_t    max_context     = 4096;     // clamps GGUF n_ctx
        int         prime_core_hint = -1;       // -1 => detect; else force this CPU
        bool        pin_decode      = true;     // pin decode thread to prime core
        size_t      work_queue_cap  = 64;       // bounded submit queue
    };

    struct GenParams {
        uint32_t max_tokens  = 512;
        float    temperature = 0.8f;
        uint32_t top_k       = 40;   // 0 => disabled
        float    top_p       = 0.95f; // 1.0 => disabled
        uint64_t seed        = 0;    // 0 => nondeterministic
    };

// Token sink (wired to UdsServer::publish). Returns false on backpressure;
// the decode loop then yields/retries so no token is dropped under connection.
    using TokenSink = std::function<bool(uint64_t request_id,
                                         int32_t      token_id,
                                         const std::string& text,
                                         bool         final)>;

// -----------------------------------------------------------------------------
// MllmEngine
// -----------------------------------------------------------------------------
    class MllmEngine {
    public:
        explicit MllmEngine(EngineConfig cfg);
        ~MllmEngine();

        MllmEngine(const MllmEngine&)            = delete;
        MllmEngine& operator=(const MllmEngine&) = delete;

        // mmap + lock weights, parse GGUF, build tokenizer/KV cache, spawn the
        // pinned decode thread. Returns 0 / -errno.
        int  load();

        void set_sink(TokenSink sink);

        // Enqueue a generation request (non-blocking). Returns false if the queue
        // is full or the engine is shutting down.
        bool submit(uint64_t request_id, const std::string& prompt,
                    const std::string& image_uri, const GenParams& params);

        // Cooperative cancel of an in-flight or queued request.
        void cancel(uint64_t request_id);

        // Stop the decode thread and release all resources (idempotent).
        void shutdown();

        bool running() const noexcept { return running_.load(std::memory_order_acquire); }

    private:
        struct Impl;                 // hides STL-heavy state from the header
        Impl* impl_ = nullptr;
    };

} // namespace ratherllm
