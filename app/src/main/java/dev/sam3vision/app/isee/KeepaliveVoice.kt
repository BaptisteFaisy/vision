package dev.sam3vision.app.isee

import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CancellationException
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

    /**
     * Voie journal du keepalive (02/11) : le PUSH (boucle 700 ms) et le PULL
     * ([FallbackVoice], ré-émission sonde) partageaient
     * `voie=repli` — indiscernables au logcat (`EXPECT voie=repli`
     * + `bus-parole level=12` identiques des deux côtés). Le PULL garde
     * `repli`, le PUSH signe ici : l'attribution d'une parole niveau 12
     * acceptée se lit au journal, sans deviner.
     */
    const val VOIE = "repli-keepalive"

    /** Dernier état de guidage connu (virage ou tout-droit, jamais stop). */
    private var lastWord: GuidanceWord = GuidanceWord.Straight

    /**
     * Dernier keepalive accepté par le bus (0 = jamais : dû dès le premier
     * silence — à froid, avant toute parole, le bus est silencieux depuis
     * l'époque).
     */
    private var lastKeepaliveMs: Long = 0L

    /**
     * Jet déjà signalé dans l'épisode de silence courant : une offre due mais
     * jetée par le bus (escaliers en cours, parole plus forte, micro tenu)
     * journalise un seul FAIL par épisode — sobre (pas un FAIL par tick
     * 700 ms), soldé dès que le bus reparle ou que le keepalive passe.
     */
    private var refusedEpisode: Boolean = false

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
     * posté ET accepté — jeté entre-temps (bavardage revenu, micro tenu,
     * escaliers en cours) = false sans timbrer, retenté au tick suivant
     * (céder sous les escaliers est sain : le compte va au bout, personne ne
     * le coupe — le keepalive parle dès le bus rendu, jamais par-dessus).
     * Testé en JVM.
     *
     * Ne lève jamais : un porte-parole en faute (lecteur mort sur appareil)
     * rend false + FAIL au lieu de tuer la boucle appelante.
     *
     * Verdict affamé : le keepalive comble le silence (positionnement seul,
     * jamais « Cross. ») ; verdict sain toutes les 2 s ⇒ le silence 3,5 s
     * n'arrive pas — le verdict garde la main sans coupe.
     */
    @Synchronized
    fun offer(language: SpeechLanguage, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (VoiceBus.silenceMs(nowMs) < SILENCE_MS) {
            refusedEpisode = false
            return false
        }
        if (nowMs - lastKeepaliveMs < SILENCE_MS) return false
        val text = lastWord.text(language)
        val accepted = try {
            VoiceBus.post(OutdoorLevel.FALLBACK, text, lastWord.clipId)
        } catch (e: Exception) {
            SpeechLog.fail("keepalive", "exception ${e.message}")
            return false
        }
        if (!accepted) {
            if (!refusedEpisode) {
                refusedEpisode = true
                SpeechLog.fail("keepalive", "offre due jetée")
            }
            return false
        }
        SpeechLog.expect(VOIE, text)
        lastKeepaliveMs = nowMs
        refusedEpisode = false
        return true
    }

    /** Nouvelle séance (et tests : l'objet est global) : mot, timbre et épisode oubliés. */
    @Synchronized
    fun reset() {
        lastWord = GuidanceWord.Straight
        lastKeepaliveMs = 0L
        refusedEpisode = false
    }
}

/**
 * Boucle du keepalive, miroir de [voiceTickJob] : toutes les [tickMs], propose
 * le mot de positionnement courant au bus — sauf caméra coupée (session fermée :
 * la sonde est arrêtée, rien à tenir en vie), dedans (chaîne dedans, pas de
 * mots dehors) et pendant la parole du user (micro tenu, transcription).
 * Testée en JVM au temps virtuel (scope séparé, boucle infinie annulée en fin
 * de test, comme le battement 700 ms).
 *
 * La boucle survit à une offre en faute (une exception = un FAIL + tick
 * suivant, jamais une mort silencieuse — seule l'annulation l'arrête).
 *
 * Dedans : silence assumé, pas un trou à combler ici — les mots de
 * positionnement sont des mots dehors (virage/tout-droit du guidage rue),
 * périmés et trompeurs dedans ; la chaîne dedans (SPEC §3 : find, user,
 * humain, chat, porte et murs) est exclusive et n'a pas de vocabulaire de
 * positionnement continu — un keepalive dedans exigerait ce vocabulaire
 * (hors zone). Un test mené dedans tait donc le keepalive par contrat :
 * vérifier le lieu avant de conclure à une régression.
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
        try {
            val st = ui.value
            if (!st.cameraActive) continue
            if (placeMode() != PictoPlaceMode.DEHORS) continue
            if (st.holding || st.transcribing) continue
            offer(language(), nowMs())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SpeechLog.fail("keepalive", "tick ${e.message}")
        }
    }
}
