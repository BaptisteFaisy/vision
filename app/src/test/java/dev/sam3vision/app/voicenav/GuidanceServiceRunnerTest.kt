package dev.sam3vision.app.voicenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Séquence du premier plan d'un service long (JUnit seul, sans Android) : entrée au
 * premier plan **en premier**, une seule fois, refus → sortie propre, et aucune exception
 * qui sorte. C'est la garde qui empêche `ForegroundServiceDidNotStartInTimeException`.
 */
class GuidanceServiceRunnerTest {

    /** Effets enregistrés : l'ordre des appels **est** ce que le runner doit garantir. */
    private class RecordingFx(
        var channelAvailable: Boolean = true,
        var enterFails: Boolean = false,
        var exitFails: Boolean = false,
        var foreground: Boolean = false,
    ) : ForegroundFx {
        val calls = mutableListOf<String>()

        override fun ensureChannel(): Boolean {
            calls += "canal"
            return channelAvailable
        }

        override fun enterForeground(fallback: Boolean) {
            calls += if (fallback) "premier-plan(secours)" else "premier-plan"
            if (enterFails) error("refus du système")
            foreground = true
        }

        override fun exitForeground() {
            calls += "sortie"
            if (exitFails) error("sortie refusée")
            foreground = false
        }

        override fun isForeground(): Boolean {
            calls += "état"
            return foreground
        }
    }

    @Test
    fun `le premier plan vient en premier, après le canal`() {
        val fx = RecordingFx()
        assertTrue(GuidanceServiceRunner(fx).start())
        assertEquals(listOf("état", "canal", "premier-plan"), fx.calls)
    }

    @Test
    fun `démarrage doublé — rafraîchit, ne recrée pas le canal et ne ressort pas`() {
        val fx = RecordingFx(foreground = true)
        assertTrue(GuidanceServiceRunner(fx).start())
        assertEquals(listOf("état", "premier-plan"), fx.calls)
        assertFalse(fx.calls.contains("sortie"))
    }

    @Test
    fun `refus du premier plan — sortie propre, incident noté`() {
        val fx = RecordingFx(enterFails = true)
        val runner = GuidanceServiceRunner(fx)
        assertFalse(runner.start())
        assertEquals(listOf("état", "canal", "premier-plan", "sortie"), fx.calls)
        assertEquals(listOf("premier plan: IllegalStateException"), runner.incidents)
    }

    @Test
    fun `canal principal indisponible — entrée par le canal de secours`() {
        val fx = RecordingFx(channelAvailable = false)
        assertTrue(GuidanceServiceRunner(fx).start())
        assertEquals(listOf("état", "canal", "premier-plan(secours)"), fx.calls)
    }

    @Test
    fun `arrêt — une seule sortie, sans entrée au premier plan`() {
        val fx = RecordingFx(foreground = true)
        assertTrue(GuidanceServiceRunner(fx).stop())
        assertEquals(listOf("sortie"), fx.calls)
    }

    @Test
    fun `tous les effets lèvent — rien ne sort du runner`() {
        val runner = GuidanceServiceRunner(
            object : ForegroundFx {
                override fun ensureChannel(): Boolean = error("canal")
                override fun enterForeground(fallback: Boolean): Unit = error("premier plan")
                override fun exitForeground(): Unit = error("sortie")
                override fun isForeground(): Boolean = error("état")
            },
        )
        // Aucune exception ne doit sortir : `@Test` échoue d'elle-même si c'est le cas.
        // Quatre étapes tentées (état, canal, premier plan, sortie), quatre incidents notés.
        assertFalse(runner.start())
        assertEquals(4, runner.incidents.size)
        assertFalse(runner.stop())
        assertEquals(1, runner.incidents.size)
    }
}
