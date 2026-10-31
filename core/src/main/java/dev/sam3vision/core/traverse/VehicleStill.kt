package dev.sam3vision.core.traverse

import kotlin.math.hypot

/**
 * Stabilité d'une boîte entre deux frames : la dérive du centre normalisé
 * reste sous [stillSpeed] (0,02 = 2 % de l'image par seconde, bande morte
 * sous 3 %) — dates invalides, écoulé nul ou centre manquant = pas un arrêt
 * (le doute ne conclut jamais). Pur, couvert via `VehicleStateWatcher`.
 */
internal fun isStillBox(
    prevCx: Float?,
    prevCy: Float?,
    prevAtMs: Long,
    cx: Float?,
    cy: Float?,
    atMs: Long,
    stillSpeed: Float = VehicleStateWatcher.STILL_SPEED,
): Boolean {
    if (atMs < 0L || prevAtMs < 0L) return false
    val dtSeconds = (atMs - prevAtMs) / 1000f
    if (dtSeconds <= 0f) return false
    val x = cx ?: return false
    val y = cy ?: return false
    val px = prevCx ?: return false
    val py = prevCy ?: return false
    return hypot(x - px, y - py) / dtSeconds <= stillSpeed
}
