# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.18.0)

project(trime VERSION 3.0.0)

# AGP 对 debug 变体传 -DCMAKE_BUILD_TYPE=Debug，编译命令里只有 -g、没有任何 -O，
# 整个 librime 以 -O0 运行：部署时的 prism 编译、输入时的音节图 / 整句组词都会慢数倍，
# 与官方 release 包对比尤其明显。NDK r30 的 toolchain 还会把各 *_FLAGS_RELEASE 置空，
# 且 AGP 的 release 变体实际用的是 RelWithDebInfo（默认 -O2 -g），所以这里对所有
# 非 Debug 类型统一强制 -O3 -DNDEBUG，保证本地离线编译的 release 包与官方发行版一致。
# 只影响非 Debug 变体，Debug 行为不变。
if(ANDROID AND NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
  set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "Release C flags" FORCE)
  set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "Release CXX flags" FORCE)
  set(CMAKE_C_FLAGS_RELWITHDEBINFO "-O3 -DNDEBUG" CACHE STRING "RelWithDebInfo C flags" FORCE)
  set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -DNDEBUG" CACHE STRING "RelWithDebInfo CXX flags" FORCE)
  set(CMAKE_C_FLAGS_MINSIZEREL "-O3 -DNDEBUG" CACHE STRING "MinSizeRel C flags" FORCE)
  set(CMAKE_CXX_FLAGS_MINSIZEREL "-O3 -DNDEBUG" CACHE STRING "MinSizeRel CXX flags" FORCE)
endif()

# for reproducible build
add_link_options("LINKER:--hash-style=both,--build-id=none")

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

# general options
option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

include(Boost)

option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
# NDK 30 provides libunwind.h but no target libunwind library. With any value
# other than none, glog's FindUnwind resolves the host x86_64 libunwind.so and
# fails when linking for other ABIs, so disable its unwind driver entirely.
set(WITH_UNWIND none CACHE STRING "unwind driver" FORCE)
add_subdirectory(librime/deps/glog)
target_compile_options(
  glog_internal PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory(librime/deps/yaml-cpp)

# enable compress for leveldb
option(SNAPPY_BUILD_TESTS "" OFF)
option(SNAPPY_BUILD_BENCHMARKS "" OFF)
option(SNAPPY_INSTALL "" OFF)
add_subdirectory(snappy)

set(HAVE_SNAPPY TRUE)
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory(librime/deps/leveldb)

add_subdirectory(librime/deps/marisa-trie)
target_compile_options(
  marisa PRIVATE "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")

set(LIBMARISA marisa)
option(USE_SYSTEM_MARISA "" ON)
add_subdirectory(librime/deps/opencc)
include(OpenccWorkarounds)

set(X11Keysym "${CMAKE_SOURCE_DIR}/librime/include")
include_directories("${X11Keysym}")

include(Rime)

add_subdirectory(librime_jni)
