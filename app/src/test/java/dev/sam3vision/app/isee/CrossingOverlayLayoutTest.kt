package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.traverse.TraversePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mise en place du feu sur la vidéo : couleurs, étiquettes et géométrie.
 * Tout est pur, donc tout se vérifie sans appareil — c'est le seul moyen de
 * savoir où le cadre tombe quand l'image et la carte n'ont pas le même rapport.
 */
class CrossingOverlayLayoutTest {

    /** Compare un cadre au millième de pixel : le flottant ne doit pas faire le juge. */
    private fun assertMarker(marker: LightMarker?, left: Float, top: Float, right: Float, bottom: Float) {
        assertNotNull(marker)
        assertEquals(left, marker!!.left, 0.001f)
        assertEquals(top, marker.top, 0.001f)
        assertEquals(right, marker.right, 0.001f)
        assertEquals(bottom, marker.bottom, 0.001f)
    }

    @Test
    fun `mode coupe ne dessine rien`() {
        assertEquals("", crossingTag(false, CrossingState.Green))
        assertEquals(CROSSING_ACCENT_OFF, crossingAccent(false, null))
        assertEquals(CROSSING_ACCENT_OFF, crossingAccent(false, CrossingState.Red))
    }

    @Test
    fun `chaque etat a sa couleur et son etiquette, binaire`() {
        assertEquals("GREEN", crossingTag(true, CrossingState.Green))
        assertEquals("RED", crossingTag(true, CrossingState.Red))
        assertEquals("RED", crossingTag(true, null))
        assertEquals(CROSSING_ACCENT_GREEN, crossingAccent(true, CrossingState.Green))
        assertEquals(CROSSING_ACCENT_RED, crossingAccent(true, CrossingState.Red))
        assertEquals(CROSSING_ACCENT_RED, crossingAccent(true, null))
    }

    @Test
    fun `l etiquette de la video dit la meme chose que la pastille de carte`() {
        // Deux surfaces, un seul vocabulaire : si l'une dérive, l'utilisateur
        // lit deux mots différents pour le même feu.
        for (state in listOf(CrossingState.Green, CrossingState.Red)) {
            val chip = crossingButtonLabel(true, state)
            assertTrue("pastille « $chip » vs étiquette « ${crossingTag(true, state)} »",
                chip.endsWith(crossingTag(true, state)))
        }
        assertEquals("Crossing: RED", crossingButtonLabel(true, null))
        assertEquals("RED", crossingTag(true, null))
    }

    @Test
    fun `sans boite ou boite invalide rien n est dessine`() {
        assertNull(lightMarker(null, 100, 100, 400f, 400f))
        assertNull(lightMarker(listOf(0.1f, 0.2f, 0.3f), 100, 100, 400f, 400f))
        assertNull(lightMarker(listOf(0.1f, 0.2f, 0.3f, 0.4f), 0, 100, 400f, 400f))
        assertNull(lightMarker(listOf(0.1f, 0.2f, 0.3f, 0.4f), 100, 100, 0f, 400f))
    }

    @Test
    fun `une boite plus petite qu un point ne designe rien`() {
        // 2 x 2 px : un cadre de cette taille ne se distingue pas d'une poussière.
        assertNull(lightMarker(listOf(0.10f, 0.10f, 0.12f, 0.12f), 100, 100, 100f, 100f))
    }

    @Test
    fun `cadre carre dans une carte carree tombe au pixel pres`() {
        // Comparaison au millième : 0.6f * 100f vaut 60.000004 en flottant, et
        // une égalité exacte sur des données de test ferait échouer le test pour
        // une raison qui n'a rien à voir avec le cadrage.
        assertMarker(lightMarker(listOf(0.1f, 0.2f, 0.5f, 0.6f), 100, 100, 100f, 100f), 10f, 20f, 50f, 60f)
    }

    @Test
    fun `image panoramique recadree en carre garde le feu sous ses pixels`() {
        // C'est le cas réel : la frame SAM fait 200x100, la carte est carrée, l'aperçu
        // est en ContentScale.Crop — même transformation que les pastilles de distance.
        val marker = lightMarker(listOf(0.25f, 0.25f, 0.75f, 0.75f), 200, 100, 400f, 400f)
        assertMarker(marker, 0f, 100f, 400f, 300f)
    }

    @Test
    fun `une boite qui deborde est ramenee dans la carte`() {
        val marker = lightMarker(listOf(-0.2f, 0.1f, 0.4f, 0.5f), 100, 100, 100f, 100f)
        assertNotNull(marker)
        assertEquals(0f, marker!!.left, 0.001f)
        assertEquals(40f, marker.right, 0.001f)
    }

    @Test
    fun `etiquette au dessus quand il y a la place`() {
        val r = tagRect(LightMarker(0f, 100f, 400f, 300f), tagWidth = 50f, tagHeight = 20f, boxWidth = 400f, boxHeight = 400f)
        assertMarker(r, 0f, 76f, 50f, 96f)
    }

    @Test
    fun `etiquette sous le cadre quand le feu touche le haut`() {
        val r = tagRect(LightMarker(0f, 10f, 400f, 300f), tagWidth = 50f, tagHeight = 20f, boxWidth = 400f, boxHeight = 400f)
        assertMarker(r, 0f, 304f, 50f, 324f)
    }

    @Test
    fun `etiquette ramenee a droite et en bas`() {
        val r = tagRect(LightMarker(380f, 390f, 400f, 400f), tagWidth = 100f, tagHeight = 20f, boxWidth = 400f, boxHeight = 400f)
        assertEquals(300f, r.left, 0.001f)
        assertEquals(400f, r.right, 0.001f)
        assertEquals(366f, r.top, 0.001f)
    }

    @Test
    fun `seul TRAVERSER autorise - le reste attend`() {
        assertEquals("may cross", traverseTag(true, TraversePhase.TRAVERSER))
        for (phase in listOf(TraversePhase.PRET, TraversePhase.ATTENDRE, TraversePhase.ALERTE, null)) {
            assertEquals("may not cross", traverseTag(true, phase))
        }
        assertEquals("", traverseTag(false, TraversePhase.TRAVERSER))
    }

    @Test
    fun `bandeau vert pour partir rouge pour attendre`() {
        assertEquals(CROSSING_ACCENT_GREEN, traverseAccent(true, TraversePhase.TRAVERSER))
        for (phase in listOf(TraversePhase.PRET, TraversePhase.ATTENDRE, TraversePhase.ALERTE, null)) {
            assertEquals(CROSSING_ACCENT_RED, traverseAccent(true, phase))
        }
        assertEquals(CROSSING_ACCENT_OFF, traverseAccent(false, TraversePhase.TRAVERSER))
    }

    @Test
    fun `bandeau centre en haut sous la puce`() {
        // Carte 400x400, bandeau 200x30, marge haute 64 : centré (x=100), top=64.
        val r = verdictRect(tagWidth = 200f, tagHeight = 30f, boxWidth = 400f, boxHeight = 400f, topMarginPx = 64f)
        assertMarker(r, 100f, 64f, 300f, 94f)
    }

    @Test
    fun `bandeau trop large colle a gauche`() {
        val r = verdictRect(tagWidth = 500f, tagHeight = 30f, boxWidth = 400f, boxHeight = 400f, topMarginPx = 64f)
        assertEquals(0f, r.left, 0.001f)
        assertEquals(64f, r.top, 0.001f)
    }

    @Test
    fun `bandeau trop haut colle en haut`() {
        val r = verdictRect(tagWidth = 100f, tagHeight = 500f, boxWidth = 400f, boxHeight = 400f, topMarginPx = 64f)
        assertEquals(150f, r.left, 0.001f)
        assertEquals(0f, r.top, 0.001f)
    }
}
