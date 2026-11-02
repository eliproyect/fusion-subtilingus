package com.elisotopo.subtilingus

import android.os.SystemClock
import java.util.Random
import kotlin.concurrent.thread

// Prueba de velocidad: transcribe 10 s de audio sintético con el modelo
// activo y mide cuánto tarda (1 hilo y los hilos de Ajustes). Sirve para
// saber con datos si el móvil/compilación va bien. Referencia razonable
// para tiny/base: menos de 3 s para 10 s de audio.
object Prueba {
    fun ejecutar(a: MainActivity, alTerminar: (String) -> Unit) {
        thread(name = "prueba") {
            val sb = StringBuilder()
            try {
                val m = Modelos.buscar(a.ajustes.modeloId) ?: Modelos.buscar(Modelos.RECOMENDADO_ID)!!
                if (!Modelos.descargado(a, m)) {
                    sb.append("El modelo «").append(m.nombre).append("» no está descargado.")
                } else {
                    sb.append("Modelo: ").append(m.nombre).append('\n')
                    val puente = WhisperBridge()
                    val t0 = SystemClock.elapsedRealtime()
                    val ctx = puente.initContext(Modelos.archivo(a, m).absolutePath)
                    val carga = SystemClock.elapsedRealtime() - t0
                    if (ctx == 0L) {
                        sb.append("No se pudo cargar el modelo.")
                    } else {
                        sb.append("Carga del modelo: ").append(carga).append(" ms\n")
                        sb.append(puente.infoSistema()).append("\n\n")
                        val audio = FloatArray(160000)
                        val rnd = Random(1)
                        for (i in audio.indices) audio[i] = (rnd.nextFloat() - 0.5f) * 0.02f
                        for (h in listOf(1, a.ajustes.hilos).distinct()) {
                            val t1 = SystemClock.elapsedRealtime()
                            puente.transcribir(ctx, audio, "en", h, 500)
                            val ms = SystemClock.elapsedRealtime() - t1
                            sb.append(h).append(if (h == 1) " hilo: " else " hilos: ")
                                .append(ms).append(" ms para 10 s de audio\n")
                        }
                        puente.freeContext(ctx)
                    }
                }
            } catch (e: Throwable) {
                sb.append("Error: ").append(e)
            }
            val r = sb.toString()
            Registro.i("PRUEBA", r.replace('\n', ' '))
            a.runOnUiThread { alTerminar(r) }
        }
    }
}
