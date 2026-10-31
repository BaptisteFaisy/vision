package dev.sam3vision.core.traverse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconnaissance des alertes mouvement ([VehicleStateText.isSafetyPhrase]). */
class VehicleStateTextTest {

    @Test
    fun `alerte securite reconnue en anglais et francais`() {
        assertTrue(VehicleStateText.isSafetyPhrase("Stop, car moving."))
        assertTrue(VehicleStateText.isSafetyPhrase("Stop, bus moving."))
        assertTrue(VehicleStateText.isSafetyPhrase("Stop, voiture en mouvement."))
        assertTrue(VehicleStateText.isSafetyPhrase("Stop, camion en mouvement."))
        assertTrue(VehicleStateText.isSafetyPhrase("Stop, 2 vehicles moving."))
        assertTrue(VehicleStateText.isSafetyPhrase("Stop, 3 véhicules en mouvement."))
    }

    @Test
    fun `phrases douces et stop seul ne sont pas des alertes mouvement`() {
        assertFalse(VehicleStateText.isSafetyPhrase("Car moving, 5 meters away."))
        assertFalse(VehicleStateText.isSafetyPhrase("2 vehicles moving, 5 meters away."))
        assertFalse(VehicleStateText.isSafetyPhrase("Car stopped, 2 meters away."))
        assertFalse(VehicleStateText.isSafetyPhrase("Stop."))
        assertFalse(VehicleStateText.isSafetyPhrase("obstacle, stop."))
        assertFalse(VehicleStateText.isSafetyPhrase("Rouge, attendez."))
    }
}
