package dev.sam3vision.core.motion

import dev.sam3vision.core.mask.GrayMask

/**
 * Source du mouvement inter-frame : c'est la **couture produite** de la lane L13
 * (`docs/L13-FUSION.md` §3), implémentée par [BlockMotionSource]. Le calque (u13)
 * décidera *quand* propager et *quoi* dessiner ; il ne fera aucune estimation, il
 * appellera cette interface — **ce branchement n'est pas fait** : `LocalOverlayEngine`
 * consomme encore `core.local.MotionSource` (`ByteArray` → `MaskMotion`), et l'écran
 * reste inerte en production.
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
