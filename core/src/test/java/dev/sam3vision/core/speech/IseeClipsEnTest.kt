package dev.sam3vision.core.speech

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clips anglais SPEC v4 : le miroir Kotlin colle au catalogue JSON au
 * caractère près, couvre toute la spec (niveaux, ordres, objets, nombres),
 * et respecte le format `res/raw` (`vp_<id>_en.mp3`).
 */
class IseeClipsEnTest {

    @Test
    fun `le registre couvre les 70 nouveaux clips et les 15 réutilisés`() {
        assertEquals(85, IseeClipsEn.all.size)
    }

    @Test
    fun `chaque entrée du catalogue JSON est dans le registre au caractère près`() {
        val catalog = JSONObject(catalogFile().readText())
        val entries = catalog.getJSONArray("clips") + catalog.getJSONArray("reuse")
        assertEquals(85, entries.size)
        for ((id, text) in entries) {
            assertEquals("clip $id", text, IseeClipsEn.all[id])
        }
    }

    @Test
    fun `ids au format res raw sans collision de noms de fichiers`() {
        val names = IseeClipsEn.all.keys.map(IseeClipsEn::fileName)
        assertEquals(names.size, names.toSet().size)
        for ((id, _) in IseeClipsEn.all) {
            assertTrue("id $id", id.matches(Regex("[a-z0-9_]+")))
        }
    }

    @Test
    fun `textes anglais uniquement, ponctués sauf le préfixe composé`() {
        for ((id, text) in IseeClipsEn.all) {
            assertTrue("texte $id vide", text.isNotBlank())
            assertTrue("texte $id non anglais : $text", text.all { it.code < 128 })
            if (id == IseeClipsEn.FIND_LOOKING_FOR) {
                assertEquals("Looking for", text)
            } else {
                assertTrue("texte $id sans point : $text", text.endsWith("."))
            }
        }
    }

    @Test
    fun `dehors niveaux 0 a 8, verdicts et repli présents`() {
        val all = IseeClipsEn.all
        assertEquals("Car.", all[IseeClipsEn.CAR])
        assertEquals("Cars.", all[IseeClipsEn.CARS])
        assertEquals("Person.", all[IseeClipsEn.HUMAN_PERSON])
        assertEquals("Stop.", all[IseeClipsEn.HUMAN_IMMINENT])
        assertEquals("Fence.", all[IseeClipsEn.FENCE])
        assertEquals("Wall.", all[IseeClipsEn.WALL])
        assertEquals("Barrier.", all[IseeClipsEn.BARRIER])
        assertEquals("Curb up.", all[IseeClipsEn.CURB_UP])
        assertEquals("Curb down.", all[IseeClipsEn.CURB_DOWN])
        assertEquals("Pole.", all[IseeClipsEn.POLE])
        assertEquals("Street lamp.", all[IseeClipsEn.LAMP])
        assertEquals("Sign.", all[IseeClipsEn.SIGN])
        assertEquals("Animal.", all[IseeClipsEn.ANIMAL])
        assertEquals("Trash can.", all[IseeClipsEn.BIN])
        assertEquals("Keepsake.", all[IseeClipsEn.KEEPSAKE])
        assertEquals("Aligned with the crosswalk.", all[IseeClipsEn.CROSSWALK_ALIGNED])
        assertEquals("On the crosswalk.", all[IseeClipsEn.CROSSWALK_ARRIVED])
        assertEquals("Sidewalk ahead.", all[IseeClipsEn.SIDEWALK_AHEAD])
        assertEquals("Cross.", all[IseeClipsEn.VERDICT_CROSS])
        assertEquals("Don't cross.", all[IseeClipsEn.VERDICT_WAIT])
        assertEquals("Left.", all[IseeClipsEn.GO_LEFT])
        assertEquals("Right.", all[IseeClipsEn.GO_RIGHT])
        assertEquals("Straight.", all[IseeClipsEn.GO_STRAIGHT])
    }

    @Test
    fun `confirmations des 10 ordres présentes`() {
        val all = IseeClipsEn.all
        assertEquals("Outdoor mode on.", all[IseeClipsEn.OUTDOOR_ON])
        assertEquals("Indoor mode on.", all[IseeClipsEn.INDOOR_ON])
        assertEquals("Crossing mode on.", all[IseeClipsEn.CROSSING_ON])
        assertEquals("Crossing mode off.", all[IseeClipsEn.CROSSING_OFF])
        assertEquals("Looking for", all[IseeClipsEn.FIND_LOOKING_FOR])
        assertEquals("Search stopped.", all[IseeClipsEn.FIND_STOPPED])
        assertEquals("Guidance started.", all[IseeClipsEn.GPS_STARTED])
        assertEquals("Guidance stopped.", all[IseeClipsEn.GPS_OFF])
        assertEquals("Guidance unavailable.", all[IseeClipsEn.GPS_UNAVAILABLE])
        assertEquals("Glasses on.", all[IseeClipsEn.GLASSES_ON])
        assertEquals("Glasses off.", all[IseeClipsEn.GLASSES_OFF])
    }

    @Test
    fun `dedans 10 objets dans l ordre de la spec plus trouvé`() {
        assertEquals(
            listOf(
                IseeClipsEn.KEYS, IseeClipsEn.MEDBOX, IseeClipsEn.GLASSES,
                IseeClipsEn.GLASSES_CASE, IseeClipsEn.PHONE, IseeClipsEn.SHOES,
                IseeClipsEn.DOOR, IseeClipsEn.WALLS, IseeClipsEn.HUMAN_PERSON,
                IseeClipsEn.SOCKS,
            ),
            IseeClipsEn.indoorObjects,
        )
        assertEquals("Found.", IseeClipsEn.all[IseeClipsEn.FOUND])
    }

    @Test
    fun `jalons escaliers 4 2 1 dans les deux sens`() {
        assertEquals(IseeClipsEn.STAIRS_UP_4, IseeClipsEn.stairsUp(4))
        assertEquals(IseeClipsEn.STAIRS_UP_2, IseeClipsEn.stairsUp(2))
        assertEquals(IseeClipsEn.STAIRS_UP_1, IseeClipsEn.stairsUp(1))
        assertEquals(IseeClipsEn.STAIRS_DOWN_4, IseeClipsEn.stairsDown(4))
        assertEquals(IseeClipsEn.STAIRS_DOWN_2, IseeClipsEn.stairsDown(2))
        assertEquals(IseeClipsEn.STAIRS_DOWN_1, IseeClipsEn.stairsDown(1))
        assertNull(IseeClipsEn.stairsUp(3))
        assertNull(IseeClipsEn.stairsDown(0))
        assertEquals("Steps.", IseeClipsEn.all[IseeClipsEn.STAIRS_STEPS])
    }

    @Test
    fun `nombres 1 a 12, cadrans 1 a 12, distances 5 10 15 20`() {
        for (n in 1..12) {
            assertEquals("n_$n", "n_$n", IseeClipsEn.number(n))
            assertEquals("clock_$n", "clock_$n", IseeClipsEn.clock(n))
        }
        assertNull(IseeClipsEn.number(0))
        assertNull(IseeClipsEn.number(13))
        assertNull(IseeClipsEn.clock(0))
        assertEquals(IseeClipsEn.METERS_5, IseeClipsEn.meters(5))
        assertEquals(IseeClipsEn.METERS_10, IseeClipsEn.meters(10))
        assertEquals(IseeClipsEn.METERS_15, IseeClipsEn.meters(15))
        assertEquals(IseeClipsEn.METERS_20, IseeClipsEn.meters(20))
        assertNull(IseeClipsEn.meters(3))
        assertEquals("Twelve.", IseeClipsEn.all[IseeClipsEn.N_12])
        assertEquals("12 o'clock.", IseeClipsEn.all[IseeClipsEn.CLOCK_12])
        assertEquals("20 meters.", IseeClipsEn.all[IseeClipsEn.METERS_20])
    }

    @Test
    fun `find my X couvre les 10 objets avec alias, inconnu nul`() {
        assertEquals(IseeClipsEn.KEYS, IseeClipsEn.findTarget("keys"))
        assertEquals(IseeClipsEn.MEDBOX, IseeClipsEn.findTarget("Medicine box"))
        assertEquals(IseeClipsEn.MEDBOX, IseeClipsEn.findTarget("pills"))
        assertEquals(IseeClipsEn.GLASSES, IseeClipsEn.findTarget("glasses"))
        assertEquals(IseeClipsEn.GLASSES_CASE, IseeClipsEn.findTarget("glasses case"))
        assertEquals(IseeClipsEn.PHONE, IseeClipsEn.findTarget("phone"))
        assertEquals(IseeClipsEn.SHOES, IseeClipsEn.findTarget("shoes"))
        assertEquals(IseeClipsEn.DOOR, IseeClipsEn.findTarget("door"))
        assertEquals(IseeClipsEn.WALLS, IseeClipsEn.findTarget("walls"))
        assertEquals(IseeClipsEn.HUMAN_PERSON, IseeClipsEn.findTarget("person"))
        assertEquals(IseeClipsEn.SOCKS, IseeClipsEn.findTarget("socks"))
        assertNull(IseeClipsEn.findTarget("spaceship"))
        assertNull(IseeClipsEn.findTarget(""))
    }

    private fun catalogFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/isee-clips-en-catalog.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("tools/isee-clips-en-catalog.json introuvable depuis ${System.getProperty("user.dir")}")
    }

    private operator fun org.json.JSONArray.plus(other: org.json.JSONArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            out += o.getString("id") to o.getString("en")
        }
        for (i in 0 until other.length()) {
            val o = other.getJSONObject(i)
            out += o.getString("id") to o.getString("en")
        }
        return out
    }
}
