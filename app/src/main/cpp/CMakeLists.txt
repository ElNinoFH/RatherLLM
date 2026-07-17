cmake_minimum_required(VERSION 3.22)
project(RatherLLM C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# AGP compiles the debug variant's native code at -O0, which makes ggml ~10-50x
# slower (a 4B model would look hung). Force optimization for the Debug config so
# the installable debug APK still runs inference at usable speed. Release symbols
# are stripped into the APK by AGP regardless.
set(CMAKE_C_FLAGS_DEBUG   "-O3 -g -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g -DNDEBUG")

# -----------------------------------------------------------------------------
#  Compiler policy
#
#  No -march=...+sme (Dimensity 9500 has no SME -> SIGILL risk) and no
#  -ffast-math (breaks the -INFINITY used in softmax). ARM CPU feature dispatch
#  (NEON / dotprod / i8mm, plus Q4_0 online repack) is handled by ggml's own
#  build below, which does correct target-appropriate codegen.
# -----------------------------------------------------------------------------

# ---- llama.cpp build configuration (MUST precede add_subdirectory) ----------
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)  # static ggml/llama/common -> one .so
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)  # chat templates (minja), tokenize helpers
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)  # downloads are done in Kotlin
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # use ggml's own threadpool on Android
set(GGML_CPU_REPACK      ON  CACHE BOOL "" FORCE)  # runtime Q4_0 -> aarch64 repack (i8mm)
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)  # CPU-only in Stage 2 (Vulkan is a Stage 6 experiment; needs SPIRV-Headers)
set(GGML_CPU_KLEIDIAI    OFF CACHE BOOL "" FORCE)  # revisit in Stage 6 benchmarks
set(GGML_LLAMAFILE       ON  CACHE BOOL "" FORCE)

# Stage 6: force the CPU arch to the Dimensity 9500's real feature set. The NDK
# cross-compile otherwise defaults to armv8-a and the dotprod/i8mm feature probes
# fail, leaving decode on the slow NEON-only path. dotprod + i8mm are the big
# quantized-matmul accelerators (int8 matmul); fp16 helps f16 ops. NO sve2/sme
# (SIGILL risk / no runtime benefit here).
set(GGML_CPU_ARM_ARCH    "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)

add_subdirectory(llama.cpp)

find_library(LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)

add_library(ratherllm SHARED
        src/jni_bridge.cpp
        src/llama_engine.cpp
)

target_include_directories(ratherllm PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/common
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/vendor
)

target_link_libraries(ratherllm
        llama-common   # common utils lib (chat templates via minja, tokenize helpers)
        llama
        ${LOG_LIB}
        ${ANDROID_LIB}
)
