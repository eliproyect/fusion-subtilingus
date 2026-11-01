package com.elisotopo.subtilingus

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

// ============================================================
// Segmentador: corta el audio continuo en FRASES (por las pausas)
// ============================================================
// En la v0.6 se cortaba cada 4 s fijos, a mitad de palabra, y Whisper
// rendía mal con trozos tan cortos. Ahora:
//   - se detecta cuándo hay voz (energía por encima del ruido de fondo,
//     que se va aprendiendo solo);
//   - la frase termina tras ~0,55 s de silencio (o a los 12 s como tope,
//     cortando en el punto más silencioso de los últimos 3 s);
//   - mientras alguien habla, cada ~1,5 s se ofrece una copia parcial
//     para enseñar un "borrador" gris que luego sustituye la frase final.
// Se usa SOLO desde el hilo de captura de audio.
// ============================================================
class Segmentador(
    private val alSegmento: (FloatArray, Long, Int) -> Unit,
    private val alParcial: (FloatArray, Long, Int) -> Unit
) {
    companion object {
        const val FS = 16000
        const val TRAMA = 320                 // 20 ms
        const val MAX_SEG_S = 12
        const val FIN_SILENCIO_TRAMAS = 28    // ~560 ms
        const val PRE_ROLL_TRAMAS = 12        // 240 ms antes de la voz
        const val MIN_VOZ_TRAMAS = 15         // menos de 300 ms de voz = ruido
        const val INICIO_VOZ_TRAMAS = 3
        const val PARCIAL_CADA_TRAMAS = 75    // 1,5 s
        const val PARCIAL_MIN_TRAMAS = 60     // 1,2 s
        const val COLA_SILENCIO_TRAMAS = 8    // silencio que se conserva al final
        const val UMBRAL_MIN_DB = -50.0
        const val MARGEN_DB = 9.0
    }

    @Volatile var nivelDb = -99.0
    @Volatile var oyoAlgo = false

    private val trama = FloatArray(TRAMA)
    private var enTrama = 0
    private val pre = FloatArray(PRE_ROLL_TRAMAS * TRAMA)
    private var preN = 0
    private val buf = FloatArray(FS * (MAX_SEG_S + 2))
    private var n = 0
    private var enVoz = false
    private var rachaVoz = 0
    private var rachaSilencio = 0
    private var tramasVoz = 0
    private var desdeParcial = 0
    private var ruidoDb = -80.0
    private var tramasTotales = 0L
    private var inicioMs = 0L
    private var idSegmento = 0

    fun entrada(datos: FloatArray, cuantos: Int) {
        for (i in 0 until cuantos) {
            trama[enTrama] = datos[i]
            enTrama++
            if (enTrama == TRAMA) {
                enTrama = 0
                procesar()
            }
        }
    }

    // Al parar: lo que quedara a medias se entrega como frase final
    fun finalizar() {
        if (enVoz) cerrarSegmento()
    }

    private fun procesar() {
        tramasTotales++
        var suma = 0.0
        for (v in trama) suma += (v * v).toDouble()
        val rms = sqrt(suma / TRAMA)
        val db = if (rms < 1e-6) -99.0 else 20.0 * log10(rms)

        nivelDb = max(db, nivelDb - 1.5)
        if (db > -70.0) oyoAlgo = true

        val umbral = max(UMBRAL_MIN_DB, ruidoDb + MARGEN_DB)
        val esVoz = db > umbral
        if (!esVoz) {
            ruidoDb = if (db < ruidoDb) ruidoDb * 0.8 + db * 0.2 else ruidoDb * 0.98 + db * 0.02
        } else {
            // sube muy despacio aunque haya voz: si el "ruido" es música
            // continua, el umbral acaba adaptándose
            ruidoDb = ruidoDb * 0.999 + db * 0.001
        }
        if (ruidoDb < -80.0) ruidoDb = -80.0

        if (!enVoz) {
            if (preN + TRAMA > pre.size) {
                System.arraycopy(pre, TRAMA, pre, 0, pre.size - TRAMA)
                preN = pre.size - TRAMA
            }
            System.arraycopy(trama, 0, pre, preN, TRAMA)
            preN += TRAMA
            if (esVoz) rachaVoz++ else rachaVoz = 0
            if (rachaVoz >= INICIO_VOZ_TRAMAS) {
                enVoz = true
                System.arraycopy(pre, 0, buf, 0, preN)
                n = preN
                inicioMs = (tramasTotales - preN / TRAMA) * 20L
                rachaSilencio = 0
                tramasVoz = rachaVoz
                desdeParcial = 0
                preN = 0
            }
            return
        }

        if (n + TRAMA > buf.size) {
            cortarForzado()
        }
        System.arraycopy(trama, 0, buf, n, TRAMA)
        n += TRAMA
        if (esVoz) {
            tramasVoz++
            rachaSilencio = 0
        } else {
            rachaSilencio++
        }
        if (rachaSilencio >= FIN_SILENCIO_TRAMAS) {
            cerrarSegmento()
            return
        }
        if (n >= MAX_SEG_S * FS) {
            cortarForzado()
            return
        }
        desdeParcial++
        if (desdeParcial >= PARCIAL_CADA_TRAMAS && n >= PARCIAL_MIN_TRAMAS * TRAMA) {
            desdeParcial = 0
            alParcial(buf.copyOf(n), inicioMs, idSegmento)
        }
    }

    private fun cerrarSegmento() {
        val sobrantes = max(0, rachaSilencio - COLA_SILENCIO_TRAMAS) * TRAMA
        val largo = n - sobrantes
        if (tramasVoz >= MIN_VOZ_TRAMAS && largo > 0) {
            alSegmento(buf.copyOf(largo), inicioMs, idSegmento)
        }
        idSegmento++
        reiniciar()
    }

    private fun reiniciar() {
        enVoz = false
        n = 0
        rachaVoz = 0
        rachaSilencio = 0
        tramasVoz = 0
        desdeParcial = 0
        preN = 0
    }

    // Frase demasiado larga: se corta donde haya menos energía
    private fun cortarForzado() {
        val desde = max(n - 3 * FS, 5 * FS)
        var corte = n
        var mejor = Double.MAX_VALUE
        var i = desde
        while (i + 1600 <= n) {
            var e = 0.0
            for (k in i until i + 1600) {
                val v = buf[k]
                e += (v * v).toDouble()
            }
            if (e < mejor) {
                mejor = e
                corte = i + 800
            }
            i += 800
        }
        if (corte > n) corte = n
        alSegmento(buf.copyOf(corte), inicioMs, idSegmento)
        idSegmento++
        val resto = n - corte
        if (resto > 0) System.arraycopy(buf, corte, buf, 0, resto)
        n = resto
        inicioMs += corte * 1000L / FS
        tramasVoz = MIN_VOZ_TRAMAS
        desdeParcial = 0
        rachaSilencio = 0
    }
}
