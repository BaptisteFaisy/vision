package dev.sam3vision.core.live

import dev.sam3vision.core.curb.RailLevel
import dev.sam3vision.core.curb.RailState
import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.curb.SidewalkRail
import dev.sam3vision.core.model.Passage
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Déclencheur du guidage : direction dite en continu toutes les 2 s, sauf overwrite (mot nouveau aussitôt), jamais de stop. */
class GuidanceAnnouncerTest {

    private val libre = emptyList<PlacedLabel>()
    private fun personne(meters: Float) = PlacedLabel("personne", 0.5f, 0.5f, meters)

    @Test
    fun `voie libre parle des la premiere frame`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, libre))
    }

    @Test
    fun `voie libre annoncee une fois puis silence`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, libre))
        assertNull(announcer.onFrame(500L, libre))
    }

    @Test
    fun `obstacle parle des la premiere frame sans attendre`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        assertEquals("Straight.", announcer.onFrame(0L, libre))
        // le danger arrive 1 s après : voix TwinLite aussitôt, pas de stabilité ni de délai
        assertEquals("left.", announcer.onFrame(1_000L, listOf(personne(2f))))
        // même mot : nouveauté, silence
        assertNull(announcer.onFrame(1_200L, listOf(personne(2f))))
    }

    @Test
    fun `retour au libre parle aussitot`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        assertEquals("left.", announcer.onFrame(0L, listOf(personne(2f))))
        assertNull(announcer.onFrame(200L, listOf(personne(2f))))
        // le couloir se dégage aussitôt : le mot change, parlé sans délai
        assertEquals("Straight.", announcer.onFrame(400L, libre))
    }

    @Test
    fun `seul le changement de mot fait reparler`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)
        fun decale(x: Float, meters: Float) = PlacedLabel("personne", x, 0.5f, meters)

        assertEquals("left.", announcer.onFrame(0L, listOf(decale(0.6f, 3.4f))))
        // 3,4 -> 3,2 m en 200 ms : pas d'urgence TTC (P0 foule, une approche
        // rapide parle désormais hors cadence — c'est voulu), donc le même mot
        // ne se redit pas avant le cooldown.
        assertNull(announcer.onFrame(200L, listOf(decale(0.6f, 3.2f))))
        // le côté change : reparle aussitôt, sans attendre le cooldown
        assertEquals("Right.", announcer.onFrame(400L, listOf(decale(0.4f, 0.4f))))
        assertNull(announcer.onFrame(600L, listOf(decale(0.4f, 0.4f))))
    }

    @Test
    fun `reset autorise une nouvelle annonce immediate`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, libre))
        announcer.reset()

        assertEquals("Straight.", announcer.onFrame(200L, libre))
    }

    @Test
    fun `phrase retenue pour l affichage`() {
        val announcer = GuidanceAnnouncer()

        announcer.onFrame(0L, libre)

        assertEquals("Straight.", announcer.lastPhrase)
    }

    @Test
    fun `cap route prime sur le cap manuel`() {
        val announcer = GuidanceAnnouncer()

        assertEquals(
            "Right.",
            announcer.onFrame(0L, libre, cap = SteeringCap.Left, routeCap = SteeringCap.Right),
        )
    }

    @Test
    fun `derniere decision exposee pour la video`() {
        val announcer = GuidanceAnnouncer()
        assertNull(announcer.lastDecision)

        announcer.onFrame(0L, listOf(personne(2f)))

        val decision = announcer.lastDecision
        assertTrue("décision attendue", decision != null)
        assertEquals(true, decision?.blocked)
        assertEquals(SteeringCap.Left, decision?.passage)
    }

    @Test
    fun `contact dit le cote TwinLite des la premiere frame`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)
        fun proche(meters: Float) = PlacedLabel("personne", 0.5f, 0.5f, meters)

        assertEquals("left.", announcer.onFrame(0L, listOf(proche(0.4f))))
        // même mot : rien de neuf
        assertNull(announcer.onFrame(200L, listOf(proche(0.45f))))
    }

    @Test
    fun `reset oublie la decision`() {
        val announcer = GuidanceAnnouncer()
        announcer.onFrame(0L, listOf(personne(2f)))

        announcer.reset()

        assertNull(announcer.lastDecision)
    }

    @Test
    fun `matiere proche non mesuree guide des la premiere frame`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)
        fun matiere() = PlacedLabel("mur", 0.5f, 0.5f, null)

        assertEquals("left.", announcer.onFrame(0L, listOf(matiere())))
        assertNull(announcer.onFrame(200L, listOf(matiere())))
    }

    private fun personneDroite(meters: Float) = PlacedLabel("personne", 0.7f, 0.5f, meters)

    @Test
    fun `personne qui s approche a droite dit gauche`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, listOf(personneDroite(3.0f))))
        assertNull(announcer.onFrame(250L, listOf(personneDroite(2.6f))))
        assertEquals("Left.", announcer.onFrame(500L, listOf(personneDroite(2.2f))))
    }

    @Test
    fun `poteau qui s approche a droite dit gauche`() {
        val announcer = GuidanceAnnouncer()
        fun poteauDroite(meters: Float) = PlacedLabel("poteau", 0.7f, 0.5f, meters)

        assertEquals("Straight.", announcer.onFrame(0L, listOf(poteauDroite(3.0f))))
        assertNull(announcer.onFrame(250L, listOf(poteauDroite(2.6f))))
        assertEquals("Left.", announcer.onFrame(500L, listOf(poteauDroite(2.2f))))
    }

    @Test
    fun `couloir bouche prime sur le decalage`() {
        val announcer = GuidanceAnnouncer()
        fun bloque(m: Float) = PlacedLabel("personne", 0.5f, 0.5f, m)

        assertEquals(
            "left.",
            announcer.onFrame(0L, listOf(bloque(3.4f), personneDroite(3.0f))),
        )
        assertNull(announcer.lastNudge)
    }

    @Test
    fun `decalage stable ne reparle pas`() {
        val announcer = GuidanceAnnouncer()

        // Approche à 1-1,5 m/s (400 ms par pas) : le TTC reste au-dessus de
        // Timing.URGENT_TTC_S (1,2 s), donc seule la cadence de redite
        // gouverne — la version à 250 ms de ce test parlait à chaque frame
        // depuis que l'urgence hors cadence existe (comportement voulu).
        announcer.onFrame(0L, listOf(personneDroite(3.0f)))
        announcer.onFrame(400L, listOf(personneDroite(2.6f)))
        assertEquals("Left.", announcer.onFrame(800L, listOf(personneDroite(2.0f))))
        assertNull(announcer.onFrame(1_200L, listOf(personneDroite(1.6f))))
        assertNull(announcer.onFrame(1_600L, listOf(personneDroite(1.3f))))
    }

    @Test
    fun `age mesure par l audit reste utilisable`() {
        val announcer = GuidanceAnnouncer()

        // 0,70 s : l'âge mesuré par l'audit foule, sous Timing.STALE_MS
        // (1,2 s) — la frame décrit encore le monde, la voix guide.
        assertEquals("Straight.", announcer.onFrame(0L, libre, ageMs = 700L))
    }

    @Test
    fun `frame perimee supprime tout droit mais laisse parler un cote`() {
        val announcer = GuidanceAnnouncer()
        val vieux = Timing.STALE_MS + 100

        // Voie libre annoncée par une frame périmée : « Tout droit. » est une
        // affirmation, on ne la prononce pas sur une mesure de 1,3 s.
        assertNull(announcer.onFrame(0L, libre, ageMs = vieux))
        // Un côté, lui, reste valable : le retour trottoir parle malgré l'âge.
        assertEquals(
            "Gauche.",
            announcer.onFrame(100L, libre, SpeechLanguage.Fr, sidewalk = SidewalkPosition.Left, ageMs = vieux),
        )
    }

    @Test
    fun `deux personnes ne font jamais taire la voix`() {
        val announcer = GuidanceAnnouncer()
        fun foule(meters: Float) = listOf(
            PlacedLabel("personne", 0.2f, 0.5f, meters),
            PlacedLabel("personne", 0.8f, 0.5f, meters),
        )

        assertTrue("guidage dès la première frame", announcer.onFrame(0L, foule(2.5f)) != null)
        announcer.onFrame(400L, foule(2.1f))
        // Deux personnes, une de chaque côté : le décalage conseille le côté
        // le plus large au lieu de se taire (muet 90-94 % avant ce patch).
        assertTrue("le décalage parle en foule", announcer.onFrame(800L, foule(1.7f)) != null)
        assertTrue("un côté conseillé", announcer.lastNudge != null)
    }

    @Test
    fun `decalage serre reste le mot du cote et leve le drapeau`() {
        val announcer = GuidanceAnnouncer()
        val trottoirEtroit = FreeSpace(leftM = 0.2f, rightM = 2f)

        announcer.onFrame(0L, listOf(personneDroite(3.0f)), SpeechLanguage.Fr)
        announcer.onFrame(250L, listOf(personneDroite(2.6f)), SpeechLanguage.Fr)
        val phrase = announcer.onFrame(500L, listOf(personneDroite(2.2f)), SpeechLanguage.Fr, free = trottoirEtroit)

        // Pas de clip « serrez » au catalogue : la phrase reste le mot de côté…
        assertEquals("Gauche.", phrase)
        // …et le drapeau dit à la couche voix qu'il n'y a pas de marge.
        assertEquals(true, announcer.lastNudge?.tight)
    }

    @Test
    fun `urgence ttc fait parler malgre la redite`() {
        val announcer = GuidanceAnnouncer()

        // Contact à droite : le mot sort à la première frame…
        assertEquals("Gauche.", announcer.onFrame(0L, listOf(personneDroite(1.4f)), SpeechLanguage.Fr))
        // …et 200 ms plus tard la fermeture (2,5 m/s) donne un TTC de 0,36 s :
        // le mot ressort malgré la redite récente (repeatMs = 2 s).
        assertEquals("Gauche.", announcer.onFrame(200L, listOf(personneDroite(0.9f)), SpeechLanguage.Fr))
    }

    @Test
    fun `place libre fournie rend le drapeau serre coherent`() {
        fun nudge(free: FreeSpace): LateralNudge? {
            val announcer = GuidanceAnnouncer()
            announcer.onFrame(0L, listOf(personneDroite(3.0f)))
            announcer.onFrame(250L, listOf(personneDroite(2.6f)))
            announcer.onFrame(500L, listOf(personneDroite(2.2f)), free = free)
            return announcer.lastNudge
        }

        assertEquals(false, nudge(FreeSpace(leftM = 0.9f, rightM = 0.9f))?.tight)
        assertEquals(true, nudge(FreeSpace(leftM = 0.2f, rightM = 0.9f))?.tight)
    }

    @Test
    fun `aucun obstacle comportement inchange`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, libre))
        assertNull(announcer.onFrame(500L, libre))
        assertNull(announcer.lastNudge)
        assertEquals("Straight.", announcer.onFrame(2_000L, libre))
    }

    @Test
    fun `arbitrage bloque TwinLite puis libre SAM, une seule phrase par changement`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)
        val bloque = listOf(personne(2f))

        assertEquals("left.", announcer.onFrame(0L, bloque))
        assertNull(announcer.onFrame(200L, bloque))
        assertEquals("Straight.", announcer.onFrame(400L, libre))
        assertNull(announcer.onFrame(600L, libre))
        assertEquals("left.", announcer.onFrame(800L, bloque))
        assertNull(announcer.onFrame(1_000L, bloque))
    }

    @Test
    fun `pas de doublon, contact garde le cote sans repeter`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        // bouché à 2 m : le côté parle seul
        assertEquals("left.", announcer.onFrame(0L, listOf(personne(2f))))
        // contact ensuite : même côté, rien de neuf
        assertNull(announcer.onFrame(200L, listOf(personne(0.4f))))
        assertNull(announcer.onFrame(400L, listOf(personne(0.4f))))
    }

    @Test
    fun `contact anglais dit le cote`() {
        val announcer = GuidanceAnnouncer(cooldownMs = 4_000L)

        assertEquals(
            "left.",
            announcer.onFrame(0L, listOf(personne(0.4f)), SpeechLanguage.En),
        )
        assertNull(announcer.onFrame(200L, listOf(personne(0.45f)), SpeechLanguage.En))
    }

    @Test
    fun `passage SAM bouche parle son cote`() {
        val announcer = GuidanceAnnouncer()
        val sam = Passage(blocked = true, side = "right", label = "person")
        val bloque = listOf(personne(2f))

        assertEquals("right.", announcer.onFrame(0L, bloque, samPassage = sam))
        assertNull(announcer.onFrame(200L, bloque, samPassage = sam))
    }

    @Test
    fun `passage SAM libre tait un couloir localement bouche`() {
        val announcer = GuidanceAnnouncer()
        val sam = Passage(blocked = false)

        assertEquals("Straight.", announcer.onFrame(0L, listOf(personne(2f)), samPassage = sam))
    }

    @Test
    fun `passage SAM bouche avec mesure proche dit le cote`() {
        val announcer = GuidanceAnnouncer()
        val sam = Passage(blocked = true, side = "left", label = "person")

        assertEquals(
            "left.",
            announcer.onFrame(0L, listOf(personne(0.4f)), samPassage = sam),
        )
    }

    @Test
    fun `retour trottoir stable redit la direction`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Gauche.", announcer.onFrame(0L, libre, SpeechLanguage.Fr, sidewalk = SidewalkPosition.Left))
        assertEquals("Gauche.", announcer.onFrame(2_000L, libre, SpeechLanguage.Fr, sidewalk = SidewalkPosition.Left))
    }

    @Test
    fun `bouche puis libre reparle et relance la repetition`() {
        val announcer = GuidanceAnnouncer()
        val bloque = listOf(personne(2f))

        assertEquals("Straight.", announcer.onFrame(0L, libre))
        assertEquals("left.", announcer.onFrame(1_000L, bloque))
        assertEquals("Straight.", announcer.onFrame(2_000L, libre))
        assertNull(announcer.onFrame(3_999L, libre))
        assertEquals("Straight.", announcer.onFrame(4_000L, libre))
    }

    @Test
    fun `repetition suit la langue courante`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, libre))
        assertEquals("Tout droit.", announcer.onFrame(2_000L, libre, SpeechLanguage.Fr))
    }

    @Test
    fun `passage SAM bouche sans cote passe a gauche`() {
        val announcer = GuidanceAnnouncer()
        val sam = Passage(blocked = true)

        assertEquals("left.", announcer.onFrame(0L, listOf(personne(2f)), samPassage = sam))
    }

    @Test
    fun `direction stable redite en continu toutes les deux secondes sauf overwrite`() {
        val announcer = GuidanceAnnouncer()

        assertEquals("Straight.", announcer.onFrame(0L, libre))
        assertNull(announcer.onFrame(1_999L, libre))
        assertEquals("Straight.", announcer.onFrame(2_000L, libre))
        assertEquals("Straight.", announcer.onFrame(4_000L, libre))
        // overwrite : mot nouveau dit aussitôt, sans attendre l'échéance
        assertEquals("Left.", announcer.onFrame(4_100L, libre, cap = SteeringCap.Left))
        assertEquals("Left.", announcer.onFrame(6_100L, libre, cap = SteeringCap.Left))
    }

    @Test
    fun `cote bouche stable redit en continu, plus de stop au contact`() {
        val announcer = GuidanceAnnouncer()
        val bloque = listOf(personne(2f))

        assertEquals("left.", announcer.onFrame(0L, bloque))
        assertEquals("left.", announcer.onFrame(2_000L, bloque))
        // Contact latéral : plus de stop (ordre user du 31/10 — on se faufile, on
        // ne s'arrête pas devant un obstacle). Le contact se contourne côté
        // dégagé à 80 cm (pas vers la personne), et la direction se redit
        // toutes les 2 s.
        val contact = listOf(PlacedLabel("personne", 0.2f, 0.5f, 0.4f))
        val contactAnnouncer = GuidanceAnnouncer()
        // Depuis le P0 foule, le décalage latéral (identité, TTC, espace libre)
        // passe devant la décision de couloir et parle avec les mots SAM : même
        // direction, autre clip (« Right. » et non « right. »).
        assertEquals("Right.", contactAnnouncer.onFrame(0L, contact, cap = SteeringCap.Left))
        assertEquals("Right.", contactAnnouncer.onFrame(2_000L, contact, cap = SteeringCap.Left))
    }

    @Test
    fun `passage SAM bascule cote degage a 80cm`() {
        val announcer = GuidanceAnnouncer()
        val sam = Passage(blocked = true, side = "left", label = "person")
        val gauche = listOf(PlacedLabel("personne", 0.3f, 0.5f, 1f))

        // Le décalage latéral prime désormais sur le passage SAM : même côté
        // dégagé (droite), mot SAM au lieu du mot TwinLite.
        assertEquals("Right.", announcer.onFrame(0L, gauche, samPassage = sam))
    }

    @Test
    fun `passage SAM garde son cote si l autre ne degage pas`() {
        val announcer = GuidanceAnnouncer()
        val sam = Passage(blocked = true, side = "left", label = "person")
        val centre = listOf(PlacedLabel("personne", 0.5f, 0.5f, 1f))

        assertEquals("left.", announcer.onFrame(0L, centre, samPassage = sam))
    }

    private fun dire(rail: RailState?, lang: SpeechLanguage = SpeechLanguage.Fr): String? =
        GuidanceAnnouncer().onFrame(0L, libre, lang, rail = rail)

    private fun bordG(m: Float, level: RailLevel) = RailState(GuidanceSide.Left, m, level)

    @Test
    fun `rail drift a gauche fait serrer a droite`() {
        // Bordure à 40 cm de l'axe : la bande d'image la laissait passer (±0,32 m à 1,5 m).
        val etat = SidewalkRail.of(listOf(PlacedLabel("curb", 0.3f, 0.5f, 1.5f)))
        assertEquals(GuidanceSide.Left, etat.side)
        assertEquals(RailLevel.DRIFT, etat.level)
        assertEquals("Serrez à droite.", dire(etat))
    }

    @Test
    fun `rail edge alerte et prime sur tout droit`() {
        assertEquals("Attention, bord du trottoir.", dire(bordG(0.2f, RailLevel.EDGE)))
    }

    @Test
    fun `decalage lateral gagne sur le rail`() {
        val announcer = GuidanceAnnouncer()
        val etat = bordG(0.4f, RailLevel.DRIFT)
        announcer.onFrame(0L, listOf(personneDroite(3.0f)), SpeechLanguage.Fr, rail = etat)
        announcer.onFrame(250L, listOf(personneDroite(2.6f)), SpeechLanguage.Fr, rail = etat)
        assertEquals("Gauche.", announcer.onFrame(500L, listOf(personneDroite(2.2f)), SpeechLanguage.Fr, rail = etat))
        assertNotNull("décalage attendu devant le rail", announcer.lastNudge)
    }

    @Test
    fun `couloir bouche gagne sur le rail`() {
        assertEquals("left.", GuidanceAnnouncer().onFrame(0L, listOf(personne(2f)), rail = bordG(0.2f, RailLevel.EDGE)))
    }

    @Test
    fun `rail muet laisse la voie libre inchangee`() {
        assertEquals("Tout droit.", dire(RailState(null, null, RailLevel.NONE)))
        assertEquals("Straight.", dire(RailState(null, 0.4f, RailLevel.DRIFT), SpeechLanguage.En))
    }

    @Test
    fun `rail redit toutes les trois secondes, pas a chaque frame`() {
        val announcer = GuidanceAnnouncer()
        val etat = bordG(0.4f, RailLevel.DRIFT)
        fun frame(atMs: Long) = announcer.onFrame(atMs, libre, SpeechLanguage.Fr, rail = etat)
        assertEquals("Serrez à droite.", frame(0L))
        assertNull(frame(1_000L))
        assertNull(frame(2_000L))
        assertEquals("Serrez à droite.", frame(3_000L))
    }

    @Test
    fun `frame perimee rend le rail muet`() {
        val etat = bordG(0.2f, RailLevel.EDGE)
        assertNull(GuidanceAnnouncer().onFrame(0L, libre, SpeechLanguage.Fr, ageMs = 1_500L, rail = etat))
    }

    @Test
    fun `reset fait reparler le rail tout de suite`() {
        val announcer = GuidanceAnnouncer()
        val etat = bordG(0.4f, RailLevel.DRIFT)
        assertEquals("Serrez à droite.", announcer.onFrame(0L, libre, SpeechLanguage.Fr, rail = etat))
        announcer.reset()
        assertEquals("Serrez à droite.", announcer.onFrame(200L, libre, SpeechLanguage.Fr, rail = etat))
    }
}
