// =============================================================================
//  ratherllm :: hardware_affinity.cpp
//
//  Production implementation of heterogeneous big-core thread placement and the
//  two-step (priority -> affinity) elevation handshake for the MT6991Z SoC.
// =============================================================================
#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1 // CPU_SET / sched_setaffinity / SCHED_FIFO on bionic+glibc
#endif

#include "../include/hardware_affinity.h"

#include <fcntl.h>
#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>

#if defined(__ANDROID__)
#include <android/log.h>
#define RLLM_AFF_TAG "ratherllm.aff"
#define RLLM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, RLLM_AFF_TAG, __VA_ARGS__)
#define RLLM_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  RLLM_AFF_TAG, __VA_ARGS__)
#define RLLM_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  RLLM_AFF_TAG, __VA_ARGS__)
#else
#define RLLM_LOGE(...) do { std::fprintf(stderr, "[ratherllm.aff][E] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define RLLM_LOGW(...) do { std::fprintf(stderr, "[ratherllm.aff][W] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define RLLM_LOGI(...) do { std::fprintf(stderr, "[ratherllm.aff][I] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace ratherllm {
    namespace {

// Read a single non-negative long from a sysfs/proc file. Returns false on any
// open/parse failure (offline core, missing node, permission, etc.).
        bool read_long_file(const char* path, long& out) {
            FILE* f = std::fopen(path, "re"); // 'e' => O_CLOEXEC
            if (!f) return false;
            long v = 0;
            const int n = std::fscanf(f, "%ld", &v);
            std::fclose(f);
            if (n != 1 || v < 0) return false;
            out = v;
            return true;
        }

    } // namespace

// =============================================================================
//  TID
// =============================================================================
    pid_t HardwareAffinity::current_tid() {
        return static_cast<pid_t>(::syscall(SYS_gettid));
    }

// =============================================================================
//  Topology detection
// =============================================================================
    CoreTopology HardwareAffinity::fallback_topology() {
        CoreTopology t;
        t.prime_cores       = {7};
        t.performance_cores = {6, 5, 4};
        t.efficiency_cores  = {3, 2, 1, 0};
        t.total_cpus        = 8;
        t.detected          = false;
        RLLM_LOGW("topology detection unavailable; using static MT6991Z fallback "
                  "(prime=7, perf=6/5/4, eff=3..0)");
        return t;
    }

    CoreTopology HardwareAffinity::detect() {
        const long nconf = ::sysconf(_SC_NPROCESSORS_CONF);
        if (nconf <= 0) return fallback_topology();

        CoreTopology topo;
        topo.total_cpus = static_cast<int>(nconf);

        std::vector<std::pair<long, int>> freqs; // (max_freq_khz, cpu)
        freqs.reserve(static_cast<size_t>(nconf));
        for (int c = 0; c < nconf; ++c) {
            char path[128];
            std::snprintf(path, sizeof(path),
                          "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
            long f = 0;
            if (read_long_file(path, f) && f > 0) {
                freqs.emplace_back(f, c);
            }
        }
        if (freqs.size() < 2) return fallback_topology();

        // Frequency tiers: highest => Prime, lowest => Efficiency, middle => Performance.
        long hi = freqs.front().first, lo = freqs.front().first;
        for (const auto& pf : freqs) {
            hi = std::max(hi, pf.first);
            lo = std::min(lo, pf.first);
        }

        for (const auto& pf : freqs) {
            if (pf.first == hi)       topo.prime_cores.push_back(pf.second);
            else if (pf.first == lo)  topo.efficiency_cores.push_back(pf.second);
            else                      topo.performance_cores.push_back(pf.second);
        }

        // Order: prime/performance descending by index (=> 7, then 6,5,4),
        // efficiency ascending. Stable, deterministic worker->core mapping.
        std::sort(topo.prime_cores.rbegin(),       topo.prime_cores.rend());
        std::sort(topo.performance_cores.rbegin(), topo.performance_cores.rend());
        std::sort(topo.efficiency_cores.begin(),   topo.efficiency_cores.end());

        // If the top tier is not unique (multiple cores at peak freq), keep the
        // highest-indexed as the single Prime and demote the rest to Performance.
        if (topo.prime_cores.size() > 1) {
            const int prime = topo.prime_cores.front();
            for (size_t i = 1; i < topo.prime_cores.size(); ++i) {
                topo.performance_cores.push_back(topo.prime_cores[i]);
            }
            topo.prime_cores.assign(1, prime);
            std::sort(topo.performance_cores.rbegin(), topo.performance_cores.rend());
        }

        if (topo.prime_cores.empty()) return fallback_topology();

        topo.detected = true;
        RLLM_LOGI("detected topology: prime=%d perf=%zu eff=%zu total=%d",
                  topo.prime_cores.front(), topo.performance_cores.size(),
                  topo.efficiency_cores.size(), topo.total_cpus);
        return topo;
    }

// =============================================================================
//  Affinity primitives
// =============================================================================
    int HardwareAffinity::set_affinity_tid(pid_t tid, const std::vector<int>& cpus) {
        if (cpus.empty()) return -EINVAL;

        cpu_set_t set;
        CPU_ZERO(&set);
        for (const int c : cpus) {
            if (c < 0 || c >= CPU_SETSIZE) {
                RLLM_LOGE("cpu index %d out of range [0, %d)", c, CPU_SETSIZE);
                return -ERANGE;
            }
            CPU_SET(c, &set);
        }

        if (::sched_setaffinity(tid, sizeof(set), &set) != 0) {
            const int e = errno;
            RLLM_LOGE("sched_setaffinity(tid=%d) failed: %s", tid, std::strerror(e));
            return -e;
        }
        return 0;
    }

    int HardwareAffinity::pin_to_cpu(int cpu) {
        return set_affinity_tid(current_tid(), std::vector<int>{cpu});
    }

    int HardwareAffinity::pin_to_cpus(const std::vector<int>& cpus) {
        return set_affinity_tid(current_tid(), cpus);
    }

    int HardwareAffinity::pin_to_class(const CoreTopology& topo, CoreClass cls) {
        const std::vector<int>* v = nullptr;
        switch (cls) {
            case CoreClass::Prime:       v = &topo.prime_cores;       break;
            case CoreClass::Performance: v = &topo.performance_cores; break;
            case CoreClass::Efficiency:  v = &topo.efficiency_cores;  break;
        }
        if (v == nullptr || v->empty()) {
            RLLM_LOGE("no cores available for requested class");
            return -ENODEV;
        }
        return set_affinity_tid(current_tid(), *v);
    }

    int HardwareAffinity::request_realtime(int fifo_priority) {
        const int lo = ::sched_get_priority_min(SCHED_FIFO);
        const int hi = ::sched_get_priority_max(SCHED_FIFO);
        if (lo < 0 || hi < 0) {
            const int e = errno;
            return e ? -e : -ENOTSUP;
        }
        sched_param sp{};
        sp.sched_priority = std::max(lo, std::min(hi, fifo_priority));

        if (::sched_setscheduler(0, SCHED_FIFO, &sp) != 0) { // 0 => calling thread
            const int e = errno;
            return -e; // EPERM is the common, non-fatal case (no CAP_SYS_NICE)
        }
        return 0;
    }

// =============================================================================
//  Step 1: vendor priority handshake (write TID to the priority node)
// =============================================================================
    int HardwareAffinity::gain_ai_priority(pid_t tid, const std::string& path) {
        if (path.empty()) return -EINVAL;

        const int fd = ::open(path.c_str(), O_WRONLY | O_CLOEXEC);
        if (fd < 0) {
            return -errno; // -ENOENT (no such node) / -EACCES (SELinux) => caller degrades
        }

        char buf[32];
        const int len = std::snprintf(buf, sizeof(buf), "%d\n", static_cast<int>(tid));
        if (len <= 0) { ::close(fd); return -EINVAL; }

        int wrote = 0;
        while (wrote < len) {
            const ssize_t w = ::write(fd, buf + wrote, static_cast<size_t>(len - wrote));
            if (w > 0) { wrote += static_cast<int>(w); continue; }
            if (w < 0 && errno == EINTR) continue;
            const int e = errno;
            int tmp = fd; do {} while (::close(tmp) != 0 && errno == EINTR);
            return e ? -e : -EIO;
        }

        int rc; do { rc = ::close(fd); } while (rc != 0 && errno == EINTR);
        return 0;
    }

// =============================================================================
//  Phase-shifting placement (full two-step handshake)
// =============================================================================
    namespace {

// Best-effort step 1 for the calling thread; never fatal.
        void apply_priority(pid_t tid, const AffinityConfig& cfg) {
            if (cfg.use_ai_priority) {
                const int pr = HardwareAffinity::gain_ai_priority(tid, cfg.ai_priority_path);
                if (pr != 0) {
                    RLLM_LOGW("ai-priority node \"%s\" unavailable (%s); continuing "
                              "without vendor elevation",
                              cfg.ai_priority_path.c_str(), std::strerror(-pr));
                } else {
                    RLLM_LOGI("ai-priority granted for tid=%d via %s",
                              tid, cfg.ai_priority_path.c_str());
                }
            }
            if (cfg.use_sched_fifo) {
                const int rr = HardwareAffinity::request_realtime(cfg.fifo_priority);
                if (rr != 0) {
                    RLLM_LOGW("SCHED_FIFO(prio=%d) denied (%s); running at default policy",
                              cfg.fifo_priority, std::strerror(-rr));
                }
            }
        }

    } // namespace

    int HardwareAffinity::configure_prefill_worker(const CoreTopology& topo,
                                                   int worker_index,
                                                   const AffinityConfig& cfg) {
        if (worker_index < 0 || worker_index >= cfg.prefill_threads) {
            RLLM_LOGE("prefill worker_index %d out of range [0,%d)",
                      worker_index, cfg.prefill_threads);
            return -ERANGE;
        }

        // Resolve the target core: worker 0 -> Prime (7); workers 1.. -> X4 (6,5,4).
        int cpu = -1;
        if (worker_index == 0) {
            if (topo.prime_cores.empty()) return -ENODEV;
            cpu = topo.prime_cores.front();
        } else {
            const size_t pidx = static_cast<size_t>(worker_index - 1);
            if (pidx >= topo.performance_cores.size()) {
                RLLM_LOGE("no performance core for prefill worker %d", worker_index);
                return -ERANGE;
            }
            cpu = topo.performance_cores[pidx];
        }

        const pid_t tid = current_tid();
        apply_priority(tid, cfg); // step 1 (best-effort)

        const int rc = set_affinity_tid(tid, std::vector<int>{cpu}); // step 2 (authoritative)
        if (rc == 0) {
            RLLM_LOGI("prefill worker %d (tid=%d) pinned to cpu%d [%s]",
                      worker_index, tid, cpu,
                      worker_index == 0 ? "X925/Prime" : "X4/Perf");
        }
        return rc;
    }

    int HardwareAffinity::configure_decode_worker(const CoreTopology& topo,
                                                  const AffinityConfig& cfg) {
        if (topo.prime_cores.empty()) {
            RLLM_LOGE("no prime core available for decode worker");
            return -ENODEV;
        }
        const int   cpu = topo.prime_cores.front(); // exclusively core 7 (X925)
        const pid_t tid = current_tid();

        apply_priority(tid, cfg); // step 1 (best-effort)

        const int rc = set_affinity_tid(tid, std::vector<int>{cpu}); // step 2 (authoritative)
        if (rc == 0) {
            RLLM_LOGI("decode worker (tid=%d) locked exclusively to cpu%d "
                      "[X925/Prime, 2MB private L2]", tid, cpu);
        }
        return rc;
    }

} // namespace ratherllm
