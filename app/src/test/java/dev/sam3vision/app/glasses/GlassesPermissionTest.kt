package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionError
import com.meta.wearable.dat.core.types.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le contrat d'autorisation du SDK ne se vérifie que par son verdict : c'est la traduction
 * verdict → état + message qui est testée ici, et la couverture **erreur par erreur** des
 * refus de session (les 18 valeurs de `DeviceSessionError`).
 */
class GlassesPermissionTest {

    private fun verdict(status: PermissionStatus): PermissionResult =
        DatResult.success<PermissionStatus, PermissionError>(status)

    private fun failure(error: PermissionError): PermissionResult =
        DatResult.failure<PermissionStatus, PermissionError>(error, IllegalStateException("test"))

    @Test
    fun `camera accordee allume la camera sans toucher au micro`() {
        val outcome = permissionOutcome(Permission.CAMERA, verdict(PermissionStatus.Granted))

        assertEquals(true, outcome.cameraAllowed)
        assertNull(outcome.micAllowed)
        assertEquals(MessageKind.INFO, outcome.message.kind)
    }

    @Test
    fun `camera refusee dit ou la redemander`() {
        val outcome = permissionOutcome(Permission.CAMERA, verdict(PermissionStatus.Denied))

        assertEquals(false, outcome.cameraAllowed)
        assertEquals(MessageKind.ERROR, outcome.message.kind)
        assertTrue(outcome.message.text.contains("Autoriser"))
    }

    @Test
    fun `micro accorde ne touche pas a la camera`() {
        val outcome = permissionOutcome(Permission.MICROPHONE, verdict(PermissionStatus.Granted))

        assertEquals(true, outcome.micAllowed)
        assertNull(outcome.cameraAllowed)
    }

    @Test
    fun `chaque echec d autorisation a sa phrase`() {
        PermissionError.values().forEach { error ->
            val message = permissionOutcome(Permission.CAMERA, failure(error)).message
            assertEquals(error.name, MessageKind.ERROR, message.kind)
            assertTrue(error.name, message.text.isNotBlank())
        }
        val noMeta = permissionOutcome(Permission.CAMERA, failure(PermissionError.META_AI_NOT_INSTALLED))
        assertTrue(noMeta.message.text.contains("Meta AI"))
    }

    @Test
    fun `les 18 erreurs de session sont traduites`() {
        assertEquals(18, DeviceSessionError.values().size)
        DeviceSessionError.values().forEach { error ->
            val message = sessionErrorMessage(error)
            assertTrue(error.name, message.text.isNotBlank())
            assertFalse(error.name, message.text.contains("_"))
        }
    }

    @Test
    fun `seules deux erreurs ouvrent la page de mise a jour`() {
        val update = DeviceSessionError.values().filter { sessionErrorNeedsGlassesAppUpdate(it) }
        assertEquals(
            setOf(
                DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED,
                DeviceSessionError.INSUFFICIENT_SDK_VERSION,
            ),
            update.toSet(),
        )
    }
}
