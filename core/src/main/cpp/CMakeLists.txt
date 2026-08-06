cmake_minimum_required(VERSION 3.0)

message(STATUS "CMAKE_CURRENT_SOURCE_DIR= ${CMAKE_CURRENT_SOURCE_DIR}")

# 获取 MikeKen-Ken/mihomo（clash-foss）子模块完整 commit，按路径读取，不依赖 foreach 顺序
set(MIHOMO_SUBMODULE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../foss/golang/clash")
message(STATUS "Getting mihomo commit hash from ${MIHOMO_SUBMODULE_DIR}")
if (EXISTS "${MIHOMO_SUBMODULE_DIR}/.git" OR EXISTS "${MIHOMO_SUBMODULE_DIR}/go.mod")
    execute_process(
        COMMAND git -C "${MIHOMO_SUBMODULE_DIR}" rev-parse HEAD
        OUTPUT_VARIABLE COMMIT_HASH
        RESULT_VARIABLE MIHOMO_GIT_RESULT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )
    if (NOT MIHOMO_GIT_RESULT EQUAL 0 OR COMMIT_HASH STREQUAL "")
        message(FATAL_ERROR "Failed to read mihomo commit hash; init submodule core/src/foss/golang/clash first")
    endif ()
else ()
    message(FATAL_ERROR "mihomo submodule missing at ${MIHOMO_SUBMODULE_DIR}; run: git submodule update --init core/src/foss/golang/clash")
endif ()
set(GIT_VERSION "${COMMIT_HASH}")
message(STATUS "mihomo commit hash= ${GIT_VERSION}")

message(STATUS "Final version info = ${GIT_VERSION}")

# 保存 变量 到 文件
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/version.h.in ${CMAKE_CURRENT_SOURCE_DIR}/version.h @ONLY)

project(clash-bridge C)


set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

set(GO_OUTPUT_BASE ${GO_OUTPUT}/${FLAVOR_NAME})

if ("${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Debug")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "Release")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "RelWithDebInfo")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
else ()
    message(FATAL_ERROR "Unknown build type ${CMAKE_BUILD_TYPE}")
endif ()

include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")

link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c)
target_link_libraries(bridge log clash)
