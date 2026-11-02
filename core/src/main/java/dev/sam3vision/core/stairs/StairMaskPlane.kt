package dev.sam3vision.core.stairs

import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.mask.decodeObjectMask
import dev.sam3vision.core.mask.upscaleNearest
import dev.sam3vision.core.model.SegObject

/**
 * Plan du masque de l'escalier à l'échelle du compteur ([LumaPlane.SIZE]
 * octets, `1` sous l'escalier vu par SAM) : le Canny ne compte que dedans —
 * le calibrage au pixel près, là où la boîte ([StairRoi]) cadre au rectangle.
 *
 * L'escalier retenu est le premier libellé escalier (le serveur trie par
 * score décroissant : c'est le mieux reconnu). Tous les encodages servis par
 * [decodeObjectMask] (`rle` du direct, `poly`, `bits`, `png`) sont relus puis
 * rééchantillonnés au plus proche voisin (niveaux 0/255 préservés).
 *
 * Fonction totale : pas d'escalier, masque illisible, absent (`none`),
 * vide ou hors taille = `null` (le compteur se replie sur la boîte, puis le
 * balayage entier) — jamais d'exception sur le chemin du direct.
 */
fun stairsMaskPlaneOf(objects: List<SegObject>, frameWidth: Int, frameHeight: Int): ByteArray? {
    if (frameWidth <= 0 || frameHeight <= 0 || objects.isEmpty()) return null
    val stairs = objects.firstOrNull { StairConcepts.isStairs(it.label) } ?: return null
    val mask = runCatching { decodeObjectMask(stairs, frameWidth, frameHeight) }.getOrNull() ?: return null
    return toPlane(mask)
}

/**
 * Même plan, en réutilisant les masques déjà décodés par le peintre ([grays], un
 * par objet — voir `MaskPainter.lastGrays`) : l'escalier n'est plus redécodé.
 * Repli sur le décodage quand le gris manque ou n'est pas à la taille de la frame.
 */
fun stairsMaskPlaneOf(
    objects: List<SegObject>,
    frameWidth: Int,
    frameHeight: Int,
    grays: List<GrayMask?>,
): ByteArray? {
    if (frameWidth <= 0 || frameHeight <= 0 || objects.isEmpty()) return null
    val index = objects.indexOfFirst { StairConcepts.isStairs(it.label) }
    if (index < 0) return null
    val gray = grays.getOrNull(index)?.takeIf { it.width == frameWidth && it.height == frameHeight }
    val mask = gray
        ?: runCatching { decodeObjectMask(objects[index], frameWidth, frameHeight) }.getOrNull()
        ?: return null
    return toPlane(mask)
}

/** Masque → plan du compteur : rééchantillonné, `null` si vide ou hors taille. */
private fun toPlane(mask: GrayMask): ByteArray? {
    val plane = runCatching { upscaleNearest(mask, LumaPlane.WIDTH, LumaPlane.HEIGHT) }.getOrNull()
        ?: return null
    if (plane.pixels.size != LumaPlane.SIZE || plane.pixels.none { it != 0.toByte() }) return null
    return plane.pixels
}
