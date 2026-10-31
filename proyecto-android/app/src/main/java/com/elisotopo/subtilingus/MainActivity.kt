package com.elisotopo.subtilingus

// ============================================================
// Subtilingus · Fase 0 — Proyecto base
// ============================================================
// Esta pantalla hace dos cosas:
//   1. Muestra información del móvil (para comprobar que la app
//      se instaló bien y sabe dónde está).
//   2. Prueba el micrófono: pide el permiso, escucha y dibuja una
//      barra con el volumen que oye.
//
// Es el primer ladrillo de la app real: cuando integremos Whisper,
// este mismo flujo (permiso -> AudioRecord) será el que alimente
// al traductor. Aquí solo comprobamos que el camino
// micrófono -> código funciona en tu móvil.
// ============================================================

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // "lateinit" = "lo inicializaré en onCreate()" (Kotlin no deja
    // usar una variable sin valor; esto lo promete por ti)
    private lateinit var infoDispositivo: TextView
    private lateinit var textoNivel: TextView
    private lateinit var botonMicro: Button

    // El capturador de micrófono. null = apagado.
    private var grabador: AudioRecord? = null
    private var escuchando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // "Infla" el layout XML: convierte activity_main.xml en
        // objetos de pantalla reales y los muestra.
        setContentView(R.layout.activity_main)

        infoDispositivo = findViewById(R.id.infoDispositivo)
        textoNivel = findViewById(R.id.textoNivel)
        botonMicro = findViewById(R.id.botonMicro)

        infoDispositivo.text =
            "Modelo: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
            "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "Paquete: " + packageName

        botonMicro.setOnClickListener {
            if (escuchando) detenerEscucha() else pedirPermisoYEscuchar()
        }
    }

    // Android exige pedir los permisos "peligrosos" (como el
    // micrófono) EN TIEMPO DE EJECUCIÓN: no basta con declararlos
    // en el manifest. Es un diálogo del sistema.
    private fun pedirPermisoYEscuchar() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            empezarEscucha()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    // Android llama aquí cuando el usuario contesta al diálogo.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            empezarEscucha()
        } else {
            Toast.makeText(
                this,
                "Sin micrófono no hay transcripción",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun empezarEscucha() {
        // 16.000 muestras/segundo, 1 canal, 16 bits: el formato
        // estándar que luego exigirá Whisper. ¡Ya está elegido!
        val frecuencia = 16000
        val minimo = AudioRecord.getMinBufferSize(
            frecuencia,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimo <= 0) {
            Toast.makeText(this, "Este móvil no soporta la captura de audio", Toast.LENGTH_LONG).show()
            return
        }
        val tamanoBuffer = minimo.coerceAtLeast(4096)

        @Suppress("DEPRECATION") // la construcción nueva es más larga; esta funciona
        val nuevo = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            frecuencia,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            tamanoBuffer
        )

        if (nuevo.state != AudioRecord.STATE_INITIALIZED) {
            nuevo.release()
            Toast.makeText(this, "No se pudo abrir el micrófono", Toast.LENGTH_LONG).show()
            return
        }

        grabador = nuevo
        nuevo.startRecording()
        escuchando = true
        botonMicro.text = "Detener"
        textoNivel.text = "Escuchando...\n"

        // Un hilo aparte lee el micrófono (read() se queda esperando
        // hasta que hay sonido, bloqueando): así la pantalla sigue
        // respondiendo mientras el "worker" trabaja.
        thread {
            // 1600 muestras = 0,1 segundos de audio por pasada
            val muestras = ShortArray(1600)
            while (escuchando) {
                val leidas = nuevo.read(muestras, 0, muestras.size)
                if (leidas <= 0) break

                // La muestra más alta del bloque = "volumen"
                var maximo = 0
                for (i in 0 until leidas) {
                    var absoluto = muestras[i].toInt()
                    if (absoluto < 0) absoluto = -absoluto
                    if (absoluto > maximo) maximo = absoluto
                }

                // Solo el hilo principal puede tocar la pantalla:
                // runOnUiThread le "pasa" el resultado desde nuestro hilo.
                runOnUiThread {
                    val barras = (maximo / 500).coerceIn(0, 24)
                    textoNivel.text =
                        "Nivel: " + maximo + "\n" +
                        "▮".repeat(barras) + "▯".repeat(24 - barras)
                }
            }
        }
    }

    private fun detenerEscucha() {
        escuchando = false
        botonMicro.text = "Probar micrófono"
        grabador?.let {
            try { it.stop() } catch (e: Exception) { /* ya estaba parado */ }
            it.release()
        }
        grabador = null
        textoNivel.text = "Micrófono detenido."
    }

    // Si sales de la app con el micro abierto, lo apagamos:
    // buena higiene (y la batería lo agradece).
    override fun onPause() {
        super.onPause()
        if (escuchando) detenerEscucha()
    }
}
