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
    fun `create a picto for my keys rend keys`() {
        assertEquals("keys", PictoVoiceOrder.parse("create a picto for my keys"))
        assertEquals("keys", PictoVoiceOrder.parse("create a pictogram of my keys"))
        assertEquals("phone", PictoVoiceOrder.parse("make a picto for my phone"))
        assertEquals("tv remote", PictoVoiceOrder.parse("create a picto for the tv remote"))
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
    fun `find the x rend le label sans article`() {
        assertEquals("keys", PictoVoiceOrder.parseFind("Find the keys"))
        assertEquals("keys", PictoVoiceOrder.parseFind("find keys"))
        assertEquals("phone", PictoVoiceOrder.parseFind("Locate my phone"))
        assertEquals("tv remote", PictoVoiceOrder.parseFind("find the tv remote"))
        assertEquals("keys", PictoVoiceOrder.parseFind("Find the keys please"))
    }

    @Test
    fun `find sans objet ou verbe non initial n est pas un ordre`() {
        assertNull(PictoVoiceOrder.parseFind("find"))
        assertNull(PictoVoiceOrder.parseFind("find the"))
        assertNull(PictoVoiceOrder.parseFind("find the please"))
        assertNull(PictoVoiceOrder.parseFind("I found my keys"))
        assertNull(PictoVoiceOrder.parseFind("où sont les clés ?"))
        assertNull(PictoVoiceOrder.parseFind("fais un pictogramme de ces médics"))
        assertNull(PictoVoiceOrder.parseFind(""))
    }

    @Test
    fun `where is are rend le label maison ou possede`() {
        assertEquals("keys", PictoVoiceOrder.parseWhere("Where are my keys"))
        assertEquals("keys", PictoVoiceOrder.parseWhere("Where are the keys"))
        assertEquals("key", PictoVoiceOrder.parseWhere("Where is my key"))
        assertEquals("phone", PictoVoiceOrder.parseWhere("Where's my phone?"))
        assertEquals("tv remote", PictoVoiceOrder.parseWhere("Where is the tv remote"))
        assertEquals("bag", PictoVoiceOrder.parseWhere("Where is my bag"))
        assertEquals("keys", PictoVoiceOrder.parseWhere("Where are my keys please"))
    }

    @Test
    fun `where hors maison sans my reste une question llm`() {
        assertNull(PictoVoiceOrder.parseWhere("Where is the exit?"))
        assertNull(PictoVoiceOrder.parseWhere("Where is Gare de Lyon?"))
        assertNull(PictoVoiceOrder.parseWhere("Where am I?"))
        assertNull(PictoVoiceOrder.parseWhere("Where is it?"))
        assertNull(PictoVoiceOrder.parseWhere("Where are you?"))
        assertNull(PictoVoiceOrder.parseWhere("Where are mine"))
        assertNull(PictoVoiceOrder.parseWhere("Where is"))
        assertNull(PictoVoiceOrder.parseWhere("Where are my"))
        assertNull(PictoVoiceOrder.parseWhere("I wonder where my keys are"))
        assertNull(PictoVoiceOrder.parseWhere("où sont mes clés ?"))
        assertNull(PictoVoiceOrder.parseWhere(""))
    }

    @Test
    fun `ou sont mes cles rend le concept maison anglais`() {
        assertEquals("keys", PictoVoiceOrder.parseOuSont("où sont mes clés ?"))
        assertEquals("phone", PictoVoiceOrder.parseOuSont("où est mon téléphone"))
        assertEquals("keys", PictoVoiceOrder.parseOuSont("Où sont les clés"))
        assertEquals("keys", PictoVoiceOrder.parseOuSont("où est ma clé"))
        assertEquals("glasses", PictoVoiceOrder.parseOuSont("où sont les lunettes"))
    }

    @Test
    fun `ou imparfait et please final rendent le concept`() {
        assertEquals("keys", PictoVoiceOrder.parseOuSont("où étaient mes clés"))
        assertEquals("phone", PictoVoiceOrder.parseOuSont("où était mon téléphone"))
        assertEquals("keys", PictoVoiceOrder.parseOuSont("où sont mes clés please"))
        assertEquals("keys", PictoVoiceOrder.parseOuSont("où est ma clef"))
        assertEquals("keys", PictoVoiceOrder.parseOuSont("où sont les clefs"))
    }

    @Test
    fun `ou sont refuse l anglais l inconnu le compose et le vide`() {
        assertNull(PictoVoiceOrder.parseOuSont("Where are my keys"))
        assertNull(PictoVoiceOrder.parseOuSont("où est mon doudou"))
        assertNull(PictoVoiceOrder.parseOuSont("où est la boîte de médicaments"))
        assertNull(PictoVoiceOrder.parseOuSont("où est"))
        assertNull(PictoVoiceOrder.parseOuSont("où sont mes"))
        assertNull(PictoVoiceOrder.parseOuSont("les clés sont où ?"))
        assertNull(PictoVoiceOrder.parseOuSont(""))
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
        assertEquals("Je cherche keys.", PictoVoiceText.findStarted("keys", SpeechLanguage.Fr))
        assertEquals("Looking for keys.", PictoVoiceText.findStarted("keys", SpeechLanguage.En))
    }
}
