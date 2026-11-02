package dev.sam3vision.app.isee

import android.app.Application
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.debugModelsLine
import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.live.LensOrigin
import dev.sam3vision.app.live.chooseLens
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.curb.RailState
import dev.sam3vision.core.curb.SidewalkRail
import dev.sam3vision.core.curb.SidewalkWatcher

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.hazard.FenceOpeningSignal
import dev.sam3vision.core.hazard.FenceOpeningWatcher
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.live.FreeSpace
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.PersonTracker
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.live.SteeringCue
import dev.sam3vision.core.live.Timing
import dev.sam3vision.core.live.TwinLiteWord
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairConcepts
import dev.sam3vision.core.stairs.StairCountVoice
import dev.sam3vision.core.stairs.StairWatcher
import dev.sam3vision.core.settings.resolveSamEndpoint
import dev.sam3vision.core.traverse.FrameMotion
import dev.sam3vision.core.traverse.VehicleStateWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Câblage de la sonde SAM de la page caméra : réglages, voix, fin d'enregistrement.
 *
 * Isolé du ViewModel pour que celui-ci reste sous la limite de 500 lignes : la
 * logique est la même, elle vit simplement à côté.
 */

/**
 * Pack voix des mots du guidage : clips embarqués (`res/raw/vp_guide_*` SAM,
 * `res/raw/vp_tl_*` TwinLite obstacle) puis cache par voix, voix du pack lue
 * dans `assets/vp_voice.txt`. Tout accès est gardé : sans pack, la voix
 * synthétise (plus lent, mais parle).
 */
internal fun guideVoicePack(app: Application): VoicePack = VoicePack(
    filesDir = app.filesDir,
    packVoiceId = {
        runCatching { app.assets.open("vp_voice.txt").bufferedReader().readText().trim() }.getOrNull()
    },
    bundledClip = { name -> rawClip(app, name) },
)

/** Clip embarqué `res/raw` par nom de fichier (`vp_guide_left_fr.mp3`), ou `null`. */
private fun rawClip(app: Application, fileName: String): ByteArray? = runCatching {
    val base = fileName.substringBeforeLast('.')
    val id = app.resources.getIdentifier(base, "raw", app.packageName)
    if (id == 0) return null
    app.resources.openRawResource(id).use { it.readBytes() }
}.getOrNull()

/**
 * Applique les Réglages à la sonde : endpoint résolu (saisie manuelle > région
 * EU/US > URL du build), monde sensoriel (dehors/dedans), guidage « tout droit »
 * (il prime sur l'annonce), annonce vocale des objets et de leur distance,
 * focale de la caméra, distances on/off, plafond d'objets. L'annonce et le
 * guidage suivent les bascules brutes (clé non exigée) : sans clé, la voix de
 * proximité parle au TTS système (repli + alerte unique) au lieu de se taire.
 *
 * Les prompts effectifs suivent le monde ([Settings.outdoorMode]) quand
 * l'appelant a passé les défauts (`default`, `indoor`, vide) ; une liste
 * explicite (Traversée, « Follow », « Find the X ») traverse inchangée.
 */
internal fun LiveProbe.configureFrom(
    settings: Settings,
    prompts: List<String> = LivePrompts.default,
    crossingActive: Boolean = false,
    sensorLens: LensSpec? = null,
    onLens: ((LensOrigin) -> Unit)? = null,
    glassesLens: LensSpec? = null,
    fromGlasses: Boolean = GlassesVideoLink.live.value,
) {
    val effective = if (prompts.isEmpty() || prompts == LivePrompts.default || prompts == LivePrompts.indoor) {
        LivePrompts.forMode(settings.outdoorMode)
    } else {
        prompts
    }
    val forced = if (fromGlasses) settings.glassesFovDeg else settings.cameraFovDeg
    val chosen = chooseLens(forced, sensorLens, glassesLens, fromGlasses)
    GlassesVideoLink.glassesStreamLens = if (fromGlasses) chosen.spec else null
    onLens?.invoke(chosen.origin)
    configure(
        baseUrl = settings.resolveSamEndpoint(BuildConfig.SAM_BASE_URL, BuildConfig.SAM_US_BASE_URL),
        prompts = effective,
        announce = settings.ttsAnnouncePositions,
        guide = settings.ttsGuidanceStraight,
        lens = chosen.spec,
        distancesOn = settings.distanceOverlayEnabled,
        collectCrops = settings.collectLightCrops,
        maxObjects = settings.objectsOnScreen,
        language = settings.speechLanguage,
        crossingActive = crossingActive,
        modelsLine = debugModelsLine(settings),
        followed = LivePrompts.followedOf(effective, settings.outdoorMode),
    )
    adaptive.configure(settings.adaptiveUploadEnabled, settings.keyframeSchedulerEnabled)
}

/** Cap de l'itinéraire : tant qu'il est posé, la sonde marche son couloir. */
fun LiveProbe.setRouteCap(cap: SteeringCap) {
    voice.routeCap = cap
}

/** Fin du guidage route : le cap manuel reprend. */
fun LiveProbe.clearRouteCap() {
    voice.routeCap = null
}

/**
 * Ordre « Find the X » : [label] rejoint les concepts SAM de la sonde quand
 * il n'y est pas déjà (comparaison insensible à la casse) — la session
 * repart comme à tout changement de prompts. En mode Traversée on ne
 * reconfigure pas (sécurité : pas de coupure en pleine traversée) ; au
 * plafond, le dernier concept cède son slot à l'ordre explicite
 * ([LivePrompts.withConcept]), seul un label vide ou de plus de 32
 * caractères est refusé, sans reconfiguration. Pur hors sonde, testé en JVM.
 */
internal fun LiveProbe.ensureConcept(settings: Settings, label: String, sensorLens: LensSpec? = null, onLens: ((LensOrigin) -> Unit)? = null, glassesLens: LensSpec? = null) {
    if (state.value.crossingActive) return
    val clean = label.trim()
    if (clean.isEmpty()) return
    val current = state.value.conceptsSent
    if (current.any { it.trim().equals(clean, ignoreCase = true) }) return
    val next = LivePrompts.withConcept(current, clean)
    if (next == current) return
    val evicted = if (current.size >= LivePrompts.MAX) current.lastOrNull() else null
    val detail = if (evicted == null) "${next.size} suivis" else "${next.size} suivis, « $evicted » sorti"
    android.util.Log.i("sam3vision", "find : « $clean » ajouté aux masques SAM ($detail)")
    configureFrom(settings, next, false, sensorLens, onLens, glassesLens)
}


/**
 * Voix parlée de la sonde (SPEC v4) : la dernière phrase décidée (`spoken`)
 * lue dans l'état. Le mot stop part niveau 4 (blocage, sous clé `fence`) ;
 * les mots du guidage vont au repli 12 ([FallbackVoice]) ; les positions ne
 * parlent pas ici (niveaux 2 et 7). La parole du user passe par le bus
 * ([VoiceBus.userSpeaking], câblé au ViewModel), pas par un coupeur local.
 * Dehors uniquement : dedans, stop et repli se taisent (chaîne dedans :
 * find, user, humain, chat, porte et murs).
 */
internal fun LiveProbe.observeSpoken(
    scope: CoroutineScope,
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEHORS },
) {
    scope.observeSpokenEmissions(
        state.map { SpokenEmission(it.spoken, it.spokenUrgent, it.spokenSeq) }.distinctUntilChanged(),
    ) { text, urgent ->
        if (placeMode() != PictoPlaceMode.DEHORS) return@observeSpokenEmissions
        if (urgent || GuidanceWord.isStopPhrase(text)) {
            if (AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis())) {
                VoiceBus.post(OutdoorLevel.FENCE, text, spokenClipId(text))
            }
        } else {
            FallbackVoice.offer(text)
        }
    }
}

/**
 * Guetteur grillage de la sonde (SPEC v4, niveau 4) : les pans suivis lus
 * dans l'état, postés au bus avec le niveau et la clé du signalement, et rapportés au battement 700 ms ([VoiceTick]).
 * Coupé en mode Traversée (le verdict y est propriétaire du son).
 */
internal fun LiveProbe.observeFenceOpening(
    scope: CoroutineScope,
    language: () -> SpeechLanguage,
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEHORS },
): FenceOpeningWatcher {
    val watcher = FenceOpeningWatcher()
    scope.launch {
        state.collect { st ->
            if (st.crossingActive) return@collect
            if (placeMode() != PictoPlaceMode.DEHORS) return@collect
            val readings = st.distanceLayer?.readings ?: return@collect
            val signal = FenceOpeningSignal.of(readings, st.width, st.height)
            val cue = watcher.onSignal(signal, System.currentTimeMillis(), language()) ?: return@collect
            VoiceTick.report(
                VoiceTick.SRC_FENCE,
                VoiceWish(cue.level, cue.phrase, cue.antiRepeatKey),
                System.currentTimeMillis(),
            )
            if (AntiRepeat.allow(cue.antiRepeatKey, System.currentTimeMillis())) {
                VoiceBus.post(cue.level, cue.phrase)
            }
        }
    }
    return watcher
}


/**
 * Détections reconstruites des lectures de distance : boîtes pixels SAM déjà
 * confirmées (score 1 : le seuil 0,5 du veto garde les brutes, pas les
 * relues). Les boîtes illisibles sont filtrées (les guetteurs les ignorent
 * aussi). Pur, testé en JVM.
 */
internal fun detectionsOf(readings: List<DistanceReading>, width: Int, height: Int): List<Detection> =
    readings.filter { it.box.size >= 4 }.map { Detection(it.label, 1f, it.box, width, height) }

/**
 * Détour obstacle de la sonde : le pilote GPS y lit le détour subi (recalcul
 * Valhalla depuis le GPS temps réel s'il dure — `VoiceNavEngineDriver`).
 * Changements seuls (`distinctUntilChanged`) : le détecteur compte le
 * continu, pas les frames.
 */
internal fun LiveProbe.observeDetour(scope: CoroutineScope, onDetour: (Boolean, Long) -> Unit) {
    scope.launch {
        state.map { it.detour }.distinctUntilChanged().collect { detour ->
            onDetour(detour, System.currentTimeMillis())
        }
    }
}

/**
 * Chemin dessiné sur la vidéo : lu dans la décision parlée à cette frame
 * (la vidéo est en direct, la voix seule est débouncée). `null` en
 * Traversée (la voix rouge/vert y est propriétaire, pas le guidage).
 */
internal fun LiveProbe.steeringCueOf(): SteeringCue? =
    if (state.value.crossingActive) null else voice.cue()

/**
 * Compteur de marches actif sur cette frame : SAM nomme l'escalier
 * (lectures d'abord, libellés bruts en repli — miroir [stairsFoundOf]) et
 * la caméra compte des marches fraîches ([lineCount] > 0, 0 = non compté).
 * Pur, testé en JVM.
 */
internal fun stairsCounterActive(
    readings: List<DistanceReading>,
    objects: List<SegObject>,
    lineCount: Int,
): Boolean {
    if (lineCount <= 0) return false
    if (readings.any { StairConcepts.isStairs(it.label) }) return true
    return objects.any { StairConcepts.isStairs(it.label) }
}

/**
 * Pistes de personnes de la séance : une identité par promeneur d'une frame à
 * l'autre (audit foule du 01/11 — sans identité, ni « il vient vers vous », ni
 * « déjà prévenu »). Une instance pour toute l'app (un seul `LiveProbe`, posé par
 * `IseeViewModel`), remise à zéro avec les autres resets de séance
 * ([ProbeHazards.reset]) ; les ids continuent d'avancer, un id d'avant ne
 * désigne jamais quelqu'un d'après. Du câblage, pas du transport.
 */
internal object ProbeTracks {
    /** Suivi de la séance courante : la seule instance, alimentée à chaque frame. */
    val tracker = PersonTracker()

    /** Nouvelle séance : plus aucune identité (voir [PersonTracker.reset]). */
    fun reset() = tracker.reset()
}

/**
 * Une frame prête à **décider** : les étiquettes de la scène recopiées avec leur
 * `trackId` et leur `closingMps` ([labels], par [PersonTracker]), les trois chiffres
 * que le guidage en tire — [ageMs], [speedMps], [free] — et le rail [rail]
 * ([SidewalkRail.of], mètres lus sur la bordure). Matière de `GuidanceAnnouncer`.
 */
internal data class ProbeGuideFrame(
    val labels: List<PlacedLabel>,
    val ageMs: Long,
    val speedMps: Float,
    val free: FreeSpace,
    val rail: RailState = RailState.none(),
)

/**
 * Frame de décision : alimente le suivi à [atMs], puis en tire les étiquettes
 * identifiées, l'âge, la vitesse de marche, la place libre et le rail trottoir. Pure
 * hors du tracker (horloge et chronologie fournies), donc testée en JVM : deux frames de
 * la même personne rendent le même `trackId`, 700 ms de chronologie un `ageMs` de 700.
 */
internal fun probeGuideFrameOf(
    tracker: PersonTracker,
    atMs: Long,
    timings: FrameTimings,
    labels: List<PlacedLabel>,
    hfovDeg: Float,
): ProbeGuideFrame {
    val placed = tracker.onFrame(atMs, labels).map { it.label }
    return ProbeGuideFrame(
        labels = placed,
        ageMs = frameAgeMsOf(atMs, timings),
        speedMps = Timing.WALK_MPS,
        free = FreeSpace.of(placed, hfovDeg),
        rail = SidewalkRail.of(placed, hfovDeg),
    )
}

/**
 * Phrase à dire pour la frame (`null` = silence, SPEC v4) : silence en
 * Traversée (le verdict parle au bus), sinon guidage (cap route, sinon cap
 * manuel) puis positions. Compteur de marches actif : seul l'évitement
 * parle (virage, stop), le reste se tait devant le compte. Le suivi
 * alimente **chaque** frame ; le guidage reçoit les étiquettes identifiées
 * ([ProbeGuideFrame.labels]) et le rail trottoir de la frame.
 */
internal fun LiveProbe.phraseOf(
    placed: List<PlacedLabel>,
    readings: List<DistanceReading>,
    frame: LiveFrame,
    at: Long,
    language: SpeechLanguage,
    hfovDeg: Float,
): String? {
    val guide = probeGuideFrameOf(ProbeTracks.tracker, at, state.value.timings, placed, hfovDeg)
    // Traversée : silence — le verdict parle au bus (niveau 100, nourri dans
    // `computeRest`), humains et bordures via le guetteur obstacles (101/102).
    if (state.value.crossingActive) return null
    resetCrossingWatches()
    val phrase = voice.phrase(at, guide.labels, state.value.steeringCap,
        frame.passage, hfovDeg = hfovDeg)
    if (phrase != null && !preemptsCrossing(phrase) &&
        stairsCounterActive(readings, frame.objects, StairCannyCount.lineCount())
    ) {
        return null
    }
    return phrase
}

/**
 * Guetteur trottoir-nommé de la sonde (SPEC v4, niveau 10) : la position lue
 * dans la voix guidée (rien dans l'état ne porte le plan du trottoir, non
 * mesuré), postée au bus avec le niveau et la clé du signalement, et au battement 700 ms ([VoiceTick]). Coupé en
 * mode Traversée (le verdict y est propriétaire du son).
 */
internal fun LiveProbe.observeSidewalk(
    scope: CoroutineScope,
    language: () -> SpeechLanguage,
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEHORS },
): SidewalkWatcher {
    val watcher = SidewalkWatcher()
    scope.launch {
        state.collect { st ->
            if (st.crossingActive) return@collect
            if (placeMode() != PictoPlaceMode.DEHORS) return@collect
            val position = this@observeSidewalk.voice.lastSidewalk
            val cue = watcher.onPosition(position, System.currentTimeMillis(), language()) ?: return@collect
            VoiceTick.report(
                VoiceTick.SRC_SIDEWALK,
                VoiceWish(cue.level, cue.phrase, cue.antiRepeatKey),
                System.currentTimeMillis(),
            )
            if (AntiRepeat.allow(cue.antiRepeatKey, System.currentTimeMillis())) {
                VoiceBus.post(cue.level, cue.phrase)
            }
        }
    }
    return watcher
}

/** Les sept guetteurs de la sonde (bordure + escaliers + compteur + mobilier + grillage + véhicules + trottoir), reset commun. */
internal class ProbeHazards(
    val curb: CurbWatcher,
    val stairs: StairWatcher,
    val count: StairCountVoice,
    val hazard: HazardWatcher,
    val fence: FenceOpeningWatcher,
    val vehicle: VehicleStateWatcher,
    val sidewalk: SidewalkWatcher,
) {
    /** Oublie les sept guetteurs, le compte caméra et les pistes de personnes (nouvelle séance). */
    fun reset() {
        curb.reset()
        stairs.reset()
        count.reset()
        hazard.reset()
        fence.reset()
        vehicle.reset()
        sidewalk.reset()
        StairCannyCount.clear()
        ProbeTracks.reset()
    }
}

/**
 * Les sept guetteurs en un appel : le ViewModel n'y tient qu'une ligne
 * (plafond 500 — voir `observeSpoken`, même contrat). Véhicules, bordure et
 * mobilier partagent le guetteur obstacles (une frame, un seul gagnant au
 * bus). Tous postent au bus ([VoiceBus]) : plus de voix directe ici. Le battement voix 700 ms ([observeVoiceTick]) démarre avec eux.
 */
internal fun LiveProbe.observeHazards(
    scope: CoroutineScope,
    language: () -> SpeechLanguage,
    placeMode: () -> PictoPlaceMode = { PictoPlaceMode.DEHORS },
): ProbeHazards {
    val obstacles = observeObstacles(scope, language, placeMode = placeMode)
    observeVoiceTick(scope, indoor = { placeMode() == PictoPlaceMode.DEDANS })
    return ProbeHazards(
        obstacles.curb,
        observeStairs(scope, language, placeMode = placeMode),
        observeStairsCount(scope, language, placeMode = placeMode),
        obstacles.hazard,
        observeFenceOpening(scope, language, placeMode = placeMode),
        obstacles.vehicle,
        observeSidewalk(scope, language, placeMode = placeMode),
    )
}
