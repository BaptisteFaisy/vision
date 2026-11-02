package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Humain au mouvement inconnu (ordre user du 02/11 : prendre de la distance
 * pour se décaler des humains) : première vue sans piste — pas de TTC ni
 * d'approche confirmée — elle conseille dès 3 m au lieu de 1,5 m (à 2,8 m/s
 * de fermeture, 1,5 m c'est le contact : l'app fonçait dessus). Un humain au
 * mouvement connu (immobile, qui s'éloigne) et tout objet gardent 1,5 m.
 *
 * Fichier séparé de `LateralNudgeTest` (plafond 500 lignes/fichier).
 */
class LateralNudgePersonTest {

    private fun personne(x: Float, meters: Float?) = PlacedLabel("personne", x, 0.5f, meters)

    private fun mur(x: Float, meters: Float) = PlacedLabel("mur", x, 0.5f, meters)

    @Test
    fun `personne inconnue a 2,2 m conseille des la premiere frame`() {
        val suivi = LateralNudgeTracker()

        val nudge = suivi.onFrame(0L, listOf(personne(0.65f, 2.2f)))

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertEquals(2.2f, nudge.meters, 0.001f)
    }

    @Test
    fun `a 3 m on conseille, a 3,1 m on attend`() {
        val proche = LateralNudgeTracker()
        assertEquals(SteeringCap.Left, proche.onFrame(0L, listOf(personne(0.65f, 3.0f)))!!.side)

        val loin = LateralNudgeTracker()
        assertNull(loin.onFrame(0L, listOf(personne(0.65f, 3.1f))))
    }

    @Test
    fun `objet inconnu a 2,2 m attend la confirmation`() {
        val suivi = LateralNudgeTracker()

        assertNull(suivi.onFrame(0L, listOf(mur(0.65f, 2.2f))))
    }

    @Test
    fun `personne au mouvement connu garde 1,5 m`() {
        // Immobile suivie (fermeture nulle connue) : le repli humain ne
        // s'applique pas — seul l'inconnu parle tôt.
        val suivi = LateralNudgeTracker()
        val immobile = PlacedLabel("personne", 0.65f, 0.5f, 2.0f, closingMps = 0f)

        assertNull(suivi.onFrame(0L, listOf(immobile)))
    }
}
