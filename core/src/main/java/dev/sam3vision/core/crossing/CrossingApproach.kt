package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Guetteur d'annonce du passage piéton : [CONFIRM_FRAMES] frames de suite
 * avec un passage en vue parlent une fois (« Passage piéton à gauche, à 2
 * mètres. »), à 6 m ou moins ([ANNOUNCE_METERS], mesurés — sans
 * mesure, la direction seule parle), puis silence jusqu'à la prochaine
 * disparition (changement seul). Doux, comme l'arrivée : l'appelant parle
 * via `say` (`urgent = false`), jamais en clip.
 *
 * La phrase nomme le passage ET le localise ([CrossingText.approach]) :
 * direction (gauche / devant / droite, [CrossingGuidance.directionOf]) puis
 * distance courte quand elle est mesurée. Sans mesure, la direction seule —
 * jamais de chiffre deviné, jamais de crash sur données manquantes.
 *
 * Pur et testé en JVM : aucune image, aucun Android, aucune horloge implicite.
 */
class CrossingApproachWatcher(
    private val confirmFrames: Int = CONFIRM_FRAMES,
) {

    private var hits = 0
    private var announced = false

    /** Dernière phrase produite (`null` tant que rien n'a été annoncé). */
    var lastPhrase: String? = null
        private set

    /**
     * Géométrie de la frame : la phrase à prononcer, ou `null` (silence).
     * [centerX] `null` ou infini = aucun passage en vue (la mémoire retombe,
     * le retour parlera) ; [meters] `null`/invalide = direction seule.
     * [language] est la langue de la voix.
     */
    fun onFrame(
        centerX: Float?,
        meters: Float?,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String? {
        val valid = centerX?.takeIf { it.isFinite() }
        if (valid == null) {
            hits = 0
            announced = false
            return null
        }
        val measured = meters?.takeIf { it.isFinite() && it > 0f }
        if (measured != null && measured > ANNOUNCE_METERS) {
            // Encore loin : l'annonce attend l'entrée dans les 6 m (compte
            // remis à zéro, annonce déjà dite gardée — dite une fois par vue).
            hits = 0
            return null
        }
        hits += 1
        if (hits < confirmFrames || announced) return null
        announced = true
        val phrase = CrossingText.approach(CrossingGuidance.directionOf(valid), meters, language)
        lastPhrase = phrase
        return phrase
    }

    /** Nouvelle session : compte et annonce oubliés. */
    fun reset() {
        hits = 0
        announced = false
        lastPhrase = null
    }

    companion object {
        /** Frames avec passage de suite avant l'annonce (miroir de l'arrivée). */
        const val CONFIRM_FRAMES = 2

        /** Au-delà (mètres mesurés), le passage se tait : dit 6 m à l'avance (ordre user du 01/11). */
        const val ANNOUNCE_METERS = 6.0f
    }
}
