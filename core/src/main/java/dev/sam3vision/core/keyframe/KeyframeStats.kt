package dev.sam3vision.core.keyframe

import dev.sam3vision.core.live.LiveStats

/**
 * Compteurs et latences du [KeyframeScheduler] — ce que le statut du direct (u07) affiche.
 *
 * La latence est tenue par [LiveStats] de `:core/live` : **pas** de p95 réimplémenté. C'est une
 * fenêtre **distincte** de celle de `LiveSession`, et c'est voulu : [LiveStats] de la session
 * mesure le perçu (toutes les frames), celle-ci ne compte que les keyframes.
 *
 * [keyframeRatio] (keyframes reçus ÷ frames) est le chiffre qui mesure l'efficacité de la
 * propagation locale : plus il est bas, plus le warp tient tout seul.
 */
class KeyframeStats(latencyWindow: Int = 20) {

    private val triggerCounts = IntArray(Trigger.entries.size)
    private val holdCounts = IntArray(HoldReason.entries.size)
    private val latency = LiveStats(latencyWindow)

    private var framesSeen = 0
    private var requestsSent = 0
    private var successes = 0
    private var failuresSeen = 0
    private var timeouts = 0
    private var ignored = 0

    /** Une frame caméra examinée par le scheduler. */
    @Synchronized
    fun onFrame() {
        framesSeen += 1
    }

    /** Une demande acceptée, avec son motif. */
    @Synchronized
    fun onRequest(trigger: Trigger) {
        triggerCounts[trigger.ordinal] += 1
        requestsSent += 1
    }

    /** Une frame retenue, avec son motif d'attente. */
    @Synchronized
    fun onHold(reason: HoldReason) {
        holdCounts[reason.ordinal] += 1
    }

    /** Réponse reçue pour la demande en vol : la latence ne compte que les succès. */
    @Synchronized
    fun onResult(success: Boolean, latencyMs: Long) {
        if (success) {
            successes += 1
            latency.record(latencyMs)
        } else {
            failuresSeen += 1
        }
    }

    /** Requête déclarée perdue au bout de `inFlightTimeoutMs` (aucune réponse). */
    @Synchronized
    fun onTimeout() {
        timeouts += 1
    }

    /** Réponse sans demande en attente : doublon, ou réponse arrivée après le timeout de 12 s. */
    @Synchronized
    fun onIgnoredResult() {
        ignored += 1
    }

    val frames: Int get() = synchronized(this) { framesSeen }

    val requests: Int get() = synchronized(this) { requestsSent }

    val successes: Int get() = synchronized(this) { successes }

    val failures: Int get() = synchronized(this) { failuresSeen }

    val timeoutsDeclared: Int get() = synchronized(this) { timeouts }

    val ignoredResults: Int get() = synchronized(this) { ignored }

    fun triggerCount(trigger: Trigger): Int = synchronized(this) { triggerCounts[trigger.ordinal] }

    fun holdCount(reason: HoldReason): Int = synchronized(this) { holdCounts[reason.ordinal] }

    /** Keyframes **reçus** ÷ frames examinées : l'efficacité de la propagation (0 si aucune frame). */
    val keyframeRatio: Float
        get() = synchronized(this) {
            if (framesSeen == 0) 0f else successes.toFloat() / framesSeen
        }

    val averageLatencyMs: Long get() = latency.averageMs

    val p95LatencyMs: Long get() = latency.p95Ms

    @Synchronized
    fun reset() {
        triggerCounts.fill(0)
        holdCounts.fill(0)
        latency.reset()
        framesSeen = 0
        requestsSent = 0
        successes = 0
        failuresSeen = 0
        timeouts = 0
        ignored = 0
    }
}