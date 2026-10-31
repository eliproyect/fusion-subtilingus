package com.elisotopo.subtilingus

// ============================================================
// Subtilingus · Fase 1 — Whisper transcribiendo de verdad
// ============================================================
// El viaje del sonido:
//   micrófono -> bloques de 4 segundos -> Whisper (en C, vía JNI)
//   -> texto en pantalla -> guardado en transcripcion.txt
//
// Dos hilos en danza:
//   · hiloAudio: lee el micro sin parar y va llenando bloques
//   · hiloTranscripcion: coge cada bloque y se lo da a Whisper
// Si Whisper va lento, la cola (capacidad 2) suelta el bloque
// más VIEJO: preferimos perder audio pasado antes que el presente.
// ============================================================

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        // 16 kHz, mono, 16 bits: el formato que exige Whisper
        const val MUESTRAS_POR_SEGUNDO = 16000
        const val SEGUNDOS_POR_BLOQUE = 4
        const val MUESTRAS_POR_BLOQUE = MUESTRAS_POR_SEGUNDO * SEGUNDOS_POR_BLOQUE
        // Whisper tiny multilingüe (~75 MB): el mejor equilibrio
        // velocidad/precisión para un móvil. Hay mejores (base, small...)
        // pero ocupan y tardan más.
        const val URL_MODELO = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
    }

    private lateinit var estado: TextView
    private lateinit var transcripcion: TextView
    private lateinit var scrollTranscripcion: ScrollView
    private lateinit var botonModelo: Button
    private lateinit var botonIdioma: Button
    private lateinit var botonTranscribir: Button
    private lateinit var botonCompartir: Button
    private lateinit var botonLimpiar: Button

    private val bridge = WhisperBridge()

    // @Volatile = "esta variable la leen/escriben varios hilos:
    // no me la caches, léela siempre de la memoria real"
    @Volatile private var ctx = 0L        // 0 = Whisper aún no cargado
    @Volatile private var escuchando = false
    private var idioma = "en"
    private var grabador: AudioRecord? = null
    private val cola = LinkedBlockingDeque<ShortArray>(2)
    private val archivoTranscripcion get() = File(filesDir, "transcripcion.txt")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        estado = findViewById(R.id.estado)
        transcripcion = findViewById(R.id.transcripcion)
        scrollTranscripcion = findViewById(R.id.scrollTranscripcion)
        botonModelo = findViewById(R.id.botonModelo)
        botonIdioma = findViewById(R.id.botonIdioma)
        botonTranscribir = findViewById(R.id.botonTranscribir)
        botonCompartir = findViewById(R.id.botonCompartir)
        botonLimpiar = findViewById(R.id.botonLimpiar)

        estado.text = "Pulsa 'Modelo' para descargar Whisper (75 MB, solo la primera vez)."

        botonModelo.setOnClickListener { prepararModelo() }
        botonIdioma.setOnClickListener {
            idioma = if (idioma == "en") "es" else "en"
            botonIdioma.text = if (idioma == "en") "EN" else "ES"
        }
        botonTranscribir.setOnClickListener {
            if (escuchando) detenerTranscripcion() else iniciarTranscripcion()
        }
        botonCompartir.setOnClickListener { compartir() }
        botonLimpiar.setOnClickListener {
            transcripcion.text = ""
            synchronized(archivoTranscripcion) {
                archivoTranscripcion.delete()
            }
        }
    }

    // ================= MODELO =================

    private fun prepararModelo() {
        if (ctx != 0L) {
            Toast.makeText(this, "El modelo ya está listo", Toast.LENGTH_SHORT).show()
            return
        }
        val destino = File(filesDir, "ggml-tiny.bin")
        // Si ya se descargó otra vez, no gastamos datos: solo cargar
        if (destino.exists() && destino.length() > 50_000_000L) {
            cargarModelo(destino.absolutePath)
            return
        }
        thread {
            try {
                runOnUiThread { estado.text = "Descargando modelo... 0%" }
                val conexion = URL(URL_MODELO).openConnection() as HttpURLConnection
                conexion.connectTimeout = 30_000
                conexion.readTimeout = 30_000
                conexion.connect()
                val total = conexion.contentLength.toLong()
                val temporal = File(filesDir, "modelo.tmp")
                var leidos = 0L
                conexion.inputStream.use { entrada ->
                    FileOutputStream(temporal).use { salida ->
                        val buffer = ByteArray(16384)
                        while (true) {
                            val n = entrada.read(buffer)
                            if (n < 0) break
                            salida.write(buffer, 0, n)
                            leidos += n
                            if (total > 0) {
                                val pct = (leidos * 100 / total).toInt()
                                runOnUiThread { estado.text = "Descargando modelo... " + pct + "%" }
                            }
                        }
                    }
                }
                if (leidos < 50_000_000L) throw IOException("Descarga incompleta")
                // Se descarga a un .tmp y solo se renombra al final:
                // si algo falla a mitad, nunca queda un archivo roto
                // con pinta de bueno.
                if (!temporal.renameTo(destino)) {
                    temporal.copyTo(destino, overwrite = true)
                    temporal.delete()
                }
                conexion.disconnect()
                cargarModelo(destino.absolutePath)
            } catch (e: Exception) {
                runOnUiThread { estado.text = "Error al descargar: " + e.message }
            }
        }
    }

    private fun cargarModelo(ruta: String) {
        runOnUiThread { estado.text = "Cargando modelo en memoria..." }
        val nuevoCtx = bridge.initContext(ruta)
        runOnUiThread {
            if (nuevoCtx != 0L) {
                ctx = nuevoCtx
                estado.text = "Whisper listo (tiny multilingüe). Pulsa 'Transcribir' y habla."
                botonModelo.text = "Modelo listo"
                botonModelo.isEnabled = false
            } else {
                estado.text = "No se pudo cargar el modelo"
            }
        }
    }

    // ============ GRABACIÓN Y TRANSCRIPCIÓN ============

    private fun iniciarTranscripcion() {
        if (ctx == 0L) {
            Toast.makeText(this, "Primero descarga el modelo", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val minimo = AudioRecord.getMinBufferSize(
            MUESTRAS_POR_SEGUNDO,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimo <= 0) {
            Toast.makeText(this, "Este móvil no soporta la captura de audio", Toast.LENGTH_LONG).show()
            return
        }
        @Suppress("DEPRECATION")
        val nuevo = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            MUESTRAS_POR_SEGUNDO,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimo.coerceAtLeast(4096)
        )
        if (nuevo.state != AudioRecord.STATE_INITIALIZED) {
            nuevo.release()
            Toast.makeText(this, "No se pudo abrir el micrófono", Toast.LENGTH_LONG).show()
            return
        }
        grabador = nuevo
        nuevo.startRecording()
        escuchando = true
        botonTranscribir.text = "Detener"
        cola.clear()
        thread { bucleAudio(nuevo) }
        thread { bucleTranscripcion() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            iniciarTranscripcion()
        } else {
            Toast.makeText(this, "Sin micrófono no hay transcripción", Toast.LENGTH_LONG).show()
        }
    }

    // El "productor": lee el micrófono y suelta bloques de 4 segundos
    private fun bucleAudio(grabador: AudioRecord) {
        val bloque = ShortArray(1600)
        val acumulado = ShortArray(MUESTRAS_POR_BLOQUE)
        var n = 0
        while (escuchando) {
            val leidas = grabador.read(bloque, 0, bloque.size)
            if (leidas <= 0) break
            val espacio = acumulado.size - n
            val copiable = if (leidas <= espacio) leidas else espacio
            if (copiable > 0) {
                System.arraycopy(bloque, 0, acumulado, n, copiable)
                n += copiable
            }
            if (n >= acumulado.size) {
                // Cola llena = Whisper no da abasto: soltamos el bloque
                // más viejo (pollFirst) antes de meter el nuevo.
                while (!cola.offerLast(acumulado.copyOf())) {
                    cola.pollFirst()
                }
                n = 0
            }
        }
    }

    // El "consumidor": cada bloque -> float -> Whisper -> pantalla + archivo
    private fun bucleTranscripcion() {
        while (true) {
            val bloque = cola.pollFirst(1, TimeUnit.SECONDS)
            if (bloque == null) {
                if (!escuchando) break
                continue
            }
            // Whisper quiere muestras float entre -1.0 y 1.0
            val muestras = FloatArray(bloque.size)
            for (i in bloque.indices) {
                muestras[i] = bloque[i] / 32768.0f
            }
            val texto = bridge.transcribeSamples(ctx, muestras, idioma)
            val limpio = texto.trim()
            if (limpio.isNotEmpty()) {
                runOnUiThread {
                    transcripcion.append(limpio + " ")
                    scrollTranscripcion.post {
                        scrollTranscripcion.fullScroll(View.FOCUS_DOWN)
                    }
                }
                synchronized(archivoTranscripcion) {
                    archivoTranscripcion.appendText(limpio + " ")
                }
            }
        }
    }

    private fun detenerTranscripcion() {
        escuchando = false
        botonTranscribir.text = "Transcribir"
        grabador?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        grabador = null
        estado.text = "En pausa. La transcripción queda guardada en el móvil."
    }

    // ============ COMPARTIR ============

    private fun compartir() {
        val texto = synchronized(archivoTranscripcion) {
            if (archivoTranscripcion.exists()) archivoTranscripcion.readText() else ""
        }
        if (texto.isBlank()) {
            Toast.makeText(this, "Aún no hay transcripción que compartir", Toast.LENGTH_SHORT).show()
            return
        }
        // Intent implícito: le pedimos a Android "¿quién sabe compartir
        // texto?" y salta el selector con WhatsApp, Gmail, etc.
        val intento = Intent(Intent.ACTION_SEND)
        intento.type = "text/plain"
        intento.putExtra(Intent.EXTRA_TEXT, texto)
        startActivity(Intent.createChooser(intento, "Compartir transcripción"))
    }

    // ============ CICLO DE VIDA ============

    override fun onPause() {
        super.onPause()
        if (escuchando) detenerTranscripcion()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ctx != 0L) {
            bridge.freeContext(ctx)
            ctx = 0L
        }
    }
}
