package dev.sam3vision.core.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verrou AWB temporisé : il se pose sur la première boîte vue, ne se re-pose pas, et
 * tombe **seul** après 3 s sans feu (une teinte figée oubliée rendrait la caméra aveugle
 * au changement d'éclairage). L'anti-rebond de 500 ms est vérifié des deux côtés.
 */
class AwbLockPolicyTest {

    @Test
    fun `premiere boite vue - verrou immediat`() {
        assertEquals(true, AwbLockPolicy().tick(lightVisible = true, nowMs = 0, locked = false))
    }

    @Test
    fun `deja verrouille - aucun changement`() {
        val policy = AwbLockPolicy()
        policy.tick(lightVisible = true, nowMs = 0, locked = false)
        assertNull(policy.tick(lightVisible = true, nowMs = 700, locked = true))
    }

    @Test
    fun `feu sorti du champ - relache apres trois secondes`() {
        val policy = AwbLockPolicy()
        policy.tick(lightVisible = true, nowMs = 0, locked = false)
        assertNull(policy.tick(lightVisible = false, nowMs = 1000, locked = true))
        assertNull(policy.tick(lightVisible = false, nowMs = 2900, locked = true))
        assertEquals(false, policy.tick(lightVisible = false, nowMs = 3000, locked = true))
    }

    @Test
    fun `anti-rebond - une bascule par demi-seconde`() {
        val policy = AwbLockPolicy()
        assertEquals(true, policy.tick(lightVisible = true, nowMs = 0, locked = false))
        // Feu qui clignote : à 200 ms, on ne relâche pas encore (anti-rebond, puis maintien).
        assertNull(policy.tick(lightVisible = false, nowMs = 200, locked = true))
        assertNull(policy.tick(lightVisible = false, nowMs = 499, locked = true))
    }

    @Test
    fun `sans feu et sans verrou - rien a faire`() {
        assertNull(AwbLockPolicy().tick(lightVisible = false, nowMs = 10_000, locked = false))
    }
}
