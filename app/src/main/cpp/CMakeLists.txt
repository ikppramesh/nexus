cmake_minimum_required(VERSION 3.22.1)
project(nexus_llm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# llama.cpp core + common (for running each model's own Jinja chat template); no tools/server.
# The Fold7's Snapdragon 8 Elite has dotprod, i8mm and fp16, which the quantized kernels use.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp build-llama)

add_library(nexus_llm SHARED nexus_llm.cpp)
target_link_libraries(nexus_llm llama llama-common log)
