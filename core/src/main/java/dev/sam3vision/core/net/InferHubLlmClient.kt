package dev.sam3vision.core.net

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Role
import dev.sam3vision.core.speech.SpeechLanguage
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_TOKENS = 800
private const val MAX_LIVE_TOKENS = 200
private const val MAX_LIVE_CHARS = 400
private const val TEMPERATURE = 0.2

/**
 * Implémentation OkHttp d'un endpoint compatible OpenAI (`POST {baseUrl}/chat/completions`).
 * Aucune clé en dur : [apiKey] vient des réglages. Erreurs typées : 401 / 429 / timeout / réseau.
 */
class InferHubLlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /** Langue de la voix : le modèle répond dans cette langue (elle est lue telle quelle). */
    private val language: SpeechLanguage = SpeechLanguage.DEFAULT,
    private val client: OkHttpClient = defaultHttpClient(),
    private val maxHistoryTurns: Int = 20,
) : LlmClient, LiveLlmClient {

    /** Narration d'une frame du direct : courte, contextualisée, bornée en longueur. */
    override suspend fun describeFrame(
        jpegB64: String,
        labels: List<String>,
        previous: String,
    ): Result<String> {
        val messages = JSONArray()
            .put(system(LlmPrompts.liveSystem(language)))
            .put(userWithImage(LlmPrompts.liveUser(labels, previous, language), jpegB64))
        return postChat(messages, MAX_LIVE_TOKENS).map { it.trim().take(MAX_LIVE_CHARS) }
    }

    override suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis> {
        val prompt = LlmPrompts.describeUser(labels, language)
        val messages = JSONArray()
            .put(system(LlmPrompts.describeSystem(language)))
            .put(userWithImage(prompt, jpegB64))
        return postChat(messages).mapCatching { content -> parseAnalysis(content, labels) }
    }

    override suspend fun ask(
        jpegB64: String,
        question: String,
        history: List<ChatTurn>,
        context: String?,
    ): Result<String> {
        val withContext = !context.isNullOrBlank()
        val messages = conversation(
            systemText = if (withContext) LlmPrompts.askMemorySystem(language) else LlmPrompts.askSystem(language),
            context = context,
            history = history,
            last = userWithImage(LlmPrompts.askQuestion(question), jpegB64),
        )
        return postChat(messages).map { it.trim() }
    }

    /** Même conversation, **sans image** : écran verrouillé, caméra fermée (cf. `Clients.kt`). */
    override suspend fun askText(
        question: String,
        history: List<ChatTurn>,
        context: String?,
    ): Result<String> {
        val messages = conversation(
            systemText = LlmPrompts.askVoiceSystem(language),
            context = context,
            history = history,
            last = role(Role.USER, LlmPrompts.askQuestion(question)),
        )
        return postChat(messages).map { it.trim() }
    }

    /** Assemblage commun aux deux questions : consigne, contexte éventuel, historique, tour final. */
    private fun conversation(
        systemText: String,
        context: String?,
        history: List<ChatTurn>,
        last: JSONObject,
    ): JSONArray {
        val messages = JSONArray().put(system(systemText))
        if (!context.isNullOrBlank()) {
            messages.put(system("${LlmPrompts.contextHeader(language)}\n${LlmPrompts.memoryContext(context)}"))
        }
        history.takeLast(maxHistoryTurns).forEach { messages.put(role(it.role, it.content)) }
        return messages.put(last)
    }

    /** Un seul point de sortie HTTP pour les deux méthodes : corps, en-têtes, extraction du texte. */
    private suspend fun postChat(messages: JSONArray, maxTokens: Int = MAX_TOKENS): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(ApiError.InvalidKey("clé API absente — renseigne-la dans Réglages"))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", TEMPERATURE)
            .put("max_tokens", maxTokens)
            .toString()
        val request = requestTo(
            baseUrl,
            "/chat/completions",
            "POST",
            jsonBody(body),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
        )
        return client.execute(request)
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { extractContent(raw) } }
    }

    private fun extractContent(raw: String): String {
        val json = parse(raw) ?: throw ApiError.BadResponse("corps de réponse illisible")
        val choices = json.optJSONArray("choices") ?: throw ApiError.BadResponse("pas de choices")
        val first = choices.optJSONObject(0) ?: throw ApiError.BadResponse("choices vide")
        val content = first.optJSONObject("message").str("content")
        if (content.isBlank()) throw ApiError.BadResponse("contenu vide")
        return content
    }
}

private fun system(text: String): JSONObject =
    JSONObject().put("role", "system").put("content", text)

private fun role(role: Role, text: String): JSONObject =
    JSONObject().put("role", if (role == Role.USER) "user" else "assistant").put("content", text)

private fun userWithImage(text: String, jpegB64: String): JSONObject = JSONObject()
    .put("role", "user")
    .put(
        "content",
        JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegB64")),
            ),
    )

internal fun parse(raw: String): JSONObject? = try {
    JSONObject(raw)
} catch (e: Exception) {
    null
}

/**
 * Tolérant : JSON attendu `{"description","tags"}` ; si le modèle renvoie du texte
 * libre, on garde ce texte en description et les labels segmentés en tags.
 */
internal fun parseAnalysis(content: String, labels: List<String>): Analysis {
    val json = extractFirstJsonObject(content)?.let { parse(it) }
        ?: return Analysis(content.trim(), labels, emptyList())
    val description = json.str("description").ifBlank { content.trim() }
    val tags = json.strings("tags").ifEmpty { labels }
    return Analysis(description, tags, emptyList())
}
