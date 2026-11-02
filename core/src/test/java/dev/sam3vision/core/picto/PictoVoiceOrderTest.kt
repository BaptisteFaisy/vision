package dev.sam3vision.core.picto

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictoVoiceOrderTest {

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
        assertEquals(listOf("wall", "window"), hits.map { it.label })
        assertEquals(0.5f, hits[0].x + hits[0].w / 2f)
    }

    @Test
    fun `huntWord rend le mot qui vise un label arme`() {
        val labels = listOf("medics", "boîte de médicaments")
        assertEquals("medicament", PictoVoiceOrder.huntWord("médicament", labels))
        assertEquals("medicaments", PictoVoiceOrder.huntWord("les médicaments", labels))
        assertNull(PictoVoiceOrder.huntWord("clés", labels))
        assertNull(PictoVoiceOrder.huntWord("médicament", emptyList()))
        assertNull(PictoVoiceOrder.huntWord("", labels))
    }

    @Test
    fun `les phrases parlent la langue de la voix`() {
        assertEquals("Démo picto activée.", PictoVoiceText.demo(true, SpeechLanguage.Fr))
        assertEquals("Démo picto coupée.", PictoVoiceText.demo(false, SpeechLanguage.Fr))
        assertEquals("Picto demo on.", PictoVoiceText.demo(true, SpeechLanguage.En))
        assertEquals("Picto demo off.", PictoVoiceText.demo(false, SpeechLanguage.En))
        assertEquals("Cherche keys.", PictoVoiceText.findStarted("keys", SpeechLanguage.Fr))
        assertEquals("Finding keys.", PictoVoiceText.findStarted("keys", SpeechLanguage.En))
        assertEquals("Que dedans.", PictoVoiceText.refused(SpeechLanguage.Fr))
        assertEquals("Indoors only.", PictoVoiceText.refused(SpeechLanguage.En))
    }

    @Test
    fun `le trouve nomme l objet a son cadran`() {
        assertEquals("Mug at 9 o'clock.", PictoVoiceText.found("mug", 9, SpeechLanguage.En))
        assertEquals("Keys at 12 o'clock.", PictoVoiceText.found("keys", 12, SpeechLanguage.En))
        assertEquals("Tv remote at 3 o'clock.", PictoVoiceText.found("tv remote", 3, SpeechLanguage.En))
        assertEquals("Mug à 9 heures.", PictoVoiceText.found("mug", 9, SpeechLanguage.Fr))
        assertEquals("Clés à midi.", PictoVoiceText.found("clés", 12, SpeechLanguage.Fr))
        assertEquals("Mug à 1 heure.", PictoVoiceText.found("mug", 1, SpeechLanguage.Fr))
    }
}
