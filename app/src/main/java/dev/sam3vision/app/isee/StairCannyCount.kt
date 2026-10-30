package dev.sam3vision.app.isee

import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.stairs.StairDetector
import dev.sam3vision.core.stairs.StairWatcher

/**
 * Dernier compte de marches vu par la caméra, pour la voix escaliers.
 *
 * L'analyseur offre le plan de luminance de chaque frame convertie
 * ([offerLuma], fil de l'analyse : le Canny y tourne, ~26 000 pixels) et le
 * guetteur escaliers (`observeStairs`) relit le compte frais à
 * chaque réponse SAM — une seule écriture d'entier transite entre les deux
 * fils, jamais une image. Frais, le compte nourrit le compte à rebours de la
 * montée (« Plus que N marches. ») ; périmé (caméra coupée, repli bitmap sans
 * luminance), il vaut 0 et l'annonce reste standard.
 */
internal object StairCannyCount {

    /** Fraîcheur d'un compte : au-delà, la caméra est coupée ou figée. */
    const val FRESH_MS = 1_500L

    @Volatile
    private var count: Int = 0

    @Volatile
    private var countedAtMs: Long = 0L

    /**
     * Compte les marches de [luma] (plan [LumaPlane] de l'analyseur, déjà
     * tourné). Plan incomplet = ignoré (le compte précédent vieillit, puis
     * expire — il ne bascule jamais à « zéro marche » sur une frame douteuse).
     */
    fun offerLuma(luma: ByteArray) {
        if (luma.size != LumaPlane.SIZE) return
        count = cannyLineCount(luma)
        countedAtMs = System.currentTimeMillis()
    }

    /**
     * Marches vues sur la dernière frame fraîche, 0 sinon : 0 = non compté,
     * pas « zéro marche » (convention [StairWatcher]).
     */
    fun lineCount(nowMs: Long = System.currentTimeMillis()): Int =
        count.takeIf { nowMs - countedAtMs <= FRESH_MS } ?: 0

    /** Nouvelle séance : le compte d'avant ne nourrit pas la montée d'après. */
    fun clear() {
        count = 0
        countedAtMs = 0L
    }
}

/**
 * Marches comptées dans [luma] (plan [LumaPlane] complet), 0 sinon : le
 * détecteur Canny pur ([StairDetector]), sans exception sur le chemin du direct.
 */
internal fun cannyLineCount(luma: ByteArray): Int {
    if (luma.size != LumaPlane.SIZE) return 0
    return runCatching {
        StairDetector.detect(GrayMask(LumaPlane.WIDTH, LumaPlane.HEIGHT, luma)).lineCount
    }.getOrDefault(0)
}
