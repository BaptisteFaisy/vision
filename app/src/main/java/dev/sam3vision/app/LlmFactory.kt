package dev.sam3vision.app

import dev.sam3vision.core.net.InferHubLlmClient
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.settings.Settings

/**
 * Client LLM de l'app : **une seule façon** de le construire, pour que la langue de la
 * voix (`Settings.speechLanguage`) soit toujours celle des réponses.
 *
 * La réponse du modèle est lue telle quelle par ElevenLabs : si le modèle répond en
 * français pendant que la voix est anglaise, on l'entend tout de suite.
 */
fun llmClient(settings: Settings): LlmClient = inferHub(settings)

/**
 * Même client, type concret : le Direct s'en sert pour la **narration continue**
 * (`LiveLlmClient`), qui n'est pas dans l'interface [LlmClient].
 */
fun inferHub(settings: Settings): InferHubLlmClient = InferHubLlmClient(
    settings.llmBaseUrl,
    settings.llmApiKey,
    settings.llmModel,
    settings.speechLanguage,
)
