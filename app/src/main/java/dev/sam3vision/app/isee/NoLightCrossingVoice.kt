package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.NoLightAnnouncer
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Voix du verdict sans feu, une par processus (même garde des 500 lignes que
 * [CrossingLateralWatch]) : « Traversez. » après 3 s sans voiture en
 * mouvement (ordre user du 01/11), « Attendez. » sur danger (véhicule brut
 * en mouvement) — tous les deux toutes les 2 s (ordre user du 31/10 : on
 * sait toujours si on peut traverser).
 */
internal object NoLightCrossWatch {

    private val announcer = NoLightAnnouncer()

    /**
     * Verdict sans-feu (`null` = silence : confirmation en cours, répétition
     * pas due, ou la voix véhicules a parlé). [vehicleSpoke] vrai quand la
     * voix véhicules a parlé sur cette frame (le verdict recule son délai).
     */
    fun update(atMs: Long, danger: Boolean, language: SpeechLanguage, vehicleSpoke: Boolean = false): String? =
        announcer.onFrame(atMs, danger, language, vehicleSpoke)

    /** Mode coupé : la prochaine traversée reconfirme son verdict. */
    fun reset() {
        announcer.reset()
    }
}
