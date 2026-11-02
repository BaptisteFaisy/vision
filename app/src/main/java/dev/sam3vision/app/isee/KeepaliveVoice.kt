package dev.sam3vision.app.isee

import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keepalive de positionnement (ordre user du 02/11 : en session iSee active,
 * la voix ne se tait JAMAIS) : le repli 12 ([FallbackVoice.offer]) est PULL
 * (il lui faut une émission sonde) — GPU qui cale, plus d'émissions, silence
 * total. Ce keepalive est PUSH : quand le bus est silencieux depuis
 * [SILENCE_MS], il poste le mot de positionnement courant au niveau 12 (le
 * plus bas dehors — le bus arbitre, jamais de coupe forcée).
 *
 * Sécurité (inviolable) : ne parle jamais pendant la parole du user (la
 * boucle tait micro tenu et transcription, et le bus jetterait le niveau 12
 * de toute façon) ; ne coupe jamais (12 est jeté dès qu'une parole est en
 * cours — le bus arbitre ; en Traversée le verdict parle toutes les 2 s
 * quand les frames arrivent, le silence 3,5 s n'existe que verdict
 * affamé) ; ne ré-affirme jamais un
 * verdict périmé (positionnement seul : virage ou tout-droit, jamais
 * « Cross. », jamais stop) ; session active dehors seulement (la boucle
 * exige caméra liée et lieu dehors) ; sobre (ne parle QUE dans le silence, une fois par
 * [SILENCE_MS] au plus — pas de spam sous bavardage).
 *
 * Le mot est celui du dernier état de guidage connu ([report], virage ou
 * tout-droit) ; à défaut d'état : tout-droit (poursuis par défaut).
 * Clips uniquement ([GuidanceWord.clipId], FR ou EN selon la langue voix).
 */
internal object KeepaliveVoice {

    /**
     * Silence bus requis avant de parler (3,5 s, épinglé en test) : au-delà
     * du plus long silence normal (battement escaliers 1 s, fin des marches
     * 2 s, cœur SAM périmé 2 s — un tel silence veut dire que plus rien ne
     * parle vraiment), bien en deçà du rappel GPS (15 s). Le même délai borne
     * la cadence propre (un keepalive au plus par silence).
     */
    const val SILENCE_MS = 3_500L

    /** Dernier état de guidage connu (virage ou tout-droit, jamais stop). */
    private var lastWord: GuidanceWord = GuidanceWord.Straight

    /**
     * Dernier keepalive accepté par le bus (0 = jamais : dû dès le premier
     * silence — à froid, avant toute parole, le bus est silencieux depuis
     * l'époque).
     */
    private var lastKeepaliveMs: Long = 0L

    /**
     * Mémorise le mot de guidage courant : virage ou tout-droit — le stop
     * n'est pas un positionnement (repli sur tout-droit, jamais tu : le
     * keepalive reste audible). Pur hors mémoire, testé en JVM.
     */
    @Synchronized
    fun report(word: GuidanceWord) {
        lastWord = if (word == GuidanceWord.Stop) GuidanceWord.Straight else word
    }

    /**
     * Propose le mot courant : posté niveau 12 si le bus est silencieux depuis
     * [SILENCE_MS] et que le précédent keepalive est plus vieux. Rend true si
     * posté ET accepté — jeté entre-temps (bavardage revenu, micro tenu) =
     * false sans timbrer, retenté au tick suivant. Testé en JVM.
     *
     * Verdict affamé : le keepalive comble le silence (positionnement seul,
     * jamais « Cross. ») ; verdict sain toutes les 2 s ⇒ le silence 3,5 s
     * n'arrive pas — le verdict garde la main sans coupe.
     */
    @Synchronized
    fun offer(language: SpeechLanguage, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (VoiceBus.silenceMs(nowMs) < SILENCE_MS) return false
        if (nowMs - lastKeepaliveMs < SILENCE_MS) return false
        val text = lastWord.text(language)
        if (!VoiceBus.post(OutdoorLevel.FALLBACK, text, lastWord.clipId)) return false
        SpeechLog.expect("repli", text)
        lastKeepaliveMs = nowMs
        return true
    }

    /** Nouvelle séance (et tests : l'objet est global) : mot et timbre oubliés. */
    @Synchronized
    fun reset() {
        lastWord = GuidanceWord.Straight
        lastKeepaliveMs = 0L
    }
}

/**
 * Boucle du keepalive, miroir de [voiceTickJob] : toutes les [tickMs], propose
 * le mot de positionnement courant au bus — sauf caméra coupée (session fermée :
 * la sonde est arrêtée, rien à tenir en vie), dedans (chaîne dedans, pas de
 * mots dehors) et pendant la parole du user (micro tenu, transcription).
 * Testée en JVM au temps virtuel (scope séparé, boucle infinie annulée en fin
 * de test, comme le battement 700 ms).
 */
internal fun keepaliveJob(
    scope: CoroutineScope,
    ui: StateFlow<IseeUiState>,
    language: () -> SpeechLanguage,
    placeMode: () -> PictoPlaceMode,
    tickMs: Long = VoiceTick.TICK_MS,
    nowMs: () -> Long = { System.currentTimeMillis() },
    offer: (SpeechLanguage, Long) -> Boolean = KeepaliveVoice::offer,
): Job = scope.launch {
    while (true) {
        delay(tickMs)
        val st = ui.value
        if (!st.cameraActive) continue
        if (placeMode() != PictoPlaceMode.DEHORS) continue
        if (st.holding || st.transcribing) continue
        offer(language(), nowMs())
    }
}
