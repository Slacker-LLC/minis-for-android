cmake_minimum_required(VERSION 3.22.1)
project(minis_native CXX C)

add_library(pty_bridge SHARED pty_bridge.c)

# Directory-handle based creation for the App-owned workspace file API. Java
# NIO's SecureDirectoryStream covers existing entries; this tiny JNI bridge is
# only needed for mkdirat-style creation of missing directory components.
add_library(minis_securefs SHARED secure_file_ops.cpp)

# T283: NDK signal handler for native crashes (SIGSEGV/SIGABRT/...).
# Separate library so loadLibrary() can pull it in independently of
# pty_bridge — the crash handler must be installed before any other
# native lib has a chance to fault.
add_library(minis_crash_handler SHARED crash_handler.cpp)

# [T-android-text-segmenter] CppJieba JNI bridge for TextSegmenter's Chinese
# path. cppjieba is header-only (no static lib to build); this version vendors
# limonp inline, so a single include root (cppjieba/include) is all it needs.
# Headers are included as "cppjieba/<Name>.hpp", so we add the parent dir.
add_library(jieba_jni SHARED jieba_jni.cpp)
target_include_directories(jieba_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/cppjieba/include
)

find_library(log-lib log)
target_link_libraries(pty_bridge ${log-lib})
target_link_libraries(minis_securefs ${log-lib})
target_link_libraries(minis_crash_handler ${log-lib})
target_link_libraries(jieba_jni ${log-lib})

# AGP 8.10 defaults to NDK r27, which does not emit 16 KB-aligned shared
# libraries by default. Keep every app-owned JNI library compatible with
# Android devices that use 16 KB pages.
target_link_options(pty_bridge PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
target_link_options(minis_securefs PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
target_link_options(minis_crash_handler PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
target_link_options(jieba_jni PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
