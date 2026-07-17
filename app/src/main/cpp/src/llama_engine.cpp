// =============================================================================
//  ratherllm :: llama_engine.cpp
// =============================================================================
#include "llama_engine.h"

#include "llama.h"
#include "common.h"
#include "chat.h"

#include <android/log.h>

#include <chrono>
#include <cstring>

#define TAG "ratherllm.eng"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

namespace rllm {

using clock_t_ = std::chrono::steady_clock;
static inline clock_t_::time_point now() { return clock_t_::now(); }
static inline double ms_since(clock_t_::time_point t0) {
    return std::chrono::duration<double, std::milli>(now() - t0).count();
}

// Longest prefix of `s` that ends on a UTF-8 codepoint boundary. Trailing bytes
// of an incomplete multi-byte sequence are held back so we never hand a broken
// codepoint to NewStringUTF (which would corrupt streamed non-ASCII text).
static size_t utf8_complete_len(const std::string& s) {
    const size_t n = s.size();
    if (n == 0) return 0;
    const size_t maxback = n < 4 ? n : 4;
    for (size_t b = 1; b <= maxback; ++b) {
        unsigned char c = static_cast<unsigned char>(s[n - b]);
        if ((c & 0xC0) != 0x80) {               // a lead byte (or ASCII)
            int need;
            if      ((c & 0x80) == 0x00) need = 1;
            else if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            else                          need = 1; // invalid lead -> single byte
            return (static_cast<size_t>(need) <= b) ? n : (n - b);
        }
    }
    return n; // all continuation bytes (unexpected) -> flush to avoid stalling
}

static void fill_info(const llama_model* model, ModelInfo& out) {
    char buf[1024];
    auto meta = [&](const char* key) -> std::string {
        int n = llama_model_meta_val_str(model, key, buf, sizeof(buf));
        return n > 0 ? std::string(buf, static_cast<size_t>(n)) : std::string();
    };
    out.arch = meta("general.architecture");
    out.name = meta("general.name");
    if (llama_model_desc(model, buf, sizeof(buf)) > 0) out.desc = buf;
    if (const char* q = llama_ftype_name(llama_model_ftype(model))) out.quant = q;
    out.n_params    = llama_model_n_params(model);
    out.size_bytes  = llama_model_size(model);
    out.n_ctx_train = llama_model_n_ctx_train(model);
    out.has_template = (llama_model_chat_template(model, nullptr) != nullptr);
    out.ok = true;
}

LlamaEngine* LlamaEngine::load(const std::string& path, const LoadParams& p, int& rc) {
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = p.n_gpu_layers;
    mp.use_mmap     = true;
    mp.use_mlock    = p.use_mlock;

    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("llama_model_load_from_file failed for %s", path.c_str());
        rc = RC_LOAD_FAILED;
        return nullptr;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(p.n_ctx);
    cp.n_batch         = 512;
    cp.n_ubatch        = 512;
    cp.n_threads       = p.n_threads;
    cp.n_threads_batch = p.n_threads_batch;

    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("llama_init_from_model failed (n_ctx=%d)", p.n_ctx);
        llama_model_free(model);
        rc = RC_OOM;
        return nullptr;
    }

    auto* e = new LlamaEngine();
    e->model_ = model;
    e->ctx_   = ctx;
    e->vocab_ = llama_model_get_vocab(model);
    e->n_ctx_ = static_cast<int>(llama_n_ctx(ctx));
    rc = RC_OK;

    ModelInfo mi = e->info();
    LOGI("loaded '%s' arch=%s quant=%s params=%llu n_ctx=%d (train %d)",
         mi.desc.c_str(), mi.arch.c_str(), mi.quant.c_str(),
         (unsigned long long) mi.n_params, e->n_ctx_, mi.n_ctx_train);
    return e;
}

LlamaEngine::~LlamaEngine() {
    if (ctx_)   { llama_free(ctx_);        ctx_ = nullptr; }
    if (model_) { llama_model_free(model_); model_ = nullptr; }
}

ModelInfo LlamaEngine::info() const {
    ModelInfo out;
    if (model_) fill_info(model_, out);
    return out;
}

int LlamaEngine::read_info(const std::string& path, ModelInfo& out) {
    llama_model_params mp = llama_model_default_params();
    mp.vocab_only = true;
    mp.use_mmap   = true;
    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) return RC_LOAD_FAILED;
    fill_info(model, out);
    llama_model_free(model);
    return RC_OK;
}

static llama_sampler* build_sampler(const GenParams& gp) {
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler* s = llama_sampler_chain_init(sp);

    if (gp.repeat_penalty != 1.0f) {
        llama_sampler_chain_add(s, llama_sampler_init_penalties(256, gp.repeat_penalty, 0.0f, 0.0f));
    }
    if (gp.temperature <= 0.0f) {
        llama_sampler_chain_add(s, llama_sampler_init_greedy());
    } else {
        if (gp.top_k > 0)   llama_sampler_chain_add(s, llama_sampler_init_top_k(gp.top_k));
        if (gp.top_p < 1.0f) llama_sampler_chain_add(s, llama_sampler_init_top_p(gp.top_p, 1));
        if (gp.min_p > 0.0f) llama_sampler_chain_add(s, llama_sampler_init_min_p(gp.min_p, 1));
        llama_sampler_chain_add(s, llama_sampler_init_temp(gp.temperature));
        const uint32_t seed = gp.seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(gp.seed);
        llama_sampler_chain_add(s, llama_sampler_init_dist(seed));
    }
    return s;
}

int LlamaEngine::generate(const std::vector<GenMessage>& msgs, const GenParams& gp, const PieceCallback& cb) {
    std::lock_guard<std::mutex> lk(gen_mtx_);
    cancel_.store(false, std::memory_order_relaxed);
    last_stats_ = GenStats{};

    if (!ctx_ || !model_) return RC_NO_MODEL;

    // ---- build chat-templated prompt (real GGUF Jinja template via minja) ----
    std::string prompt;
    try {
        common_chat_templates_ptr tmpls = common_chat_templates_init(model_, "");
        if (!tmpls) { LOGE("no chat template"); return RC_TEMPLATE_FAILED; }
        common_chat_templates_inputs in;
        in.use_jinja = true;
        in.add_generation_prompt = true;
        in.messages.reserve(msgs.size());
        for (const auto& m : msgs) {
            common_chat_msg cm;
            cm.role = m.role;
            cm.content = m.content;
            in.messages.push_back(std::move(cm));
        }
        common_chat_params cp = common_chat_templates_apply(tmpls.get(), in);
        prompt = std::move(cp.prompt);
    } catch (const std::exception& e) {
        LOGE("chat template apply failed: %s", e.what());
        return RC_TEMPLATE_FAILED;
    } catch (...) {
        LOGE("chat template apply failed (unknown)");
        return RC_TEMPLATE_FAILED;
    }

    // ---- tokenize (template already includes BOS/special tokens as text) ----
    std::vector<llama_token> tokens = common_tokenize(vocab_, prompt, /*add_special=*/false, /*parse_special=*/true);
    if (tokens.empty()) return RC_BAD_PARAMS;
    if (static_cast<int>(tokens.size()) >= n_ctx_) {
        LOGW("prompt %zu tokens >= n_ctx %d", tokens.size(), n_ctx_);
        return RC_CONTEXT_OVERFLOW;
    }
    last_stats_.n_prompt = static_cast<int>(tokens.size());

    // ---- fresh KV per request (stateless multi-turn: full history re-sent) ----
    llama_memory_clear(llama_get_memory(ctx_), true);

    // ---- prefill ----
    const auto t_pf0 = now();
    {
        llama_batch b = llama_batch_get_one(tokens.data(), static_cast<int>(tokens.size()));
        const int dr = llama_decode(ctx_, b);
        if (dr != 0) {
            LOGE("prefill decode rc=%d", dr);
            return dr == 1 ? RC_CONTEXT_OVERFLOW : RC_DECODE_FAILED;
        }
    }
    last_stats_.prefill_ms = ms_since(t_pf0);

    // ---- decode loop ----
    llama_sampler* smpl = build_sampler(gp);
    const int budget = gp.max_tokens > 0 ? gp.max_tokens
                                         : (n_ctx_ - static_cast<int>(tokens.size()));
    std::string pending;
    int rc = RC_OK;
    const auto t_dec0 = now();

    for (int i = 0; i < budget; ++i) {
        if (cancel_.load(std::memory_order_relaxed)) { last_stats_.cancelled = true; break; }

        llama_token id = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab_, id)) { last_stats_.stopped_eog = true; break; }

        pending += common_token_to_piece(vocab_, id, /*special=*/false);
        last_stats_.n_decoded++;

        const size_t emit_len = utf8_complete_len(pending);
        bool keep_going = true;
        if (emit_len > 0) {
            keep_going = cb(pending.substr(0, emit_len));
            pending.erase(0, emit_len);
        }
        if (!keep_going) { last_stats_.cancelled = true; break; }

        llama_batch nb = llama_batch_get_one(&id, 1);
        const int dr = llama_decode(ctx_, nb);
        if (dr != 0) {
            rc = (dr == 1) ? RC_CONTEXT_OVERFLOW : RC_DECODE_FAILED;
            LOGW("decode during gen rc=%d at %d", dr, i);
            break;
        }
    }
    last_stats_.decode_ms = ms_since(t_dec0);
    llama_sampler_free(smpl);

    LOGI("gen done: prompt=%d prefill=%.1fms (%.1f tok/s) decode=%d %.1fms (%.1f tok/s) eog=%d cancel=%d",
         last_stats_.n_prompt, last_stats_.prefill_ms,
         last_stats_.prefill_ms > 0 ? last_stats_.n_prompt * 1000.0 / last_stats_.prefill_ms : 0.0,
         last_stats_.n_decoded, last_stats_.decode_ms,
         last_stats_.decode_ms > 0 ? last_stats_.n_decoded * 1000.0 / last_stats_.decode_ms : 0.0,
         (int) last_stats_.stopped_eog, (int) last_stats_.cancelled);
    return rc;
}

} // namespace rllm
