// =============================================================================
//  ratherllm :: uds_server.cpp
//
//  Production implementation of the abstract-namespace, edge-triggered (EPOLLET)
//  Unix Domain Socket server used to stream MLLM tokens to the Kotlin UI.
//
//  Correctness properties upheld (see design.md):
//    C1  fd lifecycle (no leak / no double-close)     C7  no-loss-under-connection
//    C2  single-owner socket access (IO thread only)  C8  SPSC ring safety / FIFO
//    C3  edge-trigger completeness (drain to EAGAIN)   C9  (engine; n/a here)
//    C4  EPOLLOUT armed iff unsent bytes remain        C11 graceful shutdown order
//    C5  framing integrity (header + payload_len)      C12 idempotent close_client
//    C6  bounded backpressure (send high-water mark)
// =============================================================================
#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1 // accept4, SOCK_NONBLOCK, SOCK_CLOEXEC, MSG_NOSIGNAL
#endif

#include "../include/uds_server.h"

#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#if defined(__ANDROID__)
#include <android/log.h>
#define RLLM_LOG_TAG "ratherllm.uds"
#define RLLM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, RLLM_LOG_TAG, __VA_ARGS__)
#define RLLM_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  RLLM_LOG_TAG, __VA_ARGS__)
#define RLLM_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  RLLM_LOG_TAG, __VA_ARGS__)
#else
#define RLLM_LOGE(...) do { std::fprintf(stderr, "[ratherllm.uds][E] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define RLLM_LOGW(...) do { std::fprintf(stderr, "[ratherllm.uds][W] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define RLLM_LOGI(...) do { std::fprintf(stderr, "[ratherllm.uds][I] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace ratherllm {
    namespace {

// errno-safe close: never let close() clobber the caller's errno, and treat
// EINTR as success (Linux closes the fd even when interrupted).
        inline void safe_close(int& fd) {
            if (fd >= 0) {
                const int saved = errno;
                int rc;
                do { rc = ::close(fd); } while (rc != 0 && errno == EINTR);
                errno = saved;
                fd = -1;
            }
        }

        inline size_t round_up_pow2(size_t v) {
            if (v < 2) return 2;
            --v;
            for (size_t i = 1; i < sizeof(size_t) * 8; i <<= 1) v |= v >> i;
            return v + 1;
        }

// --- Little-endian payload (de)serialization helpers -------------------------
        inline void put_u32(std::vector<uint8_t>& b, uint32_t v) {
            b.push_back(uint8_t(v));        b.push_back(uint8_t(v >> 8));
            b.push_back(uint8_t(v >> 16));  b.push_back(uint8_t(v >> 24));
        }
        inline void put_i32(std::vector<uint8_t>& b, int32_t v) {
            put_u32(b, static_cast<uint32_t>(v));
        }
        inline void put_u64(std::vector<uint8_t>& b, uint64_t v) {
            for (int i = 0; i < 8; ++i) b.push_back(uint8_t(v >> (8 * i)));
        }
        inline void put_bytes(std::vector<uint8_t>& b, const std::string& s) {
            b.insert(b.end(), s.begin(), s.end());
        }

// Cursor-based reader with strict bounds checking. Returns false on overrun.
        struct Reader {
            const uint8_t* p;
            size_t         remaining;

            bool u32(uint32_t& out) {
                if (remaining < 4) return false;
                out = uint32_t(p[0]) | (uint32_t(p[1]) << 8) |
                      (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
                p += 4; remaining -= 4; return true;
            }
            bool i32(int32_t& out) {
                uint32_t u; if (!u32(u)) return false;
                out = static_cast<int32_t>(u); return true;
            }
            bool f32(float& out) {
                uint32_t u; if (!u32(u)) return false;
                std::memcpy(&out, &u, sizeof(out)); return true;
            }
            bool u64(uint64_t& out) {
                if (remaining < 8) return false;
                out = 0;
                for (int i = 0; i < 8; ++i) out |= uint64_t(p[i]) << (8 * i);
                p += 8; remaining -= 8; return true;
            }
            bool str(uint32_t len, std::string& out) {
                if (remaining < len) return false;
                out.assign(reinterpret_cast<const char*>(p), len);
                p += len; remaining -= len; return true;
            }
        };

    } // namespace

// =============================================================================
//  SpscRing
// =============================================================================
    void UdsServer::SpscRing::reset(size_t capacity_pow2) {
        buf_.assign(capacity_pow2, TokenChunk{});
        mask_ = capacity_pow2 - 1;
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

// Producer (decode thread). False when full.
    bool UdsServer::SpscRing::try_push(const TokenChunk& v) {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t next = (head + 1) & mask_;
        // Acquire the consumer's tail to learn how much space is available.
        if (next == (tail_.load(std::memory_order_acquire) & mask_)) {
            return false; // full
        }
        buf_[head] = v; // safe: consumer won't read this slot until head_ is published
        head_.store(next, std::memory_order_release);
        return true;
    }

// Consumer (IO thread). False when empty.
    bool UdsServer::SpscRing::try_pop(TokenChunk& out) {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == (head_.load(std::memory_order_acquire) & mask_)) {
            return false; // empty
        }
        out = std::move(buf_[tail]);
        buf_[tail] = TokenChunk{}; // release any held string capacity
        tail_.store((tail + 1) & mask_, std::memory_order_release);
        return true;
    }

// =============================================================================
//  Construction / destruction
// =============================================================================
    UdsServer::UdsServer(UdsConfig cfg) : cfg_(std::move(cfg)) {
        if (cfg_.ring_capacity < 2) cfg_.ring_capacity = 2;
        cfg_.ring_capacity = round_up_pow2(cfg_.ring_capacity);
        ring_.reset(cfg_.ring_capacity);
    }

    UdsServer::~UdsServer() {
        stop();              // C11: signal + join before any fd is touched
        release_resources(); // C1 : close every remaining fd exactly once
    }

// =============================================================================
//  epoll helpers
// =============================================================================
    int UdsServer::add_epoll(int fd, uint32_t events) {
        epoll_event ev{};
        ev.events  = events;
        ev.data.fd = fd;
        if (::epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, fd, &ev) != 0) {
            const int e = errno;
            RLLM_LOGE("epoll_ctl(ADD, fd=%d) failed: %s", fd, std::strerror(e));
            return -e;
        }
        return 0;
    }

    int UdsServer::mod_epoll(int fd, uint32_t events) {
        epoll_event ev{};
        ev.events  = events;
        ev.data.fd = fd;
        if (::epoll_ctl(epoll_fd_, EPOLL_CTL_MOD, fd, &ev) != 0) {
            const int e = errno;
            RLLM_LOGE("epoll_ctl(MOD, fd=%d) failed: %s", fd, std::strerror(e));
            return -e;
        }
        return 0;
    }

// =============================================================================
//  init(): abstract-namespace bind + listen + epoll + control eventfds
// =============================================================================
    int UdsServer::init() {
        if (initialized_) return 0;

        // Best-effort raise the open-file ceiling so max_clients is reachable.
        if (cfg_.raise_nofile) {
            rlimit rl{};
            if (::getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur < rl.rlim_max) {
                rlimit want = rl;
                want.rlim_cur = rl.rlim_max;
                if (::setrlimit(RLIMIT_NOFILE, &want) != 0) {
                    RLLM_LOGW("setrlimit(RLIMIT_NOFILE) failed: %s", std::strerror(errno));
                }
            }
        }

        // 1) Listening socket: non-blocking + close-on-exec from the start.
        listen_fd_ = ::socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
        if (listen_fd_ < 0) {
            const int e = errno;
            RLLM_LOGE("socket(AF_UNIX) failed: %s", std::strerror(e));
            release_resources();
            return -e;
        }

        // 2) Bind to the ABSTRACT namespace: sun_path[0] == '\0', name follows,
        //    NOT null-terminated. Effective path is "\0poco_mllm_uds_pipe".
        const std::string& name = cfg_.abstract_name;
        if (name.empty() || name.size() > sizeof(sockaddr_un{}.sun_path) - 1) {
            RLLM_LOGE("abstract_name length %zu invalid (max %zu)",
                      name.size(), sizeof(sockaddr_un{}.sun_path) - 1);
            release_resources();
            return -EINVAL;
        }

        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        addr.sun_path[0] = '\0'; // abstract-namespace marker
        std::memcpy(addr.sun_path + 1, name.data(), name.size());
        const auto addr_len =
                static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name.size());

        if (::bind(listen_fd_, reinterpret_cast<sockaddr*>(&addr), addr_len) != 0) {
            const int e = errno; // e.g. EADDRINUSE if a stale holder exists
            RLLM_LOGE("bind(@%s) failed: %s", name.c_str(), std::strerror(e));
            release_resources();
            return -e;
        }

        if (::listen(listen_fd_, cfg_.backlog) != 0) {
            const int e = errno;
            RLLM_LOGE("listen(backlog=%d) failed: %s", cfg_.backlog, std::strerror(e));
            release_resources();
            return -e;
        }

        // 3) epoll instance.
        epoll_fd_ = ::epoll_create1(EPOLL_CLOEXEC);
        if (epoll_fd_ < 0) {
            const int e = errno;
            RLLM_LOGE("epoll_create1 failed: %s", std::strerror(e));
            release_resources();
            return -e;
        }

        // 4) Control eventfds (non-blocking, close-on-exec).
        wake_fd_ = ::eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        if (wake_fd_ < 0) {
            const int e = errno;
            RLLM_LOGE("eventfd(wake) failed: %s", std::strerror(e));
            release_resources();
            return -e;
        }
        quit_fd_ = ::eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        if (quit_fd_ < 0) {
            const int e = errno;
            RLLM_LOGE("eventfd(quit) failed: %s", std::strerror(e));
            release_resources();
            return -e;
        }

        // 5) Register all control fds edge-triggered. Any failure rolls everything back.
        int rc;
        if ((rc = add_epoll(listen_fd_, EPOLLIN | EPOLLET)) != 0) { release_resources(); return rc; }
        if ((rc = add_epoll(wake_fd_,   EPOLLIN | EPOLLET)) != 0) { release_resources(); return rc; }
        if ((rc = add_epoll(quit_fd_,   EPOLLIN | EPOLLET)) != 0) { release_resources(); return rc; }

        initialized_ = true;
        RLLM_LOGI("UDS server initialized on abstract socket \"\\0%s\" (listen_fd=%d)",
                  name.c_str(), listen_fd_);
        return 0;
    }

// =============================================================================
//  start(): launch the IO thread
// =============================================================================
    int UdsServer::start(RequestHandler on_request, CancelHandler on_cancel) {
        if (!initialized_) return -EINVAL;
        if (running_.load(std::memory_order_acquire)) return -EALREADY;

        on_request_ = std::move(on_request);
        on_cancel_  = std::move(on_cancel);

        running_.store(true, std::memory_order_release);
        io_thread_ = std::thread([this] { io_loop(); });
        return 0;
    }

// =============================================================================
//  stop(): cooperative shutdown (C11)
// =============================================================================
    void UdsServer::stop() {
        bool was_running = running_.exchange(false, std::memory_order_acq_rel);

        // Always poke quit_fd_ if it exists so a parked epoll_wait returns promptly.
        if (quit_fd_ >= 0) {
            const uint64_t one = 1;
            ssize_t w;
            do { w = ::write(quit_fd_, &one, sizeof(one)); } while (w < 0 && errno == EINTR);
        }

        if (io_thread_.joinable()) {
            io_thread_.join(); // IO thread has fully exited before we touch its fds
        }
        (void)was_running;
    }

// =============================================================================
//  publish(): producer-side enqueue + wakeup (C7, C8)
// =============================================================================
    bool UdsServer::publish(const TokenChunk& chunk) {
        if (!running_.load(std::memory_order_acquire)) return false;
        if (!ring_.try_push(chunk)) {
            return false; // ring full => caller (decode thread) applies backpressure
        }
        // Single coalescible wakeup; the consumer drains the whole ring per wakeup.
        const uint64_t one = 1;
        ssize_t w;
        do { w = ::write(wake_fd_, &one, sizeof(one)); } while (w < 0 && errno == EINTR);
        if (w < 0 && errno != EAGAIN) {
            RLLM_LOGW("eventfd wake write failed: %s", std::strerror(errno));
        }
        return true;
    }

// =============================================================================
//  io_loop(): the single-threaded, edge-triggered dispatcher (C2, C3)
// =============================================================================
    void UdsServer::io_loop() {
        std::vector<epoll_event> evs(64);

        while (running_.load(std::memory_order_acquire)) {
            const int n = ::epoll_wait(epoll_fd_, evs.data(),
                                       static_cast<int>(evs.size()), -1);
            if (n < 0) {
                if (errno == EINTR) continue;
                RLLM_LOGE("epoll_wait failed: %s", std::strerror(errno));
                break;
            }

            for (int i = 0; i < n; ++i) {
                const int      fd = evs[i].data.fd;
                const uint32_t ev = evs[i].events;

                if (fd == quit_fd_) {
                    return; // C11: leave the loop; stop() will join + close fds
                } else if (fd == wake_fd_) {
                    on_wakeup();
                } else if (fd == listen_fd_) {
                    on_accept();
                } else {
                    // Client fd. HUP/ERR may co-occur with IN/OUT; service readable
                    // first so any final framed bytes are dispatched, then close.
                    if (ev & EPOLLIN)  on_readable(fd);
                    if (clients_.count(fd) && (ev & EPOLLOUT)) on_writable(fd);
                    if (clients_.count(fd) && (ev & (EPOLLHUP | EPOLLERR))) {
                        close_client(fd);
                    }
                }
            }

            // Grow the event buffer if we filled it (likely more readiness pending).
            if (n == static_cast<int>(evs.size()) && evs.size() < 1024) {
                evs.resize(evs.size() * 2);
            }
        }
    }

// =============================================================================
//  on_accept(): drain the listen backlog to EAGAIN (C3)
// =============================================================================
    void UdsServer::on_accept() {
        for (;;) {
            const int cfd = ::accept4(listen_fd_, nullptr, nullptr,
                                      SOCK_NONBLOCK | SOCK_CLOEXEC);
            if (cfd < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) break; // fully drained
                if (errno == EINTR) continue;
                // EMFILE/ENFILE/ECONNABORTED: stop this cycle, don't spin.
                RLLM_LOGW("accept4 failed: %s", std::strerror(errno));
                break;
            }

            if (clients_.size() >= cfg_.max_clients) {
                RLLM_LOGW("max_clients (%zu) reached; rejecting fd=%d",
                          cfg_.max_clients, cfd);
                int tmp = cfd; safe_close(tmp);
                continue;
            }

            // SO_PEERCRED gate: only serve peers from the expected UID.
            if (cfg_.verify_peer_uid) {
                ucred cred{};
                socklen_t clen = sizeof(cred);
                const uint32_t expect = (cfg_.expected_uid == 0xFFFFFFFFu)
                                        ? static_cast<uint32_t>(::geteuid())
                                        : cfg_.expected_uid;
                if (::getsockopt(cfd, SOL_SOCKET, SO_PEERCRED, &cred, &clen) != 0 ||
                    static_cast<uint32_t>(cred.uid) != expect) {
                    RLLM_LOGW("rejecting peer uid=%u (expected %u) on fd=%d",
                              static_cast<uint32_t>(cred.uid), expect, cfd);
                    int tmp = cfd; safe_close(tmp);
                    continue;
                }
            }

            ClientState cs;
            cs.fd = cfd;
            cs.rx.reserve(4096);
            clients_.emplace(cfd, std::move(cs));

            if (add_epoll(cfd, EPOLLIN | EPOLLET) != 0) { // EPOLLOUT armed lazily
                close_client(cfd);
                continue;
            }
            RLLM_LOGI("accepted client fd=%d (clients=%zu)", cfd, clients_.size());
        }
    }

// =============================================================================
//  on_readable(): drain recv to EAGAIN, then frame messages (C3, C5)
// =============================================================================
    void UdsServer::on_readable(int fd) {
        auto it = clients_.find(fd);
        if (it == clients_.end()) return;
        ClientState& cs = it->second;

        uint8_t tmp[16 * 1024];
        for (;;) {
            const ssize_t r = ::recv(fd, tmp, sizeof(tmp), 0);
            if (r > 0) {
                cs.rx.insert(cs.rx.end(), tmp, tmp + r);
                if (cs.rx.size() > cfg_.recv_buf_bytes) {
                    RLLM_LOGW("rx overflow on fd=%d (%zu > %zu); closing",
                              fd, cs.rx.size(), cfg_.recv_buf_bytes);
                    close_client(fd);
                    return;
                }
                continue; // ET: keep draining
            }
            if (r == 0) { close_client(fd); return; }               // peer closed
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;     // fully drained
            if (errno == EINTR) continue;
            RLLM_LOGW("recv(fd=%d) failed: %s", fd, std::strerror(errno));
            close_client(fd);
            return;
        }

        // Frame complete messages out of cs.rx in arrival order.
        size_t consumed = 0;
        while (cs.rx.size() - consumed >= sizeof(WireHeader)) {
            WireHeader h{};
            std::memcpy(&h, cs.rx.data() + consumed, sizeof(h));

            if (h.magic != kWireMagic) {
                RLLM_LOGW("bad magic 0x%08X on fd=%d; closing", h.magic, fd);
                close_client(fd);
                return;
            }
            if (h.payload_len > cfg_.recv_buf_bytes) {
                RLLM_LOGW("payload_len %u exceeds cap on fd=%d; closing",
                          h.payload_len, fd);
                close_client(fd);
                return;
            }

            const size_t total = sizeof(WireHeader) + h.payload_len;
            if (cs.rx.size() - consumed < total) break; // wait for more bytes

            dispatch_message(fd, h, cs.rx.data() + consumed + sizeof(WireHeader));
            consumed += total;

            // dispatch_message may have closed the client (protocol error).
            if (!clients_.count(fd)) return;
        }

        if (consumed > 0) {
            cs.rx.erase(cs.rx.begin(), cs.rx.begin() + static_cast<std::vector<uint8_t>::difference_type>(consumed));
        }
    }

// =============================================================================
//  dispatch_message(): decode a framed payload and invoke the handler (C5)
// =============================================================================
    void UdsServer::dispatch_message(int fd, const WireHeader& h, const uint8_t* payload) {
        Reader rd{payload, h.payload_len};

        switch (static_cast<MessageType>(h.type)) {
            case MessageType::GenerateRequest: {
                GenerateRequest req;
                uint32_t prompt_len = 0, image_len = 0;
                if (!rd.u64(req.request_id)  || !rd.u32(req.max_tokens) ||
                    !rd.f32(req.temperature) || !rd.u32(req.top_k)      ||
                    !rd.u32(prompt_len)      || !rd.str(prompt_len, req.prompt) ||
                    !rd.u32(image_len)       || !rd.str(image_len, req.image_uri)) {
                    RLLM_LOGW("malformed GenerateRequest on fd=%d; closing", fd);
                    close_client(fd);
                    return;
                }
                if (req.temperature < 0.0f) req.temperature = 0.0f;
                if (req.max_tokens == 0)    req.max_tokens   = 1;

                auto it = clients_.find(fd);
                if (it != clients_.end()) {
                    it->second.active_req = req.request_id;
                    req_to_fd_[req.request_id] = fd; // route TokenChunks back here
                }
                if (on_request_) on_request_(fd, std::move(req));
                break;
            }
            case MessageType::Cancel: {
                uint64_t rid = 0;
                if (!rd.u64(rid)) { close_client(fd); return; }
                if (on_cancel_) on_cancel_(rid);
                break;
            }
            default:
                RLLM_LOGW("unexpected message type %u on fd=%d; closing", h.type, fd);
                close_client(fd);
                break;
        }
    }

// =============================================================================
//  on_writable(): flush tx, manage EPOLLOUT (C4, C6)
// =============================================================================
    void UdsServer::on_writable(int fd) {
        auto it = clients_.find(fd);
        if (it == clients_.end()) return;
        ClientState& cs = it->second;

        while (cs.tx_sent < cs.tx.size()) {
            const ssize_t w = ::send(fd, cs.tx.data() + cs.tx_sent,
                                     cs.tx.size() - cs.tx_sent, MSG_NOSIGNAL);
            if (w > 0) { cs.tx_sent += static_cast<size_t>(w); continue; }
            if (errno == EAGAIN || errno == EWOULDBLOCK) break; // still backpressured
            if (errno == EINTR) continue;
            // EPIPE / ECONNRESET: peer is gone.
            RLLM_LOGW("send(fd=%d) failed: %s", fd, std::strerror(errno));
            close_client(fd);
            return;
        }

        if (cs.tx_sent == cs.tx.size()) {                 // fully flushed
            cs.tx.clear();
            cs.tx_sent = 0;
            if (cs.want_write) {                          // C4: disarm EPOLLOUT
                cs.want_write = false;
                if (mod_epoll(fd, EPOLLIN | EPOLLET) != 0) { close_client(fd); return; }
            }
            if (cs.closing) close_client(fd);             // drained -> finish close
        } else if (!cs.want_write) {                      // C4: arm EPOLLOUT
            cs.want_write = true;
            if (mod_epoll(fd, EPOLLIN | EPOLLOUT | EPOLLET) != 0) { close_client(fd); return; }
        }
    }

// =============================================================================
//  on_wakeup() / drain (C6, C7, C8)
// =============================================================================
    void UdsServer::on_wakeup() {
        // Clear the eventfd's level (ET: a single read collapses N writes).
        uint64_t drained = 0;
        ssize_t r;
        do { r = ::read(wake_fd_, &drained, sizeof(drained)); } while (r < 0 && errno == EINTR);
        drain_ring_to_clients();
    }

    void UdsServer::drain_ring_to_clients() {
        TokenChunk c;
        while (ring_.try_pop(c)) {
            auto rit = req_to_fd_.find(c.request_id);
            if (rit == req_to_fd_.end()) continue; // client gone => drop (C7 vacuous)
            const int fd = rit->second;

            auto cit = clients_.find(fd);
            if (cit == clients_.end()) { req_to_fd_.erase(rit); continue; }
            ClientState& cs = cit->second;

            // C6: enforce the per-client high-water mark.
            if (cs.tx.size() - cs.tx_sent > cfg_.send_hwm_bytes) {
                RLLM_LOGW("send HWM breached on fd=%d; closing stream", fd);
                cs.closing = true;
                req_to_fd_.erase(rit);
                on_writable(fd); // attempt to drain whatever is buffered, then close
                continue;
            }

            append_framed(cs, c);
            const bool was_final = c.final;
            on_writable(fd); // attempt immediate flush; arms EPOLLOUT on EAGAIN

            if (was_final && clients_.count(fd)) {
                // Stream complete: stop routing this request_id (idle the client).
                if (cit->second.active_req == c.request_id) cit->second.active_req = 0;
                req_to_fd_.erase(c.request_id);
            }
        }
    }

// =============================================================================
//  append_framed(): serialize WireHeader + TokenChunk payload into cs.tx
// =============================================================================
    void UdsServer::append_framed(ClientState& cs, const TokenChunk& chunk) {
        std::vector<uint8_t> payload;
        payload.reserve(8 + 4 + 4 + chunk.text.size());
        put_u64(payload, chunk.request_id);
        put_i32(payload, chunk.token_id);
        put_u32(payload, static_cast<uint32_t>(chunk.text.size()));
        put_bytes(payload, chunk.text);

        WireHeader h{};
        h.magic       = kWireMagic;
        h.type        = static_cast<uint16_t>(MessageType::TokenChunk);
        h.flags       = chunk.final ? kFlagFinal : kFlagNone;
        h.payload_len = static_cast<uint32_t>(payload.size());

        const uint8_t* hp = reinterpret_cast<const uint8_t*>(&h);
        cs.tx.insert(cs.tx.end(), hp, hp + sizeof(h));
        cs.tx.insert(cs.tx.end(), payload.begin(), payload.end());
    }

// =============================================================================
//  close_client(): idempotent teardown of one connection (C1, C12)
// =============================================================================
    void UdsServer::close_client(int fd) {
        auto it = clients_.find(fd);
        if (it == clients_.end()) return; // C12: already closed => no-op

        // Best-effort detach from epoll (ignore ENOENT — fd may already be gone).
        if (epoll_fd_ >= 0) {
            if (::epoll_ctl(epoll_fd_, EPOLL_CTL_DEL, fd, nullptr) != 0 &&
                errno != ENOENT && errno != EBADF) {
                RLLM_LOGW("epoll_ctl(DEL, fd=%d) failed: %s", fd, std::strerror(errno));
            }
        }

        if (it->second.active_req != 0) {
            req_to_fd_.erase(it->second.active_req);
        }
        clients_.erase(it);

        int tmp = fd;
        safe_close(tmp); // exactly one close per fd
        RLLM_LOGI("closed client fd=%d (clients=%zu)", fd, clients_.size());
    }

// =============================================================================
//  release_resources(): close every owned fd exactly once (C1)
// =============================================================================
    void UdsServer::release_resources() {
        // Detach + close all clients first.
        for (auto& kv : clients_) {
            int cfd = kv.second.fd;
            if (epoll_fd_ >= 0 && cfd >= 0) {
                ::epoll_ctl(epoll_fd_, EPOLL_CTL_DEL, cfd, nullptr);
            }
            safe_close(cfd);
        }
        clients_.clear();
        req_to_fd_.clear();

        safe_close(wake_fd_);
        safe_close(quit_fd_);
        safe_close(listen_fd_);
        safe_close(epoll_fd_);

        initialized_ = false;
    }

} // namespace ratherllm
