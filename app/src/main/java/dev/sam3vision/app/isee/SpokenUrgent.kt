package dev.sam3vision.app.isee

import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.traverse.VehicleStateText

/**
 * Urgence d'une phrase parlée : le mot stop (guidage, ou « Stop. » de
 * l'urgence externe — [GuidanceWord.isStopPhrase]) ou une alerte
 * mouvement (« Stop, car moving. » — [VehicleStateText.isSafetyPhrase],
 * Traversée seule depuis l'ordre user du 31/10). Pure, testée en JVM.
 */
internal fun spokenUrgentOf(phrase: String): Boolean =
    GuidanceWord.isStopPhrase(phrase) || VehicleStateText.isSafetyPhrase(phrase)
