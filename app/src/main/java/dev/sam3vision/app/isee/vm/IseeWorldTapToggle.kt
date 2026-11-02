package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.gesture.QuadrupleTap
import dev.sam3vision.core.live.WorldModeSpeech
import dev.sam3vision.core.picto.PictoPlaceMode

/**
 * Quadruple tap de l'accueil iSee : 4 taps en moins de 3 s inversent le
 * lieu (dedans ↔ dehors) par le même [apply] que l'ordre vocal « mode … »
 * ([PlaceModeOrder.apply]) — seule l'annonce diffère, imposée par le
 * produit (« indoor mode » / « outdoor mode »). Nourri par
 * `IseeViewModel.onSingleTap`.
 */
class IseeWorldTapToggle(
    private val placeMode: () -> PictoPlaceMode,
    /** Application du lieu + annonce (même chemin que la dictée). */
    private val apply: suspend (PictoPlaceMode, String) -> Unit,
    private val taps: QuadrupleTap = QuadrupleTap(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Un tap simple : hors rafale complète, `null` (le tap garde son
     * effet normal chez l'appelant) ; au 4e, l'action suspendue qui
     * bascule et annonce — l'appelant la lance dans son scope.
     */
    fun onTap(): (suspend () -> Unit)? {
        if (!taps.onTap(clock())) return null
        val next = if (placeMode() == PictoPlaceMode.DEDANS) PictoPlaceMode.DEHORS else PictoPlaceMode.DEDANS
        val text = WorldModeSpeech.announcement(next == PictoPlaceMode.DEHORS)
        return { apply(next, text) }
    }

    /** Une tenue coupe la rafale : les taps suivants n'enchaînent plus. */
    fun reset() {
        taps.reset()
    }
}
