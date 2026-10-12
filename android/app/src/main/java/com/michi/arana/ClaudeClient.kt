package com.michi.arana

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.WebSearchTool20260209
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pregunta a Claude (con búsqueda web) y devuelve una respuesta corta para decirla en voz alta.
 * Se llama desde un hilo de fondo.
 */
class ClaudeClient(apiKey: String) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    /** Últimos turnos como texto plano (pregunta, respuesta) para dar contexto a la conversación. */
    private val history = ArrayDeque<Pair<String, String>>()

    fun ask(question: String, skin: AvatarView.Skin): String {
        return try {
            val answer = try {
                request(question, skin, withHistory = true, withFallbacks = true)
            } catch (e: BadRequestException) {
                // Si algo del historial o del parámetro de respaldo no se acepta, reintenta lo mínimo
                request(question, skin, withHistory = false, withFallbacks = false)
            }
            history.addLast(question to answer)
            while (history.size > 6) history.removeFirst()
            answer
        } catch (e: UnauthorizedException) {
            "La clave de Claude no es válida. Revísala en los ajustes de la app."
        } catch (e: RateLimitException) {
            "Claude está ocupado ahora mismo. Inténtalo en un momento."
        } catch (e: AnthropicIoException) {
            "No pude conectarme. Revisa el internet de la tablet."
        } catch (e: AnthropicServiceException) {
            "Claude tuvo un problema (${e.statusCode()}). Inténtalo otra vez."
        } catch (e: Exception) {
            "Algo falló al preguntar a Claude."
        }
    }

    private fun request(question: String, skin: AvatarView.Skin, withHistory: Boolean, withFallbacks: Boolean): String {
        val b = MessageCreateParams.builder()
            .model("claude-opus-5-5")
            .maxTokens(16000L)
            .system(systemPrompt(skin))
            // Respuestas habladas: esfuerzo bajo para que conteste rápido
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addTool(WebSearchTool20260209.builder().maxUses(3L).build())
        if (withFallbacks) {
            // Si el modelo rechaza una petición, el servidor reintenta con un modelo de respaldo
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        if (withHistory) history.forEach { (q, a) -> b.addUserMessage(q); b.addAssistantMessage(a) }
        b.addUserMessage(question)

        var response: Message = client.messages().create(b.build())
        // La búsqueda web puede pausar el turno: se continúa reenviando lo que llevaba
        var rounds = 0
        while (response.stopReason().orElse(null) == StopReason.PAUSE_TURN && rounds < 3) {
            b.addMessage(response.toParam())
            response = client.messages().create(b.build())
            rounds++
        }
        if (response.stopReason().orElse(null) == StopReason.REFUSAL) return "Prefiero no responder eso. ¿Te ayudo con otra cosa?"

        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return clean(text).ifBlank { "No encontré una respuesta clara." }
    }

    private fun systemPrompt(skin: AvatarView.Skin): String {
        val persona = when (skin) {
            AvatarView.Skin.SOLDADO ->
                "Ahora llevas el traje de soldado: hablas como un militar amable y disciplinado (\"¡Afirmativo!\", \"misión cumplida\"), con orgullo boliviano."
            AvatarView.Skin.ARANA_ROJA ->
                "Ahora llevas el traje de héroe arácnido rojo y azul: hablas como un vecino superhéroe alegre y bromista."
            AvatarView.Skin.ARANA_NEGRA ->
                "Ahora llevas el traje arácnido negro con rayos: hablas como un joven héroe de barrio, relajado y con estilo (\"¡qué onda!\", \"tranqui\")."
        }
        val now = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale("es")).format(Date())
        return """
            Eres BYNS, el asistente personal de voz que vive en la tablet del usuario y flota encima de las apps.
            $persona
            Responde siempre en español, como si hablaras en voz alta: de 1 a 3 frases cortas, sin listas, sin markdown, sin emojis, sin enlaces.
            Si la pregunta depende de información actual (noticias, clima, precios, resultados, horarios), usa la búsqueda web y resume lo importante.
            Fecha y hora actuales del usuario: $now.
        """.trimIndent()
    }

    private fun clean(s: String): String = s
        .replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
        .replace(Regex("[*_#`>]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
