package dev.sam3vision.core.exposure

/** Point de mesure normalisé (0..1) : centre et côté de la région, tous deux relatifs au cadre. */
data class MeterPoint(val x: Float, val y: Float, val size: Float)

/**
 * Deux points de mesure AE pour viser la lampe sans sacrifier la scène : la **lampe**
 * (petite région centrée sur la boîte du feu) et le **plein cadre**. CameraX attribue à
 * chaque région le même poids maximal (`METERING_WEIGHT_MAX`) : le poids relatif ne peut
 * donc venir que de la **taille** de la région — d'où les deux points, un seul point à
 * poids maximal ferait un spot-métérage intégral et la scène s'effondrerait (SAM ne
 * segmenterait plus, et la mesure deviendrait un aller-retour qui oscille).
 *
 * La boîte vient de `CrossingPrompts.selectLightBoxes` (normalisée 0..1 sur la frame
 * **redressée**) : le JPEG 480 px part déjà tourné, donc ces coordonnées sont aussi
 * celles de la surface d'aperçu — c'est ce que consomme
 * `SurfaceOrientedMeteringPointFactory`, aucun calcul de rotation à refaire ici.
 *
 * Pur JVM, testé : boîte illisible (taille ≠ 4, non finie, aire nulle) ⇒ `null`, et
 * l'appelant n'arme **rien** plutôt que d'armer un point au hasard.
 */
object FireMetering {

    /** Côté de la région lampe (10 % du cadre) : la lampe occupe ~5 à 20 % de sa boîte de crop. */
    const val LAMP_SIZE = 0.10f

    /** Côté de la région plein cadre (100 %) : garde la scène exposée, SAM continue de segmenter. */
    const val SCENE_SIZE = 1.0f

    /**
     * Points AE `[lampe, plein cadre]` pour [box] (`x1,y1,x2,y2`, 0..1), `null` si la
     * boîte n'est pas exploitable. Le centre est borné à 0..1 (une boîte à moitié hors
     * champ reste mesurable au bord) mais on ne l'invente jamais.
     */
    fun points(box: List<Float>?): List<MeterPoint>? {
        if (box == null || box.size != 4) return null
        if (box.any { !it.isFinite() }) return null
        if (box[2] <= box[0] || box[3] <= box[1]) return null
        val cx = ((box[0] + box[2]) / 2f).coerceIn(0f, 1f)
        val cy = ((box[1] + box[3]) / 2f).coerceIn(0f, 1f)
        return listOf(
            MeterPoint(cx, cy, LAMP_SIZE),
            MeterPoint(0.5f, 0.5f, SCENE_SIZE),
        )
    }

    /** Distance de déplacement (0..1) entre deux centres : au-delà de 0,02 on ré-arme. */
    fun moved(from: MeterPoint?, to: MeterPoint?): Float {
        if (from == null || to == null) return 1f
        val dx = to.x - from.x
        val dy = to.y - from.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
