package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.model.RouteTrip

/** Fraîcheur max d'un itinéraire préchargé : au-delà, le moteur recalcule. */
const val PRELOAD_MAX_AGE_MS = 60_000L

/**
 * Itinéraire déjà calculé par le résumé : le moteur le guide tel quel au
 * lieu de refaire origine → géocode → `/route` (2e calcul supprimé :
 * ~0,5–1,8 s mesurés à chaque dictée le 02/11/2026, plus le 2e GPS qui
 * pouvait attendre 15 s en intérieur).
 *
 * Porté du chargeur au moteur par un canal latéral (le résumé ne change
 * pas) : réutilisé si [isReusableFor], sinon le moteur recalcule comme
 * avant. L'origine préchargée a quelques secondes en démarrage direct —
 * le tracker recale sur les fix temps réel, et un écart notable déclenche
 * son recalcul hors-route habituel.
 */
data class PreloadedRoute(
    /** Destination normalisée ([DestinationQuery]) : la comparaison s'y fait. */
    val destination: String,
    val origin: RoutePoint,
    val dest: RoutePoint,
    val destLabel: String,
    val trip: RouteTrip,
    val loadedAtMs: Long,
) {
    /**
     * Réutilisable pour [rawDestination] à [nowMs] : même destination
     * (normalisée côté demande, « Gare. » = « Gare ») et chargement
     * frais (0–60 s, jamais le futur). Pur, testé en JVM.
     */
    fun isReusableFor(rawDestination: String, nowMs: Long): Boolean {
        if (nowMs < loadedAtMs || nowMs - loadedAtMs > PRELOAD_MAX_AGE_MS) return false
        val asked = DestinationQuery.normalize(rawDestination).getOrNull() ?: return false
        return asked == destination
    }
}
