package dev.sam3vision.app.isee

import dev.sam3vision.core.live.GuidanceDecision
import dev.sam3vision.core.live.GuidanceHeartbeat
import dev.sam3vision.core.live.GuidancePulse
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Battement de la consigne de guidage (miroir de `ProbeMeters` : scope +
 * rappel, sans contexte Android) : les frames SAM nourrissent la décision,
 * le ticker publie un [GuidancePulse] toutes les 500 ms — même inchangé,
 * c'est le battement qui dit que la boucle est vivante.
 */
internal class ProbePulse(
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val publish: (GuidancePulse?) -> Unit,
) {
    private val heartbeat = GuidanceHeartbeat()
    private var job: Job? = null
    private var heard = false

    /** Démarre le battement (idempotent : un seul ticker à la fois). */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(GuidanceHeartbeat.TICK_MS)
                publish(current(clockMs()))
            }
        }
    }

    /** Arrête le battement et oublie la décision (sortie d'écran). */
    fun stop() {
        job?.cancel()
        job = null
        reset()
    }

    /** Oublie la décision, ticker inchangé (nouvelle séance). */
    fun reset() {
        heartbeat.reset()
        heard = false
        publish(null)
    }

    /**
     * Frame SAM : décision du guidage + plus proche droit devant (bande
     * centrale, repli sur le plus proche global si le couloir est vide).
     */
    fun feed(atMs: Long, decision: GuidanceDecision?, placed: List<PlacedLabel>) {
        if (decision != null) {
            heartbeat.onFrame(atMs, decision)
            heard = true
        }
        heartbeat.onDistances(nearestAhead(placed))
    }

    /** Pulse du battement : rien avant la première décision. */
    internal fun current(nowMs: Long): GuidancePulse? =
        if (heard) heartbeat.tick(nowMs) else null

    /** Plus proche mesuré du couloir central, sinon du champ entier. */
    private fun nearestAhead(placed: List<PlacedLabel>): Float? {
        val edge = PositionText.SIDE_EDGE
        val corridor = placed.filter { it.centerX >= edge && it.centerX <= 1f - edge }
        return nearest(corridor) ?: nearest(placed)
    }

    /** Plus proche mesuré (fini), `null` si rien de mesurable. */
    private fun nearest(labels: List<PlacedLabel>): Float? =
        labels.mapNotNull { it.meters?.takeIf { meters -> meters.isFinite() } }.minOrNull()
}
