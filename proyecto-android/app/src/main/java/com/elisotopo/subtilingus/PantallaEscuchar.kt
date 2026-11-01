package com.elisotopo.subtilingus

import android.graphics.Color
import android.graphics.Typeface
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

// Pestaña "Escuchar": elegir fuente e idioma, empezar y ver los subtítulos en directo.
class PantallaEscuchar(private val a: MainActivity) : Pantalla() {

    private val idiomas = listOf("en", "es", "auto")
    private val estado = a.texto("", 14f, Tema.TEXTO2)
    private val barra = BarraNivel(a)
    private val tarjetaMovil = a.tarjeta()
    private val tarjetaMic = a.tarjeta()
    private val chipsIdioma = Chips(a, listOf("English", "Español", "Auto")) { i -> elegirIdioma(i) }
    private val interruptor = Switch(a)
    private val botonPrincipal = a.boton("Empezar a escuchar") { a.alternarEscucha() }
    private val transcripcion = a.texto("", 17f, Tema.TEXTO)
    private val scroll = ScrollView(a)
    private val info = a.texto("", 12f, Tema.TEXTO2)

    private var versionVista = -1
    private var ultimoCorriendo: Boolean? = null
    private var ultimaFuente = ""
    private var silenciar = false

    init {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(8))

        col.addView(a.texto("Subtilingus", 26f, Tema.TEXTO, true))
        col.addView(estado, a.lpd(MATCH, WRAP, 0f, 0, 2, 0, 12))

        col.addView(a.seccion("¿Qué quieres escuchar?"))
        val fila = LinearLayout(a)
        fila.orientation = LinearLayout.HORIZONTAL
        tarjetaMovil.addView(a.texto("📱 Audio del móvil", 15f, Tema.TEXTO, true))
        tarjetaMovil.addView(a.texto("BBC, Spotify, YouTube… lo que suena en el móvil", 12f, Tema.TEXTO2))
        tarjetaMic.addView(a.texto("🎤 Micrófono", 15f, Tema.TEXTO, true))
        tarjetaMic.addView(a.texto("La TV, una clase, una conversación", 12f, Tema.TEXTO2))
        tarjetaMovil.setOnClickListener { elegirFuente(Ajustes.FUENTE_INTERNO) }
        tarjetaMic.setOnClickListener { elegirFuente(Ajustes.FUENTE_MIC) }
        fila.addView(tarjetaMovil, a.lpd(0, MATCH, 1f))
        fila.addView(tarjetaMic, a.lpd(0, MATCH, 1f, 8))
        col.addView(fila, a.lpd(MATCH, WRAP, 0f, 0, 6, 0, 12))

        col.addView(a.seccion("Idioma del audio"))
        col.addView(chipsIdioma.vista, a.lpd(MATCH, WRAP, 0f, 0, 6, 0, 8))

        interruptor.text = "Subtítulos flotantes sobre otras apps"
        interruptor.setTextColor(Tema.TEXTO)
        interruptor.setOnCheckedChangeListener { _, on ->
            if (!silenciar) {
                a.ajustes.overlay = on
                TranscriberService.instancia?.ponerOverlay(on)
            }
        }
        col.addView(interruptor, a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 10))

        col.addView(botonPrincipal, a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 10))
        col.addView(barra, a.lpd(MATCH, 8, 0f, 0, 0, 0, 10))

        val caja = a.tarjeta()
        scroll.addView(transcripcion)
        caja.addView(scroll, LinearLayout.LayoutParams(MATCH, MATCH))
        col.addView(caja, a.lpd(MATCH, 0, 1f))

        col.addView(info, a.lpd(MATCH, WRAP, 0f, 0, 8, 0, 0))
        vista = col
    }

    private fun elegirFuente(f: String) {
        if (Estado.corriendo || Estado.cargando) {
            a.toast("Detén la escucha para cambiar de fuente.")
            return
        }
        a.ajustes.fuente = f
    }

    private fun elegirIdioma(i: Int) {
        if (Estado.corriendo || Estado.cargando) {
            a.toast("Detén la escucha para cambiar de idioma.")
            return
        }
        a.ajustes.idioma = idiomas[i]
    }

    private fun estiloTarjeta(t: LinearLayout, sel: Boolean) {
        t.background = conRipple(a.fondo(if (sel) Tema.TARJETA_SEL else Tema.TARJETA, 16,
            if (sel) Tema.ACENTO else Tema.BORDE, 1))
    }

    override fun refrescar() {
        val ocupado = Estado.corriendo || Estado.cargando
        val fuente = a.ajustes.fuente
        if (fuente != ultimaFuente) {
            ultimaFuente = fuente
            estiloTarjeta(tarjetaMovil, fuente == Ajustes.FUENTE_INTERNO)
            estiloTarjeta(tarjetaMic, fuente == Ajustes.FUENTE_MIC)
            versionVista = -1
        }
        chipsIdioma.marcar(idiomas.indexOf(a.ajustes.idioma).coerceAtLeast(0))

        silenciar = true
        if (interruptor.isChecked != a.ajustes.overlay) interruptor.isChecked = a.ajustes.overlay
        silenciar = false

        if (ultimoCorriendo != ocupado) {
            ultimoCorriendo = ocupado
            botonPrincipal.text = if (ocupado) "Detener" else "Empezar a escuchar"
            botonPrincipal.background = conRipple(a.fondo(if (ocupado) Tema.PELIGRO else Tema.ACENTO, 14))
        }

        val d = Descargas.activa
        estado.text = if (d != null) "Descargando modelo… " + Descargas.progreso + "%" else Estado.mensaje
        barra.valor = if (Estado.corriendo) ((Estado.nivelDb + 70.0) / 70.0).toFloat() else 0f

        if (Estado.version != versionVista) {
            versionVista = Estado.version
            pintar()
        }

        val sb = StringBuilder()
        if (Estado.avisoAudio.isNotEmpty()) sb.append("⚠ ").append(Estado.avisoAudio).append('\n')
        if (Estado.avisoRendimiento.isNotEmpty()) sb.append("⚠ ").append(Estado.avisoRendimiento).append('\n')
        if (Descargas.error.isNotEmpty()) sb.append("⚠ Descarga: ").append(Descargas.error).append('\n')
        if (Estado.velocidad.isNotEmpty() && ocupado) sb.append(Estado.velocidad)
        info.text = sb.toString().trim()
        info.setTextColor(if (Estado.avisoAudio.isNotEmpty() || Estado.avisoRendimiento.isNotEmpty()) Tema.AVISO else Tema.TEXTO2)
    }

    private fun pintar() {
        val sb = SpannableStringBuilder()
        synchronized(Estado.lineas) {
            for (l in Estado.lineas) sb.append(l).append("\n\n")
        }
        val prov = Estado.provisional
        if (prov.isNotEmpty()) {
            val ini = sb.length
            sb.append(prov)
            sb.setSpan(ForegroundColorSpan(Tema.TEXTO2), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (sb.isEmpty()) {
            val ayuda = if (a.ajustes.fuente == Ajustes.FUENTE_INTERNO) {
                "Aquí verás los subtítulos en directo.\n\n1. Pulsa «Empezar a escuchar».\n2. Android te pedirá permiso para capturar audio: acepta.\n3. Abre BBC, Spotify o YouTube y dale al play: los subtítulos flotarán encima.\n\nTodo se guarda solo en la pestaña Textos."
            } else {
                "Aquí verás los subtítulos en directo.\n\n1. Pulsa «Empezar a escuchar».\n2. Acerca el móvil a la TV, al altavoz o a quien habla.\n\nTodo se guarda solo en la pestaña Textos."
            }
            transcripcion.text = ayuda
            transcripcion.setTextColor(Tema.TEXTO2)
            transcripcion.textSize = 15f
        } else {
            transcripcion.text = sb
            transcripcion.setTextColor(Tema.TEXTO)
            transcripcion.textSize = 17f
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
