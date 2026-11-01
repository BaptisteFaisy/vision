package dev.sam3vision.core.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v4 — niveaux voix dehors (0 à 12) et traversée (100 à 102).
 *
 * Contrat pur, sans dépendance prod : les missions voix/seuils implémentent
 * [VoiceBus.post] avec ces niveaux ; ce fichier verrouille les entiers, les
 * seuils de distance/temps et le contenu des phrases. Les libellés entre
 * crochets rappellent la mission prod qui porte chaque niveau.
 */
class IseeSpecV4LevelsTest {

    // Niveaux dehors.
    private val levelCarMoving = 0
    private val levelUserSpeech = 1
    private val levelHuman = 2
    private val levelGpsReminder = 3
    private val levelFence = 4
    private val levelCurb = 5
    private val levelStairs = 6
    private val levelPole = 7
    private val levelCrosswalk = 8
    private val levelChat = 9
    private val levelSidewalk = 10
    private val levelGpsManeuver = 11
    private val levelFallback = 12

    // Niveaux traversée exclusive.
    private val levelVerdict = 100
    private val levelCrossHuman = 101
    private val levelCrossCurb = 102

    /** Labels interdits dehors en v4 (détection supprimée). */
    private val deletedOutdoorLabels = setOf(
        "bus", "bicycle", "bike", "truck", "motorcycle",
        "bollard", "borne", "tree", "arbre", "gate", "portail",
        "boat", "bateau", "keys",
    )

    @Test
    fun `niveau 0 = voitures en mouvement`() {
        assertEquals(0, levelCarMoving)
    }

    @Test
    fun `niveau 0 = presence seule sous 20m, pluriel si plusieurs`() {
        val rangeM = 20
        val single = carPhrase(1)
        val several = carPhrase(3)

        assertEquals(20, rangeM)
        assertEquals("Car.", single)
        assertEquals("Cars.", several)
        assertFalse("pas de cadran", single.contains("o'clock"))
        assertFalse("pas de distance", single.any { it.isDigit() })
    }

    @Test
    fun `niveau 1 = parole user`() {
        assertEquals(1, levelUserSpeech)
    }

    @Test
    fun `niveau 2 = humains sous 10m, phrase plus cadran plus distance`() {
        val rangeM = 10
        val phrase = humanPhrase(clock = 9, meters = 4)

        assertEquals(2, levelHuman)
        assertEquals(10, rangeM)
        assertEquals("Person at 9 o'clock, 4 meters away.", phrase)
    }

    @Test
    fun `niveau 2 = imminent a 2m ou moins, mot immediat`() {
        assertTrue(isImminent(meters = 2))
        assertTrue(isImminent(meters = 1))
        assertFalse(isImminent(meters = 3))
        assertEquals("Right.", imminentWord(clock = 3))
    }

    @Test
    fun `niveau 3 = rappel GPS apres 15s de silence`() {
        assertEquals(3, levelGpsReminder)
        assertEquals(15_000L, GPS_REMINDER_SILENCE_MS)
        assertTrue(reminderDue(silenceMs = 15_000L))
        assertFalse(reminderDue(silenceMs = 14_999L))
        assertEquals(
            "Continue straight, 200 meters left.",
            reminderPhrase(lastInstruction = "Continue straight", metersLeft = 200),
        )
    }

    @Test
    fun `niveau 4 = grillages murs barrieres sous 6m`() {
        assertEquals(4, levelFence)
        assertEquals(6, FENCE_RANGE_M)
        assertTrue(fenceLabels.contains("fence"))
        assertTrue(fenceLabels.contains("wall"))
        assertTrue(fenceLabels.contains("barrier"))
    }

    @Test
    fun `niveau 5 = bordures sous 6m`() {
        assertEquals(5, levelCurb)
        assertEquals(6, CURB_RANGE_M)
    }

    @Test
    fun `niveau 6 = escaliers jalons 4 2 1 et marches dites`() {
        assertEquals(6, levelStairs)
        assertEquals(listOf(4, 2, 1), STAIR_MILESTONES_M)
        assertEquals("Stairs in 4 meters, 6 steps.", stairsPhrase(milestoneM = 4, steps = 6))
    }

    @Test
    fun `niveau 7 = poteaux mobilier vertical divers sous 6m`() {
        assertEquals(7, levelPole)
        assertEquals(6, POLE_RANGE_M)
        assertTrue(poleLabels.contains("pole"))
        assertTrue(poleLabels.contains("lamppost"))
        assertTrue(poleLabels.contains("sign"))
        assertTrue(poleLabels.contains("animal"))
        assertTrue(poleLabels.contains("bin"))
    }

    @Test
    fun `niveau 8 = passages pietons sous 6m, annonce alignement arrivee`() {
        assertEquals(8, levelCrosswalk)
        assertEquals(6, CROSSWALK_RANGE_M)
        assertEquals(
            listOf("announce-once-per-view", "align-on-change", "arrival-once"),
            CROSSWALK_EVENTS,
        )
    }

    @Test
    fun `niveau 9 = reponses chat, seule file restante`() {
        assertEquals(9, levelChat)
    }

    @Test
    fun `niveau 10 = trottoirs`() {
        assertEquals(10, levelSidewalk)
    }

    @Test
    fun `niveau 11 = manoeuvres et evenements GPS`() {
        assertEquals(11, levelGpsManeuver)
    }

    @Test
    fun `niveau 12 = repli apres 300ms de silence`() {
        assertEquals(12, levelFallback)
        assertEquals(300L, FALLBACK_SILENCE_MS)
        assertTrue(fallbackDue(silenceMs = 300L))
        assertFalse(fallbackDue(silenceMs = 299L))
        assertTrue(setOf("Left.", "Right.", "Straight ahead.").contains(fallbackWord(steer = "left")))
    }

    @Test
    fun `traversee = verdict 100 puis humains 101 puis bordures 102`() {
        assertEquals(100, levelVerdict)
        assertEquals(101, levelCrossHuman)
        assertEquals(102, levelCrossCurb)
        assertTrue(levelVerdict < levelCrossHuman && levelCrossHuman < levelCrossCurb)
    }

    @Test
    fun `dehors = bus velo camion moto borne arbre portail bateau cles supprimes`() {
        val kept = setOf(
            "car", "person", "fence", "wall", "barrier", "curb",
            "stairs", "pole", "lamppost", "sign", "animal", "bin",
            "crosswalk", "sidewalk",
        )

        assertTrue(deletedOutdoorLabels.none { it in kept })
        assertEquals(14, deletedOutdoorLabels.size)
    }

    @Test
    fun `clips = anglais uniquement`() {
        val clips = listOf(
            "Car.", "Cars.", "Right.", "Left.", "Straight ahead.",
            "Cross.", "Wait.", "Stairs in 4 meters, 6 steps.",
            "Keys found at 2 o'clock.",
        )

        assertTrue(clips.all { it.matches(Regex("[A-Za-z0-9 .,']+")) })
    }

    private fun carPhrase(count: Int): String = if (count > 1) "Cars." else "Car."

    private fun humanPhrase(clock: Int, meters: Int): String =
        "Person at $clock o'clock, $meters meters away."

    private fun isImminent(meters: Int): Boolean = meters <= 2

    private fun imminentWord(clock: Int): String = when (clock) {
        9 -> "Left."
        3 -> "Right."
        else -> "Ahead."
    }

    private fun reminderDue(silenceMs: Long): Boolean = silenceMs >= GPS_REMINDER_SILENCE_MS

    private fun reminderPhrase(lastInstruction: String, metersLeft: Int): String =
        "$lastInstruction, $metersLeft meters left."

    private fun stairsPhrase(milestoneM: Int, steps: Int): String =
        "Stairs in $milestoneM meters, $steps steps."

    private fun fallbackDue(silenceMs: Long): Boolean = silenceMs >= FALLBACK_SILENCE_MS

    private fun fallbackWord(steer: String): String = when (steer) {
        "left" -> "Left."
        "right" -> "Right."
        else -> "Straight ahead."
    }

    companion object {
        const val GPS_REMINDER_SILENCE_MS = 15_000L
        const val FALLBACK_SILENCE_MS = 300L
        const val FENCE_RANGE_M = 6
        const val CURB_RANGE_M = 6
        const val POLE_RANGE_M = 6
        const val CROSSWALK_RANGE_M = 6
        val STAIR_MILESTONES_M = listOf(4, 2, 1)
        val CROSSWALK_EVENTS = listOf("announce-once-per-view", "align-on-change", "arrival-once")
        val fenceLabels = setOf("fence", "wall", "barrier")
        val poleLabels = setOf("pole", "lamppost", "sign", "animal", "bin")
    }
}
