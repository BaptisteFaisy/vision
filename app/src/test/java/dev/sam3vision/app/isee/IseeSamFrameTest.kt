package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verrouille la géométrie de la page caméra : SAM reçoit du 320 px/q55 (chemin
 * chaud mesuré), le direct aidant garde son 480 px lisible (encodé à part, et
 * seulement quand un aidant regarde).
 *
 * Régression du 01/11 : un couplage unique à 480 px/q65 pour les deux avait
 * ajouté +88 ms de `capt` mesurés (campagne phase 6b : 167 → 255 ms).
 */
class IseeSamFrameTest {

    @Test
    fun `SAM recoit du 320px q55 et le direct aidant garde son 480px`() {
        assertEquals(320, ISEE_FRAME_EDGE)
        assertEquals(55, ISEE_FRAME_QUALITY)
        assertEquals(480, SHARE_FRAME_EDGE)
    }
}
