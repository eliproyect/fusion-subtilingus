package com.elisotopo.subtilingus

import android.content.Intent
import java.io.File

// Estado compartido entre el servicio (que transcribe) y la pantalla
// (que lo dibuja). La pantalla lo consulta 4 veces por segundo.
object Estado {
    @Volatile var corriendo = false
    @Volatile var cargando = false
    @Volatile var mensaje = "Listo para escuchar."
    @Volatile var avisoAudio = ""
    @Volatile var avisoRendimiento = ""
    @Volatile var nivelDb = -99.0
    @Volatile var velocidad = ""
    @Volatile var provisional = ""
    @Volatile var version = 0
    @Volatile var archivoSesion: File? = null

    // Frases ya confirmadas de la sesión actual (usar siempre synchronized)
    val lineas = ArrayList<String>()

    // Resultado del permiso de captura de audio interno (MediaProjection)
    @Volatile var proyeccionCodigo = 0
    @Volatile var proyeccionDatos: Intent? = null

    @Synchronized
    fun cambio() {
        version++
    }

    fun reiniciarSesion() {
        synchronized(lineas) { lineas.clear() }
        provisional = ""
        avisoAudio = ""
        avisoRendimiento = ""
        velocidad = ""
        nivelDb = -99.0
        archivoSesion = null
        cambio()
    }
}
