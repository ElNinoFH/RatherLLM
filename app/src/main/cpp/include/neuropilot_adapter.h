// =============================================================================
//  ratherllm :: neuropilot_adapter.h
//
//  Runtime adapter for the MediaTek NeuroPilot / Neuron APU stack. Loads the
//  vendor runtime libraries via dlopen()/dlsym() at runtime so the engine has
//  no link-time dependency on them, offloads eligible subgraphs (vision encoder,
//  prefill GEMMs) to the APU with internal-SRAM layer fusion, and degrades
//  transparently to the CPU KleidiAI/Vulkan path when the APU is unavailable.
// =============================================================================
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace ratherllm {

// A non-owning view of a tensor buffer handed to / from the APU.
    struct TensorView {
        void*                     data  = nullptr; // caller-owned host memory
        std::size_t               bytes = 0;       // size of `data` in bytes
        std::vector<std::int64_t> shape {};        // logical shape (informational)
    };

    enum class ApuPrecision : std::uint8_t {
        Int8,            // 8-bit weights, 8-bit activations
        Weight8Act16,    // 8w16a: 8-bit weights, 16-bit affine activations
        Fp16,
    };

// A contiguous, inclusive range of model layers to fuse and resolve entirely
// within the APU's internal SRAM (no LPDDR5X round-trips between them).
    struct FusionGroup {
        int first_layer = 0;
        int last_layer  = 0;
    };

    struct ApuExecConfig {
        bool                     allow_8w16a_affine_operators = false;
        bool                     prefer_sram_fusion           = true;
        std::size_t              sram_budget_bytes            = 0; // 0 => backend default
        ApuPrecision             precision                    = ApuPrecision::Int8;
        std::vector<FusionGroup> fusion_groups {};                 // SRAM-fused regions
        int                      preferred_device             = 0; // APU core preference
    };

    class NeuropilotAdapter {
    public:
        NeuropilotAdapter();
        ~NeuropilotAdapter();

        NeuropilotAdapter(const NeuropilotAdapter&)            = delete;
        NeuropilotAdapter& operator=(const NeuropilotAdapter&) = delete;

        // Lightweight probe: can the vendor runtime even be dlopen()'d on this device?
        static bool probe_available();

        // dlopen the three runtime libraries and resolve every required symbol,
        // create the backend + buffer allocator. Returns true iff the full APU path
        // is usable; on ANY failure the adapter is left in CPU-fallback state and
        // returns false (no throw, no crash).
        bool initialize();

        [[nodiscard]] bool is_available() const noexcept { return available_; }

        // Set the SRAM-fusion / precision configuration applied at load_model().
        void set_exec_config(const ApuExecConfig& cfg);

        // Compile + load a precompiled network (.dla) under the current exec config.
        // Returns 0 on success; -ENODEV when the APU is unavailable; -errno on a
        // compile/load failure (the caller falls back to CPU).
        int load_model(const std::string& dla_path);

        // Execute the loaded model. Returns 0 on success; -ENODEV when the APU is
        // unavailable; -EIO/-ENOMEM on a runtime failure (caller falls back to CPU).
        // `outputs[i].data` must point to caller-allocated memory of `outputs[i].bytes`.
        int run(const std::vector<TensorView>& inputs, std::vector<TensorView>& outputs);

        // Release runtime + compiled network + backend + allocator and dlclose()
        // every opened handle. Idempotent and leak-free.
        void unload();

    private:
        struct Api;       // resolved function-pointer table + dl handles (in .cpp)
        struct DmaBuffer; // allocator-backed DMA buffer (in .cpp)

        [[nodiscard]] std::string build_compile_options() const;

        Api*          api_          = nullptr;
        void*         backend_      = nullptr;
        void*         compiled_     = nullptr;
        void*         runtime_      = nullptr;
        void*         allocator_    = nullptr;
        bool          available_    = false;
        bool          model_loaded_ = false;
        ApuExecConfig cfg_;
    };

} // namespace ratherllm
