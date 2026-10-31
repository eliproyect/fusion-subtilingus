// ============================================================
// Subtilingus · Puente JNI con whisper.cpp
// ============================================================
// JNI = la forma en que Kotlin llama a funciones escritas en C.
// Kotlin declara "external fun" (WhisperBridge.kt) y Android busca
// AQUÍ la función con este nombre tan largo, que codifica paquete
// + clase + método. Es el equivalente C de un @Override.
// ============================================================

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

// Carga el modelo desde un archivo y devuelve el puntero al contexto
JNIEXPORT jlong JNICALL
Java_com_elisotopo_subtilingus_WhisperBridge_initContext(JNIEnv *env, jobject thiz, jstring modelPath)
{
    (void) thiz;
    if (modelPath == NULL) return 0;

    const char *ruta = (*env)->GetStringUTFChars(env, modelPath, NULL);
    if (ruta == NULL) return 0;

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = 0; // en móvil, CPU

    struct whisper_context *ctx = whisper_init_from_file_with_params(ruta, cparams);
    (*env)->ReleaseStringUTFChars(env, modelPath, ruta);

    return (jlong)(intptr_t)ctx;
}

// Libera el contexto (el modelo deja de ocupar memoria)
JNIEXPORT void JNICALL
Java_com_elisotopo_subtilingus_WhisperBridge_freeContext(JNIEnv *env, jobject thiz, jlong ctxPtr)
{
    (void) env; (void) thiz;
    if (ctxPtr != 0) {
        whisper_free((struct whisper_context *)(intptr_t)ctxPtr);
    }
}

// El corazón: muestras de audio -> texto
JNIEXPORT jstring JNICALL
Java_com_elisotopo_subtilingus_WhisperBridge_transcribeSamples(JNIEnv *env, jobject thiz,
        jlong ctxPtr, jfloatArray samples, jstring lang)
{
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)ctxPtr;
    if (ctx == NULL || samples == NULL) {
        return (*env)->NewStringUTF(env, "");
    }

    // 1) Accedemos al array de floats de Kotlin desde C (sin copiarlo)
    const jsize n = (*env)->GetArrayLength(env, samples);
    jfloat *datos = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (datos == NULL) {
        return (*env)->NewStringUTF(env, "");
    }

    // 2) Configuramos Whisper: greedy = decodificación rápida y
    //    determinista; sin timestamps, sin logs, idioma elegido.
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4;             // tu Redmi: 4 hilos van muy finos
    params.translate = false;         // transcribir, no traducir (¿todavía?)
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = true;
    params.language = NULL;
    if (lang != NULL) {
        const char *idioma = (*env)->GetStringUTFChars(env, lang, NULL);
        if (idioma != NULL) {
            params.language = idioma;
        }
    }

    // 3) La magia: decenas de capas de red neuronal en este bloque
    int rc = whisper_full(ctx, params, datos, (int)n);

    if (lang != NULL && params.language != NULL) {
        (*env)->ReleaseStringUTFChars(env, lang, params.language);
    }
    (*env)->ReleaseFloatArrayElements(env, samples, datos, JNI_ABORT);

    if (rc != 0) {
        return (*env)->NewStringUTF(env, "");
    }

    // 4) Whisper devuelve el texto en "segmentos": los unimos todos
    const int nSegmentos = whisper_full_n_segments(ctx);
    if (nSegmentos <= 0) {
        return (*env)->NewStringUTF(env, "");
    }

    size_t total = 1;
    for (int i = 0; i < nSegmentos; i++) {
        total += strlen(whisper_full_get_segment_text(ctx, i)) + 1;
    }
    char *buffer = (char *)malloc(total);
    if (buffer == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    buffer[0] = '\0';
    for (int i = 0; i < nSegmentos; i++) {
        strcat(buffer, whisper_full_get_segment_text(ctx, i));
    }
    jstring resultado = (*env)->NewStringUTF(env, buffer);
    free(buffer);
    return resultado;
}
