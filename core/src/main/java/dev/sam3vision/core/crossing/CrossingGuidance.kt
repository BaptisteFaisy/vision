package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.GroundPlane
import dev.sam3vision.core.distance.Pinhole
import dev.sam3vision.core.live.AnnouncementGate
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Guidage latéral vers le passage piéton, sur le trottoir : le centre de la
 * boîte dit où aller (couloir 0,34-0,66, miroir du guidage obstacles), le mot
 * seul (« Gauche. », « Droite. », « Tout droit. ») part en clip local
 * (< 100 ms, [GuidanceWord.clipId]) quand il change — miroir de
 * [dev.sam3vision.core.live.GuidanceAnnouncer] (mot nouveau seul).
 *
 * Distance : jamais le sténopé (`crosswalk` est DEPTH_ONLY,
 * [dev.sam3vision.core.distance.KnownSizes]) — profondeur médiane calibrée
 * d'abord, plan de sol ensuite ([CrossingDistance]). Arrivée : « Sur le
 * passage. » ([CrossingText.arrived]), douce, 2 frames, changement seul.
 * En Traversée, le mot ne parle que passage proche (≤ [GUIDE_METERS],
 * [CrossingOffer.seesNearCrosswalk]) — le feu et le veto gardent la priorité.
 *
 * Pur et testé en JVM : aucune image, aucun Android, aucune horloge implicite.
 */
object CrossingGuidance {

    /** Bord gauche du couloir « tout droit » (miroir `PositionText.SIDE_EDGE`). */
    const val LEFT_EDGE = 0.34f

    /** Bord droit du couloir « tout droit » (`1 - LEFT_EDGE`). */
    const val RIGHT_EDGE = 0.66f

    /** En deçà, la profondeur dit « arrivé » (mètres, profondeur ou sol). */
    const val ARRIVAL_METERS = 1.5f

    /** En deçà, le passage proche se rejoint à la voix, même en Traversée (ordre user 31/10). */
    const val GUIDE_METERS = 3.0f

    /** Bas de boîte normalisé qui touche le bas du cadre (arrivée). */
    const val BOTTOM_TOUCH_FRACTION = 0.98f

    /** Ligne des pieds proche du bas (fraction de hauteur, arrivée). */
    const val FOOT_CLOSE_FRACTION = 0.92f

    /** Frames positives de suite avant « Sur le passage. ». */
    const val ARRIVAL_CONFIRM_FRAMES = 2

    /** Allongement minimal d'un passage (largeur / hauteur) : jamais vertical. */
    const val CROSSWALK_MIN_ASPECT = 0.8f

    /** Aire normalisée minimale d'un passage (sous ce seuil : bruit lointain). */
    const val CROSSWALK_MIN_AREA = 0.002f

    /** Bas de boîte normalisé minimal d'un passage (zébrures au sol, bas du cadre). */
    const val CROSSWALK_MIN_BOTTOM = 0.5f

    /** Direction du passage depuis son centre normalisé (0..1). */
    fun directionOf(centerX: Float): SteeringCap = when {
        centerX < LEFT_EDGE -> SteeringCap.Left
        centerX > RIGHT_EDGE -> SteeringCap.Right
        else -> SteeringCap.Straight
    }

    /** Mot du centre : le clip parlé (< 100 ms, jamais « Stop » ici). */
    fun wordOf(centerX: Float): GuidanceWord = GuidanceWord.fromCap(directionOf(centerX))

    /**
     * Passage dans l'axe au meilleur score ([CrossingOffer.MIN_SCORE] min),
     * `null` si aucun. Le score et les étiquettes sont ceux de [CrossingOffer]
     * (0,35, 2/5 frames + hystérésis).
     *
     * Sans dimensions (`frameW`/`frameH` ≤ 0) : repli historique au seul
     * meilleur score. Sinon le score ne départage que les candidats
     * géométriquement plausibles ([isPlausibleCrosswalk]) — un faux positif
     * hors axe, vertical ou minuscule ne vole plus le guidage au vrai passage.
     */
    fun selectBest(
        objects: List<SegObject>,
        minScore: Float = CrossingOffer.MIN_SCORE,
        frameW: Int = 0,
        frameH: Int = 0,
    ): SegObject? {
        val candidates = objects
            .filter { it.score >= minScore && CrossingOffer.isCrosswalkLabel(it.label) }
        if (frameW <= 0 || frameH <= 0) return candidates.maxByOrNull { it.score }
        return candidates
            .filter { it.box.size >= 4 && isPlausibleCrosswalk(it.box, frameW, frameH) }
            .maxByOrNull { it.score }
    }

    /**
     * Boîte pixels crédible pour un passage piéton dans une frame
     * `frameW` x `frameH` : elle chevauche le couloir de marche ([LEFT_EDGE]–
     * [RIGHT_EDGE], ROI axe), pas verticale ([CROSSWALK_MIN_ASPECT], sous le
     * carré : un piéton debout (0,4-0,6) est rejeté, un passage proche gardé),
     * ni minuscule ([CROSSWALK_MIN_AREA]), bas sous la moitié du cadre
     * ([CROSSWALK_MIN_BOTTOM], zébrures au sol — proxy : le comptage des
     * bandes demanderait les pixels, indisponibles en pur JVM).
     * Géométrie illisible = faux, jamais de consigne devinée.
     */
    fun isPlausibleCrosswalk(boxPx: List<Float>, frameW: Int, frameH: Int): Boolean {
        if (boxPx.size < 4 || frameW <= 0 || frameH <= 0) return false
        val x0 = boxPx[0] / frameW
        val y0 = boxPx[1] / frameH
        val x1 = boxPx[2] / frameW
        val y1 = boxPx[3] / frameH
        if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) return false
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0f || h <= 0f) return false
        if (x1 < LEFT_EDGE || x0 > RIGHT_EDGE) return false
        if (w / h < CROSSWALK_MIN_ASPECT) return false
        if (w * h < CROSSWALK_MIN_AREA) return false
        return y1 >= CROSSWALK_MIN_BOTTOM
    }

    /**
     * Centre normalisé d'une boîte pixels ([LiveFrame]), `null` si illisible.
     * Boîte normalisée (0..1) : voir [centerXOfNormalized].
     */
    fun centerXOf(boxPx: List<Float>, frameW: Int): Float? {
        if (boxPx.size < 4 || frameW <= 0) return null
        val cx = (boxPx[0] + boxPx[2]) / 2f
        if (!cx.isFinite()) return null
        return (cx / frameW).takeIf { it.isFinite() }
    }

    /** Centre d'une boîte déjà normalisée 0..1, `null` si illisible. */
    fun centerXOfNormalized(boxNorm: List<Float>): Float? {
        if (boxNorm.size < 4) return null
        return ((boxNorm[0] + boxNorm[2]) / 2f).takeIf { it.isFinite() }
    }

    /** Boîte pixels qui touche le bas du cadre (marge [Pinhole], arrivée). */
    fun touchesBottom(boxPx: List<Float>, frameH: Int): Boolean {
        if (boxPx.size < 4 || frameH <= 0) return false
        val y2 = boxPx[3]
        if (!y2.isFinite()) return false
        return y2 >= frameH - Pinhole.EDGE_TOLERANCE_PX
    }

    /** Boîte normalisée qui touche le bas du cadre (arrivée). */
    fun touchesBottomNormalized(boxNorm: List<Float>): Boolean {
        if (boxNorm.size < 4) return false
        val y2 = boxNorm[3]
        if (!y2.isFinite()) return false
        return y2 >= BOTTOM_TOUCH_FRACTION
    }

    /** Ligne des pieds proche du bas (arrivée). */
    fun footClose(footRowPx: Float, imageH: Int): Boolean {
        if (!footRowPx.isFinite() || imageH <= 0) return false
        return footRowPx / imageH >= FOOT_CLOSE_FRACTION
    }

    /** Profondeur courte (arrivée) : mesurée et sous [ARRIVAL_METERS]. */
    fun depthClose(meters: Float?): Boolean {
        val valid = meters?.takeIf { it.isFinite() && it > 0f } ?: return false
        return valid <= ARRIVAL_METERS
    }

    /**
     * Arrivée (boîte pixels) : bas touché OU pieds proches OU profondeur
     * courte. Un seul suffit — sur le passage, les trois se valent.
     */
    fun isArrivedPx(boxPx: List<Float>, frameH: Int, meters: Float?): Boolean {
        if (touchesBottom(boxPx, frameH)) return true
        if (boxPx.size >= 4 && footClose(boxPx[3], frameH)) return true
        return depthClose(meters)
    }

    /** Arrivée (boîte normalisée) : bas touché OU profondeur courte. */
    fun isArrivedNormalized(boxNorm: List<Float>, meters: Float?): Boolean =
        touchesBottomNormalized(boxNorm) || depthClose(meters)

    /**
     * Labels sans passage piéton : à appliquer avant le guidage obstacles pour
     * que le sol peint ne dise jamais « Stop » — le passage se rejoint, il ne
     * se contourne pas. Miroir du filtre sol du guidage.
     */
    fun withoutCrosswalk(labels: List<PlacedLabel>): List<PlacedLabel> =
        labels.filterNot { CrossingOffer.isCrosswalkLabel(it.label) }

    /** Lectures sans passage piéton (même filtre, côté distances). */
    fun withoutCrosswalkReadings(readings: List<DistanceReading>): List<DistanceReading> =
        readings.filterNot { CrossingOffer.isCrosswalkLabel(it.label) }
}

/**
 * Distance du passage : profondeur médiane calibrée d'abord, plan de sol
 * ensuite — jamais le sténopé (`crosswalk` est DEPTH_ONLY : un plan au sol n'a
 * pas de hauteur). Pur JVM.
 */
object CrossingDistance {

    /**
     * Distance au sol depuis la ligne des pieds ([GroundPlane]) : le seul
     * chemin sol du passage. `null` plutôt qu'un chiffre faux (gardes du plan).
     */
    fun groundMeters(
        footRowPx: Float,
        pose: CameraPose,
        focalPx: Float,
        principalY: Float,
        imageH: Int,
    ): Float? = GroundPlane.forwardMeters(footRowPx, pose, focalPx, principalY, imageH)

    /**
     * Meilleure distance : la profondeur calibrée quand elle mesure, le sol
     * sinon, `null` si aucun ne mesure. Jamais de sténopé ici.
     */
    fun bestMeters(depthMeters: Float?, groundMeters: Float?): Float? {
        depthMeters?.takeIf { it.isFinite() && it > 0f }?.let { return it }
        return groundMeters?.takeIf { it.isFinite() && it > 0f }
    }
}

/**
 * Déclencheur latéral : un mot par changement, dit aussitôt (miroir de
 * [dev.sam3vision.core.live.GuidanceAnnouncer] — `urgent` + `firstSight` sautent
 * délai et stabilité, clips < 100 ms). Le même mot ne se répète jamais ; `null`
 * (aucun passage) fait retomber la scène pour que le retour parle.
 *
 * Douceur : les trois mots sont doux (appelant : `urgent = false`, ils
 * attendent le silence) — seul « Stop » coupe, et le passage n'en dit jamais.
 */
class CrossingLateralAnnouncer(
    minFrames: Int = AnnouncementGate.DEFAULT_MIN_FRAMES,
    cooldownMs: Long = AnnouncementGate.DEFAULT_COOLDOWN_MS,
) {

    private val gate = AnnouncementGate(minFrames, cooldownMs)

    /** Dernière phrase parlée (`null` tant que rien n'a été dit). */
    val lastPhrase: String? get() = gate.lastPhrase

    /** Dernier mot décidé (`null` avant la première frame ou sans passage). */
    var lastWord: GuidanceWord? = null
        private set

    /**
     * Centre du passage à [atMs] (`null` = aucun en vue) : le mot à prononcer,
     * ou `null` pour garder le silence. [language] est la langue de la voix.
     */
    fun onFrame(
        atMs: Long,
        centerX: Float?,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String? {
        val valid = centerX?.takeIf { it.isFinite() }
        if (valid == null) {
            gate.clearScene()
            lastWord = null
            return null
        }
        val word = CrossingGuidance.wordOf(valid)
        lastWord = word
        if (!gate.admit(atMs, word.key, urgent = true, firstSight = true)) return null
        val phrase = word.text(language)
        gate.markSpoken(word.key, atMs, phrase)
        return phrase
    }

    /** Nouvelle session : tout est oublié (le premier mot sera dit). */
    fun reset() {
        gate.reset()
        lastWord = null
    }
}

/** Arrivée à prononcer : phrase douce ([urgent] toujours faux). */
data class CrossingArrivalCue(val phrase: String, val urgent: Boolean = false)

/**
 * Guetteur d'arrivée : [CrossingGuidance.ARRIVAL_CONFIRM_FRAMES] positifs de
 * suite parlent une fois (« Sur le passage. »), puis silence jusqu'à la
 * prochaine sortie du passage (changement seul). Douce : `urgent = false`.
 * Pur JVM.
 */
class CrossingArrivalWatcher(
    private val confirmFrames: Int = CrossingGuidance.ARRIVAL_CONFIRM_FRAMES,
) {

    private var hits = 0
    private var announced = false

    /** Dernier signal produit (`null` tant que rien n'a été annoncé). */
    var lastCue: CrossingArrivalCue? = null
        private set

    /** Positif de la frame : le signal à prononcer, ou `null` (silence). */
    fun onFrame(detected: Boolean, language: SpeechLanguage = SpeechLanguage.DEFAULT): CrossingArrivalCue? {
        if (!detected) {
            hits = 0
            announced = false
            return null
        }
        hits += 1
        if (hits < confirmFrames || announced) return null
        announced = true
        val cue = CrossingArrivalCue(CrossingText.arrived(language), urgent = false)
        lastCue = cue
        return cue
    }

    /** Nouvelle session : compte et annonce oubliés. */
    fun reset() {
        hits = 0
        announced = false
        lastCue = null
    }
}
