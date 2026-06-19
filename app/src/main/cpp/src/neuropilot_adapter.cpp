// =============================================================================
//  ratherllm :: neuropilot_adapter.cpp
//
//  Production implementation of the MediaTek NeuroPilot / Neuron APU adapter
//  with dlopen()/dlsym() runtime binding, strict symbol validation, transparent
//  CPU fallback, SRAM-fused execution boundaries (8w16a affine support), and
//  leak-free dlclose() teardown.
// =============================================================================
#include "neuropilot_adapter.h"

#include <dlfcn.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

#if defined(__ANDROID__)
#include <android/log.h>
#define RLLM_NP_TAG "ratherllm.npu"
#define RLLM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, RLLM_NP_TAG, __VA_ARGS__)
#define RLLM_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  RLLM_NP_TAG, __VA_ARGS__)
#define RLLM_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  RLLM_NP_TAG, __VA_ARGS__)
#else
#define RLLM_LOGE(...) do { std::fprintf(stderr, "[ratherllm.npu][E] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define RLLM_LOGW(...) do { std::fprintf(stderr, "[ratherllm.npu][W] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define RLLM_LOGI(...) do { std::fprintf(stderr, "[ratherllm.npu][I] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace ratherllm {

// =============================================================================
//  ---- SDK ABI seam --------------------------------------------------------
//  Library names and the function-pointer signatures we bind to. Adjust the
//  symbol names / argument lists here to match the exact NeuroPilot SDK
//  revision on the target device. NEURON_NO_ERROR == 0 by convention.
// =============================================================================
    namespace {

        constexpr const char* kLibBackend   = "libneuron_backend.so";
        constexpr const char* kLibAdapter   = "libneuronusdk_adapter.mtk.so";
        constexpr const char* kLibAllocator = "libneuron_buffer_allocator.so";

        using NeuronStatus = int;
        constexpr NeuronStatus NEURON_NO_ERROR = 0;

// Buffer attribute passed to setInput/setOutput for zero-copy DMA (ION fd).
        struct BufferAttribute {
            int ion_fd; // -1 for a non-DMA (host) buffer
        };

// libneuron_backend.so
        using fn_backend_init             = void*       (*)();
        using fn_backend_compile_spec     = NeuronStatus(*)(void* backend, const char* options,
                                                            const char* dla_path, void** out_compiled);
        using fn_backend_destroy_compiled = void        (*)(void* compiled);
        using fn_backend_release          = void        (*)(void* backend);

// libneuronusdk_adapter.mtk.so
        using fn_rt_create                = NeuronStatus(*)(const void* env_options, void** runtime);
        using fn_rt_load_compiled         = NeuronStatus(*)(void* runtime, void* compiled);
        using fn_rt_load_from_file        = NeuronStatus(*)(void* runtime, const char* path);
        using fn_rt_set_input             = NeuronStatus(*)(void* runtime, std::uint64_t handle,
                                                            const void* buffer, std::size_t length,
                                                            BufferAttribute attr);
        using fn_rt_set_output            = NeuronStatus(*)(void* runtime, std::uint64_t handle,
                                                            void* buffer, std::size_t length,
                                                            BufferAttribute attr);
        using fn_rt_inference             = NeuronStatus(*)(void* runtime);
        using fn_rt_release               = void        (*)(void* runtime);

// libneuron_buffer_allocator.so
        using fn_alloc_create  = void*       (*)();
        using fn_alloc_allocate = NeuronStatus(*)(void* allocator, std::size_t size,
                                                  void** out_addr, int* out_fd, void** out_handle);
        using fn_alloc_free    = NeuronStatus(*)(void* allocator, void* handle);
        using fn_alloc_destroy = void        (*)(void* allocator);

// Resolve a symbol; on failure for a required symbol, record its name.
        template <typename Fn>
        bool resolve(void* lib, const char* name, Fn& out, bool required, const char*& missing) {
            ::dlerror(); // clear
            void* sym = ::dlsym(lib, name);
            const char* err = ::dlerror();
            if (sym == nullptr || err != nullptr) {
                if (required) { missing = name; return false; }
                out = nullptr;
                return true; // optional symbol absent: acceptable
            }
            out = reinterpret_cast<Fn>(reinterpret_cast<std::uintptr_t>(sym));
            return true;
        }

    } // namespace

// =============================================================================
//  Api : resolved function-pointer table + owned dl handles
// =============================================================================
    struct NeuropilotAdapter::Api {
        void* h_backend   = nullptr;
        void* h_adapter   = nullptr;
        void* h_allocator = nullptr;

        fn_backend_init             backend_init             = nullptr;
        fn_backend_compile_spec     backend_compile_spec     = nullptr;
        fn_backend_destroy_compiled backend_destroy_compiled = nullptr;
        fn_backend_release          backend_release          = nullptr;

        fn_rt_create        rt_create        = nullptr;
        fn_rt_load_compiled rt_load_compiled = nullptr;
        fn_rt_load_from_file rt_load_from_file = nullptr;
        fn_rt_set_input     rt_set_input     = nullptr;
        fn_rt_set_output    rt_set_output    = nullptr;
        fn_rt_inference     rt_inference     = nullptr;
        fn_rt_release       rt_release       = nullptr;

        fn_alloc_create   alloc_create   = nullptr;
        fn_alloc_allocate alloc_allocate = nullptr;
        fn_alloc_free     alloc_free     = nullptr;
        fn_alloc_destroy  alloc_destroy  = nullptr;

        bool open_libraries() {
            const int flags = RTLD_NOW | RTLD_LOCAL;
            h_allocator = ::dlopen(kLibAllocator, flags);
            if (!h_allocator) { RLLM_LOGW("dlopen(%s): %s", kLibAllocator, ::dlerror()); return false; }
            h_adapter = ::dlopen(kLibAdapter, flags);
            if (!h_adapter)   { RLLM_LOGW("dlopen(%s): %s", kLibAdapter, ::dlerror());   return false; }
            h_backend = ::dlopen(kLibBackend, flags);
            if (!h_backend)   { RLLM_LOGW("dlopen(%s): %s", kLibBackend, ::dlerror());   return false; }
            return true;
        }

        bool resolve_all() {
            const char* missing = nullptr;
            bool ok = true;

            // Required backend symbols.
            ok &= resolve(h_backend, "neuron_backend_init",             backend_init,         true,  missing);
            ok &= resolve(h_backend, "neuron_backend_compile_spec",     backend_compile_spec, true,  missing);
            ok &= resolve(h_backend, "neuron_backend_release",          backend_release,      true,  missing);
            // Optional backend symbols.
            ok &= resolve(h_backend, "neuron_backend_destroy_compiled", backend_destroy_compiled, false, missing);

            // Required runtime (adapter) symbols.
            ok &= resolve(h_adapter, "NeuronRuntime_create",            rt_create,        true, missing);
            ok &= resolve(h_adapter, "NeuronRuntime_setInput",          rt_set_input,     true, missing);
            ok &= resolve(h_adapter, "NeuronRuntime_setOutput",         rt_set_output,    true, missing);
            ok &= resolve(h_adapter, "NeuronRuntime_inference",         rt_inference,     true, missing);
            ok &= resolve(h_adapter, "NeuronRuntime_release",           rt_release,       true, missing);
            // At least one network-load path is required.
            resolve(h_adapter, "NeuronRuntime_loadNetworkFromCompiled", rt_load_compiled,  false, missing);
            resolve(h_adapter, "NeuronRuntime_loadNetworkFromFile",     rt_load_from_file, false, missing);
            if (rt_load_compiled == nullptr && rt_load_from_file == nullptr) {
                RLLM_LOGW("no NeuronRuntime load symbol available");
                ok = false;
            }

            // Required allocator symbols.
            ok &= resolve(h_allocator, "neuron_buffer_allocator_create",  alloc_create,   true, missing);
            ok &= resolve(h_allocator, "neuron_buffer_allocate",          alloc_allocate, true, missing);
            ok &= resolve(h_allocator, "neuron_buffer_free",              alloc_free,     true, missing);
            ok &= resolve(h_allocator, "neuron_buffer_allocator_destroy", alloc_destroy,  true, missing);

            if (!ok && missing) RLLM_LOGW("required Neuron symbol missing: %s", missing);
            return ok;
        }

        void close_all() {
            if (h_backend)   { ::dlclose(h_backend);   h_backend = nullptr; }
            if (h_adapter)   { ::dlclose(h_adapter);   h_adapter = nullptr; }
            if (h_allocator) { ::dlclose(h_allocator); h_allocator = nullptr; }
        }
    };

// =============================================================================
//  DmaBuffer
// =============================================================================
    struct NeuropilotAdapter::DmaBuffer {
        void*       addr   = nullptr;
        int         fd     = -1;
        void*       handle = nullptr;
        std::size_t bytes  = 0;
    };

// =============================================================================
//  Construction / probe
// =============================================================================
    NeuropilotAdapter::NeuropilotAdapter() = default;

    NeuropilotAdapter::~NeuropilotAdapter() { unload(); }

    bool NeuropilotAdapter::probe_available() {
        void* h = ::dlopen(kLibAdapter, RTLD_NOW | RTLD_LOCAL);
        if (!h) { RLLM_LOGI("NeuroPilot adapter not present: %s", ::dlerror()); return false; }
        ::dlclose(h);
        return true;
    }

    void NeuropilotAdapter::set_exec_config(const ApuExecConfig& cfg) { cfg_ = cfg; }

// =============================================================================
//  initialize(): dlopen + resolve + create backend/allocator (strict)
// =============================================================================
    bool NeuropilotAdapter::initialize() {
        if (available_) return true;

        Api* api = new Api();
        if (!api->open_libraries() || !api->resolve_all()) {
            api->close_all();
            delete api;
            RLLM_LOGW("APU init failed; using CPU KleidiAI/Vulkan fallback");
            available_ = false;
            return false;
        }

        void* backend = api->backend_init ? api->backend_init() : nullptr;
        if (backend == nullptr) {
            RLLM_LOGW("neuron_backend_init returned null; falling back to CPU");
            api->close_all();
            delete api;
            available_ = false;
            return false;
        }

        void* allocator = api->alloc_create ? api->alloc_create() : nullptr;
        if (allocator == nullptr) {
            RLLM_LOGW("neuron buffer allocator creation failed; falling back to CPU");
            if (api->backend_release) api->backend_release(backend);
            api->close_all();
            delete api;
            available_ = false;
            return false;
        }

        api_       = api;
        backend_   = backend;
        allocator_ = allocator;
        available_ = true;
        RLLM_LOGI("NeuroPilot APU runtime initialized");
        return true;
    }

// =============================================================================
//  Compile options (SRAM fusion + 8w16a affine flags)
// =============================================================================
    std::string NeuropilotAdapter::build_compile_options() const {
        std::string o;
        o.reserve(256);
        o += "{";
        o += "\"allow_8w16a_affine_operators\":";
        o += (cfg_.allow_8w16a_affine_operators ? "true" : "false");

        o += ",\"prefer_sram_fusion\":";
        o += (cfg_.prefer_sram_fusion ? "true" : "false");

        if (cfg_.sram_budget_bytes > 0) {
            o += ",\"sram_budget_bytes\":";
            o += std::to_string(cfg_.sram_budget_bytes);
        }

        const char* prec = "int8";
        switch (cfg_.precision) {
            case ApuPrecision::Int8:        prec = "int8";  break;
            case ApuPrecision::Weight8Act16:prec = "8w16a"; break;
            case ApuPrecision::Fp16:        prec = "fp16";  break;
        }
        o += ",\"precision\":\""; o += prec; o += "\"";
        o += ",\"preferred_device\":"; o += std::to_string(cfg_.preferred_device);

        o += ",\"fusion_groups\":[";
        for (std::size_t i = 0; i < cfg_.fusion_groups.size(); ++i) {
            if (i) o += ",";
            o += "[";
            o += std::to_string(cfg_.fusion_groups[i].first_layer);
            o += ",";
            o += std::to_string(cfg_.fusion_groups[i].last_layer);
            o += "]";
        }
        o += "]}";
        return o;
    }

// =============================================================================
//  load_model(): compile with options, then load into the runtime
// =============================================================================
    int NeuropilotAdapter::load_model(const std::string& dla_path) {
        if (!available_ || api_ == nullptr) return -ENODEV;

        // If a model is already loaded, release it first.
        if (compiled_) {
            if (api_->backend_destroy_compiled) api_->backend_destroy_compiled(compiled_);
            compiled_ = nullptr;
        }
        model_loaded_ = false;

        if (runtime_ == nullptr) {
            if (api_->rt_create(nullptr, &runtime_) != NEURON_NO_ERROR || runtime_ == nullptr) {
                RLLM_LOGW("NeuronRuntime_create failed; falling back to CPU");
                runtime_ = nullptr;
                return -EIO;
            }
        }

        const std::string options = build_compile_options();
        void* compiled = nullptr;

        // Optimization: if we can only load from file, skip backend compilation if possible.
        // However, usually we need to compile to apply 'options'.
        const NeuronStatus cs =
                api_->backend_compile_spec(backend_, options.c_str(), dla_path.c_str(), &compiled);

        if (cs != NEURON_NO_ERROR || compiled == nullptr) {
            RLLM_LOGW("neuron_backend_compile_spec failed (%d); falling back to CPU", cs);
            return -EIO;
        }
        compiled_ = compiled;

        NeuronStatus ls = NEURON_NO_ERROR;
        if (api_->rt_load_compiled) {
            ls = api_->rt_load_compiled(runtime_, compiled_);
        } else if (api_->rt_load_from_file) {
            ls = api_->rt_load_from_file(runtime_, dla_path.c_str());
        } else {
            ls = -1;
        }

        if (ls != NEURON_NO_ERROR) {
            RLLM_LOGW("NeuronRuntime load failed (%d); falling back to CPU", ls);
            if (api_->backend_destroy_compiled) api_->backend_destroy_compiled(compiled_);
            compiled_ = nullptr;
            return -EIO;
        }

        model_loaded_ = true;
        RLLM_LOGI("APU model loaded (%s) with options=%s", dla_path.c_str(), options.c_str());
        return 0;
    }

// =============================================================================
//  run(): allocate DMA buffers, set IO, infer, copy out, free (with cleanup)
// =============================================================================
    int NeuropilotAdapter::run(const std::vector<TensorView>& inputs,
                               std::vector<TensorView>& outputs) {
        if (!available_ || !model_loaded_ || api_ == nullptr || runtime_ == nullptr) {
            return -ENODEV; // engine falls back to CPU KleidiAI/Vulkan
        }

        std::vector<DmaBuffer> in_bufs, out_bufs;
        in_bufs.reserve(inputs.size());
        out_bufs.reserve(outputs.size());

        auto free_buf = [&](DmaBuffer& b) {
            if (b.handle && api_->alloc_free) api_->alloc_free(allocator_, b.handle);
            b = DmaBuffer{};
        };
        auto cleanup = [&]() {
            for (auto& b : in_bufs)  free_buf(b);
            for (auto& b : out_bufs) free_buf(b);
        };
        auto alloc = [&](std::size_t bytes, DmaBuffer& out) -> int {
            void* addr = nullptr; int fd = -1; void* h = nullptr;
            if (api_->alloc_allocate(allocator_, bytes, &addr, &fd, &h) != NEURON_NO_ERROR
                || addr == nullptr) {
                return -ENOMEM;
            }
            out = DmaBuffer{ addr, fd, h, bytes };
            return 0;
        };

        // Inputs -> DMA buffers -> setInput.
        for (std::size_t i = 0; i < inputs.size(); ++i) {
            if (inputs[i].data == nullptr || inputs[i].bytes == 0) { cleanup(); return -EINVAL; }
            DmaBuffer b;
            if (alloc(inputs[i].bytes, b) != 0) { cleanup(); return -ENOMEM; }
            std::memcpy(b.addr, inputs[i].data, inputs[i].bytes);
            const BufferAttribute attr{ b.fd };
            if (api_->rt_set_input(runtime_, static_cast<std::uint64_t>(i), b.addr, b.bytes, attr) != NEURON_NO_ERROR) {
                free_buf(b); cleanup(); return -EIO;
            }
            in_bufs.push_back(b);
        }

        // Outputs -> DMA buffers -> setOutput.
        for (std::size_t i = 0; i < outputs.size(); ++i) {
            if (outputs[i].data == nullptr || outputs[i].bytes == 0) { cleanup(); return -EINVAL; }
            DmaBuffer b;
            if (alloc(outputs[i].bytes, b) != 0) { cleanup(); return -ENOMEM; }
            const BufferAttribute attr{ b.fd };
            if (api_->rt_set_output(runtime_, static_cast<std::uint64_t>(i), b.addr, b.bytes, attr) != NEURON_NO_ERROR) {
                free_buf(b); cleanup(); return -EIO;
            }
            out_bufs.push_back(b);
        }

        // Execute on the APU.
        if (api_->rt_inference(runtime_) != NEURON_NO_ERROR) {
            cleanup();
            return -EIO; // transparent CPU fallback upstream
        }

        // Copy results back to caller buffers.
        for (std::size_t i = 0; i < outputs.size(); ++i) {
            std::memcpy(outputs[i].data, out_bufs[i].addr, outputs[i].bytes);
        }

        cleanup();
        return 0;
    }

// =============================================================================
//  unload(): release everything + dlclose, idempotent & leak-free
// =============================================================================
    void NeuropilotAdapter::unload() {
        if (api_) {
            if (runtime_)  { if (api_->rt_release)              api_->rt_release(runtime_);              runtime_  = nullptr; }
            if (compiled_) { if (api_->backend_destroy_compiled) api_->backend_destroy_compiled(compiled_); compiled_ = nullptr; }
            if (backend_)  { if (api_->backend_release)         api_->backend_release(backend_);         backend_  = nullptr; }
            if (allocator_){ if (api_->alloc_destroy)           api_->alloc_destroy(allocator_);         allocator_= nullptr; }
            api_->close_all(); // dlclose() backend, adapter, allocator
            delete api_;
            api_ = nullptr;
        } else {
            runtime_ = compiled_ = backend_ = allocator_ = nullptr;
        }
        available_    = false;
        model_loaded_ = false;
    }

} // namespace ratherllm
