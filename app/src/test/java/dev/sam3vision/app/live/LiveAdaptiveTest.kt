package dev.sam3vision.app.live

import dev.sam3vision.core.keyframe.KeyframeScheduler
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Envois adaptatifs (Q8+Q9) : éteints, le direct est celui d'avant (tout passe,
 * qualité constante) ; allumés, le régulateur marche les paliers qualité 0-2 sur
 * la part réseau mesurée et le scheduler ne fait envoyer que les keyframes.
 */
class LiveAdaptiveTest {

    private val base = 65
    private var now = 1_000L
    private fun scheduler() = KeyframeScheduler(clock = { now })
    private fun adaptive() = LiveAdaptive(baseQuality = base, scheduler = scheduler())

    /** Une réponse serveur : `elapsedMs` annoncé, un objet par centre, ou aucun. */
    private fun frame(vararg centersX: Float, elapsedMs: Long = 40L): LiveFrame = LiveFrame(
        width = 512,
        height = 288,
        elapsedMs = elapsedMs,
        objects = centersX.map { center ->
            SegObject(
                label = "personne",
                score = 0.9f,
                box = listOf(center - 40f, 100f, center + 40f, 180f),
                maskPngB64 = "",
            )
        },
    )

    /** Réponse soldée : part réseau = aller-retour − `elapsed_ms`, 3 Ko sur le fil. */
    private fun settled(adaptive: LiveAdaptive, roundTripMs: Long, result: Result<LiveFrame>?) =
        adaptive.onSettled(roundTripMs, result, base64Length = 4_000)

    /** Envoie des parts réseau [shares] (aller-retour = part + `elapsed_ms` 40). */
    private fun drive(adaptive: LiveAdaptive, shares: List<Long>) {
        shares.forEach { share -> settled(adaptive, share + 40L, Result.success(frame(100f))) }
    }

    /**
     * Charge persistante qui allège vraiment : chaque palier fait gagner ~12-15 ms
     * mesurés, le régulateur descend 0 → 1 → 2 → 3 (2 frames de sonde + 2 de mesure
     * par palier, la première sonde part dès la 2ᵉ frame).
     */
    private val descent = listOf(
        320L, 320L,
        300L, 300L, 300L, 300L,
        280L, 280L, 280L, 280L,
        260L, 260L,
    )

    @Test
    fun `eteint tout passe et la qualite est la constante`() {
        val adaptive = adaptive()

        assertTrue(adaptive.admit())
        assertTrue(adaptive.admit())
        assertEquals(base, adaptive.jpegQuality)
        assertEquals(0, adaptive.tier)
        assertNull(settled(adaptive, 340L, Result.success(frame(100f))))
    }

    @Test
    fun `le regulateur descend puis remonte les paliers qualite`() {
        val adaptive = adaptive()
        adaptive.configure(uploadEnabled = true, keyframesEnabled = false)

        // Part réseau 300 ms ×2 : sonde du palier 1, acceptée (gain 38 ms).
        settled(adaptive, 340L, Result.success(frame(100f)))
        settled(adaptive, 340L, Result.success(frame(100f)))
        assertEquals(1, adaptive.tier)
        settled(adaptive, 280L, Result.success(frame(100f)))
        val status = settled(adaptive, 280L, Result.success(frame(100f)))
        assertNotNull(status)
        assertEquals(1, adaptive.tier)
        assertEquals(base - 10, adaptive.jpegQuality)

        // Part réseau 100 ms : retour au palier 0 (qualité pleine).
        settled(adaptive, 140L, Result.success(frame(100f)))
        settled(adaptive, 140L, Result.success(frame(100f)))
        settled(adaptive, 140L, Result.success(frame(100f)))
        settled(adaptive, 140L, Result.success(frame(100f)))
        assertEquals(0, adaptive.tier)
        assertEquals(base, adaptive.jpegQuality)
    }

    @Test
    fun `le scheduler ne fait envoyer que les keyframes`() {
        val adaptive = adaptive()
        adaptive.configure(uploadEnabled = false, keyframesEnabled = true)

        assertTrue(adaptive.admit()) // STARTUP : première demande.
        assertFalse(adaptive.admit()) // keyframe en vol : propagation locale.
        assertNull(settled(adaptive, 120L, Result.success(frame(100f))))
        assertFalse(adaptive.admit()) // masque frais : propagation locale.

        now += 600L
        assertTrue(adaptive.admit()) // EXPIRED : le masque a vieilli.
        settled(adaptive, 120L, Result.success(frame()))
        assertTrue(adaptive.admit()) // LOST : plus aucun masque, urgent.
        settled(adaptive, 120L, Result.success(frame(100f)))

        now += 600L
        assertTrue(adaptive.admit()) // EXPIRED de nouveau.
        adaptive.onDropped() // admise mais jetée : créneau libéré, pas bloqué.
        assertFalse(adaptive.admit()) // repli : pas de redemande aussitôt.
        now += 600L
        assertTrue(adaptive.admit()) // RETRY : repli écoulé.
    }

    @Test
    fun `sous charge persistante le palier 3 rend base moins 30`() {
        listOf(65 to 35, 55 to 25).forEach { (base, bas) ->
            val adaptive = LiveAdaptive(baseQuality = base)
            adaptive.configure(uploadEnabled = true, keyframesEnabled = false)

            drive(adaptive, descent)

            assertEquals(3, adaptive.tier)
            assertEquals(bas, adaptive.jpegQuality)
        }
    }

    @Test
    fun `sans gain mesure le plafond reste intact malgre la charge`() {
        val adaptive = adaptive()
        adaptive.configure(uploadEnabled = true, keyframesEnabled = false)

        // Part constante 320 ms : les sondes ne gagnent rien, refusées + gelées.
        drive(adaptive, List(10) { 320L })

        assertEquals(0, adaptive.tier)
        assertEquals(base, adaptive.jpegQuality)
    }

    @Test
    fun `hysteresis zone mediane stable puis remontee complete`() {
        val adaptive = adaptive()
        adaptive.configure(uploadEnabled = true, keyframesEnabled = false)
        drive(adaptive, descent)
        assertEquals(3, adaptive.tier)

        // 220 ms : entre les seuils (200/250), on ne bouge pas.
        drive(adaptive, List(6) { 220L })
        assertEquals(3, adaptive.tier)
        assertEquals(base - 30, adaptive.jpegQuality)

        // 100 ms : le lien respire, retour au plafond palier par palier.
        drive(adaptive, List(12) { 100L })
        assertEquals(0, adaptive.tier)
        assertEquals(base, adaptive.jpegQuality)
    }

    @Test
    fun `le plancher q20 tient quelle que soit la base`() {
        val adaptive = LiveAdaptive(baseQuality = 40)
        adaptive.configure(uploadEnabled = true, keyframesEnabled = false)

        drive(adaptive, descent)

        assertEquals(3, adaptive.tier)
        assertEquals(LiveAdaptive.MIN_QUALITY, adaptive.jpegQuality)
    }

    @Test
    fun `reset oublie paliers et keyframe en vol`() {
        val adaptive = adaptive()
        adaptive.configure(uploadEnabled = true, keyframesEnabled = true)
        settled(adaptive, 340L, Result.success(frame(100f)))
        settled(adaptive, 340L, Result.success(frame(100f)))
        assertTrue(adaptive.admit())

        adaptive.reset()

        assertEquals(0, adaptive.tier)
        assertEquals(base, adaptive.jpegQuality)
        assertTrue(adaptive.admit())
    }
}
