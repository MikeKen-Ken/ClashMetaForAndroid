cmake_minimum_required(VERSION 3.0)

message(STATUS "CMAKE_CURRENT_SOURCE_DIR= ${CMAKE_CURRENT_SOURCE_DIR}")

# 获取 mihomo submodule 的完整 commit hash
message(STATUS "Getting mihomo commit hash")
execute_process(
    COMMAND git submodule foreach git log -1 --format=%H   
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} 
    OUTPUT_VARIABLE COMMIT_HASH
)
string(REPLACE "\n" ";" COMMIT_HASH "${COMMIT_HASH}")
list(GET COMMIT_HASH 1 COMMIT_HASH)
string(REGEX REPLACE "[\n\t\r]" "" COMMIT_HASH ${COMMIT_HASH})
# 使用完整的 commit hash，不截取
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
