package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décision pure de l'onboarding des trois gestes : la pose du doigt est calculée
 * sans Android, donc vérifiable en JVM. Ce qui dépend du moteur Compose (rendu réel
 * du Canvas, antialiasing) reste hors de portée : c'est dit dans la PR.
 */
class OnboardingGesturesTest {

    private val kinds = OnboardingGesture.entries.toList()
    private val delta = 1e-4f

    @Test
    fun `au depart, la pointe est au repos et rien n'est allume`() {
        kinds.forEach { kind ->
            val pose = onboardingPose(kind, 0f)
            assertEquals("repos ($kind)", 0f, pose.press, delta)
            assertEquals("pointe haute ($kind)", ONBOARDING_REST_Y, pose.tip, delta)
            assertFalse("pas de contact ($kind)", pose.pressed)
            assertFalse("REC éteint ($kind)", pose.recOn)
            assertEquals("aucun anneau ($kind)", ONBOARDING_OFF, pose.ripple, delta)
            assertEquals("aucun éclat ($kind)", ONBOARDING_OFF, pose.flash, delta)
            assertEquals("aucune onde ($kind)", ONBOARDING_OFF, pose.wavePhase, delta)
            assertEquals("aucune glissade ($kind)", ONBOARDING_OFF, pose.swipeUp, delta)
            assertFalse("Traversée éteinte ($kind)", pose.crossingOn)
        }
    }

    @Test
    fun `tap simple, un seul contact, la video s'allume pendant la pression`() {
        val pendant = onboardingPose(OnboardingGesture.Tap, 0.4f)
        assertEquals(1f, pendant.press, delta)
        assertTrue(pendant.pressed)
        assertEquals(ONBOARDING_TOUCH_Y, pendant.tip, delta)
        assertTrue("pastille REC allumée", pendant.recOn)
        assertTrue("anneau du tap en cours", pendant.ripple in 0f..1f)
        assertFalse("pas de photo", pendant.cameraFlash)

        val apres = onboardingPose(OnboardingGesture.Tap, 0.9f)
        assertEquals(0f, apres.press, delta)
        assertEquals(ONBOARDING_REST_Y, apres.tip, delta)
        assertFalse("vidéo arrêtée", apres.recOn)
        assertEquals("anneau éteint", ONBOARDING_OFF, apres.ripple, delta)
    }

    @Test
    fun `appui long, le doigt reste pose et les ondes pulsent`() {
        val debut = onboardingPose(OnboardingGesture.Hold, 0.3f)
        val fin = onboardingPose(OnboardingGesture.Hold, 0.9f)
        assertEquals(1f, debut.press, delta)
        assertEquals("le doigt ne se relève pas", 1f, fin.press, delta)
        assertEquals("pointe posée à l'arrivée", debut.tip, fin.tip, delta)
        assertEquals(ONBOARDING_TOUCH_Y, fin.tip, delta)
        assertTrue("ondes de la voix", fin.wavePhase in 0f..1f)
        assertTrue("les ondes avancent", fin.wavePhase > debut.wavePhase)
        assertEquals("aucun anneau de tap", ONBOARDING_OFF, debut.ripple, delta)
        assertEquals("aucun éclat de photo", ONBOARDING_OFF, fin.flash, delta)
        assertFalse("pas de pastille REC", fin.recOn)
    }

    @Test
    fun `balayage haut, le doigt glisse vers le haut et la puce Traversee s'allume`() {
        val glisse = onboardingPose(OnboardingGesture.SwipeUp, 0.4f)
        assertEquals(1f, glisse.press, delta)
        assertTrue(glisse.pressed)
        assertTrue("la pointe a quitté l'écran vers le haut", glisse.tip < ONBOARDING_TOUCH_Y)
        assertTrue("glissade en cours", glisse.swipeUp in 0f..1f)
        assertFalse("pas encore basculé", glisse.crossingOn)

        val bascule = onboardingPose(OnboardingGesture.SwipeUp, 0.7f)
        assertTrue("puce Traversée allumée", bascule.crossingOn)
        assertFalse("pas de pastille REC", bascule.recOn)
        assertEquals("aucun anneau de tap", ONBOARDING_OFF, bascule.ripple, delta)
        assertEquals("aucun éclat de photo", ONBOARDING_OFF, bascule.flash, delta)
        assertEquals("aucune onde de voix", ONBOARDING_OFF, bascule.wavePhase, delta)

        val fin = onboardingPose(OnboardingGesture.SwipeUp, 0.95f)
        assertEquals("doigt relevé", 0f, fin.press, delta)
        assertEquals(ONBOARDING_REST_Y, fin.tip, delta)
        assertTrue("le mode reste allumé après le geste", fin.crossingOn)
    }

    @Test
    fun `une pression n'existe que dans sa fenetre`() {
        // Fenêtre [0,2 .. 0,8] : rien avant, plein au milieu, rien après.
        assertEquals(0f, pressAmount(0.1f, 0.2f, 0.3f, 0.7f, 0.8f), delta)
        assertEquals(1f, pressAmount(0.5f, 0.2f, 0.3f, 0.7f, 0.8f), delta)
        assertEquals(0f, pressAmount(0.9f, 0.2f, 0.3f, 0.7f, 0.8f), delta)
        // Montée et descente symétriques : la même valeur lissée des deux côtés.
        val monte = pressAmount(0.25f, 0.2f, 0.3f, 0.7f, 0.8f)
        val descend = pressAmount(0.75f, 0.2f, 0.3f, 0.7f, 0.8f)
        assertTrue(monte in 0f..1f)
        assertEquals(monte, descend, delta)
    }

    @Test
    fun `les cartes ne pulsent pas ensemble`() {
        val periods = kinds.map(::onboardingPeriodMs)
        assertEquals(kinds.size, periods.distinct().size)
        assertTrue("boucle du tap la plus courte", periods[0] < periods[1])
    }

    @Test
    fun `chaque geste se distingue par ce qu'il allume`() {
        val tap = onboardingPose(OnboardingGesture.Tap, 0.4f)
        val hold = onboardingPose(OnboardingGesture.Hold, 0.5f)
        val swipe = onboardingPose(OnboardingGesture.SwipeUp, 0.7f)
        assertTrue("la vidéo n'est allumée que par le tap", tap.recOn && !hold.recOn && !swipe.recOn)
        assertFalse("le tap ne déclenche pas la photo", tap.cameraFlash)
        assertTrue(
            "l'appui long ne montre ni anneau ni éclat de photo",
            hold.ripple == ONBOARDING_OFF && hold.flash == ONBOARDING_OFF,
        )
        assertTrue("seules les ondes apparaissent pour l'appui long", hold.wavePhase >= 0f)
        assertTrue(
            "seule la puce Traversée s'allume pour le balayage haut",
            swipe.crossingOn && !tap.crossingOn && !hold.crossingOn,
        )
    }
}