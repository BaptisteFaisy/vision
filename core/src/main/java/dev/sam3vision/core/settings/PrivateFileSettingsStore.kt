package dev.sam3vision.core.settings

import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plan B si security-crypto est indisponible : fichier privé (`filesDir`) chiffré
 * maison en AES-256/GCM, clé générée dans l'Android Keystore (non exportable).
 * Format : `[IV 12 octets][ciphertext][tag 16 octets]` — voir [AesGcm].
 *
 * Le chiffreur est injectable ([KeystoreSettingsCipher] sur le téléphone, un jeu de
 * clés aléatoire dans les tests JVM) : le format, le mode fichier et le comportement
 * en cas de fichier abîmé sont ainsi prouvés sans Android.
 */
class PrivateFileSettingsStore(
    private val file: File,
    private val cipher: SettingsCipher = KeystoreSettingsCipher(),
) : SettingsStore {

    override suspend fun load(): Settings = withContext(Dispatchers.IO) { read() }

    override suspend fun save(s: Settings) = withContext(Dispatchers.IO) {
        write(s)
    }

    /**
     * Fichier absent, illisible, chiffré par une clé qui n'est plus la bonne ou dont
     * le tag ne valide pas ⇒ [Settings.Default]. Jamais d'exception : un démarrage
     * planté sur des réglages abîmés est plus grave que des réglages à ressaisir.
     */
    private fun read(): Settings = try {
        if (!file.exists()) {
            Settings.Default
        } else {
            cipher.decrypt(file.readBytes())
                ?.let { SettingsCodec.decode(it.toString(Charsets.UTF_8)) }
                ?: Settings.Default
        }
    } catch (e: Exception) {
        Settings.Default
    }

    /**
     * Écriture atomique et restreinte : `*.tmp` en 0600 puis renommage. Une coupure
     * au milieu de l'écriture laisse l'ancien fichier intact, et le fichier posé sur
     * disque ne contient jamais la clé en clair ni hors du fichier, ni en travers.
     */
    private fun write(s: Settings) {
        val bytes = cipher.encrypt(SettingsCodec.encode(s).toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeBytes(bytes)
            restrictToOwner(tmp)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
            }
        } finally {
            tmp.delete()
        }
        restrictToOwner(file)
    }
}

/**
 * 0600 : le fichier de réglages n'appartient qu'au propriétaire. `filesDir` est déjà
 * privé sur Android, mais l'umask du processus n'est pas un contrat — sur un poste de
 * développement (et dans les tests JVM) le fichier sortirait en 0644.
 */
private fun restrictToOwner(target: File) {
    target.setReadable(false, false)
    target.setWritable(false, false)
    target.setExecutable(false, false)
    target.setReadable(true, true)
    target.setWritable(true, true)
}

/**
 * Sérialisation `clé=valeur`, une par ligne (pas de dépendance, un seul format).
 *
 * Le format n'a **jamais** changé et ne change pas ici : il est écrit chiffré sur le
 * disque, donc un fichier posé par une version antérieure — qui ne connaît pas
 * `ttsApiKey`, `asrApiKey`, `samRegion` ou `speechLanguage` — se relit toujours,
 * les champs manquants reprenant leur défaut (voir [decode] et le test de migration).
 * Les noms de champs sont stables : renommer l'un d'eux perdrait les réglages posés
 * en base par les utilisateurs.
 */
internal object SettingsCodec {
    private const val K_LLM_BASE_URL = "llmBaseUrl"
    private const val K_LLM_API_KEY = "llmApiKey"
    private const val K_LLM_MODEL = "llmModel"
    private const val K_SAM_BASE_URL = "samBaseUrl"
    private const val K_SAM_REGION = "samRegion"
    private const val K_SPEECH_LANGUAGE = "speechLanguage"
    private const val K_TTS_API_KEY = "ttsApiKey"
    private const val K_TTS_VOICE_ID = "ttsVoiceId"
    private const val K_TTS_SPEED = "ttsSpeed"
    private const val K_TTS_AUTOPLAY = "ttsAutoplay"
    private const val K_TTS_ANNOUNCE_POSITIONS = "ttsAnnouncePositions"
    private const val K_TTS_GUIDANCE_STRAIGHT = "ttsGuidanceStraight"
    private const val K_ASR_BASE_URL = "asrBaseUrl"
    private const val K_ASR_API_KEY = "asrApiKey"
    private const val K_ASR_MODEL = "asrModel"
    private const val K_LIVE_LLM_API_KEY = "liveLlmApiKey"
    private const val K_INTRO_ENABLED = "introEnabled"
    private const val K_INTRO_PLAYED = "introPlayed"
    private const val K_DISTANCE_OVERLAY = "distanceOverlayEnabled"
    private const val K_LIVE_SAM = "liveSamEnabled"
    private const val K_BOX_PREDICTION = "boxPredictionEnabled"
    private const val K_LOCAL_OVERLAY = "localOverlayEnabled"
    private const val K_NAVIGATION_GUIDANCE = "navigationGuidanceEnabled"
    private const val K_CAMERA_FOV = "cameraFovDeg"
    private const val K_CAREGIVER_ROLE = "caregiverRole"
    private const val K_PARENT_SITE_URL = "parentSiteUrl"
    private const val K_SHARE_ENABLED = "shareEnabled"
    private const val K_GOOGLE_MAPS_API_KEY = "googleMapsApiKey"
    private const val K_SHARE_ID = "shareId"
    private const val K_SHARE_WRITE_KEY = "shareWriteKey"
    private const val K_CAREGIVER_INVITE = "caregiverInvite"
    private const val K_MAX_OBJECTS = "maxObjects"

    fun encode(s: Settings): String = buildString {
        appendLine("$K_LLM_BASE_URL=${s.llmBaseUrl}")
        appendLine("$K_LLM_API_KEY=${s.llmApiKey}")
        appendLine("$K_LLM_MODEL=${s.llmModel}")
        appendLine("$K_SAM_BASE_URL=${s.samBaseUrl}")
        appendLine("$K_SAM_REGION=${s.samRegion.name}")
        appendLine("$K_SPEECH_LANGUAGE=${s.speechLanguage.name}")
        appendLine("$K_TTS_API_KEY=${s.ttsApiKey}")
        appendLine("$K_TTS_VOICE_ID=${s.ttsVoiceId}")
        appendLine("$K_TTS_SPEED=${s.ttsSpeed}")
        appendLine("$K_TTS_AUTOPLAY=${s.ttsAutoplay}")
        appendLine("$K_TTS_ANNOUNCE_POSITIONS=${s.ttsAnnouncePositions}")
        appendLine("$K_TTS_GUIDANCE_STRAIGHT=${s.ttsGuidanceStraight}")
        appendLine("$K_ASR_BASE_URL=${s.asrBaseUrl}")
        appendLine("$K_ASR_API_KEY=${s.asrApiKey}")
        appendLine("$K_ASR_MODEL=${s.asrModel}")
        appendLine("$K_LIVE_LLM_API_KEY=${s.liveLlmApiKey}")
        appendLine("$K_INTRO_ENABLED=${s.introEnabled}")
        appendLine("$K_INTRO_PLAYED=${s.introPlayed}")
        appendLine("$K_DISTANCE_OVERLAY=${s.distanceOverlayEnabled}")
        appendLine("$K_LIVE_SAM=${s.liveSamEnabled}")
        appendLine("$K_BOX_PREDICTION=${s.boxPredictionEnabled}")
        appendLine("$K_LOCAL_OVERLAY=${s.localOverlayEnabled}")
        appendLine("$K_NAVIGATION_GUIDANCE=${s.navigationGuidanceEnabled}")
        appendLine("$K_CAMERA_FOV=${s.cameraFovDeg}")
        appendLine("$K_CAREGIVER_ROLE=${s.caregiverRole.name}")
        appendLine("$K_PARENT_SITE_URL=${s.parentSiteUrl}")
        appendLine("$K_SHARE_ENABLED=${s.shareEnabled}")
        appendLine("$K_GOOGLE_MAPS_API_KEY=${s.googleMapsApiKey}")
        appendLine("$K_SHARE_ID=${s.shareId}")
        appendLine("$K_SHARE_WRITE_KEY=${s.shareWriteKey}")
        appendLine("$K_CAREGIVER_INVITE=${s.caregiverInvite}")
        appendLine("$K_MAX_OBJECTS=${s.objectsOnScreen}")
    }

    /** Tolérant : une clé absente (ancien fichier) reprend sa valeur par défaut. */
    fun decode(text: String): Settings {
        val values = text.lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=') }
        val default = Settings.Default
        return Settings(
            llmBaseUrl = values[K_LLM_BASE_URL].orEmpty().ifBlank { default.llmBaseUrl },
            llmApiKey = values[K_LLM_API_KEY].orEmpty(),
            llmModel = values[K_LLM_MODEL].orEmpty().ifBlank { default.llmModel },
            samBaseUrl = values[K_SAM_BASE_URL].orEmpty(),
            samRegion = SamRegion.fromName(values[K_SAM_REGION]),
            speechLanguage = SpeechLanguage.fromName(values[K_SPEECH_LANGUAGE]),
            ttsApiKey = values[K_TTS_API_KEY].orEmpty(),
            ttsVoiceId = values[K_TTS_VOICE_ID].orEmpty().ifBlank { default.ttsVoiceId },
            ttsSpeed = values[K_TTS_SPEED]?.toFloatOrNull()?.let(Settings::clampTtsSpeed)
                ?: default.ttsSpeed,
            ttsAutoplay = values[K_TTS_AUTOPLAY]?.toBooleanStrictOrNull() ?: default.ttsAutoplay,
            ttsAnnouncePositions = values[K_TTS_ANNOUNCE_POSITIONS]?.toBooleanStrictOrNull()
                ?: default.ttsAnnouncePositions,
            ttsGuidanceStraight = values[K_TTS_GUIDANCE_STRAIGHT]?.toBooleanStrictOrNull()
                ?: default.ttsGuidanceStraight,
            asrBaseUrl = values[K_ASR_BASE_URL].orEmpty().ifBlank { default.asrBaseUrl },
            asrApiKey = values[K_ASR_API_KEY].orEmpty(),
            asrModel = values[K_ASR_MODEL].orEmpty().ifBlank { default.asrModel },
            liveLlmApiKey = values[K_LIVE_LLM_API_KEY].orEmpty(),
            introEnabled = values[K_INTRO_ENABLED]?.toBooleanStrictOrNull() ?: default.introEnabled,
            introPlayed = values[K_INTRO_PLAYED]?.toBooleanStrictOrNull() ?: default.introPlayed,
            distanceOverlayEnabled = values[K_DISTANCE_OVERLAY]?.toBooleanStrictOrNull()
                ?: default.distanceOverlayEnabled,
            liveSamEnabled = values[K_LIVE_SAM]?.toBooleanStrictOrNull() ?: default.liveSamEnabled,
            boxPredictionEnabled = values[K_BOX_PREDICTION]?.toBooleanStrictOrNull()
                ?: default.boxPredictionEnabled,
            localOverlayEnabled = values[K_LOCAL_OVERLAY]?.toBooleanStrictOrNull()
                ?: default.localOverlayEnabled,
            navigationGuidanceEnabled = values[K_NAVIGATION_GUIDANCE]?.toBooleanStrictOrNull()
                ?: default.navigationGuidanceEnabled,
            cameraFovDeg = values[K_CAMERA_FOV]?.toFloatOrNull()?.takeIf { it > 0f }
                ?: default.cameraFovDeg,
            maxObjects = ObjectLimits.clamp(
                values[K_MAX_OBJECTS]?.toIntOrNull() ?: default.maxObjects,
            ),
        ).let { decodeShare(values, it) }
    }

    private fun decodeShare(values: Map<String, String>, default: Settings): Settings = default.copy(
        caregiverRole = CaregiverRole.fromName(values[K_CAREGIVER_ROLE]),
        parentSiteUrl = values[K_PARENT_SITE_URL].orEmpty().ifBlank { default.parentSiteUrl },
        shareEnabled = values[K_SHARE_ENABLED]?.toBooleanStrictOrNull() ?: default.shareEnabled,
        googleMapsApiKey = values[K_GOOGLE_MAPS_API_KEY].orEmpty(),
        shareId = values[K_SHARE_ID].orEmpty().ifBlank { default.shareId },
        shareWriteKey = values[K_SHARE_WRITE_KEY].orEmpty(),
        caregiverInvite = values[K_CAREGIVER_INVITE].orEmpty(),
    )
}
