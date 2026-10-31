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
    fun `started dit Navigating to X`() {
        assertEquals("Navigating to la gare de Lyon", NavMessages.started("la gare de Lyon"))
    }

    @Test
    fun `started rogne les espaces`() {
        assertEquals("Navigating to Paris", NavMessages.started("  Paris  "))
    }

    @Test
    fun `guidage impossible verrouille a l identique`() {
        assertEquals("I can't open the guidance", NavMessages.GUIDANCE_UNAVAILABLE)
    }

    @Test
    fun `placeOpened dit Here is the page for X`() {
        assertEquals("Here is the page for la tour Eiffel", NavMessages.placeOpened("la tour Eiffel"))
    }

    @Test
    fun `placeOpened rogne les espaces`() {
        assertEquals("Here is the page for Nice", NavMessages.placeOpened(" Nice "))
    }

    @Test
    fun `repli navigateur verrouille a l identique`() {
        assertEquals("Route opened in the browser", NavMessages.BROWSER_FALLBACK)
    }

    @Test
    fun `stop verrouille a l identique`() {
        assertEquals("Guidance stopped", NavMessages.STOP_DONE)
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
