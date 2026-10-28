package dev.sam3vision.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SAM scripté : `/health` suit le scénario, `segment` ne sert jamais à la veille. */
private class ScriptedSam(private val script: MutableList<Result<Boolean>>) : SamClient {
    var healthCalls = 0
        private set

    override suspend fun segment(pngOrJpegB64: String, prompts: List<String>?): Result<dev.sam3vision.core.model.SegmentResult> =
        Result.failure(ApiError.NotSupported("sonde seule"))

    override suspend fun health(): Result<Boolean> {
        healthCalls++
        return if (script.size > 1) script.removeAt(0) else script.first()
    }
}

/**
 * La veille ne sonde que sur `Down`, s'arrête au premier succès et reste bornée :
 * c'est ce qui la rend gratuite tant que la carte est éteinte (404 vite, rien ne se
 * réveille) et ce qui empêche de tenir la carte éveillée (jamais de sonde sur `Up`).
 */
class GpuMonitorTest {

    private val endpoint = "https://sam.test"

    private fun monitor(
        sam: SamClient = ScriptedSam(mutableListOf(Result.success(true))),
        maxProbes: Int = GpuMonitor.DEFAULT_MAX_PROBES,
        sleeps: MutableList<Long>? = null,
        built: MutableList<String>? = null,
    ): GpuMonitor = GpuMonitor(
        samClient = { url -> built?.add(url); sam },
        maxProbes = maxProbes,
        sleeper = { if (sleeps == null) error("sonde inattendue") else sleeps.add(it) },
    )

    @Test
    fun `track adopte l endpoint et repart d inconnu`() {
        val monitor = monitor()
        monitor.track(endpoint)
        monitor.noteHealthResult(endpoint, Result.success(true))
        assertEquals(GpuStatus(endpoint, GpuPresence.Up), monitor.status.value)

        monitor.track("https://autre.test")

        assertEquals(GpuStatus("https://autre.test", GpuPresence.Unknown), monitor.status.value)
    }

    @Test
    fun `track du meme endpoint — l etat observe est garde`() {
        val monitor = monitor()
        monitor.track(endpoint)
        monitor.noteHealthResult(endpoint, Result.success(true))

        monitor.track("  $endpoint  ")

        assertEquals(GpuStatus(endpoint, GpuPresence.Up), monitor.status.value)
    }

    @Test
    fun `notes sans endpoint suivi — ignorees`() {
        val monitor = monitor()

        monitor.noteHealthResult(endpoint, Result.success(true))
        monitor.noteSamResult(endpoint, Result.failure(ApiError.Network()))

        assertEquals(GpuStatus(), monitor.status.value)
    }

    @Test
    fun `noteSamResult — succes Up, echec Down avec motif`() {
        val monitor = monitor()
        monitor.track(endpoint)

        monitor.noteSamResult(endpoint, Result.failure(ApiError.Network("réseau")))
        assertEquals(GpuPresence.Down("réseau"), monitor.status.value.presence)

        monitor.noteSamResult(
            endpoint,
            Result.success(dev.sam3vision.core.model.SegmentResult(10, 10, emptyList())),
        )
        assertEquals(GpuPresence.Up, monitor.status.value.presence)
    }

    @Test
    fun `note d un autre endpoint — ignoree, pas d attribution croisee`() {
        val monitor = monitor()
        monitor.track(endpoint)
        monitor.noteHealthResult(endpoint, Result.success(true))

        monitor.noteHealthResult("https://autre.test", Result.failure(ApiError.Network()))

        assertEquals(GpuStatus(endpoint, GpuPresence.Up), monitor.status.value)
    }

    @Test
    fun `noteHealthResult faux — Down avec motif health`() {
        val monitor = monitor()
        monitor.track(endpoint)

        monitor.noteHealthResult(endpoint, Result.success(false))

        assertEquals(GpuPresence.Down(GpuMonitor.HEALTH_FALSE_REASON), monitor.status.value.presence)
    }

    @Test
    fun `watch sans endpoint, sur Up ou Unknown — aucun appel`() = runBlocking {
        val sam = ScriptedSam(mutableListOf(Result.success(true)))
        val built = mutableListOf<String>()
        val monitor = monitor(sam = sam, built = built)

        monitor.watch()
        monitor.track(endpoint)
        monitor.watch()
        monitor.noteHealthResult(endpoint, Result.success(true))
        monitor.watch()

        assertEquals("aucun client construit", emptyList<String>(), built)
        assertEquals(0, sam.healthCalls)
    }

    @Test
    fun `watch sur Down — stoppe au premier succes`() = runBlocking {
        val sam = ScriptedSam(
            mutableListOf(
                Result.failure(ApiError.Network()),
                Result.failure(ApiError.Network()),
                Result.success(true),
                Result.success(true),
            ),
        )
        val sleeps = mutableListOf<Long>()
        val monitor = monitor(sam = sam, sleeps = sleeps)
        monitor.track(endpoint)
        monitor.noteHealthResult(endpoint, Result.failure(ApiError.Network()))

        monitor.watch()

        assertEquals(GpuStatus(endpoint, GpuPresence.Up), monitor.status.value)
        assertEquals("sondes jusqu'au succès", 3, sam.healthCalls)
        assertEquals(listOf(10_000L, 10_000L), sleeps)
    }

    @Test
    fun `watch borne — reste Down apres maxProbes`() = runBlocking {
        val sam = ScriptedSam(mutableListOf(Result.failure(ApiError.Network("coupé"))))
        val sleeps = mutableListOf<Long>()
        val monitor = monitor(sam = sam, maxProbes = 3, sleeps = sleeps)
        monitor.track(endpoint)
        monitor.noteHealthResult(endpoint, Result.failure(ApiError.Network()))

        monitor.watch()

        assertEquals(GpuPresence.Down("coupé"), monitor.status.value.presence)
        assertEquals(3, sam.healthCalls)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun `watch annulee avant verdict — restaure Down`() = runBlocking {
        val hang = CompletableDeferred<Result<Boolean>>()
        val sam = object : SamClient {
            override suspend fun segment(
                pngOrJpegB64: String,
                prompts: List<String>?,
            ): Result<dev.sam3vision.core.model.SegmentResult> =
                Result.failure(ApiError.NotSupported("sonde seule"))

            override suspend fun health(): Result<Boolean> = hang.await()
        }
        val monitor = monitor(sam = sam, sleeps = mutableListOf())
        monitor.track(endpoint)
        monitor.noteHealthResult(endpoint, Result.failure(ApiError.Network("coupé")))

        val job = launch { monitor.watch() }
        repeat(1_000) {
            if (monitor.status.value.presence is GpuPresence.Checking) return@repeat
            yield()
        }
        assertTrue("veille partie", monitor.status.value.presence is GpuPresence.Checking)
        job.cancel()
        job.join()

        assertEquals(GpuPresence.Down("coupé"), monitor.status.value.presence)
    }

    @Test
    fun `changement d endpoint pendant la veille — arrete, sans toucher au nouvel etat`() =
        runBlocking {
            val sam = ScriptedSam(mutableListOf(Result.failure(ApiError.Network())))
            lateinit var watching: GpuMonitor
            watching = GpuMonitor(
                samClient = { sam },
                maxProbes = 10,
                sleeper = { watching.track("https://autre.test") },
            )
            watching.track(endpoint)
            watching.noteHealthResult(endpoint, Result.failure(ApiError.Network()))

            watching.watch()

            assertEquals("une seule sonde avant la bascule", 1, sam.healthCalls)
            assertEquals(
                GpuStatus("https://autre.test", GpuPresence.Unknown),
                watching.status.value,
            )
        }
}
