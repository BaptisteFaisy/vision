package dev.sam3vision.app.pose

import dev.sam3vision.app.heightText
import dev.sam3vision.app.parseHeight
import dev.sam3vision.core.distance.CameraPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Maths du tangage IMU : pures, testées en JVM sans capteur ni Android.
 */
class PitchMathTest {

    private val halfPi = PI.toFloat() / 2f

    @Test
    fun `telephone vertical donne un tangage nul`() {
        assertEquals(0f, PitchMath.pitchRadFromOrientation(-halfPi), 1e-6f)
    }

    @Test
    fun `telephone a plat donne une camera plein vers le sol`() {
        assertEquals(halfPi, PitchMath.pitchRadFromOrientation(0f), 1e-6f)
    }

    @Test
    fun `hochement vers le bas donne un tangage positif`() {
        assertEquals(0.3f, PitchMath.pitchRadFromOrientation(-halfPi + 0.3f), 1e-6f)
    }

    @Test
    fun `tangage borne a plus ou moins pi sur deux`() {
        assertEquals(halfPi, PitchMath.pitchRadFromOrientation(halfPi), 1e-6f)
        assertEquals(-halfPi, PitchMath.pitchRadFromOrientation(-2f * halfPi), 1e-6f)
    }

    @Test
    fun `echantillon non fini traverse vers le filtre`() {
        assertTrue(PitchMath.pitchRadFromOrientation(Float.NaN).isNaN())
    }

    @Test
    fun `premier echantillon pris tel quel`() {
        assertEquals(0.4f, PitchMath.lowPass(null as Float?, 0.4f), 1e-6f)
    }

    @Test
    fun `passe-bas par defaut a zero virgule deux`() {
        assertEquals(PitchMath.ALPHA, 0.2f, 1e-6f)
        assertEquals(0.2f, PitchMath.lowPass(0f, 1f), 1e-6f)
    }

    @Test
    fun `passe-bas melange au taux demande`() {
        assertEquals(0.5f, PitchMath.lowPass(0f, 1f, alpha = 0.5f), 1e-6f)
        assertEquals(1f, PitchMath.lowPass(0f, 1f, alpha = 1f), 1e-6f)
        assertEquals(0f, PitchMath.lowPass(0f, 1f, alpha = 0f), 1e-6f)
    }

    @Test
    fun `echantillon non fini garde le precedent`() {
        assertEquals(0.3f, PitchMath.lowPass(0.3f, Float.NaN), 1e-6f)
        assertEquals(0f, PitchMath.lowPass(null as Float?, Float.NaN), 1e-6f)
    }

    @Test
    fun `pose complete quand hauteur et tangage valides`() {
        val pose = PitchMath.poseOrNull(1.5f, 0.3f)

        assertEquals(CameraPose(1.5f, 0.3f), pose)
    }

    @Test
    fun `pose nulle sans tangage`() {
        assertNull(PitchMath.poseOrNull(1.5f, null))
        assertNull(PitchMath.poseOrNull(1.5f, Float.NaN))
    }

    @Test
    fun `pose nulle quand hauteur hors main`() {
        assertNull(PitchMath.poseOrNull(0.1f, 0.3f))
        assertNull(PitchMath.poseOrNull(99f, 0.3f))
        assertNull(PitchMath.poseOrNull(Float.NaN, 0.3f))
    }

    @Test
    fun `poseOutcome dit pourquoi la pose manque`() {
        assertEquals(PoseOutcome.Invalid(PoseIssue.NO_SAMPLE), PitchMath.poseOutcome(1.5f, null))
        assertEquals(PoseOutcome.Invalid(PoseIssue.BAD_PITCH), PitchMath.poseOutcome(1.5f, Float.NaN))
        assertEquals(PoseOutcome.Invalid(PoseIssue.BAD_HEIGHT), PitchMath.poseOutcome(0.1f, 0.3f))
        assertEquals(PoseOutcome.Invalid(PoseIssue.BAD_HEIGHT), PitchMath.poseOutcome(Float.NaN, 0.3f))
        assertEquals(PoseOutcome.Valid(CameraPose(1.5f, 0.3f)), PitchMath.poseOutcome(1.5f, 0.3f))
    }

    @Test
    fun `passe-bas avec chauffe compte puis stabilise`() {
        val first = PitchMath.lowPass(null as FilteredPitch?, 0.4f)
        assertEquals(0.4f, first.pitchRad, 1e-6f)
        assertEquals(1, first.samples)
        assertFalse(first.settled)
        var state: FilteredPitch? = null
        repeat(PitchMath.SETTLING_SAMPLES) { state = PitchMath.lowPass(state, 1f) }
        assertEquals(PitchMath.SETTLING_SAMPLES, state!!.samples)
        assertTrue(state!!.settled)
    }

    @Test
    fun `passe-bas avec chauffe jette le non fini sans compter`() {
        val prev = FilteredPitch(0.3f, 2)
        assertEquals(prev, PitchMath.lowPass(prev, Float.NaN))
        assertEquals(FilteredPitch(0f, 0), PitchMath.lowPass(null as FilteredPitch?, Float.NaN))
    }

    @Test
    fun `hauteur affichee a une decimale`() {
        assertEquals("1.5", heightText(1.5f))
        assertEquals("1.5", heightText(1.54f))
        assertEquals("2.0", heightText(2f))
    }

    @Test
    fun `hauteur saisie tolere la virgule`() {
        assertEquals(1.5f, parseHeight("1,5"))
        assertEquals(1.2f, parseHeight(" 1.2 "))
    }

    @Test
    fun `hauteur saisie bornee main`() {
        assertEquals(CameraPose.MIN_HEIGHT_M, parseHeight("0.1"))
        assertEquals(CameraPose.MAX_HEIGHT_M, parseHeight("99"))
    }

    @Test
    fun `hauteur illisible rend nul`() {
        assertNull(parseHeight(""))
        assertNull(parseHeight("abc"))
    }
}
