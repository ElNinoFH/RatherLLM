# ratherllm

A fully on-device LLM chat app for Android. It runs GGUF models locally on the
CPU via [llama.cpp](https://github.com/ggml-org/llama.cpp) — no server, no
network inference, no account. Chat is multi-turn and streaming; models are
managed from inside the app (import a `.gguf` or download one by URL).

Built and tuned on a **POCO X8 Pro Max** (MediaTek Dimensity 9500 / MT6991,
Android 16). It supports models "in general" — Gemma, Qwen, Llama, Phi, Mistral,
etc. — and all the common quantizations (Q4_0, Q4_K_M, Q8_0, …).

## Highlights

- **On-device inference** with llama.cpp (ARM `dotprod` + `i8mm` int8-matmul
  kernels, NEON, fp16), streaming tokens straight from native code over JNI.
- **Multi-model / multi-architecture**: verified with Gemma 3 4B (gemma3) and
  Qwen 2.5 1.5B (qwen2) side by side.
- **Model management**: Storage-Access-Framework import and direct-URL download
  (with progress + HTTP Range resume + GGUF validation); per-model arch / quant
  / size / RAM estimate; load / delete / active indicator.
- **Chat UX**: Markdown rendering (code blocks with a copy button, inline code,
  bold, lists), a Stop button, regenerate / copy, conversation history that
  survives an app kill, and a subtle tokens/sec readout.
- **Power-user settings**: system prompt and full sampling controls (temperature,
  top-k, top-p, min-p, repetition penalty, max tokens) with a reset.
- **Resilient**: generation runs in a foreground service, so it keeps going
  through rotation, backgrounding, and rebinds; Material 3 dynamic theme.

## Architecture

```
MainActivity (Jetpack Compose, stateless UI)
        │  binds / observes StateFlows
        ▼
InferenceService (foreground service, type specialUse)
   owns: conversation • streaming state • the in-flight generation
         model repository + downloader • conversation store
        │  JNI (com.kotlin.ratherllm.NativeBridge)
        ▼
libratherllm.so
   llama_engine.cpp  — load (mmap, no-mlock), chat-template (llama-common/minja),
                       sampler chain, decode loop, cooperative cancel,
                       UTF-8-safe streaming, cheap GGUF metadata reads
   jni_bridge.cpp    — shared_ptr handle registry, per-token callback to Kotlin
                       (UTF-8→UTF-16), load-progress callback, log routing
        │  static-linked
        ▼
llama.cpp (git submodule, pinned to tag b10059) + ggml
```

Key decisions: the native engine is a thin wrapper over llama.cpp (the previous
from-scratch engine is gone); generation streams via a **direct JNI callback**
bridged to a Kotlin `Flow`/`StateFlow` (no UDS); the model is memory-mapped with
`use_mmap=true, use_mlock=false`; all state lives in the service so the Activity
is a pure observer.

## Build

Requirements: Android Studio (AGP 9.x / Gradle 9.4), Android SDK 37, **NDK 28+**,
CMake 3.22+, a JDK 21 (Android Studio's bundled JBR works).

```bash
git clone <this-repo>
cd RatherLLM
git submodule update --init          # fetches llama.cpp @ b10059
./gradlew :app:assembleDebug
```

Native config lives in `app/src/main/cpp/CMakeLists.txt`: llama.cpp is added with
`add_subdirectory` and static-linked; the CPU arch is pinned to
`armv8.2-a+dotprod+i8mm+fp16` (the Dimensity 9500's real feature set — no SME/SVE,
which would risk SIGILL). `abiFilters` is `arm64-v8a` only.

## Adding models

Open the **Models** screen (top-right icon):

- **Import .gguf** — pick a file via the system file picker. It is copied into
  the app with a progress bar and rejected if it isn't a valid GGUF.
- **Download URL** — paste a direct `.gguf` link (e.g. a Hugging Face
  `resolve/main/…gguf` URL). Downloads resume if interrupted.
- Two small starter models are offered on the empty state (Qwen 2.5 1.5B,
  Gemma 3 1B).

Models live in `filesDir/models/`. A legacy `filesDir/model.gguf` is migrated
automatically.

## Performance (measured on MT6991)

Config: CPU only, `use_mmap`, KV cache f16, `n_threads=4` (decode) /
`n_threads_batch=8` (prefill), i8mm+dotprod enabled.

| Model | Quant | Prefill | Decode | RSS |
|---|---|---|---|---|
| Gemma 3 4B | Q4_0 | ~58–66 tok/s | **11.0 tok/s** | ~5.0 GB* |
| Qwen 2.5 1.5B | Q4_K_M | ~93 tok/s | **24.8 tok/s** | ~1.9 GB |

\* RSS is high when RAM is abundant (more mmap pages stay resident); it compresses
to ~3.9 GB under pressure.

**Thread tuning** (Gemma 3 4B Q4_0 decode): 4 threads = 11.0 tok/s vs 8 threads =
7.7 tok/s — decode is memory-bound, so the 4 big cores (1×X925 + 3×X4) win and the
4 A720 little cores only add sync overhead. Prefill (compute-bound) uses all 8.

**i8mm impact**: enabling `dotprod`+`i8mm` took Gemma 3 4B decode from 6.1 tok/s
(NEON-only) to 11.0 tok/s (~1.8×).

> Note: on-device 4B inference needs the model's ~3 GB to stay resident. If the
> device's RAM is saturated by many other apps, the memory-mapped weights get
> evicted and decode thrashes (down to <1 tok/s) — this is inherent to running a
> large mmap'd model without `mlock` (which Android caps at ~64 KB).

## License

llama.cpp and ggml are MIT-licensed (see `app/src/main/cpp/llama.cpp/LICENSE`).
