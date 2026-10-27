package dev.sam3vision.app.glasses

import dev.sam3vision.app.PcmSource

/**
 * Passerelle entre le module lunettes et la dictée de l'app, qui ne se connaissent pas :
 * `GlassesController` publie le micro des lunettes quand le flux tourne, `DictationFeature`
 * le prend s'il existe et retombe sur le micro du téléphone sinon.
 *
 * Un seul point d'entrée, donc une seule règle de choix (testée en JVM dans
 * `GlassesAudioPlanTest`) : aucun appelant ne décide à la place de la dictée.
 */
object GlassesMicLink {

    @Volatile
    private var provider: (() -> PcmSource?)? = null

    /** Publie (ou remplace) la source lunettes ; `null` = pas de source disponible. */
    fun attach(source: () -> PcmSource?) {
        provider = source
    }

    /** Session terminée : plus de micro lunettes pour personne. */
    fun detach() {
        provider = null
    }

    /** Micro des lunettes utilisable maintenant, ou `null`. */
    fun source(): PcmSource? = provider?.invoke()
}