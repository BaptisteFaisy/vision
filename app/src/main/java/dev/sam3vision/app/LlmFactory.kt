package dev.sam3vision.app

import dev.sam3vision.core.net.HttpLlmClient
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.settings.Settings

/**
 * Client LLM de l'app : **une seule façon** de le construire, pour que la langue de la
 * voix (`Settings.speechLanguage`) soit toujours celle des réponses.
 *
 * La réponse du modèle est lue telle quelle par ElevenLabs : si le modèle répond en
 * français pendant que la voix est anglaise, on l'entend tout de suite.
 */
fun llmClient(settings: Settings): LlmClient = httpLlm(settings)

/**
 * Même client, type concret : le Direct s'en sert pour la **narration continue**
 * (`LiveLlmClient`), qui n'est pas dans l'interface [LlmClient].
 */
fun httpLlm(settings: Settings): HttpLlmClient = HttpLlmClient(
    settings.llmBaseUrl,
    settings.llmApiKey,
    settings.llmModel,
    settings.speechLanguage,
)

/**
 * Client de la **narration du Direct** : même modèle Meta que les questions
 * ([Settings.DEFAULT_LIVE_LLM_MODEL], Baseten), avec la clé dédiée. Le raisonnement
 * interne est coupé (`enable_thinking=false`) : le budget de 200 tokens va à la phrase.
 *
 * `null` quand la clé de narration manque : le Direct se tait.
 */
fun liveNarration(settings: Settings): HttpLlmClient? {
    if (!settings.liveLlmConfigured) return null
    return HttpLlmClient(
        Settings.DEFAULT_LIVE_LLM_BASE_URL,
        settings.liveLlmApiKey,
        Settings.DEFAULT_LIVE_LLM_MODEL,
        settings.speechLanguage,
    )
}
