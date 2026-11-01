package com.elisotopo.subtilingus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlin.concurrent.thread

// ============================================================
// Servicio en primer plano: sigue escuchando y transcribiendo aunque
// cambies a BBC, Spotify, YouTube... (la v0.6 se paraba al salir de
// la app). Muestra una notificación con botón "Detener".
// ============================================================
class TranscriberService : Service() {

    companion object {
        const val ACCION_INICIAR = "com.elisotopo.subtilingus.INICIAR"
        const val ACCION_DETENER = "com.elisotopo.subtilingus.DETENER"
        private const val CANAL = "subtilingus_escucha"
        private const val ID_NOTIF = 77

        @Volatile var instancia: TranscriberService? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    private var proyeccion: MediaProjection? = null
    private var captura: Captura? = null
    private var transcriptor: Transcriptor? = null
    private var overlay: Overlay? = null
    private var escritor: EscritorSesion? = null
    private var hiloArranque: Thread? = null
    @Volatile private var parando = false
    private var ultimoFinal = ""
    private var penultimoFinal = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
    }

    override fun onDestroy() {
        instancia = null
        if (!parando) {
            overlay?.quitar()
            try {
                proyeccion?.stop()
            } catch (e: Exception) {
            }
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_DETENER -> {
                if (Estado.corriendo || Estado.cargando) detener() else stopSelf()
            }
            ACCION_INICIAR -> iniciar()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- notificación ----------

    private fun crearNotificacion(): Notification {
        val gestor = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            gestor.createNotificationChannel(
                NotificationChannel(CANAL, "Transcripción en curso", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val parar = PendingIntent.getService(
            this, 1,
            Intent(this, TranscriberService::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE
        )
        val accion = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif), "Detener", parar
        ).build()
        return Notification.Builder(this, CANAL)
            .setContentTitle("Subtilingus está escuchando")
            .setContentText("Toca para abrir · o pulsa Detener")
            .setSmallIcon(R.drawable.ic_notif)
            .setOngoing(true)
            .setContentIntent(abrir)
            .addAction(accion)
            .build()
    }

    // ---------- arranque ----------

    private fun iniciar() {
        val ajustes = Ajustes(this)
        val fuente = ajustes.fuente
        val notif = crearNotificacion()
        try {
            if (fuente == Ajustes.FUENTE_INTERNO) {
                if (Build.VERSION.SDK_INT >= 30) {
                    startForeground(ID_NOTIF, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(ID_NOTIF, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                }
            } else {
                if (Build.VERSION.SDK_INT >= 30) {
                    startForeground(ID_NOTIF, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(ID_NOTIF, notif)
                }
            }
        } catch (e: Exception) {
            Registro.e("SERVICIO", "startForeground falló: " + e)
            Estado.mensaje = "No se pudo iniciar el servicio: " + e.message
            stopSelf()
            return
        }
        if (Estado.corriendo || Estado.cargando) return

        parando = false
        Estado.reiniciarSesion()
        Estado.cargando = true
        Estado.mensaje = "Cargando el modelo de voz…"
        ultimoFinal = ""
        penultimoFinal = ""

        if (ajustes.overlay && Settings.canDrawOverlays(this)) {
            val o = Overlay(this, ajustes)
            o.mostrarVentana()
            overlay = o
        }

        if (fuente == Ajustes.FUENTE_INTERNO) {
            try {
                val datos = Estado.proyeccionDatos ?: throw IllegalStateException("sin permiso de captura")
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(Estado.proyeccionCodigo, datos)
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        ui.post { detener() }
                    }
                }, ui)
                proyeccion = p
            } catch (e: Exception) {
                Registro.e("SERVICIO", "MediaProjection falló: " + e)
                fallo("No se pudo obtener el permiso de captura de audio: " + e.message)
                return
            }
            Estado.proyeccionDatos = null
        }

        hiloArranque = thread(name = "arranque") { arrancar(ajustes, fuente) }
    }

    private fun arrancar(ajustes: Ajustes, fuente: String) {
        try {
            val modelo = Modelos.buscar(ajustes.modeloId) ?: Modelos.buscar(Modelos.RECOMENDADO_ID)!!
            if (!Modelos.descargado(this, modelo)) {
                ui.post { fallo("Falta el modelo de voz. Descárgalo en Ajustes.") }
                return
            }
            val idioma = ajustes.idioma
            val tr = Transcriptor(modelo, Modelos.archivo(this, modelo), idioma, ajustes.modoRapido, ajustes.hilos,
                { texto, ms -> alFinal(texto, ms) },
                { texto -> alProvisional(texto) })
            if (!tr.cargar()) {
                tr.terminar()
                ui.post { fallo("No se pudo cargar el modelo. Bórralo en Ajustes y descárgalo de nuevo.") }
                return
            }
            if (parando) {
                tr.terminar()
                return
            }
            transcriptor = tr
            escritor = Sesiones.nueva(this, if (modelo.soloIngles) "en" else idioma, fuente, modelo.id)

            val seg = Segmentador(
                { audio, ini, id -> tr.encolarFinal(audio, ini, id) },
                { audio, ini, id -> tr.ofrecerParcial(audio, ini, id) }
            )
            val inicio = SystemClock.elapsedRealtime()
            val cap = Captura(fuente, proyeccion,
                alAudio = { buf, n ->
                    seg.entrada(buf, n)
                    Estado.nivelDb = seg.nivelDb
                    if (!seg.oyoAlgo && SystemClock.elapsedRealtime() - inicio > 12000) {
                        Estado.avisoAudio = if (fuente == Ajustes.FUENTE_INTERNO) {
                            "No llega audio del móvil. Puede que la app que suena bloquee la captura (Netflix, algunas de streaming) o que no esté sonando. Prueba el modo Micrófono."
                        } else {
                            "El micrófono no oye nada. Acerca el móvil a la fuente de sonido."
                        }
                    } else if (seg.oyoAlgo && Estado.avisoAudio.isNotEmpty()) {
                        Estado.avisoAudio = ""
                    }
                },
                alError = { msg ->
                    Registro.e("CAPTURA", msg)
                    Estado.avisoAudio = msg
                    ui.post { detener() }
                },
                alFin = { seg.finalizar() }
            )
            val error = cap.abrir()
            if (error != null) {
                tr.terminar()
                ui.post { fallo(error) }
                return
            }
            captura = cap
            if (parando) {
                cap.detener()
                tr.terminar()
                return
            }
            tr.iniciarHilo()
            cap.iniciar()
            Estado.corriendo = true
            Estado.cargando = false
            Estado.mensaje = "Escuchando…"
            Registro.i("SERVICIO", "Escuchando. Fuente=" + fuente + ", idioma=" + idioma + ", modelo=" + modelo.id)
        } catch (e: Throwable) {
            Registro.e("SERVICIO", "Fallo al arrancar: " + e)
            ui.post { fallo("Error al arrancar: " + e.message) }
        }
    }

    private fun fallo(mensaje: String) {
        Registro.e("SERVICIO", mensaje)
        Estado.mensaje = mensaje
        Estado.corriendo = false
        Estado.cargando = false
        limpiarUi()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- resultados (hilo de Whisper) ----------

    private fun alFinal(texto: String, ms: Long) {
        val e = escritor
        if (e != null) {
            try {
                e.anadir(ms, texto)
                Estado.archivoSesion = e.archivo
            } catch (x: Exception) {
                Registro.e("TEXTOS", "No se pudo guardar la frase: " + x)
            }
        }
        synchronized(Estado.lineas) { Estado.lineas.add(texto) }
        penultimoFinal = ultimoFinal
        ultimoFinal = texto
        Estado.cambio()
        val ant = penultimoFinal
        val act = ultimoFinal
        ui.post { overlay?.actualizar(ant, act) }
    }

    private fun alProvisional(texto: String) {
        Estado.provisional = texto
        Estado.cambio()
        if (texto.isNotEmpty()) {
            val ant = ultimoFinal
            ui.post { overlay?.actualizar(ant, texto) }
        }
    }

    // ---------- control desde la pantalla ----------

    fun ponerOverlay(activar: Boolean) {
        ui.post {
            if (activar) {
                if (overlay == null && Settings.canDrawOverlays(this)) {
                    val o = Overlay(this, Ajustes(this))
                    o.mostrarVentana()
                    overlay = o
                }
            } else {
                overlay?.quitar()
                overlay = null
            }
        }
    }

    // ---------- parada ----------

    private fun detener() {
        if (parando) return
        parando = true
        Estado.mensaje = "Terminando de transcribir lo pendiente…"
        thread(name = "parada") {
            try {
                hiloArranque?.join(20000)
                captura?.detener()
                transcriptor?.terminar()
            } catch (e: Exception) {
                Registro.e("SERVICIO", "Error al parar: " + e)
            }
            val guardado = escritor?.creado == true
            Estado.corriendo = false
            Estado.cargando = false
            Estado.mensaje = if (guardado) "Detenido. El texto está guardado en la pestaña Textos." else "Detenido."
            Estado.cambio()
            Registro.i("SERVICIO", "Detenido")
            ui.post {
                limpiarUi()
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun limpiarUi() {
        overlay?.quitar()
        overlay = null
        val p = proyeccion
        proyeccion = null
        try {
            p?.stop()
        } catch (e: Exception) {
        }
    }
}
