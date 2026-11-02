package dev.sam3vision.core.live

import dev.sam3vision.core.speech.IseeClipsEn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Veille de panne du direct : un mot par palier et par épisode, silence au retour trop
 * court, anti-bavardage entre épisodes, seuils décalés au démarrage à froid, et aucun
 * palier sauté quand un tick espacé Franchit deux seuils.
 */
class OutageWatchTest {

    private fun watch() = OutageWatch()

    @Test
    fun `tout va bien ne dit rien`() {
        val w = watch()
        assertNull(w.onTick(0, healthy = true, warmed = true, degradedAvailable = false))
        assertNull(w.onTick(5_000, healthy = true, warmed = true, degradedAvailable = false))
    }

    @Test
    fun `un hoquet plus court que le seuil ne parle pas`() {
        val w = watch()
        assertNull(w.onTick(0, healthy = false, warmed = true, degradedAvailable = false))
        assertNull(w.onTick(3_000, healthy = false, warmed = true, degradedAvailable = false))
        assertNull(w.onTick(6_000, healthy = true, warmed = true, degradedAvailable = false))
    }

    @Test
    fun `six secondes sans frame disent une fois que la vision manque`() {
        val w = watch()
        assertNull(w.onTick(0, healthy = false, warmed = true, degradedAvailable = false))
        assertEquals(OutageStep.LOST, w.onTick(6_000, false, warmed = true, degradedAvailable = false))
        // Un épisode ne se répète jamais, même si le tick continue toutes les 500 ms.
        assertNull(w.onTick(6_500, false, warmed = true, degradedAvailable = false))
        assertNull(w.onTick(7_000, false, warmed = true, degradedAvailable = false))
    }

    @Test
    fun `quinze secondes distinguent le guidage qui continue de l impasse`() {
        val withGps = watch()
        withGps.onTick(0, false, warmed = true, degradedAvailable = true)
        withGps.onTick(6_000, false, warmed = true, degradedAvailable = true)
        assertEquals(OutageStep.DEGRADED_GPS, withGps.onTick(15_000, false, warmed = true, degradedAvailable = true))

        val noGps = watch()
        noGps.onTick(0, false, warmed = true, degradedAvailable = false)
        noGps.onTick(6_000, false, warmed = true, degradedAvailable = false)
        assertEquals(OutageStep.DEGRADED_NONE, noGps.onTick(15_000, false, warmed = true, degradedAvailable = false))
    }

    @Test
    fun `soixante secondes proposent l arret, une seule fois`() {
        val w = watch()
        w.onTick(0, false, warmed = true, degradedAvailable = false)
        w.onTick(6_000, false, warmed = true, degradedAvailable = false)
        w.onTick(15_000, false, warmed = true, degradedAvailable = false)
        assertEquals(OutageStep.STOP, w.onTick(60_000, false, warmed = true, degradedAvailable = false))
        assertNull(w.onTick(90_000, false, warmed = true, degradedAvailable = false))
    }

    @Test
    fun `un retour court ne se dit pas, un retour apres une annonce se dit`() {
        val short = watch()
        short.onTick(0, false, warmed = true, degradedAvailable = false)
        assertNull(short.onTick(500, healthy = true, warmed = true, degradedAvailable = false))

        val spoken = watch()
        spoken.onTick(0, false, warmed = true, degradedAvailable = false)
        spoken.onTick(6_000, false, warmed = true, degradedAvailable = false)
        assertEquals(OutageStep.BACK, spoken.onTick(20_000, healthy = true, warmed = true, degradedAvailable = false))
        // Le retour ne se répète pas : l'épisode est clos.
        assertNull(spoken.onTick(21_000, healthy = true, warmed = true, degradedAvailable = false))
    }

    @Test
    fun `deux episodes rapproches ne bavardent pas`() {
        val w = watch()
        w.onTick(0, false, warmed = true, degradedAvailable = false)
        w.onTick(6_000, false, warmed = true, degradedAvailable = false) // LOST dit
        w.onTick(7_000, healthy = true, warmed = true, degradedAvailable = false) // BACK dit
        // Nouvel épisode 8 s plus tard : le premier mot est tu (fenêtre de 30 s).
        w.onTick(15_000, false, warmed = true, degradedAvailable = false)
        assertNull(w.onTick(21_000, false, warmed = true, degradedAvailable = false))
        // Passé la fenêtre, l'escalade suivante parle de nouveau.
        assertEquals(OutageStep.DEGRADED_NONE, w.onTick(30_000, false, warmed = true, degradedAvailable = false))
    }

    @Test
    fun `avant la premiere frame les seuils sont decales`() {
        val w = watch()
        assertNull(w.onTick(0, false, warmed = false, degradedAvailable = false))
        // À 6 s et 15 s, le conteneur GPU peut encore démarrer : aucun cri.
        assertNull(w.onTick(6_000, false, warmed = false, degradedAvailable = false))
        assertNull(w.onTick(15_000, false, warmed = false, degradedAvailable = false))
        assertEquals(OutageStep.DEGRADED_NONE, w.onTick(60_000, false, warmed = false, degradedAvailable = false))
        assertEquals(OutageStep.STOP, w.onTick(120_000, false, warmed = false, degradedAvailable = false))
    }

    @Test
    fun `un tick espace ne saute pas de palier`() {
        val w = watch()
        w.onTick(0, false, warmed = true, degradedAvailable = false)
        // Un seul tick à 20 s : on annonce le plus grave, les paliers sautés sont marqués dits.
        assertEquals(OutageStep.DEGRADED_NONE, w.onTick(20_000, false, warmed = true, degradedAvailable = false))
        assertEquals(OutageStep.STOP, w.onTick(60_000, false, warmed = true, degradedAvailable = false))
    }

    @Test
    fun `reset oublie l episode et l anti bavardage`() {
        val w = watch()
        w.onTick(0, false, warmed = true, degradedAvailable = false)
        w.onTick(6_000, false, warmed = true, degradedAvailable = false)
        w.reset()
        assertNull(w.onTick(10_000, healthy = true, warmed = true, degradedAvailable = false))
        w.onTick(11_000, false, warmed = true, degradedAvailable = false)
        assertEquals(OutageStep.LOST, w.onTick(17_000, false, warmed = true, degradedAvailable = false))
    }

    @Test
    fun `les mots de panne utilisent les clips deja embarques`() {
        assertEquals(IseeClipsEn.GPS_UNAVAILABLE, OutageStep.LOST.word().clipId)
        assertEquals(IseeClipsEn.GPS_OFF, OutageStep.STOP.word().clipId)
        // Pas de nouvel audio : ces deux paliers restent visuels seuls.
        assertNull(OutageStep.DEGRADED_GPS.word().clipId)
        assertNull(OutageStep.DEGRADED_NONE.word().clipId)
        assertNull(OutageStep.BACK.word().clipId)
        assertEquals("No connection — retrying…", OutageStep.LOST.word().line)
    }
}
