package dev.sam3vision.core.traverse

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconnaissance des alertes mouvement ([VehicleStateText.isSafetyPhrase]). */
class VehicleStateTextTest {

    @Test
    fun `alerte securite reconnue en anglais et francais`() {
        assertTrue(VehicleStateText.isSafetyPhrase("Stop, car moving."))
        assertTrue(VehicleStateText.isSafetyPhrase("Stop, voiture en mouvement."))
        assertTrue(VehicleStateText.isSafetyPhrase("Stop, 2 vehicles moving."))
        assertTrue(VehicleStateText.isSafetyPhrase("Stop, 3 véhicules en mouvement."))
        assertFalse("bus hors liste dehors", VehicleStateText.isSafetyPhrase("Stop, bus moving."))
        assertFalse("camion hors liste dehors", VehicleStateText.isSafetyPhrase("Stop, camion en mouvement."))
    }

    @Test
    fun `phrases douces et stop seul ne sont pas des alertes mouvement`() {
        assertFalse(VehicleStateText.isSafetyPhrase("Car moving."))
        assertFalse(VehicleStateText.isSafetyPhrase("2 vehicles moving."))
        assertFalse(VehicleStateText.isSafetyPhrase("Car stopped, 2 meters away."))
        assertFalse(VehicleStateText.isSafetyPhrase("Stop."))
        assertFalse(VehicleStateText.isSafetyPhrase("obstacle, stop."))
        assertFalse(VehicleStateText.isSafetyPhrase("Rouge."))
    }

    /**
     * Ordre user du 01/11/2026 : « ne me dis plus l'emplacement de la voiture
     * si tu vois une voiture qui bouge ». Un mobile ne se situe jamais — ni
     * distance, ni heure ; les immobiles (arrêt, stationnement), eux, gardent
     * leur emplacement, qui ne bouge pas.
     */
    @Test
    fun `mouvement jamais situe - arret et stationnement si`() {
        val car = VehicleStateText.nameOf("car")!!

        // Mouvement : phrase nue, singulier et pluriel, FR et EN.
        assertEquals("Voiture.", VehicleStateText.movingPhrase(car, SpeechLanguage.Fr))
        assertEquals("Car.", VehicleStateText.movingPhrase(car, SpeechLanguage.En))
        assertEquals("Voitures.", VehicleStateText.movingPluralPhrase(2, SpeechLanguage.Fr))
        assertEquals("Cars.", VehicleStateText.movingPluralPhrase(2, SpeechLanguage.En))

        // Immobiles : emplacement conservé (distance mesurée, heure sinon).
        assertEquals(
            "Voiture à l'arrêt, à 2 mètres.",
            VehicleStateText.stoppedPhrase(car, 2f, null, SpeechLanguage.Fr),
        )
        assertEquals(
            "Car stopped, at 12 o'clock.",
            VehicleStateText.stoppedPhrase(car, null, 0.5f, SpeechLanguage.En),
        )
        assertEquals(
            "Voiture en stationnement, à 8 mètres.",
            VehicleStateText.parkedPhrase(car, 8f, null, SpeechLanguage.Fr),
        )
    }

    /**
     * La phrase fixe du mouvement (et le stop) est **enregistrée** : le pack
     * voix la joue sans réseau (ordre user du 01/11). Ce qui porte encore une
     * mesure (arrêt, stationnement) ou un compte (pluriel) reste en synthèse.
     */
    @Test
    fun `mouvements et stops enregistres - le reste en synthese`() {
        val car = VehicleStateText.nameOf("car")!!
        assertEquals("car", VehicleStateText.clipIdFor("Voiture."))
        assertEquals("car", VehicleStateText.clipIdFor("Car."))
        assertNull("bus hors liste dehors", VehicleStateText.clipIdFor("Bus moving."))
        assertEquals("car", VehicleStateText.movingClipId(car))
        assertEquals("cars", VehicleStateText.clipIdFor("Cars."))
        assertNull("le stop part en voix de sécurité, pas en clip", VehicleStateText.clipIdFor("Stop, car moving."))
        assertNull(VehicleStateText.clipIdFor("Voiture à l'arrêt, à 2 mètres."))
        assertNull(VehicleStateText.clipIdFor("Voiture en stationnement, à 8 mètres."))
    }
}
