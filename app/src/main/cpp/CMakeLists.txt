cmake_minimum_required(VERSION 3.22)
project(RatherLLM C CXX)

set(CMAKE_ANDROID_ARCH_HEADER_TRIPLE "aarch64-linux-android")
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Flag optimasi tingkat tinggi khusus untuk arsitektur ARMv9.2-A pada Dimensity 9500s
set(ARCH_FLAGS "-march=armv9.2-a+sve2+sme+dotprod+i8mm -fno-omit-frame-pointer -O3 -ffast-math")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${ARCH_FLAGS} -g")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${ARCH_FLAGS} -g")

option(GGML_CPU_KLEIDIAI "Enable KleidiAl ARM CPU optimizations" ON)
option(GGML_VULKAN "Enable Vulkan GPU backend" ON)

if (GGML_CPU_KLEIDIAI)
    add_definitions(-DGGML_USE_KLEIDIAI)
    set(KLEIDIAI_SRC "${CMAKE_CURRENT_SOURCE_DIR}/external/kleidiai")
    include_directories(${KLEIDIAI_SRC})
endif()

if (GGML_VULKAN)
    find_library(VULKAN_LIB vulkan REQUIRED)
    add_definitions(-DGGML_USE_VULKAN)
endif()

find_library(LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)

# Target library disesuaikan dengan template proyekmu: ratherllm
add_library(ratherllm SHARED
        src/mllm_engine.cpp
        src/neuropilot_adapter.cpp
        src/uds_server.cpp
        src/hardware_affinity.cpp
)

target_include_directories(ratherllm PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/external/llama.cpp/include
)

target_link_libraries(ratherllm
        ${ANDROID_LIB}
        ${VULKAN_LIB}
        ${LOG_LIB}
)