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
    fun `started dit Navigation vers X`() {
        assertEquals("Navigation vers la gare de Lyon", NavMessages.started("la gare de Lyon"))
    }

    @Test
    fun `started rogne les espaces`() {
        assertEquals("Navigation vers Paris", NavMessages.started("  Paris  "))
    }

    @Test
    fun `guidage impossible verrouille a l identique`() {
        assertEquals("Je ne peux pas ouvrir le guidage", NavMessages.GUIDANCE_UNAVAILABLE)
    }

    @Test
    fun `placeOpened dit Voici la fiche de X`() {
        assertEquals("Voici la fiche de la tour Eiffel", NavMessages.placeOpened("la tour Eiffel"))
    }

    @Test
    fun `placeOpened rogne les espaces`() {
        assertEquals("Voici la fiche de Nice", NavMessages.placeOpened(" Nice "))
    }

    @Test
    fun `repli navigateur verrouille a l identique`() {
        assertEquals("Itinéraire ouvert dans le navigateur", NavMessages.BROWSER_FALLBACK)
    }

    @Test
    fun `stop verrouille a l identique`() {
        assertEquals("Guidage arrêté", NavMessages.STOP_DONE)
    }

    @Test
    fun `micro occupe reprend le libelle DictationFeature a l identique`() {
        assertEquals("micro déjà occupé par la vidéo", NavMessages.MIC_BUSY_VIDEO)
    }

    @Test
    fun `aide interrupteur eteint verrouillee a l identique`() {
        assertEquals(
            "Éteint : iSee montre la fiche du lieu au lieu de lancer le guidage.",
            NavMessages.SETTING_OFF_HELP,
        )
    }

    @Test
    fun `double echec ASR combine les deux messages`() {
        assertEquals(
            "Erreur. timeout Meta. 401 Scribe.",
            NavMessages.asrBothFailed("timeout Meta", "401 Scribe"),
        )
    }

    @Test
    fun `double echec ASR ne double pas les points`() {
        assertEquals(
            "Erreur. timeout Meta. 401 Scribe.",
            NavMessages.asrBothFailed("timeout Meta.", "  401 Scribe. "),
        )
    }

    @Test
    fun `double echec ASR replie un blanc sur echec inconnu`() {
        assertEquals(
            "Erreur. échec inconnu. 401 Scribe.",
            NavMessages.asrBothFailed("   ", "401 Scribe"),
        )
    }
}
