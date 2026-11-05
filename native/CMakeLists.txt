# PROVISIONAL - pending GEN-PERF-1
cmake_minimum_required(VERSION 3.28)
project(agent_llama_bridge LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "The llama.cpp JNI bridge is built only for Android in GitHub Actions")
endif()
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "This Phase H runtime is ARM64-only")
endif()
if(NOT LLAMA_CPP_SOURCE_DIR OR NOT EXISTS "${LLAMA_CPP_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_CPP_SOURCE_DIR must point to the pinned upstream checkout")
endif()

# The upstream static libraries are linked into one JNI .so for simple APK packaging.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build llama.cpp libraries statically" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "Do not build command-line helper code" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Do not build upstream tests" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "Do not build command-line tools" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Do not build upstream examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Do not build a network server" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "Do not build the unified CLI application" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "Do not build a web UI" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "Do not fetch a prebuilt UI" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "No network/TLS support is needed in the on-device runtime" FORCE)

set(GGML_CPU ON CACHE BOOL "Enable the CPU backend" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Do not tune for the GitHub runner CPU" FORCE)
# // PROVISIONAL - pending GEN-PERF-1: requires fail-closed asimddp and asimdhp runtime guards.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "ARM64 CPU architecture for the guarded local runtime" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "Build one generic arm64 CPU backend" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "Link the CPU backend into the JNI library" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "Avoid an additional OpenMP runtime" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Use upstream portable CPU kernels" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "Keep the CI compile independent of runner caches" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "Do not build ggml tests" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "Do not build ggml examples" FORCE)

# Explicitly disable non-CPU backends; this build is not a GPU, NPU, or MediaPipe build.
set(GGML_CUDA OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_METAL OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_HIP OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_MUSA OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_SYCL OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_RPC OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_WEBGPU OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_OPENVINO OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_ET OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_ET_SYSEMU OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_VIRTGPU OFF CACHE BOOL "CPU-only runtime" FORCE)
set(GGML_VIRTGPU_BACKEND OFF CACHE BOOL "CPU-only runtime" FORCE)

add_subdirectory("${LLAMA_CPP_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(agent_llama SHARED llama_jni.cpp)
# PROVISIONAL - pending GEN-PERF-1
target_compile_features(agent_llama PRIVATE cxx_std_17)
target_link_libraries(agent_llama PRIVATE llama android log)
target_compile_options(agent_llama PRIVATE -Wall -Wextra)
set_target_properties(agent_llama PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
