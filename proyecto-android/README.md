# Subtilingus

App personal para aprender idiomas con audio real: subtítulos en directo
sobre cualquier app (BBC, Spotify, YouTube…) o desde el micrófono (TV),
con todo guardado y herramientas de estudio.

## v0.7.1
- Refuerzos de optimización del motor nativo (Release forzado, -O3, ARMv8.2 dotprod/fp16) y prioridad alta del hilo de Whisper.
- Nueva "Prueba de velocidad" en Ajustes y diagnóstico de CPU en el registro.
- Dos modelos de prueba más (tiny.en sin comprimir, base.en q8).

## v0.7 — "Fase 2"

- **Servicio en segundo plano**: sigue transcribiendo mientras usas otras apps.
- **Audio del móvil** (Android 10+, AudioPlaybackCapture) o **micrófono**.
- **Subtítulos flotantes** (arrastrables, 3 tamaños) sobre cualquier app.
- **Frases en vez de bloques de 4 s**: se corta por las pausas; borrador gris
  mientras hablan y frase final al terminar.
- **Más rápido**: modelos cuantizados (q5_1), `audio_ctx` adaptado a cada
  frase, sin contexto previo, tiempos reales en pantalla.
- **Modelos**: inglés (.en, más precisos) y multilingües; se eligen en Ajustes.
- **Textos**: lista de sesiones con hora por frase; compartir, guardar como
  .txt / .srt, borrar.
- **Aprender**: toca una palabra → traducción, definición, ejemplos y
  "Guardar" en Vocabulario (consulta online solo de esa palabra).
- **Ajustes > Almacenamiento**: qué hay, dónde y cómo borrarlo.
  Registro de diagnóstico en Ajustes > Ver registro.

Carpeta de datos: `Android/data/com.elisotopo.subtilingus/files/`
(`modelos/`, `textos/`, `vocabulario.tsv`, `registro.log`).

## Pendiente / ideas
- Traducción de los subtítulos en vivo EN→ES (ML Kit on-device).
- Repaso del vocabulario (tarjetas, repetición espaciada).
- Diccionario offline, más idiomas en la interfaz.
- Variante de whisper.cpp con instrucciones ARMv8.2 (dotprod/fp16).
