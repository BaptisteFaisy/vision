package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.IseeClipsEn

/**
 * Jalons d'escalier EN ([StairText.distancePhrase]) → clip anglais : les
 * jalons 4/2/1 m montante/descendante jouent leur clip (« Stairs up,
 * 4 meters. ») au lieu d'être synthétisés. Fichier séparé : `StairWatcher`
 * est au plafond de taille. Pur, testé en JVM.
 */
object StairClips {

    /** Clip d'un jalon d'approche, `null` sinon (FR, sens inconnu, hors jalons). */
    fun clipIdFor(phrase: String): String? = CLIPS[phrase]

    /** Jalons EN exacts → clip ([IseeClipsEn]). */
    private val CLIPS: Map<String, String> = mapOf(
        "Stairs up at 4 meters." to IseeClipsEn.STAIRS_UP_4,
        "Stairs up at 2 meters." to IseeClipsEn.STAIRS_UP_2,
        "Stairs up at 1 meter." to IseeClipsEn.STAIRS_UP_1,
        "Stairs down at 4 meters." to IseeClipsEn.STAIRS_DOWN_4,
        "Stairs down at 2 meters." to IseeClipsEn.STAIRS_DOWN_2,
        "Stairs down at 1 meter." to IseeClipsEn.STAIRS_DOWN_1,
    )
}
