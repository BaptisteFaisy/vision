package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Solveur d'étalonnage guidé : champ mesuré depuis une taille et une distance connues. */
class CalibrationSolverTest {

    @Test
    fun `focale connue retrouvee au dixieme de degre`() {
        // 67° sur 512 px : f = 386,8 px ; 1,78 m à 2 m → 344,2 px
        val found = CalibrationSolver.solve(1.78f, 2f, 344.2f, 512)!!
        assertEquals(67f, found.fovDeg, 0.1f)
        assertTrue(found.toleranceDeg in 0.1f..1f)
    }

    @Test
    fun `tolerance resserree par les grandes boites`() {
        val near = CalibrationSolver.solve(1.78f, 2f, 344f, 512)!!
        val far = CalibrationSolver.solve(1.78f, 8f, 86f, 512)!!
        assertTrue(near.toleranceDeg < far.toleranceDeg)
        assertEquals(near.fovDeg, far.fovDeg, 0.5f)
    }

    @Test
    fun `entrees incoherentes refusees sans valeur`() {
        assertNull(CalibrationSolver.solve(0f, 2f, 340f, 512))
        assertNull(CalibrationSolver.solve(1.78f, 0f, 340f, 512))
        assertNull(CalibrationSolver.solve(1.78f, 2f, 10f, 512))
        assertNull(CalibrationSolver.solve(1.78f, 2f, 340f, 0))
        assertNull(CalibrationSolver.solve(Float.NaN, 2f, 340f, 512))
        assertNull(CalibrationSolver.solve(5f, 2f, 340f, 512))
        assertNull(CalibrationSolver.solve(1.78f, 50f, 340f, 512))
        // visée fausse : 340 px pour 20 cm à 10 m ne cadre aucun téléphone
        assertNull(CalibrationSolver.solve(0.5f, 10f, 340f, 512))
    }
}
