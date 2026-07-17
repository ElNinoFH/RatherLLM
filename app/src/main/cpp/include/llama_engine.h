// =============================================================================
//  ratherllm :: llama_engine.h
//
//  Thin C++ wrapper over llama.cpp: model load (mmap, no-mlock), chat-templated
//  multi-turn generation with a llama.cpp sampler chain, cooperative cancel, and
//  cheap GGUF metadata reads. One llama_context per engine; generation is
//  serialized by an internal mutex (single in-flight request by design).
// =============================================================================
#pragma once

#include <atomic>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_vocab;

namespace rllm {

// Result codes mirrored in com.kotlin.ratherllm.Rc.
enum Rc : int {
    RC_OK               = 0,
    RC_MODEL_MISSING    = -1001,
    RC_MODEL_UNREADABLE = -1002,
    RC_NOT_REGULAR      = -1003,
    RC_MODEL_EMPTY      = -1004,
    RC_MODEL_TOO_SMALL  = -1005,
    RC_BAD_MAGIC        = -1006,
    RC_LOAD_FAILED      = -2001,
    RC_BAD_PARAMS       = -2002,
    RC_UNSUPPORTED      = -2003,
    RC_OOM              = -2004,
    RC_CONTEXT_OVERFLOW = -2005,
    RC_DECODE_FAILED    = -2006,
    RC_TEMPLATE_FAILED  = -2007,
    RC_NO_MODEL         = -2008,
};

struct LoadParams {
    int  n_ctx           = 4096;
    int  n_threads       = 4;
    int  n_threads_batch = 4;
    int  n_gpu_layers    = 0;
    bool use_mlock       = false;
};

struct GenMessage {
    std::string role;    // "system" | "user" | "assistant"
    std::string content;
};

struct GenParams {
    int   max_tokens     = 512;
    float temperature    = 1.0f;   // Gemma 3 recommended
    int   top_k          = 64;
    float top_p          = 0.95f;
    float min_p          = 0.05f;
    float repeat_penalty = 1.1f;
    int   seed           = -1;   // <0 => random
};

struct GenStats {
    int    n_prompt    = 0;
    int    n_decoded   = 0;
    double prefill_ms  = 0.0;
    double decode_ms   = 0.0;
    bool   stopped_eog = false;
    bool   cancelled   = false;
};

struct ModelInfo {
    std::string arch;
    std::string name;
    std::string desc;
    std::string quant;
    uint64_t    n_params    = 0;
    uint64_t    size_bytes  = 0;
    int         n_ctx_train = 0;
    bool        has_template = false;
    bool        ok          = false;
};

// Per-token sink. Return false to request a cooperative stop.
using PieceCallback = std::function<bool(const std::string&)>;

class LlamaEngine {
public:
    // Loads a model. On failure returns nullptr and sets rc. On success rc == RC_OK.
    // on_progress (optional) is invoked with a 0..1 fraction during weight load.
    static LlamaEngine* load(const std::string& path, const LoadParams& p, int& rc,
                             const std::function<void(float)>& on_progress = {});
    ~LlamaEngine();

    // Runs one generation (stateless: the full conversation is re-templated and
    // re-prefilled each call). Blocks on the calling thread; invokes cb per
    // UTF-8-complete piece. Returns RC_OK or a negative code.
    int generate(const std::vector<GenMessage>& msgs, const GenParams& gp, const PieceCallback& cb);

    // Signals the in-flight generate() to stop at the next token boundary.
    void cancel() { cancel_.store(true, std::memory_order_relaxed); }

    ModelInfo info() const;
    GenStats  last_stats() const { return last_stats_; }

    // Reads GGUF metadata by loading vocab-only (no weight allocation), then frees.
    static int read_info(const std::string& path, ModelInfo& out);

private:
    LlamaEngine() = default;
    LlamaEngine(const LlamaEngine&) = delete;
    LlamaEngine& operator=(const LlamaEngine&) = delete;

    llama_model*        model_ = nullptr;
    llama_context*      ctx_   = nullptr;
    const llama_vocab*  vocab_ = nullptr;
    int                 n_ctx_ = 0;
    int                 n_ctx_train_ = 0;

    std::atomic<bool>   cancel_{false};
    std::mutex          gen_mtx_;
    GenStats            last_stats_;
};

} // namespace rllm
