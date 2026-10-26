package dev.sam3vision.core.settings

import android.content.Context
import java.io.File

/**
 * Fabrique le store de réglages : security-crypto en priorité, repli sur un
 * fichier privé chiffré maison si le Keystore refuse de s'initialiser.
 * Le repli est silencieux côté UI et documenté dans NOTES.md.
 */
object SettingsStoreFactory {

    fun create(context: Context): SettingsStore = try {
        EncryptedSettingsStore(context)
    } catch (e: Exception) {
        PrivateFileSettingsStore(File(context.applicationContext.filesDir, "settings.enc"))
    }
}
