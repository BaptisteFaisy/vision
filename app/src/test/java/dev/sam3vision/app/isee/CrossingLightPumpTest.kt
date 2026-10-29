package dev.sam3vision.app.isee

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Le seau partagé de l'écran iSee : une seule instance pour la caméra du
 * téléphone et le pont des lunettes — deux seaux voudraient dire un seau qui ne
 * reçoit jamais de frame.
 */
class CrossingLightPumpTest {

    @Test
    fun `installer deux fois rend le meme seau`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val first = CrossingLightPump.install(scope, Dispatchers.Unconfined) { null }
        val second = CrossingLightPump.install(scope, Dispatchers.Unconfined) { listOf(0f, 0f, 1f, 1f) }
        assertSame("le second install doit rendre le premier seau", first, second)
        assertNotNull(CrossingLightPump.current)
    }
}
