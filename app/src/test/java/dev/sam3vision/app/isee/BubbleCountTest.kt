package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bulles à l'écran : l'historique plus l'intro, toujours première.
 *
 * La navigation entre messages (balayage vertical, `swipeStep`) a été supprimée :
 * il ne reste que le compteur d'affichage — la sélection reste posée sur la dernière
 * bulle et aucune marche n'est possible.
 */
class BubbleCountTest {

    @Test
    fun `le compte ajoute l'intro toujours presente en tete du chat`() {
        assertEquals(1, bubbleCount(0))
        assertEquals(2, bubbleCount(1))
        assertEquals(3, bubbleCount(2))
    }

    @Test
    fun `sans echange la selection reste sur l'intro`() {
        // La sélection vaut toujours `bubbleCount(n) - 1` : avec 0 échange, l'intro (0).
        assertEquals(INTRO_SELECTION, bubbleCount(0) - 1)
    }
}
