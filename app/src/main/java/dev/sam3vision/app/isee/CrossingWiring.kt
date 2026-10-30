package dev.sam3vision.app.isee

import android.graphics.RectF
import dev.sam3vision.app.IseeHaptics
import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingArrivalWatcher
import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingDistance
import dev.sam3vision.core.crossing.CrossingGuidance
import dev.sam3vision.core.crossing.CrossingLateralAnnouncer
import dev.sam3vision.core.crossing.CrossingText
import dev.sam3vision.core.crossing.CrossingLossGate
import dev.sam3vision.core.crossing.CrossingOffer
import dev.sam3vision.core.crossing.CrossingOfferGate
import dev.sam3vision.core.crossing.CrossingPrompts
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.crossing.PedLightGate
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

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
 * est coupé tant que le mode est actif : « Stop, obstacle » et
 * « Vert, traversez » ne doivent jamais se mélanger. [auto] vrai (bascule
 * auto vision ou GPS) : la coupe ne fait pas taire la proposition — seul un
 * refus manuel (bouton, ordre vocal) vaut refus.
 */
internal fun LiveProbe.setCrossingActive(settings: Settings, active: Boolean, auto: Boolean = false) {
    if (!active && !auto) CrossingOfferWatch.declined(System.currentTimeMillis())
    configureFrom(settings, state.value.conceptsSent.ifEmpty { LivePrompts.default }, active)
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
 * Lecture du feu sur une frame : boîte SAM au meilleur score, crop JPEG classifié
 * en local puis filtré par la porte anti-confusion ([PedLightGate], 100 %
 * embarqué, aucun réseau). Pas de feu en vue ou image illisible ⇒
 * [CrossingState.Unknown] (le doute vaut « attendez »).
 *
 * Appelée **une fois par frame** par la sonde : le décodage du crop est le poste
 * coûteux, le refaire pour l'affichage doublerait le travail.
 */
internal fun crossingLightOf(frame: LiveFrame, jpegB64: String): CrossingLight {
    val box = CrossingPrompts.selectLightBox(frame.objects, frame.width, frame.height)
        ?: return CrossingLight(null, CrossingState.Unknown)
    val crop = CrossingPixels.frame(jpegB64, box)
        ?: return CrossingLight(box, CrossingState.Unknown)
    return CrossingLight(box, PedLightGate.verdict(crop.argb, crop.width, crop.height))
}

/**
 * Ce que la sonde publie pour le feu sur une frame : la phrase à dire
 * ([cue], `null` = rester muet), l'état affiché et la boîte à marquer sur la
 * vidéo. Un seul aller-retour, pour que l'étiquette ne désigne jamais un autre
 * feu que celui qui vient d'être annoncé.
 */
internal data class CrossingPatch(
    val cue: CrossingCue?,
    val state: CrossingState,
    val box: List<Float>?,
    val phase: TraversePhase,
)

/**
 * Signal traversée de la frame, ou `null` quand le mode est coupé : la sonde
 * reste alors muette et ne marque aucun feu.
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
): CrossingPatch? {
    if (!active) return null
    val light = crossingLightOf(frame, jpegB64)
    val detections = frame.objects.toDetections(frame.width, frame.height)
    val focalPx = lens.focalPx(frame.width, frame.height)
    val decision = veto.update(light.state == CrossingState.Green, detections, focalPx, atMs)
    val vetoed = decision.crossingState()
    return CrossingPatch(announcer.onFrame(atMs, vetoed, language), light.state, light.box, decision.state)
}

/**
 * Chaque nouvel état du feu fait vibrer le téléphone (vert = double salve,
 * rouge = créneau long), en plus de la voix déjà câblée par `observeSpoken`.
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
 * Chaque bascule du mode Traversée (bouton, ordre vocal, guidage auto)
 * est signalée à [onChanged] : le guidage y reprend la parole après une
 * coupe manuelle. Sans répétition (front seul).
 */
internal fun LiveProbe.observeCrossingActive(scope: CoroutineScope, onChanged: (Boolean) -> Unit) {
    var last: Boolean? = null
    scope.launch {
        state.collect { st ->
            val active = st.crossingActive
            if (active == last) return@collect
            last = active
            runCatching { onChanged(active) }
        }
    }
}

/**
 * Libellé du bouton Traversée : l'état du mode et, quand il est actif, le
 * dernier état connu du feu. Pur, testé en JVM.
 */
internal fun crossingButtonLabel(active: Boolean, state: CrossingState?, offer: Boolean = false): String {
    if (!active && offer) return "Passage piéton — traversée ?"
    if (!active) return "Traversée : OFF"
    return when (state) {
        CrossingState.Green -> "Traversée : VERT"
        CrossingState.Red -> "Traversée : ROUGE"
        CrossingState.Unknown, null -> "Traversée : …"
    }
}

/**
 * Mémoire de la bascule auto « passage piéton → mode Traversée », une par
 * processus : la sonde la consulte à chaque frame publiée (1 ligne, la garde des
 * 500 lignes ne laisse pas de place à un champ de plus dans `LiveProbe`).
 */
internal object CrossingOfferWatch {

    private val gate = CrossingOfferGate()

    /** Passage vu sur la dernière frame publiée (brut, sans mémoire ni silence). */
    var lastVisible: Boolean = false
        private set

    /** Vrai si le mode Traversée doit s'armer seul sur cette frame. */
    fun update(objects: List<SegObject>, nowMs: Long, traversing: Boolean): Boolean {
        lastVisible = CrossingOffer.seesCrosswalk(objects)
        return gate.onFrame(objects, nowMs, traversing)
    }

    /** L'utilisateur a coupé le mode : silence d'une minute. */
    fun declined(nowMs: Long) = gate.declined(nowMs)

    /** Nouvelle séance. */
    fun reset() {
        gate.reset()
        lastVisible = false
    }
}

/**
 * Bascule **automatique** vers le mode Traversée : quand le passage piéton
 * apparaît dans le champ (montée de l'offre, une fois par apparition), le mode
 * s'arme seul et le dit (« Mode traversée activé. ») — une personne aveugle ne
 * voit pas la pastille, sans cette phrase l'activation surprise n'existe pas
 * pour elle. [setAuto] ne fait rien si le mode est déjà actif.
 */
internal fun observeCrossingAuto(
    offers: Flow<Boolean>,
    scope: CoroutineScope,
    language: () -> SpeechLanguage,
    setAuto: (Boolean) -> Unit,
    say: suspend (String) -> Unit,
) {
    scope.launch {
        offers.distinctUntilChanged().filter { it }.collect {
            setAuto(true)
            say(CrossingText.mode(true, language()))
        }
    }
}

/**
 * Vue traversée par frame publiée : l'état sonde émet aussi entre deux frames
 * (poste « pixel » de la chronologie, [LiveProbe.noteDrawn]) — sans ce filtre,
 * le compteur de perte compterait deux fois chaque frame et couperait le mode
 * deux fois trop tôt. Une émission par valeur de [LiveProbeState.frames].
 */
internal fun Flow<LiveProbeState>.crossingSightPerFrame(): Flow<Pair<Boolean, Boolean>> =
    distinctUntilChangedBy { it.frames }.map { it.crossingActive to it.crossingVisible }

/**
 * Retour **automatique** au mode normal : le passage perdu plusieurs frames de
 * suite ([CrossingLossGate]) coupe le mode armé par la vision et le dit
 * (« Mode traversée coupé. »). [visionArmed] faux (mode manuel ou GPS) = le
 * mode reste, seul son propriétaire le coupe. [states] porte (actif, vu) par
 * frame publiée (voir [crossingSightPerFrame]).
 */
internal fun observeCrossingLoss(
    states: Flow<Pair<Boolean, Boolean>>,
    scope: CoroutineScope,
    language: () -> SpeechLanguage,
    visionArmed: () -> Boolean,
    setAuto: (Boolean) -> Unit,
    say: suspend (String) -> Unit,
) {
    scope.launch {
        val gate = CrossingLossGate()
        states.collect { (active, visible) ->
            if (!active || !visionArmed()) {
                gate.reset()
                return@collect
            }
            if (!gate.onFrame(visible)) {
                gate.reset()
                setAuto(false)
                say(CrossingText.mode(false, language()))
            }
        }
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
 * Ce que le guidage latéral dit sur une frame : le mot ([lateral], `null` =
 * silence, mot nouveau seul, clip [lateralClip] < 100 ms), l'arrivée
 * ([arrival], `null` = silence, « Sur le passage. »), et la géométrie qui les
 * a produits ([centerX], [meters] profondeur/sol, jamais sténopé).
 */
internal data class CrossingLateralPatch(
    val lateral: String?,
    val lateralClip: String?,
    val arrival: String?,
    val centerX: Float?,
    val meters: Float?,
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
    val depth = readings.firstOrNull { it.box == box }
        ?.meters?.takeIf { it.isFinite() && it > 0f }
    val ground = if (box.size >= 4 && pose != null) {
        CrossingDistance.groundMeters(box[3], pose, focalPx, principalY, imageH)
    } else null
    return CrossingDistance.bestMeters(depth, ground)
}

/**
 * Signal latéral de la frame : mot nouveau seul + arrivée (2 frames,
 * changement seul). Sans passage : les deux guetteurs retombent et `null` est
 * rendu. Pur hors annonceurs (injectés pour les tests JVM).
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
): CrossingLateralPatch? {
    if (width <= 0 || height <= 0) return null
    val best = CrossingGuidance.selectBest(objects)
    if (best == null) {
        lateral.onFrame(atMs, null, language)
        arrival.onFrame(false, language)
        return null
    }
    val centerX = CrossingGuidance.centerXOf(best.box, width) ?: return null
    val focalPx = lens.focalPx(width, height)
    val meters = crossingMetersOf(best.box, readings, pose, focalPx, height / 2f, height)
    val lateralPhrase = lateral.onFrame(atMs, centerX, language)
    val clip = lateralPhrase?.let { GuidanceWord.clipIdFor(it) }
    val arrived = CrossingGuidance.isArrivedPx(best.box, height, meters)
    val arrivalPhrase = arrival.onFrame(arrived, language)?.phrase
    return CrossingLateralPatch(lateralPhrase, clip, arrivalPhrase, centerX, meters)
}

/**
 * Mémoire latérale, une par processus (même garde des 500 lignes que
 * [CrossingOfferWatch]) : la sonde l'alimente à chaque frame publiée, l'écran
 * observe [flow] (mot doux + arrivée douce, jamais de « Stop » ici).
 */
internal object CrossingLateralWatch {

    private val lateral = CrossingLateralAnnouncer()
    private val arrival = CrossingArrivalWatcher()
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
            lateral, arrival, objects, width, height, readings, pose, lens, atMs, language,
        )
        _flow.value = patch
        return patch
    }

    /** Nouvelle séance. */
    fun reset() {
        lateral.reset()
        arrival.reset()
        _flow.value = null
    }
}

/**
 * Alimente le guidage latéral depuis la sonde : hors traversée le mot et
 * l'arrivée suivent chaque frame publiée ; en traversée le veto parle seul
 * et la mémoire latérale retombe (une ligne côté sonde, plafond respecté).
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
    if (traversing) CrossingLateralWatch.reset() else CrossingLateralWatch.update(
        objects, width, height, readings, pose, lens, atMs, language,
    )
}

/**
 * Parole latérale : le mot via [speakWord] (clip local < 100 ms, doux :
 * appelant `urgent = false`), l'arrivée via [say] (douce aussi). Les deux
 * guetteurs dédupliquent déjà (mot nouveau seul, arrivée changement seul) :
 * rien à filtrer de plus, le silence ne dit rien.
 */
internal fun observeCrossingLateral(
    patches: Flow<CrossingLateralPatch?>,
    scope: CoroutineScope,
    speakWord: suspend (clipId: String, text: String) -> Unit,
    say: suspend (String) -> Unit,
) {
    scope.launch {
        patches.filter { it != null && (it.lateral != null || it.arrival != null) }.collect { patch ->
            patch ?: return@collect
            val lateral = patch.lateral
            val clip = patch.lateralClip
            if (lateral != null && clip != null) runCatching { speakWord(clip, lateral) }
            val arrival = patch.arrival
            if (arrival != null) runCatching { say(arrival) }
        }
    }
}
