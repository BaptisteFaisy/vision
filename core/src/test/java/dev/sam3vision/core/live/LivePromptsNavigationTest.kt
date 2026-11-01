package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Navigation : le post-traitement déterministe
 * (NavigationIntentParser sur le texte dicté) suffit — aucun concept suivi
 * ni consigne n'est ajouté ici. Ces tests verrouillent cette non-régression.
 */
class LivePromptsNavigationTest {

    @Test
    fun `la navigation n'ajoute aucun concept suivi`() {
        assertEquals(
            listOf(
                "person", "car", "pedestrian traffic light", "curb", "sidewalk",
                "crosswalk", "pole", "stairs", "fence", "wall", "barrier", "sign",
                "street lamp", "animal", "trash can", "souvenir shop",
            ),
            LivePrompts.default,
        )
        assertEquals(22, LivePrompts.MAX)
    }

    @Test
    fun `une phrase de navigation reste un texte opaque pour normalize`() {
        // Courte : conservée telle quelle, sans interprétation.
        assertEquals(listOf("aller à la gare"), LivePrompts.normalize(listOf("aller à la gare")))
        // Longue : rejetée comme tout prompt trop long, sans traitement spécial.
        val longue = "je veux aller à la gare de Lyon s'il vous plaît merci"
        assertEquals(listOf("door"), LivePrompts.normalize(listOf(longue, "door")))
    }
}
