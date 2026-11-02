package dev.sam3vision.app.isee

/**
 * Gardes du bus vocal ([VoiceBus], sorties pour le plafond 500 lignes) :
 * niveau incoupable orphelin (escaliers 6, trottoir 10 — sans elles, un
 * niveau orphelin tairait toute la voix) et durées des marches (fin des
 * marches, fraîcheur de la phrase retenue). Pures, testées en JVM.
 */

/**
 * Bus bloqué sur des escaliers : niveau 6 en cours depuis plus de
 * [STAIRS_BUS_TTL_MS] sans parole acceptée (lecteur pendu, synthèse
 * jamais revenue — sinon chaque compte rafraîchit l'horodatage).
 * Sans cette garde, un niveau 6 orphelin tairait toute la voix,
 * les forts comme les doux. Pur, testé en JVM.
 */
internal fun stairsBusStuck(activeLevel: Int, nowMs: Long, lastSayMs: Long): Boolean =
    activeLevel == OutdoorLevel.STAIRS && nowMs - lastSayMs > STAIRS_BUS_TTL_MS

/** Silence maximal sous un niveau 6 (4 s : 4 comptes manqués, jamais en direct normal). */
internal const val STAIRS_BUS_TTL_MS = 4_000L

/** Fin des marches : 2 s sans compte accepté (2 cycles manqués, le compte bat à 1 s). */
internal const val STAIRS_QUIET_MS = 2_000L

/**
 * Fraîcheur d'une phrase escaliers retenue : 1,5 s. Au-delà, le compte a été
 * dit pour l'image d'avant (la phrase en cours a duré plus longtemps qu'un
 * cycle) — le redire serait périmé ; le tick suivant repostera le compte courant.
 */
internal const val STAIRS_PENDING_TTL_MS = 1_500L

/**
 * Bus bloqué sur un trottoir : niveau 10 en cours depuis plus de
 * [SIDEWALK_BUS_TTL_MS] sans parole acceptée — miroir de [stairsBusStuck]
 * (un trottoir absolu orphelin tairait toute la voix). Pur, testé en JVM.
 */
internal fun sidewalkBusStuck(activeLevel: Int, nowMs: Long, lastSayMs: Long): Boolean =
    activeLevel == OutdoorLevel.SIDEWALK && nowMs - lastSayMs > SIDEWALK_BUS_TTL_MS

/** Silence maximal sous un niveau 10 (4 s : un clip trottoir dure moins d'1 s). */
internal const val SIDEWALK_BUS_TTL_MS = 4_000L

/**
 * Incoupable qui barre [level] : le trottoir en cours barre tout sauf un
 * trottoir (§ 45), les escaliers en cours barrent tout sauf des escaliers
 * (retenus, § 44) et un trottoir (qui coupe, § 45) — `null` = rien ne
 * barre. Rend le motif du journal (`trottoir` / `escaliers`). Pur.
 */
internal fun uncuttableBlocker(activeLevel: Int, level: Int): String? {
    if (activeLevel == OutdoorLevel.SIDEWALK && level != OutdoorLevel.SIDEWALK) return "trottoir"
    if (activeLevel == OutdoorLevel.STAIRS && level != OutdoorLevel.STAIRS && level != OutdoorLevel.SIDEWALK) return "escaliers"
    return null
}
