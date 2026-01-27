cmake_minimum_required(VERSION 3.0)

# 尝试从远端 URL 获取版本号
set(REMOTE_VERSION_URL "https://github.com/MikeKen-Ken/mihomo/releases/download/Prerelease-Alpha/version.txt")
set(REMOTE_VERSION "")

message(STATUS "CMAKE_CURRENT_SOURCE_DIR= ${CMAKE_CURRENT_SOURCE_DIR}")
message(STATUS "Attempting to fetch version from: ${REMOTE_VERSION_URL}")

# 尝试使用 curl 获取远端版本号
find_program(CURL_EXECUTABLE curl)
if(CURL_EXECUTABLE)
    execute_process(
        COMMAND ${CURL_EXECUTABLE} -sL --connect-timeout 5 --max-time 10 "${REMOTE_VERSION_URL}"
        OUTPUT_VARIABLE REMOTE_VERSION
        ERROR_VARIABLE CURL_ERROR
        RESULT_VARIABLE CURL_RESULT
    )
    
    if(CURL_RESULT EQUAL 0 AND REMOTE_VERSION)
        # 清理版本号：去除换行符、空格等
        string(STRIP "${REMOTE_VERSION}" REMOTE_VERSION)
        string(REGEX REPLACE "[\n\r\t]" "" REMOTE_VERSION "${REMOTE_VERSION}")
        string(REGEX REPLACE "[ ]+" "" REMOTE_VERSION "${REMOTE_VERSION}")
        
        if(REMOTE_VERSION)
            set(GIT_VERSION "${REMOTE_VERSION}")
            message(STATUS "Successfully fetched remote version: ${GIT_VERSION}")
        else
            set(REMOTE_VERSION "")
        endif()
    else()
        message(STATUS "Failed to fetch remote version (curl result: ${CURL_RESULT}), falling back to local version")
        set(REMOTE_VERSION "")
    endif()
else()
    message(STATUS "curl not found, falling back to local version")
endif()

# 如果远端获取失败，使用本地 git hash 方式
if(NOT REMOTE_VERSION)
    message(STATUS "Using local git-based version")
    
    # 获取git hash
    execute_process(
        COMMAND git submodule foreach git log -1 --format=%H   
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} 
        OUTPUT_VARIABLE COMMIT_HASH
    )
    string(REPLACE "\n" ";" COMMIT_HASH "${COMMIT_HASH}")
    list(GET COMMIT_HASH 1 COMMIT_HASH)
    string (REGEX REPLACE "[\n\t\r]" "" COMMIT_HASH ${COMMIT_HASH})
    string(SUBSTRING ${COMMIT_HASH} 0 7 COMMIT_HASH)
    message(STATUS "git hash= ${COMMIT_HASH}")

    # 获取分支名称
    execute_process(
        COMMAND git submodule foreach git branch -r --contains ${COMMIT_HASH}
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} 
        OUTPUT_VARIABLE CURRENT_BRANCH
    )
    string(REPLACE "\n" ";" CURRENT_BRANCH "${CURRENT_BRANCH}")
    list(GET CURRENT_BRANCH 1 CURRENT_BRANCH)
    string (REGEX REPLACE "origin/" "" CURRENT_BRANCH ${CURRENT_BRANCH})
    string (REGEX REPLACE "[\n\t\r]" "" CURRENT_BRANCH ${CURRENT_BRANCH})
    message(STATUS "git current branch = ${CURRENT_BRANCH}")

    # 获取生成时间
    string(TIMESTAMP COMPILE_TIME "%y%m%d")
    string (REGEX REPLACE "[\n\t\r]" "" COMPILE_TIME ${COMPILE_TIME})
    string(REGEX REPLACE "\"" "" COMPILE_TIME ${COMPILE_TIME})

    # 生成版本信息
    set(GIT_VERSION "${CURRENT_BRANCH}_${COMMIT_HASH}_${COMPILE_TIME}")
    
    # 去除空格
    string(REGEX REPLACE "[ ]+" "" GIT_VERSION "${GIT_VERSION}")
endif()

message(STATUS "Final version info = ${GIT_VERSION}")

# 保存变量到文件
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
