package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModesTest {

    @Test
    fun `trois presets, sept concepts chacun, dans l ordre des pastilles`() {
        assertEquals(listOf("essentiels", "sante", "cuisine"), HomeModes.ALL.map { it.id })
        assertEquals(listOf("Essentials", "Health", "Kitchen & living room"), HomeModes.ALL.map { it.label })
        HomeModes.ALL.forEach { assertEquals(7, it.prompts.size) }
    }

    @Test
    fun `le bouton maison ouvre les essentiels`() {
        assertEquals(HomeModes.ESSENTIELS, HomeModes.DEFAULT)
        assertEquals(HomeModes.ESSENTIELS, HomeModes.byId("essentiels"))
    }

    @Test
    fun `chaque preset passe normalize tel quel`() {
        HomeModes.ALL.forEach { preset ->
            assertEquals(preset.prompts, LivePrompts.normalize(preset.prompts))
        }
    }

    @Test
    fun `les 21 concepts sont distincts et sous les plafonds`() {
        val all = HomeModes.ALL.flatMap { it.prompts }
        assertEquals(21, all.size)
        assertEquals(21, all.distinct().size)
        assertTrue(all.all { it.length <= LivePrompts.MAX_LENGTH })
        assertTrue(all.none { it.contains(',') || it.contains(';') })
    }

    @Test
    fun `byId inconnu ou nul vaut null`() {
        assertNull(HomeModes.byId("grenier"))
        assertNull(HomeModes.byId(null))
        assertNotNull(HomeModes.byId("sante"))
        assertNotNull(HomeModes.byId("cuisine"))
    }

    @Test
    fun `activeId reconnait les trois presets et rien d autre`() {
        assertEquals("essentiels", HomeModes.activeId(HomeModes.ESSENTIELS.prompts))
        assertEquals("sante", HomeModes.activeId(HomeModes.SANTE.prompts))
        assertEquals("cuisine", HomeModes.activeId(HomeModes.CUISINE.prompts))
        assertNull(HomeModes.activeId(LivePrompts.default))
        assertNull(HomeModes.activeId(listOf("keys")))
        assertNull(HomeModes.activeId(emptyList()))
    }

    @Test
    fun `display rend l anglais tel quel rogne`() {
        assertEquals("keys", HomeModes.display("keys"))
        assertEquals("medicine box", HomeModes.display("medicine box"))
        assertEquals("drinking glass", HomeModes.display("drinking glass"))
        assertEquals("Light Switch", HomeModes.display("  Light Switch "))
    }

    @Test
    fun `display laisse intacts les labels non vides`() {
        assertEquals("person", HomeModes.display("person"))
        assertEquals("electric scooter", HomeModes.display("electric scooter"))
        assertEquals("", HomeModes.display(""))
    }

    @Test
    fun `conceptForWord traduit le francais parle, casse et accents neutres`() {
        assertEquals("keys", HomeModes.conceptForWord("clés"))
        assertEquals("keys", HomeModes.conceptForWord("  CLÉS ! "))
        assertEquals("tv remote", HomeModes.conceptForWord("télécommande"))
        assertEquals("drinking glass", HomeModes.conceptForWord("verre"))
        assertEquals("light switch", HomeModes.conceptForWord("Interrupteur"))
    }

    @Test
    fun `conceptForWord accepte le singulier et l anglais`() {
        assertEquals("keys", HomeModes.conceptForWord("clé"))
        assertEquals("keys", HomeModes.conceptForWord("key"))
        assertEquals("keys", HomeModes.conceptForWord("keys"))
        assertEquals("bottle", HomeModes.conceptForWord("bouteille"))
        assertEquals("scissors", HomeModes.conceptForWord("ciseaux"))
    }

    @Test
    fun `conceptForWord vise les libelles composes par alias`() {
        assertEquals("medicine box", HomeModes.conceptForWord("médicaments"))
        assertEquals("tissue box", HomeModes.conceptForWord("mouchoirs"))
        assertEquals("glasses case", HomeModes.conceptForWord("étui"))
        assertEquals("glasses", HomeModes.conceptForWord("lunettes"))
    }

    @Test
    fun `conceptForWord refuse l inconnu le vide et les phrases`() {
        assertNull(HomeModes.conceptForWord("doudou"))
        assertNull(HomeModes.conceptForWord("pourquoi"))
        assertNull(HomeModes.conceptForWord(""))
        assertNull(HomeModes.conceptForWord("   "))
        assertNull(HomeModes.conceptForWord("boîte de médicaments"))
        assertNull(HomeModes.conceptForWord("les clés"))
    }

    @Test
    fun `isHome couvre les presets et la chasse par mot`() {
        assertTrue(HomeModes.isHome(HomeModes.ESSENTIELS.prompts))
        assertTrue(HomeModes.isHome(HomeModes.SANTE.prompts))
        assertTrue(HomeModes.isHome(HomeModes.CUISINE.prompts))
        assertTrue(HomeModes.isHome(listOf("keys")))
        assertTrue(HomeModes.isHome(listOf("keys", "glasses")))
        assertTrue(HomeModes.isHome(listOf("Keys")))
    }

    @Test
    fun `isHome refuse la rue la traversee et le vide`() {
        assertFalse(HomeModes.isHome(LivePrompts.default))
        assertFalse(HomeModes.isHome(listOf("person")))
        assertFalse(HomeModes.isHome(listOf("keys", "person")))
        assertFalse(HomeModes.isHome(emptyList()))
    }

    @Test
    fun `voiceConcept ne chasse qu un mot maison sam allume`() {
        val maison = HomeModes.ESSENTIELS.prompts
        assertEquals("keys", HomeModes.voiceConcept("clés", maison, true))
        assertEquals("keys", HomeModes.voiceConcept("CLÉS ?", listOf("keys"), true))
        assertNull(HomeModes.voiceConcept("où sont les clés ?", maison, true))
        assertNull(HomeModes.voiceConcept("doudou", maison, true))
        assertNull(HomeModes.voiceConcept("clés", LivePrompts.default, true))
        assertNull(HomeModes.voiceConcept("clés", maison, false))
        assertNull(HomeModes.voiceConcept("   ", maison, true))
    }
}
