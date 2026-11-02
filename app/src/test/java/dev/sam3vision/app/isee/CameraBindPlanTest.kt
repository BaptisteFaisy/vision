package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille les décisions pures de la liaison caméra : plans de repli, reprises
 * bornées, décision de (re)lier, verdict d'une tentative ratée et mot affiché.
 *
 * Ce sont ces règles qui empêchent les trois pannes silencieuses de l'écran :
 * une caméra liée sous un état « échec », une reprise en boucle après qu'une
 * autre app a pris le capteur, et un échec que personne ne lisait.
 */
class CameraBindPlanTest {

    private val plans = bindPlans()

    @Test
    fun `le premier plan garde les quatre cas d'usage`() {
        assertEquals(BindUseCase.values().toSet(), plans.first().useCases)
        assertNull("le plan complet ne sacrifie rien", plans.first().dropped)
    }

    @Test
    fun `chaque plan est un sous ensemble du precedent`() {
        plans.zipWithNext { previous, next ->
            assertTrue(
                "le plan ${next.useCases} n'est pas inclus dans ${previous.useCases}",
                previous.useCases.containsAll(next.useCases),
            )
        }
    }

    @Test
    fun `aucun plan n'est vide`() {
        plans.forEach { assertFalse("plan vide", it.useCases.isEmpty()) }
    }

    @Test
    fun `la video tombe avant la photo`() {
        assertEquals(BindUseCase.VIDEO, plans[1].dropped)
        assertEquals(BindUseCase.CAPTURE, plans[2].dropped)
        assertEquals(BindUseCase.PREVIEW, plans.last().dropped)
    }

    @Test
    fun `l analyse survit jusqu au dernier plan`() {
        plans.forEach {
            assertTrue("l'analyse manque au plan ${it.useCases}", BindUseCase.ANALYSIS in it.useCases)
        }
        assertEquals(setOf(BindUseCase.ANALYSIS), plans.last().useCases)
    }

    @Test
    fun `apres un echec le premier delai s applique`() {
        assertEquals(CAMERA_RETRY_DELAYS_MS[0], nextRetryDelayMs(1, recording = false))
    }

    @Test
    fun `au plafond on cesse de retenter`() {
        val cap = CAMERA_RETRY_DELAYS_MS.size + 1
        assertNull(nextRetryDelayMs(cap, recording = false))
        assertNull(nextRetryDelayMs(cap + 3, recording = false))
    }

    @Test
    fun `jamais de reprise pendant un enregistrement`() {
        assertNull(nextRetryDelayMs(1, recording = true))
    }

    @Test
    fun `sans echec il n y a rien a reprendre`() {
        assertNull(nextRetryDelayMs(0, recording = false))
    }

    @Test
    fun `les delais sont croissants`() {
        CAMERA_RETRY_DELAYS_MS.zipWithNext { first, next ->
            assertTrue("$next doit suivre $first", next > first)
        }
    }

    @Test
    fun `sans permission on ne lie pas`() {
        assertEquals(CameraGate.HOLD, cameraGate(false, false, false, false, 0))
    }

    @Test
    fun `permission accordee et rien de lie demande une liaison`() {
        assertEquals(CameraGate.BIND, cameraGate(true, bound = false, closed = false, recording = false, failures = 0))
    }

    @Test
    fun `deja liee et saine ne relie pas`() {
        assertEquals(CameraGate.HOLD, cameraGate(true, bound = true, closed = false, recording = false, failures = 0))
    }

    @Test
    fun `camera fermee par le systeme demande un re-lien`() {
        assertEquals(CameraGate.REBIND, cameraGate(true, bound = false, closed = true, recording = false, failures = 0))
    }

    @Test
    fun `pendant un mp4 on ne touche a rien`() {
        assertEquals(CameraGate.HOLD, cameraGate(true, bound = true, closed = false, recording = true, failures = 0))
        assertEquals(CameraGate.HOLD, cameraGate(true, bound = false, closed = true, recording = true, failures = 2))
    }

    @Test
    fun `apres un echec on attend avant de relier`() {
        assertEquals(CameraGate.RETRY, cameraGate(true, bound = false, closed = false, recording = false, failures = 1))
    }

    @Test
    fun `au plafond on renonce`() {
        val cap = CAMERA_RETRY_DELAYS_MS.size + 1
        assertEquals(CameraGate.GIVE_UP, cameraGate(true, bound = false, closed = false, recording = false, failures = cap))
    }

    @Test
    fun `liee avec recorder et sans echec est prete`() {
        assertEquals(BindVerdict.READY, bindVerdict(bound = true, recorderAttached = true, failure = null))
    }

    @Test
    fun `liee sans recorder doit etre deliee`() {
        assertEquals(BindVerdict.RELEASE, bindVerdict(bound = true, recorderAttached = false, failure = null))
    }

    @Test
    fun `un echec avoue delie meme une camera liee`() {
        assertEquals(BindVerdict.RELEASE, bindVerdict(bound = true, recorderAttached = true, failure = "boom"))
        assertEquals(BindVerdict.RELEASE, bindVerdict(bound = false, recorderAttached = false, failure = "boom"))
    }

    @Test
    fun `sans raison l echec affiche le mot de la carte`() {
        assertEquals(CAMERA_MISSING_TEXT, cameraFailureText(null))
        assertEquals(CAMERA_MISSING_TEXT, cameraFailureText("   "))
    }

    @Test
    fun `le detail trop long est tronque`() {
        val long = "x".repeat(CAMERA_DETAIL_MAX + 20)
        val text = cameraFailureText(long)
        assertTrue(text.startsWith(CAMERA_MISSING_TEXT))
        // Le texte est « <mot de la carte> (<détail>) » : l'ellipse est dans le détail.
        assertTrue("détail non tronqué : $text", text.contains("…"))
        assertTrue(
            "détail trop long : ${text.length}",
            text.length <= CAMERA_MISSING_TEXT.length + CAMERA_DETAIL_MAX + 4,
        )
    }
}
