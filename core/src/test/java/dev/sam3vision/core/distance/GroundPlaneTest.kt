package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan de sol : ligne des pieds → distance ([GroundPlane]) et pose caméra ([CameraPose]).
 *
 * Fixture reprise de la campagne `vld` (`vld42`) : image 1080×1440, focale f = 1000 px,
 * centre optique (540, 720), caméra à 1,40 m du sol. Les nombres attendus sont des
 * vérifications à la main (`cy + f·tan(atan(h/d) − pitch)`), arrondies au centième.
 */
class GroundPlaneTest {

    private companion object {
        const val FOCAL_PX = 1000f
        const val CENTER_X = 540f
        const val CENTER_Y = 720f
        const val IMAGE_HEIGHT = 1440
        const val HEIGHT_M = 1.40f
    }

    private fun pose(pitchRad: Float = 0f): CameraPose = CameraPose(HEIGHT_M, pitchRad)

    /** Ligne des pieds d'un objet posé à [meters], avec la fixture. */
    private fun rowOf(meters: Float, pitchRad: Float = 0f, focalPx: Float = FOCAL_PX): Float =
        GroundPlane.rowAt(meters, pose(pitchRad), focalPx, CENTER_Y)!!

    /** Profondeur mesurée depuis une ligne, avec la fixture. */
    private fun forwardOf(row: Float, pitchRad: Float = 0f, focalPx: Float = FOCAL_PX): Float? =
        GroundPlane.forwardMeters(row, pose(pitchRad), focalPx, CENTER_Y, IMAGE_HEIGHT)

    private fun measure(row: Float, centerX: Float = CENTER_X): GroundPoint? =
        GroundPlane.pointOf(row, centerX, pose(), FOCAL_PX, CENTER_X, CENTER_Y, IMAGE_HEIGHT)

    @Test
    fun `ligne des pieds a 3 m tombe a 1186,667 px`() {
        // 720 + 1000·tan(atan(1,40/3)) = 720 + 466,667
        assertEquals(1186.667f, rowOf(3f), 0.01f)
    }

    @Test
    fun `ligne des pieds a 8 m tombe a 895 px`() {
        assertEquals(895f, rowOf(8f), 0.01f)
    }

    @Test
    fun `pitch de 0,35 rad ramene la ligne de 1 m pres du bord bas`() {
        // 720 + 1000·tan(atan(1,40) − 0,35) : l'objet à 1 m descend dans le cadre
        assertEquals(1404.94f, rowOf(1f, 0.35f), 0.01f)
    }

    @Test
    fun `ligne et profondeur s'inversent`() {
        assertEquals(3f, forwardOf(rowOf(3f))!!, 0.02f)
        assertEquals(8f, forwardOf(rowOf(8f))!!, 0.02f)
        assertEquals(1f, forwardOf(rowOf(1f, 0.35f), 0.35f)!!, 0.02f)
    }

    @Test
    fun `depression d'un objet mesuree depuis l'horizontale`() {
        // 1 m sans pitch : atan(1,40) = 0,9505 rad ; 3 m : atan(1,40/3) = 0,4366 rad
        assertEquals(0.9505f, GroundPlane.depressionRad(rowOf(1f), pose(), FOCAL_PX, CENTER_Y), 1e-4f)
        assertEquals(0.4366f, GroundPlane.depressionRad(rowOf(3f), pose(), FOCAL_PX, CENTER_Y), 1e-4f)
    }

    @Test
    fun `sans pitch, l'horizon est la ligne du centre optique`() {
        assertEquals(CENTER_Y, GroundPlane.horizonRow(pose(), FOCAL_PX, CENTER_Y), 0f)
    }

    @Test
    fun `pitch de 0,3 rad remonte l'horizon et annule la depression`() {
        // 720 − 1000·tan(0,3) = 410,664 px, au-dessus du centre
        val horizon = GroundPlane.horizonRow(pose(0.3f), FOCAL_PX, CENTER_Y)
        assertEquals(410.664f, horizon, 0.01f)
        assertEquals(0f, GroundPlane.depressionRad(horizon, pose(0.3f), FOCAL_PX, CENTER_Y), 1e-5f)
    }

    @Test
    fun `ligne au-dessus de l'horizon ne porte pas de distance`() {
        // 390 px est 20 px au-dessus de l'horizon à 410,664 : dépression négative
        assertNull(forwardOf(GroundPlane.horizonRow(pose(0.3f), FOCAL_PX, CENTER_Y), 0.3f))
        assertNull(forwardOf(390f, 0.3f))
    }

    @Test
    fun `ligne hors du cadre ne porte pas de distance, le bord bas si`() {
        assertNull(forwardOf(-1f))
        assertNull(forwardOf(IMAGE_HEIGHT + 1f))
        // 1440 px (bord bas) : 1,40/tan(atan(0,72)) = 1,944 m
        assertEquals(1.944f, forwardOf(IMAGE_HEIGHT.toFloat())!!, 0.01f)
    }

    @Test
    fun `pose invalide ne mesure pas`() {
        assertFalse(CameraPose(0f).isValid)
        assertFalse(CameraPose(-1f).isValid)
        assertFalse(CameraPose(10f).isValid)
        assertFalse(CameraPose(Float.NaN).isValid)
        assertFalse(CameraPose(HEIGHT_M, Float.NaN).isValid)
        assertTrue(CameraPose(0.2f).isValid)
        assertTrue(CameraPose(1.5f).isValid)
        assertTrue(CameraPose(5f).isValid)
        assertNull(GroundPlane.forwardMeters(900f, CameraPose(0f), FOCAL_PX, CENTER_Y, IMAGE_HEIGHT))
    }

    @Test
    fun `pose par defaut, hauteur d'epaule`() {
        assertEquals(1.5f, CameraPose.DEFAULT_HEIGHT_M, 0f)
        assertEquals(CameraPose(1.5f), CameraPose.Default)
        // 1,50 m de haut à 1,50 m devant : 720 + 1000 = 1720 px (sous le cadre, ligne brute)
        assertEquals(1720f, GroundPlane.rowAt(1.5f, CameraPose.Default, FOCAL_PX, CENTER_Y)!!, 0.01f)
    }

    @Test
    fun `pose lunettes, hauteur des yeux tete droite`() {
        assertEquals(1.6f, CameraPose.GLASSES_HEIGHT_M, 0f)
        assertEquals(CameraPose(1.6f, 0f), CameraPose.Glasses)
        assertTrue(CameraPose.Glasses.isValid)
        assertEquals(CENTER_Y, GroundPlane.horizonRow(CameraPose.Glasses, FOCAL_PX, CENTER_Y), 0f)
        // 720 + 1000·(1,60/3) = 1253,333 px ; pieds à 920 px → 8,0 m
        assertEquals(1253.333f, GroundPlane.rowAt(3f, CameraPose.Glasses, FOCAL_PX, CENTER_Y)!!, 0.01f)
        assertEquals(8f, GroundPlane.forwardMeters(920f, CameraPose.Glasses, FOCAL_PX, CENTER_Y, IMAGE_HEIGHT)!!, 0.01f)
        // Bord bas : 1,60/0,72 = 2,222 m ; horizon : pas de distance
        assertEquals(2.222f, GroundPlane.forwardMeters(IMAGE_HEIGHT.toFloat(), CameraPose.Glasses, FOCAL_PX, CENTER_Y, IMAGE_HEIGHT)!!, 0.01f)
        assertNull(GroundPlane.forwardMeters(CENTER_Y, CameraPose.Glasses, FOCAL_PX, CENTER_Y, IMAGE_HEIGHT))
    }

    @Test
    fun `calibrage inutilisable ne mesure pas`() {
        assertNull(GroundPlane.forwardMeters(rowOf(3f), pose(), 0f, CENTER_Y, IMAGE_HEIGHT))
        assertNull(GroundPlane.forwardMeters(rowOf(3f), pose(), -1000f, CENTER_Y, IMAGE_HEIGHT))
        assertNull(GroundPlane.forwardMeters(rowOf(3f), pose(), Float.NaN, CENTER_Y, IMAGE_HEIGHT))
        assertNull(GroundPlane.forwardMeters(rowOf(3f), pose(), FOCAL_PX, CENTER_Y, 0))
        assertNull(GroundPlane.rowAt(3f, pose(), 0f, CENTER_Y))
    }

    @Test
    fun `entree non finie ne mesure pas`() {
        assertNull(forwardOf(Float.NaN))
        assertNull(forwardOf(Float.POSITIVE_INFINITY))
        assertNull(GroundPlane.rowAt(Float.NaN, pose(), FOCAL_PX, CENTER_Y))
        assertNull(GroundPlane.rowAt(-2f, pose(), FOCAL_PX, CENTER_Y))
    }

    @Test
    fun `borne basse de 0,3 m, partagee avec le stenope`() {
        assertEquals(0.3f, Pinhole.MIN_METERS, 0f)
        // focale 100 px : la ligne de 0,29 m est dans le cadre, sous la borne → rien
        assertNull(forwardOf(rowOf(0.29f, focalPx = 100f), focalPx = 100f))
        assertEquals(0.35f, forwardOf(rowOf(0.35f, focalPx = 100f), focalPx = 100f)!!, 0.001f)
    }

    @Test
    fun `borne haute de 60 m, partagee avec le stenope`() {
        assertEquals(60f, Pinhole.MAX_METERS, 0f)
        assertEquals(59f, forwardOf(rowOf(59f))!!, 0.01f)
        assertNull(forwardOf(rowOf(61f)))
    }

    @Test
    fun `ecart lateral symetrique et point au sol complet`() {
        assertEquals(0f, GroundPlane.lateralMeters(CENTER_X, 7.7778f, FOCAL_PX, CENTER_X)!!, 1e-6f)
        assertEquals(1.2444f, GroundPlane.lateralMeters(CENTER_X + 160f, 7.7778f, FOCAL_PX, CENTER_X)!!, 0.01f)
        assertEquals(-1.2444f, GroundPlane.lateralMeters(CENTER_X - 160f, 7.7778f, FOCAL_PX, CENTER_X)!!, 0.01f)
        assertNull(GroundPlane.lateralMeters(CENTER_X, 7.7778f, 0f, CENTER_X))
        // 900 px, boîte à 160 px du centre : 7,778 m de profondeur, 7,877 m de distance réelle
        val point = measure(900f, CENTER_X + 160f)!!
        assertEquals(7.7778f, point.forwardMeters, 0.01f)
        assertEquals(1.2444f, point.lateralMeters, 0.01f)
        assertEquals(7.8767f, point.slantMeters, 0.01f)
        assertEquals(0.1781f, point.depressionRad, 1e-4f)
    }

    @Test
    fun `les lignes qui descendent rapprochent l'objet`() {
        assertNull(forwardOf(CENTER_Y))
        val ladder = listOf(800f, 1000f, 1200f, 1430f).map { forwardOf(it)!! }
        assertEquals(17.5f, ladder.first(), 0.01f)
        assertEquals(1.9718f, ladder.last(), 0.01f)
        assertTrue(ladder.zipWithNext().all { (near, far) -> near > far })
    }
}