package dev.sam3vision.core.live

import dev.sam3vision.core.net.RecordingLiveClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LivePump] : cadence continue (latence/2, 33-900 ms), **une** frame en réserve, aucune
 * file. Aucune horloge réelle : la pompe reçoit une horloge virtuelle, donc les quatre cas
 * sont déterministes et instantanés.
 *
 * Le transport est un [RecordingLiveClient] : la pompe ne doit **jamais** l'appeler (c'est
 * l'appelant qui envoie), et chaque cas vérifie `liveCalls == 0`.
 */
class LivePumpTest {

    @Test
    fun `a deux creneaux la frame part aussitot au lieu d_attendre en reserve`() {
        val gate = FrameGate(2)
        assertTrue(gate.tryStart()) // un vol en cours, un créneau libre
        val stats = LiveStats().apply { record(240L) }
        val session = LiveSession(client, gate, stats = stats, cadence = Cadence.Fast, clock = { now })
        val pump = LivePump({ session }, { now })

        assertTrue("créneau libre : envoi immédiat, pas de réserve", pump.offer("A", 1L, 2L))
        assertFalse(pump.pending)
        assertTrue(gate.tryStart()) // deuxième vol : saturé
        assertFalse("saturé : en réserve", pump.offer("B", 3L, 4L))
        assertTrue(pump.pending)
        assertEquals(0, client.liveCalls)
    }

    /** Le client de test compte les appels : zéro attendu, la pompe ne parle pas au réseau. */
    private val client = RecordingLiveClient()

    /** Horloge virtuelle en ms : un cas avance le temps, aucun thread, aucun `sleep`. */
    private var now = 1_000L

    private fun sessionOf(gate: FrameGate, averageMs: Long = 0L): LiveSession {
        val stats = LiveStats().apply { if (averageMs > 0L) record(averageMs) }
        return LiveSession(client, gate, stats = stats, clock = { now })
    }

    @Test
    fun `la cadence continue est respectee, reset la rend immediate`() {
        val session = sessionOf(FrameGate(), averageMs = 240L)
        val pump = LivePump({ session }, { now })

        assertEquals("latence 240 ms → moitié", 120L, session.intervalMsContinuous())
        assertEquals("plancher : une frame caméra", LiveSession.MIN_MS, sessionOf(FrameGate(), 20L).intervalMsContinuous())
        assertEquals("plafond : le palier lent", LiveSession.MAX_MS, sessionOf(FrameGate(), 4_000L).intervalMsContinuous())
        assertEquals(
            "sans mesure, la cible du profil",
            Cadence.Direct.targetMs,
            sessionOf(FrameGate()).intervalMsContinuous(),
        )

        assertTrue("premier créneau : immédiat", pump.offer("A", 1L, 2L))
        assertFalse("juste avant l'échéance : jetée", pump.offer("B", 3L, 4L))
        assertFalse("jetée veut dire : pas de réserve", pump.pending)
        now += 119L
        assertFalse(pump.offer("C", 5L, 6L))
        now += 1L
        assertTrue("à l'échéance : elle part", pump.offer("D", 7L, 8L))

        pump.reset()
        assertTrue("nouvelle séance : plus d'échéance en cours", pump.offer("E", 9L, 10L))
        assertEquals("aucun envoi ne passe par la pompe", 0, client.liveCalls)
    }

    @Test
    fun `une frame vide ou sans session n_est jamais envoyee ni reservee`() {
        val session = sessionOf(FrameGate())
        val pump = LivePump({ session }, { now })

        assertFalse("image vide", pump.offer(""))
        assertFalse("espaces seuls", pump.offer("   "))
        assertFalse(pump.pending)
        assertEquals("rien de jeté : il n'y avait rien à jeter", 0L, pump.droppedFrames)

        val orphan = LivePump({ null }, { now })
        assertFalse("sans session, la caméra ne convertit rien", orphan.wantsFrame())
        assertFalse(orphan.offer("A", 1L, 2L))
        assertFalse(orphan.pending)
    }

    @Test
    fun `pendant un envoi la frame va en reserve, la plus recente ecrase l_ancienne`() {
        val gate = FrameGate()
        gate.tryStart()
        val session = sessionOf(gate, averageMs = 240L)
        val pump = LivePump({ session }, { now })

        assertTrue("créneau pris, réserve vide : la caméra prépare la suivante", pump.wantsFrame())
        assertFalse("la frame attend son tour", pump.offer("A", 1L, 2L))
        assertFalse(pump.offer("B", 3L, 4L))
        assertTrue(pump.pending)
        assertFalse("réserve pleine : convertir une image de plus serait du CPU jeté", pump.wantsFrame())
        assertEquals("une frame remplacée, donc jetée", 1L, pump.droppedFrames)
        assertEquals(0, client.liveCalls)
    }

    @Test
    fun `send vide la reserve sans attendre le tick`() {
        val gate = FrameGate()
        gate.tryStart()
        val session = sessionOf(gate, averageMs = 240L)
        val pump = LivePump({ session }, { now })

        pump.offer("vieille", 11L, 12L)
        pump.offer("recente", 21L, 22L)
        gate.finish()
        val out = FramePayload()
        assertTrue("la réserve part dès la libération du créneau", pump.send(out))
        assertEquals("la plus récente, pas la première", "recente", out.jpegB64)
        assertEquals(21L, out.captureAtNs)
        assertEquals(22L, out.encodedAtNs)
        assertFalse(pump.pending)
        assertFalse("rien n'attendait plus", pump.send(FramePayload()))
        assertFalse("l'échéance vient d'être reposée", pump.offer("trop tot", 31L, 32L))

        now += session.intervalMsContinuous()
        assertTrue("à l'échéance suivante : elle part", pump.offer("a l'echeance", 33L, 34L))
        assertEquals(0, client.liveCalls)
    }
}