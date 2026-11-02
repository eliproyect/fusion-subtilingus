package com.elisotopo.subtilingus

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// Un modelo de voz de Whisper que se puede descargar.
class ModeloInfo(
    val id: String,
    val nombre: String,
    val detalle: String,
    val archivo: String,
    val mbAprox: Int,
    val soloIngles: Boolean
)

// Dónde viven los archivos de la app (visible por USB, pero Android 11+ no
// deja entrar con gestores de archivos: usa Ajustes > Almacenamiento).
object Carpetas {
    fun base(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    fun modelos(ctx: Context): File {
        val d = File(base(ctx), "modelos")
        d.mkdirs()
        return d
    }

    fun textos(ctx: Context): File {
        val d = File(base(ctx), "textos")
        d.mkdirs()
        return d
    }
}

object Modelos {
    const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    const val RECOMENDADO_ID = "base-en"

    // Los "q5_1" son versiones comprimidas: casi igual de precisas, mucho
    // más pequeñas y rápidas en móvil. Los ".en" solo entienden inglés
    // pero en inglés aciertan más que los multilingües del mismo tamaño.
    val lista = listOf(
        ModeloInfo("tiny-en", "Inglés · Rápido", "El más ligero. Para móviles justos.",
            "ggml-tiny.en-q5_1.bin", 32, true),
        ModeloInfo("base-en", "Inglés · Equilibrado (recomendado)", "Buen punto medio entre velocidad y acierto.",
            "ggml-base.en-q5_1.bin", 60, true),
        ModeloInfo("small-en", "Inglés · Preciso", "El que mejor entiende. Necesita un móvil potente.",
            "ggml-small.en-q5_1.bin", 190, true),
        ModeloInfo("tiny-en-f16", "Inglés · Rápido, sin comprimir (prueba)", "Más grande, pero a veces va más rápido que el comprimido.",
            "ggml-tiny.en.bin", 78, true),
        ModeloInfo("base-en-q8", "Inglés · Equilibrado, calidad alta (prueba)", "Compresión suave: más preciso que el recomendado.",
            "ggml-base.en-q8_0.bin", 82, true),
        ModeloInfo("base", "Multilingüe · Equilibrado", "Inglés, español y muchos más idiomas.",
            "ggml-base-q5_1.bin", 60, false),
        ModeloInfo("small", "Multilingüe · Preciso", "Multilingüe de mayor calidad. Móvil potente.",
            "ggml-small-q5_1.bin", 190, false),
        ModeloInfo("tiny-v06", "Multilingüe · Muy básico (el de la v0.6)", "Poco preciso. Solo por compatibilidad.",
            "ggml-tiny.bin", 75, false)
    )

    fun buscar(id: String): ModeloInfo? = lista.firstOrNull { it.id == id }

    fun archivo(ctx: Context, m: ModeloInfo): File = File(Carpetas.modelos(ctx), m.archivo)

    // Un archivo "bueno" ocupa al menos el 70 % del tamaño nominal
    fun descargado(ctx: Context, m: ModeloInfo): Boolean {
        val f = archivo(ctx, m)
        return f.exists() && f.length() >= m.mbAprox * 700_000L
    }
}

// Descarga de modelos (un hilo; el progreso lo lee la pantalla).
object Descargas {
    @Volatile var activa: String? = null
    @Volatile var progreso = 0
    @Volatile var error = ""
    @Volatile private var cancelado = false

    fun cancelar() {
        cancelado = true
    }

    fun descargar(ctx: Context, m: ModeloInfo, alTerminar: () -> Unit) {
        if (activa != null) return
        val app = ctx.applicationContext
        activa = m.id
        progreso = 0
        error = ""
        cancelado = false
        thread(name = "descarga") {
            var conexion: HttpURLConnection? = null
            val destino = Modelos.archivo(app, m)
            val temporal = File(destino.parentFile, m.archivo + ".tmp")
            try {
                Registro.i("RED", "Descargando " + m.archivo)
                val c = URL(Modelos.BASE_URL + m.archivo).openConnection() as HttpURLConnection
                conexion = c
                c.connectTimeout = 30_000
                c.readTimeout = 30_000
                c.connect()
                if (c.responseCode != 200) {
                    throw IOException("El servidor respondió " + c.responseCode)
                }
                val total = c.contentLengthLong
                var leidos = 0L
                var ultimoLog = -10
                c.inputStream.use { entrada ->
                    FileOutputStream(temporal).use { salida ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            if (cancelado) throw IOException("Descarga cancelada")
                            val n = entrada.read(buffer)
                            if (n < 0) break
                            salida.write(buffer, 0, n)
                            leidos += n
                            if (total > 0) {
                                val pct = (leidos * 100 / total).toInt()
                                progreso = pct
                                if (pct >= ultimoLog + 10) {
                                    ultimoLog = pct
                                    Registro.i("RED", "Descarga al " + pct + "%")
                                }
                            }
                        }
                    }
                }
                if (leidos < m.mbAprox * 700_000L) {
                    throw IOException("Descarga incompleta (" + (leidos / 1_000_000L) + " MB)")
                }
                if (destino.exists()) destino.delete()
                if (!temporal.renameTo(destino)) {
                    temporal.copyTo(destino, overwrite = true)
                    temporal.delete()
                }
                Registro.i("RED", "Descarga terminada: " + m.archivo)
            } catch (e: Exception) {
                Registro.e("RED", "Error al descargar: " + e)
                error = e.message ?: e.toString()
                try {
                    temporal.delete()
                } catch (x: Exception) {
                }
            } finally {
                try {
                    conexion?.disconnect()
                } catch (x: Exception) {
                }
                activa = null
                Handler(Looper.getMainLooper()).post { alTerminar() }
            }
        }
    }
}
