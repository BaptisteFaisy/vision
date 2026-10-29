package dev.sam3vision.app.isee

import android.util.Log
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.segment.TwinLiteCue
import dev.sam3vision.core.segment.TwinLiteDirection
import dev.sam3vision.core.segment.TwinLiteText
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Canal vocal du guidage TwinLiteNet : la MÊME voix ElevenLabs que la
 * narration, lue par le MÊME lecteur partagé (jamais deux voix ensemble).
 *
 * Stratégie de latence (miroir d'`AnnouncementPlayer`) : clip du pack
 * ([VoicePack]) → lecture immédiate, zéro réseau ; sinon synthèse bornée
 * ([latencyBudgetMs]) → lecture + mise en cache ; trop lente → silence
 * cette fois (aucun son en retard qui couvre la piste) et remplissage
 * en arrière-plan pour la fois suivante. Voix changée ou clé absente :
 * le pack est ignoré, la synthèse runtime parle (même voix, plus lent).
 */
class TwinLiteVoice(
    private val play: suspend (File) -> Boolean,
    private val synth: suspend (String) -> ByteArray?,
    private val pack: VoicePack,
    private val scope: CoroutineScope,
    private val voiceId: () -> String,
    private val latencyBudgetMs: Long = NETWORK_LATENCY_BUDGET_MS,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    /**
     * Prononce [cue] dans [language] : vrai si un son est parti (clip ou
     * synthèse). Suspend au plus [latencyBudgetMs] (+ la mise en lecture).
     */
    suspend fun say(cue: TwinLiteCue, language: SpeechLanguage): Boolean {
        val id = idFor(cue.direction)
        val voice = voiceId()
        pack.clipFor(id, language, voice)?.let { return played(it, cue, "clip") }
        val phrase = TwinLiteText.phrase(cue.direction, language)
        val bytes = withTimeoutOrNull(latencyBudgetMs) { synth(phrase) }
        if (bytes == null) {
            scope.launch { pack.put(id, language, voice, synth(phrase)) }
            return false
        }
        val clip = pack.put(id, language, voice, bytes) ?: return false
        return played(clip, cue, "synth")
    }

    private suspend fun played(clip: File, cue: TwinLiteCue, via: String): Boolean {
        val ok = play(clip)
        log("twinlite: dit « ${cue.phrase} » (urgent=${cue.urgent}, $via) : $ok")
        return ok
    }

    companion object {
        private const val TAG = "sam3vision"

        /** Budget réseau par consigne : au-delà, silence + remplissage fond. */
        const val NETWORK_LATENCY_BUDGET_MS: Long = 500L

        /** Concept parlé → clip du pack (`vp_<id>_<lang>.mp3`). */
        fun idFor(direction: TwinLiteDirection): String = when (direction) {
            TwinLiteDirection.LEFT -> "tl_left"
            TwinLiteDirection.STRAIGHT -> "tl_straight"
            TwinLiteDirection.RIGHT -> "tl_right"
            TwinLiteDirection.STOP -> "tl_stop"
        }
    }
}
