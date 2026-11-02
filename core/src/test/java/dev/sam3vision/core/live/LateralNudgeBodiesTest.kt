package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Veto corps du décalage latéral : poussette, fauteuil et chien se
 * contournent comme une personne (ordre user du 02/11) — on n'envoie
 * jamais vers eux, on inverse ou on serre en marquant `tight`.
 */
class LateralNudgeBodiesTest {

    private fun mur(x: Float, meters: Float) = PlacedLabel("mur", x, 0.5f, meters)

    @Test
    fun `on ne s ecarte pas vers un chien on inverse`() {
        // Le mur approche à gauche, un chien tient la droite — on serre à gauche.
        val suivi = LateralNudgeTracker()
        val frames = { m: Float -> listOf(mur(0.35f, m), PlacedLabel("chien", 0.65f, 0.5f, 2.0f)) }
        suivi.onFrame(0L, frames(3.0f), free = FreeSpace(0.1f, 1.0f))
        suivi.onFrame(250L, frames(2.6f), free = FreeSpace(0.1f, 1.0f))
        val nudge = suivi.onFrame(500L, frames(2.2f), free = FreeSpace(0.1f, 1.0f))

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertTrue("0,1 m < marge de bord", nudge.tight)
    }

    @Test
    fun `on ne s ecarte pas vers une poussette on inverse`() {
        // Miroir chien : une poussette tient la droite — on serre à gauche.
        val suivi = LateralNudgeTracker()
        val frames = { m: Float -> listOf(mur(0.35f, m), PlacedLabel("poussette", 0.65f, 0.5f, 2.0f)) }
        suivi.onFrame(0L, frames(3.0f), free = FreeSpace(0.1f, 1.0f))
        suivi.onFrame(250L, frames(2.6f), free = FreeSpace(0.1f, 1.0f))
        val nudge = suivi.onFrame(500L, frames(2.2f), free = FreeSpace(0.1f, 1.0f))

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertTrue("0,1 m < marge de bord", nudge.tight)
    }
}
