package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.IseeClipsEn

/**
 * Jalons d'escalier EN ([StairText.distancePhrase]) → clip anglais : les
 * jalons 2/1/0,5 m jouent leur clip (« Stairs, 2 meters. ») au lieu d'être
 * synthétisés. Fichier séparé : `StairWatcher` est au plafond de taille.
 * Pur, testé en JVM.
 */
object StairClips {

    /** Clip d'un jalon d'approche, `null` sinon (FR, hors jalons). */
    fun clipIdFor(phrase: String): String? = CLIPS[phrase]

    /** Jalons EN exacts → clip ([IseeClipsEn]). */
    private val CLIPS: Map<String, String> = mapOf(
        "Stairs 2 m." to IseeClipsEn.STAIRS_2M,
        "Stairs 1 m." to IseeClipsEn.STAIRS_1M,
        "Stairs 0.5 m." to IseeClipsEn.STAIRS_HALF_M,
    )
}
