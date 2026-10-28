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
    val liveLlmApiKey: String = BuildConfig.LIVE_LLM_API_KEY,
    val googleMapsApiKey: String = BuildConfig.GOOGLE_MAPS_API_KEY,
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
internal fun seeded(loaded: Settings, build: BuildDefaults): Settings = seedGoogleMaps(withTtsDefaults(
    seedAsr(seedLiveLlm(migrateLegacyVoice(seedVoice(withLlmKey(migrateLegacyLlm(dropPresetUrl(loaded), build), build), build), build), build), build),
), build)

/** L'installation et la mise à jour remplissent la clé de carte tant qu'elle est vide. */
internal fun seedGoogleMaps(loaded: Settings, build: BuildDefaults): Settings =
    if (loaded.googleMapsApiKey.isNotBlank() || build.googleMapsApiKey.isBlank()) loaded
    else loaded.copy(googleMapsApiKey = build.googleMapsApiKey)

/**
 * Clé de **narration du Direct** (même modèle Meta que les questions, clé dédiée) : elle
 * ne sert qu'à la narration. Sans elle, le Direct se tait — c'est le sens du réglage.
 */
internal fun seedLiveLlm(loaded: Settings, build: BuildDefaults): Settings {
    if (loaded.liveLlmApiKey.isNotBlank() || build.liveLlmApiKey.isBlank()) return loaded
    return loaded.copy(liveLlmApiKey = build.liveLlmApiKey)
}

/** Ancien défaut du code (InferHub) et son modèle : ce qu'une app d'avant migre. */
private const val LEGACY_LLM_BASE_URL = "https://api.inferhub.dev/v1"
private const val LEGACY_LLM_MODEL = "ali/qwen3.8-omni-flash"

/** Le réglage est-il resté sur l'ancien défaut, sans choix de l'utilisateur ? */
private fun Settings.onLegacyLlm(): Boolean =
    llmBaseUrl.trim().trimEnd('/').equals(LEGACY_LLM_BASE_URL, ignoreCase = true) &&
        (llmModel.isBlank() || llmModel == LEGACY_LLM_MODEL)

/**
 * Migration silencieuse : un réglage resté sur l'ancien défaut (InferHub + qwen) n'a
 * jamais été choisi, on l'aligne donc sur le défaut de **ce** build — base, modèle **et**
 * clé, sinon la clé InferHub partirait chez Baseten et répondrait 401. Une base URL saisie
 * à la main n'est jamais touchée : seule la valeur d'usine est reconnue.
 *
 * Garde d'épinglage : un modèle déjà épinglé (le défaut Meta) n'est **jamais** réécrit,
 * même si sa base ressemble à l'ancienne — la migration ne défait pas l'épinglage.
 *
 * C'est ce qui évite toute manipulation après une mise à jour de l'APK.
 */
internal fun migrateLegacyLlm(loaded: Settings, build: BuildDefaults): Settings {
    if (isPinnedLlmModel(loaded.llmModel)) return loaded
    if (!loaded.onLegacyLlm()) return loaded
    return loaded.copy(
        llmBaseUrl = build.llmBaseUrl.ifBlank { Settings.DEFAULT_LLM_BASE_URL },
        llmModel = build.llmModel.ifBlank { Settings.DEFAULT_LLM_MODEL },
        llmApiKey = build.llmApiKey.ifBlank { loaded.llmApiKey },
    )
}

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

/**
 * Migration silencieuse : un réglage resté sur l'ancien défaut (Sarah) n'a jamais
 * été choisi, on l'aligne sur la voix de **ce** build (ou Laure à défaut). Une voix
 * saisie à la main n'est jamais touchée : seul l'ancien défaut est reconnu.
 */
internal fun migrateLegacyVoice(loaded: Settings, build: BuildDefaults): Settings {
    if (loaded.ttsVoiceId != Settings.SARAH_VOICE_ID) return loaded
    return loaded.copy(ttsVoiceId = build.ttsVoiceId.ifBlank { Settings.LAURE_VOICE_ID })
}
