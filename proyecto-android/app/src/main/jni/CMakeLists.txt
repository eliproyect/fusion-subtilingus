# ============================================================
# CMake: el "hormigón" que junta nuestro C con whisper.cpp
# ============================================================
# Truco clave: whisper.cpp NO viaja en el ZIP del proyecto. Se
# descarga durante la compilación (GitHub Actions tiene internet)
# con FetchContent, y el NDK lo compila para ARM64. Así nuestro
# proyecto solo lleva este archivo y whisper-jni.c.
# ============================================================
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
