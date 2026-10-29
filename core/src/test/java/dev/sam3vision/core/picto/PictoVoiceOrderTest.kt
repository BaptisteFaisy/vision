package dev.sam3vision.core.picto

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictoVoiceOrderTest {

    @Test
    fun `fais un pictogramme de x rend le label sans articles`() {
        assertEquals("medics", PictoVoiceOrder.parse("fais un pictogramme de ces médics"))
        assertEquals("tasse", PictoVoiceOrder.parse("Fais un pictogramme de la tasse !"))
        assertEquals("cles", PictoVoiceOrder.parse("crée un picto de mes clés"))
        assertEquals("boite de medicaments", PictoVoiceOrder.parse("dessine le pictogramme de la boîte de médicaments"))
    }

    @Test
    fun `pictogramme de ca rend vide, objet filme sans nom`() {
        assertEquals("", PictoVoiceOrder.parse("fais un pictogramme de ça"))
        assertEquals("", PictoVoiceOrder.parse("fais-moi un picto de cela"))
    }

    @Test
    fun `sans verbe ou sans picto ce n est pas un ordre`() {
        assertNull(PictoVoiceOrder.parse("c'est quoi ce pictogramme ?"))
        assertNull(PictoVoiceOrder.parse("dessine-moi un mouton"))
        assertNull(PictoVoiceOrder.parse("clés"))
        assertNull(PictoVoiceOrder.parse("où sont les clés ?"))
        assertNull(PictoVoiceOrder.parse(""))
    }

    @Test
    fun `le mot vise le repere - egalite pluriel prefixe`() {
        assertTrue(PictoVoiceOrder.matches("médics", "medics"))
        assertTrue(PictoVoiceOrder.matches("médicament", "medics"))
        assertTrue(PictoVoiceOrder.matches("médicaments", "boîte de médicaments"))
        assertTrue(PictoVoiceOrder.matches("clé", "clés"))
        assertTrue(PictoVoiceOrder.matches("tasse", "tasse"))
    }

    @Test
    fun `le mot ne vise ni l inconnu ni le vide`() {
        assertFalse(PictoVoiceOrder.matches("livre", "lunettes"))
        assertFalse(PictoVoiceOrder.matches("clés", ""))
        assertFalse(PictoVoiceOrder.matches("", "clés"))
        assertFalse(PictoVoiceOrder.matches("les médicaments", "medics"))
    }

    @Test
    fun `demo picto bascule, le reste non`() {
        assertTrue(PictoVoiceOrder.isDemoToggle("démo picto"))
        assertTrue(PictoVoiceOrder.isDemoToggle("active la démo pictogramme"))
        assertFalse(PictoVoiceOrder.isDemoToggle("coupe la démo"))
        assertFalse(PictoVoiceOrder.isDemoToggle("fais un pictogramme de ces médics"))
        assertFalse(PictoVoiceOrder.isDemoToggle("démo"))
        assertFalse(PictoVoiceOrder.isDemoToggle(""))
    }

    @Test
    fun `la demo montre un mur et une fenetre fixes`() {
        val hits = PictoVoiceOrder.demoHits()
        assertEquals(listOf("mur", "fenêtre"), hits.map { it.label })
        assertEquals(0.5f, hits[0].x + hits[0].w / 2f)
    }

    @Test
    fun `les phrases parlent la langue de la voix`() {
        assertEquals("Je crée le pictogramme.", PictoVoiceText.started(SpeechLanguage.Fr))
        assertEquals(
            "Pictogramme medics prêt. Dis son nom pour être guidé.",
            PictoVoiceText.created("medics", SpeechLanguage.Fr),
        )
        assertEquals("Création du pictogramme impossible.", PictoVoiceText.failed(SpeechLanguage.Fr))
        assertEquals("Création déjà en cours.", PictoVoiceText.busy(SpeechLanguage.Fr))
        assertEquals("Creating the pictogram.", PictoVoiceText.started(SpeechLanguage.En))
        assertEquals(
            "Pictogram medics ready. Say its name for guidance.",
            PictoVoiceText.created("medics", SpeechLanguage.En),
        )
        assertEquals("Pictogramme prêt.", PictoVoiceText.createdUnnamed(SpeechLanguage.Fr))
        assertEquals("Pictogram ready.", PictoVoiceText.createdUnnamed(SpeechLanguage.En))
        assertEquals("Démo picto activée.", PictoVoiceText.demo(true, SpeechLanguage.Fr))
        assertEquals("Démo picto coupée.", PictoVoiceText.demo(false, SpeechLanguage.Fr))
        assertEquals("Picto demo on.", PictoVoiceText.demo(true, SpeechLanguage.En))
    }
}
