package dev.sam3vision.core.keyframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Horloge virtuelle + rig : aucune attente réelle, aucun thread dans ces tests. */
private class Rig(config: KeyframeConfig = KeyframeConfig()) {

    val clock = FakeClock(0L)
    val scheduler = KeyframeScheduler(config) { clock.read() }

    fun frame(signal: FrameSignal = FrameSignal.Still): KeyframeDecision = scheduler.onFrame(signal)

    fun request(signal: FrameSignal = FrameSignal.Still): KeyframeDecision.Request {
        val decision = frame(signal)
        assertTrue("demande attendue, obtenu $decision", decision is KeyframeDecision.Request)
        return decision as KeyframeDecision.Request
    }

    fun hold(signal: FrameSignal = FrameSignal.Still): KeyframeDecision.Hold {
        val decision = frame(signal)
        assertTrue("attente attendue, obtenu $decision", decision is KeyframeDecision.Hold)
        return decision as KeyframeDecision.Hold
    }

    /** Répond à la demande en vol (succès par défaut) : c'est ce qui libère le créneau. */
    fun respond(success: Boolean = true, residualError: Float = 0f, requestAtMs: Long? = null) {
        scheduler.onKeyframeResult(success, LATENCY_MS, residualError, requestAtMs)
    }

    /** Frames de 33 ms jusqu'à [untilMs], chaque demande recevant sa réponse immédiatement. */
    fun drive(untilMs: Long, signal: FrameSignal, residualError: Float = 0f): List<KeyframeDecision> {
        val seen = mutableListOf<KeyframeDecision>()
        while (true) {
            val decision = frame(signal)
            seen += decision
            if (decision is KeyframeDecision.Request) respond(residualError = residualError)
            if (clock.read() >= untilMs) break
            clock.advance(KeyframeScene.TICK_MS)
        }
        return seen
    }

    companion object {
        const val LATENCY_MS = 150L
    }
}

/** Signal d'erreur maximale, sans coupure : c'est la propagation qui a décroché, pas la scène. */
private val HighDrift = FrameSignal(reprojectionError = 1f, sceneChange = 0f, objectsPresent = true)

/** Coupure de scène : les masques en service sont faux, l'erreur lissée n'a pas encore réagi. */
private val SceneCut = FrameSignal(reprojectionError = 0.2f, sceneChange = 0.9f, objectsPresent = true)

class KeyframeSchedulerTest {

    @Test
    fun `la premiere frame demande un keyframe STARTUP`() {
        val rig = Rig()
        val decision = rig.request()
        assertEquals(Trigger.STARTUP, decision.trigger)
        assertEquals(0L, decision.atMs)
        assertTrue(rig.scheduler.inFlight)
        assertEquals(1, rig.scheduler.stats.triggerCount(Trigger.STARTUP))
    }

    @Test
    fun `une seule demande est en vol a la fois`() {
        val rig = Rig()
        rig.request()
        rig.clock.advance(KeyframeScene.TICK_MS)
        val hold = rig.hold()
        assertEquals(HoldReason.IN_FLIGHT, hold.reason)
        assertEquals(12_000L - KeyframeScene.TICK_MS, hold.retryInMs)
    }

    @Test
    fun `la reponse libere le creneau et les masques suffisent`() {
        val rig = Rig()
        rig.request()
        rig.respond()
        assertFalse(rig.scheduler.inFlight)
        rig.clock.advance(KeyframeScene.TICK_MS)
        val hold = rig.hold()
        assertEquals(HoldReason.STABLE, hold.reason)
        assertEquals(500L - KeyframeScene.TICK_MS, hold.retryInMs)
        assertEquals(1, rig.scheduler.stats.successes)
    }

    @Test
    fun `un echec arme le repli de 500 ms`() {
        val rig = Rig()
        rig.request()
        rig.respond(success = false)
        assertEquals(1, rig.scheduler.consecutiveFailures)
        rig.clock.advance(KeyframeScene.TICK_MS)
        val hold = rig.hold()
        assertEquals(HoldReason.BACKOFF, hold.reason)
        assertEquals(500L - KeyframeScene.TICK_MS, hold.retryInMs)
    }

    @Test
    fun `le repli double a chaque echec et se plafonne a 8 s`() {
        val rig = Rig()
        rig.request()
        rig.respond(success = false)
        val waits = mutableListOf<Long>()
        repeat(6) {
            val hold = rig.hold()
            assertEquals(HoldReason.BACKOFF, hold.reason)
            waits += hold.retryInMs
            rig.clock.advance(hold.retryInMs)
            rig.request()
            rig.respond(success = false)
        }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L), waits)
    }

    @Test
    fun `un succes remet le repli a zero`() {
        val rig = Rig()
        rig.request()
        rig.respond(success = false)
        rig.clock.advance(500L)
        rig.request()
        rig.respond()
        assertEquals(0, rig.scheduler.consecutiveFailures)
        rig.clock.advance(KeyframeScene.TICK_MS)
        assertEquals(HoldReason.STABLE, rig.hold().reason)
    }

    @Test
    fun `une coupure de scene passe outre le delai minimal`() {
        val rig = Rig()
        rig.request()
        rig.respond()
        rig.clock.advance(KeyframeScene.TICK_MS)
        val decision = rig.request(SceneCut)
        assertEquals(Trigger.SCENE_CHANGE, decision.trigger)
        assertEquals(KeyframeScene.TICK_MS, decision.atMs)
        assertEquals(1, rig.scheduler.stats.triggerCount(Trigger.SCENE_CHANGE))
    }

    @Test
    fun `un masque perdu passe devant une coupure de scene`() {
        val rig = Rig()
        rig.request()
        rig.respond()
        rig.clock.advance(KeyframeScene.TICK_MS)
        assertEquals(Trigger.LOST, rig.request(FrameSignal.Lost).trigger)
        assertEquals(1, rig.scheduler.stats.triggerCount(Trigger.LOST))
    }

    @Test
    fun `l erreur doit durer deux frames avant de demander`() {
        val rig = Rig()
        rig.request()
        rig.respond()
        rig.clock.advance(KeyframeScene.TICK_MS)
        assertEquals(HoldReason.STABLE, rig.hold(HighDrift).reason)
        rig.clock.advance(KeyframeScene.TICK_MS)
        val second = rig.hold(HighDrift)
        assertEquals(HoldReason.MIN_INTERVAL, second.reason)
        rig.clock.advance(200L - 2 * KeyframeScene.TICK_MS)
        assertEquals(Trigger.REPROJECTION, rig.request(HighDrift).trigger)
    }

    @Test
    fun `une seule frame au-dessus du seuil ne suffit pas`() {
        val rig = Rig()
        rig.request()
        rig.respond()
        rig.clock.advance(250L)
        assertEquals(HoldReason.STABLE, rig.hold(HighDrift).reason)
    }

    @Test
    fun `le keyframe expire a 500 ms`() {
        val rig = Rig()
        rig.request()
        rig.respond()
        rig.clock.advance(499L)
        assertEquals(HoldReason.STABLE, rig.hold().reason)
        rig.clock.advance(1L)
        assertEquals(Trigger.EXPIRED, rig.request().trigger)
    }

    @Test
    fun `la veille statique etire le plafond a 1 000 ms`() {
        val rig = Rig(KeyframeConfig(allowStaticSlack = true))
        rig.request()
        rig.respond()
        rig.clock.advance(600L)
        val hold = rig.hold()
        assertEquals(HoldReason.STABLE, hold.reason)
        assertEquals(400L, hold.retryInMs)
        rig.clock.advance(400L)
        assertEquals(Trigger.EXPIRED, rig.request().trigger)
    }

    @Test
    fun `la veille statique est refusee par defaut`() {
        val rig = Rig()
        rig.request()
        rig.respond()
        rig.clock.advance(600L)
        assertEquals(Trigger.EXPIRED, rig.request().trigger)
    }

    @Test
    fun `le budget arrete la cinquieme demande d une seconde`() {
        val rig = Rig()
        val seen = rig.drive(untilMs = 990L, signal = HighDrift, residualError = 1f)
        val requests = seen.filterIsInstance<KeyframeDecision.Request>()
        assertEquals(4, requests.size)
        assertEquals(
            listOf(Trigger.STARTUP, Trigger.REPROJECTION, Trigger.REPROJECTION, Trigger.REPROJECTION),
            requests.map { it.trigger },
        )
        assertEquals(HoldReason.BUDGET, (seen.last() as KeyframeDecision.Hold).reason)
    }

    @Test
    fun `le jeton d urgence n est accorde qu une fois`() {
        val rig = Rig()
        val seen = rig.drive(untilMs = 200L, signal = SceneCut)
        val requests = seen.filterIsInstance<KeyframeDecision.Request>()
        assertEquals(3, requests.size)
        assertEquals(HoldReason.BUDGET, (seen.last() as KeyframeDecision.Hold).reason)
    }

    @Test
    fun `un keyframe sans reponse est declare perdu au bout de 12 s`() {
        val rig = Rig()
        rig.request()
        rig.clock.advance(11_967L)
        assertEquals(HoldReason.IN_FLIGHT, rig.hold().reason)
        rig.clock.advance(33L)
        val hold = rig.hold()
        assertEquals(HoldReason.BACKOFF, hold.reason)
        assertEquals(1, rig.scheduler.stats.timeoutsDeclared)
        assertEquals(1, rig.scheduler.consecutiveFailures)
        assertFalse(rig.scheduler.inFlight)
    }

    @Test
    fun `un doublon et une reponse tardive sont ignores`() {
        val rig = Rig()
        rig.request()
        rig.respond()
        rig.respond()
        assertEquals(1, rig.scheduler.stats.successes)
        assertEquals(1, rig.scheduler.stats.ignoredResults)

        val late = Rig()
        late.request()
        late.clock.advance(12_012L)
        late.hold()
        late.clock.advance(33L)
        late.respond(requestAtMs = 0L)
        assertEquals(0, late.scheduler.stats.successes)
        assertEquals(1, late.scheduler.stats.ignoredResults)
        assertEquals(-1L, late.scheduler.keyframeAgeMs)
    }

    @Test
    fun `une reponse mal appariee est ignoree`() {
        val rig = Rig()
        rig.request()
        rig.clock.advance(100L)
        rig.respond(requestAtMs = 99L)
        assertEquals(1, rig.scheduler.stats.ignoredResults)
        assertTrue(rig.scheduler.inFlight)
        rig.respond(requestAtMs = 0L)
        assertFalse(rig.scheduler.inFlight)
        assertEquals(1, rig.scheduler.stats.successes)
    }

    @Test
    fun `reset remet le scheduler a neuf`() {
        val rig = Rig()
        rig.request()
        rig.respond(success = false)
        rig.clock.advance(1_000L)
        rig.scheduler.reset()
        assertFalse(rig.scheduler.inFlight)
        assertEquals(0, rig.scheduler.consecutiveFailures)
        assertEquals(0, rig.scheduler.stats.frames)
        assertEquals(0, rig.scheduler.stats.requests)
        assertEquals(Trigger.STARTUP, rig.request().trigger)
    }

    @Test
    fun `un estimateur casse ne bloque jamais les keyframes`() {
        val rig = Rig()
        assertEquals(Trigger.STARTUP, rig.request(FrameSignal(Float.NaN, Float.NaN, true)).trigger)
        val sanitized = FrameSignal(Float.NaN, Float.POSITIVE_INFINITY, true).sanitized
        assertEquals(0f, sanitized.reprojectionError, 0f)
        assertEquals(0f, sanitized.sceneChange, 0f)
        rig.respond(residualError = Float.NaN)
        assertEquals(0f, FrameSignal(Float.NaN, 0.9f, true).sanitized.sceneChange, 0f)
        rig.clock.advance(1_000L)
        assertEquals(Trigger.EXPIRED, rig.request().trigger)
    }

    @Test
    fun `les compteurs rapportent les motifs et le ratio de keyframes`() {
        val rig = Rig()
        rig.drive(untilMs = 400L, signal = FrameSignal.Still)
        assertEquals(1, rig.scheduler.stats.requests)
        assertEquals(1, rig.scheduler.stats.successes)
        assertTrue(rig.scheduler.stats.holdCount(HoldReason.STABLE) > 0)
        assertEquals(0, rig.scheduler.stats.frames - 1 - rig.scheduler.stats.holdCount(HoldReason.STABLE))
        assertEquals(
            rig.scheduler.stats.successes.toFloat() / rig.scheduler.stats.frames,
            rig.scheduler.stats.keyframeRatio,
            0.0001f,
        )
        assertEquals(Rig.LATENCY_MS, rig.scheduler.stats.p95LatencyMs)
    }
}