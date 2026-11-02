package com.elisotopo.subtilingus

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

// Pestaña "Ajustes": modelos de voz, rendimiento, subtítulos flotantes,
// almacenamiento (qué ocupa cada cosa y cómo borrarla) y registro.
class PantallaAjustes(private val a: MainActivity) : Pantalla() {

    private class Fila(
        val m: ModeloInfo,
        val tarjeta: LinearLayout,
        val estado: TextView,
        val accion: TextView,
        val borrar: TextView
    ) {
        var ultimoEstilo = -1
    }

    private val filas = ArrayList<Fila>()
    private val almacenamiento = a.texto("", 13f, Tema.TEXTO)
    private val chipsHilos = Chips(a, listOf("2", "3", "4", "6")) { i ->
        a.ajustes.hilos = hilosOpciones[i]
    }
    private val chipsTamano = Chips(a, listOf("Pequeño", "Medio", "Grande")) { i ->
        a.ajustes.tamanoTexto = i
    }
    private val hilosOpciones = listOf(2, 3, 4, 6)

    init {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(24))

        col.addView(a.texto("Ajustes", 26f, Tema.TEXTO, true), a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 14))

        // ---- modelos ----
        col.addView(a.seccion("Modelo de voz (Whisper)"))
        col.addView(a.texto("Es el «cerebro» que convierte voz en texto. Se descarga una vez y funciona sin internet. " +
                "Toca uno para usarlo.", 12f, Tema.TEXTO2), a.lpd(MATCH, WRAP, 0f, 0, 2, 0, 8))
        for (m in Modelos.lista) {
            val t = a.tarjeta()
            val fila = LinearLayout(a)
            fila.orientation = LinearLayout.HORIZONTAL
            fila.gravity = Gravity.CENTER_VERTICAL
            val textos = LinearLayout(a)
            textos.orientation = LinearLayout.VERTICAL
            textos.addView(a.texto(m.nombre, 14f, Tema.TEXTO, true))
            textos.addView(a.texto(m.detalle, 12f, Tema.TEXTO2))
            val est = a.texto("", 12f, Tema.ACENTO)
            textos.addView(est, a.lpd(MATCH, WRAP, 0f, 0, 2))
            fila.addView(textos, LinearLayout.LayoutParams(0, WRAP, 1f))
            val borrar = a.botonSuave("🗑", Tema.PELIGRO) { borrarModelo(m) }
            val accion = a.botonSuave("") { pulsarAccion(m) }
            fila.addView(accion, a.lpd(WRAP, WRAP, 0f, 8))
            fila.addView(borrar, a.lpd(WRAP, WRAP, 0f, 6))
            t.addView(fila)
            t.setOnClickListener { elegir(m) }
            col.addView(t, a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 8))
            filas.add(Fila(m, t, est, accion, borrar))
        }

        // ---- rendimiento ----
        col.addView(a.seccion("Rendimiento"), a.lpd(WRAP, WRAP, 0f, 0, 14, 0, 0))
        val rapido = Switch(a)
        rapido.text = "Modo rápido"
        rapido.setTextColor(Tema.TEXTO)
        rapido.isChecked = a.ajustes.modoRapido
        rapido.setOnCheckedChangeListener { _, on -> a.ajustes.modoRapido = on }
        col.addView(rapido, a.lpd(MATCH, WRAP, 0f, 0, 6))
        col.addView(a.texto("Recorta el trabajo de Whisper en frases cortas: transcribe 2-3 veces más rápido. " +
                "Si notas que falla más, desactívalo.", 12f, Tema.TEXTO2), a.lpd(MATCH, WRAP, 0f, 0, 2, 0, 10))
        col.addView(a.texto("Hilos de CPU", 13f, Tema.TEXTO, true))
        col.addView(chipsHilos.vista, a.lpd(MATCH, WRAP, 0f, 0, 6, 0, 4))
        col.addView(a.texto("Más hilos = más rápido, pero más calor y batería. 4 suele ir bien.", 12f, Tema.TEXTO2))

        // ---- subtítulos flotantes ----
        col.addView(a.seccion("Subtítulos flotantes"), a.lpd(WRAP, WRAP, 0f, 0, 16, 0, 6))
        col.addView(chipsTamano.vista)
        col.addView(a.texto("En pantalla puedes arrastrarlos con el dedo, cambiar el tamaño con «Aa» y ocultarlos con ✕.",
            12f, Tema.TEXTO2), a.lpd(MATCH, WRAP, 0f, 0, 6))

        // ---- almacenamiento ----
        col.addView(a.seccion("Almacenamiento"), a.lpd(WRAP, WRAP, 0f, 0, 16, 0, 6))
        val caja = a.tarjeta()
        caja.addView(almacenamiento)
        col.addView(caja)
        val botones = LinearLayout(a)
        botones.orientation = LinearLayout.HORIZONTAL
        botones.addView(a.botonSuave("Borrar todos los textos", Tema.PELIGRO) {
            a.confirmar("¿Borrar todos los textos?", "Se eliminarán todas las transcripciones guardadas. El vocabulario no se toca.", "Borrar todo") {
                Sesiones.borrarTodos(a)
                actualizarAlmacenamiento()
                a.toast("Textos borrados")
            }
        }, a.lpd(0, WRAP, 1f))
        botones.addView(a.botonSuave("Ver registro") { verRegistro() }, a.lpd(0, WRAP, 1f, 8))
        col.addView(botones, a.lpd(MATCH, WRAP, 0f, 0, 8))
        col.addView(a.botonSuave("⏱ Prueba de velocidad") { pruebaVelocidad() }, a.lpd(MATCH, WRAP, 0f, 0, 8))

        col.addView(a.texto("Subtilingus v0.7.1\nTodo el audio se procesa en tu móvil. Solo se usa internet para descargar modelos y para consultar el diccionario al tocar una palabra.",
            12f, Tema.TEXTO2), a.lpd(MATCH, WRAP, 0f, 0, 18))

        val sv = ScrollView(a)
        sv.addView(col)
        vista = sv
    }

    override fun alMostrar() {
        chipsTamano.marcar(a.ajustes.tamanoTexto.coerceIn(0, 2))
        chipsHilos.marcar(hilosOpciones.indexOf(a.ajustes.hilos).coerceAtLeast(0))
        actualizarAlmacenamiento()
    }

    private fun elegir(m: ModeloInfo) {
        if (!Modelos.descargado(a, m)) {
            a.toast("Primero descarga este modelo.")
            return
        }
        a.ajustes.modeloId = m.id
        if (m.soloIngles && a.ajustes.idioma == "es") {
            a.toast("Ojo: este modelo solo entiende inglés.")
        }
    }

    private fun pulsarAccion(m: ModeloInfo) {
        when {
            Descargas.activa == m.id -> Descargas.cancelar()
            Descargas.activa != null -> a.toast("Ya hay otra descarga en curso.")
            !Modelos.descargado(a, m) ->
                Descargas.descargar(a, m) { actualizarAlmacenamiento() }
            else -> elegir(m)
        }
    }

    private fun borrarModelo(m: ModeloInfo) {
        if ((Estado.corriendo || Estado.cargando) && a.ajustes.modeloId == m.id) {
            a.toast("Detén la escucha antes de borrar el modelo en uso.")
            return
        }
        a.confirmar("¿Borrar «" + m.nombre + "»?", "Liberará unos " + m.mbAprox + " MB. Podrás descargarlo otra vez.", "Borrar") {
            Modelos.archivo(a, m).delete()
            actualizarAlmacenamiento()
        }
    }

    private fun tam(bytes: Long): String = when {
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1000.0)
        else -> bytes.toString() + " B"
    }

    private fun actualizarAlmacenamiento() {
        var modelos = 0L
        var cuantosModelos = 0
        for (f in Carpetas.modelos(a).listFiles() ?: emptyArray()) {
            modelos += f.length()
            cuantosModelos++
        }
        val textos = Sesiones.tamanoTextos(a)
        val sb = StringBuilder()
        sb.append("Carpeta de la app:\n").append(Carpetas.base(a).absolutePath).append("\n\n")
        sb.append("Modelos: ").append(tam(modelos)).append(" (").append(cuantosModelos).append(")\n")
        sb.append("Textos: ").append(tam(textos)).append(" (").append(Sesiones.cuantos(a)).append(" archivos)\n")
        sb.append("Vocabulario: ").append(Vocabulario.leer(a).size).append(" palabras")
        almacenamiento.text = sb.toString()
    }

    override fun refrescar() {
        for (f in filas) {
            val desc = Modelos.descargado(a, f.m)
            val bajando = Descargas.activa == f.m.id
            val sel = a.ajustes.modeloId == f.m.id && desc
            f.estado.text = when {
                bajando -> "Descargando… " + Descargas.progreso + "%"
                desc -> "Descargado · " + tam(Modelos.archivo(a, f.m).length())
                else -> "Sin descargar · " + f.m.mbAprox + " MB"
            }
            f.accion.text = when {
                bajando -> "Cancelar"
                !desc -> "Descargar"
                sel -> "En uso ✓"
                else -> "Usar"
            }
            f.borrar.visibility = if (desc && !bajando) View.VISIBLE else View.GONE
            val estilo = if (sel) 1 else 0
            if (estilo != f.ultimoEstilo) {
                f.ultimoEstilo = estilo
                f.tarjeta.background = conRipple(a.fondo(if (sel) Tema.TARJETA_SEL else Tema.TARJETA, 16,
                    if (sel) Tema.ACENTO else Tema.TARJETA, 1))
            }
        }
        if (Descargas.activa == null && Descargas.error.isNotEmpty() && almacenamiento.text.isNotEmpty()) {
            // el error se muestra en la pestaña Escuchar; aquí basta con refrescar tamaños
        }
    }

    private fun pruebaVelocidad() {
        if (Estado.corriendo || Estado.cargando) {
            a.toast("Detén la escucha antes de la prueba.")
            return
        }
        a.toast("Midiendo… tarda unos segundos.")
        Prueba.ejecutar(a) { resultado ->
            AlertDialog.Builder(a)
                .setTitle("Prueba de velocidad")
                .setMessage(resultado + "\nReferencia: lo normal es menos de 3 s para 10 s de audio.")
                .setPositiveButton("Compartir") { _, _ -> a.compartir(resultado, "Compartir prueba") }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun verRegistro() {
        val tv = TextView(a)
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 11f
        tv.setTextColor(Tema.TEXTO)
        tv.setTextIsSelectable(true)
        val t = Registro.texto()
        tv.text = if (t.isEmpty()) "(vacío)" else t
        tv.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12))
        val sv = ScrollView(a)
        sv.addView(tv)
        AlertDialog.Builder(a)
            .setTitle("Registro de diagnóstico")
            .setView(sv)
            .setPositiveButton("Compartir") { _, _ -> a.compartir(Registro.texto(), "Compartir registro") }
            .setNeutralButton("Limpiar") { _, _ -> Registro.limpiar() }
            .setNegativeButton("Cerrar", null)
            .show()
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }
}
