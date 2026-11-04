package dev.sam3vision.core.live

import dev.sam3vision.core.crossing.CrossingGuidance
import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardSignal
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.Passage
import dev.sam3vision.core.speech.SpeechLanguage
import java.util.Random
import kotlin.math.min

/**
 * Adaptateur de la voix : le **seul** point qui touche les API de décision.
 *
 * Reprend l'app, frame par frame : suivi puis guidage calculés **à chaque frame**
 * (`LiveProbe.phraseOf`, l'anti-répétition du guidage avance même quand personne ne
 * l'entend), danger relu dans les lectures et guidé par le chemin (`obstacleFrame`),
 * arbitrage au [SimBus].
 * Rail et trou de grillage restent à leur défaut : sans bordure ni deuxième pan de
 * clôture dans ces scènes, `ProbeVoice` leur passerait du vide. Comme dans
 * `ProbeVoice.phrase`, ni âge, ni vitesse, ni place libre ne sont transmis.
 *
 * Rend le mot accepté par le bus (il commence à [nowMs]), `null` sinon.
 */
fun frameWord(sim: SimFrame, nowMs: Long): String? {
    val voice = sim.voice
    val labels = voice.tracker.onFrame(nowMs, sim.placed).map { it.label }
    val guide = voice.announcer.onFrame(
        atMs = nowMs,
        labels = CrossingGuidance.withoutCrosswalk(labels),
        language = SpeechLanguage.En,
        cap = SteeringCap.Straight,
        routeCap = null,
        hfovDeg = sim.hfovDeg,
        sidewalk = SidewalkPosition.Ahead,
        samPassage = sim.passage,
    )
    val signal = HazardSignal.of(sim.readings, sim.width, sim.height).steeredBy(voice.announcer.lastView)
    val danger = voice.hazard.onSignal(signal, nowMs, SpeechLanguage.En)?.phrase
    return voice.bus.arbitrate(danger, guide, nowMs)
}

/** Ce qu'une frame donne à la décision : lectures, labels placés, passage serveur. */
class SimFrame(
    val readings: List<DistanceReading>,
    val placed: List<PlacedLabel>,
    val passage: Passage,
    val width: Int,
    val height: Int,
    val hfovDeg: Float,
    val voice: SimVoice,
)

/** État de décision d'une marche : les mêmes objets que la sonde, neufs à chaque seed. */
class SimVoice {
    val tracker = PersonTracker()
    val announcer = GuidanceAnnouncer()
    val hazard = HazardWatcher()
    val bus = SimBus()
}

/** Un mot accepté : instant de départ, texte, voix (A guidage, B danger). */
data class SaidWord(val atMs: Long, val text: String, val voice: Char) {
    /** Côté du mot (-1 gauche, +1 droite), `null` pour un mot sans côté. */
    val side: Int? get() = sideOf(text)
}

/**
 * Bus vocal réduit : un mot tient la voix [WalkModel.WORD_MS], tout ce qui arrive
 * pendant ce temps est jeté. Le danger (B) passe devant le guidage (A) ; A demande
 * [WalkModel.SILENCE_MS] depuis le **départ** du dernier mot (`VoiceBus.silenceMs`
 * se mesure ainsi, pas depuis sa fin).
 *
 * Réduction assumée : le vrai `VoiceBus` garde une place d'attente (`VoicePending`,
 * le vœu de rang égal ou meilleur se dit à la fin du mot) ; ici il est perdu.
 */
class SimBus {
    private var holdUntilMs = Long.MIN_VALUE
    private var lastSayMs = Long.MIN_VALUE / 2

    /** Mots acceptés, dans l'ordre. */
    val said = ArrayList<SaidWord>()

    /** Arbitre la frame à [nowMs] : le mot dit, `null` s'il n'y a rien ou si le bus tient. */
    fun arbitrate(danger: String?, guide: String?, nowMs: Long): String? {
        if (nowMs < holdUntilMs) return null
        if (danger != null) return say(danger, 'B', nowMs)
        if (guide != null && nowMs - lastSayMs >= WalkModel.SILENCE_MS) return say(guide, 'A', nowMs)
        return null
    }

    private fun say(text: String, voice: Char, nowMs: Long): String {
        said += SaidWord(nowMs, text, voice)
        lastSayMs = nowMs
        holdUntilMs = nowMs + WalkModel.WORD_MS
        return text
    }
}

/** Constantes du modèle, une seule définition pour la boucle, le marcheur et le rapport. */
object WalkModel {
    /** Pas de simulation (ms). */
    const val STEP_MS = 50L

    /** Âge de l'image jugée par chaque décision (ms). */
    const val IMAGE_AGE_MS = 700L

    /** Durée maximale d'une scène (ms). */
    const val MAX_MS = 25_000L

    /** Durée d'un mot sur le bus (ms) : 100 ms de départ puis le clip. */
    const val WORD_MS = 450L

    /** Silence requis avant la voix A (ms), `FallbackVoice.SILENCE_MS`. */
    const val SILENCE_MS = 300L

    /** Vitesse de marche (m/s). */
    const val WALK_MPS = 1.2

    /** Demi-largeur du corps (m). */
    const val BODY_HALF_M = 0.25

    /** Écart sous lequel un passage compte pour frôlé (m). */
    const val TIGHT_GAP_M = 0.25

    /** Marge au-delà du dernier obstacle pour finir la scène (m). */
    const val PAST_LAST_M = 2.0

    /** Graines jouées par scène. */
    const val SEEDS = 20
}

/** Résultat d'une marche, une seed d'une scène. */
data class WalkOutcome(
    val minGapM: Double,
    val contact: Boolean,
    val said: List<SaidWord>,
    val finalX: Double,
    val frames: Int,
    val seenFrames: Int,
    val measuredFrames: Int,
) {
    /** Mots de côté dits (gauche ou droite). */
    val sideWords: Int get() = said.count { it.side != null }

    /** Changements de côté entre deux mots de côté consécutifs. */
    val sideChanges: Int
        get() = said.mapNotNull { it.side }.zipWithNext().count { (first, second) -> first != second }
}

/**
 * Une marche en boucle fermée : le monde avance par pas de [WalkModel.STEP_MS], chaque
 * décision juge la scène d'il y a [WalkModel.IMAGE_AGE_MS], et le mot n'atteint le
 * marcheur qu'une fois dit en entier. [trace] reçoit une ligne par décision.
 */
class WalkRun(
    private val scene: WalkScene,
    seed: Long,
    private val decisionHz: Double,
    private val trace: ((String) -> Unit)? = null,
) {
    private val rng = Random(seed)
    private val camera = SimCamera()
    private val walker = Walker(rng)
    private val voice = SimVoice()
    private val meter = DistanceMeter().apply {
        pose = camera.pose
        groundPlaneEnabled = true
    }
    private val poses = ArrayList<WalkPose>()
    private var heardAtMs = Long.MAX_VALUE
    private var inFlight: String? = null
    private var frames = 0
    private var seenFrames = 0
    private var measuredFrames = 0

    /** Joue la scène jusqu'au dépassement, au contact ou à [WalkModel.MAX_MS]. */
    fun play(): WalkOutcome {
        var nowMs = 0L
        var nextDecisionMs = WalkModel.IMAGE_AGE_MS.toDouble()
        var minGap = gapAt(nowMs)
        while (nowMs < WalkModel.MAX_MS && minGap > 0.0 && walker.y <= scene.endY(nowMs)) {
            poses += walker.pose()
            if (nowMs >= nextDecisionMs) {
                decide(nowMs)
                nextDecisionMs += 1_000.0 / decisionHz
            }
            deliver(nowMs)
            walker.step(WalkModel.STEP_MS, nowMs)
            nowMs += WalkModel.STEP_MS
            minGap = min(minGap, gapAt(nowMs))
        }
        return WalkOutcome(minGap, minGap <= 0.0, voice.bus.said, walker.x, frames, seenFrames, measuredFrames)
    }

    /** Écart d'épaule à [atMs] : surface la plus proche moins la demi-largeur du corps. */
    private fun gapAt(atMs: Long): Double =
        scene.obstacles.minOf { it.gapFrom(walker.x, walker.y, atMs) } - WalkModel.BODY_HALF_M

    /** Une décision : l'image d'il y a 0,7 s passe par la vraie mesure, puis par la voix. */
    private fun decide(nowMs: Long) {
        val seenAtMs = nowMs - WalkModel.IMAGE_AGE_MS
        val objects = camera.shoot(scene.obstacles, poses[(seenAtMs / WalkModel.STEP_MS).toInt()], seenAtMs, rng)
        val width = camera.width
        val height = camera.height
        val passage = ServerPassage.suggest(objects, width, height)
        val readings = meter.measure(width, height, objects.map { Detection(it.label, 1f, it.box, width, height) })
        val placed = placedLabels(LiveFrame(width, height, 0L, objects, passage), readings)
        val word = frameWord(SimFrame(readings, placed, passage, width, height, camera.hfovDeg, voice), nowMs)
        frames++
        if (objects.isNotEmpty()) seenFrames++
        if (readings.isNotEmpty()) measuredFrames++
        trace?.invoke(traceLine(nowMs, placed, word))
        if (word != null) {
            inFlight = word
            heardAtMs = nowMs + WalkModel.WORD_MS
        }
    }

    /** Le mot atteint le marcheur à la fin du clip. */
    private fun deliver(nowMs: Long) {
        val word = inFlight ?: return
        if (nowMs < heardAtMs) return
        walker.hear(word, nowMs)
        inFlight = null
    }

    /** Ligne de trace : le marcheur **maintenant**, la scène telle que l'image l'a vue. */
    private fun traceLine(nowMs: Long, placed: List<PlacedLabel>, word: String?): String {
        val seen = placed.joinToString(" ") { "${it.label}[${f2(it.x0)}-${f2(it.x1)} ${f2(it.meters)}m]" }
        return "t=${nowMs}ms x=${f2(walker.x)} y=${f2(walker.y)} cap=${f2(walker.commandDeg)} $seen -> ${word ?: "-"}"
    }

    private fun f2(value: Number?): String = value?.let { walkFmt(it.toDouble(), 2) } ?: "?"
}
