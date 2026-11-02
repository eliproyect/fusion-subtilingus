# ============================================================
# CMake: el "hormigón" que junta nuestro C con whisper.cpp
# ============================================================
# Truco clave: whisper.cpp NO viaja en el ZIP del proyecto. Se
# descarga durante la compilación (GitHub Actions tiene internet)
# con FetchContent, y el NDK lo compila para ARM64. Así nuestro
# proyecto solo lleva este archivo y whisper-jni.c.
# ============================================================

# ============================================================
# FIX v0.5 (¡EL IMPORTANTE!): tu editor compila la variante DEBUG
# del APK. CMake, en modo Debug, compila el C++ con -O0 (sin
# optimizar), y ggml (el motor de Whisper) sin optimizar llega a
# ser 10-20 veces más lento: por eso un bloque de 4 segundos de
# voz tardaba 109 segundos de CPU en tu Redmi. Forzamos -O3 en
# TODOS los casos: el APK sigue siendo un debug instalable, pero
# con la librería C a la velocidad que debe.
# ============================================================
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")
# Y por si el tipo de build fuera "Debug", vaciamos sus flags
# (-O0 iría al final de la línea y ganaría a nuestro -O3)
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

cmake_minimum_required(VERSION 3.22.1)

# v0.7.1: la prueba en tu Redmi mostro Whisper ~50x mas lento de lo normal.
# Refuerzos para que la optimizacion NO se pueda perder:
#  1) tipo de build Release forzado
#  2) add_compile_options(-O3): va DESPUES de los flags de Debug en la
#     linea del compilador, asi que gana a cualquier -O0
#  3) instrucciones ARMv8.2 (dotprod + fp16) del Snapdragon 7s Gen 2 de
#     tu Redmi Note 13 Pro: aceleran las matematicas de Whisper.
#     (En un movil MUY antiguo esto daria "instruccion ilegal".)
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+dotprod+fp16")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod+fp16")
add_compile_options(-O3)

project(subtilingus LANGUAGES C CXX)

add_compile_options(-O3)

include(FetchContent)

# No queremos sus ejemplos ni tests: solo la librería (más rápido)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
  whisper
  GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
  GIT_TAG        v1.9.5
  GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(whisper)

# Nuestra librería: el puente JNI enlazado contra whisper
add_library(whisper_jni SHARED whisper-jni.c)
target_link_libraries(whisper_jni PRIVATE whisper)
