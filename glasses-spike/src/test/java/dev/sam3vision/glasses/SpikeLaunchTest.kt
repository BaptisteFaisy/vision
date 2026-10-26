package dev.sam3vision.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lancement piloté par les extras, testé sur JVM : aucun `Intent`, aucun
 * `Context`, aucun Robolectric.
 *
 * Ce qui est verrouillé ici : les quatre combinaisons d'extras, et le fait que
 * `analyze=true` arme l'analyse AVANT que la session ne démarre — l'ordre qui
 * décide si la première frame est analysée ou perdue.
 */
class SpikeLaunchTest {

    private val flags = listOf(false, true)

    // ---- Traduction des extras ---------------------------------------------

    @Test
    fun `les quatre combinaisons d extras sont traduites`() {
        assertEquals(SpikeLaunch.Options(mock = false, analyze = false), SpikeLaunch.fromExtras(false, false))
        assertEquals(SpikeLaunch.Options(mock = true, analyze = false), SpikeLaunch.fromExtras(true, false))
        assertEquals(SpikeLaunch.Options(mock = false, analyze = true), SpikeLaunch.fromExtras(false, true))
        assertEquals(SpikeLaunch.Options(mock = true, analyze = true), SpikeLaunch.fromExtras(true, true))
    }

    @Test
    fun `des extras absents ne demandent rien`() {
        val options = SpikeLaunch.fromExtras(null, null)
        assertFalse(options.mock)
        assertFalse(options.analyze)
        assertFalse(options.autoLaunch)
        assertEquals(emptyList<LaunchStep>(), SpikeLaunch.plan(options))
    }

    @Test
    fun `analyze seul n ouvre aucune session depuis l intent`() {
        val options = SpikeLaunch.fromExtras(mock = null, analyze = true)
        assertFalse("le mode réel demande un tap", options.autoLaunch)
        assertEquals(listOf(LaunchStep.ARM_ANALYZE), SpikeLaunch.plan(options))
    }

    @Test
    fun `mock seul lance la session sans armer l analyse`() {
        val options = SpikeLaunch.fromExtras(mock = true, analyze = null)
        assertTrue(options.autoLaunch)
        assertEquals(
            listOf(LaunchStep.ENABLE_MOCK, LaunchStep.PHONE_CAMERA_FEED, LaunchStep.START_SESSION),
            SpikeLaunch.plan(options),
        )
    }

    @Test
    fun `les deux extras lancent le mock et arment l analyse`() {
        val plan = SpikeLaunch.plan(SpikeLaunch.fromExtras(mock = true, analyze = true))
        assertEquals(
            listOf(
                LaunchStep.ARM_ANALYZE,
                LaunchStep.ENABLE_MOCK,
                LaunchStep.PHONE_CAMERA_FEED,
                LaunchStep.START_SESSION,
            ),
            plan,
        )
    }

    // ---- Ordre imposé ------------------------------------------------------

    @Test
    fun `l analyse est armee avant l ouverture de session`() {
        val plan = SpikeLaunch.plan(SpikeLaunch.fromExtras(mock = true, analyze = true))
        val arme = plan.indexOf(LaunchStep.ARM_ANALYZE)
        val session = plan.indexOf(LaunchStep.START_SESSION)
        assertTrue("les deux étapes doivent être présentes", arme >= 0 && session >= 0)
        assertTrue("ARM_ANALYZE doit précéder START_SESSION (plan = $plan)", arme < session)
    }

    /**
     * Rejoue le plan avec l'exécuteur minimal de `MainActivity` : dès que la
     * session démarre, la première frame lit le drapeau d'analyse
     * (`MainActivity.onFrame`). Rend (drapeau armé, première frame analysée).
     */
    private fun replay(options: SpikeLaunch.Options): Pair<Boolean, Boolean> {
        var analyzeFrames = false
        var firstFrameAnalyzed = false
        SpikeLaunch.plan(options).forEach { step ->
            when (step) {
                LaunchStep.ARM_ANALYZE -> analyzeFrames = true
                LaunchStep.START_SESSION -> firstFrameAnalyzed = analyzeFrames
                else -> Unit
            }
        }
        return analyzeFrames to firstFrameAnalyzed
    }

    @Test
    fun `analyze vrai arme l analyse avant la premiere frame`() {
        val (arme, premiereFrameAnalysee) = replay(SpikeLaunch.fromExtras(mock = true, analyze = true))
        assertTrue(arme)
        assertTrue("la première frame doit déjà être analysée", premiereFrameAnalysee)
    }

    @Test
    fun `sans analyze la session demarre sans analyse, comme avant`() {
        val (arme, premiereFrameAnalysee) = replay(SpikeLaunch.fromExtras(mock = true, analyze = null))
        assertFalse(arme)
        assertFalse(premiereFrameAnalysee)
    }

    // ---- Invariants du plan ------------------------------------------------

    @Test
    fun `chaque etape apparait une fois et la session est la derniere`() {
        flags.forEach { mock ->
            flags.forEach { analyze ->
                val options = SpikeLaunch.Options(mock = mock, analyze = analyze)
                val plan = SpikeLaunch.plan(options)
                assertEquals("étape répétée : $plan", plan.size, plan.distinct().size)
                assertEquals("session ouverte ssi mock", mock, plan.contains(LaunchStep.START_SESSION))
                if (mock) assertEquals("la session ferme le plan : $plan", LaunchStep.START_SESSION, plan.last())
                if (analyze) assertEquals("l'analyse ouvre le plan : $plan", LaunchStep.ARM_ANALYZE, plan.first())
            }
        }
    }

    @Test
    fun `l analyse n est jamais armee deux fois`() {
        flags.forEach { mock ->
            val plan = SpikeLaunch.plan(SpikeLaunch.Options(mock = mock, analyze = true))
            assertEquals(1, plan.count { it == LaunchStep.ARM_ANALYZE })
        }
    }
}