# Subtilingus

App personal para aprender idiomas escuchando contenido real: subtítulos
en directo (inglés/español) sobre cualquier audio + transcripción guardada,
con capa de aprendizaje (diccionario, tarjetas) como objetivo final.

**Fase 0** — Proyecto base: pantalla de diagnóstico + prueba de micrófono.
El micrófono se abre ya en el formato que usará Whisper (16 kHz, mono, 16 bits).

Proyecto para el editor web (Fusion): MainActivity.kt, manifest, gradle y res/
están en el formato interno del editor; al compilar se suben a GitHub y
GitHub Actions genera el APK.
