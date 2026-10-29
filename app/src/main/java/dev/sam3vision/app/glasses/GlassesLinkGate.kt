package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.LinkState
import kotlinx.coroutines.delay

/**
 * La porte d'entrée de la session, partagée par l'app et le banc d'essai.
 *
 * `createSession` refuse toute paire qui n'est pas encore `CONNECTED` : c'est le
 * « Aucun appareil éligible disponible » du SDK, mesuré sur les Ray-Ban Meta réelles — le lien
 * met 2 à 30 s à monter après l'allumage ou l'enregistrement, et une session ouverte entre-temps
 * échoue alors que les lunettes sont bien appairées. Attendre vaut mieux que réessayer à
 * l'aveugle ou demander un ré-appairage qui n'a rien à voir.
 */

/** Attente maximale du lien Bluetooth avant d'ouvrir la session (mesuré : 10 à 30 s). */
internal const val LINK_WAIT_MS = 30_000L

/** Période de relecture des états de lien pendant cette attente. */
internal const val LINK_POLL_MS = 500L

/** Vrai dès qu'une paire est `CONNECTED`. */
internal fun hasLinkedDevice(links: List<LinkState>): Boolean =
    links.any { it == LinkState.CONNECTED }

/** États de lien des paires connues du SDK ; vide tant qu'il n'y en a aucune. */
internal fun linkedStates(): List<LinkState> = runCatching {
    Wearables.devices.value.mapNotNull { id -> Wearables.devicesMetadata[id]?.value?.linkState }
}.getOrDefault(emptyList())

/**
 * Attend qu'une paire soit réellement `CONNECTED` (borne [LINK_WAIT_MS]).
 *
 * Rend `false` au bout du délai : l'appelant le dit et n'ouvre pas de session morte.
 * [onWaiting] n'est appelé que si le lien n'est pas déjà monté — un lien déjà là n'affiche
 * rien, et l'attente se voit au lieu de passer pour un bouton sans effet.
 */
internal suspend fun awaitLinkedDevice(
    clock: () -> Long,
    onWaiting: () -> Unit = {},
): Boolean {
    if (hasLinkedDevice(linkedStates())) return true
    onWaiting()
    val deadline = clock() + LINK_WAIT_MS
    while (clock() < deadline) {
        if (hasLinkedDevice(linkedStates())) return true
        delay(LINK_POLL_MS)
    }
    return false
}
