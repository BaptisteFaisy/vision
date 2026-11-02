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
import dev.sam3vision.core.crossing.Evidence
import dev.sam3vision.core.crossing.LightEvidenceGate
import dev.sam3vision.core.crossing.LightTemporalVote
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
    /**
     * Porte de preuve lumineuse : une boîte manquante reste [Evidence.Unknown]
     * tant que l'absence n'est pas prouvée (4 frames de suite sans aucune
     * boîte, contexte de passage ; l'absence redevient concluable après 8
     * frames de perte durable, trou D3). Tenue du vert déléguée au vote
     * (mesure A5 : 3 verts sur les 5 dernières frames, +123 ms p50), d'où
     * `minGreenFrames = 1` et `greenHoldMs = 0` — **`MIN_GREEN_EVIDENCE_FRAMES`
     * et `GREEN_HOLD_MS` sont donc neutralisées ici**, sinon la latence
     * s'additionnerait (A9 : +0,8 à 1,5 s).
     */
    private val gate: LightEvidenceGate = LightEvidenceGate(minGreenFrames = 1, greenHoldMs = 0L),
    /** Vote temporel du vert (3 preuves sur 5, sortie immédiate sur rouge prouvé). */
    private val vote: LightTemporalVote = LightTemporalVote(),
) {
    /** Dernier verdict vibré (`null` = aucun : le premier verdict parlé vibre). */
    private var felt: CrossingVerdict? = null

    /** Frames de suite sans preuve de verdict (ni vert ni rouge lu) : borne le silence. */
    private var unprovenFrames = 0

    /** Une boîte de feu a-t-elle été vue depuis le dernier [reset] ? */
    private var sawBox = false

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
        val read = readLight(frame, jpegB64)
        val evidence = gate.onFrame(
            atMs = atMs,
            sawAnyLightBox = read.box != null,
            bestState = if (read.box != null) read.state else null,
        )
        if (read.box != null) sawBox = true
        val light = lightOf(read, evidence)
        val proven = light != CrossingVerdictLight.Unknown
        if (proven) unprovenFrames = 0 else trackUnproven()
        val carDetections = frame.objects.toDetections(frame.width, frame.height)
            .filter { CrossingVerdictRule.isCarLabel(it.label) }
        val carReadings = readings.filter { CrossingVerdictRule.isCarLabel(it.label) }
        cars.update(carDetections, carReadings, atMs, motion = motion)
        val moving = cars.movingThisFrame
        val verdict = CrossingVerdictRule.decide(light, moving, carVeto)
        val cue = announcer.onFrame(atMs, verdict)
        // Rien de lisible (boîte manquante, couleur illisible, vert pas encore
        // confirmé) : on ne PRONONCE rien. Parler « Don't cross. » sur les deux
        // premières frames d'un vert ferait entendre « Don't cross. » puis
        // « Cross. » 100 ms plus tard — pire que le silence. L'overlay et
        // l'haptique restent au rouge (sûr), la phrase attend une preuve.
        // Mais le silence est BORNÉ : au-delà de la borne de ré-armement de la
        // porte (8 frames, 0,7 à 1,6 s) le verdict parle — jamais de silence à vie (D3).
        val silent = !proven && unprovenFrames < LightEvidenceGate.ABSENT_REARM_FRAMES
        // Rien n'est prononcé sur une frame muette : la phrase reste DUE et part
        // dès la première frame qui a une preuve (sinon l'annonceur croyait
        // avoir parlé et le silence durait 2 s de plus).
        if (silent) announcer.rearm(atMs)
        val verdictPost = if (silent) null else cue?.let { CrossingPost(CrossingLevels.VERDICT, it.phrase, it.clipId) }
        val posts = CrossingPriority.order(verdictPost, human, curb)
        publish(posts, verdictPost, atMs)
        val overlay = overlayOf(verdict)
        // Pas de vibration sur une frame sans preuve : le front est prononcé avec
        // la phrase, jamais avant elle.
        val haptic = if (!silent && cue != null && felt != verdict) overlay else null
        if (!silent && cue != null) felt = verdict
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

    /**
     * Feu vu par le verdict, **avec mémoire** : [LightEvidenceGate] sépare
     * « aucune preuve » (une frame sans boîte : raté de SAM, camion qui masque,
     * téléphone baissé) de « absence prouvée » (4 frames sans aucune boîte,
     * face au passage) — le premier ne doit jamais mener à « Cross. » (A9 §3 D1).
     *
     * Le vote n'est alimenté **que sur les frames où une boîte existe** : une
     * frame sans boîte n'est pas une preuve non verte, elle est traitée par la
     * porte de preuve. C'est ce qui rend un vert clignotant (détection
     * intermittente) concluable — sinon la fenêtre se vidait à chaque frame
     * manquée et le verdict restait muet à vie (trou D4).
     *
     * `redProven` reste **faux** ici : [CrossingLight] ne porte que le verdict
     * binaire de `PedLightGate` (aucune fraction rouge), donc le rouge lu n'est
     * pas *prouvé* au sens de `LightTemporalVote.redProven` (fraction ≥ 0,02),
     * il est *non-vert*. Conséquence assumée et mesurée : plus de `forget()` de
     * la fenêtre à chaque frame non verte, donc l'accord **glissant** 3/5 de la
     * mesure A5 (371/400 verts, 16 faux verts sur 980 séquences) tient enfin ;
     * la sortie du vert est portée par `EXIT_FRAMES = 2` (2 frames non vertes
     * consécutives, ≈130-160 ms), et le verdict lui-même bascule sur le rouge
     * dès la frame rouge lue (le rouge ne dépend pas du vote, seulement le
     * vert). Le vote ne sert donc qu'à autoriser le vert.
     */
    private fun lightOf(read: CrossingLight, evidence: Evidence): CrossingVerdictLight {
        if (read.box == null) return absentLight(evidence)
        val voted = vote.onFrame(rawState = read.state, redProven = false)
        val light = verdictLightOf(read)
        if (light == CrossingVerdictLight.Green && voted != CrossingState.Green) {
            return CrossingVerdictLight.Unknown
        }
        return light
    }

    /**
     * Frame sans boîte : l'absence de la porte n'autorise la traversée
     * (`CrossingVerdictLight.Absent`) que si **aucune** boîte n'a été vue dans
     * cette traversée. Après un feu vu puis perdu, la porte rouvre bien
     * l'absence (8 frames), mais sa perte n'est pas une preuve : un rouge
     * masqué redeviendrait une autorisation de traverser (A9 §3 D1). Le verdict
     * rend `Unknown`, donc « Don't cross. », et la phrase est prononcée dès que
     * la porte conclut.
     */
    private fun absentLight(evidence: Evidence): CrossingVerdictLight =
        if (evidence == Evidence.Absent && !sawBox) CrossingVerdictLight.Absent else CrossingVerdictLight.Unknown

    /** Compte le silence et, à la borne, jette la preuve verte accumulée (déblocage borné). */
    private fun trackUnproven() {
        unprovenFrames += 1
        if (unprovenFrames == LightEvidenceGate.ABSENT_REARM_FRAMES) vote.reset()
    }

    /** Oublie annonce, suivi voitures et front haptique (nouvelle traversée). */
    fun reset() {
        announcer.reset()
        cars.reset()
        gate.reset()
        vote.reset()
        felt = null
        unprovenFrames = 0
        sawBox = false
    }
}

/**
 * Feu vu par le verdict : boîte nulle = absent (pas de feu en vue — les voitures
 * tranchent, nouveauté SPEC v4), sinon la couleur lue ([CrossingState] binaire).
 *
 * C'est une **traduction pure** : elle ne prouve rien. Le moteur ne l'appelle
 * que boîte non nulle ([CrossingVerdictEngine.lightOf]) et filtre lui-même
 * `Absent` par la porte de preuve ([LightEvidenceGate]) : une boîte manquante
 * n'est pas une preuve d'absence (raté de SAM, camion qui masque, téléphone
 * baissé), et la perte d'un feu déjà vu n'en est pas une non plus (A9 § 3 D1).
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
