# Native core of the Ephora viewer: Second Life primitive geometry.
#
# Everything the renderer needs to turn protocol data into triangles lives here,
# because it is the one part of the pipeline that is hot (thousands of prims can
# appear at once) and completely independent of the graphics API. The Android
# side only ever sees meshes and textures (see slcore_jni.cpp), so the renderer
# backend can be replaced without touching this code.
#
# The standalone tests are not built by the app; turn SLCORE_BUILD_TESTS on to
# build and run them on a desktop:
#   cmake -B build -DSLCORE_BUILD_TESTS=ON && cmake --build build && ctest --test-dir build

cmake_minimum_required(VERSION 3.22.1)

project(slcore CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_CODEC OFF CACHE BOOL "" FORCE)
set(BUILD_MJ2 OFF CACHE BOOL "" FORCE)
set(BUILD_JPIP OFF CACHE BOOL "" FORCE)
set(BUILD_JPT OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOC OFF CACHE BOOL "" FORCE)
set(BUILD_LUTS_GENERATOR OFF CACHE BOOL "" FORCE)
set(BUILD_THIRDPARTY OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        openjpeg
        URL https://github.com/uclouvain/openjpeg/archive/refs/tags/v2.5.4.zip
)
FetchContent_MakeAvailable(openjpeg)

add_library(slcore SHARED
        sl_prim_geometry.cpp
        sl_j2k_decode.cpp
        slcore_jni.cpp)

target_include_directories(slcore PRIVATE ${openjpeg_SOURCE_DIR}/src/lib/openjp2 ${openjpeg_BINARY_DIR}/src/lib/openjp2)

target_compile_options(slcore PRIVATE -Wall -Wextra -fno-exceptions -fno-rtti -Os)
target_link_libraries(slcore PRIVATE log openjp2)

option(SLCORE_BUILD_TESTS "Build the standalone sl_prim_geometry tests" OFF)

if (SLCORE_BUILD_TESTS)
    enable_testing()
    add_executable(slcore_tests
            slcore_tests.cpp
            sl_prim_geometry.cpp)
    target_compile_options(slcore_tests PRIVATE -Wall -Wextra -O1)
    add_test(NAME slcore_tests COMMAND slcore_tests)
endif ()
