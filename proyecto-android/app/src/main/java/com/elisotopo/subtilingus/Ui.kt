package com.elisotopo.subtilingus

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

// Pequeña "caja de herramientas" para construir las pantallas por código.
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Tema {
    val FONDO = 0xFF0E1116.toInt()
    val TARJETA = 0xFF181C24.toInt()
    val TARJETA_SEL = 0xFF1F3158.toInt()
    val BORDE = 0xFF2A303C.toInt()
    val ACENTO = 0xFF5B8CFF.toInt()
    val TEXTO = 0xFFEDEFF3.toInt()
    val TEXTO2 = 0xFF9AA3B2.toInt()
    val OK = 0xFF3DD6A0.toInt()
    val PELIGRO = 0xFFFF6B6B.toInt()
    val AVISO = 0xFFFFC857.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.fondo(color: Int, radioDp: Int = 14, bordeColor: Int = 0, bordeDp: Int = 0): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radioDp).toFloat()
    if (bordeDp > 0) g.setStroke(dp(bordeDp), bordeColor)
    return g
}

fun conRipple(base: GradientDrawable): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)

fun Context.texto(t: CharSequence, sp: Float = 15f, color: Int = Tema.TEXTO, negrita: Boolean = false): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = sp
    v.setTextColor(color)
    if (negrita) v.typeface = Typeface.DEFAULT_BOLD
    return v
}

fun Context.boton(t: String, fondoColor: Int = Tema.ACENTO, colorTexto: Int = Color.WHITE, alClick: () -> Unit): TextView {
    val v = texto(t, 15f, colorTexto, true)
    v.gravity = Gravity.CENTER
    v.setPadding(dp(16), dp(12), dp(16), dp(12))
    v.background = conRipple(fondo(fondoColor, 14))
    v.isClickable = true
    v.setOnClickListener { alClick() }
    return v
}

// Botón pequeño con borde (acciones secundarias)
fun Context.botonSuave(t: String, color: Int = Tema.TEXTO, alClick: () -> Unit): TextView {
    val v = texto(t, 13f, color, true)
    v.gravity = Gravity.CENTER
    v.setPadding(dp(12), dp(8), dp(12), dp(8))
    v.background = conRipple(fondo(Tema.TARJETA, 12, Tema.BORDE, 1))
    v.isClickable = true
    v.setOnClickListener { alClick() }
    return v
}

fun Context.tarjeta(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    l.setPadding(dp(14), dp(12), dp(14), dp(12))
    l.background = fondo(Tema.TARJETA, 16)
    return l
}

fun Context.seccion(t: String): TextView {
    val v = texto(t.uppercase(), 12f, Tema.TEXTO2, true)
    v.letterSpacing = 0.08f
    return v
}

fun lp(w: Int, h: Int, peso: Float = 0f, izq: Int = 0, arr: Int = 0, der: Int = 0, aba: Int = 0): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, peso)
    p.setMargins(izq, arr, der, aba)
    return p
}

// Igual que lp pero con los márgenes en dp
fun Context.lpd(w: Int, h: Int, peso: Float = 0f, izq: Int = 0, arr: Int = 0, der: Int = 0, aba: Int = 0): LinearLayout.LayoutParams =
    lp(w, h, peso, dp(izq), dp(arr), dp(der), dp(aba))

// Barra del nivel de sonido
class BarraNivel(ctx: Context) : View(ctx) {
    private val pista = Paint(Paint.ANTI_ALIAS_FLAG)
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    var valor: Float = 0f
        set(v) {
            val nuevo = v.coerceIn(0f, 1f)
            if (nuevo != field) {
                field = nuevo
                invalidate()
            }
        }

    init {
        pista.color = Tema.BORDE
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val rad = h / 2f
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, rad, rad, pista)
        if (valor > 0.02f) {
            relleno.color = if (valor > 0.85f) Tema.AVISO else Tema.OK
            r.set(0f, 0f, width * valor, h)
            canvas.drawRoundRect(r, rad, rad, relleno)
        }
    }
}

// Fila de opciones excluyentes (como botones de radio, pero bonitos)
class Chips(private val ctx: Context, etiquetas: List<String>, private val alElegir: (Int) -> Unit) {
    val vista = LinearLayout(ctx)
    private val items = ArrayList<TextView>()
    private var actual = -2

    init {
        vista.orientation = LinearLayout.HORIZONTAL
        for ((i, e) in etiquetas.withIndex()) {
            val tv = ctx.texto(e, 14f, Tema.TEXTO, true)
            tv.gravity = Gravity.CENTER
            tv.setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(10))
            tv.setOnClickListener { alElegir(i) }
            vista.addView(tv, ctx.lpd(0, WRAP, 1f, if (i == 0) 0 else 6))
            items.add(tv)
        }
    }

    fun marcar(i: Int) {
        if (i == actual) return
        actual = i
        for ((k, tv) in items.withIndex()) {
            val sel = k == i
            tv.background = conRipple(ctx.fondo(if (sel) Tema.TARJETA_SEL else Tema.TARJETA, 12,
                if (sel) Tema.ACENTO else Tema.BORDE, 1))
            tv.setTextColor(if (sel) Tema.TEXTO else Tema.TEXTO2)
        }
    }
}

// Cada pantalla de la app (Escuchar, Textos, Ajustes)
abstract class Pantalla {
    lateinit var vista: View
    open fun alMostrar() {}
    open fun refrescar() {}
}
