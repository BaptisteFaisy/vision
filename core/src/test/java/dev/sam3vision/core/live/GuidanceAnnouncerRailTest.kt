package dev.sam3vision.core.live

import dev.sam3vision.core.curb.RailLevel
import dev.sam3vision.core.curb.RailState
import dev.sam3vision.core.curb.SidewalkRail
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rail trottoir dans le guidage (audit du 01/11) : les mots du guidage, clips
 * locaux — « Droite. » en dérive, « Stop. » puis le côté opposé au bord.
 *
 * Extrait de `GuidanceAnnouncerTest` (plafond 500 lignes/fichier).
 */
class GuidanceAnnouncerRailTest {

    private val libre = emptyList<PlacedLabel>()

    /** État de rail du côté droit (bordure à droite ⇒ aller à gauche). */
    private fun bordG(meters: Float, level: RailLevel): RailState =
        RailState(GuidanceSide.Right, meters, level)

    /** Personne plein axe : sert à vérifier que le couloir bouché gagne sur le rail. */
    private fun personne(meters: Float) = PlacedLabel("personne", 0.5f, 0.5f, meters)

    /** Mur à droite, à [meters] : un humain parlerait dès la 1re frame (3 m, ordre user du 02/11). */
    private fun murDroite(meters: Float) = PlacedLabel("mur", 0.7f, 0.5f, meters)

    /**
     * Bordure placée pour que son écart latéral à l'axe vaille [lateralM]
     * (négatif = à gauche), à la profondeur [depth] (miroir du helper de
     * `SidewalkRailTest`, privé et non réutilisable).
     */
    private fun bord(lateralM: Float, depth: Float = 2f): PlacedLabel {
        val halfWidth = 2f * depth * LensSpec.halfTangent(LensSpec.DEFAULT_H_FOV_DEG)
        return PlacedLabel("curb", 0.5f + lateralM / halfWidth, 0.5f, depth)
    }

    private fun dire(state: RailState, language: SpeechLanguage = SpeechLanguage.Fr): String? =
        GuidanceAnnouncer().onFrame(0L, libre, language, rail = state)

    @Test
    fun `rail drift a gauche dit le mot oppose`() {
        // Bordure à 40 cm de l'axe : la bande d'image la laissait passer (±0,32 m à 1,5 m).
        // Ordre user du 01/11 : le **mot du guidage** (« Droite. »), pas une phrase.
        val etat = SidewalkRail.of(listOf(PlacedLabel("curb", 0.3f, 0.5f, 1.5f)))
        assertEquals(GuidanceSide.Left, etat.side)
        assertEquals(RailLevel.DRIFT, etat.level)
        assertEquals("Droite.", dire(etat))
    }

    @Test
    fun `rail edge dit stop puis la direction opposee`() {
        // Ordre user du 01/11 : à 30 cm, « Stop. » d'abord, puis le côté opposé.
        val announcer = GuidanceAnnouncer()
        val etat = bordG(0.2f, RailLevel.EDGE)

        assertEquals("Stop.", announcer.onFrame(0L, libre, SpeechLanguage.Fr, rail = etat))
        assertEquals("Gauche.", announcer.onFrame(200L, libre, SpeechLanguage.Fr, rail = etat))
        assertNull(announcer.onFrame(400L, libre, SpeechLanguage.Fr, rail = etat))
    }

    @Test
    fun `decalage lateral gagne sur le rail`() {
        val announcer = GuidanceAnnouncer()
        val etat = bordG(0.4f, RailLevel.DRIFT)
        assertEquals("Gauche.", announcer.onFrame(0L, listOf(murDroite(3.0f)), SpeechLanguage.Fr, rail = etat))
        announcer.onFrame(250L, listOf(murDroite(2.6f)), SpeechLanguage.Fr, rail = etat)
        announcer.onFrame(500L, listOf(murDroite(2.2f)), SpeechLanguage.Fr, rail = etat)
        assertNotNull("décalage attendu devant le rail", announcer.lastNudge)
    }

    @Test
    fun `couloir bouche gagne sur le rail`() {
        assertEquals("right.", GuidanceAnnouncer().onFrame(0L, listOf(personne(2f)), rail = bordG(0.2f, RailLevel.EDGE)))
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
        // Bordure à droite ⇒ on va à gauche.
        assertEquals("Gauche.", frame(0L))
        assertNull(frame(1_000L))
        assertNull(frame(2_000L))
        assertEquals("Gauche.", frame(3_000L))
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
        assertEquals("Gauche.", announcer.onFrame(0L, libre, SpeechLanguage.Fr, rail = etat))
        announcer.reset()
        assertEquals("Gauche.", announcer.onFrame(200L, libre, SpeechLanguage.Fr, rail = etat))
    }

    @Test
    fun `securite sous 1 m force la direction inverse devant le cap`() {
        // Ordre user du 03/11 : bordure à gauche à 0,9 m + cap à gauche ⇒
        // « Droite. » forcé — on ne marche plus vers la bordure.
        val etat = RailState(GuidanceSide.Left, 0.9f, RailLevel.SAFETY)
        assertEquals(
            "Droite.",
            GuidanceAnnouncer().onFrame(0L, libre, SpeechLanguage.Fr, cap = SteeringCap.Left, rail = etat),
        )
    }

    @Test
    fun `securite gagne sur le decalage lateral`() {
        // Même mur qui approche que `decalage lateral gagne sur le rail` (qui
        // dit « Gauche. ») : la sécurité parle d'abord et possède la frame, y
        // compris entre deux redites — jamais le côté de la bordure.
        val announcer = GuidanceAnnouncer()
        val etat = RailState(GuidanceSide.Left, 0.9f, RailLevel.SAFETY)
        fun frame(atMs: Long, mur: Float) =
            announcer.onFrame(atMs, listOf(murDroite(mur)), SpeechLanguage.Fr, rail = etat)
        assertEquals("Droite.", frame(0L, 3.0f))
        assertNull(announcer.lastNudge)
        assertNull(frame(250L, 2.6f))
        assertNull(frame(500L, 2.2f))
        assertNull(frame(750L, 2.0f))
        assertEquals("Droite.", frame(3_000L, 2.0f))
    }

    @Test
    fun `deux bordures sous 1 m disent stop`() {
        // Pas de direction sûre : bordures des deux côtés à moins d'1 m.
        val labels = listOf(bord(-0.9f), bord(0.8f))
        val etat = SidewalkRail.of(labels)
        assertEquals(GuidanceSide.Right, etat.side)
        assertEquals(RailLevel.SAFETY, etat.level)
        assertEquals("Stop.", GuidanceAnnouncer().onFrame(0L, labels, SpeechLanguage.Fr, rail = etat))
    }

    @Test
    fun `personne sous 1 m du cote ou aller dit stop`() {
        // Bordure à gauche, mais personne à droite à 0,8 m : le veto 1 m
        // (miroir du retour trottoir) refuse d'y envoyer l'utilisateur.
        val labels = listOf(PlacedLabel("person", 0.7f, 0.5f, 0.8f))
        val etat = RailState(GuidanceSide.Left, 0.9f, RailLevel.SAFETY)
        assertEquals("Stop.", GuidanceAnnouncer().onFrame(0L, labels, SpeechLanguage.Fr, rail = etat))
    }

    @Test
    fun `securite muette sur frame perimee`() {
        val etat = RailState(GuidanceSide.Left, 0.9f, RailLevel.SAFETY)
        assertNull(GuidanceAnnouncer().onFrame(0L, libre, SpeechLanguage.Fr, ageMs = 1_500L, rail = etat))
    }

    @Test
    fun `securite tient jusqu a 1,20 m puis rend la main`() {
        // Hystérésis : 0,9 m parle, 1,1 m tient encore (niveau mesuré NONE),
        // 1,3 m sort — la voie libre reparle.
        val announcer = GuidanceAnnouncer()
        fun frame(atMs: Long, marge: Float, niveau: RailLevel) =
            announcer.onFrame(atMs, libre, SpeechLanguage.Fr, rail = RailState(GuidanceSide.Left, marge, niveau))
        assertEquals("Droite.", frame(0L, 0.9f, RailLevel.SAFETY))
        assertEquals("Droite.", frame(3_000L, 1.1f, RailLevel.NONE))
        assertEquals("Tout droit.", frame(6_000L, 1.3f, RailLevel.NONE))
    }

    @Test
    fun `stop au contact garde sur la securite`() {
        // Bordure au contact + personne au contact plein axe (colonne
        // centrale : hors fenêtre du décalage) : le stop reste un stop.
        val labels = listOf(
            PlacedLabel("curb", 0.5f, 0.5f, 0.5f),
            PlacedLabel("person", 0.5f, 0.5f, 0.5f),
        )
        val etat = RailState(GuidanceSide.Left, 0.9f, RailLevel.SAFETY)
        assertEquals(
            "obstacle, arrêtez-vous.",
            GuidanceAnnouncer().onFrame(0L, labels, SpeechLanguage.Fr, rail = etat),
        )
    }
}
