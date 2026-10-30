package dev.sam3vision.app.glasses

import android.media.AudioManager
import android.media.ToneGenerator
import dev.sam3vision.app.PcmSource
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.DictationUseCase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Question entendue aux lunettes : le texte transcrit et le PCM 16 kHz capté. */
data class GlassesTalkHeard(val text: String, val pcm: ByteArray)

/**
 * Une question posée aux lunettes, du premier appui long à la transcription.
 *
 * Le contrôleur ne fait que relayer l'appui long (`press`) : ici vivent le
 * micro des lunettes (ouvert à la première bascule, coupé à la seconde), la
 * photo-contexte (captée en parallèle, comme `IseePhotoShot.captureForHold`),
 * le délai d'envoi automatique ([GlassesTalkPolicy.MAX_LISTEN_MS]) et la
 * transcription par le moteur de dictée de l'app (Meta puis Scribe).
 *
 * Testable en JVM : le micro, la photo, le moteur et les signaux sont injectés
 * — aucun SDK, aucun Android. Le délai est injectable ([listenMs]) pour ne pas
 * attendre 20 s dans les tests.
 */
class GlassesTalk(
    private val scope: CoroutineScope,
    /** Micro utilisable maintenant (`null` = session fermée, flux arrêté). */
    private val audio: () -> PcmSource?,
    /** Photo des lunettes pour le contexte de la question (`null` = texte seul). */
    private val snapPhoto: suspend () -> File?,
    /** Moteur de dictée de l'app (Meta puis Scribe, `null` = rien de configuré). */
    private val engine: (Settings) -> SpeechClient?,
    private val settings: () -> Settings,
    /** Signal sonore dans les lunettes (l'écran du téléphone est en poche). */
    private val beep: (GlassesTalkCue) -> Unit = {},
    /** Écoute en cours ou terminée : l'écran et iSee suivent. */
    private val onListening: (Boolean) -> Unit = {},
    /** Question prête : photo-contexte, texte transcrit, PCM capté. */
    private val onHeard: (photo: File?, text: String, pcm: ByteArray) -> Unit = { _, _, _ -> },
    /** Échec dit à l'utilisateur, jamais silencieux. */
    private val onNote: (GlassesMessage) -> Unit = {},
    private val listenMs: Long = GlassesTalkPolicy.MAX_LISTEN_MS,
) {
    private var phase = GlassesTalkPhase.IDLE
    private var mic: PcmSource? = null
    private var photo: File? = null
    private var timeout: Job? = null

    /** Vrai entre le premier appui long et l'envoi (ou l'abandon). */
    val listening: Boolean get() = phase == GlassesTalkPhase.LISTENING

    /** Vrai dès que l'écoute est ouverte, transcription comprise. */
    val busy: Boolean get() = phase != GlassesTalkPhase.IDLE

    /**
     * Un appui long sur le bouton capture : ouvre l'écoute, ou envoie la
     * question en cours. [TalkPress.MIC_UNAVAILABLE] = repli sur l'ancien
     * comportement (photo → chat sur le téléphone), décidé par l'appelant.
     */
    fun press(): TalkPress {
        val source = audio()
        return when (val decision = GlassesTalkPolicy.press(phase, micReady = source != null)) {
            TalkPress.STARTED -> if (start(source!!)) decision else TalkPress.MIC_UNAVAILABLE
            TalkPress.SENDING -> {
                send()
                decision
            }
            TalkPress.MIC_UNAVAILABLE, TalkPress.IGNORED -> decision
        }
    }

    /** Fin du délai : envoie si l'écoute est toujours en cours, sinon rien. */
    fun timeout() {
        if (GlassesTalkPolicy.timeout(phase)) send()
    }

    /** Abandon (session perdue, déconnexion) : micro rendu, question jetée. */
    fun cancel() {
        timeout?.cancel()
        timeout = null
        mic?.abort()
        mic = null
        photo = null
        if (phase != GlassesTalkPhase.IDLE) {
            phase = GlassesTalkPhase.IDLE
            onListening(false)
        }
    }

    /**
     * Recette sans voix (builds debug, extra adb `--es glasses_talk`) : injecte
     * une question comme si le micro l'avait entendue. Le mock n'ayant pas de
     * piste audio, c'est le seul chemin qui prouve la transcription aval.
     */
    fun injectTranscript(text: String) {
        if (text.isBlank()) return
        timeout?.cancel()
        timeout = null
        mic?.abort()
        mic = null
        val heardPhoto = photo
        photo = null
        phase = GlassesTalkPhase.IDLE
        onListening(false)
        onHeard(heardPhoto, text, SILENCE_PCM)
    }

    /** Ouvre le micro et l'écoute ; `false` = micro refusé, repli chez l'appelant. */
    private fun start(source: PcmSource): Boolean {
        if (!source.start()) {
            onNote(GlassesMessages.talkMicUnavailable())
            return false
        }
        mic = source
        phase = GlassesTalkPhase.LISTENING
        beep(GlassesTalkCue.LISTENING)
        onListening(true)
        timeout = scope.launch {
            delay(listenMs)
            timeout()
        }
        scope.launch { photo = runCatching { snapPhoto() }.getOrNull() }
        return true
    }

    private fun send() {
        phase = GlassesTalkPhase.SENDING
        timeout?.cancel()
        timeout = null
        beep(GlassesTalkCue.SENT)
        val active = mic
        mic = null
        scope.launch {
            val heardPhoto = photo
            photo = null
            val outcome = transcribe(active)
            phase = GlassesTalkPhase.IDLE
            outcome
                .onSuccess { onHeard(heardPhoto, it.text, it.pcm) }
                .onFailure { onNote(GlassesMessages.talkFailed(it.message ?: "dictée impossible")) }
            onListening(false)
        }
    }

    /** Micro coupé puis transcrit par le moteur de dictée de l'app. */
    private suspend fun transcribe(active: PcmSource?): Result<GlassesTalkHeard> {
        if (active == null) return Result.failure(IllegalStateException("micro lunettes perdu"))
        val pcm = active.stopAndCollect()
        val client = engine(settings())
            ?: return Result.failure(
                IllegalStateException("aucun moteur de dictée : clé Meta ou ElevenLabs (Réglages)"),
            )
        return DictationUseCase(client).invoke(pcm).map { text -> GlassesTalkHeard(text, pcm) }
    }

    private companion object {
        /** 1 s de silence 16 kHz mono : l'injection debug garde un MP3 valide. */
        val SILENCE_PCM = ByteArray(16_000 * 2)
    }
}

/**
 * Branchement mains-libres du contrôleur : `GlassesTalk` pur-JVM d'un côté,
 * état partagé et événements de l'autre. Le contrôleur n'y met que le relais.
 */
internal class GlassesTalkHost(
    scope: CoroutineScope,
    audio: () -> PcmSource?,
    snapPhoto: suspend () -> File?,
    engine: (Settings) -> SpeechClient?,
    settings: () -> Settings,
    beep: (GlassesTalkCue) -> Unit,
    private val state: MutableStateFlow<GlassesUiState>,
    private val events: MutableSharedFlow<GlassesEvent>,
    private val note: (GlassesMessage) -> Unit,
) {
    private val talk = GlassesTalk(
        scope = scope,
        audio = audio,
        snapPhoto = snapPhoto,
        engine = engine,
        settings = settings,
        beep = beep,
        onListening = ::onListening,
        onHeard = ::onHeard,
        onNote = note,
    )

    /** Un appui long : ouvre l'écoute, ou envoie la question en cours. */
    fun press(): TalkPress = talk.press()

    /** Vrai dès que l'écoute est ouverte, transcription comprise. */
    val busy: Boolean get() = talk.busy

    /** Abandon : micro rendu, question jetée. */
    fun cancel() = talk.cancel()

    /** Recette sans voix : injecte une question comme si le micro l'avait entendue. */
    fun inject(text: String) = talk.injectTranscript(text)

    private fun onListening(listening: Boolean) {
        state.update { it.copy(talkListening = listening) }
        if (listening) {
            note(GlassesMessages.talkListening())
            events.tryEmit(GlassesEvent.TalkListening)
        } else {
            events.tryEmit(GlassesEvent.TalkEnded)
        }
    }

    private fun onHeard(photo: File?, text: String, pcm: ByteArray) {
        events.tryEmit(GlassesEvent.VoiceHeard(photo, text, pcm))
    }
}

/**
 * Signaux sonores dans les lunettes (l'écran du téléphone est en poche) : un
 * bip = « parle », deux bips = « question envoyée ». `ToneGenerator` suit la
 * sortie système (A2DP des lunettes en session) — à confirmer à l'oreille.
 */
internal class GlassesTalkBeeps {
    private var tone: ToneGenerator? = null

    operator fun invoke(cue: GlassesTalkCue) {
        runCatching {
            val gen = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME).also { tone = it }
            val sound = if (cue == GlassesTalkCue.LISTENING) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2
            gen.startTone(sound, DURATION_MS)
        }
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
    }

    private companion object {
        const val VOLUME = 80
        const val DURATION_MS = 150
    }
}
