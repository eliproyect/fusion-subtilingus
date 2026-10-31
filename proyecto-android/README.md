# Subtilingus

App personal para aprender idiomas escuchando contenido real: subtítulos
en directo sobre cualquier audio + transcripción guardada, con capa de
aprendizaje (diccionario, tarjetas) como objetivo final.

**Fase 0** — Proyecto base: pantalla de diagnóstico + prueba de micrófono. ✅

**Fase 1** — Whisper on-device: transcripción en directo por bloques de 4 s,
idioma conmutable EN/ES, texto guardado en el móvil y botón de compartir.
whisper.cpp se compila con el NDK (se descarga en build con CMake
FetchContent) y el modelo tiny (75 MB) se descarga la primera vez que
se abre la app. Todo offline: el audio nunca sale del móvil. ✅

**Fase 1.1 (actual, v0.5)** — Modo depuración: pestaña **Registro** (hora +
nivel + etiqueta de cada paso, también en `registro.log`), medidor de nivel
del micrófono en dB, contadores en vivo, resumen del mundo C tras cada
bloque (`estadoNativo()`: rc, segmentos, CPU) y pestaña **Archivos**:
mini-explorador de la carpeta de la app, porque Android 11+ no deja entrar
a los gestores de archivos en `Android/data/`. "Limpiar" en Archivos ofrece
borrar de verdad la transcripción o el modelo (con confirmación).

Cambios de velocidad y calidad en v0.5:
- **-O3 forzado en CMake**: la variante debug compilaba ggml sin optimizar
  (-O0) y un bloque de 4 s tardaba 109 s de CPU; con -O3 baja a pocos
  segundos (el hallazgo del Registro).
- Los bloques de silencio (< -50 dB) no se envían a Whisper: menos frases
  fantasma y menos batería.
- `suppress_non_speech_tokens` en Whisper para ignorar ruido no vocal.
