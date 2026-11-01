package dev.sam3vision.core.live

import dev.sam3vision.core.curb.CurbEdge
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidage « tout droit » : couloir central, côté de sortie, quatre mots FR/EN, signature. */
class GuidanceTest {

    private fun placed(label: String, x: Float, meters: Float? = null) =
        PlacedLabel(label, x, 0.5f, meters)

    @Test
    fun `voie libre sans objet`() {
        val decision = Guidance.decide(emptyList())

        assertFalse(decision.blocked)
        assertEquals("Straight.", Guidance.phrase(decision))
        assertEquals("Straight.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `objets lateraux ne bloquent pas`() {
        val labels = listOf(placed("personne", 0.1f, 1f), placed("chien", 0.9f, 1f))

        assertFalse(Guidance.decide(labels).blocked)
    }

    @Test
    fun `obstacle au centre bloque vers le cote le plus libre`() {
        val labels = listOf(
            placed("personne", 0.5f, 2f),
            placed("chaise", 0.1f, 1f),
        )

        val decision = Guidance.decide(labels)

        assertTrue(decision.blocked)
        assertEquals(GuidanceSide.Right, decision.side)
        assertEquals("Right.", Guidance.phrase(decision))
    }

    @Test
    fun `distance inconnue bloque par prudence`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f)))

        assertTrue(decision.blocked)
        assertEquals("Left.", Guidance.phrase(decision))
        assertEquals("Left.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `obstacle sur l axe a 10 m bloque (portee axe 15 m)`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f, 10f)))

        assertTrue(decision.blocked)
        assertEquals(GuidanceSide.Left, decision.side)
        assertEquals("Left.", Guidance.phrase(decision))
    }

    @Test
    fun `objet sur l axe au dela de 15 m ne bloque pas`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f, 16f)))

        assertFalse(decision.blocked)
    }

    @Test
    fun `mot anglais du cote de sortie`() {
        val decision = Guidance.decide(listOf(placed("person", 0.5f, 2f)))

        assertEquals("Left.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `contact mesure dit le cote, jamais stop`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f, 0.4f)))

        assertTrue(decision.blocked)
        assertEquals("Left.", Guidance.phrase(decision))
        assertEquals("Gauche.", Guidance.phrase(decision, SpeechLanguage.Fr))
        assertEquals("w|left", Guidance.key(decision))
    }

    @Test
    fun `deux obstacles differents, meme mot, pas de reannonce`() {
        val pres = Guidance.decide(listOf(placed("personne", 0.5f, 2.4f)))
        val loin = Guidance.decide(listOf(placed("personne", 0.5f, 3.4f)))

        assertEquals(Guidance.key(pres), Guidance.key(loin))
        assertEquals("w|left", Guidance.key(loin))
    }

    @Test
    fun `egalite des cotes on passe a l oppose du decalage`() {
        val decaleAGauche = Guidance.decide(listOf(placed("personne", 0.4f, 2f)))
        val decaleADroite = Guidance.decide(listOf(placed("personne", 0.6f, 2f)))

        assertEquals(GuidanceSide.Right, decaleAGauche.side)
        assertEquals(GuidanceSide.Left, decaleADroite.side)
    }

    @Test
    fun `labels vides ignores`() {
        val decision = Guidance.decide(listOf(placed("  ", 0.5f, 1f)))

        assertFalse(decision.blocked)
    }

    @Test
    fun `signature libre et signature obstacle`() {
        assertEquals("w|straight", Guidance.key(Guidance.decide(emptyList())))

        val bloque = Guidance.decide(listOf(placed("personne", 0.5f, 2.4f)))

        assertEquals("w|left", Guidance.key(bloque))
    }

    @Test
    fun `un mot different fait reparler`() {
        val libre = Guidance.decide(emptyList())
        val bloque = Guidance.decide(listOf(placed("personne", 0.5f, 2.4f)))

        assertNotEquals(Guidance.key(libre), Guidance.key(bloque))
    }

    @Test
    fun `le cote de sortie donne le mot`() {
        val onze = Guidance.decide(listOf(placed("personne", 0.36f, 2f)))

        assertEquals("w|right", Guidance.key(onze))
        assertEquals("Right.", Guidance.phrase(onze))
    }

    @Test
    fun `decalage nul sans distance`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f)))

        assertNull(decision.shiftMeters)
        assertEquals("w|left", Guidance.key(decision))
    }

    @Test
    fun `le decalage ne fait plus reparler`() {
        val pres = Guidance.decide(listOf(placed("personne", 0.5f, 2f)))
        val loin = Guidance.decide(listOf(placed("personne", 0.5f, 3.4f)))

        assertEquals(1f, pres.shiftMeters!!, 0.001f)
        assertEquals(1.5f, loin.shiftMeters!!, 0.001f)
        assertEquals(Guidance.key(pres), Guidance.key(loin))
    }

    @Test
    fun `le sol marchable ne bloque pas le couloir`() {
        val decision = Guidance.decide(listOf(placed("sidewalk", 0.5f)))

        assertFalse(decision.blocked)
    }

    @Test
    fun `les chemins et la route ne se contournent pas`() {
        val sols = listOf(
            "chemin", "chemins", "sentier", "path", "pathway", "walkway", "trail",
            "route", "routes", "rue", "road", "street", "avenue", "boulevard",
            "allée", "chaussée", "ruelle", "alley",
        )

        for (sol in sols) {
            val decision = Guidance.decide(listOf(placed(sol, 0.5f, 2f)))

            assertFalse(sol, decision.blocked)
            assertEquals("Straight.", Guidance.phrase(decision))
        }
    }

    @Test
    fun `la voie ferree reste un obstacle`() {
        for (rail in listOf("track", "rail", "voie")) {
            val decision = Guidance.decide(listOf(placed(rail, 0.5f, 2f)))

            assertTrue(rail, decision.blocked)
        }
    }

    @Test
    fun `la bordure bloque toujours, elle se monte`() {
        val decision = Guidance.decide(listOf(placed("curb", 0.5f)))

        assertTrue(decision.blocked)
        assertEquals("Left.", Guidance.phrase(decision))
    }

    @Test
    fun `peinture au sol bloque`() {
        val decision = Guidance.decide(listOf(placed("crosswalk", 0.5f)))

        assertTrue(decision.blocked)
        assertEquals("Left.", Guidance.phrase(decision))
    }

    @Test
    fun `contact bordure + obstacle au contact cap droit = stop`() {
        val labels = listOf(placed("curb", 0.5f, 0.5f), placed("mur", 0.5f, 0.5f))
        val decision = Guidance.decide(labels)

        assertTrue(decision.blocked)
        assertTrue(decision.curbContact)
        assertTrue(decision.contactStop)
        assertEquals("Stop.", Guidance.phrase(decision))
        assertEquals("Stop.", Guidance.phrase(decision, SpeechLanguage.Fr))
        assertEquals("w|stop", Guidance.key(decision))
    }

    @Test
    fun `bordure seule au contact = pas de stop, consigne attention`() {
        val decision = Guidance.decide(listOf(placed("curb", 0.5f, 0.5f)))

        assertTrue(decision.blocked)
        assertTrue(decision.curbContact)
        assertFalse(decision.contactStop)
        assertEquals("Attention bordure.", Guidance.curbPhrase(decision, SpeechLanguage.Fr))
        assertEquals("Attention, curb.", Guidance.curbPhrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `sens connu = consigne monter ou descendre`() {
        val labels = listOf(placed("marche", 0.5f, 0.5f))
        val up = Guidance.decide(labels, curbEdge = CurbEdge.Up)
        val down = Guidance.decide(labels, curbEdge = CurbEdge.Down)

        assertTrue(up.curbContact)
        assertEquals("Marche, maintenant.", Guidance.curbPhrase(up, SpeechLanguage.Fr))
        assertEquals("Descente, maintenant.", Guidance.curbPhrase(down, SpeechLanguage.Fr))
    }

    @Test
    fun `cap lateral = pas de stop au contact`() {
        val labels = listOf(placed("curb", 0.2f, 0.5f), placed("mur", 0.2f, 0.5f))
        val decision = Guidance.decide(labels, SteeringCap.Left)

        assertTrue(decision.blocked)
        assertTrue(decision.curbContact)
        assertFalse(decision.contactStop)
    }

    @Test
    fun `obstacle a 70 cm = pas de stop`() {
        val labels = listOf(placed("curb", 0.5f, 0.5f), placed("mur", 0.5f, 0.7f))
        val decision = Guidance.decide(labels)

        assertTrue(decision.curbContact)
        assertFalse(decision.contactStop)
    }

    @Test
    fun `sans consigne bordure hors contact`() {
        val loin = Guidance.decide(listOf(placed("curb", 0.5f, 2f)))
        val libre = Guidance.decide(emptyList())

        assertFalse(loin.curbContact)
        assertNull(Guidance.curbPhrase(loin))
        assertNull(Guidance.curbPhrase(libre))
    }

    @Test
    fun `contact mesure avec inconnu dit le cote`() {
        val labels = listOf(placed("mur", 0.5f), placed("personne", 0.5f, 0.4f))
        val decision = Guidance.decide(labels)

        assertTrue(decision.blocked)
        assertEquals("Left.", Guidance.phrase(decision))
        assertEquals("w|left", Guidance.key(decision))
    }

    // — Audit foule P0, point 3 : un ordre ne pointe pas hors du trottoir —

    /**
     * Scène de l'audit : trottoir de 1,80 m, obstacle (chaise) à 3,50 m juste à
     * gauche de l'axe. Sans espace mesuré, l'ordre chiffré dépasse le trottoir
     * (1,50 m de décalage quand il s'arrête à 0,90 m) ; avec l'espace mesuré, le
     * côté conseillé a de la place et le chiffre y tient.
     */
    @Test
    fun `audit trottoir 1 80 m = l ordre tient dans le trottoir`() {
        val labels = listOf(placed("chaise", 0.46f, 3.5f))
        val historique = Guidance.decide(labels)
        val mesure = Guidance.decide(labels, free = FreeSpace(0f, 0.9f))

        assertEquals(GuidanceSide.Right, historique.side)
        assertTrue("l'ordre historique dépasse le trottoir", historique.shiftMeters!! > 0.9f)
        assertTrue(mesure.blocked)
        assertNotEquals(GuidanceSide.Left, mesure.side)
        assertTrue("le chiffre tient dans la place", mesure.shiftMeters!! <= 0.9f)
    }

    /** Le côté calculé sans marge (0,10 m) cède la place au côté large (0,90 m). */
    @Test
    fun `un cote sans place est ecarte au profit du plus large`() {
        val labels = listOf(placed("chaise", 0.55f, 3.5f))
        val mesure = Guidance.decide(labels, free = FreeSpace(0.1f, 0.9f))

        assertEquals(GuidanceSide.Left, Guidance.decide(labels).side)
        assertEquals(GuidanceSide.Right, mesure.side)
        assertEquals("Right.", Guidance.phrase(mesure))
    }

    /** Gauche bouchée et droite à 0,40 m : la marge de bord suffit, on parle. */
    @Test
    fun `gauche bouchee et droite a 0 40 m = le conseil est conserve`() {
        val labels = listOf(placed("chaise", 0.55f, 3.5f))
        val mesure = Guidance.decide(labels, free = FreeSpace(0f, 0.4f))

        assertTrue(mesure.blocked)
        assertEquals(GuidanceSide.Right, mesure.side)
        assertEquals("Right.", Guidance.phrase(mesure))
    }

    /** Aucun côté n'a la marge : le côté calculé est gardé, jamais de silence. */
    @Test
    fun `aucune place des deux cotes = le cote calcule est garde`() {
        val labels = listOf(placed("chaise", 0.55f, 3.5f))
        val mesure = Guidance.decide(labels, free = FreeSpace(0f, 0.1f))

        assertTrue(mesure.blocked)
        assertEquals(GuidanceSide.Left, mesure.side)
        assertEquals(0f, mesure.shiftMeters!!, 0.001f)
        assertEquals("Left.", Guidance.phrase(mesure))
    }

    /** 1,50 m de décalage sur 0,60 m de place : ramené au demi-mètre inférieur. */
    @Test
    fun `un ordre de 1 50 m sur 0 60 m de place est ramene a 0 50 m`() {
        val labels = listOf(placed("chaise", 0.5f, 3.5f))
        val mesure = Guidance.decide(labels, free = FreeSpace(0.6f, 0.6f))

        assertEquals(GuidanceSide.Left, mesure.side)
        assertEquals(0.5f, mesure.shiftMeters!!, 0.001f)
    }

    /** Invariant : le chiffre annoncé tient toujours dans la place du côté dit. */
    @Test
    fun `le decalage ne depasse jamais la place du cote conseille`() {
        val labels = listOf(placed("chaise", 0.5f, 3.5f))
        val espaces = listOf(
            FreeSpace(0.6f, 0.6f),
            FreeSpace(0f, 0.4f),
            FreeSpace(1.2f, 0.5f),
            FreeSpace(0.35f, 2f),
        )

        for (free in espaces) {
            val decision = Guidance.decide(labels, free = free)
            val side = decision.side!!
            assertTrue(
                "$free : ${decision.shiftMeters} > ${free.room(side)}",
                decision.shiftMeters!! <= free.room(side),
            )
        }
    }

    /** À 2 m/s, l'horizon vaut 5,8 m : un obstacle à 5 m entre dans le couloir. */
    /** Une portée fixée par l'appelant fait foi, vitesse ou pas. */
    @Test
    fun `une portee explicite fait foi sur la vitesse`() {
        val cinq = listOf(placed("personne", 0.5f, 5f))

        assertTrue(Guidance.decide(cinq, rangeMeters = 6f, axisRangeMeters = 6f).blocked)
        assertFalse(
            Guidance.decide(cinq, rangeMeters = 3f, speedMps = 2f, axisRangeMeters = 3f).blocked,
        )
    }

    /** Espace inconnu (trottoir supposé) : rien ne change, scène historique. */
    @Test
    fun `free inconnu = comportement historique inchange`() {
        val scene = listOf(placed("personne", 0.5f, 2f), placed("chaise", 0.1f, 1f))
        val historique = Guidance.decide(scene)

        assertEquals(historique, Guidance.decide(scene, free = FreeSpace.unknown()))
        assertEquals(1f, historique.shiftMeters!!, 0.001f)
        assertEquals("w|right", Guidance.key(historique))
    }

    /** Trottoir mesuré symétrique = trottoir supposé : décision identique. */
    @Test
    fun `trottoir symetrique mesure = decision identique`() {
        val labels = listOf(placed("personne", 0.5f, 2.4f))

        assertEquals(Guidance.decide(labels), Guidance.decide(labels, free = FreeSpace(0.9f, 0.9f)))
    }

    /** Cap latéral : le décalage est plafonné par la place, le passage reste dit. */
    @Test
    fun `cap lateral = le decalage est plafonne aussi`() {
        val labels = listOf(placed("chaise", 0.2f, 3.5f))
        val decision = Guidance.decide(labels, SteeringCap.Left, free = FreeSpace(0.6f, 0.6f))

        assertTrue(decision.blocked)
        assertNull(decision.side)
        assertEquals(SteeringCap.Straight, decision.passage)
        assertEquals(0.5f, decision.shiftMeters!!, 0.001f)
    }
}
