package dev.sam3vision.core.net

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Role
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Budget de sortie d'une question ou d'une description.
 *
 * 800 suffisait à un modèle qui répond directement ; le défaut actuel
 * (`muse-spark-open-1026`) **raisonne d'abord** : mesuré sur les vrais prompts
 * (2026-10-27), 515 à 606 tokens de raisonnement avant le premier mot de réponse, soit
 * 579-667 sur 800 — une marge trop courte, un dépassement rendant `content` vide. 1200
 * garde la même réponse visible en cas de raisonnement plus long ; seuls les tokens
 * réellement émis sont facturés.
 */
private const val MAX_TOKENS = 1200
private const val MAX_LIVE_TOKENS = 200
private const val MAX_LIVE_CHARS = 400
private const val TEMPERATURE = 0.2
private const val RETRY_DELAY_MS = 1_500L

/**
 * Modèle de repli quand le modèle demandé est inconnu du fournisseur (404
 * `model_not_found`) : le modèle épinglé (Baseten, `muse-spark-open-1026`) — un seul
 * fournisseur pour tout le LLM de l'app. Un modèle exotique ou hérité (ex. qwen InferHub)
 * qui 404 est donc rejoué sur l'épinglé, sans toucher aux réglages ; si l'épinglé lui-même
 * 404, l'échec remonte tel quel (aucun second modèle Baseten à tenter).
 */
internal const val FALLBACK_LLM_MODEL = Settings.DEFAULT_LLM_MODEL

/** 402 côté LLM : budget du fournisseur, jamais la voix ElevenLabs. */
private const val LLM_PAYMENT_LINE =
    "AI refused (402): no provider in your budget — raise the cap or change model"

/**
 * Implémentation OkHttp d'un endpoint compatible OpenAI (`POST {baseUrl}/chat/completions`).
 * Aucune clé en dur : [apiKey] vient des réglages. Erreurs typées : 401 / 429 / timeout / réseau.
 * Un 404 « modèle inconnu » rejoue la question sur [FALLBACK_LLM_MODEL] ; un échec transitoire
 * (délai, cadence, 5xx) est rejoué une fois après [RETRY_DELAY_MS] — jamais plus de 3 appels.
 */
class HttpLlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /** Langue de la voix : le modèle répond dans cette langue (elle est lue telle quelle). */
    private val language: SpeechLanguage = SpeechLanguage.DEFAULT,
    private val client: OkHttpClient = defaultHttpClient(),
    private val maxHistoryTurns: Int = 20,
    /**
     * Raisonnement interne du modèle. **Éteint par défaut** : `muse-spark` écrit ses pensées
     * dans `reasoning_content` avant la réponse, ce qui coûte 0,7 à 1,2 s et ~35 % de jetons
     * pour la même phrase (mesuré sur la vraie clé, image comprise). Et quand le budget est
     * trop court, `content` revient `null` — donc **aucune réponse**.
     */
    private val thinking: Boolean = false,
    /** Attente avant le nouvel essai (transitoire) : injectée pour les tests. */
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) : LlmClient, LiveLlmClient {

    /** Narration d'une frame du direct : courte, contextualisée, bornée en longueur. */
    override suspend fun describeFrame(
        jpegB64: String,
        labels: List<String>,
        previous: String,
    ): Result<String> {
        val messages = JSONArray()
            .put(system(LlmPrompts.liveSystem(language)))
            .put(userMessage(LlmPrompts.liveUser(labels, previous, language), jpegB64))
        return postChat(messages, MAX_LIVE_TOKENS).map { it.trim().take(MAX_LIVE_CHARS) }
    }

    override suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis> {
        val prompt = LlmPrompts.describeUser(labels, language)
        val messages = JSONArray()
            .put(system(LlmPrompts.describeSystem(language)))
            .put(userMessage(prompt, jpegB64))
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
            last = userMessage(LlmPrompts.askQuestion(question), jpegB64),
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

    /**
     * Un seul point de sortie HTTP : repli modèle sur 404 `model_not_found`, puis un nouvel
     * essai sur transitoire — 3 appels au plus, dans cet ordre, puis l'échec remonte tel quel.
     */
    private suspend fun postChat(messages: JSONArray, maxTokens: Int = MAX_TOKENS): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(ApiError.InvalidKey("API key missing — enter it in Settings"))
        }
        endpointRefusal("endpoint LLM", baseUrl, "/chat/completions")?.let { return Result.failure(it) }
        var current = model
        var result = postOnce(messages, maxTokens, current)
        var error = result.exceptionOrNull() ?: return result
        if (isUnknownModel(error) && current != FALLBACK_LLM_MODEL) {
            current = FALLBACK_LLM_MODEL
            result = postOnce(messages, maxTokens, current)
            error = result.exceptionOrNull() ?: return result
        }
        if (!error.isTransient) return result
        delay(retryDelayMs)
        return postOnce(messages, maxTokens, current)
    }

    /** Une tentative : corps, en-têtes, extraction du texte — l'orchestreur décide du reste. */
    private suspend fun postOnce(messages: JSONArray, maxTokens: Int, usingModel: String): Result<String> {
        val body = JSONObject()
            .put("model", usingModel)
            .put("messages", messages)
            .put("temperature", TEMPERATURE)
            .put("max_tokens", maxTokens)
            .apply { if (!thinking) put("chat_template_kwargs", JSONObject().put("enable_thinking", false)) }
            .toString()
        // Construite dans `executeBuilt` : une URL malformée devient une erreur, pas un crash.
        val result = client.executeBuilt {
            requestTo(
                baseUrl,
                "/chat/completions",
                "POST",
                jsonBody(body),
                headers = mapOf("Authorization" to "Bearer $apiKey"),
            )
        }
            .then { outcome -> outcome.requireSuccess() }
            .then { raw -> catchingResult { extractContent(raw) } }
        if (result.exceptionOrNull() is ApiError.PaymentRequired) {
            return Result.failure(ApiError.PaymentRequired(LLM_PAYMENT_LINE))
        }
        return result
    }

    private fun extractContent(raw: String): String {
        val json = parse(raw) ?: throw ApiError.BadResponse("unreadable response body")
        json.optJSONObject("error")?.let { refused ->
            val message = refused.optString("message").ifBlank { "error" }.take(160)
            throw ApiError.BadResponse("the provider refused: $message")
        }
        val choices = json.optJSONArray("choices") ?: throw ApiError.BadResponse("pas de choices")
        val first = choices.optJSONObject(0) ?: throw ApiError.BadResponse("choices vide")
        val message = first.optJSONObject("message") ?: throw ApiError.BadResponse("no message")
        val content = messageContent(message)
        if (content.isBlank()) throw ApiError.BadResponse("empty content")
        return content
    }
}

/** 404 « modèle inconnu » du fournisseur : le seul 404 qui mérite le repli. */
private fun isUnknownModel(error: Throwable): Boolean =
    error is ApiError.Http && error.code == 404 &&
        (error.body.contains("model_not_found", ignoreCase = true) ||
            error.body.contains("unknown model", ignoreCase = true))

/** Échec qui mérite un nouvel essai : délai, cadence, ou panne côté fournisseur. */
private val Throwable.isTransient: Boolean
    get() = when (this) {
        is ApiError.Timeout, is ApiError.RateLimited -> true
        is ApiError.Http -> code >= 500
        else -> false
    }

/**
 * Texte du message : chaîne (cas nominal) ou morceaux `[{"text": ...}]` recollés
 * (certains modèles multimodaux répondent en parties, pas en chaîne).
 */
private fun messageContent(message: JSONObject): String {
    message.optJSONArray("content")?.let { parts ->
        return (0 until parts.length()).mapNotNull { index ->
            parts.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
        }.joinToString("")
    }
    return message.str("content")
}

private fun system(text: String): JSONObject =
    JSONObject().put("role", "system").put("content", text)

private fun role(role: Role, text: String): JSONObject =
    JSONObject().put("role", if (role == Role.USER) "user" else "assistant").put("content", text)

/**
 * Le message `user` de tous les appels : texte, plus la photo quand [jpegB64] en porte une.
 *
 * Sans image utilisable le message part **texte seul** : une image vide expédiée en
 * `data:image/jpeg;base64,` faisait répondre 400 au backend, et la question restait
 * sans réponse. Une seule fabrique pour les trois appels, aucun JSON dupliqué.
 */
private fun userMessage(text: String, jpegB64: String): JSONObject {
    val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
    if (jpegB64.isNotBlank()) {
        content.put(
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegB64")),
        )
    }
    return JSONObject().put("role", "user").put("content", content)
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
