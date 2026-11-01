package dev.sam3vision.core.live

import dev.sam3vision.core.curb.RailLevel
import dev.sam3vision.core.curb.RailState
import dev.sam3vision.core.curb.SidewalkRail
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

    /** Personne à droite, à [meters] : sert à vérifier que le décalage gagne sur le rail. */
    private fun personneDroite(meters: Float) = PlacedLabel("personne", 0.7f, 0.5f, meters)

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
}
