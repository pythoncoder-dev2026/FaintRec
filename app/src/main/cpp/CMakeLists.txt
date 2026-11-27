cmake_minimum_required(VERSION 3.22.1)
project(faintrec_lame LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Directory where LAME 3.100 C sources can be placed:
# app/src/main/cpp/lame/libmp3lame/*.c and app/src/main/cpp/lame/include/lame.h
set(LAME_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/lame")
set(LAME_SRC_DIR "${LAME_ROOT}/libmp3lame")
set(LAME_INCLUDE_DIR "${LAME_ROOT}/include")

set(JNI_SOURCES
    lame_jni.cpp
)

if(EXISTS "${LAME_SRC_DIR}/lame.c" AND EXISTS "${LAME_INCLUDE_DIR}/lame.h")
    message(STATUS "FaintRec: Building native libmp3lame from ${LAME_SRC_DIR}")
    set(LAME_C_SOURCES
        ${LAME_SRC_DIR}/bitstream.c
        ${LAME_SRC_DIR}/encoder.c
        ${LAME_SRC_DIR}/fft.c
        ${LAME_SRC_DIR}/gain_analysis.c
        ${LAME_SRC_DIR}/id3tag.c
        ${LAME_SRC_DIR}/lame.c
        ${LAME_SRC_DIR}/newmdct.c
        ${LAME_SRC_DIR}/presets.c
        ${LAME_SRC_DIR}/psymodel.c
        ${LAME_SRC_DIR}/quantize.c
        ${LAME_SRC_DIR}/quantize_pvt.c
        ${LAME_SRC_DIR}/reservoir.c
        ${LAME_SRC_DIR}/set_get.c
        ${LAME_SRC_DIR}/tables.c
        ${LAME_SRC_DIR}/takehiro.c
        ${LAME_SRC_DIR}/util.c
        ${LAME_SRC_DIR}/vbrquantize.c
        ${LAME_SRC_DIR}/VbrTag.c
        ${LAME_SRC_DIR}/version.c
    )

    add_library(faintrec_lame SHARED ${JNI_SOURCES} ${LAME_C_SOURCES})
    target_compile_definitions(faintrec_lame PRIVATE
        HAVE_LAME=1
        STDC_HEADERS=1
        HAVE_STDINT_H=1
        HAVE_LIMITS_H=1
        HAVE_MEMCPY=1
        IEEE754_FLOAT32_T=float
        TAKEHIRO_IEEE754_HACK=1
    )
    target_include_directories(faintrec_lame PRIVATE
        ${LAME_INCLUDE_DIR}
        ${LAME_SRC_DIR}
    )
else()
    message(STATUS "FaintRec: LAME sources not found at ${LAME_SRC_DIR}; building JNI bridge stub reporting LAME unavailable")
    add_library(faintrec_lame SHARED ${JNI_SOURCES})
    target_compile_definitions(faintrec_lame PRIVATE HAVE_LAME=0)
endif()

find_library(log-lib log)
target_link_libraries(faintrec_lame ${log-lib} m)
