package dev.sam3vision.app.isee

import android.content.Context
import android.os.SystemClock
import dev.sam3vision.app.segment.LiveSegmentMeter
import dev.sam3vision.app.segment.SegmentMeter
import dev.sam3vision.core.segment.TwinLiteAdvice
import dev.sam3vision.core.segment.TwinLiteAnnouncer
import dev.sam3vision.core.segment.TwinLiteCue
import dev.sam3vision.core.segment.TwinLiteGuide
import dev.sam3vision.core.segment.TwinLiteText
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Overlay TwinLite à afficher : [arrow] le glyphe de direction (« ↑ », « ← », « → »,
 * « ■ »), [label] la consigne parlée (source unique : [TwinLiteText]).
 */
data class TwinLiteUi(val arrow: String, val label: String) {
    companion object {
        /** Flèche + libellé pour un conseil, dans la langue de la voix. */
        fun forAdvice(advice: TwinLiteAdvice, language: SpeechLanguage): TwinLiteUi =
            TwinLiteUi(advice.direction.arrow, TwinLiteText.phrase(advice.direction, language))
    }
}

/**
 * Câblage TwinLiteNet de la caméra iSee : les mêmes frames JPEG que SAM nourrissent le
 * mètre embarqué, et l'overlay suit le masque praticable frais — la voix, elle,
 * ne parle qu'aux changements (annonceur). 100 % téléphone, 0 GPU Modal.
 *
 * Le masque périmé cache l'overlay et tait la voix : une flèche obsolète (« tout
 * droit » vers un mur) est pire que pas de flèche.
 */
class TwinLiteWiring(
    private val meter: SegmentMeter,
    private val announcer: TwinLiteAnnouncer = TwinLiteAnnouncer(),
    private val voice: TwinLiteVoice? = null,
    private val scope: CoroutineScope? = null,
    private val language: () -> SpeechLanguage = { SpeechLanguage.DEFAULT },
    private val rate: () -> Float = { Settings.DEFAULT_TTS_SPEED },
) {
    /** Câblage de production : mètre réel LiteRT sur le scope du ViewModel. */
    constructor(
        scope: CoroutineScope,
        appContext: Context,
        voice: TwinLiteVoice,
        language: () -> SpeechLanguage = { SpeechLanguage.DEFAULT },
        rate: () -> Float = { Settings.DEFAULT_TTS_SPEED },
    ) : this(LiveSegmentMeter(scope, appContext), TwinLiteAnnouncer(), voice, scope, language, rate)

    private val _ui = MutableStateFlow<TwinLiteUi?>(null)

    /** Overlay courant (`null` = caché : pas de masque frais). */
    val ui: StateFlow<TwinLiteUi?> = _ui

    private var lastTickMs = 0L

    /**
     * Frame à segmenter (même JPEG que SAM) ; l'overlay suit au plus à [TICK_MS].
     * Le tick lit le masque précédent (l'inférence court en fond, ~1 cycle de retard).
     */
    fun offerFrame(jpegB64: String) {
        if (jpegB64.isBlank()) return
        meter.offer(jpegB64)
        val now = SystemClock.elapsedRealtime()
        if (now - lastTickMs < TICK_MS) return
        tickUi(now, language())
    }

    /**
     * Overlay depuis le dernier masque frais : direction → flèche (toujours), conseil →
     * voix (aux changements seuls). `null` = masque périmé (silence, overlay caché).
     */
    fun tickUi(nowMs: Long, language: SpeechLanguage): TwinLiteUi? {
        lastTickMs = nowMs
        val mask = meter.latestFresh()
        if (mask == null) {
            _ui.value = null
            announcer.onAdvice(null, nowMs, language)
            return null
        }
        val advice = TwinLiteGuide.directionFor(mask)
        val shown = TwinLiteUi.forAdvice(advice, language)
        _ui.value = shown
        val cue: TwinLiteCue? = announcer.onAdvice(advice, nowMs, language)
        if (cue != null) say(cue, language)
        return shown
    }

    /** Nouvelle session : masque, annonceur et overlay oubliés (moteur gardé chaud). */
    fun reset() {
        meter.reset()
        announcer.reset()
        lastTickMs = 0L
        _ui.value = null
    }

    /** Fin de vie : moteur LiteRT fermé (terminal). */
    fun close() {
        meter.close()
        announcer.reset()
        _ui.value = null
    }

    /** Prononce [cue] en fond (ou sur place sans scope, en test). */
    private fun say(cue: TwinLiteCue, language: SpeechLanguage) {
        val target = voice ?: return
        val runner = scope
        if (runner == null) {
            runCatching { target.say(cue, language, rate()) }
            return
        }
        runner.launch { runCatching { target.say(cue, language, rate()) } }
    }

    companion object {
        private const val TICK_MS = 300L
    }
}
