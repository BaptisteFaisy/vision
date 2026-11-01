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
 * les urgences — d'où le silence de deux secondes entre deux répétitions
 * (SPEC v4 : escaliers 2 s), et deux comptes différents espacés d'au moins
 * [COUNT_MS] (un pic isolé du compteur Canny ne fait pas bégayer). Un compte
 * à 0 sur un positif = compteur indisponible (voie labels SAM, qui nomme sans
 * compter) : silence, comme [StairWatcher] — 0 n'est pas « zéro marche »,
 * c'est « non compté ».
 *
 * Pur JVM et testé.
 */
class StairCountVoice {

    /** Dernier compte dit ([NO_COUNT] = aucun : la prochaine détection parle). */
    private var lastCount: Int = NO_COUNT

    /** Instant du dernier compte dit ([NO_INSTANT] si aucun). */
    private var lastSaidAtMs: Long = NO_INSTANT

    /**
     * Compte vu à [nowMs] : la phrase à dire (« 5 marches. »), ou `null`
     * (pas d'escalier, pas de compteur, même compte dit il y a moins de
     * [REPEAT_MS], ou 300 ms pas écoulées depuis un autre compte).
     */
    fun onTick(
        detected: Boolean,
        count: Int,
        nowMs: Long,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String? {
        if (!detected || count <= 0) {
            lastCount = NO_COUNT
            return null
        }
        if (count == lastCount) {
            if (lastSaidAtMs == NO_INSTANT || nowMs - lastSaidAtMs < REPEAT_MS) return null
            lastSaidAtMs = nowMs
            return StairText.stepCount(count, language)
        }
        if (lastSaidAtMs != NO_INSTANT && nowMs - lastSaidAtMs < COUNT_MS) return null
        lastCount = count
        lastSaidAtMs = nowMs
        return StairText.stepCount(count, language)
    }

    /** Oublie le compte et l'instant (nouvelle séance de direct). */
    fun reset() {
        lastCount = NO_COUNT
        lastSaidAtMs = NO_INSTANT
    }

    companion object {
        /** Intervalle minimal entre deux comptes dits : l'info suit la caméra à 300 ms. */
        const val COUNT_MS = 300L

        /**
         * Silence avant de redire le même compte : 2 s (SPEC v4 : escaliers
         * 2 s) — dans l'escalier, le compte suit chaque pas sans bégayer.
         */
        const val REPEAT_MS = 2_000L

        /** Aucun compte dit encore (ou escalier perdu de vue). */
        private const val NO_COUNT = -1

        /** Aucun compte dit encore. */
        private const val NO_INSTANT = Long.MIN_VALUE
    }
}
