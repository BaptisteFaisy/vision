package dev.sam3vision.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le préchauffage GPU part une fois par fenêtre, jamais deux fois de suite. */
class PrewarmTest {

    @Test
    fun `le premier affichage prechauffe toujours`() {
        assertTrue(shouldPrewarm(-120_000L, 1_000L, 120_000L))
    }

    @Test
    fun `deux affichages rapproches ne rallument qu'une fois`() {
        assertFalse(shouldPrewarm(1_000L, 60_000L, 120_000L))
        assertTrue(shouldPrewarm(1_000L, 121_000L, 120_000L))
    }
}
