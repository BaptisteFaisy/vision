package dev.sam3vision.app.isee

import dev.sam3vision.app.glasses.GlassesSessionKeepAlive
import dev.sam3vision.app.glasses.GlassesVideoLink

/**
 * Mise en arrière-plan d'iSee, sortie du ViewModel (plafond 500 lignes) : la
 * sonde s'arrête net (sessions fermées, carte éteinte) — sauf session
 * lunettes-iSee en cours, qui survit à la veille du téléphone avec ses
 * sessions SAM, son flux et sa voix.
 */
internal object IseeBackground {

    /**
     * L'app part derrière : la sonde et ses sessions meurent, sauf si les
     * lunettes sont en session — auquel cas rien n'est coupé et le réveil
     * retrouve le guidage là où la veille l'a laissé.
     */
    fun enter(probe: LiveProbe) {
        if (glassesActive()) return
        probe.onBackground()
        probe.stop()
    }

    /** Session lunettes-iSee en cours : le flux vit, ou le maintien de fond tourne. */
    private fun glassesActive(): Boolean =
        GlassesSessionKeepAlive.running.value || GlassesVideoLink.streaming.value || GlassesVideoLink.live.value
}
