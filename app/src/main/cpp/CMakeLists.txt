cmake_minimum_required(VERSION 3.22)
project(RatherLLM C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ARMv9.2-A flags are valid ONLY on arm64-v8a. Applying them to x86/x86_64/
# armeabi-v7a is what produced "Valid target CPU values are: i386 ..." and the
# cascade of missing <cstddef>/<jni.h> headers. Guard by ABI.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ARCH_FLAGS "-march=armv9.2-a+sve2+sme+dotprod+i8mm -fno-omit-frame-pointer -O3 -ffast-math")
    set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${ARCH_FLAGS} -g")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${ARCH_FLAGS} -g")
endif()

option(GGML_CPU_KLEIDIAI "Enable KleidiAI ARM CPU optimizations" ON)
option(GGML_VULKAN       "Enable Vulkan GPU backend" ON)

# Only add the KleidiAI include path + define if the sources are actually vendored,
# otherwise the engine uses its built-in scalar fallback (RLLM_HAVE_KLEIDIAI unset).
if(GGML_CPU_KLEIDIAI)
    set(KLEIDIAI_SRC "${CMAKE_CURRENT_SOURCE_DIR}/external/kleidiai")
    if(EXISTS "${KLEIDIAI_SRC}")
        add_definitions(-DRLLM_HAVE_KLEIDIAI)
        include_directories(${KLEIDIAI_SRC})
    endif()
endif()

if(GGML_VULKAN)
    find_library(VULKAN_LIB vulkan)
    if(VULKAN_LIB)
        add_definitions(-DGGML_USE_VULKAN)
    endif()
endif()

find_library(LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)

add_library(ratherllm SHARED
        src/mllm_engine.cpp
        src/neuropilot_adapter.cpp
        src/uds_server.cpp
        src/hardware_affinity.cpp
        src/jni_bridge.cpp          # <-- was missing; JNI symbols now compiled in
)

target_include_directories(ratherllm PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(ratherllm
        ${ANDROID_LIB}
        ${LOG_LIB}
        ${VULKAN_LIB}
)
