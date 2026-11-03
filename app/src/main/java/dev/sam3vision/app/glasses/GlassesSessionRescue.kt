package dev.sam3vision.app.glasses

/**
 * Reprise après la chute d'une session établie, pure et testée en JVM : une session
 * qui a vécu puis tombe (micro-coupure Bluetooth, lunettes qui la ferment) se rouvre
 * seule, en backoff plafonné — au lieu d'attendre une relance manuelle.
 * Passé [RESCUE_MAX_RETRIES] chutes rapprochées, c'est un refus durable et il se dit.
 */

/** Reprises automatiques après chute, avant de conclure au refus durable. */
internal const val RESCUE_MAX_RETRIES = 10

/** Une session qui a vécu au-delà de ce délai pardonne les reprises passées. */
internal const val RESCUE_FORGIVE_MS = 60_000L

/** Attente de la première reprise ; les suivantes doublent jusqu'au plafond. */
internal const val RESCUE_BASE_MS = 1_000L

/** Plafond d'attente entre deux reprises (le service retombe, jamais pilonné). */
internal const val RESCUE_CAP_MS = 30_000L

/**
 * Attente avant la reprise n° [used] (1-based) : 1, 2, 4, 8, 16 s puis 30 s
 * plafonné. Dix reprises couvrent ~2 min de lien instable avant l'abandon dit.
 */
internal fun rescueDelayMs(used: Int): Long =
    minOf(RESCUE_CAP_MS, RESCUE_BASE_MS shl (used - 1).coerceIn(0, 5))

/**
 * Vrai si la session a assez vécu pour pardonner les reprises passées : une chute
 * isolée après une longue séance rouvre un budget neuf, seul le flapping accumule.
 * `startedAtMs` est lu à la création, `stoppedAtMs` à la retombée — même horloge.
 */
internal fun rescueForgiven(startedAtMs: Long, stoppedAtMs: Long): Boolean =
    stoppedAtMs - startedAtMs >= RESCUE_FORGIVE_MS
