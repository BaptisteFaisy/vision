package dev.sam3vision.core.live

/**
 * Horodatage de capture d'une frame, ramené sur l'horloge de [LiveTimings]
 * (`SystemClock.elapsedRealtimeNanos()`).
 *
 * `ImageProxy.imageInfo.timestamp` est **censé** être sur la même base, mais certains HAL
 * livrent une horloge qui n'inclut pas la veille : le 2026-10-26, sur le Redmi, le poste
 * de capture datait de 27,45 s (exactement le temps d'écran éteint) et la chronologie
 * affichait « latence perçue 27 450 ms » à côté d'un aller-retour de 181 ms.
 *
 * On accepte donc l'horodatage capteur seulement s'il est comparable à l'horloge du
 * système ([TOLERANCE_NS]) ; sinon on prend l'instant présent. On perd les quelques
 * millisecondes entre le capteur et l'analyseur, on garde une latence qui veut dire
 * quelque chose.
 */
fun captureStamp(sensorNs: Long, nowNs: Long): Long =
    if (sensorNs > 0L && kotlin.math.abs(nowNs - sensorNs) < TOLERANCE_NS) sensorNs else nowNs

/** Écart maximal toléré entre l'horodatage capteur et l'horloge système : 1 s. */
const val TOLERANCE_NS: Long = 1_000_000_000L