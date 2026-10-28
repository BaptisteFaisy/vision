package dev.sam3vision.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Démarrage du micro verrouillé : premier plan ou arrêt, sans jamais lever. Chaque test rejoue
 * un démarrage avec des effets en faux, dont certains qui lèvent : le runner doit toujours rendre
 * sa décision, arrêter le service sur les chemins de refus, et noter les avertissements.
 */
class LockedMicStartTest {

    /** Effets en faux : chaque appel est noté, ceux de [throwsOn] lèvent. */
    private class Fx(var throwsOn: Map<String, Throwable> = emptyMap()) : LockedMicStartFx {
        val calls = mutableListOf<String>()
        val published = mutableListOf<LockedMicState>()
        val foregrounds = mutableListOf<LockedMicState>()

        private fun step(name: String) {
            calls += name
            throwsOn[name]?.let { throw it }
        }

        override fun publish(state: LockedMicState) {
            published += state
            step("publish")
        }

        override fun enterForeground(state: LockedMicState) {
            foregrounds += state
            step("enterForeground")
        }

        override fun stopForeground() = step("stopForeground")
        override fun stopSelf() = step("stopSelf")
        override fun toggle() = step("toggle")
        override fun cancelAutoSend() = step("cancelAutoSend")
        override fun releaseRecorder() = step("releaseRecorder")
        override fun releaseWakeLock() = step("releaseWakeLock")
        override fun stopVoice() = step("stopVoice")
    }

    private fun run(
        fx: Fx = Fx(),
        action: MicStartAction = MicStartAction.ARM,
        current: LockedMicState = LockedMicState(),
        hasAudio: Boolean = true,
    ): MicStartOutcome = LockedMicStartRunner(fx).run(action, current, hasAudio)

    @Test
    fun `l intention nulle ou inconnue arme, chaque action est reconnue`() {
        assertEquals(MicStartAction.ARM, parseMicStartAction(null))
        assertEquals(MicStartAction.ARM, parseMicStartAction("dev.sam3vision.app.locked.BIDON"))
        assertEquals(MicStartAction.ARM, parseMicStartAction(MicStartActions.ARM))
        assertEquals(MicStartAction.TOGGLE, parseMicStartAction(MicStartActions.TOGGLE))
        assertEquals(MicStartAction.STOP, parseMicStartAction(MicStartActions.STOP))
    }

    @Test
    fun `stop demonte tout publie off et s arrete dans l ordre`() {
        val fx = Fx()
        val outcome = run(fx, action = MicStartAction.STOP)

        assertTrue(outcome is MicStartOutcome.Stopped)
        assertTrue(outcome.warnings.isEmpty())
        assertEquals(
            listOf(
                "cancelAutoSend", "releaseRecorder", "releaseWakeLock", "stopVoice",
                "publish", "stopForeground", "stopSelf",
            ),
            fx.calls,
        )
        assertEquals(listOf(LockedMicPhase.OFF), fx.published.map { it.phase })
    }

    @Test
    fun `stop ne leve jamais meme si tout leve et tente chaque etape`() {
        val boom = RuntimeException("boum")
        val fx = Fx(
            throwsOn = mapOf(
                "cancelAutoSend" to boom, "releaseRecorder" to boom, "releaseWakeLock" to boom,
                "stopVoice" to boom, "publish" to boom, "stopForeground" to boom, "stopSelf" to boom,
            ),
        )

        val outcome = run(fx, action = MicStartAction.STOP)

        assertTrue(outcome is MicStartOutcome.Stopped)
        assertEquals(7, fx.calls.size)
        assertTrue("stopSelf est tenté même si tout lève", fx.calls.contains("stopSelf"))
        assertEquals(7, outcome.warnings.size)
    }

    @Test
    fun `armer sans permission refuse ouvre iSee et s arrete sans premier plan`() {
        val fx = Fx()
        val outcome = run(fx, hasAudio = false)

        assertTrue(outcome is MicStartOutcome.Refused)
        assertEquals(MicStartRefus.OPEN_ISEE, (outcome as MicStartOutcome.Refused).reason)
        assertFalse(fx.calls.contains("enterForeground"))
        assertEquals(listOf("publish", "stopForeground", "stopSelf"), fx.calls)
        assertEquals(LockedMicPhase.FAILED, fx.published.single().phase)
        assertEquals(MicStartRefus.OPEN_ISEE, fx.published.single().error)
    }

    @Test
    fun `le geste sans permission refuse micro indisponible`() {
        val fx = Fx()
        val outcome = run(fx, action = MicStartAction.TOGGLE, hasAudio = false)

        assertTrue(outcome is MicStartOutcome.Refused)
        assertEquals(MicStartRefus.UNAVAILABLE, (outcome as MicStartOutcome.Refused).reason)
        assertEquals(MicStartRefus.UNAVAILABLE, fx.published.single().error)
    }

    @Test
    fun `un premier plan refuse degrade sans fatal`() {
        val fx = Fx(
            throwsOn = mapOf(
                "enterForeground" to SecurityException(
                    "Starting FGS with type microphone requires permissions",
                ),
            ),
        )

        val outcome = run(fx)

        assertTrue("pas de FATAL : un refus parlé, comme le 27/10", outcome is MicStartOutcome.Refused)
        assertEquals(MicStartRefus.OPEN_ISEE, (outcome as MicStartOutcome.Refused).reason)
        assertEquals(LockedMicPhase.READY, fx.foregrounds.single().phase)
        assertTrue(fx.calls.contains("stopSelf"))
        assertEquals(1, outcome.warnings.size)
        assertTrue(outcome.warnings.single().contains("premier plan"))
    }

    @Test
    fun `armer prend le premier plan avant de publier`() {
        val fx = Fx()
        val outcome = run(fx)

        assertTrue(outcome is MicStartOutcome.Armed)
        assertEquals(false, (outcome as MicStartOutcome.Armed).gesture)
        assertEquals(listOf("enterForeground", "publish"), fx.calls)
        assertEquals(LockedMicPhase.READY, fx.foregrounds.single().phase)
        assertEquals(LockedMicPhase.READY, fx.published.single().phase)
    }

    @Test
    fun `re armer garde l etat affiche et reprend le premier plan`() {
        val fx = Fx()
        val failed = LockedMicState().failed("panne de réseau")

        val outcome = run(fx, current = failed)

        assertTrue(outcome is MicStartOutcome.Armed)
        assertEquals(listOf("enterForeground"), fx.calls)
        assertEquals("aucune republication : l'échec reste affiché", 0, fx.published.size)
        assertEquals(failed, fx.foregrounds.single())
    }

    @Test
    fun `re armer pret ne republie pas`() {
        val fx = Fx()
        val ready = LockedMicState().armed()

        run(fx, current = ready)

        assertEquals(listOf("enterForeground"), fx.calls)
        assertEquals(ready, fx.foregrounds.single())
    }

    @Test
    fun `le geste bascule apres l armement`() {
        val fx = Fx()
        val outcome = run(fx, action = MicStartAction.TOGGLE)

        assertTrue(outcome is MicStartOutcome.Armed)
        assertEquals(true, (outcome as MicStartOutcome.Armed).gesture)
        assertEquals(listOf("enterForeground", "publish", "toggle"), fx.calls)
    }

    @Test
    fun `le geste qui leve degrade en echec sans arreter le service`() {
        val fx = Fx(throwsOn = mapOf("toggle" to RuntimeException("micro pris")))

        val outcome = run(fx, action = MicStartAction.TOGGLE)

        assertTrue(outcome is MicStartOutcome.Armed)
        assertFalse("armé : le bouton de l'écran verrouillé reste vivant", fx.calls.contains("stopSelf"))
        assertEquals(
            listOf(LockedMicPhase.READY, LockedMicPhase.FAILED),
            fx.published.map { it.phase },
        )
        assertEquals(MicStartRefus.TOGGLE_FAILED, fx.published.last().error)
        assertEquals(1, outcome.warnings.size)
    }

    @Test
    fun `une publication qui leve n empeche pas l arret du refuse`() {
        val fx = Fx(throwsOn = mapOf("publish" to SecurityException("notification refusée")))

        val outcome = run(fx, hasAudio = false)

        assertTrue(outcome is MicStartOutcome.Refused)
        assertEquals(listOf("publish", "stopForeground", "stopSelf"), fx.calls)
        assertEquals(1, outcome.warnings.size)
    }

    @Test
    fun `une publication qui leve n empeche pas le premier plan pris`() {
        val fx = Fx(throwsOn = mapOf("publish" to SecurityException("notification refusée")))

        val outcome = run(fx)

        assertTrue(outcome is MicStartOutcome.Armed)
        assertEquals(listOf("enterForeground", "publish"), fx.calls)
    }

    @Test
    fun `un arret qui leve ne sort pas du runner`() {
        val fx = Fx(throwsOn = mapOf("stopSelf" to RuntimeException("déjà mort")))

        val outcome = run(fx, hasAudio = false)

        assertTrue(outcome is MicStartOutcome.Refused)
        assertEquals(1, outcome.warnings.size)
    }

    @Test
    fun `une erreur du premier plan n est pas fatale non plus`() {
        val fx = Fx(throwsOn = mapOf("enterForeground" to AssertionError("système incohérent")))

        val outcome = run(fx)

        assertTrue(outcome is MicStartOutcome.Refused)
        assertTrue(fx.calls.contains("stopSelf"))
    }
}
