package dev.sam3vision.core.settings

import android.content.Context
import java.io.File

/**
 * Fabrique le store de réglages : security-crypto en priorité, repli sur un
 * fichier privé chiffré maison si le Keystore refuse de s'initialiser.
 * Le repli est silencieux côté UI et documenté dans NOTES.md.
 *
 * Le choix ne se fait pas au constructeur (les prefs chiffrées ne s'ouvrent
 * qu'au premier accès) mais autour de chaque opération, via [ResilientSettingsStore].
 */
object SettingsStoreFactory {

    fun create(context: Context): SettingsStore {
        val app = context.applicationContext
        return ResilientSettingsStore(
            EncryptedSettingsStore(app),
            PrivateFileSettingsStore(File(app.filesDir, "settings.enc")),
        )
    }
}
