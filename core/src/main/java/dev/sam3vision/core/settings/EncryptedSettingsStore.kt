package dev.sam3vision.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_FILE = "sam3vision_secure_settings"

/**
 * Stockage chiffré (Jetpack security-crypto : AES256-SIV pour les clés,
 * AES256-GCM pour les valeurs, maître dans le Keystore matériel).
 *
 * ⚠️ Parité obligatoire avec [SettingsCodec] (repli fichier chiffré) : un champ
 * ajouté à [Settings] doit être écrit **ici** (`save`) et relu **là-bas** (`load`),
 * sinon il retombe silencieusement sur son défaut à chaque redémarrage. La garde est
 * exécutée par `SettingsStoreParityTest` (une clé `KEY_` par champ de [Settings]).
 */
class EncryptedSettingsStore(context: Context) : SettingsStore {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { createPrefs(appContext) }

    override suspend fun load(): Settings = withContext(Dispatchers.IO) {
        val default = Settings.Default
        Settings(
            llmBaseUrl = prefs.getString(KEY_LLM_BASE_URL, default.llmBaseUrl).orEmpty()
                .ifBlank { default.llmBaseUrl },
            llmApiKey = prefs.getString(KEY_LLM_API_KEY, default.llmApiKey).orEmpty(),
            llmModel = prefs.getString(KEY_LLM_MODEL, default.llmModel).orEmpty()
                .ifBlank { default.llmModel },
            samBaseUrl = prefs.getString(KEY_SAM_BASE_URL, default.samBaseUrl).orEmpty(),
            samRegion = SamRegion.fromName(prefs.getString(KEY_SAM_REGION, default.samRegion.name)),
            speechLanguage = SpeechLanguage.fromName(
                prefs.getString(KEY_SPEECH_LANGUAGE, default.speechLanguage.name),
            ),
            ttsApiKey = prefs.getString(KEY_TTS_API_KEY, default.ttsApiKey).orEmpty(),
            ttsVoiceId = prefs.getString(KEY_TTS_VOICE_ID, default.ttsVoiceId).orEmpty()
                .ifBlank { default.ttsVoiceId },
            ttsAutoplay = prefs.getBoolean(KEY_TTS_AUTOPLAY, default.ttsAutoplay),
            ttsAnnouncePositions = prefs.getBoolean(KEY_TTS_ANNOUNCE_POSITIONS, default.ttsAnnouncePositions),
            // `.ifBlank` : une valeur vide persistée par une version antérieure retombe sur
            // le défaut au lieu de laisser un endpoint invalide (origin/master, commit f77c61c).
            asrBaseUrl = prefs.getString(KEY_ASR_BASE_URL, default.asrBaseUrl).orEmpty()
                .ifBlank { default.asrBaseUrl },
            asrApiKey = prefs.getString(KEY_ASR_API_KEY, default.asrApiKey).orEmpty(),
            asrModel = prefs.getString(KEY_ASR_MODEL, default.asrModel).orEmpty()
                .ifBlank { default.asrModel },
            introEnabled = prefs.getBoolean(KEY_INTRO_ENABLED, default.introEnabled),
            introPlayed = prefs.getBoolean(KEY_INTRO_PLAYED, default.introPlayed),
            distanceOverlayEnabled = prefs.getBoolean(KEY_DISTANCE_OVERLAY, default.distanceOverlayEnabled),
            liveSamEnabled = prefs.getBoolean(KEY_LIVE_SAM, default.liveSamEnabled),
            cameraFovDeg = prefs.getFloat(KEY_CAMERA_FOV, default.cameraFovDeg)
                .takeIf { it > 0f } ?: default.cameraFovDeg,
            maxObjects = ObjectLimits.clamp(
                prefs.getInt(KEY_MAX_OBJECTS, default.maxObjects),
            ),
        )
    }

    override suspend fun save(s: Settings) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_LLM_BASE_URL, s.llmBaseUrl)
            .putString(KEY_LLM_API_KEY, s.llmApiKey)
            .putString(KEY_LLM_MODEL, s.llmModel)
            .putString(KEY_SAM_BASE_URL, s.samBaseUrl)
            .putString(KEY_SAM_REGION, s.samRegion.name)
            .putString(KEY_SPEECH_LANGUAGE, s.speechLanguage.name)
            .putString(KEY_TTS_API_KEY, s.ttsApiKey)
            .putString(KEY_TTS_VOICE_ID, s.ttsVoiceId)
            .putBoolean(KEY_TTS_AUTOPLAY, s.ttsAutoplay)
            .putBoolean(KEY_TTS_ANNOUNCE_POSITIONS, s.ttsAnnouncePositions)
            .putString(KEY_ASR_BASE_URL, s.asrBaseUrl)
            .putString(KEY_ASR_API_KEY, s.asrApiKey)
            .putString(KEY_ASR_MODEL, s.asrModel)
            .putBoolean(KEY_INTRO_ENABLED, s.introEnabled)
            .putBoolean(KEY_INTRO_PLAYED, s.introPlayed)
            .putBoolean(KEY_DISTANCE_OVERLAY, s.distanceOverlayEnabled)
            .putBoolean(KEY_LIVE_SAM, s.liveSamEnabled)
            .putFloat(KEY_CAMERA_FOV, s.cameraFovDeg)
            .putInt(KEY_MAX_OBJECTS, s.objectsOnScreen)
            .apply()
    }

    companion object {
        const val KEY_LLM_BASE_URL = "llm_base_url"
        const val KEY_LLM_API_KEY = "llm_api_key"
        const val KEY_LLM_MODEL = "llm_model"
        const val KEY_SAM_BASE_URL = "sam_base_url"
        const val KEY_MAX_OBJECTS = "max_objects"
        const val KEY_SAM_REGION = "sam_region"
        const val KEY_SPEECH_LANGUAGE = "speech_language"
        const val KEY_TTS_API_KEY = "tts_api_key"
        const val KEY_TTS_VOICE_ID = "tts_voice_id"
        const val KEY_TTS_AUTOPLAY = "tts_autoplay"
        const val KEY_TTS_ANNOUNCE_POSITIONS = "tts_announce_positions"
        const val KEY_ASR_BASE_URL = "asr_base_url"
        const val KEY_ASR_API_KEY = "asr_api_key"
        const val KEY_ASR_MODEL = "asr_model"
        const val KEY_INTRO_ENABLED = "intro_enabled"
        const val KEY_INTRO_PLAYED = "intro_played"
        const val KEY_DISTANCE_OVERLAY = "distance_overlay"
        const val KEY_LIVE_SAM = "live_sam"
        const val KEY_CAMERA_FOV = "camera_fov_deg"

        /** Lève si le Keystore est indisponible : le factory retombe alors sur un fichier chiffré. */
        internal fun createPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
