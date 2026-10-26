package dev.sam3vision.core.motion

import dev.sam3vision.core.mask.GrayMask

/**
 * Source du mouvement inter-frame du calque local : c'est la **couture** de la lane L13
 * (`docs/L13-FUSION.md` §3). Le calque (u13) décide *quand* propager et *quoi* dessiner ;
 * il ne fait aucune estimation, il appelle cette interface.
 *
 * Les deux images arrivent dans le conteneur 8 bits des masques ([GrayMask]) : la luminance
 * du direct est un plan d'octets comme un masque, un type neuf ne servirait qu'à recopier
 * `levelAt`. Elles doivent avoir la **même grille** (le plan de référence 256×103).
 */
interface MotionSource {

    /** Mouvement qui amène [prevLuma] sur [curLuma], ou `null` s'il n'y a pas d'avis à donner. */
    fun estimateSince(prevLuma: GrayMask, curLuma: GrayMask): MotionSample?
}

/**
 * Source **inerte**, le défaut de l'arbre : le calque reste sur `LocalReason.NO_ESTIMATOR`
 * (écran identique à avant la lane) plutôt que de warper sur un mouvement inventé. C'est
 * aussi ce que reçoit `LiveViewModel.onFrame` quand l'appelant n'a pas de luminance.
 */
object NoMotion : MotionSource {
    override fun estimateSince(prevLuma: GrayMask, curLuma: GrayMask): MotionSample? = null
}
