cmake_minimum_required(VERSION 3.22.1)
project("buddy_jni" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libllama.so")

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libggml.so")

add_library(ggml_base SHARED IMPORTED)
set_target_properties(ggml_base PROPERTIES IMPORTED_LOCATION "${JNILIBS_DIR}/libggml-base.so")

add_library(buddy_jni SHARED buddy_jni.cpp)

target_include_directories(buddy_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")

target_link_libraries(
    buddy_jni
    llama
    ggml
    ggml_base
    android
    log
)
