package dev.sam3vision.core.distance

import dev.sam3vision.core.model.Detection

/**
 * Distance estimée d'un objet de la frame courante.
 *
 * [meters] est la valeur **lissée** (celle qu'on affiche), [rawMeters] la mesure
 * brute de cette frame (celle qu'on journalise), [box] la boîte en pixels de
 * l'image analysée pour retrouver l'objet à l'écran.
 */
data class DistanceReading(
    val trackId: Int,
    val label: String,
    val meters: Float,
    val rawMeters: Float,
    val box: List<Float>,
)

/**
 * Sténopé : `distance = hauteur réelle × focale(px) / hauteur(px) de la boîte`.
 *
 * Une hypothèse, assumée : l'objet est **debout et entier** dans l'image. Un objet
 * coupé par le bord bas paraîtra plus loin, un objet penché plus près — d'où les
 * bornes de plausibilité, qui préfèrent [null] à une valeur absurde.
 */
object Pinhole {

    /** Rien de crédible en deçà (l'objet toucherait l'objectif). */
    const val MIN_METERS = 0.3f

    /** Au-delà, la hauteur en pixels ne porte plus d'information utile. */
    const val MAX_METERS = 60f

    /** Sous cette hauteur de boîte, un pixel d'erreur vaut des mètres. */
    private const val MIN_BOX_PX = 4f

    /** Marge d'un bord d'image : en deçà, la boîte est considérée coupée. */
    const val EDGE_TOLERANCE_PX = 2f

    /**
     * Boîte coupée par le bord **haut ou bas** de l'image : sa hauteur ne mesure plus
     * l'objet, donc la distance serait surestimée (mesuré le 26/10 : une couette mal
     * segmentée en « personne », collée aux deux bords, sortait à 31 m). Les bords
     * gauche/droite ne gênent pas : c'est la hauteur qui porte la mesure.
     */
    fun isVerticallyCut(box: List<Float>, imageHeight: Int, tolerancePx: Float = EDGE_TOLERANCE_PX): Boolean {
        if (box.size < 4 || imageHeight <= 0) return false
        return box[1] <= tolerancePx || box[3] >= imageHeight - tolerancePx
    }

    /** Distance en mètres, `null` si la boîte ou le calibrage ne permet pas de conclure. */
    fun distanceMeters(box: List<Float>, realHeightMeters: Float, focalPx: Float): Float? {
        if (box.size < 4 || focalPx <= 0f || realHeightMeters <= 0f) return null
        val heightPx = box[3] - box[1]
        if (heightPx < MIN_BOX_PX) return null
        val meters = realHeightMeters * focalPx / heightPx
        if (!meters.isFinite() || meters < MIN_METERS || meters > MAX_METERS) return null
        return meters
    }
}

/**
 * Fusion des deux branches de la lane L19 : le **sténopé** ([Pinhole], qui a besoin d'une
 * hauteur de classe connue) et le **plan de sol** ([GroundPlane], qui demande la ligne des
 * pieds et la pose de la caméra).
 *
 * Les deux se trompent dans des cas différents : le sténopé sur un objet allongé ou d'une
 * taille autre que celle de sa classe, le sol sur un objet **hors du sol** (porté, posé sur
 * un meuble). D'où la règle, et une seule :
 *
 * - les deux valeurs **concordent** (rapport ≤ [COHERENCE_RATIO]) : leur moyenne — deux mesures
 *   indépendantes se trompent rarement du même côté, la moyenne resserre les ±15-20 % ;
 * - elles **divergent** : une hypothèse est cassée, sans qu'on sache laquelle ; le sténopé
 *   garde la main — c'est la branche validée sur le téléphone, elle ne dépend ni de la pose ni
 *   du fait que l'objet soit posé ;
 * - une seule branche parle (classe inconnue, pas de pose réglée): c'est elle, sans invention.
 */
object DistanceFusion {

    /** Au-delà, les deux branches ne parlent plus du même objet. */
    const val COHERENCE_RATIO = 1.5f

    /** Distance fusionnée, `null` si aucune branche n'a de valeur. */
    fun combine(standingMeters: Float?, groundMeters: Float?): Float? {
        if (standingMeters == null) return groundMeters
        if (groundMeters == null) return standingMeters
        if (ratio(standingMeters, groundMeters) > COHERENCE_RATIO) return standingMeters
        return (standingMeters + groundMeters) / 2f
    }

    /** Écart relatif entre deux mesures, ≥ 1 (la plus grande sur la plus petite). */
    private fun ratio(first: Float, second: Float): Float =
        if (first >= second) first / second else second / first
}

/**
 * Mesure des distances du direct : calibrage caméra ([LensSpec]), tailles connues
 * ([KnownSizes]), plan de sol facultatif ([GroundPlane]) et lissage par piste
 * ([DistanceSmoother]).
 *
 * Sans objet mesurable dans la frame, [measure] renvoie une liste vide : l'overlay
 * n'affiche alors rien du tout.
 */
class DistanceMeter(
    private var lens: LensSpec = LensSpec.Default,
    private val smoother: DistanceSmoother = DistanceSmoother(),
) {

    /** Coupé par le réglage utilisateur : aucune mesure, aucun coût. */
    var enabled: Boolean = true

    /**
     * Pose de la caméra pour la mesure au sol (Réglages) ; `null` par défaut : seule la
     * branche sténopé travaille alors, exactement comme avant le plan de sol. Une pose
     * inexploitable est ignorée par [GroundPlane], jamais devinée.
     */
    var pose: CameraPose? = null

    /** Calibrage courant (celui du capteur, sinon le réglage). */
    val lensSpec: LensSpec get() = lens

    /** Applique un calibrage plausible ; un [spec] inutilisable laisse l'ancien en place. */
    fun setLens(spec: LensSpec?) {
        if (spec != null && spec.usable) lens = spec
    }

    /** Oublie les valeurs lissées (nouvelle session de direct). */
    fun reset() = smoother.reset()

    /**
     * Une lecture par objet exploitable, dans l'ordre de [detections]. [trackIds]
     * porte l'identité stable du journal du direct (`null` = objet non identifié :
     * la mesure n'est alors pas lissée, mais elle est quand même rendue).
     */
    fun measure(
        width: Int,
        height: Int,
        detections: List<Detection>,
        trackIds: List<Int?> = emptyList(),
    ): List<DistanceReading> {
        if (!enabled) return emptyList()
        val focalPx = lens.focalPx(width, height)
        if (focalPx <= 0f) return emptyList()
        return detections.mapIndexedNotNull { index, detection ->
            readOne(height, detection, trackIds.getOrNull(index), focalPx)
        }
    }

    private fun readOne(
        height: Int,
        detection: Detection,
        trackId: Int?,
        focalPx: Float,
    ): DistanceReading? {
        if (Pinhole.isVerticallyCut(detection.box, height)) return null
        val raw = rawMeters(height, detection, focalPx) ?: return null
        val id = trackId ?: UNTRACKED_ID
        val smoothed = if (trackId == null) raw else smoother.smooth(trackId, raw)
        return DistanceReading(id, detection.label, smoothed, raw, detection.box)
    }

    /**
     * La mesure brute d'une frame : les deux branches de la lane, fusionnées par
     * [DistanceFusion]. Un objet sans hauteur de classe connue peut donc être mesuré s'il est
     * posé au sol et que la pose est réglée — sans jamais inventer de chiffre.
     */
    private fun rawMeters(height: Int, detection: Detection, focalPx: Float): Float? {
        val standing = KnownSizes.heightMeters(detection.label)
            ?.let { Pinhole.distanceMeters(detection.box, it, focalPx) }
        return DistanceFusion.combine(standing, groundMeters(detection.box, focalPx, height))
    }

    /**
     * Plan de sol : la ligne des **pieds** (bas de la boîte) donne la distance sans rien savoir
     * de la classe. Le centre optique est supposé au centre de l'image — l'hypothèse déjà faite
     * par le reste du fichier — et la ligne des pieds vient d'une boîte non coupée : c'est
     * [Pinhole.isVerticallyCut], en amont, qui l'a garanti.
     */
    private fun groundMeters(box: List<Float>, focalPx: Float, imageHeight: Int): Float? {
        val current = pose ?: return null
        if (box.size < 4) return null
        return GroundPlane.forwardMeters(box[3], current, focalPx, imageHeight / 2f, imageHeight)
    }

    companion object {
        /** Identité des objets que le journal n'a pas encore identifiés. */
        const val UNTRACKED_ID = -1
    }
}