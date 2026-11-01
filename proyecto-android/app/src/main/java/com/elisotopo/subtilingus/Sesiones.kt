package com.elisotopo.subtilingus

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// Una frase de una sesión con su minuto:segundo
class Linea(val ms: Long, val texto: String)

class DatosSesion(
    val archivo: File,
    val fecha: Long,
    val idioma: String,
    val fuente: String,
    val lineas: List<Linea>
)

class ResumenSesion(
    val archivo: File,
    val titulo: String,
    val meta: String,
    val vista: String
)

// Escribe una sesión línea a línea. El archivo no se crea hasta que hay
// la primera frase (así no quedan archivos vacíos).
class EscritorSesion(val archivo: File, private val cabecera: String) {
    @Volatile var creado = false

    @Synchronized
    fun anadir(ms: Long, texto: String) {
        if (!creado) {
            archivo.writeText(cabecera + "\n")
            creado = true
        }
        archivo.appendText("[" + Sesiones.mmss(ms) + "] " + texto.replace('\n', ' ') + "\n")
    }
}

// Formato de cada archivo en  Textos/ :
//   #SUB fecha=2026-10-31T18:42 idioma=en fuente=interno modelo=base-en
//   [00:03] Hello and welcome.
object Sesiones {
    private val regexLinea = Regex("^\\[(\\d+):(\\d\\d)\\] (.*)$")

    fun mmss(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    fun nueva(ctx: Context, idioma: String, fuente: String, modeloId: String): EscritorSesion {
        val ahora = Date()
        val nombre = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(ahora) + ".txt"
        val cabecera = "#SUB fecha=" + SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(ahora) +
                " idioma=" + idioma + " fuente=" + fuente + " modelo=" + modeloId
        return EscritorSesion(File(Carpetas.textos(ctx), nombre), cabecera)
    }

    fun leer(archivo: File): DatosSesion {
        var fecha = archivo.lastModified()
        var idioma = "en"
        var fuente = Ajustes.FUENTE_MIC
        val lineas = ArrayList<Linea>()
        try {
            for (l in archivo.readLines()) {
                if (l.startsWith("#SUB")) {
                    for (par in l.split(' ')) {
                        val kv = par.split('=', limit = 2)
                        if (kv.size != 2) continue
                        when (kv[0]) {
                            "fecha" -> {
                                try {
                                    val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(kv[1])
                                    if (d != null) fecha = d.time
                                } catch (e: Exception) {
                                }
                            }
                            "idioma" -> idioma = kv[1]
                            "fuente" -> fuente = kv[1]
                        }
                    }
                    continue
                }
                val m = regexLinea.find(l)
                if (m != null) {
                    val ms = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000
                    lineas.add(Linea(ms, m.groupValues[3]))
                } else if (l.isNotBlank()) {
                    lineas.add(Linea(0, l))
                }
            }
        } catch (e: Exception) {
            Registro.e("TEXTOS", "No se pudo leer " + archivo.name + ": " + e)
        }
        return DatosSesion(archivo, fecha, idioma, fuente, lineas)
    }

    fun titulo(d: DatosSesion): String =
        SimpleDateFormat("d MMM yyyy · HH:mm", Locale("es", "ES")).format(Date(d.fecha))

    fun nombreFuente(f: String): String = if (f == Ajustes.FUENTE_INTERNO) "Audio del móvil" else "Micrófono"

    fun lista(ctx: Context): List<ResumenSesion> {
        val archivos = Carpetas.textos(ctx).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name } ?: return emptyList()
        val res = ArrayList<ResumenSesion>()
        for (f in archivos) {
            val d = leer(f)
            val dur = if (d.lineas.isEmpty()) 0L else d.lineas.last().ms / 60000L
            val meta = d.idioma.uppercase() + " · " + nombreFuente(d.fuente) + " · " +
                    d.lineas.size + " frases · " + (if (dur < 1) "<1" else dur.toString()) + " min"
            val vista = if (d.lineas.isEmpty()) "(vacío)" else d.lineas[0].texto
            res.add(ResumenSesion(f, titulo(d), meta, vista))
        }
        return res
    }

    fun textoPlano(lineas: List<Linea>, conTiempos: Boolean): String {
        val sb = StringBuilder()
        for (l in lineas) {
            if (conTiempos) sb.append('[').append(mmss(l.ms)).append("] ")
            sb.append(l.texto).append('\n')
        }
        return sb.toString()
    }

    private fun tc(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val r = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, r)
    }

    // Subtítulos .srt (para ponerlos a un vídeo o repasarlos)
    fun srt(lineas: List<Linea>): String {
        val sb = StringBuilder()
        for ((i, l) in lineas.withIndex()) {
            val ini = l.ms
            val sigue = if (i + 1 < lineas.size) lineas[i + 1].ms else ini + 5000
            val deseado = ini + max(1500L, l.texto.length * 65L)
            val tope = max(sigue - 50L, ini + 800L)
            val fin = min(deseado, tope)
            sb.append(i + 1).append('\n')
            sb.append(tc(ini)).append(" --> ").append(tc(fin)).append('\n')
            sb.append(l.texto).append("\n\n")
        }
        return sb.toString()
    }

    fun tamanoTextos(ctx: Context): Long {
        var t = 0L
        val l = Carpetas.textos(ctx).listFiles() ?: return 0L
        for (f in l) t += f.length()
        return t
    }

    fun cuantos(ctx: Context): Int = Carpetas.textos(ctx).listFiles()?.size ?: 0

    fun borrarTodos(ctx: Context) {
        Carpetas.textos(ctx).listFiles()?.forEach { it.delete() }
    }
}

// Palabras guardadas desde el visor (vocabulario.tsv)
object Vocabulario {
    class Entrada(val palabra: String, val traduccion: String, val frase: String)

    private fun archivo(ctx: Context) = File(Carpetas.base(ctx), "vocabulario.tsv")

    @Synchronized
    fun anadir(ctx: Context, palabra: String, traduccion: String, frase: String) {
        val limpio = { s: String -> s.replace('\t', ' ').replace('\n', ' ').trim() }
        archivo(ctx).appendText(limpio(palabra) + "\t" + limpio(traduccion) + "\t" + limpio(frase) + "\n")
    }

    @Synchronized
    fun leer(ctx: Context): List<Entrada> {
        val f = archivo(ctx)
        if (!f.exists()) return emptyList()
        val res = ArrayList<Entrada>()
        try {
            for (l in f.readLines()) {
                val p = l.split('\t')
                if (p.size >= 3) res.add(Entrada(p[0], p[1], p[2]))
            }
        } catch (e: Exception) {
        }
        return res
    }

    @Synchronized
    fun quitar(ctx: Context, indice: Int) {
        val todas = leer(ctx).toMutableList()
        if (indice in todas.indices) todas.removeAt(indice)
        val sb = StringBuilder()
        for (e in todas) sb.append(e.palabra).append('\t').append(e.traduccion).append('\t').append(e.frase).append('\n')
        archivo(ctx).writeText(sb.toString())
    }

    @Synchronized
    fun borrarTodo(ctx: Context) {
        archivo(ctx).delete()
    }
}

// Pasa lo que dejó la v0.6 (modelo y transcripcion.txt sueltos) a la
// estructura nueva, sin tener que descargar ni perder nada.
object Migracion {
    fun ejecutar(ctx: Context) {
        try {
            val base = Carpetas.base(ctx)
            val viejoModelo = File(base, "ggml-tiny.bin")
            val nuevoModelo = File(Carpetas.modelos(ctx), "ggml-tiny.bin")
            if (viejoModelo.exists() && !nuevoModelo.exists()) {
                if (!viejoModelo.renameTo(nuevoModelo)) {
                    viejoModelo.copyTo(nuevoModelo, overwrite = true)
                    viejoModelo.delete()
                }
                Registro.i("MIGRA", "Modelo de la v0.6 movido a modelos/")
            }
            val viejoTexto = File(base, "transcripcion.txt")
            if (viejoTexto.exists()) {
                val t = viejoTexto.readText().replace('\n', ' ').trim()
                if (t.isNotEmpty()) {
                    val f = File(Carpetas.textos(ctx), "antigua-v06.txt")
                    val fecha = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(Date(viejoTexto.lastModified()))
                    f.writeText("#SUB fecha=" + fecha + " idioma=en fuente=mic modelo=tiny-v06\n[00:00] " + t + "\n")
                    Registro.i("MIGRA", "Transcripción de la v0.6 movida a textos/")
                }
                viejoTexto.delete()
            }
        } catch (e: Exception) {
            Registro.e("MIGRA", "Migración fallida: " + e)
        }
    }
}
