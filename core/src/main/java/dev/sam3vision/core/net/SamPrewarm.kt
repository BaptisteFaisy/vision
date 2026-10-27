package dev.sam3vision.core.net

import kotlinx.coroutines.CancellationException

/**
 * Pré-chauffe single-shot du conteneur Modal : un seul `GET /health` (client patient,
 * 180 s), tiré à l'ouverture de l'écran pour que le réveil (2-7 s avec snapshot)
 * chevauche l'arrivée de la caméra — la 1re frame trouve le conteneur tiède.
 *
 * Protocole figé : **un seul tir par démarrage**, jamais de polling (un sondage
 * périodique tiendrait la carte allumée à 2,415 $/h) et jamais de relance : un
 * échec se contente de laisser la 1re frame réveiller le conteneur comme avant.
 * `true` = conteneur sain (`ok`), `false` = timeout ou erreur (non bloquant).
 */
suspend fun prewarmSam(baseUrl: String, client: LiveClient = HttpLiveClient(baseUrl)): Boolean =
    try {
        client.health().getOrThrow().ok
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
