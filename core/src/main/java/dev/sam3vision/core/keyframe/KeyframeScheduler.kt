package dev.sam3vision.core.keyframe

import dev.sam3vision.core.live.FrameGate

/**
 * Décide, frame par frame, s'il faut redemander un **SAM complet** (keyframe) — u12, lane L13.
 *
 * Rien n'est envoyé ici : le scheduler est une **fonction de l'horloge et des mesures**. Il dit
 * « envoie cette frame clé » ([KeyframeDecision.Request]) ou « propage localement »
 * ([KeyframeDecision.Hold]) ; l'appelant (u13) envoie, puis rappelle
 * [onKeyframeResult] — **toujours**, c'est ce qui libère le créneau.
 *
 * Réutilise [FrameGate] de `:core/live` pour n'avoir **qu'un** keyframe en vol (la file GPU a
 * déjà coûté 600 ms) et `KeyframeStats`/`LiveStats` pour les compteurs : aucune réimplémentation.
 *
 * Sûr entre threads (`@Synchronized`) : appelable depuis le thread caméra et le thread IO.
 */
class KeyframeScheduler(
    private val config: KeyframeConfig = KeyframeConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Un seul keyframe en vol, comme l'impose la file GPU. */
    private val gate = FrameGate(slots = 1)

    private val budget = RequestBudget(config.budgetRatePerSec, config.budgetBurst)

    /** Compteurs exposés (latence, motifs, ratio keyframes/frames). */
    val stats = KeyframeStats()

    /** Horodatage de la demande en vol : borne du timeout et clé d'appariement des réponses. */
    private var inFlightAtMs = NEVER

    /** Dernière demande acceptée (délai minimal), [NEVER] avant la première. */
    private var lastRequestMs = NEVER

    /** Masques en service : instant de la frame clé qui les a produits, [NEVER] si aucun. */
    private var keyframeAtMs = NEVER

    /** Erreur de reprojection lissée (EMA) et nombre de frames consécutives au-dessus du seuil. */
    private var smoothedError = 0f
    private var highErrorFrames = 0

    /** Échecs consécutifs et fin du repli courant ([NEVER] = aucun échec en cours). */
    private var failures = 0
    private var backoffUntilMs = NEVER

    /** Vrai pendant qu'un keyframe attend sa réponse. */
    val inFlight: Boolean get() = gate.inFlight

    /** Échecs consécutifs en cours (`0` = sain). */
    val consecutiveFailures: Int get() = synchronized(this) { failures }

    /** Âge du keyframe en service, en ms (`-1` si aucun keyframe reçu). */
    val keyframeAgeMs: Long
        get() = synchronized(this) {
            if (keyframeAtMs == NEVER) -1L else clock() - keyframeAtMs
        }

    /**
     * Une décision par frame caméra, avant tout réseau. Jamais bloquante.
     *
     * Ordre des priorités : une requête perdue est d'abord réparée (timeout), puis ce qui est en
     * vol interdit d'en envoyer une autre, puis les motifs sont examinés — coupure de scène et
     * masque perdu **outre** le délai minimal et le repli, le reste derrière.
     */
    @Synchronized
    fun onFrame(signal: FrameSignal): KeyframeDecision {
        val now = clock()
        reapLostRequest(now)
        stats.onFrame()
        val measured = signal.sanitized
        smoothedError += config.emaAlpha * (measured.reprojectionError - smoothedError)
        highErrorFrames = if (smoothedError > config.errorThreshold) highErrorFrames + 1 else 0

        if (gate.inFlight) return hold(HoldReason.IN_FLIGHT, config.inFlightTimeoutMs - (now - inFlightAtMs))

        val ageMs = keyframeAgeMs(now)
        val trigger = pickTrigger(measured, ageMs, now)
            ?: return hold(HoldReason.STABLE, config.effectiveMaxAgeMs - ageMs)
        if (!trigger.isUrgent) {
            if (inBackoff(now)) return hold(HoldReason.BACKOFF, backoffUntilMs - now)
            val sinceLastMs = if (lastRequestMs == NEVER) Long.MAX_VALUE else now - lastRequestMs
            if (sinceLastMs < config.minIntervalMs) {
                return hold(HoldReason.MIN_INTERVAL, config.minIntervalMs - sinceLastMs)
            }
        }
        if (!spendBudget(trigger, now)) return hold(HoldReason.BUDGET, budget.msUntilToken(now))
        return accept(trigger, now)
    }

    /**
     * Réponse à la demande en vol. **Idempotent** : une réponse sans demande en attente — doublon,
     * ou réponse tardive arrivée après le timeout de 12 s — est **ignorée** (comptée dans
     * `stats.ignoredResults`). Sans ce garde-fou, un client bavard recalerait l'âge du keyframe et
     * libérerait le créneau d'une requête plus récente : l'écran croirait son masque frais alors
     * que le warp a décroché.
     *
     * [requestAtMs] est facultatif (rétro-compatible) : le passer (c'est `Request.atMs`) lève
     * l'ambiguïté d'une réponse tardive qui croise une nouvelle demande.
     */
    @Synchronized
    fun onKeyframeResult(
        success: Boolean,
        latencyMs: Long,
        residualError: Float = 0f,
        requestAtMs: Long? = null,
    ) {
        val mismatched = requestAtMs != null && requestAtMs != inFlightAtMs
        if (!gate.inFlight || mismatched) {
            stats.onIgnoredResult()
            return
        }
        gate.finish()
        stats.onResult(success, latencyMs)
        if (!success) {
            recordFailure(clock())
            return
        }
        failures = 0
        backoffUntilMs = NEVER
        keyframeAtMs = maxOf(keyframeAtMs, inFlightAtMs)
        smoothedError = clamp01(residualError)
        highErrorFrames = 0
    }

    /** Remet le scheduler à neuf : nouvelle session de direct, aucun reste de la précédente. */
    @Synchronized
    fun reset() {
        if (gate.inFlight) gate.finish()
        budget.reset(clock())
        stats.reset()
        inFlightAtMs = NEVER
        lastRequestMs = NEVER
        keyframeAtMs = NEVER
        smoothedError = 0f
        highErrorFrames = 0
        failures = 0
        backoffUntilMs = NEVER
    }

    /** Un keyframe sans réponse au bout de `inFlightTimeoutMs` est déclaré perdu : échec + repli. */
    private fun reapLostRequest(now: Long) {
        if (!gate.inFlight || now - inFlightAtMs < config.inFlightTimeoutMs) return
        gate.finish()
        stats.onTimeout()
        recordFailure(now)
    }

    /** Échec (timeout ou réponse négative) : arme le repli suivant de l'échelle, jamais rejouée à zéro. */
    private fun recordFailure(now: Long) {
        failures += 1
        backoffUntilMs = now + config.backoffAfterFailures(failures)
    }

    private fun inBackoff(now: Long): Boolean = failures > 0 && now < backoffUntilMs

    private fun keyframeAgeMs(now: Long): Long =
        if (keyframeAtMs == NEVER) Long.MAX_VALUE else now - keyframeAtMs

    /**
     * Motif de la demande, par ordre de priorité. `null` = les masques en service suffisent.
     * Coupure de scène et perte de masque passent devant l'âge : elles disent que ce qui est
     * affiché est **faux**, pas seulement vieux.
     */
    private fun pickTrigger(signal: FrameSignal, ageMs: Long, now: Long): Trigger? = when {
        keyframeAtMs == NEVER -> Trigger.STARTUP
        !signal.objectsPresent -> Trigger.LOST
        signal.sceneChange >= config.sceneThreshold -> Trigger.SCENE_CHANGE
        failures > 0 && now >= backoffUntilMs -> Trigger.RETRY
        ageMs >= config.effectiveMaxAgeMs -> Trigger.EXPIRED
        highErrorFrames >= config.errorFrames -> Trigger.REPROJECTION
        else -> null
    }

    private fun spendBudget(trigger: Trigger, now: Long): Boolean =
        if (trigger.isUrgent) budget.allowUrgent(now) else budget.allow(now)

    /** Prend le créneau et date la demande : c'est l'instant de la décision, pas de l'envoi. */
    private fun accept(trigger: Trigger, now: Long): KeyframeDecision {
        gate.tryStart()
        inFlightAtMs = now
        lastRequestMs = now
        stats.onRequest(trigger)
        return KeyframeDecision.Request(trigger, now)
    }

    private fun hold(reason: HoldReason, retryInMs: Long): KeyframeDecision {
        stats.onHold(reason)
        return KeyframeDecision.Hold(reason, retryInMs.coerceAtLeast(0L))
    }

    private companion object {
        /** Jamais : horodatage sentinelle (`Long.MIN_VALUE` ne peut pas être un instant réel). */
        const val NEVER = Long.MIN_VALUE
    }
}