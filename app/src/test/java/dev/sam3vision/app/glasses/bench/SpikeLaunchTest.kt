package dev.sam3vision.app.glasses.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Séquence de démarrage ([SpikeLaunch.plan]) testée sur JVM : aucun `Intent`,
 * aucun `Context`, aucun Robolectric.
 *
 * Le banc n'est plus une `Activity` : `BenchScreen` construit les `Options` depuis
 * ses gestes puis exécute le plan (`applyLaunchPlan`). C'est donc le plan — et lui
 * seul — qui reste à verrouiller ; `SpikeLaunch.fromExtras` n'a plus d'appelant et
 * n'est plus testé (un test d'un comportement qu'aucun écran n'exécute ne prouve rien).
 *
 * Ce que ces tests défendent : `analyze` arme l'analyse AVANT que la session ne
 * démarre — l'ordre qui décide si la première frame est analysée ou perdue.
 */
class SpikeLaunchTest {

    // ---- Traduction des options --------------------------------------------

    @Test
    fun `des options vides ne demandent aucune etape`() {
        assertEquals(emptyList<LaunchStep>(), SpikeLaunch.plan(SpikeLaunch.Options()))
    }

    @Test
    fun `analyze seul n ouvre aucune session`() {
        // Le mode réel demande un tap sur l'écran : le plan ne fait qu'armer l'analyse.
        assertEquals(listOf(LaunchStep.ARM_ANALYZE), SpikeLaunch.plan(SpikeLaunch.Options(analyze = true)))
    }

    @Test
    fun `mock seul lance la session sans armer l analyse`() {
        assertEquals(
            listOf(LaunchStep.ENABLE_MOCK, LaunchStep.PHONE_CAMERA_FEED, LaunchStep.START_SESSION),
            SpikeLaunch.plan(SpikeLaunch.Options(mock = true)),
        )
    }

    @Test
    fun `les deux options lancent le mock et arment l analyse`() {
        assertEquals(
            listOf(
                LaunchStep.ARM_ANALYZE,
                LaunchStep.ENABLE_MOCK,
                LaunchStep.PHONE_CAMERA_FEED,
                LaunchStep.START_SESSION,
            ),
            SpikeLaunch.plan(SpikeLaunch.Options(mock = true, analyze = true)),
        )
    }

    // ---- Ordre imposé ------------------------------------------------------

    @Test
    fun `l analyse est armee avant l ouverture de session`() {
        val plan = SpikeLaunch.plan(SpikeLaunch.Options(mock = true, analyze = true))
        val arme = plan.indexOf(LaunchStep.ARM_ANALYZE)
        val session = plan.indexOf(LaunchStep.START_SESSION)
        assertTrue("les deux étapes doivent être présentes", arme >= 0 && session >= 0)
        assertTrue("ARM_ANALYZE doit précéder START_SESSION (plan = $plan)", arme < session)
    }

    /**
     * Rejoue le plan avec l'exécuteur minimal de `BenchScreen.applyLaunchPlan` : dès
     * que la session démarre, la première frame lit le drapeau d'analyse.
     * Rend (drapeau armé, première frame analysée).
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
        val (arme, premiereFrameAnalysee) = replay(SpikeLaunch.Options(mock = true, analyze = true))
        assertTrue(arme)
        assertTrue("la première frame doit déjà être analysée", premiereFrameAnalysee)
    }

    @Test
    fun `sans analyze la session demarre sans analyse, comme avant`() {
        val (arme, premiereFrameAnalysee) = replay(SpikeLaunch.Options(mock = true))
        assertFalse(arme)
        assertFalse(premiereFrameAnalysee)
    }

    // ---- Invariants du plan ------------------------------------------------

    @Test
    fun `chaque etape apparait une fois et la session est la derniere`() {
        flags.forEach { mock ->
            flags.forEach { analyze ->
                val plan = SpikeLaunch.plan(SpikeLaunch.Options(mock = mock, analyze = analyze))
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

    private companion object {
        val flags = listOf(false, true)
    }
}