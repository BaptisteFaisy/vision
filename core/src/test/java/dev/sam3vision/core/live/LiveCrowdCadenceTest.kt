package dev.sam3vision.core.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import dev.sam3vision.core.net.LiveClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cadence de foule (audit foule du 01/11, P0 point 1 « guider vite, et tout le temps ») :
 * en foule on garde le double vol jusqu'à 600 ms de latence moyenne. 1,43 s pour un piéton
 * frontal à 2,7 m/s ne laisse que deux frames à 1,4 ips — on ne peut pas en perdre une
 * parce que le budget de [Cadence.Fast] (150 ms) a refermé les créneaux.
 */
class LiveCrowdCadenceTest {

    @Test
    fun `la foule garde deux creneaux et un budget de 600 ms`() {
        assertEquals(2, Cadence.Crowd.concurrency)
        assertEquals(600L, Cadence.Crowd.latencyBudgetMs)
        assertEquals("pas de plancher : on repart dès qu'un créneau se libère", 0L, Cadence.Crowd.fastMs)
        assertEquals(0L, Cadence.Crowd.targetMs)
        assertEquals("le palier lent du direct ne bouge pas", Cadence.Fast.slowMs, Cadence.Crowd.slowMs)
    }

    @Test
    fun `la cadence rapide garde son budget de 150 ms`() {
        assertEquals(150L, Cadence.Fast.latencyBudgetMs)
        assertEquals(2, Cadence.Fast.concurrency)
    }

    @Test
    fun `a 300 ms de moyenne la foule ne referme pas les creneaux`() {
        val crowd = session(Cadence.Crowd)
        (1..3).forEach { _ -> crowd.stats.record(300L) }
        crowd.regulate()
        assertEquals("budget 600 ms : le second vol reste ouvert", 2, crowd.slots)
    }

    @Test
    fun `a 300 ms de moyenne la cadence rapide referme a un creneau`() {
        val fast = session(Cadence.Fast)
        (1..3).forEach { _ -> fast.stats.record(300L) }
        fast.regulate()
        assertEquals("budget 150 ms dépassé : un seul vol", 1, fast.slots)
    }

    @Test
    fun `la bascule a chaud rouvre le second vol sans perdre les mesures`() {
        val session = session(Cadence.Fast)
        (1..3).forEach { _ -> session.stats.record(300L) }
        session.regulate()
        assertEquals(1, session.slots)

        session.useCadence(Cadence.Crowd)
        assertEquals("la foule rouvre le créneau refermé par le budget rapide", 2, session.slots)
        assertEquals("les mesures de la séance sont gardées", 3, session.stats.samples)

        session.useCadence(Cadence.Fast)
        session.regulate()
        assertEquals("sortie de foule : le budget de 150 ms referme", 1, session.slots)
    }

    /** Session sans transport : la cadence se règle sur [LiveSession.stats], pas sur le lien. */
    private fun session(cadence: Cadence) = LiveSession(SilentClient(), cadence = cadence)

    /** Client muet : aucun réseau, aucune réponse publiée dans ces tests. */
    private class SilentClient : LiveClient {
        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> = Result.success(LiveFrame(512, 1138, 0L, emptyList()))

        override suspend fun health(): Result<LiveHealth> =
            Result.success(LiveHealth(true, "sam3.1", "cuda", "A100"))
    }
}
