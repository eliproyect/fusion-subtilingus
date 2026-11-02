package com.elisotopo.subtilingus

import android.os.SystemClock
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.min

// ============================================================
// Filtro de basura típica de Whisper (frases fantasma con silencio/música)
// ============================================================
object Filtro {
    private val etiquetas = listOf(
        Regex("\\[[^\\]]*\\]"),
        Regex("\\([^)]*\\)"),
        Regex("\\*[^*]*\\*")
    )
    private val espacios = Regex("\\s+")
    private val noLetras = Regex("[^a-záéíóúñü ]")

    private val basura = setOf("you", "bye", "thank you very much", "gracias por ver el video")
    private val prefijosBasura = listOf(
        "thanks for watching", "thank you for watching", "please subscribe",
        "subtitles by", "subtitles made by", "amara org", "subtitulos realizados por",
        "subtitulos por", "suscribete", "gracias por ver"
    )

    fun normalizar(t: String): String =
        espacios.replace(noLetras.replace(t.lowercase(), ""), " ").trim()

    fun limpiar(bruto: String): String {
        var s = bruto
        for (r in etiquetas) s = r.replace(s, " ")
        s = s.replace("♪", " ")
        s = espacios.replace(s, " ").trim()
        if (s.isEmpty()) return ""
        val norm = normalizar(s)
        if (norm.isEmpty()) return ""
        if (norm in basura) return ""
        for (p in prefijosBasura) if (norm.startsWith(p)) return ""
        if (hayRepeticion(norm)) return ""
        return s
    }

    // "the the the the the ..." = bucle de Whisper
    private fun hayRepeticion(norm: String): Boolean {
        val pal = norm.split(' ')
        var racha = 1
        for (i in 1 until pal.size) {
            if (pal[i] == pal[i - 1]) {
                racha++
                if (racha >= 5) return true
            } else {
                racha = 1
            }
        }
        return false
    }
}

// ============================================================
// Transcriptor: un hilo que toma frases de la cola y las pasa por Whisper
// ============================================================
// - Las frases FINALES tienen prioridad y se procesan todas.
// - Los BORRADORES (parciales) solo se procesan si no hay nada más
//   pendiente, y solo se guarda el más reciente: así, si el móvil va
//   justo, se sacrifican los borradores y no la transcripción.
// ============================================================
class Transcriptor(
    private val modelo: ModeloInfo,
    private val archivoModelo: File,
    private val idioma: String,
    private val modoRapido: Boolean,
    private val hilosCpu: Int,
    private val alFinal: (String, Long) -> Unit,
    private val alProvisional: (String) -> Unit
) {
    private class Trabajo(val audio: FloatArray, val inicioMs: Long, val id: Int)

    companion object {
        private const val MAX_COLA = 4
        private const val MIN_MUESTRAS = 17600   // Whisper exige >= 1 s
    }

    private val puente = WhisperBridge()
    private var ctx = 0L
    private val candado = Any()
    private val finales = ArrayList<Trabajo>()
    private var parcial: Trabajo? = null
    @Volatile private var ultimoFinalId = -1
    @Volatile private var cerrando = false
    private var hilo: Thread? = null
    private var ultimaNorm = ""
    private var idProvisional = -1
    private var lentos = 0

    private val idiomaEfectivo: String = if (modelo.soloIngles) "en" else idioma

    fun cargar(): Boolean {
        ctx = puente.initContext(archivoModelo.absolutePath)
        Registro.i("NATIVO", puente.estadoNativo() + " · manejador=" + ctx)
        try {
            Registro.i("CPU", puente.infoSistema())
        } catch (e: Throwable) {
            Registro.w("CPU", "infoSistema no disponible: " + e)
        }
        return ctx != 0L
    }

    fun iniciarHilo() {
        hilo = thread(name = "whisper") { bucle() }
    }

    fun encolarFinal(audio: FloatArray, inicioMs: Long, id: Int) {
        synchronized(candado) {
            finales.add(Trabajo(audio, inicioMs, id))
            ultimoFinalId = id
            parcial = null
            while (finales.size > MAX_COLA) {
                finales.removeAt(0)
                Estado.avisoRendimiento = "Tu móvil no da abasto: se han saltado frases. Prueba un modelo más pequeño en Ajustes."
                Registro.w("COLA", "Cola llena: frase descartada")
            }
        }
    }

    fun ofrecerParcial(audio: FloatArray, inicioMs: Long, id: Int) {
        synchronized(candado) {
            if (finales.isEmpty()) parcial = Trabajo(audio, inicioMs, id)
        }
    }

    // Termina de procesar lo pendiente y libera el modelo
    fun terminar() {
        cerrando = true
        val h = hilo
        if (h == null) {
            liberar()
        } else {
            try {
                h.join(120_000)
            } catch (e: InterruptedException) {
            }
        }
    }

    private fun liberar() {
        if (ctx != 0L) {
            puente.freeContext(ctx)
            ctx = 0L
        }
    }

    private fun bucle() {
        // Prioridad alta: los hilos que crea Whisper heredan esta prioridad
        // y así Android no los relega a los núcleos lentos en segundo plano.
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            Registro.w("WHISPER", "No se pudo subir la prioridad: " + e)
        }
        try {
            while (true) {
                var t: Trabajo? = null
                var esParcial = false
                synchronized(candado) {
                    if (finales.isNotEmpty()) {
                        t = finales.removeAt(0)
                    } else if (!cerrando && parcial != null) {
                        t = parcial
                        parcial = null
                        esParcial = true
                    }
                    Unit
                }
                val trabajo = t
                if (trabajo == null) {
                    if (cerrando) break
                    Thread.sleep(60)
                    continue
                }
                try {
                    procesar(trabajo, esParcial)
                } catch (e: Exception) {
                    Registro.e("WHISPER", "Error al transcribir: " + e)
                }
            }
        } finally {
            liberar()
            Registro.i("WHISPER", "Hilo de transcripción terminado")
        }
    }

    private fun procesar(t: Trabajo, esParcial: Boolean) {
        var muestras = t.audio
        val durReal = muestras.size / 16000.0
        if (muestras.size < MIN_MUESTRAS) muestras = muestras.copyOf(MIN_MUESTRAS)
        val durProc = muestras.size / 16000.0
        // El encoder de Whisper siempre procesa 30 s; para una frase de 4 s
        // se desperdicia el 85 % del trabajo. audio_ctx lo recorta (50 pasos/s).
        val audioCtx = if (modoRapido) min(1500, ceil((durProc + 3.0) * 50.0).toInt()) else 0

        val t0 = SystemClock.elapsedRealtime()
        val bytes = puente.transcribir(ctx, muestras, idiomaEfectivo, hilosCpu, audioCtx)
        val ms = SystemClock.elapsedRealtime() - t0
        val texto = Filtro.limpiar(String(bytes, Charsets.UTF_8))

        val ratio = ms / (durProc * 1000.0)
        Estado.velocidad = String.format(Locale.US, "Último fragmento: %.1f s de audio → %.1f s de proceso (x%.2f)",
            durReal, ms / 1000.0, ratio)
        Registro.i("WHISPER", (if (esParcial) "borrador " else "frase ") + t.id + ": " +
                puente.estadoNativo() + " · " + ms + " ms · «" + texto.take(80) + "»")

        if (esParcial) {
            if (t.id > ultimoFinalId && texto.isNotEmpty()) {
                idProvisional = t.id
                alProvisional(texto)
            }
            return
        }

        if (ratio > 1.0) lentos++ else lentos = 0
        if (lentos >= 3) {
            Estado.avisoRendimiento = "El modelo va más lento que el audio. Usa uno más pequeño o activa el Modo rápido."
        } else if (lentos == 0 && Estado.avisoRendimiento.startsWith("El modelo va")) {
            Estado.avisoRendimiento = ""
        }

        if (idProvisional in 0..t.id) {
            idProvisional = -1
            alProvisional("")
        }
        if (texto.isNotEmpty()) {
            val norm = Filtro.normalizar(texto)
            if (norm != ultimaNorm) {
                ultimaNorm = norm
                alFinal(texto, t.inicioMs)
            }
        }
    }
}
