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
project(subtilingus LANGUAGES C CXX)

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
