package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.DeviceSessionError

/**
 * Rejous d'ouverture de session, purs et testés en JVM : un seul rejou quand
 * `createSession` refuse ([SessionRetry]), et des essais espacés quand la
 * session créée meurt aussitôt ([isQuickDeath]).
 */
internal data class SessionRetry(val used: Int = 0) {

    /** `true` = l'échec se rejoue une fois au lieu de se dire. */
    fun shouldRetry(error: DeviceSessionError): Boolean =
        used < MAX_RETRIES && (error == DeviceSessionError.UNEXPECTED_ERROR ||
            error == DeviceSessionError.SESSION_ALREADY_EXISTS)

    /** Rejou consommé : l'échec suivant se dira. */
    fun next(): SessionRetry = copy(used = used + 1)

    private companion object {
        const val MAX_RETRIES = 1
    }
}

/**
 * Une session morte avant ce délai n'a jamais vraiment vécu : le refus mesuré
 * le 31/10/2026 tuait la session en ~80 ms, sans `STARTED` ni image rendue.
 * En dessous on réessaie, au-dessus la session a vécu et sa fin se dit.
 */
internal const val QUICK_DEATH_MS = 2_000L

/** Essais automatiques après une mort rapide, avant de conclure au refus. */
internal const val QUICK_DEATH_MAX_RETRIES = 3

/**
 * Vrai si la session est morte avant d'avoir pu servir. `startedAtMs` est lu à
 * la création, `stoppedAtMs` à la retombée — même horloge des deux côtés.
 */
internal fun isQuickDeath(startedAtMs: Long, stoppedAtMs: Long): Boolean =
    stoppedAtMs - startedAtMs < QUICK_DEATH_MS

/**
 * Attente avant le nouvel essai : 1 s, 3 s, puis 5 s. Le service retombe entre
 * deux tentatives au lieu d'être pilonné.
 */
internal fun quickDeathDelayMs(used: Int): Long = when {
    used <= 1 -> 1_000L
    used == 2 -> 3_000L
    else -> 5_000L
}
