package dev.sam3vision.app.isee

import android.graphics.RectF
import dev.sam3vision.app.IseeHaptics
import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.live.LensOrigin
import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingApproachWatcher
import dev.sam3vision.core.crossing.CrossingArrivalWatcher
import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingDistance
import dev.sam3vision.core.crossing.CrossingGuidance
import dev.sam3vision.core.crossing.CrossingLateralAnnouncer
import dev.sam3vision.core.crossing.CrossingOffer
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.crossing.NoLightGate
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.toDetections
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraversePhase
import dev.sam3vision.core.traverse.TraverseVeto
import dev.sam3vision.core.traverse.crossingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Câblage du **mode Traversée** de la sonde : mêmes masques qu'en mode normal
 * (liste unique [LivePrompts.default]), couleur du feu lue dans la frame,
 * annonces et vibrations.
 *
 * Isolé de [LiveProbe] pour que celle-ci reste sous la limite de 500 lignes
 * (même raison que `ProbeWiring.kt`) : la sonde ne porte que l'annonceur, le
 * fanion d'état et l'appel publié.
 */

/**
 * Zone tactile publiée par la puce Traversée (coordonnées fenêtre, `null` quand
 * masquée) : la racine y exempte ses gestes, comme pour le chip Picto.
 */
class CrossingChipZone {
    private var bounds: RectF? = null

    /** Zone publiée, `null` quand la puce n'est pas affichée. */
    fun zone(): RectF? = bounds

    /** La puce se place / se retire : la racine exempte sa zone de l'interception. */
    fun setBounds(bounds: RectF?) {
        this.bounds = bounds
    }
}

/**
 * Active ou coupe le mode Traversée : les prompts **ne changent pas** (liste
 * unique normal/Traversée, ordre user du 30/10 — mêmes masques dans les deux
 * modes), seule la voix bascule (feu + veto trafic au lieu du guidage). Les
 * prompts courants sont rejoués tels quels : monde (dehors/dedans), champ
 * « Suivre » et « Find the X » survivent à la bascule. Le guidage obstacles
 * n'est plus coupé (§ 25) : ses ordres d'évitement (« Gauche. », « Droite. »,
 * stop) passent devant la voix rouge/vert, « Tout droit. » restant tu tant
 * que le mode est actif. Toute bascule est manuelle (puce, ordre vocal).
 */
internal fun LiveProbe.setCrossingActive(
    settings: Settings,
    active: Boolean,
    sensorLens: LensSpec? = null,
    onLens: ((LensOrigin) -> Unit)? = null,
    fromGlasses: Boolean = GlassesVideoLink.live.value,
    glassesLens: LensSpec? = null,
) {
    configureFrom(settings, state.value.conceptsSent.ifEmpty { LivePrompts.default }, active, sensorLens, onLens, glassesLens, fromGlasses)
}

/**
 * Le feu d'une frame : **où** il est ([box], normalisée 0..1) et **ce qu'il dit**
 * ([state]). Les deux viennent de la même lecture, donc l'étiquette posée sur la
 * vidéo ne peut pas désigner un autre feu que celui qui a été classifié.
 */
data class CrossingLight(
    val box: List<Float>?,
    val state: CrossingState,
)

/**
 * Vert utilisable pour une décision (veto sonde, pipeline direct) : état vert
 * **et** boîte présente. Une lecture sans boîte (pas de feu en vue —
 * l'ex-`boxOf()` null de la pompe locale supprimée le 31/10) ne fait jamais
 * traverser, même si l'état porté est vert : fail-closed, l'équivalent
 * binaire de l'ancien « Doute ». Seuils inchangés : ceci ne touche ni
 * l'arbre ni la porte, seulement l'interprétation de la lecture.
 */
internal val CrossingLight.greenForDecision: Boolean
    get() = state == CrossingState.Green && box != null

/**
 * Fusion de deux lectures de feux (câblage 2 feux) : accord → l'état lu,
 * désaccord → [CrossingState.Red] (attendre, la voix dédiée vient de
 * [CrossingText.contradiction]). Pur, testé en JVM.
 */
internal fun combineLightStates(first: CrossingState, second: CrossingState): CrossingState {
    if (first == second) return first
    return CrossingState.Red
}

/**
 * Phrase du signal [cue] (`null` = silence gardé) : contradiction [contradiction]
 * (lue 2 feux) → phrase dédiée ; phase PRET du veto + signal vert →
 * « prêt », pas « traversez » (la voix et le bandeau disent tous deux « pas
 * encore »). L'état et l'urgence sont intacts : seule la phrase s'aligne.
 */
internal fun crossingCueOf(
    cue: CrossingCue?,
    phase: TraversePhase,
    language: SpeechLanguage,
    contradiction: Boolean = false,
): CrossingCue? {
    if (cue == null) return null
    if (contradiction) return cue.copy(phrase = CrossingText.contradiction(language))
    if (phase == TraversePhase.PRET && cue.state == CrossingState.Green) {
        return cue.copy(phrase = CrossingText.ready(language))
    }
    return cue
}

/**
 * Ce que la sonde publie pour le feu sur une frame : la phrase à dire
 * ([cue], `null` = rester muet), l'état confirmé affiché (dernier annoncé,
 * rouge par défaut) et la boîte lue sur la frame. Un seul aller-retour,
 * pour que l'étiquette ne désigne jamais un autre feu que celui qui vient
 * d'être annoncé.
 */
internal data class CrossingPatch(
    val cue: CrossingCue?,
    val state: CrossingState,
    val box: List<Float>?,
    val phase: TraversePhase,
    /** Vrai une fois l'absence de feu confirmée (voix sans-feu, feu muet). */
    val noLight: Boolean = false,
)

/**
 * Signal traversée de la frame, ou `null` quand le mode est coupé : la sonde
 * reste alors muette et ne marque aucun feu. Le veto ne voit un vert que
 * boîte à l'appui ([greenForDecision]) : sans feu en vue, attente — sauf
 * absence de feu confirmée ([noLightGate], `null` = voix feu seule, legacy),
 * où le feu se tait et la phrase vient de la branche sans-feu (véhicules
 * puis « Traversez. », ordre user du 31/10). [contradiction] vrai (deux feux
 * lus en désaccord, câblage 2 feux) : la phrase dédiée remplace, l'état
 * reste fail-safe (voir [crossingCueOf]).
 */
internal fun crossingPatchOf(
    announcer: CrossingAnnouncer,
    veto: TraverseVeto,
    active: Boolean,
    frame: LiveFrame,
    lens: LensSpec,
    jpegB64: String,
    atMs: Long,
    language: SpeechLanguage,
    onCrop: CrossingCropTap? = null,
    noLightGate: NoLightGate? = null,
    contradiction: Boolean = false,
): CrossingPatch? {
    if (!active) return null
    val light = crossingLightOf(frame, jpegB64, onCrop)
    val detections = frame.objects.toDetections(frame.width, frame.height)
    val focalPx = lens.focalPx(frame.width, frame.height)
    val decision = veto.update(light.greenForDecision, detections, focalPx, atMs)
    if (noLightGate?.onFrame(light.box) == true) {
        // Sans feu confirmé : le feu se tait (pas de « Rouge, attendez. »
        // pour un feu absent), l'état publié reste rouge (aucun vert lu —
        // pastille, bouton et vibrations inchangés).
        return CrossingPatch(null, CrossingState.Red, null, decision.state, true)
    }
    val vetoed = decision.crossingState()
    val cue = crossingCueOf(announcer.onFrame(atMs, vetoed, language), decision.state, language, contradiction)
    // Voix, vibration et affichage parlent d'une seule voix confirmée : l'état
    // publié est le dernier annoncé (rouge tant que rien n'est annoncé), jamais
    // la lecture brute de la frame — un glitch d'une frame ne se voit ni ne se
    // sent (ordre user du 31/10, certitude rouge/vert).
    val confirmed = announcer.lastCue?.state ?: CrossingState.Red
    return CrossingPatch(cue, confirmed, light.box, decision.state)
}

/**
 * Chaque nouvel état **confirmé** du feu fait vibrer le téléphone (vert =
 * double salve, rouge = créneau long), en plus de la voix déjà câblée par
 * `observeSpoken` — l'état publié étant le dernier annoncé, un glitch
 * d'une frame ne vibre jamais.
 */
internal fun LiveProbe.observeCrossing(scope: CoroutineScope, haptics: IseeHaptics) {
    var last: CrossingState? = null
    scope.launch {
        state.collect { st ->
            val crossing = st.crossing
            if (!st.crossingActive || crossing == null || crossing == last) return@collect
            last = crossing
            haptics.crossing(crossing)
        }
    }
}

/**
 * Libellé du bouton Traversée : l'état du mode et, quand il est actif, le
 * dernier état connu du feu. Pur, testé en JVM.
 */
internal fun crossingButtonLabel(active: Boolean, state: CrossingState?): String {
    if (!active) return "Crossing: OFF"
    return when (state) {
        CrossingState.Green -> "Crossing: GREEN"
        CrossingState.Red, null -> "Crossing: RED"
    }
}

/**
 * Labels sans passage piéton : le guidage obstacles les reçoit filtrés pour
 * que le sol peint ne dise jamais « Stop ». Le passage se rejoint (guidage
 * latéral doux), il ne se contourne pas.
 */
internal fun List<PlacedLabel>.withoutCrosswalk(): List<PlacedLabel> =
    CrossingGuidance.withoutCrosswalk(this)

/**
 * Ce que le guidage latéral dit sur une frame : le mot ([lateral], mot nouveau seul, clip
 * [lateralClip] < 100 ms), l'annonce ([approach], « Passage piéton … » localisé), l'arrivée
 * ([arrival], « Sur le passage. »), et la géométrie ([centerX], [meters]). `null` = silence.
 */
internal data class CrossingLateralPatch(
    val lateral: String?,
    val lateralClip: String?,
    val arrival: String?,
    val centerX: Float?,
    val meters: Float?,
    val approach: String? = null,
    /** Horodatage de la frame (ms) : l'annonce d'activation n'en parle que fraîche. */
    val atMs: Long = 0L,
)

/** Centre du meilleur passage (pixels → normalisé), `null` si aucun. */
internal fun crossingCenterXOf(objects: List<SegObject>, width: Int): Float? {
    val best = CrossingGuidance.selectBest(objects) ?: return null
    return CrossingGuidance.centerXOf(best.box, width)
}

/**
 * Distance courte du passage : profondeur calibrée de [readings] pour [box]
 * d'abord, plan de sol ([pose]) ensuite, `null` si aucun ne mesure. Jamais de
 * sténopé (`crosswalk` DEPTH_ONLY).
 */
internal fun crossingMetersOf(
    box: List<Float>,
    readings: List<DistanceReading>,
    pose: CameraPose?,
    focalPx: Float,
    principalY: Float,
    imageH: Int,
): Float? {
    val depth = crosswalkDepthMeters(readings, box)
    val ground = if (box.size >= 4 && pose != null) {
        CrossingDistance.groundMeters(box[3], pose, focalPx, principalY, imageH)
    } else null
    return CrossingDistance.bestMeters(depth, ground)
}

/**
 * Distances des passages d'une frame pour le guidage latéral : une mesure
 * par objet ([crossingMetersOf], profondeur puis sol), `null` sans mesure.
 * Le tri proche/loin part dans [CrossingOffer.seesNearCrosswalk].
 */
internal fun crossingOfferMeters(
    readings: List<DistanceReading>,
    pose: CameraPose?,
    lens: LensSpec,
    width: Int,
    height: Int,
): (SegObject) -> Float? {
    val focalPx = lens.focalPx(width, height)
    return { obj -> crossingMetersOf(obj.box, readings, pose, focalPx, height / 2f, height) }
}

/**
 * Signal latéral de la frame : mot nouveau seul + annonce localisée + arrivée (2 frames,
 * changement seul). Sans passage : les trois guetteurs retombent et `null` est rendu.
 * Pur hors annonceurs (injectés pour les tests JVM).
 */
internal fun crossingLateralPatchOf(
    lateral: CrossingLateralAnnouncer,
    arrival: CrossingArrivalWatcher,
    objects: List<SegObject>,
    width: Int,
    height: Int,
    readings: List<DistanceReading>,
    pose: CameraPose?,
    lens: LensSpec,
    atMs: Long,
    language: SpeechLanguage,
    approach: CrossingApproachWatcher = CrossingApproachWatcher(),
): CrossingLateralPatch? {
    if (width <= 0 || height <= 0) return null
    val best = CrossingGuidance.selectBest(objects)
    if (best == null) {
        lateral.onFrame(atMs, null, language)
        arrival.onFrame(false, language)
        approach.onFrame(null, null, language)
        return null
    }
    val centerX = CrossingGuidance.centerXOf(best.box, width) ?: return null
    val focalPx = lens.focalPx(width, height)
    val meters = crossingMetersOf(best.box, readings, pose, focalPx, height / 2f, height)
    val lateralPhrase = lateral.onFrame(atMs, centerX, language)
    val clip = lateralPhrase?.let { GuidanceWord.clipIdFor(it) }
    val arrived = CrossingGuidance.isArrivedPx(best.box, height, meters)
    val arrivalPhrase = arrival.onFrame(arrived, language)?.phrase
    val approachPhrase = approach.onFrame(centerX, meters, language)
    return CrossingLateralPatch(lateralPhrase, clip, arrivalPhrase, centerX, meters, approachPhrase, atMs)
}

/**
 * Mémoire latérale, une par processus (la garde des 500 lignes ne laisse
 * pas de place à un champ de plus dans `LiveProbe`) : la sonde l'alimente
 * à chaque frame publiée, l'écran
 * observe [flow] (mot + annonce + arrivée, doux, jamais de « Stop » ici).
 */
internal object CrossingLateralWatch {

    private val lateral = CrossingLateralAnnouncer()
    private val arrival = CrossingArrivalWatcher()
    private val approach = CrossingApproachWatcher()
    private val _flow = MutableStateFlow<CrossingLateralPatch?>(null)

    /** Dernier signal latéral (`null` = silence ou aucun passage). */
    val flow: StateFlow<CrossingLateralPatch?> = _flow

    /** Frame publiée : met à jour mot + arrivée, publie le signal. */
    fun update(
        objects: List<SegObject>,
        width: Int,
        height: Int,
        readings: List<DistanceReading>,
        pose: CameraPose?,
        lens: LensSpec,
        atMs: Long,
        language: SpeechLanguage,
    ): CrossingLateralPatch? {
        val patch = crossingLateralPatchOf(
            lateral, arrival, objects, width, height, readings, pose, lens, atMs, language, approach,
        )
        _flow.value = patch
        return patch
    }

    /** Nouvelle séance. */
    fun reset() {
        lateral.reset()
        arrival.reset()
        approach.reset()
        _flow.value = null
    }
}

/**
 * Alimente le guidage latéral depuis la sonde : hors traversée le mot et
 * l'arrivée suivent chaque frame publiée ; en traversée ils ne suivent que
 * passage proche (≤ 6 m mesurés ou boîte prouvée sous les pieds,
 * [CrossingOffer.seesNearCrosswalk] — fail-closed) : à moins de 6 m en face,
 * l'user s'aligne à la voix (« Gauche. », « Droite. », « Tout droit. »,
 * « Sur le passage. »), le feu et le veto gardant la priorité du son (mots
 * doux, `urgent = false`). Loin ou invisible, la mémoire retombe (une ligne
 * côté sonde, plafond respecté).
 */
internal fun feedCrossingLateral(
    traversing: Boolean,
    objects: List<SegObject>,
    width: Int,
    height: Int,
    readings: List<DistanceReading>,
    pose: CameraPose?,
    lens: LensSpec,
    atMs: Long,
    language: SpeechLanguage,
) {
    if (!traversing) {
        CrossingLateralWatch.update(objects, width, height, readings, pose, lens, atMs, language)
        return
    }
    val near = CrossingOffer.seesNearCrosswalk(
        objects, crossingOfferMeters(readings, pose, lens, width, height), height,
        maxMeters = CrossingGuidance.GUIDE_METERS,
    )
    if (near) {
        CrossingLateralWatch.update(objects, width, height, readings, pose, lens, atMs, language)
    } else {
        CrossingLateralWatch.reset()
    }
}

/**
 * Parole latérale : le mot via [speakWord] (clip local < 100 ms, doux :
 * appelant `urgent = false`), l'annonce localisée via [say], l'arrivée via
 * [sayArrival] (voie séparée : l'arrivée passe en dernier — ordre user du
 * 31/10 — donc jamais par la même voix que l'annonce). Les guetteurs
 * dédupliquent déjà : le silence ne dit rien. [flush] (rattrapage au
 * silence) tourne à chaque émission, même vide.
 */
internal fun observeCrossingLateral(
    patches: Flow<CrossingLateralPatch?>,
    scope: CoroutineScope,
    speakWord: suspend (clipId: String, text: String) -> Unit,
    say: suspend (String) -> Unit,
    sayArrival: suspend (String) -> Unit = say,
    flush: (suspend () -> Unit)? = null,
) {
    scope.launch {
        patches.collect { patch ->
            if (patch != null) {
                val lateral = patch.lateral
                val clip = patch.lateralClip
                if (lateral != null && clip != null) runCatching { speakWord(clip, lateral) }
                val approach = patch.approach
                if (approach != null) runCatching { say(approach) }
                val arrival = patch.arrival
                if (arrival != null) runCatching { sayArrival(arrival) }
            }
            if (flush != null) runCatching { flush() }
        }
    }
}

/**
 * Voix du guidage latéral sur [ProximityVoice] : mot en clip doux (file au
 * silence), annonce localisée en doux obstacle (file au silence), arrivée
 * (« Sur le passage. ») en file dernière — jamais devant une autre annonce
 * (ordre user du 31/10) — et rattrapage arrivée à chaque frame, aussi en
 * Traversée (où la file obstacles est coupée). Rien ne coupe ici.
 */
internal fun observeCrossingVoice(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
) = observeCrossingLateral(
    CrossingLateralWatch.flow, scope,
    speakWord = { clip, text -> voice.speakWord(clip, text, language(), playerIdle(), urgent = false, queue = true) },
    say = { voice.onPhrase(it, playerIdle(), queue = true) },
    sayArrival = { voice.onArrivalPhrase(it, playerIdle()) },
    flush = { voice.flushArrival(playerIdle()) },
)
