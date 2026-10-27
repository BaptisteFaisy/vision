package dev.sam3vision.core.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Routeur de navigation vocale : une transcription → demande de guidage, ou ignoré.
 *
 * Contrat de [NavigationRouter.route] : rend [NavigationRequest.destination] quand le
 * texte demande un guidage, `null` sinon (pas une navigation, texte vide ou blanc).
 * La destination est rendue telle que dite (articles et accents gardés), trimmée ;
 * la détection est insensible à la casse, aux accents et à la ponctuation finale.
 */
class NavigationRouterTest {

    private val router = NavigationRouter()

    @Test
    fun `emmene-moi a la gare = navigation detectee`() {
        val request = router.route("emmène-moi à la gare")

        assertEquals("la gare", request?.destination)
    }

    @Test
    fun `je veux aller a = navigation detectee`() {
        val request = router.route("je veux aller à la boulangerie")

        assertEquals("la boulangerie", request?.destination)
    }

    @Test
    fun `va a en debut de phrase = navigation detectee`() {
        val request = router.route("va à la pharmacie")

        assertEquals("la pharmacie", request?.destination)
    }

    @Test
    fun `guide-moi jusqu a = navigation detectee`() {
        val request = router.route("guide-moi jusqu'à la sortie")

        assertEquals("la sortie", request?.destination)
    }

    @Test
    fun `itineraire vers = navigation detectee`() {
        val request = router.route("itinéraire vers le musée")

        assertEquals("le musée", request?.destination)
    }

    @Test
    fun `question d heure = ignoree`() {
        assertNull(router.route("quelle heure est-il ?"))
    }

    @Test
    fun `ou est la gare = ignore, c'est une question`() {
        assertNull(router.route("où est la gare ?"))
        assertNull(router.route("ou est la gare ?"))
    }

    @Test
    fun `demande de description = ignoree`() {
        assertNull(router.route("décris-moi cette photo"))
    }

    @Test
    fun `petite phrase sans demande = ignoree`() {
        assertNull(router.route("il fait beau aujourd'hui"))
    }

    @Test
    fun `texte vide = ignore`() {
        assertNull(router.route(""))
    }

    @Test
    fun `texte blanc = ignore`() {
        assertNull(router.route("   "))
        assertNull(router.route("\n\t "))
    }

    @Test
    fun `destination garde ses accents`() {
        val request = router.route("emmène-moi à la crèche")

        assertEquals("la crèche", request?.destination)
    }

    @Test
    fun `destination trimmee`() {
        val request = router.route("  emmène-moi à la gare  ")

        assertEquals("la gare", request?.destination)
    }

    @Test
    fun `apostrophe gardee dans la destination`() {
        val request = router.route("guide-moi jusqu'à l'hôpital")

        assertEquals("l'hôpital", request?.destination)
    }
}
