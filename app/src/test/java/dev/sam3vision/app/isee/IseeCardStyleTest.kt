package dev.sam3vision.app.isee

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le bloc caméra dégage le bandeau système du haut : son haut commence sous
 * la barre (l'aperçu natif recouvrirait le bandeau blanc et ses icônes), son
 * bas ne bouge pas. Figé ici : une régression rendrait la batterie et les
 * notifications illisibles sur iSee.
 */
class IseeCardStyleTest {

    /** Le haut du bloc commence sous la barre, plus la marge de 8 dp. */
    @Test
    fun `le bloc camera commence sous le bandeau systeme`() {
        assertEquals(36.dp, cameraBlockTop(28.dp))
    }

    /** Le bas du bloc ne bouge pas : la hauteur absorbe le décalage. */
    @Test
    fun `le bas du bloc ne bouge pas`() {
        val top = cameraBlockTop(28.dp)
        val height = cameraBlockHeight(480.dp, 28.dp)
        assertEquals(8.dp + 480.dp, top + height)
    }

    /** Sans barre (paysage) : le bloc garde sa marge de 8 dp. */
    @Test
    fun `sans barre le bloc garde sa marge`() {
        assertEquals(8.dp, cameraBlockTop(0.dp))
        assertEquals(480.dp, cameraBlockHeight(480.dp, 0.dp))
    }
}
