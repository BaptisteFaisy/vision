package dev.sam3vision.core.picto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindIndoorTest {

    @Test
    fun `fromSpoken vise les 10 noms parles anglais`() {
        assertEquals(FindIndoorTarget.KEYS, FindIndoorTarget.fromSpoken("keys"))
        assertEquals(FindIndoorTarget.MEDICINE_BOX, FindIndoorTarget.fromSpoken("medicine box"))
        assertEquals(FindIndoorTarget.GLASSES, FindIndoorTarget.fromSpoken("glasses"))
        assertEquals(FindIndoorTarget.GLASSES_CASE, FindIndoorTarget.fromSpoken("glasses case"))
        assertEquals(FindIndoorTarget.PHONE, FindIndoorTarget.fromSpoken("phone"))
        assertEquals(FindIndoorTarget.SHOES, FindIndoorTarget.fromSpoken("shoes"))
        assertEquals(FindIndoorTarget.DOOR, FindIndoorTarget.fromSpoken("door"))
        assertEquals(FindIndoorTarget.WALL, FindIndoorTarget.fromSpoken("wall"))
        assertEquals(FindIndoorTarget.PERSON, FindIndoorTarget.fromSpoken("person"))
        assertEquals(FindIndoorTarget.SOCKS, FindIndoorTarget.fromSpoken("socks"))
    }

    @Test
    fun `fromSpoken vise les 10 noms parles francais`() {
        assertEquals(FindIndoorTarget.KEYS, FindIndoorTarget.fromSpoken("clés"))
        assertEquals(FindIndoorTarget.MEDICINE_BOX, FindIndoorTarget.fromSpoken("boîte de médicaments"))
        assertEquals(FindIndoorTarget.GLASSES, FindIndoorTarget.fromSpoken("lunettes"))
        assertEquals(FindIndoorTarget.GLASSES_CASE, FindIndoorTarget.fromSpoken("boîtier de lunettes"))
        assertEquals(FindIndoorTarget.PHONE, FindIndoorTarget.fromSpoken("téléphone"))
        assertEquals(FindIndoorTarget.SHOES, FindIndoorTarget.fromSpoken("chaussures"))
        assertEquals(FindIndoorTarget.DOOR, FindIndoorTarget.fromSpoken("porte"))
        assertEquals(FindIndoorTarget.WALL, FindIndoorTarget.fromSpoken("mur"))
        assertEquals(FindIndoorTarget.PERSON, FindIndoorTarget.fromSpoken("personne"))
        assertEquals(FindIndoorTarget.SOCKS, FindIndoorTarget.fromSpoken("chaussettes"))
    }

    @Test
    fun `fromSpoken ignore la casse et rend null pour l inconnu`() {
        assertEquals(FindIndoorTarget.KEYS, FindIndoorTarget.fromSpoken("Keys"))
        assertEquals(FindIndoorTarget.WALL, FindIndoorTarget.fromSpoken("MURS"))
        assertNull(FindIndoorTarget.fromSpoken("glass"))
        assertNull(FindIndoorTarget.fromSpoken("homme"))
        assertNull(FindIndoorTarget.fromSpoken("doudou"))
        assertNull(FindIndoorTarget.fromSpoken(""))
    }

    @Test
    fun `le verre n'est pas des lunettes`() {
        assertNull(FindIndoorTarget.fromSpoken("glass"))
        assertEquals(FindIndoorTarget.GLASSES, FindIndoorTarget.fromSpoken("glasses"))
    }

    @Test
    fun `clips porte et murs`() {
        assertEquals("indoor_door_9", FindIndoorClips.doorAt(9).id)
        assertEquals("Door at 9 o'clock.", FindIndoorClips.doorAt(9).text)
        assertEquals("indoor_wall_12", FindIndoorClips.wallAt(12).id)
        assertEquals("Wall at 12 o'clock.", FindIndoorClips.wallAt(12).text)
    }

    @Test
    fun `clips anglais uniquement sans accent`() {
        val clips = listOf(9, 10, 11, 12, 1, 2, 3)
            .flatMap { listOf(FindIndoorClips.doorAt(it), FindIndoorClips.wallAt(it)) }
        assertTrue(clips.isNotEmpty())
        clips.forEach { clip ->
            assertTrue("id " + clip.id, clip.id.matches(Regex("[a-z0-9_]+")))
            assertTrue("texte " + clip.text, clip.text.all { it.code in 32..126 })
        }
    }

    @Test
    fun `niveaux dedans ordonnes cherche puis user puis humain puis chat puis portes`() {
        assertEquals(listOf(0, 1, 2, 9, 13), FindIndoorLevels.ORDER)
        assertEquals(0, FindIndoorLevels.FIND)
        assertEquals(13, FindIndoorLevels.DOOR_WALL)
        assertTrue(FindIndoorLevels.ORDER == FindIndoorLevels.ORDER.sorted())
    }

    @Test
    fun `cles anti-repetition stables et durees`() {
        assertEquals("find-found", FindIndoorRepeat.FIND_FOUND_KEY)
        assertEquals(2_000L, FindIndoorRepeat.FIND_FOUND_MS)
        assertEquals("door-wall", FindIndoorRepeat.DOOR_WALL_KEY)
        assertEquals(4_000L, FindIndoorRepeat.DOOR_WALL_MS)
    }

    @Test
    fun `arbitre le plus petit gagne stable et silence`() {
        val find = FindIndoorPriority.Candidate(FindIndoorLevels.FIND, "Mug at 9 o'clock.", null)
        val human = FindIndoorPriority.Candidate(FindIndoorLevels.HUMAN, "Personne à 9 heures.", null)
        val chat = FindIndoorPriority.Candidate(FindIndoorLevels.CHAT, "Réponse.", null)
        val doors = FindIndoorPriority.Candidate(FindIndoorLevels.DOOR_WALL, "Door at 3 o'clock.", "indoor_door_3")
        assertEquals(find, FindIndoorPriority.decide(listOf(chat, doors, human, find)))
        assertEquals(human, FindIndoorPriority.decide(listOf(chat, doors, human)))
        assertEquals(chat, FindIndoorPriority.decide(listOf(doors, chat)))
        assertEquals(doors, FindIndoorPriority.decide(listOf(doors)))
        assertNull(FindIndoorPriority.decide(emptyList()))
        val tie = FindIndoorPriority.Candidate(FindIndoorLevels.HUMAN, "second", null)
        assertEquals(human, FindIndoorPriority.decide(listOf(human, tie)))
    }

    @Test
    fun `clef et clefs visent les cles`() {
        assertEquals(FindIndoorTarget.KEYS, FindIndoorTarget.fromSpoken("clef"))
        assertEquals(FindIndoorTarget.KEYS, FindIndoorTarget.fromSpoken("clefs"))
    }
}
