package dev.sam3vision.app.isee

import dev.sam3vision.app.glasses.GlassesSessionKeepAlive
import dev.sam3vision.app.glasses.GlassesVideoLink

/**
 * Mise en arrière-plan d'iSee, sortie du ViewModel (plafond 500 lignes) : la
 * sonde ne s'arrête plus net — l'extinction GPU est différée de 60 s, et un
 * retour avant retrouve la carte chaude. Session lunettes-iSee en cours : rien
 * n'est armé ni coupé, le réveil retrouve le guidage là où la veille l'a laissé.
 */
internal object IseeBackground {

    /** Grâce partagée (une seule mise en fond à la fois) : tir unique à 60 s. */
    private val grace = ReleaseGrace()

    /**
     * L'app part derrière : frames gardées, extinction armée dans 60 s — sauf
     * session lunettes, qui ne coupe rien.
     */
    fun enter(probe: LiveProbe) {
        if (glassesActive()) return
        probe.onBackground()
        armRelease(probe)
    }

    /**
     * Caméra coupée (fond ou révoquée au premier plan) : extinction armée, sans
     * garder les frames — sauf session lunettes. Les sessions sont gardées : la
     * liaison suivante rouvre par-dessus, sans réveil si la carte est chaude.
     */
    fun armRelease(probe: LiveProbe) {
        if (glassesActive()) return
        grace.arm(
            shouldRelease = { probe.session?.socketOpen == true },
            extinguish = { releaseOnCameraOff(probe.state.value.endpoint) },
        )
    }

    /** Retour au premier plan, caméra liée ou fermeture : plus rien à éteindre. */
    fun cancelRelease() = grace.cancel()

    /** Session lunettes-iSee en cours : le flux vit, ou le maintien de fond tourne. */
    private fun glassesActive(): Boolean =
        GlassesSessionKeepAlive.running.value || GlassesVideoLink.streaming.value || GlassesVideoLink.live.value
}
