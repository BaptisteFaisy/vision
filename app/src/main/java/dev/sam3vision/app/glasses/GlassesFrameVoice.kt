package dev.sam3vision.app.glasses

import android.content.Context
import android.util.Log
import dev.sam3vision.app.VoiceAnnouncer
import dev.sam3vision.app.llmClient
import dev.sam3vision.app.samClientOrNull
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AnalyzeUseCase

/** Journal logcat de la collecte des frames (`adb logcat -s GlassesDirect`). */
private const val FRAME_LOG_TAG = "GlassesDirect"

/** Dossier des MP3 des descriptions lues sur le haut-parleur. */
private const val FRAMES_VOICE_DIR = "glasses-frames"

/**
 * Voix des descriptions collectées : le maillon « frame → analyse → voix »,
 * extrait du contrôleur (limite 500 lignes). Clients uniques de l'app
 * ([llmClient], [samClientOrNull]), réglages relus à chaque frame pour suivre
 * les clés saisies en cours de session.
 */
internal class GlassesFrameVoice(
    context: Context,
    private val settings: () -> Settings,
) {
    /** Voix des descriptions collectées : le haut-parleur, comme le reste de l'app. */
    private val voice = VoiceAnnouncer(context.applicationContext, FRAMES_VOICE_DIR)

    private val step = GlassesAnalysisStep(
        analyze = { base64 ->
            val current = settings()
            AnalyzeUseCase(llmClient(current), samClientOrNull(current)).invoke(base64)
        },
        say = { text ->
            val current = settings()
            voice.say(current, text)
            when {
                current.ttsApiKey.isBlank() -> "voix coupée (clé TTS absente)"
                GlassesVoiceRoute.targetsGlasses -> "lunettes"
                else -> "haut-parleur"
            }
        },
    )

    /** Frame collectée → analyse partagée (SAM/LLM) → voix ; le sort est en journal. */
    suspend fun onFrame(frame: JpegFrame) {
        step.onFrame(frame, ::frameLog)
    }

    /**
     * Journal de la collecte : logcat, pas le bandeau (une ligne par frame noierait l'écran).
     * Partagé avec le contrôleur (`onFrameLog`) : même journal qu'avant l'extraction.
     */
    internal fun frameLog(line: String) {
        Log.d(FRAME_LOG_TAG, line)
    }

    /** Fin de session : la voix en cours se tait. */
    fun stop() {
        voice.stop()
    }

    /** Libération finale (activité détruite). */
    fun release() {
        voice.release()
    }
}
