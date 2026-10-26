package dev.sam3vision.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fenêtre de réveil du GPU : c'est elle qui transforme un échec de frame en « Réveil du
 * GPU A100 40 Go sur Modal… » pendant 60 s, puis en vraie erreur. Aucun Android ici.
 */
class LiveWakeGuardTest {

    private var now = 1_000_000L

    private fun guard(timeoutMs: Long = 60_000L) = LiveWakeGuard(timeoutMs = timeoutMs, clock = { now })

    @Test
    fun `armement ouvre la fenetre pour la duree du delai`() {
        val wake = guard()
        wake.arm()
        assertEquals(now + 60_000L, wake.deadlineAt)
        assertTrue(wake.isWaking())
    }

    @Test
    fun `la fenetre tient jusqu a la derniere milliseconde`() {
        val wake = guard()
        wake.arm()
        now += 59_999L
        assertTrue(wake.isWaking())
    }

    @Test
    fun `passe le delai l echec est une vraie erreur`() {
        val wake = guard()
        wake.arm()
        now += 60_000L
        assertFalse(wake.isWaking())
        now += 30_000L
        assertFalse(wake.isWaking())
    }

    @Test
    fun `sans armement aucun echec n est excuse`() {
        val wake = guard()
        assertEquals(0L, wake.deadlineAt)
        assertFalse(wake.isWaking())
    }

    @Test
    fun `reset ferme la fenetre`() {
        val wake = guard()
        wake.arm()
        wake.reset()
        assertEquals(0L, wake.deadlineAt)
        assertFalse(wake.isWaking())
    }

    @Test
    fun `rearmer redonne un delai plein depuis l instant courant`() {
        val wake = guard()
        wake.arm()
        now += 45_000L
        wake.arm()
        assertEquals(now + 60_000L, wake.deadlineAt)
        assertTrue(wake.isWaking())
        now += 59_000L
        assertTrue(wake.isWaking())
    }

    @Test
    fun `un delai nul ne laisse aucune fenetre`() {
        val wake = guard(timeoutMs = 0L)
        wake.arm()
        assertFalse(wake.isWaking())
    }
}