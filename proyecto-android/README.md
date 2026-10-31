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
