# BYNS · asistente flotante para Android

Una minifigura de bloques **en 3D** que flota **encima de cualquier aplicación** de la tablet.
Está modelada con piezas 3D (cabeza cilíndrica, torso, brazos con codo, manos en C, piernas) y se dibuja
con perspectiva, luz y estampados, así que puede girar, tumbarse, colgarse de cabeza o hacer volteretas.
Se mueve solo con las articulaciones de una minifigura real, sin deformar piezas: la cabeza gira a los lados,
cada brazo gira en el hombro (el codo es fijo), las manos giran en la muñeca y cada pierna gira en la cadera.
Tiene tres trajes y se transforma entre ellos:

| Traje | Movimientos especiales |
|---|---|
| 🪖 **Soldado** (principal): casco, lentes, camuflaje y chaleco con la bandera de Bolivia | saluda con la mano en la frente al recibir una orden; apunta (fusil M4, AK-47, francotirador o pistola), rodilla en tierra, cuerpo a tierra, lagartijas, firmes, marcha, corre, binoculares, radio |
| 🔴 **Araña roja** | se columpia de una telaraña, lanza telarañas |
| ⚫ **Araña negra** (traje con rayos) | igual que la roja, con su propio estilo |

Todos: **columpiarse cruzando la pantalla** colgado de una telaraña (o cuerda) enganchada arriba, **colgarse de cabeza**,
saludar, saltar, bailar, voltereta, caminar, agacharse, sentarse, mostrar músculos, mirar alrededor, escuchar, pensar y
transformarse (gira y suelta piezas).

**Equipo del soldado** (por voz o en la app): casco, casco con visión nocturna, boina, gorra o nada; camuflaje multicam,
desierto, bosque, digital o negro; lentes, gafas tácticas o nada; con o sin mochila; y el arma.

## Cómo se usa

- Di **«Oye BYNS»** (si activas la escucha permanente) y luego tu orden, o todo junto: «Oye BYNS, abre YouTube».
- **Toca** a BYNS → chat (escribe o 🎤), con botones para cambiar de traje.
- **Mantén pulsado** → te escucha directamente.
- **Arrastra** → se pega al borde; suéltalo sobre la ✕ para esconderlo.

Entiende en español: «transfórmate», «ponte el traje negro», «modo soldado», «saca el fusil», «baila», «voltereta»,
«columpiate», la hora y la fecha, temporizadores y recordatorios, «abre …» (cualquier app instalada), la batería y chistes.
**Cualquier otra pregunta se la pasa a Claude**, que puede buscar en internet, y responde con la personalidad del traje.
Claude también puede mover a BYNS: «ponte en modo combate en el desierto» → cambia el camuflaje, saca el arma y apunta.

## Preguntas con Claude

Pega tu clave de la API de Claude (console.anthropic.com → API Keys) en la app, sección «Preguntas con Claude»,
y toca «Probar conexión con Claude» para comprobarla.
La clave se guarda solo en la tablet. Cada pregunta consume saldo de tu cuenta de Anthropic.
Se usa el modelo Claude Opus 5.5 con esfuerzo bajo (respuestas rápidas), búsqueda web y respaldo automático
del servidor si el modelo rechaza una petición.

## Voces

Cada traje usa una voz distinta de las voces en español instaladas en la tablet, con su propio tono y velocidad.
No se usan las voces reales de los actores de las películas (son voces protegidas). Para más variedad, instala más
voces en Ajustes → Texto a voz → Google → Instalar datos de voz.

## Instalar en la tablet

1. Descarga `BYNS.apk` desde **Releases → BYNS (APK para Android)** en la tablet e instálala.
2. Abre **BYNS**: permite «mostrar sobre otras apps», permite el micrófono y toca **¡Activar a BYNS!**.
3. Opcional: activa «Escuchar siempre "Oye BYNS"» y pega tu clave de Claude.

Requiere Android 8.0 o superior. El dictado usa el reconocimiento de voz del sistema (app de Google).
La escucha permanente gasta más batería y, según la tablet, puede sonar un pitido cada vez que se reinicia.
El micrófono espera más antes de cortar la frase, reintenta solo si no captó nada y reconoce «BYNS» aunque lo
transcriba como «bins», «vins», «beins», «bains», etc.
En iPad no es posible: iOS no permite que una app se dibuje encima de las demás.

## Compilar

```sh
cd android
./gradlew assembleDebug        # APK en app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # dibuja todos los trajes/poses en app/build/avatars/avatars.png
```
