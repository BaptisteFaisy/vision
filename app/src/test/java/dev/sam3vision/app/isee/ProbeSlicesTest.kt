package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.live.GuidancePulse
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.live.SteeringCue
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.traverse.TraversePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le scopage pixels/chiffres, verrouillé écriture par écriture : la clé pixels
 * ne bouge qu'en #1 (+ raz), la clé chiffres qu'en #2 (+ battements), la clé
 * notice que sur ses trois champs — et la voix voit exactement les mêmes
 * événements dans la tranche chiffres que dans l'état brut (même opérateur
 * que `observeSpoken`, aucune frame ni phrase perdue ou ajoutée).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeSlicesTest {

    /** Horloge monotone pilotée (ns), comme `imageInfo.timestamp`. */
    private var ns = 0L
    private val clock: () -> Long get() = { ns }

    private fun at(ms: Long) {
        ns = ms * 1_000_000L
    }

    /** Frame sans objet : aucun masque à décorer, aucun Android à moquer. */
    private fun frame() = LiveFrame(width = 512, height = 1138, elapsedMs = 100L, objects = emptyList())

    @Test
    fun `cle pixels, ecriture 1 oui, ecriture 2 non`() {
        val base = LiveProbeState()
        val afterPixels = base.copy(pixelSeq = 1, width = 512, height = 1138)
        assertTrue("l'écriture #1 (seq + taille) change la clé pixels", pixelKeyOf(afterPixels) != pixelKeyOf(base))
        val afterRest = afterPixels.copy(frames = 1, spokenSeq = 1, distanceLayer = DistanceLayer(width = 1))
        assertEquals("l'écriture #2 ne touche pas la clé pixels", pixelKeyOf(afterPixels), pixelKeyOf(afterRest))
    }

    @Test
    fun `cle chiffres, ecriture 2 oui, ecriture 1 non`() {
        val base = LiveProbeState()
        val afterPixels = base.copy(pixelSeq = 1, width = 512, height = 1138, latencyMs = 200L)
        assertEquals("l'écriture #1 ne touche pas la clé chiffres", restKeyOf(base), restKeyOf(afterPixels))
        val afterRest = afterPixels.copy(frames = 1, spokenSeq = 1, distanceLayer = DistanceLayer(width = 1))
        assertTrue("l'écriture #2 (frames + distances) change la clé chiffres", restKeyOf(afterRest) != restKeyOf(afterPixels))
        val beat = afterRest.copy(pulse = GuidancePulse(atMs = 1L, ageMs = 0L, stale = false, word = GuidanceWord.Straight, side = null, shiftMeters = null, nearestMeters = null))
        assertTrue("le battement 500 ms change la clé chiffres", restKeyOf(beat) != restKeyOf(afterRest))
        val crossing = afterRest.copy(
            crossingActive = true,
            crossing = CrossingState.Red,
            crossingBox = listOf(0f, 0f, 1f, 1f),
            traversePhase = TraversePhase.ATTENDRE,
            scanLines = CrosswalkScanLines(current = "x", left = null, right = null, accent = 0),
            steering = SteeringCue(cap = SteeringCap.Straight, blocked = false, passage = null),
            followed = listOf("feu"),
        )
        assertTrue("la Traversée change la clé chiffres", restKeyOf(crossing) != restKeyOf(afterRest))
    }

    @Test
    fun `cle notice, frames erreur reveil, rien d autre`() {
        val base = LiveProbeState()
        assertEquals("l'écriture #1 ne touche pas la notice", noticeKeyOf(base), noticeKeyOf(base.copy(pixelSeq = 1)))
        assertTrue("frames change la notice", noticeKeyOf(base.copy(frames = 1)) != noticeKeyOf(base))
        assertTrue("l'erreur change la notice", noticeKeyOf(base.copy(error = "GPU waking up")) != noticeKeyOf(base))
        assertTrue("le réveil change la notice", noticeKeyOf(base.copy(warming = true)) != noticeKeyOf(base))
        assertEquals("le battement ne touche pas la notice",
            noticeKeyOf(base),
            noticeKeyOf(base.copy(pulse = GuidancePulse(atMs = 1L, ageMs = 0L, stale = false, word = GuidanceWord.Straight, side = null, shiftMeters = null, nearestMeters = null))))
    }

    @Test
    fun `noteDrawn et motion, aucune cle scopee ne bouge, le panneau les lit`() {
        val base = LiveProbeState(pixelSeq = 2, frames = 2)
        // `noteDrawn` pose le poste PIXEL après le dessin : le chrono du panneau plein
        // état le lit, les feuilles scopées n'ont pas à se réveiller.
        val drawn = base.copy(timings = FrameTimings(captureToJpegMs = 3))
        assertEquals("timings seul : clé pixels intacte", pixelKeyOf(base), pixelKeyOf(drawn))
        assertEquals("timings seul : clé chiffres intacte", restKeyOf(base), restKeyOf(drawn))
        assertEquals("timings seul : clé notice intacte", noticeKeyOf(base), noticeKeyOf(drawn))
        val moved = base.copy(motion = MotionDebug(verdict = "caméra bouge"))
        assertEquals("motion seul : clé pixels intacte", pixelKeyOf(base), pixelKeyOf(moved))
        assertEquals("motion seul : clé chiffres intacte", restKeyOf(base), restKeyOf(moved))
        assertTrue("le panneau lit bien le chrono", liveProbeTimingsLine(drawn) != liveProbeTimingsLine(base))
        assertTrue("le panneau lit bien le mouvement", liveProbeMotionLine(moved).isNotBlank())
    }

    @Test
    fun `raz, les trois cles bougent, l ecran se vide`() {
        val live = LiveProbeState(pixelSeq = 3, frames = 3, masks = emptyList(), width = 512, error = "boom")
        val cleared = live.cleared()
        assertTrue("le raz change la clé pixels", pixelKeyOf(cleared) != pixelKeyOf(live))
        assertTrue("le raz change la clé chiffres", restKeyOf(cleared) != restKeyOf(live))
        assertTrue("le raz change la clé notice", noticeKeyOf(cleared) != noticeKeyOf(live))
    }

    /**
     * Deux frames publiées : les pixels voient `seq` 1..2, les chiffres voient
     * `frames` 1..2, la notice suit ses trois champs — et le triplet voix
     * (`observeSpoken`) est identique dans la tranche chiffres et dans l'état
     * brut : aucune frame ni phrase perdue ou ajoutée par le scopage.
     */
    @Test
    fun `deux frames, pixels et chiffres separes, voix identique`() = runTest {
        val fake = BlockingClient(mutableListOf())
        val probe = probe(this, fake)
        // Tickers coupés : battements et prédictions en temps réel rendraient
        // les comptes non déterministes (les clés pures les couvrent déjà).
        probe.pulse.stop()
        probe.meters.stopPredictions()

        val pixelSeqs = mutableListOf<Int>()
        val restSnaps = mutableListOf<LiveProbeState>()
        val rawSpoken = mutableListOf<SpokenEmission>()
        val restSpoken = mutableListOf<SpokenEmission>()
        val notices = mutableListOf<ProbeNoticeKey>()
        // Collecteurs non confinés : chaque émission est vue (un collecteur confiné
        // conflaterait deux écritures dos à dos et raterait une valeur).
        val collectors = listOf(
            launch(UnconfinedTestDispatcher(testScheduler)) { probe.state.pixelStates().collect { pixelSeqs += it.pixelSeq } },
            launch(UnconfinedTestDispatcher(testScheduler)) { probe.state.restStates().collect { restSnaps += it } },
            launch(UnconfinedTestDispatcher(testScheduler)) { probe.state.map { SpokenEmission(it.spoken, it.spokenUrgent, it.spokenSeq) }.distinctUntilChanged().collect { rawSpoken += it } },
            launch(UnconfinedTestDispatcher(testScheduler)) { probe.state.restStates().map { SpokenEmission(it.spoken, it.spokenUrgent, it.spokenSeq) }.distinctUntilChanged().collect { restSpoken += it } },
            launch(UnconfinedTestDispatcher(testScheduler)) { probe.state.noticeStates().collect { notices += noticeKeyOf(it) } },
        )
        advanceUntilIdle()

        at(0)
        probe.onFrame("A", captureAtNs = 1_000_000L)
        advanceUntilIdle()
        probe.onFrame("B", captureAtNs = 2_000_000L)
        at(40)
        fake.release(frame())
        advanceUntilIdle()
        collectors.forEach { it.cancel() }

        assertEquals("A puis B publiées", 2, fake.sent)
        assertEquals("deux frames au compteur", 2, probe.state.value.frames)
        assertEquals("deux écritures #1", 2, probe.state.value.pixelSeq)
        assertEquals("pixels : seq 1..2, une fois chacun, jamais les chiffres", listOf(0, 1, 2), pixelSeqs)
        assertEquals("chiffres : frames 1..2, une fois chacun, jamais les pixels",
            listOf(0, 1, 2), restSnaps.map { it.frames })
        assertEquals("voix : mêmes triplets dans la tranche chiffres et l'état brut", rawSpoken, restSpoken)
        assertEquals("voix : la tranche chiffres porte la dernière phrase", probe.state.value.spokenSeq, restSpoken.last().seq)
        assertEquals("voix : la tranche chiffres ne perd pas la fin", probe.state.value, restSnaps.last())
        assertNoticesFollowFrames(notices)
    }

    /** Préambule de chauffe puis frames : la notice part chaude, s'éteint au réveil GPU, puis suit frames 1..2 — jamais les pixels. */
    private fun assertNoticesFollowFrames(notices: List<ProbeNoticeKey>) {
        assertEquals("notice : frames 1..2, une fois chacun, jamais les pixels",
            listOf(
                ProbeNoticeKey(frames = 0, error = null, warming = true),
                ProbeNoticeKey(frames = 0, error = null, warming = false),
                ProbeNoticeKey(frames = 1, error = null, warming = false),
                ProbeNoticeKey(frames = 2, error = null, warming = false),
            ),
            notices)
    }

    /** Sonde guidée branchée sur un client sans transport, sans réseau ni GPU. */
    private fun probe(scope: TestScope, fake: BlockingClient): LiveProbe {
        val built = LiveProbe(
            scope = scope,
            clock = clock,
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, _ -> fake },
        )
        // Pas d'adaptatif ici : le test verrouille le scopage, les deux frames partent.
        built.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
        built.configure(ENDPOINT, guide = true)
        built.begin()
        return built
    }

    private companion object {
        const val ENDPOINT = "https://sam.test"
    }

    /**
     * Client de test : consigne chaque envoi dans [journal] et **attend** que le test
     * livre la réponse (partagée : la règle veut qu'une seule frame suive la première).
     */
    private class BlockingClient(private val journal: MutableList<String>) : LiveClient {

        var sent: Int = 0
            private set

        private val reply = CompletableDeferred<Result<LiveFrame>>()

        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> {
            sent += 1
            journal += jpegB64
            return reply.await()
        }

        /** Livre LA réponse : A puis B la reçoivent tour à tour. */
        fun release(frame: LiveFrame) {
            reply.complete(Result.success(frame))
        }

        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(ok = true, model = "sam3.1", device = "cuda", gpu = "A100"))
    }
}
