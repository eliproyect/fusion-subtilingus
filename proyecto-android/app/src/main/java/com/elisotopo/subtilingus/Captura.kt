package com.elisotopo.subtilingus

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import kotlin.concurrent.thread
import kotlin.math.max

// ============================================================
// Captura de audio: dos fuentes posibles
//  - INTERNO: el audio que suena en el móvil (BBC, Spotify, YouTube...)
//    mediante AudioPlaybackCapture (Android 10+). Calidad perfecta: no
//    entra ruido de la habitación. Algunas apps lo prohíben (Netflix...).
//  - MIC: el micrófono (TV, una clase, una conversación).
// Entrega bloques de float -1..1 a 16 kHz mono.
// ============================================================
class Captura(
    private val fuente: String,
    private val proyeccion: MediaProjection?,
    private val alAudio: (FloatArray, Int) -> Unit,
    private val alError: (String) -> Unit,
    private val alFin: () -> Unit
) {
    private var grabador: AudioRecord? = null
    private var factor = 1
    @Volatile private var activo = false
    private var hilo: Thread? = null

    // Devuelve null si todo fue bien, o el motivo del fallo
    @Suppress("DEPRECATION")
    fun abrir(): String? {
        if (fuente == Ajustes.FUENTE_INTERNO) {
            if (proyeccion == null) return "Falta el permiso para capturar el audio del móvil."
            // 16 kHz directo si el móvil lo admite; si no, 48 kHz y bajamos a 16 kHz
            val intentos = listOf(Pair(16000, 1), Pair(48000, 3))
            for ((tasa, f) in intentos) {
                try {
                    val cfg = AudioPlaybackCaptureConfiguration.Builder(proyeccion)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    val formato = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(tasa)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                    val minimo = AudioRecord.getMinBufferSize(tasa, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT)
                    val r = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(cfg)
                        .setAudioFormat(formato)
                        .setBufferSizeInBytes(max(minimo, tasa * 2))
                        .build()
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        grabador = r
                        factor = f
                        Registro.i("CAPTURA", "Audio interno abierto a " + tasa + " Hz")
                        return null
                    }
                    r.release()
                    Registro.w("CAPTURA", "Audio interno sin inicializar a " + tasa + " Hz")
                } catch (e: Exception) {
                    Registro.w("CAPTURA", "Audio interno a " + tasa + " Hz falló: " + e)
                }
            }
            return "No se pudo abrir la captura del audio del móvil. Prueba con el modo Micrófono."
        }

        val minimo = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if (minimo <= 0) return "Este móvil no soporta la captura de audio."
        return try {
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minimo, 32000))
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                "No se pudo abrir el micrófono (¿otra app lo está usando?)."
            } else {
                grabador = r
                factor = 1
                Registro.i("CAPTURA", "Micrófono abierto")
                null
            }
        } catch (e: Exception) {
            "No se pudo abrir el micrófono: " + e.message
        }
    }

    fun iniciar() {
        val r = grabador ?: return
        try {
            r.startRecording()
        } catch (e: Exception) {
            alError("No se pudo empezar a grabar: " + e.message)
            return
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            alError("El sistema no dejó empezar a grabar.")
            return
        }
        activo = true
        hilo = thread(name = "captura") { bucle(r) }
    }

    private fun bucle(r: AudioRecord) {
        val bloque = ShortArray(1600 * factor)    // 100 ms
        val salida = FloatArray(1600)
        try {
            while (activo) {
                val leidas = r.read(bloque, 0, bloque.size)
                if (leidas < 0) {
                    if (activo) alError("Error de lectura del audio (código " + leidas + ").")
                    break
                }
                if (leidas == 0) continue
                val m = leidas / factor
                for (i in 0 until m) {
                    var s = 0
                    for (k in 0 until factor) s += bloque[i * factor + k]
                    salida[i] = s / (32768f * factor)
                }
                alAudio(salida, m)
            }
        } finally {
            alFin()
            Registro.i("CAPTURA", "Hilo de captura terminado")
        }
    }

    fun detener() {
        activo = false
        val r = grabador
        try {
            r?.stop()
        } catch (e: Exception) {
        }
        try {
            hilo?.join(3000)
        } catch (e: InterruptedException) {
        }
        try {
            r?.release()
        } catch (e: Exception) {
        }
        grabador = null
    }
}
