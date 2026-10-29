package dev.sam3vision.app.isee

import android.app.Application
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.segment.LiveSegmentMeter
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.segment.TwinLiteAnnouncer
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * TwinLiteNet de l'écran iSee, hors du ViewModel (limite 500 lignes) : câblage
 * flèche + voix ElevenLabs (pack MP3, lecteur partagé — une seule voix).
 *
 * Le lecteur est celui de l'écran (jamais deux voix ensemble) ; la synthèse
 * runtime utilise la clé et la voix des réglages, comme la narration.
 */
class IseeTwinLite(
    app: Application,
    scope: CoroutineScope,
    settings: () -> Settings,
    player: SpeechPlayer,
    muted: () -> Boolean = { false },
) {
    private val pack = VoicePack(
        filesDir = app.filesDir,
        packVoiceId = {
            runCatching {
                app.assets.open("vp_voice.txt").bufferedReader().readText()
            }.getOrNull()
        },
        bundledClip = { name ->
            runCatching {
                val id = app.resources.getIdentifier(
                    name.removeSuffix(".mp3"), "raw", app.packageName)
                if (id == 0) null else app.resources.openRawResource(id).readBytes()
            }.getOrNull()
        },
    )
    private val voice = TwinLiteVoice(
        play = { player.playFile(it).isSuccess },
        synth = { text ->
            val current = settings()
            if (current.ttsApiKey.isBlank()) null
            else ElevenLabsTtsClient(current.ttsApiKey, current.ttsVoiceId)
                .synthesize(text).getOrNull()
        },
        pack = pack,
        scope = scope,
        voiceId = { settings().ttsVoiceId },
    )
    private val wiring = TwinLiteWiring(
        LiveSegmentMeter(scope, app),
        TwinLiteAnnouncer(),
        voice,
        scope,
        { settings().speechLanguage },
        muted,
    )

    /** Overlay courant (`null` = caché : pas de masque frais). */
    val ui: StateFlow<TwinLiteUi?> = wiring.ui

    /** Frame à segmenter (même JPEG que SAM). */
    fun offerFrame(jpegB64: String) = wiring.offerFrame(jpegB64)

    /** Nouvelle session : masque, annonceur et overlay oubliés (moteur gardé chaud). */
    fun reset() = wiring.reset()

    /** Fin de vie : segmentation fermée (lecteur gardé : il est à l'écran). */
    fun close() = wiring.close()
}
