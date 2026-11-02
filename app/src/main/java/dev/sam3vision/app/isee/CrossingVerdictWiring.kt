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
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.traverse.VehicleStateWatcher

/**
 * Publication vocale, même forme que `VoiceBus.post` (`level`, `text`,
 * `clipId`) **et même retour** : `true` quand la phrase a été retenue/émise,
 * `false` quand le bus l'a jetée. Le routeur y branche `VoiceBus.post`.
 */
fun interface CrossingVerdictPoster {
    /** Vrai si la phrase est passée (voir [VoiceBus.post]). */
    fun post(level: Int, text: String, clipId: String?): Boolean
}

/**
 * Moteur du verdict branché au bus (SPEC v4, [CrossingLevels.VERDICT] = cran 0,
 * le plus fort : un mot d'obstacle ne peut plus l'écraser) : feu piéton puis
 * voitures en mouvement. Nourri à chaque frame en Traversée, remis à zéro à l'activation.
 */
internal fun busVerdictEngine(): CrossingVerdictEngine = CrossingVerdictEngine(
    poster = CrossingVerdictPoster { level, text, clipId -> VoiceBus.post(level, text, clipId) },
)

/**
 * Frame traversée (SPEC v4) : [posts] ordonnés (verdict 0, humains 101,
 * bordures 102), [verdict] et ses entrées ([light], [carMoving], [carVeto]),
 * [overlay] (l'état rouge-vert affiché et vibré) et [haptic] (l'état à vibrer,
 * `null` hors front — la répétition des 3 s ne vibre pas).
 */
data class CrossingVerdictFrame(
    val posts: List<CrossingPost>,
    val verdict: CrossingVerdict,
    val light: CrossingVerdictLight,
    val carMoving: Boolean,
    val overlay: CrossingState,
    val haptic: CrossingState?,
    /** Veto trafic de la frame (voiture proche/en approche, bus, camion, moto). */
    val carVeto: Boolean = false,
)

/**
 * Moteur du verdict traversée (SPEC v4) : feu piéton ([readLight], boîte nulle =
 * absent) puis voitures en mouvement (voiture uniquement, [VehicleStateWatcher]
 * nourri de voitures seules, jugées par le fond) → [CrossingVerdictRule]
 * → annonce (2 s, changement immédiat) → [posts][CrossingPriority] → [poster].
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
     * affinées (filtrées voiture comme les détections). [motion] (`null` =
     * pas de signal fond) juge les pistes voitures par le fond : une boîte
     * qui suit la caméra (pan sur une garée) ne conclut plus le mouvement.
     * [carVeto] est le veto trafic de la frame (`TraverseVeto` : véhicule
     * proche, en approche, bus/camion/moto compris) : veto présent ⇒ jamais de
     * feu vert (A9 § 3 D7). Le verdict n'est réputé prononcé que si [poster]
     * l'a **retenu** ; sinon l'annonceur est ré-armé et la frame suivante
     * rejoue la phrase (A9 § 3 D8).
     */
    fun onFrame(
        frame: LiveFrame,
        readings: List<DistanceReading>,
        jpegB64: String,
        atMs: Long,
        human: CrossingPost? = null,
        curb: CrossingPost? = null,
        motion: FrameMotion? = null,
        carVeto: Boolean = false,
    ): CrossingVerdictFrame {
        val light = verdictLightOf(readLight(frame, jpegB64))
        val carDetections = frame.objects.toDetections(frame.width, frame.height)
            .filter { CrossingVerdictRule.isCarLabel(it.label) }
        val carReadings = readings.filter { CrossingVerdictRule.isCarLabel(it.label) }
        cars.update(carDetections, carReadings, atMs, motion = motion)
        val moving = cars.movingThisFrame
        val verdict = CrossingVerdictRule.decide(light, moving, carVeto)
        val cue = announcer.onFrame(atMs, verdict)
        val verdictPost = cue?.let { CrossingPost(CrossingLevels.VERDICT, it.phrase, it.clipId) }
        val posts = CrossingPriority.order(verdictPost, human, curb)
        publish(posts, verdictPost, atMs)
        val overlay = overlayOf(verdict)
        val haptic = if (cue != null && felt != verdict) overlay else null
        if (cue != null) felt = verdict
        return CrossingVerdictFrame(posts, verdict, light, moving, overlay, haptic, carVeto)
    }

    /**
     * Publie [posts] dans l'ordre : le verdict d'abord, et s'il est refusé par
     * le bus ([CrossingVerdictPoster.post] faux) l'annonceur est ré-armé pour
     * que la frame suivante le rejoue ; humains et bordures ensuite, sans
     * retour (leurs guetteurs repostent à chaque frame).
     */
    private fun publish(posts: List<CrossingPost>, verdictPost: CrossingPost?, atMs: Long) {
        val target = poster ?: return
        posts.forEach { post ->
            val accepted = target.post(post.level, post.text, post.clipId)
            if (!accepted && post === verdictPost) announcer.rearm(atMs)
        }
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
 *
 * TODO(W7→lot LightEvidenceGate, A9 § 4.2) : « boîte nulle » n'est **pas** une
 * preuve d'absence — un raté de SAM, un camion qui masque ou un téléphone
 * baissé deviennent une autorisation de traverser, dès la première frame
 * (A9 § 3 D1, le trou principal). Le correctif (preuve d'absence sur 4 crops
 * manquants, état `Unknown` ⇒ « attendez ») est fourni par
 * le lot `LightEvidenceGate` : il ajoute la valeur d'énumération et son
 * horloge. On ne duplique pas ici, pour ne pas figer une seconde sémantique
 * d'« inconnu » dans la même énumération.
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
