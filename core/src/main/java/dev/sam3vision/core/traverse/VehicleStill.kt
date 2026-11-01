package dev.sam3vision.core.traverse

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Écart maximal de mesure (m) entre deux frames pour une immobilité : couvre
 * le tremblé sténopé et les bascules de frontière de seau (7,49 m ↔ 7,51 m),
 * pas une approche franche — une vraie approche change aussi la boîte (voir
 * [isGrowingBeyondJitter]). Prior rue.
 */
const val STILL_RANGE_M = 0.5f

/**
 * Croissance d'aire relative max attribuée au tremblé de boîte SAM (4 %) :
 * en dessous, une boîte qui gonfle d'une frame à l'autre ne prouve ni
 * approche ni refus d'arrêt. Une approche franche fait ~7 %/frame à 12 m à
 * 2 m/s (5 im/s) — au-delà, ça roule. Asymétrique de propos : le déclencheur
 * veto reste strict (le tremblé peut vetoter, direction sûre), seule la
 * preuve d'arrêt est tolérante. Limite connue : une approche très lente
 * (< ~1 m/s au loin, centre et portée stables) lit comme un arrêt — le
 * tau (< 3 s) et la profondeur (quand câblée) restent les backstops.
 */
const val AREA_JITTER_RATIO = 0.04f

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

/**
 * Stabilité de mesure entre deux frames : deux inconnues valent stabilité
 * (comme « ? » == « ? »), une inconnue d'un seul côté vaut doute, sinon
 * écart sous [rangeM]. Pur, couvert via `TraverseVeto` (la voix l'adoptera à la fin de son refactor).
 */
internal fun isStableRange(meters: Float?, prevMeters: Float?, rangeM: Float = STILL_RANGE_M): Boolean {
    if (meters == null || !meters.isFinite()) return prevMeters == null || !prevMeters.isFinite()
    if (prevMeters == null || !prevMeters.isFinite()) return false
    return abs(meters - prevMeters) <= rangeM
}

/**
 * Croissance franche au-delà du tremblé : [area] dépasse [prevArea] de plus
 * de [jitterRatio] ([AREA_JITTER_RATIO] par défaut). Aire précédente nulle
 * ou invalide : injugeable, pas de croissance (l'appelant garde ses autres
 * gardes — centre, portée, fraîcheur). Pur, couvert via `TraverseVeto`.
 */
internal fun isGrowingBeyondJitter(prevArea: Float, area: Float, jitterRatio: Float = AREA_JITTER_RATIO): Boolean {
    if (!prevArea.isFinite() || !area.isFinite() || prevArea <= 0f) return false
    return area > prevArea * (1f + jitterRatio)
}
