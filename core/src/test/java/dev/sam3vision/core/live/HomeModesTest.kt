package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModesTest {

    @Test
    fun `trois presets, sept concepts chacun, dans l ordre des pastilles`() {
        assertEquals(listOf("essentiels", "sante", "cuisine"), HomeModes.ALL.map { it.id })
        assertEquals(listOf("Essentiels", "Santé", "Cuisine & salon"), HomeModes.ALL.map { it.labelFr })
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
    fun `french traduit les 21 concepts maison`() {
        assertEquals("clés", HomeModes.french("keys"))
        assertEquals("boîte de médicaments", HomeModes.french("medicine box"))
        assertEquals("verre", HomeModes.french("drinking glass"))
        assertEquals("interrupteur", HomeModes.french("  Light Switch "))
    }

    @Test
    fun `french laisse intacts les labels hors maison`() {
        assertEquals("person", HomeModes.french("person"))
        assertEquals("electric scooter", HomeModes.french("electric scooter"))
        assertEquals("", HomeModes.french(""))
    }
}
