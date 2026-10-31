// ============================================================
// Subtilingus · Puente JNI con whisper.cpp
// ============================================================
// JNI = la forma en que Kotlin llama a funciones escritas en C.
// Kotlin declara "external fun" (WhisperBridge.kt) y Android busca
// AQUÍ la función con este nombre tan largo, que codifica paquete
// + clase + método. Es el equivalente C de un @Override.
//
// g_estado es un "cuaderno" donde cada función anota lo que acaba
// de pasar. Kotlin lo lee con estadoNativo() y lo enseña en la
// pestaña Registro.
// ============================================================

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "whisper.h"

// Última palabra del mundo C. Solo ASCII: NewStringUTF es lo
// bastante quisquilloso como para no arriesgarse con acentos.
static char g_estado[512] = "nada ejecutado todavia";

// Carga el modelo desde un archivo y devuelve el puntero al contexto
JNIEXPORT jlong JNICALL
Java_com_elisotopo_subtilingus_WhisperBridge_initContext(JNIEnv *env, jobject thiz, jstring modelPath)
{
    (void) thiz;
    if (modelPath == NULL) {
        snprintf(g_estado, sizeof(g_estado), "init FALLO: ruta nula");
        return 0;
    }

    const char *ruta = (*env)->GetStringUTFChars(env, modelPath, NULL);
    if (ruta == NULL) {
        snprintf(g_estado, sizeof(g_estado), "init FALLO: sin memoria para la ruta");
        return 0;
    }

    // Copiamos la ruta ANTES de soltarla: snprintf sobre un puntero
    // ya liberado es usar memoria prestada (use-after-free).
    char copia[256];
    snprintf(copia, sizeof(copia), "%s", ruta);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = 0; // en movil, CPU

    struct whisper_context *ctx = whisper_init_from_file_with_params(ruta, cparams);
    (*env)->ReleaseStringUTFChars(env, modelPath, ruta);

    if (ctx == NULL) {
        snprintf(g_estado, sizeof(g_estado),
                 "init FALLO al cargar '%s' (archivo corrupto, incompleto o incompatible)", copia);
        return 0;
    }
    snprintf(g_estado, sizeof(g_estado), "init OK: modelo cargado desde %s", copia);
    return (jlong)(intptr_t)ctx;
}

// Libera el contexto (el modelo deja de ocupar memoria)
JNIEXPORT void JNICALL
Java_com_elisotopo_subtilingus_WhisperBridge_freeContext(JNIEnv *env, jobject thiz, jlong ctxPtr)
{
    (void) env; (void) thiz;
    if (ctxPtr != 0) {
        whisper_free((struct whisper_context *)(intptr_t)ctxPtr);
        snprintf(g_estado, sizeof(g_estado), "modelo liberado de memoria");
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
        snprintf(g_estado, sizeof(g_estado), "transcribe FALLO: contexto o muestras nulos");
        return (*env)->NewStringUTF(env, "");
    }

    // 1) Accedemos al array de floats de Kotlin desde C (sin copiarlo)
    const jsize n = (*env)->GetArrayLength(env, samples);
    jfloat *datos = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (datos == NULL) {
        snprintf(g_estado, sizeof(g_estado), "transcribe FALLO: sin acceso al array de muestras");
        return (*env)->NewStringUTF(env, "");
    }

    // 2) Configuramos Whisper: greedy = decodificación rápida y
    //    determinista; sin timestamps, idioma elegido.
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4;             // tu Redmi: 4 hilos van muy finos
    params.translate = false;         // transcribir, no traducir (¿todavía?)
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = true;
    // NUEVO v0.5: ignorar tokens "no voz" (suspiros, música, ruido):
    // menos frases fantasma cuando hay silencio o fondo.
    params.language = NULL;
    if (lang != NULL) {
        const char *idioma = (*env)->GetStringUTFChars(env, lang, NULL);
        if (idioma != NULL) {
            params.language = idioma;
        }
    }

    // 3) La magia: decenas de capas de red neuronal en este bloque.
    //    Clock mide el tiempo de CPU invertido: si crece mucho,
    //    el móvil no da abasto y la cola descartará bloques.
    clock_t t0 = clock();
    int rc = whisper_full(ctx, params, datos, (int)n);
    double seg_cpu = (double)(clock() - t0) / (double)CLOCKS_PER_SEC;

    if (lang != NULL && params.language != NULL) {
        (*env)->ReleaseStringUTFChars(env, lang, params.language);
    }
    (*env)->ReleaseFloatArrayElements(env, samples, datos, JNI_ABORT);

    // 4) Anotamos QUÉ pasó, no solo "salió vacío": rc (0 = bien),
    //    segmentos y CPU. Esto es lo que verás en el Registro.
    const int nSegmentos = whisper_full_n_segments(ctx);
    snprintf(g_estado, sizeof(g_estado),
             "rc=%d, segmentos=%d, %.2f s CPU para %d muestras",
             rc, nSegmentos, seg_cpu, (int)n);

    if (rc != 0) {
        return (*env)->NewStringUTF(env, "");
    }

    // 5) Whisper devuelve el texto en "segmentos": los unimos todos
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

// El "cuaderno" del mundo C, para la pestaña Registro de Kotlin
JNIEXPORT jstring JNICALL
Java_com_elisotopo_subtilingus_WhisperBridge_estadoNativo(JNIEnv *env, jobject thiz)
{
    (void) thiz;
    return (*env)->NewStringUTF(env, g_estado);
}
