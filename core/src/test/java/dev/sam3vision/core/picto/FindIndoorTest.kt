package dev.sam3vision.core.picto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindIndoorTest {

    @Test
    fun `find my x vise les 10 cibles en anglais`() {
        assertEquals(FindIndoorTarget.KEYS, FindIndoorOrder.parseFind("find my keys"))
        assertEquals(FindIndoorTarget.MEDICINE_BOX, FindIndoorOrder.parseFind("find the medicine box"))
        assertEquals(FindIndoorTarget.GLASSES, FindIndoorOrder.parseFind("find my glasses"))
        assertEquals(FindIndoorTarget.GLASSES_CASE, FindIndoorOrder.parseFind("find the glasses case"))
        assertEquals(FindIndoorTarget.PHONE, FindIndoorOrder.parseFind("find my phone"))
        assertEquals(FindIndoorTarget.SHOES, FindIndoorOrder.parseFind("find my shoes"))
        assertEquals(FindIndoorTarget.DOOR, FindIndoorOrder.parseFind("find the door"))
        assertEquals(FindIndoorTarget.WALL, FindIndoorOrder.parseFind("find the wall"))
        assertEquals(FindIndoorTarget.PERSON, FindIndoorOrder.parseFind("find the person"))
        assertEquals(FindIndoorTarget.SOCKS, FindIndoorOrder.parseFind("find my socks"))
    }

    @Test
    fun `find my x vise les 10 cibles en francais`() {
        assertEquals(FindIndoorTarget.KEYS, FindIndoorOrder.parseFind("find mes clés"))
        assertEquals(FindIndoorTarget.MEDICINE_BOX, FindIndoorOrder.parseFind("find ma boîte de médicaments"))
        assertEquals(FindIndoorTarget.GLASSES, FindIndoorOrder.parseFind("find mes lunettes"))
        assertEquals(FindIndoorTarget.GLASSES_CASE, FindIndoorOrder.parseFind("find mon boîtier de lunettes"))
        assertEquals(FindIndoorTarget.PHONE, FindIndoorOrder.parseFind("find mon téléphone"))
        assertEquals(FindIndoorTarget.SHOES, FindIndoorOrder.parseFind("find mes chaussures"))
        assertEquals(FindIndoorTarget.DOOR, FindIndoorOrder.parseFind("find la porte"))
        assertEquals(FindIndoorTarget.WALL, FindIndoorOrder.parseFind("find le mur"))
        assertEquals(FindIndoorTarget.PERSON, FindIndoorOrder.parseFind("find un humain"))
        assertEquals(FindIndoorTarget.SOCKS, FindIndoorOrder.parseFind("find mes chaussettes"))
    }

    @Test
    fun `singulier pluriel locate et please sont toleres`() {
        assertEquals(FindIndoorTarget.KEYS, FindIndoorOrder.parseFind("find the key"))
        assertEquals(FindIndoorTarget.SHOES, FindIndoorOrder.parseFind("find a shoe please"))
        assertEquals(FindIndoorTarget.PHONE, FindIndoorOrder.parseFind("locate my portable"))
        assertEquals(FindIndoorTarget.MEDICINE_BOX, FindIndoorOrder.parseFind("find mes médicaments"))
        assertEquals(FindIndoorTarget.GLASSES_CASE, FindIndoorOrder.parseFind("find my étui"))
        assertEquals(FindIndoorTarget.WALL, FindIndoorOrder.parseFind("find the walls"))
    }

    @Test
    fun `le verre n'est pas des lunettes`() {
        assertNull(FindIndoorOrder.parseFind("find the glass"))
        assertTrue(FindIndoorOrder.isUnknownFind("find the glass"))
    }

    @Test
    fun `find off efface`() {
        assertEquals(FindIndoorCommand.Off, FindIndoorOrder.parse("find off"))
        assertEquals(FindIndoorCommand.Off, FindIndoorOrder.parse("Find off please"))
        assertTrue(FindIndoorOrder.isFindOff("find off"))
        assertFalse(FindIndoorOrder.isFindOff("find my keys"))
        assertFalse(FindIndoorOrder.isFindOff("locate off"))
    }

    @Test
    fun `ni verbe ni cible ni ordre`() {
        assertNull(FindIndoorOrder.parse("find"))
        assertNull(FindIndoorOrder.parse("I found my keys"))
        assertNull(FindIndoorOrder.parse("où sont mes clés"))
        assertNull(FindIndoorOrder.parse("pourquoi ?"))
        assertNull(FindIndoorOrder.parse(""))
        assertFalse(FindIndoorOrder.isUnknownFind("find"))
        assertFalse(FindIndoorOrder.isUnknownFind("I found my keys"))
    }

    @Test
    fun `find d'objet hors des 10 est inconnu pas question`() {
        assertNull(FindIndoorOrder.parse("find the elephant"))
        assertTrue(FindIndoorOrder.isUnknownFind("find the elephant"))
        assertTrue(FindIndoorOrder.isUnknownFind("find my doudou"))
    }

    @Test
    fun `parse rend start avec la cible`() {
        assertEquals(
            FindIndoorCommand.Start(FindIndoorTarget.SOCKS),
            FindIndoorOrder.parse("find my socks"),
        )
    }

    @Test
    fun `clips demarrage trouves et one-shots`() {
        val started = FindIndoorClips.started(FindIndoorTarget.KEYS)
        assertEquals("find_start_keys", started.id)
        assertEquals("Looking for keys.", started.text)
        val found = FindIndoorClips.found(FindIndoorTarget.GLASSES_CASE, 3)
        assertEquals("find_found_glasses_case_3", found.id)
        assertEquals("Glasses case at 3 o'clock.", found.text)
        assertEquals("find_off", FindIndoorClips.OFF.id)
        assertEquals("Search cleared.", FindIndoorClips.OFF.text)
        assertEquals("find_refused", FindIndoorClips.REFUSED.id)
        assertEquals("Find works indoors only.", FindIndoorClips.REFUSED.text)
        assertEquals("find_unknown", FindIndoorClips.UNKNOWN.id)
        assertEquals("I cannot find that.", FindIndoorClips.UNKNOWN.text)
        assertEquals("indoor_door_9", FindIndoorClips.doorAt(9).id)
        assertEquals("Door at 9 o'clock.", FindIndoorClips.doorAt(9).text)
        assertEquals("indoor_wall_12", FindIndoorClips.wallAt(12).id)
        assertEquals("Wall at 12 o'clock.", FindIndoorClips.wallAt(12).text)
    }

    @Test
    fun `clips anglais uniquement sans accent`() {
        val clips = FindIndoorTarget.entries.flatMap { target ->
            listOf(FindIndoorClips.started(target)) + listOf(9, 10, 11, 12, 1, 2, 3).map { FindIndoorClips.found(target, it) }
        } + listOf(FindIndoorClips.OFF, FindIndoorClips.REFUSED, FindIndoorClips.UNKNOWN) +
            listOf(9, 12).flatMap { listOf(FindIndoorClips.doorAt(it), FindIndoorClips.wallAt(it)) }
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
        val find = FindIndoorPriority.Candidate(FindIndoorLevels.FIND, "Keys at 9 o'clock.", "find_found_keys_9")
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
