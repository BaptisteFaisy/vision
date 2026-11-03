package dev.sam3vision.core.curb

import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.GuidanceSide
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rail trottoir en **mètres** sur la bordure (`curb`).
 *
 * Le maintien sur trottoir historique mesurait une **bande d'image**
 * (`PositionText.SIDE_EDGE`), donc une tolérance qui grandissait avec la
 * distance : ±0,32 m à 1,5 m, ±0,42 m à 2 m, ±0,85 m à 4 m. Autrement dit, à
 * quatre mètres devant soi, 85 cm de dérive passaient sans un mot (constat
 * utilisateur du 01/11 : « il peut passer à 80 cm du sidewalk »).
 *
 * [SidewalkRail] repasse tout en mètres sur la bordure, avec trois paliers :
 * [SidewalkRail.SAFETY_M] = 1 m (direction inverse forcée, ordre user du
 * 03/11), [SidewalkRail.DRIFT_M] = 0,50 m (on s'écarte) et
 * [SidewalkRail.EDGE_M] = 0,30 m (on est sur le fil, « Stop. » puis l'opposé).
 * La consigne de sécurité et de dérive est **l'opposé du bord**.
 */
class SidewalkRailTest {

    /**
     * Bordure placée pour que son écart latéral à l'axe vaille [lateralM]
     * (négatif = à gauche), à la profondeur [depth] : inverse de la projection
     * `(centerX − 0,5) × 2 × profondeur × tan(hfov/2)` (même helper que
     * `FreeSpaceTest.bord`, qui est privé et non réutilisable).
     */
    private fun bord(
        lateralM: Float,
        depth: Float = 2f,
        label: String = "curb",
        hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
    ): PlacedLabel {
        val halfWidth = 2f * depth * LensSpec.halfTangent(hfovDeg)
        return PlacedLabel(label, 0.5f + lateralM / halfWidth, 0.5f, depth)
    }

    /** Bordure à [centerX], profondeur [meters] (null = distance inconnue). */
    private fun vue(centerX: Float, meters: Float?, label: String = "curb") =
        PlacedLabel(label, centerX, 0.5f, meters)

    @Test
    fun `aucune bordure rend none`() {
        val state = SidewalkRail.of(emptyList())

        assertEquals(RailState.none(), state)
        assertNull(state.side)
        assertNull(state.clearanceM)
        assertEquals(RailLevel.NONE, state.level)
    }

    @Test
    fun `bordure a gauche a 0,4 m est une derive a gauche`() {
        val state = SidewalkRail.of(listOf(bord(-0.4f)))

        assertEquals(GuidanceSide.Left, state.side)
        assertEquals(0.4f, state.clearanceM!!, 0.01f)
        assertEquals(RailLevel.DRIFT, state.level)
    }

    @Test
    fun `bordure a droite a 0,25 m est au bord`() {
        val state = SidewalkRail.of(listOf(bord(0.25f)))

        assertEquals(GuidanceSide.Right, state.side)
        assertEquals(0.25f, state.clearanceM!!, 0.01f)
        assertEquals(RailLevel.EDGE, state.level)
    }

    @Test
    fun `bordure a 0,8 m est en securite et dit l oppose`() {
        // Ordre user du 03/11 : à moins d'1 m, la direction inverse — le
        // silence historique sous 0,50 m ne vaut plus entre 0,50 et 1 m.
        val state = SidewalkRail.of(listOf(bord(-0.8f)))

        assertEquals(GuidanceSide.Left, state.side)
        assertEquals(0.8f, state.clearanceM!!, 0.01f)
        assertEquals(RailLevel.SAFETY, state.level)
        assertEquals(RailCue.Direction(GuidanceWord.Right), SidewalkRail.cue(state))
    }

    @Test
    fun `bordure a 1,1 m reste silencieuse`() {
        val state = SidewalkRail.of(listOf(bord(-1.1f)))

        assertEquals(RailLevel.NONE, state.level)
        assertNull(SidewalkRail.cue(state))
    }

    @Test
    fun `deux bordures gardent la plus petite`() {
        val state = SidewalkRail.of(listOf(bord(-0.9f), bord(0.35f)))

        assertEquals(GuidanceSide.Right, state.side)
        assertEquals(0.35f, state.clearanceM!!, 0.01f)
        assertEquals(RailLevel.DRIFT, state.level)
    }

    @Test
    fun `bordure sans distance est ignoree`() {
        val state = SidewalkRail.of(listOf(vue(0.2f, meters = null)))

        assertEquals(RailState.none(), state)
        assertNull(state.side)
    }

    @Test
    fun `bordure a distance nulle ou negative est ignoree`() {
        assertEquals(RailState.none(), SidewalkRail.of(listOf(vue(0.2f, meters = 0f))))
        assertEquals(RailState.none(), SidewalkRail.of(listOf(vue(0.2f, meters = -2f))))
    }

    @Test
    fun `sidewalk et crosswalk ne sont pas des bordures`() {
        // Le masque de sol est coupé par le cadre : son bord n'est pas la bordure.
        assertEquals(RailState.none(), SidewalkRail.of(listOf(bord(-0.2f, label = "sidewalk"))))
        assertEquals(RailState.none(), SidewalkRail.of(listOf(bord(-0.2f, label = "crosswalk"))))
    }

    @Test
    fun `casse accents et pluriel de la bordure sont reconnus`() {
        for (label in listOf("Kerb", "BORDURE", "curbs", "Bordures")) {
            val state = SidewalkRail.of(listOf(bord(-0.4f, label = label)))
            assertEquals("label $label", GuidanceSide.Left, state.side)
            assertEquals("label $label", RailLevel.DRIFT, state.level)
        }
    }

    @Test
    fun `paliers de levelOf aux bornes 0,30 et 0,50`() {
        assertEquals(RailLevel.EDGE, SidewalkRail.levelOf(0.10f))
        // Bornes incluses : 30 cm est encore le bord, 50 cm encore la dérive.
        assertEquals(RailLevel.EDGE, SidewalkRail.levelOf(0.30f))
        assertEquals(RailLevel.DRIFT, SidewalkRail.levelOf(0.31f))
        assertEquals(RailLevel.DRIFT, SidewalkRail.levelOf(0.50f))
        // Ordre user du 03/11 : au-delà de 50 cm, c'est la sécurité jusqu'à 1 m.
        assertEquals(RailLevel.SAFETY, SidewalkRail.levelOf(0.51f))
    }

    @Test
    fun `derive dit le mot oppose au bord, pas une phrase`() {
        // Ordre user du 01/11 : « juste droite et gauche » — les mots du
        // guidage, donc les clips locaux (guide_left / guide_right).
        val gauche = SidewalkRail.of(listOf(bord(-0.4f)))
        assertEquals(RailCue.Direction(GuidanceWord.Right), SidewalkRail.cue(gauche))

        val droite = SidewalkRail.of(listOf(bord(0.4f)))
        assertEquals(RailCue.Direction(GuidanceWord.Left), SidewalkRail.cue(droite))
    }

    @Test
    fun `au bord c est stop, la direction vient ensuite`() {
        // Ordre user du 01/11 : « à 30 cm c'est stop puis la direction opposée ».
        val gauche = SidewalkRail.of(listOf(bord(-0.2f)))
        assertEquals(RailLevel.EDGE, gauche.level)
        assertEquals(RailCue.Stop, SidewalkRail.cue(gauche))
        assertEquals(GuidanceSide.Right, SidewalkRail.awayFrom(gauche.side))

        val droite = SidewalkRail.of(listOf(bord(0.2f)))
        assertEquals(RailCue.Stop, SidewalkRail.cue(droite))
        assertEquals(GuidanceSide.Left, SidewalkRail.awayFrom(droite.side))
    }

    @Test
    fun `a 4 m le rail dit l oppose a 80 cm et la derive a 45 cm`() {
        // Le chiffre de l'audit : l'ancienne bande d'image tolérait ±0,85 m à 4 m.
        // Ordre user du 03/11 : 80 cm n'est plus silencieux, c'est la sécurité.
        val large = SidewalkRail.of(listOf(bord(0.80f, depth = 4f)))
        assertEquals(RailLevel.SAFETY, large.level)
        assertEquals(RailCue.Direction(GuidanceWord.Left), SidewalkRail.cue(large))

        val serre = SidewalkRail.of(listOf(bord(0.45f, depth = 4f)))
        assertEquals(RailLevel.DRIFT, serre.level)
        assertEquals(RailCue.Direction(GuidanceWord.Left), SidewalkRail.cue(serre))
    }

    @Test
    fun `projection inverse 0,34 a 2 m vaut 0,42 m`() {
        // 0,34 = l'ancienne bande SIDE_EDGE ; à 2 m elle valait 0,42 m de dérive.
        val state = SidewalkRail.of(listOf(vue(centerX = 0.34f, meters = 2f)))

        assertEquals(GuidanceSide.Left, state.side)
        assertEquals(0.42f, state.clearanceM!!, 0.01f)
        assertEquals(RailLevel.DRIFT, state.level)
    }

    @Test
    fun `paliers de levelOf entree 1,00 et sortie 1,20 avec hysteresis`() {
        // Entrée stricte : « moins d'1 m », 1,00 m pile ne déclenche pas.
        assertEquals(RailLevel.SAFETY, SidewalkRail.levelOf(0.99f))
        assertEquals(RailLevel.NONE, SidewalkRail.levelOf(1.00f))
        assertEquals(RailLevel.NONE, SidewalkRail.levelOf(1.10f))
        // Tenue : déjà en sécurité, on y reste jusqu'à 1,20 m.
        assertEquals(RailLevel.SAFETY, SidewalkRail.levelOf(1.10f, RailLevel.SAFETY))
        assertEquals(RailLevel.NONE, SidewalkRail.levelOf(1.20f, RailLevel.SAFETY))
        // Les paliers urgents gagnent sur la sécurité tenue.
        assertEquals(RailLevel.DRIFT, SidewalkRail.levelOf(0.40f, RailLevel.SAFETY))
        assertEquals(RailLevel.EDGE, SidewalkRail.levelOf(0.20f, RailLevel.SAFETY))
    }

    @Test
    fun `clearances rend la bordure la plus proche de chaque cote`() {
        val both = SidewalkRail.clearances(listOf(bord(-0.9f), bord(-0.6f), bord(0.8f)))

        assertEquals(0.6f, both[GuidanceSide.Left]!!, 0.01f)
        assertEquals(0.8f, both[GuidanceSide.Right]!!, 0.01f)

        // Côté sans bordure mesurée absent : l'inconnu n'est pas un bord.
        val single = SidewalkRail.clearances(listOf(bord(-0.9f)))
        assertEquals(1, single.size)
        assertNull(single[GuidanceSide.Right])

        assertTrue(SidewalkRail.clearances(emptyList()).isEmpty())
    }

    @Test
    fun `safetyCue dit l oppose du bord sous 1 m`() {
        assertEquals(
            RailCue.Direction(GuidanceWord.Right),
            SidewalkRail.safetyCue(listOf(bord(-0.9f)), GuidanceSide.Left, rangeMeters = 4f),
        )
        assertEquals(
            RailCue.Direction(GuidanceWord.Left),
            SidewalkRail.safetyCue(listOf(bord(0.8f)), GuidanceSide.Right, rangeMeters = 4f),
        )
    }

    @Test
    fun `safetyCue dit stop quand les deux cotes sont sous 1 m`() {
        val labels = listOf(bord(-0.9f), bord(0.8f))

        assertEquals(RailCue.Stop, SidewalkRail.safetyCue(labels, GuidanceSide.Left, rangeMeters = 4f))
        assertEquals(RailCue.Stop, SidewalkRail.safetyCue(labels, GuidanceSide.Right, rangeMeters = 4f))
    }

    @Test
    fun `safetyCue dit stop si une personne bloque le cote ou aller`() {
        val labels = listOf(bord(-0.9f), PlacedLabel("person", 0.7f, 0.5f, 0.8f))

        assertEquals(RailCue.Stop, SidewalkRail.safetyCue(labels, GuidanceSide.Left, rangeMeters = 4f))
    }

    @Test
    fun `fenetres de mesure trop pres et trop loin sont muettes`() {
        // 2 cm de l'axe : bruit de bordure sous le nez, pas une consigne.
        assertNull(SidewalkRail.of(listOf(bord(0.02f))).clearanceM)
        // 6,5 m : on ne parle pas d'un bord aussi loin (garde MAX_USEFUL_M).
        assertNull(SidewalkRail.of(listOf(bord(6.5f, depth = 12f))).clearanceM)
        assertTrue(SidewalkRail.of(listOf(bord(5.5f, depth = 12f))).clearanceM != null)
    }
}
