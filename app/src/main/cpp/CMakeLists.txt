cmake_minimum_required(VERSION 3.22)
project(RatherLLM C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -----------------------------------------------------------------------------
#  Compiler flags
#
#  Deliberately NO -march=armv9.2-a+sve2+sme (Dimensity 9500 ships no SME -> a
#  scalar/SVE2 codepath compiled unconditionally risks SIGILL) and NO -ffast-math
#  (the softmax uses -INFINITY, which -ffast-math turns into UB). CPU feature
#  dispatch (i8mm / dotprod / NEON) is delegated to llama.cpp's own build system
#  in Stage 2, which performs correct *runtime* detection.
# -----------------------------------------------------------------------------

find_library(LOG_LIB log REQUIRED)

# Stage 1 skeleton: only the JNI stub is compiled. llama.cpp is wired in at
# Stage 2 (git submodule at app/src/main/cpp/llama.cpp, add_subdirectory).
add_library(ratherllm SHARED
        src/jni_bridge.cpp
)

target_link_libraries(ratherllm
        ${LOG_LIB}
)
