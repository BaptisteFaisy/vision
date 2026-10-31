package dev.sam3vision.core.stairs

import dev.sam3vision.core.local.LumaPlane
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
    val plane = runCatching { upscaleNearest(mask, LumaPlane.WIDTH, LumaPlane.HEIGHT) }.getOrNull()
        ?: return null
    if (plane.pixels.size != LumaPlane.SIZE || plane.pixels.none { it != 0.toByte() }) return null
    return plane.pixels
}
