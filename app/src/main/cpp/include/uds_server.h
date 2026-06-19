// =============================================================================
//  ratherllm :: uds_server.h
//
//  Asynchronous IPC boundary between the native MLLM engine and the Kotlin UI
//  (com.kotlin.ratherllm). Streams generated tokens over a NON-BLOCKING,
//  ABSTRACT-NAMESPACE Unix Domain Socket multiplexed with epoll (edge-triggered).
//  No TCP loopback is present in this path.
//
//  Wire framing (byte stream over SOCK_STREAM):
//    [ WireHeader (12 bytes, little-endian) ][ payload (payload_len bytes) ]
//      magic       : 0x524C4C4D  ("RLLM")  -- mismatch => connection closed
//      type        : MessageType
//      flags       : bit0 = final (last chunk of a stream)
//      payload_len : <= UdsConfig::recv_buf_bytes, else connection closed
//
//  Payload encodings (little-endian, self-describing, dependency-free):
//
//    GenerateRequest (client -> server):
//      u64 request_id
//      u32 max_tokens
//      f32 temperature
//      u32 top_k
//      u32 prompt_len    ; prompt_len bytes (UTF-8)
//      u32 image_uri_len ; image_uri_len bytes (UTF-8, may be 0)
//
//    TokenChunk (server -> client):  flags bit0 carries `final`
//      u64 request_id
//      i32 token_id
//      u32 text_len      ; text_len bytes (UTF-8)
//
//    Cancel (client -> server):
//      u64 request_id
// =============================================================================
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace ratherllm {

// -----------------------------------------------------------------------------
// Wire protocol
// -----------------------------------------------------------------------------
    inline constexpr uint32_t kWireMagic = 0x524C4C4Du; // "RLLM"

    enum class MessageType : uint16_t {
        GenerateRequest = 1, // client -> server
        TokenChunk      = 2, // server -> client
        Cancel          = 3, // client -> server
        Error           = 4, // server -> client
    };

    enum WireFlags : uint16_t {
        kFlagNone  = 0,
        kFlagFinal = 1u << 0, // last chunk of a stream (EOS / stop)
    };

#pragma pack(push, 1)
    struct WireHeader {
        uint32_t magic;       // must equal kWireMagic
        uint16_t type;        // MessageType
        uint16_t flags;       // WireFlags bitmask
        uint32_t payload_len; // bytes immediately following this header
    };
#pragma pack(pop)
    static_assert(sizeof(WireHeader) == 12, "WireHeader must be exactly 12 bytes");

// -----------------------------------------------------------------------------
// Public data models
// -----------------------------------------------------------------------------

// A single token emitted by the engine, plus stream framing metadata.
    struct TokenChunk {
        uint64_t    request_id = 0;  // correlates to the originating GenerateRequest
        int32_t     token_id   = -1; // model vocabulary id (-1 when text-only payload)
        std::string text;            // UTF-8 detokenized fragment
        bool        final      = false; // true on EOS / generation stop
    };

// Parsed request from the Kotlin client.
    struct GenerateRequest {
        uint64_t    request_id  = 0;
        std::string prompt;            // UTF-8 prompt text
        std::string image_uri;         // optional multimodal input ("" if none)
        uint32_t    max_tokens  = 512;
        float       temperature = 0.8f; // >= 0
        uint32_t    top_k       = 40;   // 0 => disabled
    };

// Callback invoked (ON THE IO THREAD) when a complete request is parsed.
// The engine should enqueue work and return immediately; it MUST NOT block.
    using RequestHandler = std::function<void(int client_fd, GenerateRequest&&)>;

// Callback invoked (ON THE IO THREAD) when a Cancel message is parsed.
    using CancelHandler = std::function<void(uint64_t request_id)>;

// -----------------------------------------------------------------------------
// Configuration
// -----------------------------------------------------------------------------
    struct UdsConfig {
        // Abstract-namespace name WITHOUT the leading NUL; the implementation binds
        // to sun_path = '\0' + abstract_name. Default => "\0poco_mllm_uds_pipe".
        std::string abstract_name   = "poco_mllm_uds_pipe";
        int         backlog         = 16;
        size_t      max_clients     = 8;
        size_t      recv_buf_bytes  = 64u * 1024u;        // inbound framing cap / client
        size_t      send_hwm_bytes  = 1u * 1024u * 1024u; // per-client send high-water mark
        size_t      ring_capacity   = 4096;               // SPSC token ring slots (rounded ^2)
        bool        verify_peer_uid = true;               // SO_PEERCRED gate on accept
        uint32_t    expected_uid    = 0xFFFFFFFFu;        // 0xFFFFFFFF => use geteuid()
        bool        raise_nofile    = true;               // best-effort RLIMIT_NOFILE bump
    };

// -----------------------------------------------------------------------------
// UdsServer
// -----------------------------------------------------------------------------
    class UdsServer {
    public:
        explicit UdsServer(UdsConfig cfg);
        ~UdsServer();

        UdsServer(const UdsServer&)            = delete;
        UdsServer& operator=(const UdsServer&) = delete;
        UdsServer(UdsServer&&)                 = delete;
        UdsServer& operator=(UdsServer&&)      = delete;

        // Bind (abstract ns) + listen + create epoll + control eventfds.
        // Returns 0 on success, -errno on failure (all partial state is released).
        int init();

        // Spawn the IO thread running the epoll loop. Returns 0 on success,
        // -EALREADY if already running, -EINVAL if init() has not succeeded.
        int start(RequestHandler on_request, CancelHandler on_cancel = nullptr);

        // Producer API (thread-safe; intended to be called from the decode thread):
        // enqueue a chunk for delivery and wake the IO thread via eventfd.
        // Returns false if the SPSC ring is currently full (apply backpressure).
        bool publish(const TokenChunk& chunk);

        // Request cooperative shutdown and join the IO thread. Idempotent.
        void stop();

        bool running() const noexcept {
            return running_.load(std::memory_order_acquire);
        }

    private:
        // Per-fd connection state (recv/send buffers + epoll flags).
        struct ClientState {
            int                  fd         = -1;
            std::vector<uint8_t> rx;                 // accumulated inbound bytes
            std::vector<uint8_t> tx;                 // pending outbound bytes
            size_t               tx_sent    = 0;     // already-flushed offset within tx
            bool                 want_write = false; // EPOLLOUT currently armed
            bool                 closing    = false; // drain tx, then close
            uint64_t             active_req = 0;     // 0 == idle
        };

        // Lock-free single-producer / single-consumer ring of TokenChunks.
        class SpscRing {
        public:
            void   reset(size_t capacity_pow2);
            bool   try_push(const TokenChunk& v);
            bool   try_pop(TokenChunk& out);
        private:
            std::vector<TokenChunk> buf_;
            size_t                  mask_ = 0;
            std::atomic<size_t>     head_{0}; // producer index
            std::atomic<size_t>     tail_{0}; // consumer index
        };

        // --- IO thread internals -------------------------------------------------
        void io_loop();
        void on_accept();
        void on_readable(int fd);
        void on_writable(int fd);
        void on_wakeup();
        void drain_ring_to_clients();
        void dispatch_message(int fd, const WireHeader& h, const uint8_t* payload);
        void append_framed(ClientState& cs, const TokenChunk& chunk);
        void close_client(int fd);

        // --- epoll helpers -------------------------------------------------------
        int  add_epoll(int fd, uint32_t events);
        int  mod_epoll(int fd, uint32_t events);

        // --- teardown helper -----------------------------------------------------
        void release_resources();

        UdsConfig                            cfg_;
        int                                  listen_fd_ = -1;
        int                                  epoll_fd_  = -1;
        int                                  wake_fd_   = -1; // token-available eventfd
        int                                  quit_fd_   = -1; // shutdown eventfd
        std::thread                          io_thread_;
        std::atomic<bool>                    running_{false};
        bool                                 initialized_ = false;

        std::unordered_map<int, ClientState> clients_;        // fd -> state (IO thread)
        std::unordered_map<uint64_t, int>    req_to_fd_;       // request_id -> fd (IO thread)
        SpscRing                             ring_;            // decode -> IO handoff
        RequestHandler                       on_request_;
        CancelHandler                        on_cancel_;
    };

} // namespace ratherllm
