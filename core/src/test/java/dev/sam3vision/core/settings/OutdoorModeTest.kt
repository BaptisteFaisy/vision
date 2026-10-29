package dev.sam3vision.core.settings

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Monde dehors/dedans : `true` à l'installation, persisté comme les autres
 * drapeaux — la bascule survit au kill de l'app (relecture sur un nouveau
 * store, comme après un redémarrage).
 */
class OutdoorModeTest {

    private val dossier = Files.createTempDirectory("isee-outdoor-mode").toFile()
    private val fichier = File(dossier, "settings.enc")
    private val chiffre = TestCipher(ByteArray(32) { (it * 3 + 1).toByte() })

    @Test
    fun `dehors par defaut a l installation`() {
        assertTrue(Settings.Default.outdoorMode)
    }

    @Test
    fun `le codec restitue le monde dedans`() {
        val relu = SettingsCodec.decode(SettingsCodec.encode(Settings.Default.copy(outdoorMode = false)))

        assertFalse(relu.outdoorMode)
    }

    @Test
    fun `le monde dedans survit a un redemarrage simule`() = runBlocking {
        PrivateFileSettingsStore(fichier, chiffre).save(Settings.Default.copy(outdoorMode = false))

        // Reboot simulé : nouvelle instance, même fichier.
        val relu = PrivateFileSettingsStore(fichier, chiffre).load()

        assertFalse(relu.outdoorMode)
    }

    @Test
    fun `un ancien fichier sans la cle retombe sur dehors`() = runBlocking {
        val herite = "llmApiKey=cle-de-l-epoque\n"
        fichier.writeBytes(chiffre.encrypt(herite.toByteArray(Charsets.UTF_8)))

        assertTrue(PrivateFileSettingsStore(fichier, chiffre).load().outdoorMode)
    }
}
