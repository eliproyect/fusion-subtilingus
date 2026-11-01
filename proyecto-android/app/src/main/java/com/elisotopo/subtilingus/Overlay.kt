package com.elisotopo.subtilingus

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// ============================================================
// Subtítulos flotantes sobre cualquier app (permiso "Mostrar sobre
// otras apps"). Se arrastra con el dedo; "Aa" cambia el tamaño; "✕" lo
// oculta (la transcripción sigue). Se apaga solo tras 9 s sin voz.
// Todos los métodos se llaman desde el hilo principal.
// ============================================================
class Overlay(private val ctx: Context, private val ajustes: Ajustes) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val tamanos = floatArrayOf(16f, 21f, 27f)

    private var raiz: FrameLayout? = null
    private var caja: LinearLayout? = null
    private var texto: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var puesta = false
    private var visible = false
    private val ocultar = Runnable { fijarVisible(false) }

    fun mostrarVentana() {
        if (puesta) return
        val c = ctx
        val raizV = FrameLayout(c)
        raizV.setPadding(c.dp(8), 0, c.dp(8), 0)

        val cajaV = LinearLayout(c)
        cajaV.orientation = LinearLayout.HORIZONTAL
        cajaV.gravity = Gravity.CENTER_VERTICAL
        cajaV.setPadding(c.dp(14), c.dp(10), c.dp(6), c.dp(10))
        cajaV.background = c.fondo(0xD9000000.toInt(), 16)
        cajaV.visibility = View.INVISIBLE

        val textoV = TextView(c)
        textoV.setTextColor(Color.WHITE)
        textoV.textSize = tamanos[ajustes.tamanoTexto.coerceIn(0, 2)]
        textoV.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        cajaV.addView(textoV, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val botones = LinearLayout(c)
        botones.orientation = LinearLayout.VERTICAL
        val bTam = c.texto("Aa", 14f, Color.LTGRAY, true)
        bTam.setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6))
        bTam.setOnClickListener {
            val nuevo = (ajustes.tamanoTexto + 1) % 3
            ajustes.tamanoTexto = nuevo
            textoV.textSize = tamanos[nuevo]
        }
        val bCerrar = c.texto("✕", 16f, Color.LTGRAY, true)
        bCerrar.setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6))
        bCerrar.setOnClickListener { fijarVisible(false) }
        botones.addView(bTam)
        botones.addView(bCerrar)
        cajaV.addView(botones, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        raizV.addView(cajaV, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        p.y = c.dp(ajustes.overlayY)

        // Arrastrar con el dedo (gravedad abajo: subir el dedo = y mayor)
        var downY = 0f
        var inicioY = 0
        cajaV.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    inicioY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val tope = c.resources.displayMetrics.heightPixels - c.dp(120)
                    p.y = (inicioY + (downY - ev.rawY)).toInt().coerceIn(0, tope)
                    try {
                        wm.updateViewLayout(raizV, p)
                    } catch (e: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    ajustes.overlayY = (p.y / c.resources.displayMetrics.density).toInt()
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(raizV, p)
            raiz = raizV
            caja = cajaV
            texto = textoV
            params = p
            puesta = true
            visible = false
        } catch (e: Exception) {
            Registro.e("OVERLAY", "No se pudo mostrar la ventana flotante: " + e)
        }
    }

    // Mientras está oculta, la ventana no captura toques (los deja pasar a la app de debajo)
    private fun fijarVisible(v: Boolean) {
        val p = params ?: return
        val r = raiz ?: return
        val cj = caja ?: return
        if (v == visible) return
        visible = v
        cj.visibility = if (v) View.VISIBLE else View.INVISIBLE
        p.flags = if (v) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            wm.updateViewLayout(r, p)
        } catch (e: Exception) {
        }
    }

    private fun cola(s: String, max: Int): String {
        if (s.length <= max) return s
        val t = s.takeLast(max)
        val i = t.indexOf(' ')
        return "…" + (if (i in 0..20) t.substring(i + 1) else t)
    }

    // anterior = frase previa (gris); actual = frase en curso o última (blanca)
    fun actualizar(anterior: String, actual: String) {
        val tv = texto ?: return
        val a = cola(anterior.trim(), 90)
        val b = cola(actual.trim(), 170)
        if (a.isEmpty() && b.isEmpty()) return
        val sb = SpannableStringBuilder()
        if (a.isNotEmpty()) {
            sb.append(a)
            sb.setSpan(ForegroundColorSpan(0xFFB5BCC9.toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ")
        }
        val ini = sb.length
        sb.append(b)
        if (b.isNotEmpty()) {
            sb.setSpan(ForegroundColorSpan(Color.WHITE), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sb
        fijarVisible(true)
        ui.removeCallbacks(ocultar)
        ui.postDelayed(ocultar, 9000)
    }

    fun quitar() {
        ui.removeCallbacks(ocultar)
        val r = raiz
        if (puesta && r != null) {
            try {
                wm.removeView(r)
            } catch (e: Exception) {
            }
        }
        puesta = false
        raiz = null
        caja = null
        texto = null
        params = null
    }
}
