package dev.sam3vision.core.navcore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verrouille le catalogue français ([NavMessages]) : chaque texte est un
 * contrat (spéc. voix § b/e, `strings.xml` `nav_*`, `NavigationPhrases`).
 * Un test casse si un libellé change — c'est voulu.
 */
class NavMessagesTest {

    @Test
    fun `started dit Going to X`() {
        assertEquals("Going to la gare de Lyon", NavMessages.started("la gare de Lyon"))
    }

    @Test
    fun `started rogne les espaces`() {
        assertEquals("Going to Paris", NavMessages.started("  Paris  "))
    }

    @Test
    fun `guidage impossible verrouille a l identique`() {
        assertEquals("No guidance.", NavMessages.GUIDANCE_UNAVAILABLE)
    }

    @Test
    fun `placeOpened dit Page X`() {
        assertEquals("Page: la tour Eiffel.", NavMessages.placeOpened("la tour Eiffel"))
    }

    @Test
    fun `placeOpened rogne les espaces`() {
        assertEquals("Page: Nice.", NavMessages.placeOpened(" Nice "))
    }

    @Test
    fun `repli navigateur verrouille a l identique`() {
        assertEquals("Route opened in the browser", NavMessages.BROWSER_FALLBACK)
    }

    @Test
    fun `stop verrouille a l identique`() {
        assertEquals("Stopped", NavMessages.STOP_DONE)
    }

    @Test
    fun `micro occupe reprend le libelle DictationFeature a l identique`() {
        assertEquals("microphone already busy with video", NavMessages.MIC_BUSY_VIDEO)
    }

    @Test
    fun `aide interrupteur eteint verrouillee a l identique`() {
        assertEquals(
            "Off: iSee shows the place page instead of starting guidance.",
            NavMessages.SETTING_OFF_HELP,
        )
    }

    @Test
    fun `double echec ASR combine les deux messages`() {
        assertEquals(
            "Error. timeout Meta. 401 Scribe.",
            NavMessages.asrBothFailed("timeout Meta", "401 Scribe"),
        )
    }

    @Test
    fun `double echec ASR ne double pas les points`() {
        assertEquals(
            "Error. timeout Meta. 401 Scribe.",
            NavMessages.asrBothFailed("timeout Meta.", "  401 Scribe. "),
        )
    }

    @Test
    fun `double echec ASR replie un blanc sur echec inconnu`() {
        assertEquals(
            "Error. unknown failure. 401 Scribe.",
            NavMessages.asrBothFailed("   ", "401 Scribe"),
        )
    }
}
