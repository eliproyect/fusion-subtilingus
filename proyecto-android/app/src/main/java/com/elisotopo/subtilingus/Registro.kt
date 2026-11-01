package com.elisotopo.subtilingus

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Registro de diagnóstico: una línea por suceso, en memoria (últimas 500)
// y en registro.log. Se ve en Ajustes > Ver registro.
object Registro {
    private const val MAX = 500
    private var archivo: File? = null
    private val memoria = ArrayDeque<String>()
    private val formato = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun iniciar(carpeta: File) {
        if (archivo != null) return
        val f = File(carpeta, "registro.log")
        try {
            if (f.exists() && f.length() > 1_000_000L) f.delete()
        } catch (e: Exception) {
        }
        archivo = f
    }

    fun i(etiqueta: String, mensaje: String) = escribir("I", etiqueta, mensaje)
    fun w(etiqueta: String, mensaje: String) = escribir("W", etiqueta, mensaje)
    fun e(etiqueta: String, mensaje: String) = escribir("E", etiqueta, mensaje)

    @Synchronized
    private fun escribir(nivel: String, etiqueta: String, mensaje: String) {
        val linea = formato.format(Date()) + " " + nivel + "/" + etiqueta + ": " + mensaje
        memoria.addLast(linea)
        while (memoria.size > MAX) memoria.removeFirst()
        Log.println(if (nivel == "E") Log.ERROR else Log.INFO, "Subtilingus", etiqueta + ": " + mensaje)
        try {
            archivo?.appendText(linea + "\n")
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun texto(): String = memoria.joinToString("\n")

    @Synchronized
    fun limpiar() {
        memoria.clear()
        try {
            archivo?.delete()
        } catch (e: Exception) {
        }
    }

    fun archivoLog(): File? = archivo
}
