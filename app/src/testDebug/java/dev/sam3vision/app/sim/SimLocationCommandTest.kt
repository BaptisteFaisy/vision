package dev.sam3vision.app.sim

import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lecture des extras de simulation, sans appareil : ce qui se prouve en JVM, c'est la
 * traduction `am broadcast` → [SimFix] et ses bornes. La construction d'un
 * `android.location.Location` et l'appel réel à `setTestProviderLocation` restent à vérifier
 * sur émulateur (stubs Android non fonctionnels hors appareil).
 */
class SimLocationCommandTest {

    private fun ok(extras: Map<String, String?>): SimFix =
        (parseSimFix(extras) as SimParse.Ok).fix

    private fun reason(extras: Map<String, String?>?): String =
        (parseSimFix(extras) as SimParse.Rejected).reason

    /** Une balade réelle : ce que le harnais envoie au minimum. */
    private val paris = mapOf("lat" to "48.8566", "lon" to "2.3522")

    @Test
    fun `extras minimaux deviennent un fix complet`() {
        val fix = ok(paris)
        assertEquals(48.8566, fix.lat, 1e-9)
        assertEquals(2.3522, fix.lon, 1e-9)
        assertEquals(SimFix.DEFAULT_ACC_M.toFloat(), fix.accM, 1e-6f)
        assertNull(fix.bearingDeg)
        assertNull(fix.speedMps)
        assertEquals(0L, fix.atMs)
    }

    @Test
    fun `precision par defaut traverse le guidage`() {
        val tuning = NavTuning()
        assertTrue(SimFix.DEFAULT_ACC_M > SimFix.MIN_ACC_M)
        assertTrue(SimFix.DEFAULT_ACC_M.toFloat() <= tuning.maxAccM)
        // Le plafond admis ici est exactement celui que le moteur rejette au-delà.
        assertEquals(tuning.maxAccM, SimFix.MAX_ACC_M.toFloat(), 0f)
    }

    @Test
    fun `fix produit est utilisable par le moteur`() {
        val fix = ok(paris + ("acc" to "12") + ("ms" to "1700000000000"))
        val nav = NavFix(lat = fix.lat, lon = fix.lon, accM = fix.accM, atMs = fix.atMs)
        assertTrue(nav.isUsable())
        assertEquals(1700000000000L, nav.atMs)
    }

    @Test
    fun `precision hors bornes est refusee`() {
        listOf("0", "0.5", "26", "50").forEach { acc ->
            val why = reason(paris + ("acc" to acc))
            assertTrue("acc=$acc devrait être refusé, dit : $why", why.contains("acc"))
        }
    }

    @Test
    fun `precision aux bornes est acceptee`() {
        assertEquals(1f, ok(paris + ("acc" to "1")).accM, 1e-6f)
        assertEquals(25f, ok(paris + ("acc" to "25")).accM, 1e-6f)
    }

    @Test
    fun `coordonnees hors domaine ou illisibles sont refusees`() {
        assertTrue(reason(mapOf("lat" to "90.1", "lon" to "2.3")).contains("lat"))
        assertTrue(reason(mapOf("lat" to "-90.5", "lon" to "2.3")).contains("lat"))
        assertTrue(reason(mapOf("lat" to "48.8", "lon" to "180.5")).contains("lon"))
        assertTrue(reason(mapOf("lat" to "nord", "lon" to "2.3")).contains("lat"))
        assertTrue(reason(mapOf("lat" to "48.8", "lon" to "est")).contains("lon"))
    }

    @Test
    fun `coordonnees aux bornes sont acceptees`() {
        assertEquals(90.0, ok(mapOf("lat" to "90", "lon" to "180")).lat, 0.0)
        assertEquals(-180.0, ok(mapOf("lat" to "-90", "lon" to "-180")).lon, 0.0)
    }

    /// Extras manquants : le fix est ignoré, jamais complété d'office (pas de 0,0 silencieux).
    @Test
    fun `extras manquants sont ignores`() {
        assertTrue(reason(null).contains("aucun extra"))
        assertTrue(reason(emptyMap()).contains("lat"))
        assertTrue(reason(mapOf("lon" to "2.3522")).contains("lat"))
        assertTrue(reason(mapOf("lat" to "48.8566")).contains("lon"))
        assertTrue(reason(mapOf("lat" to "", "lon" to "2.3522")).contains("lat"))
    }

    @Test
    fun `cap est ramene dans le tour et vitesse negative refusee`() {
        assertEquals(350f, ok(paris + ("bearing" to "-10")).bearingDeg!!, 1e-4f)
        assertEquals(90f, ok(paris + ("bearing" to "450")).bearingDeg!!, 1e-4f)
        assertEquals(1.4f, ok(paris + ("speed" to "1.4")).speedMps!!, 1e-5f)
        assertTrue(reason(paris + ("speed" to "-2")).contains("speed"))
        assertTrue(reason(paris + ("bearing" to "plein nord")).contains("bearing"))
        assertTrue(reason(paris + ("ms" to "-1")).contains("ms"))
    }
}
