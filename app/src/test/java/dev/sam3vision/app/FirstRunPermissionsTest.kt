package dev.sam3vision.app

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Les quatre permissions runtime d'iSee, groupees et filtrees au premier lancement. */
class FirstRunPermissionsTest {

    @Test
    fun `les quatre permissions sont groupees en une seule demande`() {
        assertEquals(
            setOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            IseePermissions.toSet(),
        )
    }

    @Test
    fun `aucune permission manquante quand tout est accorde`() {
        assertEquals(
            emptyList<String>(),
            IseePermissions.missingFrom(IseePermissions.toSet()),
        )
    }

    @Test
    fun `les permissions accordees sont sorties de la demande`() {
        val granted = setOf(Manifest.permission.CAMERA)
        assertEquals(
            listOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            IseePermissions.missingFrom(granted),
        )
    }
}
