package dev.sam3vision.core.local

import dev.sam3vision.core.mask.MaskMotion

/**
 * Couture vers l'estimateur de mouvement **consommée par [LocalOverlayEngine]**
 * (`setMotion`) : le mouvement rendu est un [MaskMotion] — le type de la lane B,
 * qui sert déjà au warp du masque — exprimé dans le **plan de référence**
 * [LumaPlane] (pixels de 256×103), l'appelant le remet à l'échelle du plan masque
 * avec [MaskMotion.scaledBy].
 *
 * Aucune géométrie n'est réécrite ici : [MaskMotion] porte déjà la similitude
 * (translation + échelle autour d'un centre), ses bornes d'échelle et son inversion.
 * Le calque (agent D/u13) ne fait **aucune** estimation : il consomme ce que produit
 * cette couture, et reste inerte tant qu'elle n'est pas branchée ([NoMotion]).
 *
 * Ne pas confondre avec `core.motion.MotionSource` (lane L13, agent A/u10) : c'est
 * la couture **produite** par l'estimateur `BlockMotionSource` (`GrayMask` →
 * `MotionSample`), testée mais pas encore branchée au calque. La fusion des deux
 * coutures (`docs/L13-FUSION.md` §3), c'est activer la propagation : un changement
 * de comportement, pas un nettoyage.
 *
 * Pur JVM : aucun Android, donc testable sans appareil.
 */
fun interface MotionSource {
    /**
     * Mouvement entre le keyframe de référence ([prevLuma]) et la frame courante
     * ([curLuma]) ; `null` quand l'estimation est impossible (plan absent, scène coupée).
     */
    fun estimateSince(prevLuma: ByteArray?, curLuma: ByteArray?): MaskMotion?
}

/**
 * Estimateur absent : le calque reste **inerte** (`LocalReason.NO_ESTIMATOR`) et l'écran
 * se comporte exactement comme avant. C'est l'état de cet arbre : l'estimateur existe
 * (`core.motion.BlockMotionSource`, testé) mais n'est pas branché au calque — aucune
 * propagation n'est simulée pour faire croire à un gain.
 */
object NoMotion : MotionSource {
    override fun estimateSince(prevLuma: ByteArray?, curLuma: ByteArray?): MaskMotion? = null
}

/**
 * Plan de luminance de référence : 256×103, soit la moitié d'une frame 512×206. Une seule
 * définition, partagée par le producteur (l'app, depuis le bitmap **déjà tourné** de
 * l'analyseur) et par les estimateurs — jamais le plan Y du NV21, qui n'est pas tourné.
 */
object LumaPlane {
    const val WIDTH = 256
    const val HEIGHT = 103

    /** Nombre d'octets d'un plan complet. */
    const val SIZE = WIDTH * HEIGHT
}