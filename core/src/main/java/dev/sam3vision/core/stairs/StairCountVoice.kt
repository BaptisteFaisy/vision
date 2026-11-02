package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Voix du compteur de marches (SPEC v4 : le nombre de marches est dit quand
 * l'escalier est reconnu) : dit combien de marches sont vues (« 5 marches. »),
 * au plus toutes les [COUNT_MS] à chaque changement — et le même compte se
 * répète toutes les [REPEAT_MS] tant que l'escalier reste vu (dans
 * l'escalier, le compte reste utile ; un passage devant, plus bref, ne
 * déclenche pas la répétition).
 *
 * La voix synthétise en ~1 s : répéter à 300 ms l'étoufferait et bloquerait
 * les urgences — d'où le silence d'une seconde entre deux répétitions
 * (ordre user du 02/11, amende SPEC v4 : escaliers 2 s — le compte est
 * prioritaire et l'esquive parle entre deux comptes). Le compte dit est en
 * plus la **médiane des [WINDOW_TICKS] derniers ticks** (900 ms) : le
 * compteur Canny est volatil pour des raisons photométriques (grain ±10 →
 * 8 à 12 marches fantômes, exposition ×1,4 → 8 à 11, contraste bas → 8 à 4,
 * mesuré le 03/11), et suivre le brut faisait redémarrer la phrase toutes
 * les 300 ms (« il se coupe en permanence et n'arrive pas à dire le nombre
 * de marches », ordre user du 03/11). Un pic d'un ou deux ticks ne parle
 * donc jamais ; une vraie marche (deux ticks d'accord) parle au tick
 * suivant, et deux comptes différents restent espacés de [REPEAT_MS] comme
 * deux répétitions. La première détection parle tout de suite (l'escalier
 * vient d'être reconnu, rien n'est encore dit). Un compte
 * à 0 sur un positif = compteur indisponible (voie labels SAM, qui nomme sans
 * compter) : silence, comme [StairWatcher] — 0 n'est pas « zéro marche »,
 * c'est « non compté ».
 *
 * Pur JVM et testé.
 */
/** Compte dit : phrase (texte) + chaîne MP3 (vide = phrase seule). */
data class StairCountSaid(val phrase: String, val clips: List<String>)

class StairCountVoice {

    /** Dernier compte dit ([NO_COUNT] = aucun : la prochaine détection parle). */
    private var lastCount: Int = NO_COUNT

    /** Instant du dernier compte dit ([NO_INSTANT] si aucun). */
    private var lastSaidAtMs: Long = NO_INSTANT

    /** Fenêtre de lissage : les [WINDOW_TICKS] derniers comptes vus (que les positifs). */
    private val window: ArrayDeque<Int> = ArrayDeque()

    /** Dernier tick où un compte positif a été vu ([NO_INSTANT] si aucun). */
    private var lastSeenAtMs: Long = NO_INSTANT

    /**
     * Compte vu à [nowMs] : phrase + chaîne à dire (« 5 marches. »), ou
     * `null` (pas d'escalier, pas de compteur, même compte dit il y a moins
     * de [REPEAT_MS], ou compte lissé inchangé).
     */
    fun onTick(
        detected: Boolean,
        count: Int,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): StairCountSaid? {
        if (!detected || count <= 0) {
            // Trou court (compte Canny périmé > 1,5 s, SAM qui clignote) : la
            // fenêtre reste — sinon la première valeur après le trou parlerait
            // brute, pic photométrique compris. Escalier vraiment perdu
            // ([LOST_MS] sans compte) : tout est oublié, la prochaine
            // rencontre parle tout de suite.
            if (lastSeenAtMs == NO_INSTANT || nowMs - lastSeenAtMs > LOST_MS) {
                lastCount = NO_COUNT
                window.clear()
            }
            return null
        }
        lastSeenAtMs = nowMs
        window.addLast(count)
        while (window.size > WINDOW_TICKS) window.removeFirst()
        val smoothed = median()
        if (smoothed == lastCount) {
            if (lastSaidAtMs == NO_INSTANT || nowMs - lastSaidAtMs < REPEAT_MS) return null
            lastSaidAtMs = nowMs
            return said(smoothed, language)
        }
        // Changement : le compte lissé (médiane de 3 ticks) fait foi — un pic
        // photométrique d'un tick ne le déplace pas, deux ticks d'accord
        // (une vraie marche) le déplacent. La première détection parle
        // sans attendre.
        if (lastSaidAtMs != NO_INSTANT && nowMs - lastSaidAtMs < REPEAT_MS) return null
        lastCount = smoothed
        lastSaidAtMs = nowMs
        return said(smoothed, language)
    }

    /** Médiane de la fenêtre (borne haute à taille paire : le compte prudent). */
    private fun median(): Int {
        val sorted = window.sorted()
        return sorted[sorted.size / 2]
    }

    /** Phrase + chaîne MP3 du compte (chaîne vide hors pack EN). */
    private fun said(count: Int, language: SpeechLanguage): StairCountSaid =
        StairCountSaid(StairText.stepCount(count, language), StairChain.countClips(count, language))

    /** Oublie le compte et l'instant (nouvelle séance de direct). */
    fun reset() {
        lastCount = NO_COUNT
        lastSaidAtMs = NO_INSTANT
        lastSeenAtMs = NO_INSTANT
        window.clear()
    }

    companion object {
        /** Cadence du compteur : l'info suit la caméra à 300 ms. */
        const val COUNT_MS = 300L

        /**
         * Fin de mémoire d'un escalier perdu de vue : 1,5 s sans compte
         * positif (miroir de la fraîcheur du compteur Canny) — en deçà, un
         * trou est un incident de mesure et le lissage continue.
         */
        const val LOST_MS = 1_500L

        /**
         * Fenêtre du lissage : 3 ticks (900 ms) — un pic photométrique d'un
         * tick (grain, exposition) ne déplace pas la médiane, deux ticks
         * d'accord (une vraie marche) oui.
         */
        const val WINDOW_TICKS = 3

        /**
         * Silence avant de redire le même compte : 1 s (ordre user du
         * 02/11) — dans l'escalier, le compte suit chaque pas et
         * l'esquive parle au milieu.
         */
        const val REPEAT_MS = 1_000L

        /** Aucun compte dit encore (ou escalier perdu de vue). */
        private const val NO_COUNT = -1

        /** Aucun compte dit encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
