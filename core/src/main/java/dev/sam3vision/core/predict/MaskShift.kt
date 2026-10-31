package dev.sam3vision.core.predict

import dev.sam3vision.core.mask.GrayMask

/**
 * Variante **masquée** du block-matching SAD ([estimateShift], PISTE 3) : le décalage
 * est mesuré sur les seuls pixels du masque SAM de l'objet, au lieu du rectangle de
 * boîte entier — le fond capturé dans le rectangle (qui suit la caméra, pas l'objet)
 * ne pollue plus la mesure.
 *
 * Même machinerie que [estimateShift] (grille tous les [stepPx] px, recherche pleine
 * ±[searchPx] px, immobile gagnant des égalités, même [Shift]) : aucune logique n'est
 * dupliquée, seuls les points d'échantillonnage sont filtrés par le masque.
 *
 * [mask] est le masque SAM **dans les coordonnées de [prev]** (même grille ; un masque
 * à une autre échelle se rééchantillonne d'abord — voir `MaskUpscale`). `null` si les
 * grilles diffèrent, si la boîte est trop petite, ou si moins de [MIN_SAMPLES] points
 * du masque sont échantillonnés (masque vide ou minuscule : pas d'avis statistique).
 */
fun estimateMaskedShift(
    prev: GrayMask,
    cur: GrayMask,
    box: Box,
    mask: GrayMask,
    searchPx: Int = DEFAULT_SEARCH_PX,
    stepPx: Int = PROBE_STEP_PX,
): Shift? {
    if (prev.width != cur.width || prev.height != cur.height) return null
    if (mask.width != prev.width || mask.height != prev.height) return null
    val area = sampleRect(box, prev.width, prev.height, stepPx, searchPx) ?: return null
    val within = { x: Int, y: Int -> mask.isObject(x, y) }
    val zero = sad(prev, cur, area, 0, 0, stepPx, within) ?: return null
    val offsets = centeredOffsets(searchPx)
    val best = bestIn(prev, cur, area, stepPx, offsets, offsets, within) ?: return null
    return Shift(best.first.dx, best.first.dy, confidence(zero, best.second))
}
