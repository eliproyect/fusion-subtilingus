package com.elisotopo.subtilingus

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

// Pestaña "Textos": lista de sesiones guardadas, visor con palabras
// tocables (diccionario + traducción) y vocabulario guardado.
class PantallaTextos(private val a: MainActivity) : Pantalla() {

    private val marco = FrameLayout(a)
    private var abierta: DatosSesion? = null
    private var enVocabulario = false

    init {
        vista = marco
    }

    override fun alMostrar() {
        val d = abierta
        if (d != null && d.archivo.exists()) return
        abierta = null
        if (enVocabulario) mostrarVocabulario() else mostrarLista()
    }

    // Devuelve true si "atrás" se ha gastado aquí dentro
    fun atras(): Boolean {
        if (abierta != null || enVocabulario) {
            abierta = null
            enVocabulario = false
            mostrarLista()
            return true
        }
        return false
    }

    private fun columna(): LinearLayout {
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(8))
        return col
    }

    // ---------------- lista ----------------

    private fun mostrarLista() {
        enVocabulario = false
        abierta = null
        marco.removeAllViews()
        val col = columna()

        val cab = LinearLayout(a)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        cab.addView(a.texto("Textos", 26f, Tema.TEXTO, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        cab.addView(a.botonSuave("📖 Vocabulario") { mostrarVocabulario() })
        col.addView(cab, a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 12))

        val lista = Sesiones.lista(a)
        if (lista.isEmpty()) {
            col.addView(a.texto("Aún no hay textos.\n\nCada vez que escuches algo, la transcripción se guarda aquí automáticamente, con la hora de cada frase. Podrás tocar cualquier palabra para ver su significado y guardarla.",
                15f, Tema.TEXTO2))
        } else {
            val sv = ScrollView(a)
            val items = LinearLayout(a)
            items.orientation = LinearLayout.VERTICAL
            for (r in lista) {
                val t = a.tarjeta()
                t.addView(a.texto(r.titulo, 16f, Tema.TEXTO, true))
                t.addView(a.texto(r.meta, 12f, Tema.ACENTO))
                val v = a.texto(r.vista, 13f, Tema.TEXTO2)
                v.maxLines = 2
                v.ellipsize = android.text.TextUtils.TruncateAt.END
                t.addView(v, a.lpd(MATCH, WRAP, 0f, 0, 4))
                t.isClickable = true
                t.setOnClickListener { mostrarSesion(r.archivo) }
                items.addView(t, a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 10))
            }
            sv.addView(items)
            col.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        marco.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------------- visor de una sesión ----------------

    private fun mostrarSesion(archivo: java.io.File) {
        enVocabulario = false
        val d = Sesiones.leer(archivo)
        abierta = d
        marco.removeAllViews()
        val col = columna()

        val cab = LinearLayout(a)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        cab.addView(a.botonSuave("←") { atras() })
        cab.addView(a.texto(Sesiones.titulo(d), 17f, Tema.TEXTO, true), a.lpd(0, WRAP, 1f, 12))
        col.addView(cab, a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 10))

        val acciones = LinearLayout(a)
        acciones.orientation = LinearLayout.HORIZONTAL
        acciones.addView(a.botonSuave("Compartir") {
            a.compartir(Sesiones.textoPlano(d.lineas, false), "Compartir texto")
        }, a.lpd(0, WRAP, 1f))
        acciones.addView(a.botonSuave("Guardar como…") { dialogoExportar(d) }, a.lpd(0, WRAP, 1f, 8))
        acciones.addView(a.botonSuave("Borrar", Tema.PELIGRO) {
            a.confirmar("¿Borrar este texto?", "Se eliminará del móvil. No se puede deshacer.", "Borrar") {
                d.archivo.delete()
                atras()
            }
        }, a.lpd(0, WRAP, 1f, 8))
        col.addView(acciones, a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 8))

        col.addView(a.texto("Toca una palabra para ver su significado y traducción.", 12f, Tema.TEXTO2),
            a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 8))

        // Texto con marcas de tiempo en gris; guardamos dónde empieza y acaba cada frase
        val sb = SpannableStringBuilder()
        val rangos = ArrayList<IntArray>()
        for (l in d.lineas) {
            val iniT = sb.length
            sb.append(Sesiones.mmss(l.ms)).append("  ")
            sb.setSpan(ForegroundColorSpan(Tema.TEXTO2), iniT, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val ini = sb.length
            sb.append(l.texto)
            rangos.add(intArrayOf(ini, sb.length))
            sb.append("\n\n")
        }
        val visor = a.texto(sb, 18f, Tema.TEXTO)
        visor.setLineSpacing(0f, 1.15f)
        val detector = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val lay = visor.layout ?: return false
                val x = e.x - visor.totalPaddingLeft + visor.scrollX
                val y = e.y - visor.totalPaddingTop + visor.scrollY
                val linea = lay.getLineForVertical(y.toInt())
                val off = lay.getOffsetForHorizontal(linea, x)
                for (r in rangos) {
                    if (off >= r[0] - 1 && off <= r[1]) {
                        val palabra = palabraEn(sb, off, r[0], r[1])
                        if (palabra != null) {
                            DialogoPalabra.mostrar(a, palabra, sb.substring(r[0], r[1]), d.idioma)
                        }
                        return true
                    }
                }
                return false
            }
        })
        visor.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            true
        }
        val sv = ScrollView(a)
        sv.addView(visor)
        col.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        marco.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun esLetra(c: Char): Boolean = Character.isLetter(c) || c == '\'' || c == '’'

    private fun palabraEn(t: CharSequence, off: Int, ini: Int, fin: Int): String? {
        var o = off.coerceIn(ini, fin - 1)
        if (!esLetra(t[o])) {
            if (o - 1 >= ini && esLetra(t[o - 1])) o-- else return null
        }
        var i = o
        while (i > ini && esLetra(t[i - 1])) i--
        var j = o
        while (j < fin - 1 && esLetra(t[j + 1])) j++
        val p = t.subSequence(i, j + 1).toString().trim('\'', '’')
        return if (p.isEmpty()) null else p
    }

    private fun dialogoExportar(d: DatosSesion) {
        val base = d.archivo.nameWithoutExtension
        val opciones = arrayOf("Texto con horas (.txt)", "Texto limpio (.txt)", "Subtítulos (.srt)")
        AlertDialog.Builder(a)
            .setTitle("Guardar como…")
            .setItems(opciones) { _, cual ->
                when (cual) {
                    0 -> a.exportar(base + ".txt", "text/plain", Sesiones.textoPlano(d.lineas, true))
                    1 -> a.exportar(base + ".txt", "text/plain", Sesiones.textoPlano(d.lineas, false))
                    else -> a.exportar(base + ".srt", "application/octet-stream", Sesiones.srt(d.lineas))
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- vocabulario ----------------

    private fun mostrarVocabulario() {
        enVocabulario = true
        abierta = null
        marco.removeAllViews()
        val col = columna()
        val cab = LinearLayout(a)
        cab.orientation = LinearLayout.HORIZONTAL
        cab.gravity = Gravity.CENTER_VERTICAL
        cab.addView(a.botonSuave("←") { atras() })
        cab.addView(a.texto("Vocabulario", 22f, Tema.TEXTO, true), a.lpd(0, WRAP, 1f, 12))
        col.addView(cab, a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 10))

        val lista = Vocabulario.leer(a)
        if (lista.isEmpty()) {
            col.addView(a.texto("Aquí aparecerán las palabras que guardes al tocarlas en un texto.", 15f, Tema.TEXTO2))
        } else {
            val acciones = LinearLayout(a)
            acciones.orientation = LinearLayout.HORIZONTAL
            acciones.addView(a.botonSuave("Compartir") {
                val sb = StringBuilder()
                for (e in lista) sb.append(e.palabra).append(" — ").append(e.traduccion).append("\n   ").append(e.frase).append("\n\n")
                a.compartir(sb.toString(), "Compartir vocabulario")
            }, a.lpd(0, WRAP, 1f))
            acciones.addView(a.botonSuave("Borrar todo", Tema.PELIGRO) {
                a.confirmar("¿Borrar todo el vocabulario?", "No se puede deshacer.", "Borrar") {
                    Vocabulario.borrarTodo(a)
                    mostrarVocabulario()
                }
            }, a.lpd(0, WRAP, 1f, 8))
            col.addView(acciones, a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 10))

            val sv = ScrollView(a)
            val items = LinearLayout(a)
            items.orientation = LinearLayout.VERTICAL
            for ((i, e) in lista.withIndex()) {
                val t = a.tarjeta()
                t.addView(a.texto(e.palabra + "  →  " + e.traduccion, 16f, Tema.TEXTO, true))
                t.addView(a.texto("“" + e.frase + "”", 12f, Tema.TEXTO2), a.lpd(MATCH, WRAP, 0f, 0, 4))
                t.setOnLongClickListener {
                    a.confirmar("¿Quitar «" + e.palabra + "»?", "", "Quitar") {
                        Vocabulario.quitar(a, i)
                        mostrarVocabulario()
                    }
                    true
                }
                items.addView(t, a.lpd(MATCH, WRAP, 0f, 0, 0, 0, 10))
            }
            sv.addView(items)
            col.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
            col.addView(a.texto("Mantén pulsada una palabra para quitarla.", 12f, Tema.TEXTO2), a.lpd(MATCH, WRAP, 0f, 0, 6))
        }
        marco.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
    }
}

// Ventana con significado, traducción y ejemplos de la palabra tocada
object DialogoPalabra {
    fun mostrar(a: MainActivity, palabra: String, frase: String, idiomaSesion: String) {
        val lang = if (idiomaSesion == "es") "es" else "en"
        val destino = if (lang == "es") "en" else "es"

        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(a.dp(22), a.dp(18), a.dp(22), a.dp(6))
        val tTrad = a.texto("Traduciendo…", 18f, Tema.ACENTO, true)
        val tDef = a.texto("Buscando definición…", 14f, Tema.TEXTO)
        val tFraseTrad = a.texto("", 13f, Tema.TEXTO2)
        col.addView(a.texto(palabra, 24f, Tema.TEXTO, true))
        col.addView(tTrad, a.lpd(MATCH, WRAP, 0f, 0, 4, 0, 10))
        col.addView(tDef)
        col.addView(a.texto("En la frase:", 12f, Tema.TEXTO2, true), a.lpd(MATCH, WRAP, 0f, 0, 14, 0, 2))
        col.addView(a.texto("“" + frase + "”", 13f, Tema.TEXTO))
        col.addView(tFraseTrad, a.lpd(MATCH, WRAP, 0f, 0, 6))
        col.addView(a.texto("Consulta online: se envía solo esta palabra y frase.", 11f, Tema.TEXTO2), a.lpd(MATCH, WRAP, 0f, 0, 12))
        val sv = ScrollView(a)
        sv.addView(col)

        AlertDialog.Builder(a)
            .setView(sv)
            .setPositiveButton("Guardar palabra") { _, _ ->
                val tr = tTrad.text.toString()
                val limpia = if (tr.endsWith("…") || tr.startsWith("(")) "" else tr
                Vocabulario.anadir(a, palabra, limpia, frase)
                a.toast("Guardada en Vocabulario")
            }
            .setNegativeButton("Cerrar", null)
            .show()

        thread(name = "dic1") {
            val r = Diccionario.traducir(palabra, lang, destino)
            a.runOnUiThread { tTrad.text = r ?: "(sin traducción: ¿hay internet?)" }
        }
        thread(name = "dic2") {
            val r = Diccionario.definicion(palabra, lang)
            a.runOnUiThread { tDef.text = r ?: "(sin definición en el diccionario)" }
        }
        thread(name = "dic3") {
            val r = Diccionario.traducir(frase, lang, destino)
            a.runOnUiThread { tFraseTrad.text = if (r != null) "→ " + r else "" }
        }
    }
}
