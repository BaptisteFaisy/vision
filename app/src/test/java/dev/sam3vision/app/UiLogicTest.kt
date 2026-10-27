package dev.sam3vision.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique pure de l'UI : palette des masques et échelle d'affichage. */
class UiLogicTest {

    @Test
    fun `la couleur des masques tourne sans trou`() {
        val first = maskColorAt(0)
        val second = maskColorAt(1)
        assertNotEquals(first, second)
        assertEquals(first, maskColorAt(MASK_COLORS.size))
        assertTrue(MASK_COLORS.size >= 6)
        assertTrue(MASK_COLORS.distinct().size == MASK_COLORS.size)
    }

    @Test
    fun `l'echelle d'overlay ne grandit jamais l'image`() {
        assertEquals(1f, overlayScaleFor(1200f, 800))
        assertEquals(0.5f, overlayScaleFor(500f, 1000))
        assertEquals(1f, overlayScaleFor(500f, 0))
    }

    @Test
    fun `la dictee s'ajoute au brouillon sans coller les mots`() {
        assertEquals("quelle heure", mergeDraft("", "  quelle heure "))
        assertEquals("decris la photo quelle heure", mergeDraft("decris la photo", "quelle heure"))
        assertEquals("deja la", mergeDraft("deja la", "   "))
        assertEquals("seule", mergeDraft("  ", "seule"))
    }

    @Test
    fun `la bascule d'intro dit l'etat qu'elle porte`() {
        assertEquals("Intro on every opening: on", introToggleLabel(true))
        assertEquals("Intro on every opening: off", introToggleLabel(false))
        assertNotEquals(introToggleLabel(true), introToggleLabel(false))
    }

    @Test
    fun `la bulle d'intro est posee a chaque ouverture tant que l'intro n'est pas coupee`() {
        assertTrue(introBubbleVisible(settingsReady = true, introEnabled = true))
        assertTrue(!introBubbleVisible(settingsReady = false, introEnabled = true))
        assertTrue(!introBubbleVisible(settingsReady = true, introEnabled = false))
    }

    @Test
    fun `la phrase Disable iSee intro coupe l'intro et rien d'autre`() {
        assertTrue(isIntroDisablePhrase("Please disable iSee intro"))
        assertTrue(isIntroDisablePhrase("  DISABLE ISEE INTRO. "))
        // La dictée entend « i see » en deux mots : la coupe doit quand même marcher.
        assertTrue(isIntroDisablePhrase("ok, disable I see intro please"))
        // La dictée écrit parfois « iSee » en lettres (« IC », « I.C. », « I C »).
        assertTrue(isIntroDisablePhrase("disable IC intro"))
        assertTrue(isIntroDisablePhrase("Please, disable I.C. intro!"))
        assertTrue(isIntroDisablePhrase("disable I C intro"))
        assertFalse(isIntroDisablePhrase("What does iSee intro say?"))
        assertFalse(isIntroDisablePhrase("disable intro"))
        assertFalse(isIntroDisablePhrase(""))
    }

    @Test
    fun `la phrase francaise desactive iSee intro coupe aussi l'intro`() {
        // L'interface est en français : la phrase dictée en français doit couper.
        assertTrue(isIntroDisablePhrase("désactive isee intro"))
        assertTrue(isIntroDisablePhrase("Desactive iSee intro !"))
        // La dictée entend « i see » en deux mots, avec l'accent de « désactive ».
        assertTrue(isIntroDisablePhrase("ok, désactive i see intro s'il te plaît"))
        assertTrue(isIntroDisablePhrase("Désactiver iSee intro"))
        assertTrue(isIntroDisablePhrase("désactive IC intro"))
        // Une question sur l'intro, même en français, ne coupe rien.
        assertFalse(isIntroDisablePhrase("Que dit l'intro iSee ?"))
        assertFalse(isIntroDisablePhrase("désactive le micro"))
    }

    @Test
    fun `le texte de l'intro est celui que la voix dit`() {
        assertTrue(ISEE_INTRO_TEXT.startsWith("Welcome to iSee."))
        assertTrue(ISEE_INTRO_TEXT.contains("tap again to stop"))
        assertTrue(ISEE_INTRO_TEXT.contains("Disable iSee intro"))
        // Plus de navigation entre messages : l'intro n'annonce plus de balayage.
        assertFalse(ISEE_INTRO_TEXT.contains("swipe down"))
        assertTrue(ISEE_INTRO_TEXT.indexOf("Disable iSee intro") > 0)
    }

    @Test
    fun `la taille cible echange largeur et hauteur sur un quart de tour`() {
        // Capteur 640×480 livré à l'horizontale, redressé à 90° : 480×640, réduit à 512.
        assertEquals(384 to 512, targetSize(640, 480, 90, LIVE_EDGE))
        assertEquals(384 to 512, targetSize(640, 480, -270, LIVE_EDGE))
    }

    @Test
    fun `la taille cible d'un paysage ne tourne rien`() {
        assertEquals(512 to 384, targetSize(640, 480, 0, LIVE_EDGE))
        // 180° ne change pas les dimensions.
        assertEquals(512 to 384, targetSize(640, 480, 180, LIVE_EDGE))
    }

    @Test
    fun `une image deja plus petite que l'edge n'est jamais agrandie`() {
        assertEquals(320 to 240, targetSize(320, 240, 0, LIVE_EDGE))
        assertEquals(240 to 320, targetSize(320, 240, 270, LIVE_EDGE))
        assertEquals(512 to 512, targetSize(512, 512, 0, LIVE_EDGE))
    }

    @Test
    fun `un edge impair garde le bord demande sans depasser`() {
        val (width, height) = targetSize(1000, 500, 0, 333)
        assertEquals(333, width)
        assertEquals(166, height)
        assertTrue("le bord impair ne doit pas être dépassé", width <= 333 && height <= 333)
        // Les proportions sont conservées à l'arrondi près.
        assertEquals(0.5, height.toDouble() / width.toDouble(), 0.01)
    }

    @Test
    fun `les angles camera sont ramenes dans le cercle`() {
        assertEquals(270, normalizeDegrees(-90))
        assertEquals(0, normalizeDegrees(360))
        assertTrue(isQuarterTurn(90))
        assertTrue(isQuarterTurn(-90))
        assertFalse(isQuarterTurn(180))
        assertFalse(isQuarterTurn(0))
    }

    @Test
    fun `les couleurs de theme sont distinctes`() {
        assertNotEquals(AppColors.primary, AppColors.danger)
        assertNotEquals(AppColors.background, AppColors.surface)
        assertTrue(MASK_ALPHA in 1..255)
    }
}
