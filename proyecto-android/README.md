# Subtilingus

App personal para aprender idiomas escuchando contenido real: subtítulos
en directo sobre cualquier audio + transcripción guardada, con capa de
aprendizaje (diccionario, tarjetas) como objetivo final.

**Fase 0** — Proyecto base: pantalla de diagnóstico + prueba de micrófono. ✅

**Fase 1** — Whisper on-device: transcripción en directo por bloques de 4 s,
idioma conmutable EN/ES, texto guardado en el móvil y botón de compartir.
whisper.cpp se compila con el NDK (se descarga en build con CMake
FetchContent) y el modelo tiny (75 MB) se descarga la primera vez que
se abre la app. Todo offline: el audio nunca sale del móvil.

**Fase 1.1 (actual, v0.4)** — Modo depuración: no se transcribía y no había forma
de saber por qué. Nueva pestaña **Registro** (hora + nivel + etiqueta de
cada paso, también en `registro.log`), medidor de nivel del micrófono en dB,
contadores en vivo (trozos, bloques, descartados, procesados), resumen del
mundo C tras cada bloque (`estadoNativo()`: rc, segmentos, CPU) y archivos
en la carpeta externa de la app. Como Android 11+ no deja a los gestores
de archivos entrar en `Android/data/`, la pestaña **Archivos** lista la
carpeta (nombres, tamaños, fechas): es nuestro mini-explorador. El modelo
descargado con la Fase 1 se migra automáticamente: no hay que re-descargarlo.
Fix v0.4: `AudioRecord.ERROR_NO_INIT` no existe en la API 34 (llegó en la
35) y rompía la compilación.
