package com.elisotopo.subtilingus

// ============================================================
// El puente con la librería nativa (JNI)
// ============================================================
// "external fun" = "la implementación NO está en Kotlin: vive en C"
// (jni/whisper-jni.c). Kotlin solo declara el contrato; el NDK
// compila el C y el nombre largo de las funciones C
// (Java_com_elisotopo_subtilingus_WhisperBridge_initContext) es
// literalmente paquete + clase + método codificados.
// ============================================================

class WhisperBridge {

    companion object {
        init {
            // Carga libwhisper_jni.so (lo compila el NDK en GitHub Actions)
            System.loadLibrary("whisper_jni")
        }
    }

    // Carga el modelo (.bin) y devuelve un "manejador" (un número que
    // en C es un puntero a memoria). 0 = fallo. Kotlin no mira dentro,
    // solo lo guarda y lo devuelve en las llamadas siguientes.
    external fun initContext(modelPath: String): Long

    // Recibe muestras float de -1.0 a 1.0 y devuelve el texto que
    // Whisper ha oído en el idioma indicado ("en", "es", ...).
    external fun transcribeSamples(ctx: Long, samples: FloatArray, lang: String): String

    // Libera la memoria del modelo (higiene, como cerrar una conexión).
    external fun freeContext(ctx: Long)
}
