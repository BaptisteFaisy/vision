package dev.sam3vision.core.distance

import dev.sam3vision.core.model.Detection
import kotlin.jvm.Volatile

/**
 * Distance estimée d'un objet de la frame courante.
 *
 * [meters] est la valeur **lissée** (celle qu'on affiche), [rawMeters] la mesure
 * brute de cette frame (celle qu'on journalise), [box] la boîte en pixels de
 * l'image analysée pour retrouver l'objet à l'écran, [confidence] la confiance
 * de la mesure brute (voir [ReadingConfidence]).
 *
 * [cutFallback] : repli bas-au-contact **historique** — plus produit depuis le
 * P0 point 7 (une boîte coupée par le bas n'a plus de distance, voir
 * [DistanceMeter.measure]). Le champ reste : la calibration profondeur le filtre
 * (`DepthReadings` : un repli ne fait jamais une ancre) et une lecture venue
 * d'ailleurs peut encore le porter.
 */
data class DistanceReading(
    val trackId: Int,
    val label: String,
    val meters: Float,
    val rawMeters: Float,
    val box: List<Float>,
    val confidence: Float = 1f,
    val cutFallback: Boolean = false,
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
        return isCutTop(box, tolerancePx) || isCutBottom(box, imageHeight, tolerancePx)
    }

    /**
     * Bord haut coupé : la hauteur ne mesure plus l'objet (tête hors cadre).
     * Refusé, comme le bord bas — aucune des deux coupes n'a de mesure.
     */
    fun isCutTop(box: List<Float>, tolerancePx: Float = EDGE_TOLERANCE_PX): Boolean {
        if (box.size < 4) return false
        return box[1] <= tolerancePx
    }

    /**
     * Bord bas coupé : les pieds sont sous le cadre, donc la hauteur de la boîte
     * ne mesure plus l'objet **et** la ligne des pieds n'est plus dans l'image —
     * les deux branches de la lane sont muettes, la mesure est refusée (voir
     * [DistanceMeter.measure]).
     */
    fun isCutBottom(box: List<Float>, imageHeight: Int, tolerancePx: Float = EDGE_TOLERANCE_PX): Boolean {
        if (box.size < 4 || imageHeight <= 0) return false
        return box[3] >= imageHeight - tolerancePx
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
 * - les deux valeurs **concordent** (rapport ≤ [COHERENCE_RATIO]) : leur moyenne
 *   **pondérée** — deux mesures indépendantes se trompent rarement du même côté ;
 *   le sténopé pèse [KnownSizes.fusionWeight] (0,77-0,97 selon sa classe), le sol
 *   [GroundWeight] (0,25-1 selon la dépression des pieds) : de près le sol tire,
 *   de loin le sténopé reprend la main ;
 * - elles **divergent** : une hypothèse est cassée, sans qu'on sache laquelle ; le sténopé
 *   garde la main — c'est la branche validée sur le téléphone, elle ne dépend ni de la pose ni
 *   du fait que l'objet soit posé, et les poids ne changent rien à cette prudence ;
 * - une seule branche parle (classe inconnue, pas de pose réglée): c'est elle, sans invention.
 */
object DistanceFusion {

    /** Au-delà, les deux branches ne parlent plus du même objet. */
    const val COHERENCE_RATIO = 1.5f

    /**
     * Distance fusionnée, `null` si aucune branche n'a de valeur. Les poids ne
     * jouent que sur la moyenne cohérente ; en divergence, le sténopé garde la
     * main (la pose du sol peut être fausse, le sténopé est la branche validée).
     */
    fun combine(
        standingMeters: Float?,
        groundMeters: Float?,
        standingWeight: Float = 1f,
        groundWeight: Float = 1f,
    ): Float? {
        if (standingMeters == null) return groundMeters
        if (groundMeters == null) return standingMeters
        if (ratio(standingMeters, groundMeters) > COHERENCE_RATIO) return standingMeters
        val standing = standingWeight.takeIf { it.isFinite() && it > 0f } ?: 1f
        val ground = groundWeight.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (standingMeters * standing + groundMeters * ground) / (standing + ground)
    }

    /** Écart relatif entre deux mesures, ≥ 1 (la plus grande sur la plus petite). */
    private fun ratio(first: Float, second: Float): Float =
        if (first >= second) first / second else second / first
}

/**
 * Mesure des distances du direct : calibrage caméra ([LensSpec]), tailles connues
 * ([KnownSizes]), plan de sol facultatif ([GroundPlane]) et suivi par piste
 * ([DistanceKalman], [DistanceSmoother] en repli).
 *
 * Sans objet mesurable dans la frame, [measure] renvoie une liste vide : l'overlay
 * n'affiche alors rien du tout.
 */
class DistanceMeter(
    private var lens: LensSpec = LensSpec.Default,
    private val smoother: DistanceSmoother = DistanceSmoother(),
    private val kalman: DistanceKalman = DistanceKalman(),
) {

    /** Coupé par le réglage utilisateur : aucune mesure, aucun coût. */
    var enabled: Boolean = true

    /** Suivi Kalman (défaut) ou EMA historique : le repli ne prédit pas ([predictions]). */
    var kalmanEnabled: Boolean = true

    /**
     * Pose de la caméra pour la mesure au sol (IMU temps réel, `null` sans capteur) ;
     * `null` : seule la branche sténopé travaille. Volatile : écrite par le thread
     * capteur, lue par le thread des frames. Une pose inexploitable est ignorée par
     * [GroundPlane], jamais devinée.
     */
    @Volatile
    var pose: CameraPose? = null

    /** Calibrage courant (celui du capteur, sinon le réglage). */
    val lensSpec: LensSpec get() = lens

    /** Non-suivis de la dernière mesure, maintenus par [predictions] jusqu'à l'oubli. */
    private var untracked: List<DistanceReading> = emptyList()
    private var untrackedAtMs: Long = 0L

    /** Applique un calibrage plausible ; un [spec] inutilisable laisse l'ancien en place. */
    fun setLens(spec: LensSpec?) {
        if (spec != null && spec.usable) lens = spec
    }

    /** Oublie pistes, maintien et valeurs lissées (nouvelle session de direct). */
    fun reset() {
        smoother.reset()
        kalman.reset()
        untracked = emptyList()
    }

    /**
     * Distances à [atMs] pour les objets connus, sans mesure nouvelle : pistes
     * Kalman extrapolées par leur vitesse + objets non suivis maintenus tels quels
     * (la sonde ne suit pas : sans maintien, ses pastilles s'éteindraient entre deux
     * inférences). L'info reste fraîche entre deux frames (à 1 image/s, un tick à
     * 80 ms donne ~12 rafraîchissements). Vide sans Kalman ([kalmanEnabled]), sans
     * objet vivant, ou mesures coupées. Confiance dégradée par l'âge, oubli au
     * [DistanceKalman.timeoutMs] — jamais de prédiction au-delà.
     */
    fun predictions(atMs: Long = System.currentTimeMillis()): List<DistanceReading> {
        if (!enabled || !kalmanEnabled) return emptyList()
        val tracked = kalman.predictions(atMs).map {
            DistanceReading(it.trackId, it.label, it.meters, it.meters, it.box, it.confidence)
        }
        return tracked + heldUntracked(atMs)
    }

    /**
     * Objets non suivis de la dernière mesure, maintenus jusqu'à l'oubli (même
     * horizon que le Kalman) : pas de vitesse estimée, valeur figée, confiance
     * dégradée comme une prédiction.
     */
    private fun heldUntracked(atMs: Long): List<DistanceReading> {
        if (untracked.isEmpty()) return emptyList()
        val age = atMs - untrackedAtMs
        if (age < 0 || age > kalman.timeoutMs) return emptyList()
        val factor = (1f - age.toFloat() / kalman.timeoutMs)
            .coerceIn(DistanceKalman.MIN_PREDICT_CONFIDENCE, 1f)
        return untracked.map {
            it.copy(confidence = (it.confidence * factor).coerceIn(DistanceKalman.MIN_PREDICT_CONFIDENCE, 1f))
        }
    }

    /**
     * Une lecture par objet exploitable, dans l'ordre de [detections]. [trackIds]
     * porte l'identité stable du journal du direct (`null` = objet non identifié :
     * la mesure n'est alors pas lissée, mais elle est quand même rendue).
     * Les boîtes masquées par une meilleure détection ([Occlusion]) sont écartées :
     * leur hauteur ne mesure plus l'objet.
     * Une boîte coupée par le bord haut **ou bas** est écartée : sa hauteur ne
     * mesure plus l'objet et, coupée en bas, sa ligne de pieds n'est plus dans
     * l'image — aucune des deux branches de la lane ne peut conclure (voir [readOne]).
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
        val (principalX, principalY) = lens.principalPx(width, height)
        val readings = detections.mapIndexedNotNull { index, detection ->
            if (Occlusion.isOccluded(detections, index)) null
            else readOne(width, height, detection, trackIds.getOrNull(index), focalPx, principalX, principalY)
        }
        untracked = readings.filter { it.trackId == UNTRACKED_ID }
        untrackedAtMs = System.currentTimeMillis()
        return readings
    }

    private fun readOne(
        width: Int,
        height: Int,
        detection: Detection,
        trackId: Int?,
        focalPx: Float,
        principalX: Float,
        principalY: Float,
    ): DistanceReading? {
        // La bbox du masque, quand elle est fiable, remplace la boîte détecteur pour
        // la mesure (hauteur vraie, pieds exacts) ; la lecture garde la boîte
        // détecteur (overlay, appariement profondeur).
        val box = detection.maskBox?.takeIf { it.size >= 4 } ?: detection.box
        if (Pinhole.isCutTop(box)) return null
        // Bas coupé (pieds sous le cadre) : aucune mesure, `null`. Le sténopé ne
        // peut pas parler (hauteur tronquée) et le plan de sol non plus — sa
        // formule demande la ligne des pieds, qui est précisément sous le cadre.
        // La rabattre sur le bord bas (ce que faisait cette branche jusqu'au P0
        // point 7) rendait une **constante de pose** (2,27 m à tangage 0, 6,29 m à
        // −20° pour un téléphone à 1,5 m) : un piéton à 1 m était annoncé à 2,27 m
        // et le seuil de contact de 0,6 m devenait inatteignable. Il n'existe pas
        // de plan de sol par objet côté direct pour remplacer ce chiffre
        // (pas de `GroundReadings` par boîte — c'est là qu'un futur pipeline
        // se branchera ; l'ancien `GroundPipeline`, jamais branché, a été
        // supprimé le 01/11/2026). Une distance
        // inconnue se traite comme proche par les appelants conservateurs
        // (`Guidance` bloque sans distance) ; un chiffre faux est pire.
        if (Pinhole.isCutBottom(box, height)) return null
        // Boîte redressée (identité sans distorsion mesurée) : les deux branches
        // — sténopé via [rawMeters], plan de sol via [groundMeters] — lisent des
        // bords haut/bas sur leurs rayons idéaux.
        val measured = undistortedBox(box, width, height, principalX, principalY)
        val raw = rawMeters(detection.label, measured, focalPx, principalY, height) ?: return null
        val id = trackId ?: UNTRACKED_ID
        val smoothed = if (trackId == null) {
            raw.meters
        } else if (kalmanEnabled) {
            kalman.filter(trackId, raw.meters, raw.confidence, label = detection.label, box = detection.box).meters
        } else {
            smoother.smooth(trackId, raw.meters)
        }
        return DistanceReading(id, detection.label, smoothed, raw.meters, detection.box, raw.confidence)
    }

    /**
     * La mesure brute d'une frame : les deux branches de la lane, fusionnées par
     * [DistanceFusion] (poids sténopé [KnownSizes.fusionWeight], poids sol
     * [GroundWeight] depuis la dépression des pieds). Un objet sans hauteur de
     * classe connue peut donc être mesuré s'il est posé au sol et que la pose est
     * réglée — sans jamais inventer de chiffre.
     */
    private fun rawMeters(
        label: String,
        box: List<Float>,
        focalPx: Float,
        principalY: Float,
        height: Int,
    ): RawReading? {
        val standing = KnownSizes.heightMeters(label)
            ?.let { Pinhole.distanceMeters(box, it, focalPx) }
        val ground = groundMeters(box, focalPx, principalY, height)
        val meters = DistanceFusion.combine(
            standing,
            ground,
            KnownSizes.fusionWeight(label),
            groundWeight(box, focalPx, principalY),
        ) ?: return null
        val confidence = ReadingConfidence.of(box, standing, ground)
        return RawReading(meters, confidence)
    }

    /**
     * Poids de la branche sol pour cette boîte : plein pot quand les pieds plongent
     * (dépression franche), plancher vers l'horizon ([GroundWeight]). `1f` sans pose
     * (poids inutilisé, la branche sol est alors muette).
     */
    private fun groundWeight(box: List<Float>, focalPx: Float, principalY: Float): Float {
        val current = pose ?: return 1f
        if (box.size < 4) return GroundWeight.MIN_WEIGHT
        return GroundWeight.of(GroundPlane.depressionRad(box[3], current, focalPx, principalY))
    }

    /**
     * Boîte redressée pour la mesure ([Distortion], centre optique du calibrage) :
     * seuls les bords haut/bas bougent — ce sont eux que lisent les deux branches —
     * l'abscisse des points redressés étant le centre horizontal de la boîte. Les
     * bords gauche/droit restent intacts, et la lecture garde la boîte détecteur
     * brute (overlay). Identité sans distorsion mesurée (`lens.distortion == null`).
     */
    private fun undistortedBox(
        box: List<Float>,
        width: Int,
        height: Int,
        principalX: Float,
        principalY: Float,
    ): List<Float> {
        val distortion = lens.distortion ?: return box
        if (box.size < 4) return box
        val norm = Distortion.normPx(width, height)
        val centerX = (box[0] + box[2]) / 2f
        return listOf(
            box[0],
            Distortion.undistortY(centerX, box[1], principalX, principalY, norm, distortion),
            box[2],
            Distortion.undistortY(centerX, box[3], principalX, principalY, norm, distortion),
        )
    }

    /**
     * Plan de sol : la ligne des **pieds** (bas de la boîte) donne la distance sans rien savoir
     * de la classe. Le centre optique est celui du calibrage ([LensSpec.principalPx],
     * centre de l'image par défaut) et la ligne des pieds vient d'une boîte non coupée
     * et déjà redressée : c'est [readOne], en amont, qui l'a garanti (coupe sur la
     * boîte brute, mesure sur la boîte redressée).
     */
    private fun groundMeters(
        box: List<Float>,
        focalPx: Float,
        principalY: Float,
        imageHeight: Int,
    ): Float? {
        val current = pose ?: return null
        if (box.size < 4) return null
        return GroundPlane.forwardMeters(box[3], current, focalPx, principalY, imageHeight)
    }

    companion object {
        /** Identité des objets que le journal n'a pas encore identifiés. */
        const val UNTRACKED_ID = -1
    }

    /** Mesure brute d'une frame : mètres fusionnés + confiance de la mesure. */
    private data class RawReading(val meters: Float, val confidence: Float)
}