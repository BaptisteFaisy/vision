package dev.sam3vision.app.isee

import dev.sam3vision.core.picto.PictoPlaceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Témoins tactiles de la rue (Traversée, lieu) : leurs zones d'exemption et
 * leurs bascules, extraits du ViewModel (limite 500 lignes). Les bascules
 * rejouent les chemins vocaux : Traversée via [onToggleCrossing], lieu via la
 * phrase canonique de l'ordre vocal. Pur côté décision, testé en JVM.
 */
class IseeModeChips(
    private val scope: CoroutineScope,
    private val placeMode: () -> PictoPlaceMode,
    private val runMode: suspend (String) -> Boolean,
    private val onToggleCrossing: () -> Unit,
) {
    /** Zone tactile publiée par la puce Traversée (exemption d'interception racine). */
    val crossing = CrossingChipZone()

    /** Zone tactile publiée par la puce lieu (exemption d'interception racine). */
    val place = PlaceModeChipZone()

    /** Bascule du mode Traversée : mêmes masques, voix traversée ou retour. */
    fun toggleCrossing() = onToggleCrossing()

    /** Bascule dedans/dehors : rejoue la phrase canonique — même état, même synchro sonde, même confirmation. */
    fun togglePlace() {
        scope.launch {
            val target = if (placeMode() == PictoPlaceMode.DEDANS) "mode dehors" else "mode dedans"
            runMode(target)
        }
    }
}
