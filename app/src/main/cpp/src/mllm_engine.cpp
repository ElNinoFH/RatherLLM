// =============================================================================
//  ratherllm :: mllm_engine.cpp
//
//  Production implementation of the MLLM inference core.
//
//  Invariants:
//    (1) Anti-LMK residency : mlock + MADV_WILLNEED + MADV_DONTDUMP, with a
//        defensive page-touch pre-fault fallback on RLIMIT_MEMLOCK failure.
//    (2) Pinned decode loop : thread locked to the prime core (hardware_affinity)
//        pushes each token into the lock-free SPSC ring via the TokenSink and
//        triggers UdsServer::publish() asynchronously.
//    (3) Strict lifecycle   : munlock + munmap + fd close on teardown / failure.
// =============================================================================
#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif

#include "../include/mllm_engine.h"
#include "../include/hardware_affinity.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <random>
#include <set>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#if defined(GGML_USE_KLEIDIAI)
//  --- KleidiAI seam ---------------------------------------------------------
//  Pull in the ukernel + packing headers for the chosen SME2 / i8mm variant.
//  Swap the variant include + symbol set below to match your KleidiAI build.
#include "kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi4c32p/kai_matmul_clamp_f32_qai8dxp_qsi4c32p_interface.h"
#include "kai/ukernels/matmul/pack/kai_lhs_quant_pack_qai8dxp_f32.h"
#include "kai/ukernels/matmul/pack/kai_rhs_pack_nxk_qsi4c32p_qs4cxs1s0.h"
#endif

#if defined(__ANDROID__)
#include <android/log.h>
#define RLLM_ENG_TAG "ratherllm.eng"
#define RLLM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, RLLM_ENG_TAG, __VA_ARGS__)
#define RLLM_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  RLLM_ENG_TAG, __VA_ARGS__)
#define RLLM_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  RLLM_ENG_TAG, __VA_ARGS__)
#else
#define RLLM_LOGE(...) do { std::fprintf(stderr, "[ratherllm.eng][E] " __VA_ARGS__); std::fprintf(stderr, "
"); } while (0)
#define RLLM_LOGW(...) do { std::fprintf(stderr, "[ratherllm.eng][W] " __VA_ARGS__); std::fprintf(stderr, "
"); } while (0)
#define RLLM_LOGI(...) do { std::fprintf(stderr, "[ratherllm.eng][I] " __VA_ARGS__); std::fprintf(stderr, "
"); } while (0)
#endif

namespace ratherllm {

// =============================================================================
//  Low-level numeric helpers
// =============================================================================
    namespace {

        constexpr int QK = 32; // block size for Q4_0 / Q8_0

#pragma pack(push, 1)
        struct block_q4_0 { uint16_t d; uint8_t qs[QK / 2]; };
        struct block_q8_0 { uint16_t d; int8_t  qs[QK]; };
#pragma pack(pop)

        inline float f16_to_f32(uint16_t h) {
            const uint32_t sign = (uint32_t)(h & 0x8000u) << 16;
            uint32_t exp = (h >> 10) & 0x1Fu;
            uint32_t man = h & 0x3FFu;
            uint32_t f;
            if (exp == 0) {
                if (man == 0) { f = sign; }
                else {
                    exp = 127 - 15 + 1;
                    while ((man & 0x400u) == 0) { man <<= 1; --exp; }
                    man &= 0x3FFu;
                    f = sign | (exp << 23) | (man << 13);
                }
            } else if (exp == 0x1F) {
                f = sign | 0x7F800000u | (man << 13);
            } else {
                f = sign | ((exp - 15 + 127) << 23) | (man << 13);
            }
            float r; std::memcpy(&r, &f, 4); return r;
        }

        inline void dequant_block_q4_0(const block_q4_0* b, float* out) {
            const float d = f16_to_f32(b->d);
            for (int j = 0; j < QK / 2; ++j) {
                const float x0 = (float)(b->qs[j] & 0x0F) - 8.0f;
                const float x1 = (float)(b->qs[j] >> 4)   - 8.0f;
                out[j]          = x0 * d;
                out[j + QK / 2] = x1 * d;
            }
        }

        inline void dequant_block_q8_0(const block_q8_0* b, float* out) {
            const float d = f16_to_f32(b->d);
            for (int j = 0; j < QK; ++j) out[j] = (float)b->qs[j] * d;
        }

        inline float clampf(float v, float lo, float hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

    } // namespace

// =============================================================================
//  MemoryArena
// =============================================================================
    MemoryArena::~MemoryArena() { release(); }

    int MemoryArena::raise_memlock_limit() {
        rlimit rl{};
        if (::getrlimit(RLIMIT_MEMLOCK, &rl) != 0) return -errno;
        if (rl.rlim_cur < rl.rlim_max || rl.rlim_max == RLIM_INFINITY) {
            rlimit want = rl;
            want.rlim_cur = rl.rlim_max;
            if (::setrlimit(RLIMIT_MEMLOCK, &want) != 0) return -errno;
        }
        return 0;
    }

    int MemoryArena::map_file(const std::string& path) {
        release();

        fd_ = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd_ < 0) { const int e = errno; RLLM_LOGE("open(%s): %s", path.c_str(), std::strerror(e)); return -e; }

        struct stat st{};
        if (::fstat(fd_, &st) != 0) { const int e = errno; release(); return -e; }
        if (st.st_size <= 0)        { release(); return -EINVAL; }
        size_ = static_cast<size_t>(st.st_size);

        base_ = ::mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd_, 0);
        if (base_ == MAP_FAILED) {
            const int e = errno; base_ = nullptr; release();
            RLLM_LOGE("mmap(%s, %zu): %s", path.c_str(), size_, std::strerror(e));
            return -e;
        }
        RLLM_LOGI("mapped %s (%zu bytes) at %p", path.c_str(), size_, base_);
        return 0;
    }

    int MemoryArena::lock_and_prefault(void* base, size_t len) {
        if (base == nullptr || len == 0) return -EINVAL;

        // (a) Hint the kernel to read pages ahead; (b) exclude from core dumps.
        if (::madvise(base, len, MADV_WILLNEED) != 0)
            RLLM_LOGW("madvise(WILLNEED): %s", std::strerror(errno));
#if defined(MADV_DONTDUMP)
        if (::madvise(base, len, MADV_DONTDUMP) != 0)
            RLLM_LOGW("madvise(DONTDUMP): %s", std::strerror(errno));
#endif

        // (c) Physically pin so PSI pressure cannot reclaim and LMK is less likely.
        if (::mlock(base, len) == 0) {
            locked_base_ = base; locked_len_ = len; locked_ = true;
            RLLM_LOGI("mlock OK: %zu bytes pinned resident", len);
            return 0;
        }

        const int e = errno; // EPERM / ENOMEM / RLIMIT_MEMLOCK
        RLLM_LOGW("mlock failed (%s); falling back to defensive page-touch pre-fault",
                  std::strerror(e));

        // (d) Defensive fallback: touch one byte per page to force population so the
        //     weights are at least resident even if not locked.
        const size_t pg = static_cast<size_t>(::sysconf(_SC_PAGESIZE));
        volatile uint8_t sink = 0;
        volatile const uint8_t* p = static_cast<volatile const uint8_t*>(base);
        for (size_t off = 0; off < len; off += pg) sink ^= p[off];
        (void)sink;

        locked_ = false;
        return -e; // degraded residency: faulted-in but not locked
    }

    int MemoryArena::lock_all() {
        if (base_ == nullptr) return -EINVAL;
        return lock_and_prefault(base_, size_);
    }

    void MemoryArena::release() {
        if (locked_ && locked_base_) {
            if (::munlock(locked_base_, locked_len_) != 0)
                RLLM_LOGW("munlock: %s", std::strerror(errno));
            locked_ = false; locked_base_ = nullptr; locked_len_ = 0;
        }
        if (base_) {
            if (::munmap(base_, size_) != 0)
                RLLM_LOGW("munmap: %s", std::strerror(errno));
            base_ = nullptr; size_ = 0;
        }
        if (fd_ >= 0) {
            int rc; do { rc = ::close(fd_); } while (rc != 0 && errno == EINTR);
            fd_ = -1;
        }
    }

// =============================================================================
//  KleidiMatmul
// =============================================================================
    KleidiMatmul::KleidiMatmul() {
#if defined(__aarch64__)
        // getauxval-based feature flags would go here; the engine also gates by
    // KleidiAI availability. Default to the build-time capability.
  #if defined(GGML_USE_KLEIDIAI)
    sme2_ = true;  // assume SME2 path is linkable per CMake (GGML_CPU_KLEIDIAI)
    i8mm_ = true;
  #endif
#endif
    }

    size_t KleidiMatmul::packed_size(const QWeight& w) const {
#if defined(GGML_USE_KLEIDIAI)
        if (w.qt == QuantType::Q4_0) {
        // RHS packing footprint for the qsi4c32p layout (n x k, 32-block scales).
        return kai_get_rhs_packed_size_rhs_pack_nxk_qsi4c32p_qs4cxs1s0(
                   /*n=*/w.n, /*k=*/w.k, /*nr=*/8, /*kr=*/16, /*sr=*/2, /*bl=*/QK,
                   /*scale_dt=*/kai_datatype::kai_dt_bf16);
    }
#endif
        (void)w;
        return 0; // scalar path packs nothing
    }

    int KleidiMatmul::prepare(QWeight& w, void* dst) const {
#if defined(GGML_USE_KLEIDIAI)
        if (w.qt == QuantType::Q4_0 && dst != nullptr) {
        // Repack GGUF Q4_0 blocks into the ukernel's qsi4c32p RHS layout.
        // (rhs_pack consumes the int4 nibbles + per-block scales it derives
        //  from the GGUF block headers; see KleidiAI pack docs for the exact
        //  argument marshalling for your revision.)
        kai_run_rhs_pack_nxk_qsi4c32p_qs4cxs1s0(
            /*num_groups=*/1, /*n=*/w.n, /*k=*/w.k,
            /*nr=*/8, /*kr=*/16, /*sr=*/2, /*bl=*/QK,
            /*rhs=*/static_cast<const uint8_t*>(w.data),
            /*bias=*/nullptr, /*scale=*/nullptr,
            /*rhs_packed=*/dst, /*extra_bytes=*/0, /*params=*/nullptr);
        w.packed = dst; w.is_packed = true;
        return 0;
    }
#endif
        (void)w; (void)dst;
        return 0; // scalar path: nothing to do
    }

    int KleidiMatmul::matmul(const QWeight& w, int m, const float* lhs, float* out,
                             float lo, float hi) const {
        if (!lhs || !out || w.n <= 0 || w.k <= 0 || (w.k % QK) != 0) return -EINVAL;

#if defined(GGML_USE_KLEIDIAI)
        if (w.is_packed && w.qt == QuantType::Q4_0) {
        const auto& uk = kai_matmul_clamp_f32_qai8dxp_qsi4c32p_4x8x32_neon_i8mm;
        const size_t mr = uk.get_mr(), nr = uk.get_nr(), kr = uk.get_kr(), sr = uk.get_sr();

        // 1) Dynamic-quantize + pack the f32 LHS to qai8dxp.
        const size_t lhs_packed_sz =
            kai_get_lhs_packed_size_lhs_quant_pack_qai8dxp_f32((size_t)m, (size_t)w.k, mr, kr, sr);
        std::vector<uint8_t> lhs_packed(lhs_packed_sz);
        kai_run_lhs_quant_pack_qai8dxp_f32(
            (size_t)m, (size_t)w.k, mr, kr, sr, /*m_idx_start=*/0,
            lhs, /*lhs_stride=*/(size_t)w.k * sizeof(float), lhs_packed.data());

        // 2) Clamped matmul straight into `out` (f32, m x n).
        uk.run_matmul(
            (size_t)m, (size_t)w.n, (size_t)w.k, QK,
            lhs_packed.data(), w.packed, out,
            /*dst_stride_row=*/(size_t)w.n * sizeof(float),
            /*dst_stride_col=*/sizeof(float), lo, hi);
        return 0;
    }
#endif
        return matmul_scalar(w, m, lhs, out, lo, hi);
    }

    int KleidiMatmul::matmul_scalar(const QWeight& w, int m, const float* lhs,
                                    float* out, float lo, float hi) const {
        const int bpr = w.k / QK; // blocks per row
        float tmp[QK];

        for (int mi = 0; mi < m; ++mi) {
            const float* a = lhs + (size_t)mi * w.k;
            for (int j = 0; j < w.n; ++j) {
                float acc = 0.0f;
                if (w.qt == QuantType::Q4_0) {
                    const block_q4_0* row =
                            static_cast<const block_q4_0*>(w.data) + (size_t)j * bpr;
                    for (int b = 0; b < bpr; ++b) {
                        dequant_block_q4_0(&row[b], tmp);
                        const float* ap = a + b * QK;
                        for (int t = 0; t < QK; ++t) acc += ap[t] * tmp[t];
                    }
                } else if (w.qt == QuantType::Q8_0) {
                    const block_q8_0* row =
                            static_cast<const block_q8_0*>(w.data) + (size_t)j * bpr;
                    for (int b = 0; b < bpr; ++b) {
                        dequant_block_q8_0(&row[b], tmp);
                        const float* ap = a + b * QK;
                        for (int t = 0; t < QK; ++t) acc += ap[t] * tmp[t];
                    }
                } else if (w.qt == QuantType::F32) {
                    const float* row = static_cast<const float*>(w.data) + (size_t)j * w.k;
                    for (int t = 0; t < w.k; ++t) acc += a[t] * row[t];
                } else { // F16
                    const uint16_t* row = static_cast<const uint16_t*>(w.data) + (size_t)j * w.k;
                    for (int t = 0; t < w.k; ++t) acc += a[t] * f16_to_f32(row[t]);
                }
                out[(size_t)mi * w.n + j] = clampf(acc, lo, hi);
            }
        }
        return 0;
    }

// =============================================================================
//  GGUF parsing (metadata + tensor directory)
// =============================================================================
    namespace {

        enum gguf_type : uint32_t {
            GGUF_U8=0, GGUF_I8=1, GGUF_U16=2, GGUF_I16=3, GGUF_U32=4, GGUF_I32=5,
            GGUF_F32=6, GGUF_BOOL=7, GGUF_STR=8, GGUF_ARR=9, GGUF_U64=10, GGUF_I64=11,
            GGUF_F64=12,
        };

        struct TensorRef {
            const void* data = nullptr;
            QuantType   qt   = QuantType::F32;
            std::vector<uint64_t> dims;
            uint64_t    n() const { return dims.empty() ? 0 : dims.back(); }   // rows (out)
            uint64_t    k() const { return dims.empty() ? 0 : dims.front(); }  // cols (in)
        };

        struct ModelHParams {
            uint32_t n_vocab=0, n_embd=0, n_layer=0, n_head=0, n_head_kv=0, n_ff=0, n_ctx=0;
            float    rms_eps   = 1e-5f;
            float    rope_base = 10000.0f;
            int32_t  bos=1, eos=2;
        };

// Bounds-checked cursor over the mmap'd GGUF blob.
        struct Cursor {
            const uint8_t* p;
            const uint8_t* end;
            bool ok = true;

            template <typename T> T rd() {
                if (!ok || p + sizeof(T) > end) { ok = false; return T{}; }
                T v; std::memcpy(&v, p, sizeof(T)); p += sizeof(T); return v;
            }
            std::string rd_str() {
                const uint64_t len = rd<uint64_t>();
                if (!ok || p + len > end) { ok = false; return {}; }
                std::string s(reinterpret_cast<const char*>(p), (size_t)len);
                p += len; return s;
            }
            void skip(size_t n) { if (p + n > end) ok = false; else p += n; }
        };

        size_t gguf_scalar_size(uint32_t t) {
            switch (t) {
                case GGUF_U8: case GGUF_I8: case GGUF_BOOL: return 1;
                case GGUF_U16: case GGUF_I16:               return 2;
                case GGUF_U32: case GGUF_I32: case GGUF_F32:return 4;
                case GGUF_U64: case GGUF_I64: case GGUF_F64:return 8;
                default: return 0;
            }
        }

        QuantType ggml_to_quant(uint32_t t) {
            switch (t) {
                case 0:  return QuantType::F32;
                case 1:  return QuantType::F16;
                case 2:  return QuantType::Q4_0;
                case 8:  return QuantType::Q8_0;
                default: return QuantType::F32; // unsupported types treated as F32-shaped
            }
        }

    } // namespace

// =============================================================================
//  Tokenizer (GGUF-vocab backed; greedy SentencePiece-style encode)
// =============================================================================
    namespace {

        class Tokenizer {
        public:
            void build(std::vector<std::string> vocab) {
                vocab_ = std::move(vocab);
                for (int i = 0; i < (int)vocab_.size(); ++i) piece_to_id_[vocab_[i]] = i;
                max_piece_ = 0;
                for (auto& s : vocab_) max_piece_ = std::max(max_piece_, s.size());
            }

            std::vector<int> encode(const std::string& text) const {
                // SentencePiece convention: leading-space marker U+2581 ("\xe2\x96\x81").
                std::string s;
                s.reserve(text.size() + 8);
                s += "\xe2\x96\x81";
                for (char c : text) { if (c == ' ') s += "\xe2\x96\x81"; else s += c; }

                std::vector<int> out;
                size_t i = 0;
                while (i < s.size()) {
                    size_t best_len = 0; int best_id = -1;
                    const size_t cap = std::min(max_piece_, s.size() - i);
                    for (size_t l = cap; l >= 1; --l) {
                        auto it = piece_to_id_.find(s.substr(i, l));
                        if (it != piece_to_id_.end()) { best_len = l; best_id = it->second; break; }
                    }
                    if (best_id >= 0) { out.push_back(best_id); i += best_len; }
                    else {
                        // Byte fallback: emit <0xNN> token if present, else skip 1 byte.
                        char buf[8];
                        std::snprintf(buf, sizeof(buf), "<0x%02X>", (unsigned char)s[i]);
                        auto it = piece_to_id_.find(buf);
                        if (it != piece_to_id_.end()) out.push_back(it->second);
                        ++i;
                    }
                }
                return out;
            }

            std::string decode_piece(int id) const {
                if (id < 0 || id >= (int)vocab_.size()) return {};
                std::string p = vocab_[id];
                // Byte token <0xNN> -> raw byte.
                if (p.size() == 6 && p[0] == '<' && p[1] == '0' && p[2] == 'x') {
                    return std::string(1, (char)std::strtol(p.c_str() + 3, nullptr, 16));
                }
                // U+2581 -> space.
                std::string out;
                for (size_t i = 0; i < p.size();) {
                    if (i + 3 <= p.size() && (unsigned char)p[i] == 0xE2 &&
                        (unsigned char)p[i+1] == 0x96 && (unsigned char)p[i+2] == 0x81) {
                        out += ' '; i += 3;
                    } else { out += p[i]; ++i; }
                }
                return out;
            }

            size_t size() const { return vocab_.size(); }

        private:
            std::vector<std::string>            vocab_;
            std::unordered_map<std::string,int> piece_to_id_;
            size_t                              max_piece_ = 0;
        };

    } // namespace

// =============================================================================
//  Engine implementation (Impl)
// =============================================================================
    struct MllmEngine::Impl {
        explicit Impl(EngineConfig c) : cfg(std::move(c)) {}

        EngineConfig             cfg;
        MemoryArena              arena;
        KleidiMatmul             kleidi;
        ModelHParams             hp;
        Tokenizer                tok;
        std::unordered_map<std::string, TensorRef> tensors;

        // KV cache (f32): per layer, [n_ctx * n_head_kv * head_dim].
        std::vector<float>       k_cache, v_cache;
        int                      head_dim = 0;

        // Work queue.
        struct Job {
            uint64_t  request_id = 0;
            std::string prompt;
            std::string image_uri;
            GenParams params;
        };
        std::mutex               q_mtx;
        std::condition_variable  q_cv;
        std::deque<Job>          queue;
        std::set<uint64_t>       cancelled;

        TokenSink                sink;
        std::thread              decode_thread;
        std::atomic<bool>        running{false};

        // ---- helpers ------------------------------------------------------------
        TensorRef* find_tensor(const std::string& name) {
            auto it = tensors.find(name);
            if (it == tensors.end()) {
                RLLM_LOGE("required tensor not found: %s", name.c_str());
                return nullptr;
            }
            return &it->second;
        }

        bool check_tensor(TensorRef* t, const std::string& name) {
            if (!t) return false;
            return true;
        }

        QWeight as_weight(const TensorRef& t) {
            QWeight w; w.data = t.data; w.qt = t.qt;
            w.n = (int)t.n(); w.k = (int)t.k(); return w;
        }

        bool is_cancelled(uint64_t rid) {
            std::lock_guard<std::mutex> lk(q_mtx);
            return cancelled.erase(rid) > 0;
        }

        // ---- GGUF load ----------------------------------------------------------
        int parse_gguf();
        // ---- math ---------------------------------------------------------------
        void rmsnorm(const float* x, const TensorRef* w, float* out, int n);
        void rope(float* vec, int n_heads, int hd, int pos);
        void embed(int token, float* out);
        void forward_one(int token, int pos, std::vector<float>& logits);
        int  sample(std::vector<float>& logits, const GenParams& gp, std::mt19937_64& rng);
        // ---- loop ---------------------------------------------------------------
        void decode_loop();
    };

// ---- GGUF parsing -----------------------------------------------------------
    int MllmEngine::Impl::parse_gguf() {
        const uint8_t* base = static_cast<const uint8_t*>(arena.base());
        if (!base) return -EINVAL;
        Cursor c{ base, base + arena.size() };

        const uint32_t magic = c.rd<uint32_t>();
        if (magic != 0x46554747u /* "GGUF" */) { RLLM_LOGE("not a GGUF file"); return -EINVAL; }
        const uint32_t version   = c.rd<uint32_t>(); (void)version;
        const uint64_t n_tensors = c.rd<uint64_t>();
        const uint64_t n_kv      = c.rd<uint64_t>();

        uint32_t alignment = 32;
        std::vector<std::string> vocab;

        auto read_value = [&](uint32_t type, const std::string& key) {
            if (type == GGUF_STR) {
                const std::string v = c.rd_str();
                (void)v; (void)key;
            } else if (type == GGUF_ARR) {
                const uint32_t et = c.rd<uint32_t>();
                const uint64_t n  = c.rd<uint64_t>();
                if (et == GGUF_STR) {
                    const bool grab = (key == "tokenizer.ggml.tokens");
                    for (uint64_t i = 0; i < n && c.ok; ++i) {
                        std::string s = c.rd_str();
                        if (grab) vocab.push_back(std::move(s));
                    }
                } else {
                    const size_t sz = gguf_scalar_size(et);
                    if (sz == 0) { c.ok = false; return; }
                    c.skip((size_t)n * sz);
                }
            } else {
                const size_t sz = gguf_scalar_size(type);
                if (sz == 0) { c.ok = false; return; }
                // Capture the hyperparameters we need; skip the rest.
                if (type == GGUF_U32 || type == GGUF_I32) {
                    const uint32_t v = c.rd<uint32_t>();
                    if      (key == "general.alignment")                  alignment = v;
                    else if (key == "llama.embedding_length")             hp.n_embd = v;
                    else if (key == "llama.block_count")                  hp.n_layer = v;
                    else if (key == "llama.attention.head_count")         hp.n_head = v;
                    else if (key == "llama.attention.head_count_kv")      hp.n_head_kv = v;
                    else if (key == "llama.feed_forward_length")          hp.n_ff = v;
                    else if (key == "llama.context_length")               hp.n_ctx = v;
                    else if (key == "tokenizer.ggml.bos_token_id")        hp.bos = (int32_t)v;
                    else if (key == "tokenizer.ggml.eos_token_id")        hp.eos = (int32_t)v;
                } else if (type == GGUF_F32) {
                    const float v = c.rd<float>();
                    if      (key == "llama.attention.layer_norm_rms_epsilon") hp.rms_eps = v;
                    else if (key == "llama.rope.freq_base")                   hp.rope_base = v;
                } else {
                    c.skip(sz);
                }
            }
        };

        for (uint64_t i = 0; i < n_kv && c.ok; ++i) {
            const std::string key = c.rd_str();
            const uint32_t    typ = c.rd<uint32_t>();
            read_value(typ, key);
        }
        if (!c.ok) { RLLM_LOGE("GGUF metadata parse error"); return -EINVAL; }

        // Tensor directory.
        struct RawTensor { std::string name; std::vector<uint64_t> dims; uint32_t type; uint64_t off; };
        std::vector<RawTensor> raw;
        raw.reserve((size_t)n_tensors);
        for (uint64_t i = 0; i < n_tensors && c.ok; ++i) {
            RawTensor rt;
            rt.name = c.rd_str();
            const uint32_t nd = c.rd<uint32_t>();
            rt.dims.resize(nd);
            for (uint32_t d = 0; d < nd; ++d) rt.dims[d] = c.rd<uint64_t>();
            rt.type = c.rd<uint32_t>();
            rt.off  = c.rd<uint64_t>();
            raw.push_back(std::move(rt));
        }
        if (!c.ok) { RLLM_LOGE("GGUF tensor directory parse error"); return -EINVAL; }

        // Data section begins at the next `alignment` boundary after the headers.
        const size_t hdr_end = (size_t)(c.p - base);
        const size_t data_off = (hdr_end + (alignment - 1)) & ~((size_t)alignment - 1);
        const uint8_t* data_base = base + data_off;
        if (data_base > base + arena.size()) return -EINVAL;

        for (auto& rt : raw) {
            TensorRef tr;
            tr.qt   = ggml_to_quant(rt.type);
            tr.dims = rt.dims;
            tr.data = data_base + rt.off;
            if ((const uint8_t*)tr.data >= base + arena.size()) {
                RLLM_LOGE("tensor %s out of bounds", rt.name.c_str());
                return -EINVAL;
            }
            tensors.emplace(rt.name, std::move(tr));
        }

        // Derive / clamp.
        if (auto* te = find_tensor("token_embd.weight")) hp.n_vocab = (uint32_t)te->n();
        if (hp.n_head_kv == 0) hp.n_head_kv = hp.n_head;
        if (cfg.max_context && hp.n_ctx > cfg.max_context) hp.n_ctx = cfg.max_context;
        if (!hp.n_embd || !hp.n_layer || !hp.n_head || !hp.n_ctx || vocab.empty()) {
            RLLM_LOGE("incomplete model hyperparameters");
            return -EINVAL;
        }
        head_dim = (int)(hp.n_embd / hp.n_head);

        tok.build(std::move(vocab));

        // Allocate KV cache (f32).
        const size_t kv_per_layer = (size_t)hp.n_ctx * hp.n_head_kv * head_dim;
        k_cache.assign((size_t)hp.n_layer * kv_per_layer, 0.0f);
        v_cache.assign((size_t)hp.n_layer * kv_per_layer, 0.0f);

        RLLM_LOGI("GGUF: vocab=%u embd=%u layers=%u heads=%u kv_heads=%u ff=%u ctx=%u hd=%d",
                  hp.n_vocab, hp.n_embd, hp.n_layer, hp.n_head, hp.n_head_kv, hp.n_ff,
                  hp.n_ctx, head_dim);
        return 0;
    }

// ---- math -------------------------------------------------------------------
    void MllmEngine::Impl::rmsnorm(const float* x, const TensorRef* w, float* out, int n) {
        double ss = 0.0;
        for (int i = 0; i < n; ++i) ss += (double)x[i] * x[i];
        const float inv = 1.0f / std::sqrt((float)(ss / n) + hp.rms_eps);
        const uint16_t* wf16 = (w && w->qt == QuantType::F16) ? (const uint16_t*)w->data : nullptr;
        const float*    wf32 = (w && w->qt == QuantType::F32) ? (const float*)w->data    : nullptr;
        for (int i = 0; i < n; ++i) {
            const float g = wf16 ? f16_to_f32(wf16[i]) : (wf32 ? wf32[i] : 1.0f);
            out[i] = x[i] * inv * g;
        }
    }

    void MllmEngine::Impl::rope(float* vec, int n_heads, int hd, int pos) {
        const int half = hd / 2;
        for (int h = 0; h < n_heads; ++h) {
            float* p = vec + (size_t)h * hd;
            for (int i = 0; i < half; ++i) {
                const float freq = 1.0f / std::pow(hp.rope_base, (2.0f * (float)i) / (float)hd);
                const float ang  = (float)pos * freq;
                const float cs = std::cos(ang), sn = std::sin(ang);
                const float x0 = p[i], x1 = p[i + half];
                p[i]        = x0 * cs - x1 * sn;
                p[i + half] = x0 * sn + x1 * cs;
            }
        }
    }

    void MllmEngine::Impl::embed(int token, float* out) {
        const TensorRef* te = find_tensor("token_embd.weight");
        const int n = (int)hp.n_embd;
        if (!te) { std::memset(out, 0, n * sizeof(float)); return; }
        if (te->qt == QuantType::Q4_0) {
            const int bpr = n / QK;
            const block_q4_0* row = (const block_q4_0*)te->data + (size_t)token * bpr;
            for (int b = 0; b < bpr; ++b) dequant_block_q4_0(&row[b], out + b * QK);
        } else if (te->qt == QuantType::Q8_0) {
            const int bpr = n / QK;
            const block_q8_0* row = (const block_q8_0*)te->data + (size_t)token * bpr;
            for (int b = 0; b < bpr; ++b) dequant_block_q8_0(&row[b], out + b * QK);
        } else if (te->qt == QuantType::F16) {
            const uint16_t* row = (const uint16_t*)te->data + (size_t)token * n;
            for (int i = 0; i < n; ++i) out[i] = f16_to_f32(row[i]);
        } else {
            const float* row = (const float*)te->data + (size_t)token * n;
            std::memcpy(out, row, n * sizeof(float));
        }
    }

    void MllmEngine::Impl::forward_one(int token, int pos, std::vector<float>& logits) {
        const int n_embd = (int)hp.n_embd;
        const int hd     = head_dim;
        const int n_head = (int)hp.n_head;
        const int n_kv   = (int)hp.n_head_kv;
        const int group  = n_head / n_kv;
        const int q_dim  = n_head * hd;
        const int kv_dim = n_kv * hd;
        const size_t kv_per_layer = (size_t)hp.n_ctx * kv_dim;

        std::vector<float> x(n_embd), xb(n_embd), q(q_dim), k(kv_dim), v(kv_dim);
        std::vector<float> att(q_dim), ffn_g(hp.n_ff), ffn_u(hp.n_ff);
        embed(token, x.data());

        char name[64];
        for (int l = 0; l < (int)hp.n_layer; ++l) {
            std::snprintf(name, sizeof(name), "blk.%d.attn_norm.weight", l);
            rmsnorm(x.data(), find_tensor(name), xb.data(), n_embd);

            std::snprintf(name, sizeof(name), "blk.%d.attn_q.weight", l);
            { auto* t = find_tensor(name); if (!t) return; QWeight w = as_weight(*t); kleidi.matmul(w, 1, xb.data(), q.data(), -INFINITY, INFINITY); }
            std::snprintf(name, sizeof(name), "blk.%d.attn_k.weight", l);
            { auto* t = find_tensor(name); if (!t) return; QWeight w = as_weight(*t); kleidi.matmul(w, 1, xb.data(), k.data(), -INFINITY, INFINITY); }
            std::snprintf(name, sizeof(name), "blk.%d.attn_v.weight", l);
            { auto* t = find_tensor(name); if (!t) return; QWeight w = as_weight(*t); kleidi.matmul(w, 1, xb.data(), v.data(), -INFINITY, INFINITY); }

            rope(q.data(), n_head, hd, pos);
            rope(k.data(), n_kv,   hd, pos);

            // Append K/V to the cache at this position.
            float* Kc = k_cache.data() + (size_t)l * kv_per_layer + (size_t)pos * kv_dim;
            float* Vc = v_cache.data() + (size_t)l * kv_per_layer + (size_t)pos * kv_dim;
            std::memcpy(Kc, k.data(), kv_dim * sizeof(float));
            std::memcpy(Vc, v.data(), kv_dim * sizeof(float));

            // Grouped-query attention.
            const float scale = 1.0f / std::sqrt((float)hd);
            std::vector<float> scores(pos + 1);
            for (int h = 0; h < n_head; ++h) {
                const int kvh = h / group;
                const float* qh = q.data() + (size_t)h * hd;
                float maxs = -INFINITY;
                for (int t = 0; t <= pos; ++t) {
                    const float* kt = k_cache.data() + (size_t)l * kv_per_layer
                                      + (size_t)t * kv_dim + (size_t)kvh * hd;
                    float s = 0.0f;
                    for (int d = 0; d < hd; ++d) s += qh[d] * kt[d];
                    s *= scale; scores[t] = s; maxs = std::max(maxs, s);
                }
                float sum = 0.0f;
                for (int t = 0; t <= pos; ++t) { scores[t] = std::exp(scores[t] - maxs); sum += scores[t]; }
                const float invsum = (sum > 0.0f) ? 1.0f / sum : 0.0f;
                float* oh = att.data() + (size_t)h * hd;
                for (int d = 0; d < hd; ++d) oh[d] = 0.0f;
                for (int t = 0; t <= pos; ++t) {
                    const float w = scores[t] * invsum;
                    const float* vt = v_cache.data() + (size_t)l * kv_per_layer
                                      + (size_t)t * kv_dim + (size_t)kvh * hd;
                    for (int d = 0; d < hd; ++d) oh[d] += w * vt[d];
                }
            }

            std::snprintf(name, sizeof(name), "blk.%d.attn_output.weight", l);
            { auto* t = find_tensor(name); if (!t) return; QWeight w = as_weight(*t);
                kleidi.matmul(w, 1, att.data(), xb.data(), -INFINITY, INFINITY); }
            for (int i = 0; i < n_embd; ++i) x[i] += xb[i]; // residual

            // FFN (SwiGLU).
            std::snprintf(name, sizeof(name), "blk.%d.ffn_norm.weight", l);
            rmsnorm(x.data(), find_tensor(name), xb.data(), n_embd);
            std::snprintf(name, sizeof(name), "blk.%d.ffn_gate.weight", l);
            { auto* t = find_tensor(name); if (!t) return; QWeight w = as_weight(*t); kleidi.matmul(w, 1, xb.data(), ffn_g.data(), -INFINITY, INFINITY); }
            std::snprintf(name, sizeof(name), "blk.%d.ffn_up.weight", l);
            { auto* t = find_tensor(name); if (!t) return; QWeight w = as_weight(*t); kleidi.matmul(w, 1, xb.data(), ffn_u.data(), -INFINITY, INFINITY); }
            for (uint32_t i = 0; i < hp.n_ff; ++i) {
                const float g = ffn_g[i];
                ffn_g[i] = (g / (1.0f + std::exp(-g))) * ffn_u[i]; // SiLU(g) * up
            }
            std::snprintf(name, sizeof(name), "blk.%d.ffn_down.weight", l);
            { auto* t = find_tensor(name); if (!t) return; QWeight w = as_weight(*t);
                kleidi.matmul(w, 1, ffn_g.data(), xb.data(), -INFINITY, INFINITY); }
            for (int i = 0; i < n_embd; ++i) x[i] += xb[i]; // residual
        }

        rmsnorm(x.data(), find_tensor("output_norm.weight"), xb.data(), n_embd);

        // Output projection (tie to token_embd if a dedicated head is absent).
        TensorRef* outw = find_tensor("output.weight");
        if (!outw) outw = find_tensor("token_embd.weight");
        if (!outw || hp.n_vocab == 0) {
            logits.assign(hp.n_vocab ? hp.n_vocab : 0, 0.0f);
            return;
        }
        QWeight w = as_weight(*outw);
        logits.assign(hp.n_vocab, 0.0f);
        kleidi.matmul(w, 1, xb.data(), logits.data(), -INFINITY, INFINITY);
    }

    int MllmEngine::Impl::sample(std::vector<float>& logits, const GenParams& gp,
                                 std::mt19937_64& rng) {
        const int n = (int)logits.size();
        if (gp.temperature <= 0.0f) { // greedy
            return (int)(std::max_element(logits.begin(), logits.end()) - logits.begin());
        }
        // Temperature.
        const float inv_t = 1.0f / gp.temperature;
        for (float& v : logits) v *= inv_t;

        // Build candidate index list, optionally top-k truncated.
        std::vector<int> idx(n);
        for (int i = 0; i < n; ++i) idx[i] = i;
        int keep = n;
        if (gp.top_k > 0 && (int)gp.top_k < n) {
            keep = (int)gp.top_k;
            std::partial_sort(idx.begin(), idx.begin() + keep, idx.end(),
                              [&](int a, int b){ return logits[a] > logits[b]; });
            idx.resize(keep);
        } else {
            std::sort(idx.begin(), idx.end(), [&](int a, int b){ return logits[a] > logits[b]; });
        }

        // Softmax over kept candidates.
        float mx = logits[idx[0]];
        std::vector<float> probs(idx.size());
        float sum = 0.0f;
        for (size_t i = 0; i < idx.size(); ++i) { probs[i] = std::exp(logits[idx[i]] - mx); sum += probs[i]; }
        for (float& p : probs) p /= sum;

        // Top-p (nucleus) truncation.
        if (gp.top_p < 1.0f) {
            float cum = 0.0f; size_t cut = probs.size();
            for (size_t i = 0; i < probs.size(); ++i) { cum += probs[i]; if (cum >= gp.top_p) { cut = i + 1; break; } }
            probs.resize(cut); idx.resize(cut);
            float s = 0.0f; for (float p : probs) s += p;
            for (float& p : probs) p /= s;
        }

        std::uniform_real_distribution<float> dist(0.0f, 1.0f);
        float r = dist(rng), acc = 0.0f;
        for (size_t i = 0; i < probs.size(); ++i) { acc += probs[i]; if (r <= acc) return idx[i]; }
        return idx.back();
    }

// ---- decode loop (invariant 2) ----------------------------------------------
    void MllmEngine::Impl::decode_loop() {
        // Pin to the prime core (Cortex-X925) + best-effort priority elevation.
        if (cfg.pin_decode) {
            const CoreTopology topo = HardwareAffinity::detect();
            AffinityConfig ac;
            const int rc = (cfg.prime_core_hint >= 0)
                           ? HardwareAffinity::pin_to_cpu(cfg.prime_core_hint)
                           : HardwareAffinity::configure_decode_worker(topo, ac);
            if (rc != 0) RLLM_LOGW("decode-thread pin failed: %s", std::strerror(-rc));
        }

        std::vector<float> logits;
        while (running.load(std::memory_order_acquire)) {
            Job job;
            {
                std::unique_lock<std::mutex> lk(q_mtx);
                q_cv.wait(lk, [&]{ return !queue.empty() || !running.load(std::memory_order_acquire); });
                if (!running.load(std::memory_order_acquire)) break;
                job = std::move(queue.front());
                queue.pop_front();
            }

            std::mt19937_64 rng(job.params.seed ? job.params.seed : std::random_device{}());

            // --- Prefill: run the prompt tokens through to fill the KV cache. ----
            std::vector<int> ids = tok.encode(job.prompt);
            if (ids.empty()) ids.push_back(hp.bos);
            const int ctx_max = (int)hp.n_ctx;
            int pos = 0;
            bool stop = false;
            for (size_t i = 0; i < ids.size() && pos < ctx_max; ++i, ++pos) {
                forward_one(ids[i], pos, logits);
                if (is_cancelled(job.request_id)) { stop = true; break; }
            }

            // --- Decode: autoregressive generation. -----------------------------
            const uint32_t cap = std::min<uint32_t>(job.params.max_tokens,
                                                    (uint32_t)std::max(0, ctx_max - pos));
            for (uint32_t g = 0; g < cap && !stop && pos < ctx_max; ++g) {
                const int next = sample(logits, job.params, rng);

                const bool final = (next == hp.eos) || (g + 1 == cap) || (pos + 1 >= ctx_max);
                const std::string piece = (next == hp.eos) ? std::string() : tok.decode_piece(next);

                // Invariant 2: push token -> SPSC ring -> UDS publish() (async).
                // sink() == UdsServer::publish(); false == ring-full backpressure.
                if (sink) {
                    int spins = 0;
                    while (running.load(std::memory_order_acquire) &&
                           !sink(job.request_id, next, piece, final)) {
                        if (++spins < 64) std::this_thread::yield();
                        else { std::this_thread::sleep_for(std::chrono::microseconds(50)); spins = 0; }
                    }
                }
                if (final) break;

                forward_one(next, pos, logits);
                ++pos;
                if (is_cancelled(job.request_id)) {
                    if (sink) while (running.load(std::memory_order_acquire) &&
                                     !sink(job.request_id, -1, "", true)) std::this_thread::yield();
                    break;
                }
            }
        }
    }

// =============================================================================
//  MllmEngine public surface
// =============================================================================
    MllmEngine::MllmEngine(EngineConfig cfg) : impl_(new Impl(std::move(cfg))) {}

    MllmEngine::~MllmEngine() {
        shutdown();
        delete impl_;
        impl_ = nullptr;
    }

    int MllmEngine::load() {
        if (!impl_) return -EINVAL;

        MemoryArena::raise_memlock_limit(); // best-effort before locking

        int rc = impl_->arena.map_file(impl_->cfg.model_path);
        if (rc != 0) return rc;

        rc = impl_->parse_gguf();
        if (rc != 0) { impl_->arena.release(); return rc; } // strict cleanup on failure

        if (impl_->cfg.lock_weights) {
            const int lr = impl_->arena.lock_all(); // invariant 1
            if (lr != 0) RLLM_LOGW("weights not fully locked (degraded residency): %s",
                                   std::strerror(-lr));
        }

        impl_->running.store(true, std::memory_order_release);
        impl_->decode_thread = std::thread([this]{ impl_->decode_loop(); });

        RLLM_LOGI("engine loaded (kleidiai sme2=%d i8mm=%d)",
                  (int)impl_->kleidi.sme2_available(), (int)impl_->kleidi.i8mm_available());
        return 0;
    }

    void MllmEngine::set_sink(TokenSink sink) { if (impl_) impl_->sink = std::move(sink); }

    bool MllmEngine::submit(uint64_t request_id, const std::string& prompt,
                            const std::string& image_uri, const GenParams& params) {
        if (!impl_ || !impl_->running.load(std::memory_order_acquire)) return false;
        {
            std::lock_guard<std::mutex> lk(impl_->q_mtx);
            if (impl_->queue.size() >= impl_->cfg.work_queue_cap) return false;
            impl_->queue.push_back(Impl::Job{ request_id, prompt, image_uri, params });
        }
        impl_->q_cv.notify_one();
        return true;
    }

    void MllmEngine::cancel(uint64_t request_id) {
        if (!impl_) return;
        std::lock_guard<std::mutex> lk(impl_->q_mtx);
        impl_->cancelled.insert(request_id);
        // Drop it from the queue if still pending.
        for (auto it = impl_->queue.begin(); it != impl_->queue.end();) {
            if (it->request_id == request_id) it = impl_->queue.erase(it);
            else ++it;
        }
    }

    void MllmEngine::shutdown() {
        if (!impl_ || !impl_->running.load(std::memory_order_acquire)) return;

        impl_->running.store(false, std::memory_order_release);
        impl_->q_cv.notify_all();

        if (impl_->decode_thread.joinable()) {
            impl_->decode_thread.join();
        }

        impl_->arena.release();
    }

    bool MllmEngine::running() const noexcept {
        return impl_ && impl_->running.load(std::memory_order_acquire);
    }

} // namespace ratherllm
