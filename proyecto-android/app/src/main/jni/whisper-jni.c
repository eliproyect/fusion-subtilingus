// ============================================================
// Subtilingus · Puente JNI con whisper.cpp (v0.7)
// ============================================================
// Cambios respecto a la v0.6:
//  - transcribir() devuelve BYTES (UTF-8) y no un jstring: Whisper
//    a veces corta un caracter de varios bytes (acentos, ñ) entre
//    dos tokens, y NewStringUTF con UTF-8 invalido puede abortar la
//    app en builds debug (CheckJNI). Kotlin decodifica con seguridad.
//  - nuevos parametros: hilos y audio_ctx (recorta el trabajo del
//    encoder para fragmentos cortos: la mayor ganancia de velocidad).
//  - no_context: cada fragmento se transcribe independiente (evita
//    bucles de texto repetido).
//  - greedy.best_of = 1: menos trabajo si Whisper reintenta.
//  - el tiempo se mide con reloj real (antes: CPU de todos los hilos).
// ============================================================

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "whisper.h"

static char g_estado[512] = "nada ejecutado todavia";

static double ahora_s(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

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

JNIEXPORT void JNICALL
Java_com_elisotopo_subtilingus_WhisperBridge_freeContext(JNIEnv *env, jobject thiz, jlong ctxPtr)
{
    (void) env; (void) thiz;
    if (ctxPtr != 0) {
        whisper_free((struct whisper_context *)(intptr_t)ctxPtr);
        snprintf(g_estado, sizeof(g_estado), "modelo liberado de memoria");
    }
}

// muestras (float -1..1, 16 kHz mono) -> texto UTF-8 como bytes
JNIEXPORT jbyteArray JNICALL
Java_com_elisotopo_subtilingus_WhisperBridge_transcribir(JNIEnv *env, jobject thiz,
        jlong ctxPtr, jfloatArray samples, jstring lang, jint hilos, jint audioCtx)
{
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)ctxPtr;
    if (ctx == NULL || samples == NULL) {
        snprintf(g_estado, sizeof(g_estado), "transcribe FALLO: contexto o muestras nulos");
        return (*env)->NewByteArray(env, 0);
    }

    const jsize n = (*env)->GetArrayLength(env, samples);
    jfloat *datos = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (datos == NULL) {
        snprintf(g_estado, sizeof(g_estado), "transcribe FALLO: sin acceso al array de muestras");
        return (*env)->NewByteArray(env, 0);
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = hilos > 0 ? (int)hilos : 4;
    params.translate = false;
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = true;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.greedy.best_of = 1;
    params.audio_ctx = audioCtx > 0 ? (int)audioCtx : 0;

    params.language = "en";
    const char *idioma = NULL;
    if (lang != NULL) {
        idioma = (*env)->GetStringUTFChars(env, lang, NULL);
        if (idioma != NULL) {
            params.language = idioma;
        }
    }

    const double t0 = ahora_s();
    int rc = whisper_full(ctx, params, datos, (int)n);
    const double seg = ahora_s() - t0;

    if (idioma != NULL) {
        (*env)->ReleaseStringUTFChars(env, lang, idioma);
    }
    (*env)->ReleaseFloatArrayElements(env, samples, datos, JNI_ABORT);

    const int nSegmentos = (rc == 0) ? whisper_full_n_segments(ctx) : 0;
    snprintf(g_estado, sizeof(g_estado),
             "rc=%d, segmentos=%d, %.2f s reloj para %d muestras (audio_ctx=%d, hilos=%d)",
             rc, nSegmentos, seg, (int)n, (int)audioCtx, (int)hilos);

    if (rc != 0 || nSegmentos <= 0) {
        return (*env)->NewByteArray(env, 0);
    }

    size_t total = 1;
    for (int i = 0; i < nSegmentos; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (t != NULL) total += strlen(t) + 1;
    }
    char *buffer = (char *)malloc(total);
    if (buffer == NULL) {
        return (*env)->NewByteArray(env, 0);
    }
    buffer[0] = '\0';
    for (int i = 0; i < nSegmentos; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (t != NULL) strcat(buffer, t);
    }
    const size_t largo = strlen(buffer);
    jbyteArray resultado = (*env)->NewByteArray(env, (jsize)largo);
    if (resultado != NULL && largo > 0) {
        (*env)->SetByteArrayRegion(env, resultado, 0, (jsize)largo, (const jbyte *)buffer);
    }
    free(buffer);
    return resultado;
}

// El "cuaderno" del mundo C (solo ASCII para no arriesgar con NewStringUTF)
JNIEXPORT jstring JNICALL
Java_com_elisotopo_subtilingus_WhisperBridge_estadoNativo(JNIEnv *env, jobject thiz)
{
    (void) thiz;
    char limpio[512];
    size_t i = 0;
    for (; i < sizeof(limpio) - 1 && g_estado[i] != '\0'; i++) {
        unsigned char c = (unsigned char)g_estado[i];
        limpio[i] = (c >= 32 && c < 127) ? (char)c : '?';
    }
    limpio[i] = '\0';
    return (*env)->NewStringUTF(env, limpio);
}

// Diagnostico: que instrucciones del procesador usa la libreria (NEON,
// DOTPROD, FP16...) y si nuestro C se compilo optimizado.
JNIEXPORT jstring JNICALL
Java_com_elisotopo_subtilingus_WhisperBridge_infoSistema(JNIEnv *env, jobject thiz)
{
    (void) thiz;
#ifdef __OPTIMIZE__
    const char *opt = "C optimizado";
#else
    const char *opt = "C SIN OPTIMIZAR (-O0)";
#endif
    char bruto[900];
    snprintf(bruto, sizeof(bruto), "%s | %s", opt, whisper_print_system_info());
    char limpio[900];
    size_t i = 0;
    for (; i < sizeof(limpio) - 1 && bruto[i] != '\0'; i++) {
        unsigned char c = (unsigned char)bruto[i];
        limpio[i] = (c >= 32 && c < 127) ? (char)c : '?';
    }
    limpio[i] = '\0';
    return (*env)->NewStringUTF(env, limpio);
}
