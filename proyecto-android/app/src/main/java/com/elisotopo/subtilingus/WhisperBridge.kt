package com.elisotopo.subtilingus

// El puente con la librería nativa (JNI): "external fun" = la
// implementación vive en C (jni/whisper-jni.c).
class WhisperBridge {

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }

    // Carga el modelo (.bin). Devuelve un manejador (puntero en C). 0 = fallo.
    external fun initContext(modelPath: String): Long

    // muestras float -1..1 a 16 kHz -> texto en UTF-8 (bytes).
    // idioma: "en", "es" o "auto". audioCtx: 0 = completo; si no, recorta
    // el contexto del encoder (más rápido en fragmentos cortos).
    external fun transcribir(ctx: Long, muestras: FloatArray, idioma: String, hilos: Int, audioCtx: Int): ByteArray

    // Resumen de la última operación hecha en C (para el Registro).
    external fun estadoNativo(): String

    // Instrucciones de CPU que usa la librería y si el C va optimizado
    external fun infoSistema(): String

    external fun freeContext(ctx: Long)
}
