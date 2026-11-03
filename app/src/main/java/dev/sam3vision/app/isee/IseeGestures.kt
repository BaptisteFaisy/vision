package dev.sam3vision.app.isee

import dev.sam3vision.app.isee.vm.IseeSwipeOrders

/**
 * Les gestes de la surface iSee, en un seul relais : appui long (question), tap
 * (vidéo et quadruple tap pour le lieu), et les trois balayages — vers le haut
 * (Traversée), vers le bas (fonction GPS, + maintien : dicter la destination),
 * vers la droite (création d'un picto, comme le bouton). La gauche (lunettes)
 * vit dans `MainActivity`, seule à voir les lunettes.
 *
 * Extrait d'`IseeViewModel` (garde 500 lignes) et des extensions de
 * `IseeSteeringOrders` : l'appelant n'a qu'un point d'entrée
 * (`isee.gestures::onSwipeDown`), chaque geste garde son exécutant. Tout est
 * injecté en lambdas (tenue comprise) : le routage se teste en JVM, sans
 * Android (voir `IseeGesturesTest`).
 */
class IseeGestures internal constructor(
    private val holdStart: () -> Unit,
    private val holdEnd: () -> Unit,
    private val holdCancel: () -> Unit,
    private val singleTap: () -> Unit,
    private val swipe: IseeSwipeOrders,
    /** Bascule Traversée du balayage vers le haut (la puce a la même). */
    private val toggleCrossing: () -> Unit,
    /** Création d'un picto du balayage vers la droite (le bouton a la même). */
    private val createPicto: () -> Unit,
) {

    fun onHoldStart() = holdStart()

    fun onHoldEnd() = holdEnd()

    fun onHoldCancel() = holdCancel()

    fun onSingleTap() = singleTap()

    /** Balayage vers le haut : même bascule Traversée que la puce. */
    fun onSwipeUp() = toggleCrossing()

    /** Balayage vers le bas : « active le GPS », comme l'ordre vocal. */
    fun onSwipeDown() = swipe.gpsOn()

    /** Maintien après balayage vers le bas : ouvrir le micro destination. */
    fun onSwipeHoldStart() = swipe.askDestination()

    /** Relâchement du maintien : envoyer l'endroit dicté en destination. */
    fun onSwipeHoldEnd() = swipe.sendDestination()

    /** Maintien annulé : micro relâché, rien de transcrit. */
    fun onSwipeHoldCancel() = swipe.cancelDestination()

    /** Balayage vers la droite : même création picto que le bouton. */
    fun onSwipeRight() = createPicto()
}
