# YT → NewPipe

Navegas por la app oficial de YouTube (con tu cuenta, historial y recomendaciones) y, al tocar un vídeo, se abre en NewPipe.

## Cómo funciona

1. Tocas un vídeo en YouTube y se abre su reproductor.
2. Un servicio de accesibilidad detecta el botón **Compartir**, lo pulsa y luego pulsa **Copiar enlace**.
3. Una actividad invisible lee el enlace del portapapeles y lo abre en NewPipe (o Tubular).
4. Cuando vuelves a YouTube, el reproductor que quedó abierto se cierra solo.

Tarda alrededor de medio segundo. En YouTube aparece un botón flotante **NewPipe ●/○**: tócalo para activar o desactivar el desvío (por si alguna vez quieres ver algo en YouTube) y arrástralo para moverlo. Funciona también con Shorts.

## Conseguir el APK

La firma usa una clave privada que **nunca** está en el repositorio (`~/.config/yttonp/keystore.properties`
con `storeFile`, `storePassword`, `keyAlias` y `keyPassword`). Todas las versiones deben firmarse con la misma
clave para instalarse encima de la anterior.

- **Desde el PC (la forma habitual):** `./gradlew assembleRelease` y `adb install -r app/build/outputs/apk/release/app-release.apk`.
- **GitHub Actions** solo comprueba que compila. Si algún día quieres que publique el APK en *Releases*, añade
  los Secrets `YTTONP_KEYSTORE_B64` (el `.jks` en base64) y `YTTONP_KEYSTORE_PASSWORD`; sin ellos no publica nada.

Hasta la v2.4 la clave estaba en el repositorio (y sigue en su historial): ya no se usa. La v2.5 se firma con
la nueva, así que hubo que desinstalar la versión anterior una vez.

## Puesta en marcha en el móvil

1. Instala el APK y abre **YT → NewPipe**.
2. Pulsa *Abrir ajustes de Accesibilidad* → *Apps instaladas* (o *Servicios descargados*) → **YT → NewPipe** → actívalo.
   - En Android 13+, si sale "Ajuste restringido": *Abrir información de la app* → ⋮ → *Permitir ajustes restringidos*, y repite el paso.
3. Elige si quieres abrir en NewPipe o Tubular.
4. Abre YouTube y toca un vídeo.

## Si deja de funcionar

YouTube cambia su interfaz con frecuencia. Los textos que busca el servicio están en
`app/src/main/java/com/javi/yt2newpipe/YtTexts.kt` (español e inglés y otros idiomas).
Si no encuentra "Copiar enlace", guarda lo que había en pantalla en la sección **Diagnóstico** de la app: con eso se ajustan los textos.

## Limitaciones

- Lo que ves en NewPipe no entra en tu historial de YouTube.
- Si giras el móvil a pantalla completa antes de que actúe, el botón Compartir no está visible y no se dispara.
- Es para uso personal: los servicios de accesibilidad de este tipo no se pueden publicar en Google Play.
