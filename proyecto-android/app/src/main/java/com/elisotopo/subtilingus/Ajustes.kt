package com.elisotopo.subtilingus

import android.content.Context

// Preferencias de la app (SharedPreferences con nombres en español).
class Ajustes(contexto: Context) {

    companion object {
        const val FUENTE_INTERNO = "interno"   // audio que suena en el móvil
        const val FUENTE_MIC = "mic"           // micrófono (TV, clase...)
    }

    private val sp = contexto.applicationContext.getSharedPreferences("subtilingus", Context.MODE_PRIVATE)

    var modeloId: String
        get() = sp.getString("modelo", Modelos.RECOMENDADO_ID) ?: Modelos.RECOMENDADO_ID
        set(v) { sp.edit().putString("modelo", v).apply() }

    // "en", "es" o "auto"
    var idioma: String
        get() = sp.getString("idioma", "en") ?: "en"
        set(v) { sp.edit().putString("idioma", v).apply() }

    var fuente: String
        get() = sp.getString("fuente", FUENTE_INTERNO) ?: FUENTE_INTERNO
        set(v) { sp.edit().putString("fuente", v).apply() }

    var overlay: Boolean
        get() = sp.getBoolean("overlay", true)
        set(v) { sp.edit().putBoolean("overlay", v).apply() }

    // 0 pequeño, 1 medio, 2 grande
    var tamanoTexto: Int
        get() = sp.getInt("tamano", 1)
        set(v) { sp.edit().putInt("tamano", v).apply() }

    // distancia (dp) del borde inferior de la pantalla
    var overlayY: Int
        get() = sp.getInt("overlayY", 140)
        set(v) { sp.edit().putInt("overlayY", v).apply() }

    var modoRapido: Boolean
        get() = sp.getBoolean("rapido", true)
        set(v) { sp.edit().putBoolean("rapido", v).apply() }

    var hilos: Int
        get() = sp.getInt("hilos", Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        set(v) { sp.edit().putInt("hilos", v).apply() }
}
