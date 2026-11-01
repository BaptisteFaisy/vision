package dev.sam3vision.core.traverse

import dev.sam3vision.core.motion.EgoMotion
import dev.sam3vision.core.motion.GyroHint
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.predict.EgoVerdict
import dev.sam3vision.core.predict.ResiduDecision
import dev.sam3vision.core.predict.ResiduVerdict
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Verdict fond/boîte (V1) : qui a bougé entre deux frames — le véhicule suivi
 * ([VEHICLE_MOVED]), la caméra ([CAMERA_MOVED]), personne ([STATIC]), ou on ne
 * sait pas ([UNKNOWN], le doute ne parle jamais d'arrêt).
 */
enum class Verdict {
    /** Le résidu (boîte − fond) dépasse le seuil : le véhicule bouge vraiment. */
    VEHICLE_MOVED,

    /** Résidu nul mais fond franc : la boîte suit la caméra, le véhicule est fixe. */
    CAMERA_MOVED,

    /** Boîte et fond immobiles : le véhicule est à l'arrêt, caméra fixe. */
    STATIC,

    /** Fond plat, confiance basse, doute : jamais d'arrêt inventé. */
    UNKNOWN,
}

/**
 * Verdict daté d'une piste : [trackId] l'id suivi, [globalDx]/[globalDy] et
 * [boxDx]/[boxDy] les vitesses comparées (fractions d'image par seconde),
 * [globalScale]/[globalConf] le zoom et la confiance du fond, [bgChangeScore]
 * la part de fond changé (0..1, preuve enregistrée, non décisive en V1).
 */
data class MovingVerdict(
    val trackId: Int,
    val globalDx: Float,
    val globalDy: Float,
    val globalScale: Float,
    val globalConf: Float,
    val boxDx: Float,
    val boxDy: Float,
    val bgChangeScore: Float,
    val verdict: Verdict,
)

/**
 * Mouvement global d'une frame pour le veto fond : [global] l'estimation
 * inter-frames (`BlockMotionSource`, pixels du plan de luminance
 * [lumaWidth]×[lumaHeight]), [dtMs] le délai entre les deux frames (> 0).
 * `null` = pas de signal fond : comportement strictement inchangé.
 */
data class FrameMotion(
    val global: MotionSample,
    val lumaWidth: Int,
    val lumaHeight: Int,
    val dtMs: Long,
)

/**
 * Qui bouge, le véhicule ou la caméra (V1, règle produit : le fond qui change
 * pendant que la boîte s'en écarte, c'est le véhicule qui roule ; le fond qui
 * défile pendant que la boîte le suit, c'est la caméra).
 *
 * On compare deux vitesses (fractions d'image par seconde) : le fond global
 * ([MotionSample], pixels du plan de luminance) et la boîte suivie (fractions
 * d'image). Le résidu = boîte − fond est le mouvement propre du véhicule :
 * au-delà de [LATERAL_RATIO], il roule ([Verdict.VEHICLE_MOVED]) ; résidu nul
 * et fond franc, la caméra bouge ([Verdict.CAMERA_MOVED]) ; les deux faibles,
 * arrêt ([Verdict.STATIC]). Le reste — confiance sous [TRUST_MIN], fond plat
 * (estimateur muet, `null`), temps ou dimensions invalides, bande de doute —
 * dit [Verdict.UNKNOWN] : on ne tranche jamais sans preuve.
 *
 * Seuils = priors rue à recaler sur séquences téléphone (rue, parking,
 * embouteillage), direction sûre : une piste jeune,
 * floue ou perdue ne déclenche jamais. Pur JVM, zéro allocation hors verdict.
 */
object VehicleBackground {

    /** Dérive maximale d'un arrêt, prior rue : 2 % de l'image par seconde (miroir de `VehicleStateWatcher`). */
    const val STILL_SPEED = 0.02f

    /** Au-delà (fraction d'image/s), prior rue, le résidu ou le fond est franc (bande morte au-dessus de l'arrêt). */
    const val LATERAL_RATIO = 0.03f

    /** Confiance minimale du fond, prior rue repris de `Shift.TRUST_MIN` : en dessous, le fond est plat ou flou. */
    const val TRUST_MIN = 0.12f

    /**
     * Verdict de la piste [trackId] : [boxDx]/[boxDy] le déplacement de la
     * boîte entre les deux frames (fractions de largeur/hauteur d'image),
     * [global] le mouvement du fond (`null` = estimateur muet, fond plat),
     * [lumaWidth]/[lumaHeight] la grille du plan de luminance (unités de
     * [global]), [dtMs] le délai inter-frames (> 0), [bgChangeScore] la part
     * de fond changé (0..1, hors bornes = ramené, non fini = 0).
     *
     * [gyro] (PISTE4, `null` = verdicts visuels seuls, inchangés) : déplacement
     * gyro attendu en pixels du plan de luminance. Ordre avec la piste 1
     * (compensation ego-motion) : le résidu **visuel** (boîte − fond mesuré)
     * passe d'abord — si [global] est utilisable, [gyro] est ignoré ; le résidu
     * gyro ne sert qu'en **complément** quand le bloc-matching sature en rotation
     * rapide (fond muet ou peu confiant), où le résidu décisionnel devient
     * boîte − gyro. Si la piste 1 corrige [global] en entrée, ce hook la reçoit
     * déjà corrigée : translation ego d'abord, rotation gyro ensuite, sans conflit.
     */
    fun decide(
        trackId: Int,
        boxDx: Float,
        boxDy: Float,
        global: MotionSample?,
        lumaWidth: Int,
        lumaHeight: Int,
        dtMs: Long,
        bgChangeScore: Float,
        gyro: GyroHint? = null,
    ): MovingVerdict {
        val bg = bgChangeScore.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (dtMs <= 0L || lumaWidth <= 0 || lumaHeight <= 0) {
            return unknown(trackId, global, bg)
        }
        if (!boxDx.isFinite() || !boxDy.isFinite()) return unknown(trackId, global, bg)
        // PISTE4 : résidu visuel d'abord ; le gyro ne prend le relais (fond de
        // repli, rotation pure) que si le fond mesuré est inutilisable — pan rapide
        // qui sature le bloc-matching. Sans gyro, chemin strictement inchangé.
        val sample = EgoMotion.fusedSample(global, gyro)
        if (sample == null) return unknown(trackId, global, bg)
        val dtSeconds = dtMs / 1000f
        val gx = sample.dx / lumaWidth / dtSeconds
        val gy = sample.dy / lumaHeight / dtSeconds
        val scaleRate = abs(sample.scale - 1f) / dtSeconds
        val bx = boxDx / dtSeconds
        val by = boxDy / dtSeconds
        if (!gx.isFinite() || !gy.isFinite() || !scaleRate.isFinite()) {
            return unknown(trackId, sample, bg)
        }
        if (!bx.isFinite() || !by.isFinite()) return unknown(trackId, sample, bg)
        val residual = hypot(bx - gx, by - gy)
        val ground = hypot(gx, gy) + scaleRate
        return MovingVerdict(trackId, gx, gy, sample.scale, sample.confidence, bx, by, bg, classify(residual, ground))
    }

    /**
     * Vitesse du fond seul (fractions d'image/s, zoom inclus), `null` si
     * inutilisable (confiance sous [TRUST_MIN], temps ou dimensions invalides,
     * valeurs non finies). Même normalisation que [decide].
     */
    fun groundOf(global: MotionSample?, lumaWidth: Int, lumaHeight: Int, dtMs: Long): Float? {
        if (global == null || dtMs <= 0L || lumaWidth <= 0 || lumaHeight <= 0) return null
        if (!global.confidence.isFinite() || global.confidence < TRUST_MIN) return null
        if (!global.dx.isFinite() || !global.dy.isFinite() || !global.scale.isFinite()) return null
        val dtSeconds = dtMs / 1000f
        val ground = hypot(global.dx / lumaWidth / dtSeconds, global.dy / lumaHeight / dtSeconds) +
            abs(global.scale - 1f) / dtSeconds
        return ground.takeIf { it.isFinite() }
    }

    /**
     * Fond visuel exploitable : les mêmes exigences que [decide] avant la PISTE4
     * (confiance et valeurs finies). Un fond qui échoue ici laisse la place au
     * repli gyro au lieu de conclure [Verdict.UNKNOWN] tout de suite.
     */
    private fun visualUsable(sample: MotionSample): Boolean = EgoMotion.visualUsable(sample)

    /**
     * Arrêt prouvé d'une piste : [speed01ps] la dérive du centre (fractions
     * d'image/s, magnitude positive) sous [STILL_SPEED] et [confidence] la
     * confiance du fond au-dessus de [TRUST_MIN]. Le doute (valeur non finie
     * ou négative, fond plat) dit faux : jamais d'arrêt inventé. Prior rue,
     * consommé par `VehicleStateWatcher` (autre module) pour l'arrêt mesuré.
     */
    fun isStatic(speed01ps: Float, confidence: Float): Boolean {
        if (!speed01ps.isFinite() || !confidence.isFinite()) return false
        if (speed01ps < 0f) return false
        if (confidence < TRUST_MIN) return false
        return speed01ps <= STILL_SPEED
    }

    /** Résidu franc = le véhicule roule ; résidu nul + fond franc = la caméra ; sinon doute ou arrêt. */
    private fun classify(residual: Float, ground: Float): Verdict = when {
        residual > LATERAL_RATIO -> Verdict.VEHICLE_MOVED
        residual <= STILL_SPEED && ground > LATERAL_RATIO -> Verdict.CAMERA_MOVED
        residual <= STILL_SPEED && ground <= STILL_SPEED -> Verdict.STATIC
        else -> Verdict.UNKNOWN
    }

    /** Verdict de doute : vitesses à zéro, preuves du fond conservées quand elles sont finies. */
    private fun unknown(trackId: Int, sample: MotionSample?, bg: Float): MovingVerdict = MovingVerdict(
        trackId = trackId,
        globalDx = 0f,
        globalDy = 0f,
        globalScale = sample?.scale?.takeIf { it.isFinite() } ?: 1f,
        globalConf = sample?.confidence?.takeIf { it.isFinite() } ?: 0f,
        boxDx = 0f,
        boxDy = 0f,
        bgChangeScore = bg,
        verdict = Verdict.UNKNOWN,
    )
}

// FUSION : [Verdict] est le seul vocabulaire du chemin véhicule — les verdicts
// génériques des pistes s'y convertissent ici, jamais l'inverse (`predict`
// n'importe pas `traverse`). `null` = doute : l'appelant garde son verdict.

/**
 * Seuil « ego ≈ 0 » ([ResiduDecision.egoPx], pixels de masque) : sous 1 px, le
 * résidu nul du masque dit l'arrêt ([Verdict.STATIC]) plutôt que la caméra
 * mobile — prior à recaler sur téléphone, comme les seuils IoU du résidu.
 */
const val RESIDU_STILL_EGO_PX = 1f

/**
 * [EgoVerdict] (PISTE1) → [Verdict] : le résidu boîte−fond franc dit le
 * véhicule ([Verdict.VEHICLE_MOVED]), le résidu nul l'arrêt ([Verdict.STATIC]).
 */
fun EgoVerdict.toVerdict(): Verdict? = when (this) {
    EgoVerdict.MOVING -> Verdict.VEHICLE_MOVED
    EgoVerdict.STATIC -> Verdict.STATIC
    EgoVerdict.UNCERTAIN -> null
}

/**
 * [ResiduDecision] (PISTE3) → [Verdict], mapping proposé par la piste 3 : objet
 * franc → [Verdict.VEHICLE_MOVED] ; résidu nul + ego ≈ 0 ([egoStillPx]) →
 * [Verdict.STATIC], + ego franc → [Verdict.CAMERA_MOVED].
 */
fun ResiduDecision.toVerdict(egoStillPx: Float = RESIDU_STILL_EGO_PX): Verdict? = when (verdict) {
    ResiduVerdict.OBJECT_MOVED -> Verdict.VEHICLE_MOVED
    ResiduVerdict.CAMERA_MOVED -> if (egoPx <= egoStillPx) Verdict.STATIC else Verdict.CAMERA_MOVED
    ResiduVerdict.UNCERTAIN -> null
}
