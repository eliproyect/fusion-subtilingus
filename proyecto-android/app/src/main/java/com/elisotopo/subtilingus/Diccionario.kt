package com.elisotopo.subtilingus

import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Consultas online de una palabra (solo al tocarla en el visor):
//  - definición y ejemplos: dictionaryapi.dev (gratuito, sin clave)
//  - traducción: MyMemory (gratuito, sin clave, con límite diario)
// Se envía únicamente la palabra o frase tocada, nunca el audio.
object Diccionario {

    private fun obtener(url: String): String? {
        var c: HttpURLConnection? = null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            c = conn
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Subtilingus/0.7")
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Registro.w("DICC", "Consulta fallida: " + e)
            null
        } finally {
            try {
                c?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    fun traducir(texto: String, de: String, a: String): String? {
        val q = URLEncoder.encode(texto.take(400), "UTF-8")
        val cuerpo = obtener("https://api.mymemory.translated.net/get?q=" + q + "&langpair=" + de + "%7C" + a)
            ?: return null
        return try {
            val t = JSONObject(cuerpo).getJSONObject("responseData").getString("translatedText")
            Html.fromHtml(t, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        } catch (e: Exception) {
            null
        }
    }

    // Devuelve un bloque de texto con fonética, definiciones y ejemplos
    fun definicion(palabra: String, idioma: String): String? {
        val p = URLEncoder.encode(palabra.lowercase(), "UTF-8")
        val cuerpo = obtener("https://api.dictionaryapi.dev/api/v2/entries/" + idioma + "/" + p) ?: return null
        return try {
            val e = JSONArray(cuerpo).getJSONObject(0)
            val sb = StringBuilder()
            val fon = e.optString("phonetic", "")
            if (fon.isNotEmpty()) sb.append(fon).append("\n\n")
            val sig = e.optJSONArray("meanings")
            if (sig != null) {
                for (i in 0 until minOf(2, sig.length())) {
                    val m = sig.getJSONObject(i)
                    val clase = m.optString("partOfSpeech", "")
                    val defs = m.optJSONArray("definitions") ?: continue
                    for (j in 0 until minOf(2, defs.length())) {
                        val d = defs.getJSONObject(j)
                        sb.append("• ")
                        if (clase.isNotEmpty()) sb.append('(').append(clase).append(") ")
                        sb.append(d.optString("definition", "")).append('\n')
                        val ej = d.optString("example", "")
                        if (ej.isNotEmpty()) sb.append("   “").append(ej).append("”\n")
                    }
                }
            }
            val r = sb.toString().trim()
            if (r.isEmpty()) null else r
        } catch (e: Exception) {
            null
        }
    }
}
