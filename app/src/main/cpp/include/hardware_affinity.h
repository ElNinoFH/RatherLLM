// =============================================================================
//  ratherllm :: hardware_affinity.h
//
//  Heterogeneous big-core thread placement for the MediaTek Dimensity 9500s
//  (MT6991Z) all-big-core topology:
//      core 7      -> Cortex-X925  @ 3.73 GHz   (Prime,       2MB private L2)
//      cores 6/5/4 -> Cortex-X4    @ 3.30 GHz   (Performance)
//      cores 3..0  -> Cortex-A720  @ 2.40 GHz   (Efficiency, reserved for OS/UI)
//
//  Two-step thread-elevation handshake (per worker, from its own context):
//      1) Write this thread's TID to a vendor priority node (default
//         "/proc/set_ai_thread") to request scheduler elevation  [best-effort].
//      2) Invoke sched_setaffinity() to hard-pin the thread       [authoritative].
//
//  Phase-shifting placement:
//      Prefill phase : 4-thread pool. worker 0 -> core 7 (Prime),
//                      workers 1/2/3 -> cores 6/5/4 (Performance).
//                      A720 cores are NEVER targeted (clock-mismatch barriers).
//      Decode  phase : single thread locked exclusively to core 7 (Prime) to
//                      exploit its 2MB private L2 and minimize power.
// =============================================================================
#pragma once

#include <sys/types.h> // pid_t

#include <string>
#include <vector>

namespace ratherllm {

    enum class CoreClass {
        Prime, Performance, Efficiency
    }; // X925 / X4 / A720

    enum class Phase {
        Prefill, Decode
    };

    struct CoreTopology {
        std::vector<int> prime_cores;       // expected {7}      (highest freq tier)
        std::vector<int> performance_cores; // expected {6,5,4}  (mid freq tier, desc)
        std::vector<int> efficiency_cores;  // expected {3,2,1,0} (lowest freq tier)
        int total_cpus = 0;
        bool detected = false; // false => static MT6991Z fallback used
    };

    struct AffinityConfig {
        // Vendor scheduler-elevation node. NON-STANDARD: absent on stock kernels.
        std::string ai_priority_path = "/proc/set_ai_thread";
        bool use_ai_priority = true;  // attempt the TID-write handshake
        bool use_sched_fifo = true;  // also request SCHED_FIFO (best-effort)
        int fifo_priority = 10;    // 1..99 for SCHED_FIFO
        int prefill_threads = 4;     // size of the prefill pool
    };

    class HardwareAffinity {
    public:
        // Detect the core topology from cpufreq max-frequency tiers. Falls back to
        // the known MT6991Z static layout if sysfs is unreadable/degenerate.
        static CoreTopology detect();

        // --- Low-level primitives (operate on the CALLING thread) ----------------
        static int pin_to_cpu(int cpu);                          // 0 / -errno
        static int pin_to_cpus(const std::vector<int> &cpus);    // 0 / -errno
        static int pin_to_class(const CoreTopology &topo, CoreClass cls);

        static int request_realtime(int fifo_priority);          // SCHED_FIFO

        // Step 1 of the handshake (standalone): write `tid` to the priority node.
        // Returns 0 on success or -errno (e.g. -ENOENT when the node is absent).
        static int gain_ai_priority(pid_t tid, const std::string &path);

        // --- Phase-shifting entry points (CALL FROM WITHIN THE WORKER THREAD) -----
        // Performs the full two-step handshake (priority then affinity) for the
        // calling thread. Returns the affinity result (the authoritative op):
        //   0 on success, -errno on failure. Priority failures are non-fatal and
        //   surfaced only via logs.
        //
        // worker_index in [0, cfg.prefill_threads):
        //   index 0      -> prime core (7)
        //   index 1..N-1 -> performance cores (6,5,4)
        static int configure_prefill_worker(const CoreTopology &topo,
                                            int worker_index,
                                            const AffinityConfig &cfg = {});

        // Decode: lock the calling thread exclusively to the prime core (7).
        static int configure_decode_worker(const CoreTopology &topo,
                                           const AffinityConfig &cfg = {});

        // Kernel thread id of the calling thread.
        static pid_t current_tid();

    private:
        static int set_affinity_tid(pid_t tid, const std::vector<int> &cpus);

        static CoreTopology fallback_topology();
    };

} // namespace ratherllm
