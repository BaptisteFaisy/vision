package dev.sam3vision.core.crossing

import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Guetteur d'annonce du passage piéton (SPEC v4, niveau 8, couche 1/3) :
 * [CONFIRM_FRAMES] frames de suite avec un passage en vue parlent une fois
 * (« Gauche, 2 mètres »), à 6 m ou moins ([ANNOUNCE_METERS], mesurés ; sans
 * mesure, la direction seule parle), puis silence jusqu'à la prochaine
 * disparition (changement seul). Doux, comme l'arrivée : l'appelant parle
 * via `say` (`urgent = false`), jamais en clip.
 *
 * La phrase localise ([CrossingText.approach], « passage » implicite) :
 * direction (gauche / devant / droite, [CrossingGuidance.directionOf]) puis
 * distance courte quand elle est mesurée. Sans mesure, la direction seule —
 * jamais de chiffre deviné, jamais de crash sur données manquantes.
 *
 * Deux exceptions au « une fois par vue » (ordre user du 02/11 : dire le
 * passage devant toutes les 2 s, devant les gauche/droite) : une direction
 * nouvelle se re-confirme ([CONFIRM_FRAMES] frames) puis se redit aussitôt
 * (le passage passé devant parle, au lieu de rester sur le « Gauche »
 * périmé) ; et « Devant » se répète toutes les [REPEAT_AHEAD_MS] ms tant
 * que le passage reste devant ([repeatAhead], hors Traversée, avant
 * l'arrivée — l'appelant coupe via [arrived]).
 *
 * Les deux autres couches v4 : l'alignement ([CrossingLateralAnnouncer],
 * mot au changement, clé `crosswalk-align`) et l'arrivée
 * ([CrossingArrivalWatcher], dite une fois, clé `crosswalk-arrival`).
 *
 * Pur et testé en JVM : aucune image, aucun Android, aucune horloge implicite.
 */
class CrossingApproachWatcher(
    private val confirmFrames: Int = CONFIRM_FRAMES,
) {

    private var hits = 0
    private var lastDir: SteeringCap? = null
    private var announcedDir: SteeringCap? = null
    private var saidAtMs = 0L

    /** Dernière phrase produite (`null` tant que rien n'a été annoncé). */
    var lastPhrase: String? = null
        private set

    /**
     * Géométrie de la frame : la phrase à prononcer, ou `null` (silence).
     * [centerX] `null` ou infini = aucun passage en vue (la mémoire retombe,
     * le retour parlera) ; [meters] `null`/invalide = direction seule.
     * [language] est la langue de la voix. [repeatAhead] vrai (hors
     * Traversée) : « Devant » redit toutes les [REPEAT_AHEAD_MS] ms ;
     * [arrived] vrai : la répétition se tait (l'arrivée parle à la place).
     */
    fun onFrame(
        centerX: Float?,
        meters: Float?,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        atMs: Long = 0L,
        repeatAhead: Boolean = false,
        arrived: Boolean = false,
    ): String? {
        val valid = centerX?.takeIf { it.isFinite() }
        if (valid == null) {
            reset()
            return null
        }
        val measured = meters?.takeIf { it.isFinite() && it > 0f }
        if (measured != null && measured > ANNOUNCE_METERS) {
            // Encore loin : l'annonce attend l'entrée dans les 6 m (compte
            // remis à zéro, annonce déjà dite gardée — dite une fois par vue).
            hits = 0
            return null
        }
        val dir = CrossingGuidance.directionOf(valid)
        if (dir != lastDir) {
            lastDir = dir
            hits = 1
        } else {
            hits += 1
        }
        if (hits < confirmFrames) return null
        if (announcedDir != dir) return announce(dir, meters, language, atMs)
        if (dir == SteeringCap.Straight && repeatAhead && !arrived &&
            atMs - saidAtMs >= REPEAT_AHEAD_MS
        ) {
            return announce(dir, meters, language, atMs)
        }
        return null
    }

    /** Nouvelle session : compte, direction et annonce oubliés. */
    fun reset() {
        hits = 0
        lastDir = null
        announcedDir = null
        saidAtMs = 0L
        lastPhrase = null
    }

    /** Dit [dir] ([meters] frais) et mémorise la parole à [atMs]. */
    private fun announce(
        dir: SteeringCap,
        meters: Float?,
        language: SpeechLanguage,
        atMs: Long,
    ): String {
        announcedDir = dir
        saidAtMs = atMs
        val phrase = CrossingText.approach(dir, meters, language)
        lastPhrase = phrase
        return phrase
    }

    companion object {
        /** Frames avec passage de suite avant l'annonce (miroir de l'arrivée). */
        const val CONFIRM_FRAMES = 2

        /** Au-delà (mètres mesurés), le passage se tait : dit 6 m à l'avance (SPEC v4 : < 6 m). */
        const val ANNOUNCE_METERS = 6.0f

        /**
         * « Devant » redit toutes les 2 s tant que le passage reste devant
         * (ordre user du 02/11, hors Traversée — niveau 8, devant le repli
         * gauche/droite 12).
         */
        const val REPEAT_AHEAD_MS = 2_000L

        /** Niveau VoiceBus v4 : passages piétons (< 6 m, 3 couches). */
        const val LEVEL = 8

        /** Clé AntiRepeat v4 : annonce du passage (2 s, répétition du « devant »). */
        const val KEY_ANNOUNCE = "crosswalk-announce"
    }
}
