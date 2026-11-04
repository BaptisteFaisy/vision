package dev.sam3vision.app.isee

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le bloc caméra dégage le bandeau système du haut : son haut commence sous
 * la barre (l'aperçu natif recouvrirait le bandeau blanc et ses icônes), son
 * bas remonte de 4 dp (demande user 2026-11-04 : le direct descend très
 * légèrement moins bas). Figé ici.
 */
class IseeCardStyleTest {

    /** Le haut du bloc commence sous la barre, plus la marge de 8 dp. */
    @Test
    fun `le bloc camera commence sous le bandeau systeme`() {
        assertEquals(36.dp, cameraBlockTop(28.dp))
    }

    /** Le bas du bloc remonte de 4 dp : le direct descend moins bas. */
    @Test
    fun `le bas du bloc remonte de 4 dp`() {
        val top = cameraBlockTop(28.dp)
        val height = cameraBlockHeight(480.dp, 28.dp)
        assertEquals(8.dp + 480.dp - 4.dp, top + height)
    }

    /** Sans barre (paysage) : le haut garde sa marge, le bas remonte aussi. */
    @Test
    fun `sans barre le haut garde sa marge et le bas remonte`() {
        assertEquals(8.dp, cameraBlockTop(0.dp))
        assertEquals(476.dp, cameraBlockHeight(480.dp, 0.dp))
    }
}
