package dev.sam3vision.app.isee

import dev.sam3vision.app.IseeHaptics
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.crossing.CrossingPost
import dev.sam3vision.core.crossing.CrossingPriority
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingVerdict
import dev.sam3vision.core.crossing.CrossingVerdictAnnouncer
import dev.sam3vision.core.crossing.CrossingVerdictLight
import dev.sam3vision.core.crossing.CrossingVerdictRule
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.traverse.VehicleStateWatcher

/**
 * Publication vocale, même signature que `VoiceBus.post` (`level`, `text`,
 * `clipId`) : le routeur y branche `VoiceBus.post` (voir le snippet de câblage
 * dans le rapport de mission — `VoiceBus` vit sur la branche routeur).
 */
fun interface CrossingVerdictPoster {
    fun post(level: Int, text: String, clipId: String?)
}

/**
 * Moteur du verdict branché au bus (SPEC v4, niveau 100) : feu piéton puis
 * voitures en mouvement. Nourri à chaque frame en Traversée, remis à zéro à l'activation.
 */
internal fun busVerdictEngine(): CrossingVerdictEngine = CrossingVerdictEngine(
    poster = CrossingVerdictPoster { level, text, clipId -> VoiceBus.post(level, text, clipId) },
)

/**
 * Frame traversée (SPEC v4) : [posts] ordonnés (verdict 100, humains 101,
 * bordures 102), [verdict] et ses entrées ([light], [carMoving]), [overlay]
 * (l'état rouge-vert affiché et vibré) et [haptic] (l'état à vibrer, `null`
 * hors front — la répétition des 3 s ne vibre pas).
 */
data class CrossingVerdictFrame(
    val posts: List<CrossingPost>,
    val verdict: CrossingVerdict,
    val light: CrossingVerdictLight,
    val carMoving: Boolean,
    val overlay: CrossingState,
    val haptic: CrossingState?,
)

/**
 * Moteur du verdict traversée (SPEC v4) : feu piéton ([readLight], boîte nulle =
 * absent) puis voitures en mouvement (voiture uniquement, [VehicleStateWatcher]
 * nourri de voitures seules) → [CrossingVerdictRule] → annonce (3 s,
 * changement immédiat) → [posts][CrossingPriority] → [poster].
 *
 * [human] et [curb] sont les posts des missions humains/bordures (niveaux 101 et
 * 102, `null` = silence) : le moteur les ordonne après le verdict et les publie
 * dans le même appel. Chat et GPS sont tus par le routeur en traversée, pas ici.
 *
 * Pur hors [readLight] (décodage crop, injecté pour les tests JVM) et [poster].
 */
class CrossingVerdictEngine(
    private val announcer: CrossingVerdictAnnouncer = CrossingVerdictAnnouncer(),
    private val cars: VehicleStateWatcher = VehicleStateWatcher(),
    private val readLight: (LiveFrame, String) -> CrossingLight = ::crossingLightOf,
    private val poster: CrossingVerdictPoster? = null,
) {
    /** Dernier verdict vibré (`null` = aucun : le premier verdict parlé vibre). */
    private var felt: CrossingVerdict? = null

    /**
     * Traite une frame à [atMs] : verdict, posts ordonnés publiés ([poster]),
     * overlay rouge-vert et front haptique. [readings] porte les distances
     * affinées (filtrées voiture comme les détections).
     */
    fun onFrame(
        frame: LiveFrame,
        readings: List<DistanceReading>,
        jpegB64: String,
        atMs: Long,
        human: CrossingPost? = null,
        curb: CrossingPost? = null,
    ): CrossingVerdictFrame {
        val light = verdictLightOf(readLight(frame, jpegB64))
        val carDetections = frame.objects.toDetections(frame.width, frame.height)
            .filter { CrossingVerdictRule.isCarLabel(it.label) }
        val carReadings = readings.filter { CrossingVerdictRule.isCarLabel(it.label) }
        cars.update(carDetections, carReadings, atMs)
        val moving = cars.movingThisFrame
        val verdict = CrossingVerdictRule.decide(light, moving)
        val cue = announcer.onFrame(atMs, verdict)
        val verdictPost = cue?.let { CrossingPost(CrossingLevels.VERDICT, it.phrase, it.clipId) }
        val posts = CrossingPriority.order(verdictPost, human, curb)
        if (poster != null) posts.forEach { poster.post(it.level, it.text, it.clipId) }
        val overlay = overlayOf(verdict)
        val haptic = if (cue != null && felt != verdict) overlay else null
        if (cue != null) felt = verdict
        return CrossingVerdictFrame(posts, verdict, light, moving, overlay, haptic)
    }

    /** Oublie annonce, suivi voitures et front haptique (nouvelle traversée). */
    fun reset() {
        announcer.reset()
        cars.reset()
        felt = null
    }
}

/**
 * Feu vu par le verdict : boîte nulle = absent (pas de feu en vue — les voitures
 * tranchent, nouveauté SPEC v4), sinon la couleur lue ([CrossingState] binaire).
 */
internal fun verdictLightOf(light: CrossingLight): CrossingVerdictLight = when {
    light.box == null -> CrossingVerdictLight.Absent
    light.state == CrossingState.Green -> CrossingVerdictLight.Green
    else -> CrossingVerdictLight.Red
}

/** Verdict → état rouge-vert (overlay conservé, haptique conservée). */
internal fun overlayOf(verdict: CrossingVerdict): CrossingState = when (verdict) {
    CrossingVerdict.Cross -> CrossingState.Green
    CrossingVerdict.DontCross -> CrossingState.Red
}

/**
 * Front haptique de la frame : [frame.haptic] non nul → [IseeHaptics.crossing].
 * Une ligne côté sonde, comme `observeCrossing` avant.
 */
internal fun playVerdictHaptic(haptics: IseeHaptics, frame: CrossingVerdictFrame) {
    val felt = frame.haptic ?: return
    haptics.crossing(felt)
}
