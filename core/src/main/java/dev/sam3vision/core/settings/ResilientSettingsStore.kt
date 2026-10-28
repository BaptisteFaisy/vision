package dev.sam3vision.core.settings

import kotlinx.coroutines.CancellationException

/**
 * Store qui ne plante jamais l'appelant : le [primary] (chiffré) est essayé
 * d'abord, le [fallback] (fichier) prend le relais dès le premier échec et
 * reste en service — inutile de retenter un Keystore en panne à chaque accès.
 *
 * Le test du constructeur seul ne suffit pas (`EncryptedSettingsStore` n'ouvre
 * ses prefs qu'au premier accès) : c'est ici, autour de chaque opération, que
 * le repli a lieu. Testé en JVM avec des stores factices.
 */
class ResilientSettingsStore(
    private val primary: SettingsStore,
    private val fallback: SettingsStore,
) : SettingsStore {

    @Volatile
    private var degraded = false

    override suspend fun load(): Settings {
        if (degraded) return fallback.load()
        return try {
            primary.load()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            degraded = true
            fallback.load()
        }
    }

    override suspend fun save(s: Settings) {
        if (degraded) {
            fallback.save(s)
            return
        }
        try {
            primary.save(s)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            degraded = true
            fallback.save(s)
        }
    }
}
