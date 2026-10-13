package com.michi.arana

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pregunta a Claude (con búsqueda web) y devuelve una respuesta corta para decirla en voz alta.
 * Claude además puede mover a BYNS con la herramienta «accion_byns» (movimientos, trajes, equipo,
 * armas, abrir apps, temporizadores). Se llama desde un hilo de fondo; [onAction] se invoca por cada
 * acción y devuelve el resultado para Claude.
 */
class ClaudeClient(apiKey: String) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    /** Últimos turnos como texto plano (pregunta, respuesta) para dar contexto a la conversación. */
    private val history = ArrayDeque<Pair<String, String>>()

    private val actionTool: Tool = Tool.builder()
        .name("accion_byns")
        .description(
            "Hace que BYNS (el avatar en pantalla) haga algo. Úsala cuando el usuario pida un gesto, movimiento, " +
                "traje, equipo, arma, abrir una app o un temporizador. Valores de «valor» según «tipo»:\n" +
                "- movimiento: SALUDO_MILITAR, FIRMES, APUNTAR, RODILLA_EN_TIERRA, MARCHAR, CAMINAR, CORRER, AGACHARSE, SENTARSE, " +
                "CUERPO_A_TIERRA, LAGARTIJAS, BINOCULARES, RADIO, MUSCULOS, MIRAR_ALREDEDOR, SALUDAR, SALTAR, BAILAR, VOLTERETA, " +
                "COLUMPIARSE (cruza la pantalla colgado de una telaraña o cuerda), COLGARSE_DE_CABEZA, LANZAR_TELARANA, PENSAR\n" +
                "- traje: SOLDADO, ARANA_ROJA, ARANA_NEGRA\n" +
                "- equipo: CASCO, CASCO_NVG, BOINA, GORRA, SIN_CASCO, MULTICAM, DESIERTO, BOSQUE, DIGITAL, NEGRO, LENTES, GAFAS, SIN_LENTES, MOCHILA, SIN_MOCHILA\n" +
                "- arma: FUSIL, AK, FRANCOTIRADOR, PISTOLA (la saca y apunta)\n" +
                "- abrir_app: nombre de la app instalada (por ejemplo YouTube)\n" +
                "- temporizador: duración en minutos (número)"
        )
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(
                    Tool.InputSchema.Properties.builder()
                        .putAdditionalProperty("tipo", JsonValue.from(mapOf(
                            "type" to "string",
                            "enum" to listOf("movimiento", "traje", "equipo", "arma", "abrir_app", "temporizador")
                        )))
                        .putAdditionalProperty("valor", JsonValue.from(mapOf("type" to "string")))
                        .build()
                )
                .required(listOf("tipo", "valor"))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build()
        )
        .strict(true)
        .build()

    /** Comprueba la clave con una pregunta mínima. Devuelve null si todo va bien o el mensaje de error. */
    fun test(): String? = try {
        client.messages().create(
            MessageCreateParams.builder().model("claude-opus-5-5").maxTokens(16000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("Responde solo: listo").build()
        )
        null
    } catch (e: Exception) { errorText(e) }

    fun ask(question: String, skin: AvatarView.Skin, onAction: (String, String) -> String): String {
        return try {
            val answer = try {
                request(question, skin, onAction, withHistory = true, withFallbacks = true)
            } catch (e: BadRequestException) {
                // Si algo del historial o del parámetro de respaldo no se acepta, reintenta lo mínimo
                request(question, skin, onAction, withHistory = false, withFallbacks = false)
            }
            history.addLast(question to answer)
            while (history.size > 6) history.removeFirst()
            answer
        } catch (e: Exception) { errorText(e) }
    }

    private fun errorText(e: Exception): String = when (e) {
        is UnauthorizedException -> "La clave de Claude no es válida. Revísala en los ajustes de la app."
        is RateLimitException -> "Claude está ocupado ahora mismo. Inténtalo en un momento."
        is AnthropicIoException -> "No pude conectarme. Revisa el internet de la tablet."
        is AnthropicServiceException -> "Claude tuvo un problema (${e.statusCode()}). Inténtalo otra vez."
        else -> "Algo falló al preguntar a Claude."
    }

    private fun request(
        question: String, skin: AvatarView.Skin, onAction: (String, String) -> String,
        withHistory: Boolean, withFallbacks: Boolean
    ): String {
        val b = MessageCreateParams.builder()
            .model("claude-opus-5-5")
            .maxTokens(16000L)
            .system(systemPrompt(skin))
            // Respuestas habladas: esfuerzo bajo para que conteste rápido
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addTool(WebSearchTool20260209.builder().maxUses(3L).build())
            .addTool(actionTool)
        if (withFallbacks) {
            // Si el modelo rechaza una petición, el servidor reintenta con un modelo de respaldo
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        if (withHistory) history.forEach { (q, a) -> b.addUserMessage(q); b.addAssistantMessage(a) }
        b.addUserMessage(question)

        var response: Message = client.messages().create(b.build())
        val spoken = StringBuilder()
        var rounds = 0
        while (rounds < 5) {
            val stop = response.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) return "Prefiero no responder eso. ¿Te ayudo con otra cosa?"
            response.content().forEach { blk -> blk.text().ifPresent { spoken.append(it.text()) } }
            when (stop) {
                StopReason.TOOL_USE -> {
                    // Ejecuta cada acción pedida y devuelve todos los resultados en un solo mensaje
                    val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { tu ->
                        val input = try { tu._input().convert(Map::class.java) as Map<*, *> } catch (_: Exception) { emptyMap<String, Any>() }
                        val result = onAction(input["tipo"]?.toString().orEmpty(), input["valor"]?.toString().orEmpty())
                        ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(result).build())
                    }
                    b.addMessage(response)
                    b.addUserMessageOfBlockParams(results)
                }
                // La búsqueda web puede pausar el turno: se continúa reenviando lo que llevaba
                StopReason.PAUSE_TURN -> b.addMessage(response)
                else -> break
            }
            response = client.messages().create(b.build())
            rounds++
        }
        return clean(spoken.toString()).ifBlank { "¡Hecho!" }
    }

    private fun systemPrompt(skin: AvatarView.Skin): String {
        val persona = when (skin) {
            AvatarView.Skin.SOLDADO ->
                "Ahora llevas el uniforme de soldado: hablas como un militar amable y disciplinado (\"¡Afirmativo!\", \"misión cumplida\"), con orgullo boliviano."
            AvatarView.Skin.ARANA_ROJA ->
                "Ahora llevas el traje de héroe arácnido rojo y azul: hablas como un vecino superhéroe alegre y bromista."
            AvatarView.Skin.ARANA_NEGRA ->
                "Ahora llevas el traje arácnido negro con rayos: hablas como un joven héroe de barrio, relajado y con estilo (\"¡qué onda!\", \"tranqui\")."
        }
        val now = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale("es")).format(Date())
        return """
            Eres BYNS, el asistente personal de voz que vive en la tablet del usuario: una minifigura de bloques en 3D que flota encima de las apps.
            $persona
            Responde siempre en español, como si hablaras en voz alta: de 1 a 3 frases cortas, sin listas, sin markdown, sin emojis, sin enlaces.
            Si la pregunta depende de información actual (noticias, clima, precios, resultados, horarios), usa la búsqueda web y resume lo importante.
            Cuando el usuario te pida un gesto, un movimiento, cambiar de traje o de equipo, sacar un arma, abrir una app o un temporizador,
            usa la herramienta accion_byns (puedes encadenar varias acciones) y luego confirma en una frase corta.
            Fecha y hora actuales del usuario: $now.
        """.trimIndent()
    }

    private fun clean(s: String): String = s
        .replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
        .replace(Regex("[*_#`>]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
