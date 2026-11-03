package dev.sam3vision.core.traverse

import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garée vs circulation, brut du verdict (SPEC v4) : une voiture garée ne dit
 * jamais mouvement — ni fixe, ni sur une frame de gigue SAM isolée — et une
 * voiture qui approche bloque (run de 2 croissances, ~400 ms à 5 ips). Le
 * fond [MotionSample.Zero] (frames identiques) prouve l'arrêt. Biais garée :
 * le tau est attribué à son instance (pas au label) et la croissance exige
 * la même instance (un saut de boîte vers une autre voiture n'est pas une
 * croissance). Séquences synthétiques à la cadence du direct (200 ms).
 */
class VehicleParkedVsTrafficTest {

    private fun feed(watcher: VehicleStateWatcher, width: Float, atMs: Long, motion: FrameMotion? = null) =
        watcher.update(
            listOf(vehicleDet("car", vehicleBox(width))),
            listOf(vehicleReading("car", 8f, vehicleBox(width))),
            atMs = atMs, language = SpeechLanguage.En, motion = motion,
        )

    @Test
    fun `garee fixe sans fond - silence total`() {
        val watcher = VehicleStateWatcher()
        repeat(3) { index ->
            val cue = feed(watcher, 100f, index * 200L)
            assertFalse("frame $index : brut faux", watcher.movingThisFrame)
            assertFalse("frame $index : pas de mouvement", cue?.moving == true)
            assertNull("frame $index : jamais de stop", cue?.safetyPhrase)
        }
    }

    @Test
    fun `gigue isolee sans fond - une frame de croissance ne dit pas mouvement`() {
        // Gigue SAM réelle S1 (±7 % de surface) : une croissance isolée
        // n'est pas une preuve — le brut exige un run de 2 (le verdict ne
        // contredit pas « Cross. » sur un flicker).
        val watcher = VehicleStateWatcher()
        listOf(100f, 107f, 100f).forEachIndexed { index, width ->
            feed(watcher, width, index * 200L)
            assertFalse("frame $index : gigue isolée, brut faux", watcher.movingThisFrame)
        }
    }

    @Test
    fun `croissances repetees sans fond - deux de suite disent mouvement`() {
        // Fail-safe : une croissance qui se répète bloque, même sans fond
        // (le doute ne fait jamais traverser).
        val watcher = VehicleStateWatcher()
        listOf(100f, 107f, 114f).forEachIndexed { index, width ->
            feed(watcher, width, index * 200L)
        }
        assertTrue("run-2 : brut vrai", watcher.movingThisFrame)
    }

    @Test
    fun `circulation qui approche - brut puis annonce mouvement`() {
        val watcher = VehicleStateWatcher()
        var moving = 0
        listOf(100f, 110f, 120f, 130f).forEachIndexed { index, width ->
            val cue = feed(watcher, width, index * 200L)
            if (cue?.moving == true) moving += 1
        }
        assertTrue("brut vrai en fin d'approche", watcher.movingThisFrame)
        assertTrue("mouvement annoncé", moving >= 1)
    }

    @Test
    fun `tau voisin - la garee ne capte pas le tau (attribution par instance)`() {
        // Une voiture qui approche franchement (tau armé, run-5) à côté d'une
        // garée, même label : sans attribution, la garée capte le tau et la
        // voix compte 2 mobiles ; avec, seule la roulante parle.
        val watcher = VehicleStateWatcher(movingCooldownMs = 0L)
        val parkedBox = vehicleBox(120f, cx = 500f)
        listOf(100f, 110f, 120f, 130f, 140f, 150f, 160f).forEachIndexed { index, width ->
            val moveBox = vehicleBox(width)
            val cue = watcher.update(
                listOf(vehicleDet("car", moveBox), vehicleDet("car", parkedBox)),
                listOf(vehicleReading("car", 12f, moveBox), vehicleReading("car", 5f, parkedBox)),
                atMs = index * 100L,
                language = SpeechLanguage.En,
            )
            if (index == 2 || index == 4 || index == 6) {
                assertTrue("seule la roulante est mobile", cue!!.moving)
                assertEquals("la garée n'est pas comptée (tau attribué)", 1, cue.movingCount)
            } else assertNull("frame $index : silence (confirmation ou reconfirmation)", cue)
        }
    }

    @Test
    fun `saut de boite - churn SAM, jamais en mouvement puis arret`() {
        // La boîte saute d'une voiture à l'autre dans la même clé (IoU < 0,3,
        // aire qui explose deux frames de suite) : sans continuité, ce n'est
        // pas une croissance — une autre voiture n'est pas un mouvement
        // (churn S1). Quand la boîte se fixe, l'arrêt se dit.
        val watcher = VehicleStateWatcher()
        val boxes = listOf(
            vehicleBox(100f, cx = 300f),
            vehicleBox(140f, cx = 380f),
            vehicleBox(180f, cx = 300f),
            vehicleBox(140f, cx = 300f),
            vehicleBox(100f, cx = 300f),
        )
        boxes.forEachIndexed { index, box ->
            val cue = watcher.update(
                listOf(vehicleDet("car", box)),
                listOf(vehicleReading("car", 5f, box)),
                atMs = index * 200L,
                language = SpeechLanguage.En,
            )
            if (index < 4) {
                assertNull("frame $index : saut de boîte, silence", cue)
                assertFalse("frame $index : brut faux", watcher.movingThisFrame)
            } else {
                assertFalse("boîte fixée : arrêt, pas mouvement", cue!!.moving)
                assertEquals("Car stopped, 5 meters away.", cue.phrase)
            }
        }
        assertNull("jamais d'alerte mouvement", watcher.lastMovingAlertMs)
    }

    @Test
    fun `fond zero - frames identiques prouvent l'arret`() {
        // [MotionSample.Zero] = coût statique nul = frames identiques : la
        // preuve d'immobilité la plus forte, pas un doute.
        val static = FrameMotion(MotionSample.Zero, 96, 96, 200L)
        val watcher = VehicleStateWatcher()
        var parked = 0
        repeat(3) { index ->
            val cue = feed(watcher, 100f, index * 200L, static)
            assertFalse("frame $index : brut faux", watcher.movingThisFrame)
            if (cue != null && !cue.moving) parked += 1
            assertNull("frame $index : jamais de stop", cue?.safetyPhrase)
        }
        assertEquals("arrêt prouvé annoncé (garée 8 m)", 1, parked)
    }
}
