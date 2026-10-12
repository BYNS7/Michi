# Michi Araña · asistente flotante para Android

Una arañita héroe estilo chibi que flota **encima de cualquier aplicación** de la tablet.

- **Toca** a Michi → se abre el chat (escribe o toca 🎤).
- **Mantén pulsado** → te escucha directamente.
- **Arrastra** → se pega al borde; suéltalo sobre la ✕ de abajo para esconderlo.

Entiende en español: la hora y la fecha, temporizadores, recordatorios («recuérdame tomar agua en 30 minutos»,
«… a las 6 de la tarde»), «¿qué avisos tengo?», «abre YouTube», «busca …», la batería y chistes.
Habla en voz alta (se puede silenciar desde la app).

## Instalar en la tablet

1. En GitHub, abre **Releases → Michi Araña (APK para Android)** y descarga `MichiArana.apk` desde la tablet.
   (También queda como artefacto en la pestaña **Actions**.)
2. Ábrela e instálala. Android pedirá permitir «instalar apps desconocidas» para el navegador o Archivos.
3. Abre **Michi Araña** y sigue los pasos: permitir «mostrar sobre otras apps», permitir el micrófono
   y tocar **¡Activar a Michi!**.

Requiere Android 8.0 o superior. El dictado por voz usa el reconocimiento de voz del sistema (app de Google).
En iPad no es posible: iOS no permite que una app se dibuje encima de las demás.

## Compilar

```sh
cd android
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

Cada push que toque `android/` compila la APK con GitHub Actions (`.github/workflows/android.yml`).
