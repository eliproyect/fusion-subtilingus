package com.elisotopo.subtilingus

// ============================================================
// Subtilingus v0.7 · Pantalla principal
// ============================================================
// Tres pestañas: Escuchar · Textos · Ajustes. La app solo "dirige":
// pide permisos, lanza el servicio (TranscriberService) y dibuja lo
// que éste le cuenta a través de Estado.
// ============================================================

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val REQ_PERMISOS = 1
        const val REQ_PROYECCION = 10
        const val REQ_EXPORTAR = 12
    }

    lateinit var ajustes: Ajustes
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var contenedor: FrameLayout
    private lateinit var pantallas: List<Pantalla>
    private lateinit var pantallaTextos: PantallaTextos
    private val pestanas = ArrayList<TextView>()
    private var actual = 0
    private var exportPendiente = ""

    private val tic = object : Runnable {
        override fun run() {
            pantallas[actual].refrescar()
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ajustes = Ajustes(this)
        Registro.iniciar(Carpetas.base(this))
        Migracion.ejecutar(this)
        Registro.i("APP", "=== Subtilingus 0.7.1 · " + Build.MANUFACTURER + " " + Build.MODEL +
                " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        Registro.i("APP", "Carpeta de archivos: " + Carpetas.base(this).absolutePath)

        window.statusBarColor = Tema.FONDO
        window.navigationBarColor = Tema.TARJETA

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setBackgroundColor(Tema.FONDO)

        contenedor = FrameLayout(this)
        raiz.addView(contenedor, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val barra = LinearLayout(this)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.setBackgroundColor(Tema.TARJETA)
        val nombres = listOf("🎧\nEscuchar", "📚\nTextos", "⚙️\nAjustes")
        for ((i, n) in nombres.withIndex()) {
            val t = texto(n, 12f, Tema.TEXTO2, true)
            t.gravity = android.view.Gravity.CENTER
            t.setPadding(0, dp(8), 0, dp(8))
            t.setOnClickListener { irA(i) }
            barra.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            pestanas.add(t)
        }
        raiz.addView(barra, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(raiz)

        pantallaTextos = PantallaTextos(this)
        pantallas = listOf(PantallaEscuchar(this), pantallaTextos, PantallaAjustes(this))
        for (p in pantallas) {
            contenedor.addView(p.vista, FrameLayout.LayoutParams(MATCH, MATCH))
            p.vista.visibility = View.GONE
        }
        irA(0)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tic)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tic)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (actual == 1 && pantallaTextos.atras()) return
        super.onBackPressed()
    }

    fun irA(i: Int) {
        actual = i
        for ((k, p) in pantallas.withIndex()) {
            p.vista.visibility = if (k == i) View.VISIBLE else View.GONE
        }
        for ((k, t) in pestanas.withIndex()) {
            t.setTextColor(if (k == i) Tema.ACENTO else Tema.TEXTO2)
        }
        pantallas[i].alMostrar()
        pantallas[i].refrescar()
    }

    fun toast(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }

    fun confirmar(titulo: String, mensaje: String, textoSi: String, accion: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton(textoSi) { _, _ -> accion() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ============ EMPEZAR / DETENER ============

    fun alternarEscucha() {
        if (Estado.corriendo || Estado.cargando) {
            startService(Intent(this, TranscriberService::class.java).setAction(TranscriberService.ACCION_DETENER))
            return
        }
        val m = Modelos.buscar(ajustes.modeloId) ?: Modelos.buscar(Modelos.RECOMENDADO_ID)!!
        if (!Modelos.descargado(this, m)) {
            AlertDialog.Builder(this)
                .setTitle("Falta el modelo de voz")
                .setMessage("«" + m.nombre + "» ocupa unos " + m.mbAprox + " MB y se descarga una sola vez. " +
                        "Después todo funciona sin internet.\n\nMantén la app abierta mientras se descarga.")
                .setPositiveButton("Descargar") { _, _ ->
                    Descargas.descargar(this, m) { }
                    irA(2)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        if (m.soloIngles && ajustes.idioma == "es") {
            AlertDialog.Builder(this)
                .setTitle("Este modelo solo entiende inglés")
                .setMessage("Has elegido «Español», pero el modelo activo es solo inglés. " +
                        "Elige un modelo «Multilingüe» en Ajustes o cambia el idioma a English.")
                .setPositiveButton("Ir a Ajustes") { _, _ -> irA(2) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        pedirPermisos()
    }

    private fun pedirPermisos() {
        val faltan = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (faltan.isNotEmpty()) {
            requestPermissions(faltan.toTypedArray(), REQ_PERMISOS)
            return
        }
        continuarTrasPermisos()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISOS) return
        var micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        for ((i, p) in permissions.withIndex()) {
            if (p == Manifest.permission.RECORD_AUDIO && i < grantResults.size) {
                micOk = grantResults[i] == PackageManager.PERMISSION_GRANTED
            }
        }
        Registro.i("PERM", "Micrófono concedido: " + micOk)
        if (micOk) continuarTrasPermisos() else toast("Sin el permiso de audio no puedo transcribir.")
    }

    private fun continuarTrasPermisos() {
        if (ajustes.overlay && !Settings.canDrawOverlays(this)) {
            dialogoOverlay()
            return
        }
        iniciarCaptura()
    }

    fun dialogoOverlay() {
        AlertDialog.Builder(this)
            .setTitle("Permiso para los subtítulos flotantes")
            .setMessage("Para dibujar los subtítulos encima de BBC, Spotify, YouTube… Android necesita que actives " +
                    "«Mostrar sobre otras apps» para Subtilingus.\n\nActívalo, vuelve aquí y pulsa Empezar de nuevo.")
            .setPositiveButton("Dar permiso") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            }
            .setNeutralButton("Seguir sin flotantes") { _, _ ->
                ajustes.overlay = false
                iniciarCaptura()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun iniciarCaptura() {
        if (ajustes.fuente == Ajustes.FUENTE_INTERNO) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROYECCION)
        } else {
            lanzarServicio()
        }
    }

    private fun lanzarServicio() {
        val i = Intent(this, TranscriberService::class.java).setAction(TranscriberService.ACCION_INICIAR)
        try {
            startForegroundService(i)
        } catch (e: Exception) {
            Registro.e("APP", "No se pudo lanzar el servicio: " + e)
            toast("No se pudo iniciar: " + e.message)
        }
    }

    // ============ EXPORTAR ============

    fun exportar(nombre: String, mime: String, contenido: String) {
        exportPendiente = contenido
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        i.putExtra(Intent.EXTRA_TITLE, nombre)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORTAR)
    }

    fun compartir(texto: String, titulo: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, texto)
        startActivity(Intent.createChooser(i, titulo))
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROYECCION) {
            if (resultCode == RESULT_OK && data != null) {
                Estado.proyeccionCodigo = resultCode
                Estado.proyeccionDatos = data
                lanzarServicio()
            } else {
                toast("Sin ese permiso no puedo oír el audio del móvil. Prueba el modo Micrófono.")
            }
        } else if (requestCode == REQ_EXPORTAR && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val contenido = exportPendiente
            thread(name = "exportar") {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(contenido.toByteArray(Charsets.UTF_8)) }
                    runOnUiThread { toast("Archivo guardado.") }
                } catch (e: Exception) {
                    Registro.e("EXPORTAR", "Fallo: " + e)
                    runOnUiThread { toast("No se pudo guardar: " + e.message) }
                }
            }
        }
    }
}
