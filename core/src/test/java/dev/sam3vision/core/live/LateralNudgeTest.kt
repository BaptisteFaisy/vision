package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Consigne latérale : un humain, un objet ou un poteau proche qui s'approche
 * par un côté conseille un décalage de l'autre — mais seulement dans les 2 m
 * de l'axe de marche (ordre user du 30/10 : au-delà, l'obstacle passe au
 * large, on se tait). Ni statique ni lointain ni hors fenêtre : le suivi
 * exige des diminutions consécutives (pas un bond).
 */
class LateralNudgeTest {

    private fun personne(x: Float, meters: Float?) = PlacedLabel("personne", x, 0.5f, meters)

    /** 3 frames d'approche à droite : le 3e conseille la gauche. */
    private fun approcheDroite(suivi: LateralNudgeTracker): LateralNudge? {
        suivi.onFrame(0L, listOf(personne(0.65f, 3.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 2.6f)))
        return suivi.onFrame(500L, listOf(personne(0.65f, 2.2f)))
    }

    @Test
    fun `personne qui s approche a droite conseille la gauche`() {
        val nudge = approcheDroite(LateralNudgeTracker())

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertEquals(2.2f, nudge.meters, 0.001f)
    }

    @Test
    fun `personne qui s approche a gauche conseille la droite`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.35f, 3.0f)))
        suivi.onFrame(250L, listOf(personne(0.35f, 2.6f)))

        val nudge = suivi.onFrame(500L, listOf(personne(0.35f, 2.2f)))

        assertEquals(SteeringCap.Right, nudge!!.side)
    }

    @Test
    fun `poteau qui s approche a droite conseille la gauche`() {
        val suivi = LateralNudgeTracker()
        val poteau = { m: Float -> PlacedLabel("poteau", 0.65f, 0.5f, m) }
        suivi.onFrame(0L, listOf(poteau(3.0f)))
        suivi.onFrame(250L, listOf(poteau(2.6f)))

        val nudge = suivi.onFrame(500L, listOf(poteau(2.2f)))

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertEquals(2.2f, nudge.meters, 0.001f)
    }

    @Test
    fun `objet qui s approche a gauche conseille la droite`() {
        val suivi = LateralNudgeTracker()
        val voiture = { m: Float -> PlacedLabel("car", 0.35f, 0.5f, m) }
        suivi.onFrame(0L, listOf(voiture(3.0f)))
        suivi.onFrame(250L, listOf(voiture(2.6f)))

        val nudge = suivi.onFrame(500L, listOf(voiture(2.2f)))

        assertEquals(SteeringCap.Right, nudge!!.side)
    }

    @Test
    fun `personne statique ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 2.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 2.0f)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, 2.0f))))
    }

    @Test
    fun `personne qui s eloigne ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 2.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 2.4f)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, 2.8f))))
    }

    @Test
    fun `le sol et la route ne conseillent rien`() {
        listOf("trottoir", "route").forEach { label ->
            val suivi = LateralNudgeTracker()
            val sol = { m: Float -> PlacedLabel(label, 0.65f, 0.5f, m) }
            suivi.onFrame(0L, listOf(sol(3.0f)))
            suivi.onFrame(250L, listOf(sol(2.6f)))

            assertNull("sol $label", suivi.onFrame(500L, listOf(sol(2.2f))))
        }
    }

    @Test
    fun `panneau haut ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        val panneau = { m: Float -> PlacedLabel("panneau", 0.65f, 0.1f, m) }
        suivi.onFrame(0L, listOf(panneau(3.0f)))
        suivi.onFrame(250L, listOf(panneau(2.6f)))

        assertNull(suivi.onFrame(500L, listOf(panneau(2.2f))))
    }

    @Test
    fun `distance inconnue ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, null)))
        suivi.onFrame(250L, listOf(personne(0.65f, null)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, null))))
    }

    @Test
    fun `des deux cotes a la fois on ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        val deux = { m: Float -> listOf(personne(0.35f, m), personne(0.65f, m)) }
        suivi.onFrame(0L, deux(3.0f))
        suivi.onFrame(250L, deux(2.6f))

        assertNull(suivi.onFrame(500L, deux(2.2f)))
    }

    @Test
    fun `au dela de 2 m sur le cote on ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        // x = 0,05 à ~4 m, champ 67° : 2,2 m à gauche de l'axe — passe au large.
        suivi.onFrame(0L, listOf(personne(0.05f, 4.0f)))
        suivi.onFrame(250L, listOf(personne(0.05f, 3.9f)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.05f, 3.8f))))
    }

    @Test
    fun `pres du bord mais dans les 2 m on conseille`() {
        val suivi = LateralNudgeTracker()
        // x = 0,1 à 2,2 m : 1,2 m à gauche de l'axe — dans la fenêtre.
        suivi.onFrame(0L, listOf(personne(0.1f, 3.0f)))
        suivi.onFrame(250L, listOf(personne(0.1f, 2.6f)))

        val nudge = suivi.onFrame(500L, listOf(personne(0.1f, 2.2f)))

        assertEquals(SteeringCap.Right, nudge!!.side)
    }

    @Test
    fun `au dela de quatre metres on ne conseille rien`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 5.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 4.6f)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, 4.2f))))
    }

    @Test
    fun `a quatre metres qui s approche on conseille`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 4.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 3.6f)))

        val nudge = suivi.onFrame(500L, listOf(personne(0.65f, 3.2f)))

        assertEquals(SteeringCap.Left, nudge!!.side)
    }

    @Test
    fun `le champ calibre la fenetre en metres`() {
        val frames = listOf(4.0f, 3.9f, 3.8f)
        // x = 0,92 à ~4 m : 2,1 m de l'axe à 67° (dehors), 1,2 m à 40° (dedans).
        val large = LateralNudgeTracker()
        frames.forEachIndexed { i, m -> assertNull(large.onFrame(i * 250L, listOf(personne(0.92f, m)))) }

        val etroit = LateralNudgeTracker()
        etroit.onFrame(0L, listOf(personne(0.92f, 4.0f)), 40f)
        etroit.onFrame(250L, listOf(personne(0.92f, 3.9f)), 40f)

        assertEquals(SteeringCap.Left, etroit.onFrame(500L, listOf(personne(0.92f, 3.8f)), 40f)!!.side)
    }

    @Test
    fun `mur qui s approche conseille de s en eloigner`() {
        val suivi = LateralNudgeTracker()
        val mur = { m: Float -> PlacedLabel("mur", 0.35f, 0.5f, m) }
        suivi.onFrame(0L, listOf(mur(3.0f)))
        suivi.onFrame(250L, listOf(mur(2.6f)))

        val nudge = suivi.onFrame(500L, listOf(mur(2.2f)))

        assertEquals(SteeringCap.Right, nudge!!.side)
    }

    @Test
    fun `on ne s ecarte pas vers une personne`() {
        // Mur qui s'approche à gauche, personne immobile à droite dans la
        // fenêtre : se taire plutôt que d'envoyer l'utilisateur dessus.
        val suivi = LateralNudgeTracker()
        val frames = { m: Float -> listOf(PlacedLabel("mur", 0.35f, 0.5f, m), personne(0.65f, 2.0f)) }
        suivi.onFrame(0L, frames(3.0f))
        suivi.onFrame(250L, frames(2.6f))

        assertNull(suivi.onFrame(500L, frames(2.2f)))
    }

    @Test
    fun `personne hors fenetre cote cible on conseille quand meme`() {
        // Même approche, personne à 0,99/4 m (2,6 m de l'axe : passe au large) —
        // le décalage à droite reste conseillé.
        val suivi = LateralNudgeTracker()
        val frames = { m: Float -> listOf(PlacedLabel("mur", 0.35f, 0.5f, m), personne(0.99f, 4.0f)) }
        suivi.onFrame(0L, frames(3.0f))
        suivi.onFrame(250L, frames(2.6f))

        val nudge = suivi.onFrame(500L, frames(2.2f))

        assertEquals(SteeringCap.Right, nudge!!.side)
    }

    @Test
    fun `reset oublie l approche`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 3.0f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 2.6f)))
        suivi.reset()

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, 2.2f))))
    }
}
