package com.elisotopo.subtilingus

// ============================================================
// Subtilingus · Fase 1.1 — Modo depuración
// ============================================================
// Lo aprendido en la Fase 1: cuando algo falla y no hay registros,
// estamos a ciegas. Esta versión añade:
//
//   · Pestaña "Registro": cada paso deja una línea con hora,
//     etiqueta y nivel (I=info, W=atención, E=error). También se
//     guarda en registro.log para poder compartirlo.
//   · Medidor de nivel del micrófono (barra + dB): si no se mueve,
//     el problema es la captura de audio, no Whisper.
//   · Contadores en vivo: trozos leídos, bloques capturados,
//     descartados y procesados, caracteres de texto.
//   · whisper-jni.c ahora "cuenta lo que hace" y Kotlin se lo
//     pregunta tras cada bloque (función estadoNativo).
//   · Los archivos (modelo, transcripción, registro) viven en la
//     carpeta externa de la app, visible desde un PC por USB.
// ============================================================

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : Activity() {

    companion object {
        // 16 kHz, mono, 16 bits: el formato que exige Whisper
        const val MUESTRAS_POR_SEGUNDO = 16000
        const val SEGUNDOS_POR_BLOQUE = 4
        const val MUESTRAS_POR_BLOQUE = MUESTRAS_POR_SEGUNDO * SEGUNDOS_POR_BLOQUE
        const val URL_MODELO = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
        const val NOMBRE_MODELO = "ggml-tiny.bin"
        const val NOMBRE_TRANSCRIPCION = "transcripcion.txt"
        const val NOMBRE_REGISTRO = "registro.log"
        // Cuántas líneas guardamos en memoria (la de la pantalla).
        // El archivo registro.log guarda todas.
        const val MAX_ENTRADAS_REGISTRO = 600
    }

    // ---- vistas ----
    private lateinit var estado: TextView
    private lateinit var stats: TextView
    private lateinit var transcripcion: TextView
    private lateinit var registroTexto: TextView
    private lateinit var scrollTranscripcion: ScrollView
    private lateinit var scrollRegistro: ScrollView
    private lateinit var nivelBarra: ProgressBar
    private lateinit var botonModelo: Button
    private lateinit var botonIdioma: Button
    private lateinit var botonTranscribir: Button
    private lateinit var botonCompartir: Button
    private lateinit var botonCompartirLog: Button
    private lateinit var botonLimpiar: Button
    private lateinit var pestanaTexto: Button
    private lateinit var pestanaRegistro: Button

    private val bridge = WhisperBridge()

    @Volatile private var ctx = 0L        // 0 = Whisper aún no cargado
    @Volatile private var escuchando = false
    private var idioma = "en"
    private var pestanaActiva = "texto"
    private var grabador: AudioRecord? = null
    private val cola = LinkedBlockingDeque<ShortArray>(2)

    // ---- contadores para la línea de estadísticas ----
    // @Volatile: los actualizan hilos de fondo y los lee la pantalla.
    @Volatile private var trozosLeidos = 0
    @Volatile private var bloquesCapturados = 0
    @Volatile private var bloquesDescartados = 0
    @Volatile private var bloquesProcesados = 0
    @Volatile private var caracteresTexto = 0

    // ---- carpeta de archivos: externa (visible por USB) si existe ----
    // Se fija en onCreate; antes de eso no se usa.
    private var carpeta: File = File("")
    private val archivoModelo get() = File(carpeta, NOMBRE_MODELO)
    private val archivoTranscripcion get() = File(carpeta, NOMBRE_TRANSCRIPCION)
    private val archivoRegistro get() = File(carpeta, NOMBRE_REGISTRO)

    // ---- el registro (log) en memoria ----
    private val entradasRegistro = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        estado = findViewById(R.id.estado)
        stats = findViewById(R.id.stats)
        transcripcion = findViewById(R.id.transcripcion)
        registroTexto = findViewById(R.id.registroTexto)
        scrollTranscripcion = findViewById(R.id.scrollTranscripcion)
        scrollRegistro = findViewById(R.id.scrollRegistro)
        nivelBarra = findViewById(R.id.nivelBarra)
        botonModelo = findViewById(R.id.botonModelo)
        botonIdioma = findViewById(R.id.botonIdioma)
        botonTranscribir = findViewById(R.id.botonTranscribir)
        botonCompartir = findViewById(R.id.botonCompartir)
        botonCompartirLog = findViewById(R.id.botonCompartirLog)
        botonLimpiar = findViewById(R.id.botonLimpiar)
        pestanaTexto = findViewById(R.id.pestanaTexto)
        pestanaRegistro = findViewById(R.id.pestanaRegistro)

        // Carpeta de datos: la externa de la app es la misma que la
        // interna para Android (misma "dueña", sin permisos extra),
        // pero se ve desde un PC conectado por USB. Si no existe,
        // caemos a la interna, que siempre existe.
        carpeta = getExternalFilesDir(null) ?: filesDir
        val movido = migrarModeloAntiguo()
        // Un registro de más de 1 MB de sesiones anteriores empieza
        // de cero: nos interesa lo de hoy.
        if (archivoRegistro.exists() && archivoRegistro.length() > 1_000_000L) {
            archivoRegistro.delete()
        }

        // ===== diagnóstico de arranque: saber QUÉ y DÓNDE desde el segundo 0
        log("I", "APP", "=== Subtilingus 0.3 (modo depuración) ===")
        log("I", "APP", "Móvil: " + Build.MANUFACTURER + " " + Build.MODEL +
                " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        log("I", "APP", "Carpeta de archivos: " + carpeta.absolutePath)
        if (movido) {
            log("I", "MODELO", "El modelo descargado en la versión anterior se ha movido a: " +
                    archivoModelo.absolutePath)
        }
        if (archivoModelo.exists()) {
            log("I", "MODELO", "Modelo presente: " + mb(archivoModelo.length()) + " MB")
        } else {
            log("I", "MODELO", "Sin modelo todavía: pulsa 'Modelo' para descargarlo (75 MB)")
        }
        val permisoOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        log("I", "PERM", "Permiso de micrófono concedido: " + permisoOk)

        estado.text = "Pulsa 'Modelo' para descargar Whisper (75 MB, solo la primera vez)."
        stats.text = "Sin actividad todavía."

        pestanaTexto.setOnClickListener { mostrarPestana("texto") }
        pestanaRegistro.setOnClickListener { mostrarPestana("registro") }
        mostrarPestana("texto")

        botonModelo.setOnClickListener { prepararModelo() }
        botonIdioma.setOnClickListener {
            idioma = if (idioma == "en") "es" else "en"
            botonIdioma.text = if (idioma == "en") "EN" else "ES"
            log("I", "APP", "Idioma de transcripción: " + idioma)
        }
        botonTranscribir.setOnClickListener {
            if (escuchando) detenerTranscripcion() else iniciarTranscripcion()
        }
        botonCompartir.setOnClickListener { compartirActivo() }
        botonCompartirLog.setOnClickListener { compartirRegistro() }
        botonLimpiar.setOnClickListener { limpiarActivo() }
    }

    // ================= REGISTRO (LOG) =================

    // Una línea con la hora + nivel + etiqueta + mensaje. Ejemplo:
    //   18:42:31.104 I/MIC: AudioRecord arrancado
    // La guarda en memoria (para la pestaña), en registro.log
    // (para poder compartirlo) y la pinta en pantalla.
    private fun log(nivel: String, etiqueta: String, mensaje: String) {
        val marca = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val linea = marca + " " + nivel + "/" + etiqueta + ": " + mensaje
        synchronized(entradasRegistro) {
            entradasRegistro.addLast(linea)
            while (entradasRegistro.size > MAX_ENTRADAS_REGISTRO) {
                entradasRegistro.removeFirst()
            }
        }
        try {
            archivoRegistro.appendText(linea + "\n")
        } catch (e: Exception) {
            // Si ni siquiera podemos escribir el registro, no hay
            // mucho más que hacer: que no crashee la app.
        }
        // runOnUiThread: esta función se llama desde hilos de fondo,
        // y solo el hilo principal puede tocar las vistas. Si ya
        // estamos en él, se ejecuta directamente.
        if (::registroTexto.isInitialized) {
            runOnUiThread {
                registroTexto.append(linea + "\n")
                scrollRegistro.post {
                    scrollRegistro.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    // Migra el modelo si la versión anterior lo dejó en filesDir
    // (memoria interna): así NO hay que volver a descargar 75 MB.
    private fun migrarModeloAntiguo(): Boolean {
        return try {
            val viejo = File(filesDir, NOMBRE_MODELO)
            if (!archivoModelo.exists() && viejo.exists() && viejo.length() > 50_000_000L) {
                if (viejo.renameTo(archivoModelo)) {
                    true
                } else {
                    // rename puede fallar entre "volúmenes" distintos:
                    // copiar y borrar es el plan B clásico.
                    viejo.copyTo(archivoModelo, overwrite = true)
                    viejo.delete()
                    true
                }
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun mb(bytes: Long): String {
        return String.format(Locale.US, "%.1f", bytes / 1_000_000.0)
    }

    // Cambia entre la vista de transcripción y la de registro.
    // alpha 1.0 = pestaña activa, 0.45 = atenuada.
    private fun mostrarPestana(nombre: String) {
        pestanaActiva = nombre
        val verTexto = nombre == "texto"
        scrollTranscripcion.visibility = if (verTexto) View.VISIBLE else View.GONE
        scrollRegistro.visibility = if (verTexto) View.GONE else View.VISIBLE
        pestanaTexto.alpha = if (verTexto) 1.0f else 0.45f
        pestanaRegistro.alpha = if (verTexto) 0.45f else 1.0f
    }

    // ================= MODELO =================

    private fun prepararModelo() {
        if (ctx != 0L) {
            Toast.makeText(this, "El modelo ya está listo", Toast.LENGTH_SHORT).show()
            return
        }
        if (archivoModelo.exists() && archivoModelo.length() > 50_000_000L) {
            cargarModelo(archivoModelo.absolutePath)
            return
        }
        thread {
            try {
                log("I", "RED", "Descargando modelo de: " + URL_MODELO)
                runOnUiThread { estado.text = "Descargando modelo... 0%" }
                val conexion = URL(URL_MODELO).openConnection() as HttpURLConnection
                conexion.connectTimeout = 30_000
                conexion.readTimeout = 30_000
                conexion.connect()
                val total = conexion.contentLength.toLong()
                log("I", "RED", "Conexión abierta. Tamaño anunciado: " +
                        (if (total > 0) mb(total) + " MB" else "desconocido"))
                val temporal = File(carpeta, "modelo.tmp")
                var leidos = 0L
                var ultimoPctRegistrado = -10
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
                                runOnUiThread {
                                    estado.text = "Descargando modelo... " + pct + "%"
                                }
                                // Al registro solo cada 10%: no saturarlo
                                if (pct >= ultimoPctRegistrado + 10) {
                                    ultimoPctRegistrado = pct
                                    log("I", "RED", "Descarga al " + pct + "% (" + mb(leidos) + " MB)")
                                }
                            }
                        }
                    }
                }
                if (leidos < 50_000_000L) {
                    throw IOException("Descarga incompleta: solo " + mb(leidos) + " MB")
                }
                log("I", "RED", "Descarga terminada: " + mb(leidos) + " MB")
                // Se descarga a un .tmp y solo se renombra al final:
                // si algo falla a mitad, nunca queda un archivo roto
                // con pinta de bueno.
                if (!temporal.renameTo(archivoModelo)) {
                    temporal.copyTo(archivoModelo, overwrite = true)
                    temporal.delete()
                }
                conexion.disconnect()
                cargarModelo(archivoModelo.absolutePath)
            } catch (e: Exception) {
                log("E", "RED", "Error al descargar: " + e)
                runOnUiThread { estado.text = "Error al descargar: " + e.message }
            }
        }
    }

    private fun cargarModelo(ruta: String) {
        log("I", "MODELO", "Cargando modelo en memoria (puede tardar varios segundos)...")
        runOnUiThread { estado.text = "Cargando modelo en memoria..." }
        // initContext es una llamada NATIVA: puede tardar. La hacemos
        // en el hilo actual (estamos en un hilo de fondo).
        val nuevoCtx = bridge.initContext(ruta)
        log("I", "NATIVO", bridge.estadoNativo() + " · manejador=" + nuevoCtx)
        runOnUiThread {
            if (nuevoCtx != 0L) {
                ctx = nuevoCtx
                estado.text = "Whisper listo (tiny multilingüe). Pulsa 'Transcribir' y habla."
                botonModelo.text = "Modelo listo"
                botonModelo.isEnabled = false
            } else {
                estado.text = "No se pudo cargar el modelo (mira el Registro)"
                log("E", "MODELO", "Whisper NO se cargó. El archivo puede estar corrupto: " +
                        "pulsa 'Limpiar' y vuelve a descargarlo.")
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
            log("W", "PERM", "Sin permiso de micrófono: pidiéndolo al usuario...")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val minimo = AudioRecord.getMinBufferSize(
            MUESTRAS_POR_SEGUNDO,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        log("I", "MIC", "Buffer mínimo del micrófono: " + minimo + " bytes")
        if (minimo <= 0) {
            log("E", "MIC", "getMinBufferSize devolvió " + minimo + ": este móvil no soporta la captura")
            Toast.makeText(this, "Este móvil no soporta la captura de audio", Toast.LENGTH_LONG).show()
            return
        }
        try {
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
                log("E", "MIC", "AudioRecord quedó SIN inicializar (¿otra app usando el micrófono?)")
                Toast.makeText(this, "No se pudo abrir el micrófono", Toast.LENGTH_LONG).show()
                return
            }
            grabador = nuevo
            nuevo.startRecording()
            // recordingState: 1 = parado, 3 = grabando de verdad
            log("I", "MIC", "AudioRecord arrancado (recordingState=" + nuevo.recordingState + ")")
        } catch (e: Exception) {
            log("E", "MIC", "Excepción al abrir el micrófono: " + e)
            Toast.makeText(this, "No se pudo abrir el micrófono", Toast.LENGTH_LONG).show()
            return
        }
        escuchando = true
        trozosLeidos = 0
        bloquesCapturados = 0
        bloquesDescartados = 0
        bloquesProcesados = 0
        caracteresTexto = 0
        botonTranscribir.text = "Detener"
        cola.clear()
        log("I", "APP", "Transcripción iniciada. Idioma: " + idioma +
                ". Transcripción se guardará en: " + archivoTranscripcion.absolutePath)
        thread { bucleAudio(grabador!!) }
        thread { bucleTranscripcion() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val concedido = requestCode == 1 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        log("I", "PERM", "Respuesta del diálogo de permiso: concedido=" + concedido)
        if (concedido) {
            iniciarTranscripcion()
        } else {
            Toast.makeText(this, "Sin micrófono no hay transcripción", Toast.LENGTH_LONG).show()
        }
    }

    // El "productor": lee el micrófono y suelta bloques de 4 segundos.
    // NOVEDAD: nada de this-bucle-se-muere-en-silencio. Cada rama
    // problemática deja constancia en el Registro.
    private fun bucleAudio(grabador: AudioRecord) {
        val bloque = ShortArray(1600)
        val acumulado = ShortArray(MUESTRAS_POR_BLOQUE)
        var n = 0
        var rmsVentana = 0.0
        var ultimoRefresco = 0L
        while (escuchando) {
            val leidas = grabador.read(bloque, 0, bloque.size)
            if (leidas < 0) {
                // La Fase 1 hacía "break" sin decir NADA: este era el
                // punto de fallo invisible más probable.
                val motivo = when (leidas) {
                    AudioRecord.ERROR_BAD_VALUE ->
                        "ERROR_BAD_VALUE (argumentos inválidos)"
                    AudioRecord.ERROR_INVALID_OPERATION ->
                        "ERROR_INVALID_OPERATION (lectura en estado no válido)"
                    AudioRecord.ERROR_NO_INIT ->
                        "ERROR_NO_INIT (el grabador no se inicializó)"
                    else -> "código desconocido " + leidas
                }
                log("E", "MIC", "read() falló: " + motivo +
                        ". El hilo de audio se detiene. Trozos leídos hasta aquí: " + trozosLeidos)
                break
            }
            if (leidas == 0) continue
            trozosLeidos++

            // RMS = raíz de la media de los cuadrados: "volumen medio"
            // del trozo recién leído. Sirve para el medidor de nivel.
            var suma = 0.0
            for (i in 0 until leidas) {
                val v = bloque[i].toDouble()
                suma += v * v
            }
            rmsVentana = max(rmsVentana, sqrt(suma / leidas))

            val espacio = acumulado.size - n
            val copiable = if (leidas <= espacio) leidas else espacio
            if (copiable > 0) {
                System.arraycopy(bloque, 0, acumulado, n, copiable)
                n += copiable
            }
            if (n >= acumulado.size) {
                while (!cola.offerLast(acumulado.copyOf())) {
                    // Cola llena = Whisper no da abasto: soltamos el
                    // bloque más viejo y lo contamos.
                    cola.pollFirst()
                    bloquesDescartados++
                    log("W", "COLA", "Whisper va lento: bloque descartado (total " +
                            bloquesDescartados + ")")
                }
                bloquesCapturados++
                n = 0
            }

            // Refresco de pantalla cada 250 ms (no en cada trozo:
            // serían 10 veces por segundo y no aporta nada)
            val ahora = SystemClock.elapsedRealtime()
            if (ahora - ultimoRefresco >= 250) {
                val rms = rmsVentana
                rmsVentana = 0.0
                ultimoRefresco = ahora
                runOnUiThread { refrescarStats(rms) }
            }
        }
        log("I", "MIC", "Hilo de audio terminado (trozos leídos: " + trozosLeidos + ")")
    }

    // Pinta la línea de estadísticas y el medidor de nivel.
    private fun refrescarStats(rms: Double) {
        // dB respecto al máximo teórico (32768): -inf = silencio total,
        // -60 = muy flojo, -20 = voz normal, -6 = gritando
        val db: Double = if (rms <= 0.0) -99.0 else 20.0 * log10(rms / 32768.0)
        val dbTexto = if (db <= -90.0) "-inf" else String.format(Locale.US, "%.0f", db)
        stats.text = "mic " + dbTexto + " dB · trozos " + trozosLeidos +
                " · bloques " + bloquesCapturados +
                " · descartados " + bloquesDescartados +
                " · procesados " + bloquesProcesados +
                " · texto " + caracteresTexto + " car."
        nivelBarra.progress = ((db + 90.0) / 90.0 * 100.0).toInt().coerceIn(0, 100)
    }

    // El "consumidor": cada bloque -> float -> Whisper -> pantalla + archivo
    private fun bucleTranscripcion() {
        var numero = 0
        while (true) {
            val bloque = cola.pollFirst(1, TimeUnit.SECONDS)
            if (bloque == null) {
                if (!escuchando) break
                continue
            }
            numero++
            // Whisper quiere muestras float entre -1.0 y 1.0
            val muestras = FloatArray(bloque.size)
            for (i in bloque.indices) {
                muestras[i] = bloque[i] / 32768.0f
            }
            val t0 = SystemClock.elapsedRealtime()
            val texto = bridge.transcribeSamples(ctx, muestras, idioma)
            val ms = SystemClock.elapsedRealtime() - t0
            // estadoNativo() devuelve lo que pasó DENTRO de C:
            // código de retorno, segmentos y CPU consumida.
            log("I", "WHISPER", "bloque " + numero + ": " + bridge.estadoNativo() +
                    " · " + ms + " ms en total")
            bloquesProcesados++
            val limpio = texto.trim()
            if (limpio.isNotEmpty()) {
                // En el registro recortamos a 120 caracteres: el texto
                // completo va a la pestaña Texto y al archivo.
                val muestra = if (limpio.length > 120) limpio.substring(0, 120) + "..." else limpio
                log("I", "TEXTO", "«" + muestra + "»")
                caracteresTexto += limpio.length
                runOnUiThread {
                    transcripcion.append(limpio + " ")
                    scrollTranscripcion.post {
                        scrollTranscripcion.fullScroll(View.FOCUS_DOWN)
                    }
                }
                synchronized(archivoTranscripcion) {
                    archivoTranscripcion.appendText(limpio + " ")
                }
            } else {
                log("I", "WHISPER", "bloque " + numero + " sin texto (silencio o no reconocido)")
            }
        }
        log("I", "WHISPER", "Hilo de transcripción terminado (bloques procesados: " + bloquesProcesados + ")")
    }

    private fun detenerTranscripcion() {
        escuchando = false
        botonTranscribir.text = "Transcribir"
        grabador?.let {
            try { it.stop() } catch (e: Exception) {
                log("W", "MIC", "stop() lanzó excepción (normal si ya había muerto): " + e)
            }
            it.release()
        }
        grabador = null
        estado.text = "En pausa. La transcripción queda guardada en el móvil."
        log("I", "APP", "Transcripción detenida. Resumen: trozos " + trozosLeidos +
                ", bloques " + bloquesCapturados + ", descartados " + bloquesDescartados +
                ", procesados " + bloquesProcesados + ", texto " + caracteresTexto + " car.")
    }

    // ============ COMPARTIR Y LIMPIAR ============

    // Comparte lo que muestre la pestaña ACTIVA (texto o registro)
    private fun compartirActivo() {
        if (pestanaActiva == "registro") {
            compartirRegistro()
            return
        }
        val texto = synchronized(archivoTranscripcion) {
            if (archivoTranscripcion.exists()) archivoTranscripcion.readText() else ""
        }
        if (texto.isBlank()) {
            Toast.makeText(this, "Aún no hay transcripción que compartir", Toast.LENGTH_SHORT).show()
            return
        }
        mandarPorIntent(texto, "Compartir transcripción")
    }

    private fun compartirRegistro() {
        val texto = synchronized(entradasRegistro) {
            if (archivoRegistro.exists()) archivoRegistro.readText() else ""
        }
        if (texto.isBlank()) {
            Toast.makeText(this, "El registro está vacío", Toast.LENGTH_SHORT).show()
            return
        }
        mandarPorIntent(texto, "Compartir registro")
    }

    // Intent implícito: le pedimos a Android "¿quién sabe compartir
    // texto?" y salta el selector con WhatsApp, Gmail, etc.
    private fun mandarPorIntent(texto: String, titulo: String) {
        val intento = Intent(Intent.ACTION_SEND)
        intento.type = "text/plain"
        intento.putExtra(Intent.EXTRA_TEXT, texto)
        startActivity(Intent.createChooser(intento, titulo))
    }

    // Limpia lo que muestre la pestaña ACTIVA (texto o registro)
    private fun limpiarActivo() {
        if (pestanaActiva == "registro") {
            synchronized(entradasRegistro) {
                entradasRegistro.clear()
                archivoRegistro.delete()
            }
            registroTexto.text = ""
            log("I", "APP", "Registro limpiado a mano")
            Toast.makeText(this, "Registro vaciado", Toast.LENGTH_SHORT).show()
        } else {
            transcripcion.text = ""
            synchronized(archivoTranscripcion) {
                archivoTranscripcion.delete()
            }
            log("I", "APP", "Transcripción limpiada a mano")
            Toast.makeText(this, "Transcripción borrada", Toast.LENGTH_SHORT).show()
        }
    }

    // ============ CICLO DE VIDA ============

    override fun onPause() {
        super.onPause()
        if (escuchando) {
            log("W", "APP", "La app pasó a segundo plano: se detiene la escucha")
            detenerTranscripcion()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        log("I", "APP", "La app se cierra. Liberando el modelo de memoria...")
        if (ctx != 0L) {
            bridge.freeContext(ctx)
            ctx = 0L
        }
    }
}
