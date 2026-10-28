package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleMapsSeedTest {
    private val build = BuildDefaults(googleMapsApiKey = "cle-google-du-build")

    @Test
    fun `une installation neuve et une ancienne installation recoivent la cle du build`() {
        assertEquals("cle-google-du-build", seedGoogleMaps(Settings.Default, build).googleMapsApiKey)
    }

    @Test
    fun `une cle modifiee dans les parametres est conservee`() {
        val saved = Settings.Default.copy(googleMapsApiKey = "ma-cle-carte")
        assertEquals("ma-cle-carte", seedGoogleMaps(saved, build).googleMapsApiKey)
    }

    @Test
    fun `le chemin complet de preparation des reglages remplit aussi Google Maps`() {
        assertEquals("cle-google-du-build", seeded(Settings.Default, build).googleMapsApiKey)
    }
}
