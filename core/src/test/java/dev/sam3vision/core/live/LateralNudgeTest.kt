package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consigne latérale, version audit foule du 01/11/2026 : un obstacle proche qui
 * s'approche par un côté conseille un décalage de l'autre — dans les 2 m de
 * l'axe de marche seulement (ordre user du 30/10 : au-delà, il passe au large).
 * Ce qui change par rapport à l'ancien test, et que les tests adaptés disent :
 * - **jamais muet par ambiguïté** : deux côtés occupés ⇒ le côté le plus large ;
 * - **veto personne** : on inverse le côté s'il est libre de personne, sinon on
 *   conseille le côté visé en marquant `tight` (« serrez », on ne fonce pas) ;
 * - **décision en secondes** (TTC projeté, âge, vitesse) et **hystérésis**.
 */
class LateralNudgeTest {

    private fun personne(x: Float, meters: Float?) = PlacedLabel("personne", x, 0.5f, meters)

    private fun mur(x: Float, meters: Float) = PlacedLabel("mur", x, 0.5f, meters)

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

    /**
     * Adapté à la garde TTC : une approche lente à 4 m ne parle plus (2,5 s de
     * contact), donc la séquence approche plus vite (2 m/s de fermeture, TTC
     * 2,0 s) tout en restant à 4 m — seule la fenêtre en mètres décide.
     */
    @Test
    fun `le champ calibre la fenetre en metres`() {
        val frames = listOf(5.0f, 4.5f, 4.0f)
        // x = 0,92 à 4 m : 2,2 m de l'axe à 67° (dehors), 1,2 m à 40° (dedans).
        val large = LateralNudgeTracker()
        frames.forEachIndexed { i, m ->
            assertNull(large.onFrame(i * 250L, listOf(personne(0.92f, m)), 67f, 0L, 2.0f))
        }

        val etroit = LateralNudgeTracker()
        etroit.onFrame(0L, listOf(personne(0.92f, 5.0f)), 40f, 0L, 2.0f)
        etroit.onFrame(250L, listOf(personne(0.92f, 4.5f)), 40f, 0L, 2.0f)

        val nudge = etroit.onFrame(500L, listOf(personne(0.92f, 4.0f)), 40f, 0L, 2.0f)

        assertEquals(SteeringCap.Left, nudge!!.side)
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

    @Test
    fun `personne non mesuree cote approche laisse parler`() {
        val suivi = LateralNudgeTracker()
        val gauche = { m: Float -> PlacedLabel("poteau", 0.35f, 0.5f, m) }
        suivi.onFrame(0L, listOf(gauche(3.0f), PlacedLabel("personne", 0.3f, 0.5f, null)))
        suivi.onFrame(250L, listOf(gauche(2.6f), PlacedLabel("personne", 0.3f, 0.5f, null)))
        val nudge = suivi.onFrame(500L, listOf(gauche(2.2f), PlacedLabel("personne", 0.3f, 0.5f, null)))

        assertEquals(SteeringCap.Right, nudge!!.side)
    }

    @Test
    fun `loin une seule diminution ne suffit pas`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 3.0f)))

        assertNull(suivi.onFrame(250L, listOf(personne(0.65f, 2.9f))))
    }

    @Test
    fun `obstacle proche conseille des la 1re frame`() {
        val suivi = LateralNudgeTracker()

        val nudge = suivi.onFrame(0L, listOf(personne(0.65f, 1.2f)))

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertEquals(1.2f, nudge.meters, 0.001f)
    }

    @Test
    fun `obstacle proche statique conseille aussitot`() {
        val suivi = LateralNudgeTracker()

        val premier = suivi.onFrame(0L, listOf(personne(0.35f, 1.0f)))
        val second = suivi.onFrame(250L, listOf(personne(0.35f, 1.0f)))

        assertEquals(SteeringCap.Right, premier!!.side)
        assertEquals(SteeringCap.Right, second!!.side)
    }

    @Test
    fun `a 1,5 m on conseille, a 1,6 m on attend la confirmation`() {
        val proche = LateralNudgeTracker()
        assertEquals(SteeringCap.Left, proche.onFrame(0L, listOf(personne(0.65f, 1.5f)))!!.side)

        val loin = LateralNudgeTracker()
        assertNull(loin.onFrame(0L, listOf(personne(0.65f, 1.6f))))
    }

    /**
     * ADAPTÉ : ce test attendait `null` (« des deux côtés on ne conseille
     * rien ») — c'est le défaut mesuré de l'audit (muet 90-94 % des frames en
     * foule). Les deux côtés occupés conseillent maintenant le côté le plus
     * large ; la personne côté visé force « serrez ».
     */
    @Test
    fun `deux personnes des deux cotes ne font jamais taire`() {
        val suivi = LateralNudgeTracker()
        val deux = { m: Float -> listOf(personne(0.3f, m), personne(0.7f, m)) }
        suivi.onFrame(0L, deux(3.0f), free = FreeSpace(0.35f, 1.1f))
        suivi.onFrame(250L, deux(2.6f), free = FreeSpace(0.35f, 1.1f))
        val nudge = suivi.onFrame(500L, deux(2.2f), free = FreeSpace(0.35f, 1.1f))

        assertNotNull(nudge)
        assertEquals(SteeringCap.Right, nudge!!.side)
        assertTrue("personne côté visé ⇒ serrez", nudge.tight)
    }

    /** Côté le plus large, marge suffisante : deux murs, rien de serré. */
    @Test
    fun `deux murs des deux cotes conseillent le plus large`() {
        val suivi = LateralNudgeTracker()
        val deux = listOf(mur(0.3f, 1.4f), mur(0.7f, 1.4f))

        val nudge = suivi.onFrame(0L, deux, free = FreeSpace(0.4f, 1.2f))

        assertEquals(SteeringCap.Right, nudge!!.side)
        assertFalse(nudge.tight)
    }

    /**
     * ADAPTÉ : ce test attendait `null` (« proche des deux côtés on ne conseille
     * rien »). Les deux côtés bouchés et sans marge ⇒ consigne + `tight`.
     */
    @Test
    fun `proche des deux cotes sans marge on conseille serre`() {
        val suivi = LateralNudgeTracker()
        val deux = listOf(PlacedLabel("mur", 0.35f, 0.5f, 1.2f), PlacedLabel("mur", 0.65f, 0.5f, 1.2f))

        val nudge = suivi.onFrame(0L, deux, free = FreeSpace(0.2f, 0.15f))

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertTrue("0,2 m < marge de bord", nudge.tight)
    }

    /** Groupe de 4 qui bouche tout : non nul, et « serrez ». */
    @Test
    fun `groupe de quatre qui bouche tout ne se tait pas`() {
        val suivi = LateralNudgeTracker()
        val foule = listOf(0.2f, 0.35f, 0.65f, 0.8f).map { personne(it, 1.2f) }

        val nudge = suivi.onFrame(0L, foule, free = FreeSpace(0.05f, 0.05f))

        assertNotNull(nudge)
        assertTrue(nudge!!.tight)
    }

    /** Trois personnes dont une plein axe : l'axe ne compte pas comme côté. */
    @Test
    fun `trois personnes dont une plein axe conseillent le cote libre`() {
        val suivi = LateralNudgeTracker()
        val groupe = listOf(personne(0.25f, 1.4f), personne(0.5f, 1.0f), personne(0.4f, 1.3f))

        val nudge = suivi.onFrame(0L, groupe)

        assertEquals(SteeringCap.Right, nudge!!.side)
        assertEquals(1.3f, nudge.meters, 0.001f)
    }

    /**
     * ADAPTÉ : ce test attendait `null` (« on ne s'écarte pas vers une
     * personne »). Le veto n'éteint plus la consigne : il inverse le côté
     * (gauche, libre de personne) et signale la marge.
     */
    @Test
    fun `on ne s ecarte pas vers une personne on inverse`() {
        val suivi = LateralNudgeTracker()
        val frames = { m: Float -> listOf(mur(0.35f, m), personne(0.65f, 2.0f)) }
        suivi.onFrame(0L, frames(3.0f), free = FreeSpace(0.1f, 1.0f))
        suivi.onFrame(250L, frames(2.6f), free = FreeSpace(0.1f, 1.0f))
        val nudge = suivi.onFrame(500L, frames(2.2f), free = FreeSpace(0.1f, 1.0f))

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertTrue("0,1 m < marge de bord", nudge.tight)
    }

    /**
     * ADAPTÉ : ce test attendait `null` (« personne non mesurée côté cible
     * bloque le décalage »). Le côté cible est bloqué et l'autre est libre de
     * personne ⇒ on inverse ; la marge manquante est signalée.
     */
    @Test
    fun `personne non mesuree cote cible on passe de l autre cote`() {
        val suivi = LateralNudgeTracker()
        val gauche = { m: Float -> PlacedLabel("poteau", 0.35f, 0.5f, m) }
        suivi.onFrame(0L, listOf(gauche(3.0f)))
        suivi.onFrame(250L, listOf(gauche(2.6f)))
        val nudge = suivi.onFrame(500L, listOf(gauche(2.2f), PlacedLabel("personne", 0.7f, 0.5f, null)))

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertTrue(nudge.tight)
    }

    @Test
    fun `urgence sous 1,2 s sort des la premiere frame`() {
        val suivi = LateralNudgeTracker()
        val arrive = PlacedLabel("personne", 0.3f, 0.5f, 3.0f, closingMps = 3.0f)

        val nudge = suivi.onFrame(0L, listOf(arrive))

        assertEquals(SteeringCap.Right, nudge!!.side)
        assertEquals(1.0f, nudge.ttcS!!, 0.01f)
        assertTrue(Timing.isUrgent(nudge.ttcS))
    }

    @Test
    fun `sans piste ni diminution, 1,9 s de contact attend encore`() {
        val suivi = LateralNudgeTracker()
        val arrive = PlacedLabel("personne", 0.65f, 0.5f, 3.0f, closingMps = 1.6f)

        assertNull(suivi.onFrame(0L, listOf(arrive)))
    }

    @Test
    fun `une piste connue confirme l approche sous 2,2 s`() {
        val suivi = LateralNudgeTracker()
        val arrive = PlacedLabel("personne", 0.65f, 0.5f, 3.0f, trackId = 7, closingMps = 1.6f)

        val nudge = suivi.onFrame(0L, listOf(arrive))

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertEquals(7, nudge.id)
        assertEquals(1.875f, nudge.ttcS!!, 0.01f)
    }

    @Test
    fun `l age de la mesure rapproche la decision`() {
        val arrive = { PlacedLabel("personne", 0.3f, 0.5f, 3.0f, closingMps = 1.6f) }

        val frais = LateralNudgeTracker()
        assertNull("3 m vus à l'instant : 1,9 s, on attend", frais.onFrame(0L, listOf(arrive())))

        val vieux = LateralNudgeTracker()
        val nudge = vieux.onFrame(0L, listOf(arrive()), ageMs = 700L)

        assertNotNull("3 m vus il y a 0,7 s : déjà 1,9 m", nudge)
        assertEquals(1.88f, nudge!!.meters, 0.02f)
        assertEquals(1.175f, nudge.ttcS!!, 0.02f)
        assertTrue(Timing.isUrgent(nudge.ttcS))
    }

    @Test
    fun `une personne qui s eloigne n a pas de TTC ni d urgence`() {
        val suivi = LateralNudgeTracker()
        val parte = PlacedLabel("personne", 0.65f, 0.5f, 1.4f, closingMps = -1.0f)

        val nudge = suivi.onFrame(0L, listOf(parte))

        assertEquals(SteeringCap.Left, nudge!!.side)
        assertNull(nudge.ttcS)
        assertFalse(Timing.isUrgent(nudge.ttcS))
    }

    @Test
    fun `personne qui suit a droite ne crie pas`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(personne(0.65f, 2.5f)))
        suivi.onFrame(250L, listOf(personne(0.65f, 2.45f)))

        assertNull(suivi.onFrame(500L, listOf(personne(0.65f, 2.4f))))
    }

    @Test
    fun `la vitesse de marche porte plus loin`() {
        val lent = LateralNudgeTracker()
        val vite = LateralNudgeTracker()
        val frames = listOf(5.6f, 5.0f, 4.4f)

        frames.forEachIndexed { i, m -> assertNull(lent.onFrame(i * 250L, listOf(personne(0.65f, m)), 67f, 0L, 1.0f)) }

        vite.onFrame(0L, listOf(personne(0.65f, 5.6f)), 67f, 0L, 2.0f)
        vite.onFrame(250L, listOf(personne(0.65f, 5.0f)), 67f, 0L, 2.0f)
        val nudge = vite.onFrame(500L, listOf(personne(0.65f, 4.4f)), 67f, 0L, 2.0f)

        assertNotNull("à 2 m/s la portée vaut 5,8 m", nudge)
        assertTrue(nudge!!.meters > LateralNudgeTracker.NUDGE_METERS)
    }

    @Test
    fun `le cote conseille est tenu 1,5 s`() {
        val suivi = LateralNudgeTracker()
        assertEquals(SteeringCap.Left, suivi.onFrame(0L, listOf(mur(0.7f, 1.4f)))!!.side)

        // Deux côtés bouchés, l'espace dit droite (1 m) plus large que gauche (0,2 m).
        val deux = listOf(mur(0.3f, 1.0f), mur(0.7f, 1.6f))
        val free = FreeSpace(0.2f, 1.0f)
        assertEquals("à 250 ms, l'autre côté est plus large mais on tient", SteeringCap.Left, suivi.onFrame(250L, deux, free = free)!!.side)
        assertEquals("à 1,4 s, toujours tenu", SteeringCap.Left, suivi.onFrame(1_400L, deux, free = free)!!.side)
    }

    @Test
    fun `apres 1,5 s on bascule si le gain depasse 0,35 m`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(mur(0.7f, 1.4f)))

        val nudge = suivi.onFrame(1_500L, listOf(mur(0.3f, 1.0f), mur(0.7f, 2.0f)), free = FreeSpace(0.2f, 1.0f))

        assertEquals(SteeringCap.Right, nudge!!.side)
    }

    @Test
    fun `apres 1,5 s un gain trop faible ne fait pas changer`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(mur(0.7f, 1.4f)))

        val nudge = suivi.onFrame(1_600L, listOf(mur(0.3f, 1.3f), mur(0.7f, 1.5f)), free = FreeSpace(0.8f, 1.0f))

        assertEquals("0,2 m de gain < 0,35 m", SteeringCap.Left, nudge!!.side)
    }

    @Test
    fun `la tenue tombe si l obstacle qui la motive disparait`() {
        val suivi = LateralNudgeTracker()
        suivi.onFrame(0L, listOf(mur(0.7f, 1.4f)))

        val nudge = suivi.onFrame(250L, listOf(mur(0.3f, 1.0f)))

        assertEquals(SteeringCap.Right, nudge!!.side)
    }
}
