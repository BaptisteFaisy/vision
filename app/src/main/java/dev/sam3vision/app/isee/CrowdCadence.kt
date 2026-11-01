package dev.sam3vision.app.isee

/**
 * Hystérésis de l'état foule de la sonde iSee ([LiveProbe.crowd]) : on entre au
 * **premier** corps, on ne sort qu'après [CROWD_EXIT_MS] sans corps.
 *
 * Sans elle l'état clignote au rythme des frames (1,4-1,7 décisions/s : une
 * frame sans corps suffit) et le second vol de `Cadence.Crowd` se refermerait
 * au moment du contact — exactement quand une décision fraîche compte.
 * [sinceMs] = instant du dernier corps vu. Pur, testé en JVM.
 *
 * Extrait de `LiveProbe` (plafond 500 lignes, audit foule du 01/11).
 */
internal fun crowdOf(previous: Boolean, bodies: Int, sinceMs: Long, nowMs: Long): Boolean =
    if (bodies > 0) true else previous && nowMs - sinceMs < CROWD_EXIT_MS

/** Sortie de foule : 3 s sans corps, soit ~4 décisions au rythme mesuré le plus lent. */
internal const val CROWD_EXIT_MS = 3_000L
