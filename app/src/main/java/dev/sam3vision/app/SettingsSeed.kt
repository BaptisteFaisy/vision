package dev.sam3vision.app

import dev.sam3vision.core.settings.SamRegion
import dev.sam3vision.core.settings.Settings

/**
 * Valeurs injectées au build (LLM, voix ElevenLabs, dictée Meta), lues dans le
 * `local.properties` gitignoré. [FromBuild] = ce que cet APK embarque.
 */
internal data class BuildDefaults(
    val llmBaseUrl: String = BuildConfig.LLM_BASE_URL,
    val llmApiKey: String = BuildConfig.LLM_API_KEY,
    val llmModel: String = BuildConfig.LLM_MODEL,
    val ttsApiKey: String = BuildConfig.TTS_API_KEY,
    val ttsVoiceId: String = BuildConfig.TTS_VOICE_ID,
    val asrApiKey: String = BuildConfig.ASR_API_KEY,
) {
    companion object {
        val FromBuild: BuildDefaults = BuildDefaults()
    }
}

/**
 * Ce que **cet APK** embarque, lu dans le `local.properties` (gitignoré) au moment du
 * build : c'est le seul chemin par lequel une clé entre dans l'app sans être tapée à la
 * main, donc une installation neuve n'a rien à saisir. Paramétrable pour que les tests
 * n'aient pas à dépendre du `local.properties` du poste.
 *
 * Règle : une valeur déjà saisie dans Réglages gagne toujours, jamais écrasée par
 * le build. Utilisé par **toutes** les vues qui parlent ou appellent un backend —
 * `AppViewModel` (chat, analyse, dictée) comme `LiveViewModel` (mode Direct) :
 * sinon le Direct serait muet alors que le chat parle.
 */
fun seededFromBuild(loaded: Settings): Settings = seeded(loaded, BuildDefaults.FromBuild)

/** Même chose, avec les valeurs de build passées explicitement (tests JVM). */
internal fun seeded(loaded: Settings, build: BuildDefaults): Settings = withTtsDefaults(
    seedAsr(seedVoice(withLlmKey(dropPresetUrl(loaded), build), build), build),
)

/**
 * Une URL SAM qui n'est que le preset d'une région (injectée par ce build, ou persistée
 * par une version antérieure) ne doit pas figer le choix Europe/États-Unis : on l'efface
 * et laisse la région des Réglages parler. Une URL saisie à la main est conservée.
 */
internal fun dropPresetUrl(loaded: Settings): Settings =
    if (SamRegion.isPreset(loaded.samBaseUrl)) loaded.copy(samBaseUrl = "") else loaded

/** Clé LLM de build : uniquement si rien n'a encore été saisi. */
internal fun withLlmKey(loaded: Settings, build: BuildDefaults): Settings {
    if (loaded.llmApiKey.isNotBlank() || build.llmApiKey.isBlank()) return loaded
    return loaded.copy(
        llmApiKey = build.llmApiKey,
        llmBaseUrl = loaded.llmBaseUrl.ifBlank { build.llmBaseUrl },
        llmModel = loaded.llmModel.ifBlank { build.llmModel },
    )
}

/** Clé de dictée Meta de build : sans elle, la dictée retombe sur ElevenLabs Scribe. */
internal fun seedAsr(loaded: Settings, build: BuildDefaults): Settings {
    if (loaded.asrApiKey.isNotBlank() || build.asrApiKey.isBlank()) return loaded
    return loaded.copy(asrApiKey = build.asrApiKey)
}

/** Clé ElevenLabs de build : voix du chat, de la dictée et de l'annonce des positions. */
internal fun seedVoice(loaded: Settings, build: BuildDefaults): Settings {
    if (loaded.ttsApiKey.isNotBlank() || build.ttsApiKey.isBlank()) return loaded
    return loaded.copy(
        ttsApiKey = build.ttsApiKey,
        ttsVoiceId = loaded.ttsVoiceId.ifBlank { build.ttsVoiceId },
    )
}