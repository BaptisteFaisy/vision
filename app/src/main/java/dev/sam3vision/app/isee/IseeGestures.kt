package dev.sam3vision.app.isee

import dev.sam3vision.app.isee.vm.IseeSwipeOrders

/**
 * Les gestes de la surface iSee, en un seul relais : appui long (question), tap
 * (vidéo et quadruple tap pour le lieu), et les trois balayages — vers le haut
 * (Traversée), vers le bas (fonction GPS), vers la droite (« Quoi chercher ? »).
 *
 * Extrait d'`IseeViewModel` (garde 500 lignes) et des extensions de
 * `IseeSteeringOrders` : l'appelant n'a qu'un point d'entrée
 * (`isee.gestures::onSwipeDown`), chaque geste garde son exécutant.
 */
class IseeGestures internal constructor(
    private val hold: IseeHoldTalk,
    private val swipe: IseeSwipeOrders,
    /** Bascule Traversée du balayage vers le haut (la puce a la même). */
    private val toggleCrossing: () -> Unit,
) {

    fun onHoldStart() = hold.start()

    fun onHoldEnd() = hold.end()

    fun onHoldCancel() = hold.cancel()

    fun onSingleTap() = hold.singleTap()

    /** Balayage vers le haut : même bascule Traversée que la puce. */
    fun onSwipeUp() = toggleCrossing()

    /** Balayage vers le bas : « active le GPS », comme l'ordre vocal. */
    fun onSwipeDown() = swipe.gpsOn()

    /** Balayage vers la droite : iSee demande l'objet, puis chasse. */
    fun onSwipeRight() = swipe.askToFind()
}
