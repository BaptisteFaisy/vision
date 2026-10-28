package dev.sam3vision.core.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientSettingsStoreTest {

    private class FakeStore(var settings: Settings = Settings.Default, var fail: Boolean = false) : SettingsStore {
        var loads = 0
        var saves = 0

        override suspend fun load(): Settings {
            loads++
            if (fail) throw IllegalStateException("keystore en panne")
            return settings
        }

        override suspend fun save(s: Settings) {
            saves++
            if (fail) throw IllegalStateException("keystore en panne")
            settings = s
        }
    }

    @Test
    fun `le primaire sert tant qu'il repond`() = runBlocking {
        val primary = FakeStore(Settings.Default.copy(llmModel = "modele"))
        val store = ResilientSettingsStore(primary, FakeStore())
        assertEquals("modele", store.load().llmModel)
        store.save(Settings.Default.copy(llmModel = "autre"))
        assertEquals("autre", primary.settings.llmModel)
    }

    @Test
    fun `le repli prend le relais au premier echec et y reste`() = runBlocking {
        val primary = FakeStore(fail = true)
        val fallback = FakeStore(Settings.Default.copy(llmModel = "repli"))
        val store = ResilientSettingsStore(primary, fallback)
        assertEquals("repli", store.load().llmModel)
        store.save(Settings.Default.copy(llmModel = "sauve"))
        assertEquals("sauve", fallback.settings.llmModel)
        // Le primaire n'est plus sollicité : un seul essai chacun.
        assertEquals(1, primary.loads)
        assertEquals(0, primary.saves)
        assertTrue(fallback.loads >= 1)
    }
}
