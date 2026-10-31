package dev.sam3vision.app.isee

import android.app.Application
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.debugModelsLine
import dev.sam3vision.app.live.chosenLens
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.curb.SidewalkWatcher
import dev.sam3vision.core.cycle.CycleWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.hazard.FenceOpeningSignal
import dev.sam3vision.core.hazard.FenceOpeningWatcher
import dev.sam3vision.core.hazard.HazardSignal
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.live.SteeringCue
import dev.sam3vision.core.live.TwinLiteWord
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairCountVoice
import dev.sam3vision.core.stairs.StairWatcher
import dev.sam3vision.core.settings.resolveSamEndpoint
import dev.sam3vision.core.traverse.VehicleStateWatcher
import kotlinx.coroutines.CoroutineScope
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
 * focale de la caméra, distances on/off, plafond d'objets.
 *
 * Les prompts effectifs suivent le monde ([Settings.outdoorMode]) quand
 * l'appelant a passé les défauts ([LivePrompts.default], [LivePrompts.indoor]
 * ou une liste vide) ; une liste explicite (Traversée feu+voitures, champ
 * « Follow », « Find the X ») traverse inchangée.
 */
internal fun LiveProbe.configureFrom(
    settings: Settings,
    prompts: List<String> = LivePrompts.default,
    crossingActive: Boolean = false,
    sensorLens: LensSpec? = null,
) {
    android.util.Log.i("DepthLive", "configure profondeur=" + settings.depthBackend)
    val effective = if (prompts.isEmpty() || prompts == LivePrompts.default || prompts == LivePrompts.indoor) {
        LivePrompts.forMode(settings.outdoorMode)
    } else {
        prompts
    }
    configure(
        baseUrl = settings.resolveSamEndpoint(BuildConfig.SAM_BASE_URL, BuildConfig.SAM_US_BASE_URL),
        prompts = effective,
        announce = settings.announcePositions,
        guide = settings.guideStraight,
        lens = chosenLens(settings.cameraFovDeg, sensorLens),
        distancesOn = settings.distanceOverlayEnabled,
        collectCrops = settings.collectLightCrops,
        maxObjects = settings.objectsOnScreen,
        language = settings.speechLanguage,
        crossingActive = crossingActive,
        modelsLine = debugModelsLine(settings),
        depth = settings.depthBackend,
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
internal fun LiveProbe.ensureConcept(settings: Settings, label: String) {
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
    configureFrom(settings, next, false)
}

/**
 * Entrée clip d'une phrase de la sonde (`null` = synthèse réseau) : mots SAM,
 * mots TwinLite. Pur, testé en JVM.
 */
internal fun spokenClipId(text: String): String? =
    GuidanceWord.clipIdFor(text) ?: TwinLiteWord.clipIdFor(text)

/**
 * Chaque phrase de la sonde est confiée à la voix, y compris redite à
 * l'identique (direction stable : [LiveProbeState.spokenSeq] distingue les
 * émissions). [playerIdle] vrai = aucune bulle du chat en cours de lecture.
 * La règle d'écrasement vit dans [observeSpokenEmissions] (répétition
 * identique en vol gardée, phrase fraîche dernier-gagnant). Les mots du
 * guidage partent en clips (< 100 ms), le reste (positions) en synthèse
 * réseau. L'urgence vient de l'état ([LiveProbeState.spokenUrgent], mot
 * stop) : seul le stop coupe la lecture en cours, chat compris — les mots
 * doux attendent le silence, en file (`queue`, redits au silence).
 */
internal fun LiveProbe.observeSpoken(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
) {
    scope.observeSpokenEmissions(
        state.map { SpokenEmission(it.spoken, it.spokenUrgent, it.spokenSeq) }.distinctUntilChanged(),
    ) { text, urgent ->
        val clipId = spokenClipId(text)
        if (clipId != null) voice.speakWord(clipId, text, language(), playerIdle(), urgent = urgent, queue = !urgent)
        else voice.onPhrase(text, playerIdle(), urgent = urgent, queue = !urgent)
    }
}

/**
 * Guetteur trottoir de la sonde : le signal est relu dans l'état (lectures de
 * distance + dimensions, déjà publiées par frame), sans toucher à `LiveProbe`
 * (499/500 lignes — tout câblage vit ici, comme `observeSpoken`). Rend le
 * guetteur pour le `reset()` de début de séance. Le palier 0,5 m
 * ([CurbTier.NEAR]) parle en alerte de sécurité (« Bordure à 0,5 mètre. »,
 * [SafetyVoice], jamais de « Stop » — ordre user du 31/10) ; la détection,
 * les paliers 2 / 1,5 / 1 / 0,7 m ([CurbTier.FAR], 0,7 m [CurbTier.NEAR])
 * et le bateau ([CurbTier.NONE]) restent doux.
 * Les paliers proches (0,7 / 0,5 m) passent en Traversée, la détection,
 * les paliers doux et le bateau y sont coupés — et la porte précède le
 * watcher (un FAR coupé ne consomme ni confirmation ni cooldown).
 * `collect` séquentiel, voix douce en coroutine fille.
 */
internal fun LiveProbe.observeCurb(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
    safety: SafetyVoice,
    watcher: CurbWatcher = CurbWatcher(),
): CurbWatcher {
    scope.launch {
        state.collect { st ->
            val readings = st.distanceLayer?.readings ?: return@collect
            val signal = CurbSignal.of(readings, st.width, st.height)
            if (st.crossingActive && signal.tier != CurbTier.NEAR) return@collect
            val cue = watcher.onSignal(signal, System.currentTimeMillis(), language()) ?: return@collect
            if (!shouldSpeakCurb(cue, st.crossingActive)) return@collect
            val alert = cue.safetyPhrase
            if (alert != null) {
                safety.speak(alert)
                return@collect
            }
            scope.launch { voice.onPhrase(cue.phrase, playerIdle(), urgent = false) }
        }
    }
    return watcher
}

/**
 * Parole bordure : les paliers proches 0,7 / 0,5 m ([CurbTier.NEAR])
 * passent toujours, même en Traversée et même après un stop (une alerte
 * de sécurité ne se tait pour rien) ; la détection et les paliers doux
 * ([CurbTier.FAR]) et le bateau ([CurbTier.NONE]) sont coupés en
 * Traversée. Pure, testée en JVM.
 */
internal fun shouldSpeakCurb(cue: CurbCue, crossingActive: Boolean): Boolean =
    cue.tier == CurbTier.NEAR || !crossingActive

/**
 * Guetteur mobilier de la sonde, miroir du trottoir : le mobilier suivi
 * ([dev.sam3vision.core.hazard.HazardConcepts]) lu dans l'état, sans toucher
 * à `LiveProbe` (lectures de distance + dimensions, déjà publiées par
 * frame). L'annonce (« Attention à la barrière. ») est douce (attend le
 * silence) : iSee décrit, en douceur — sauf le poteau imminent, urgent sauf
 * P1/P2 en cours ([p1p2Busy] : le poteau est P3). La phrase douce tue part en
 * file ([ProximityVoice.flushPending], redite au silence) : une alerte
 * confirmée ne se perd plus. Coupé en mode Traversée. Lecture séquentielle,
 * comme le trottoir : une alerte confirmée ne s'annule pas.
 */
internal fun LiveProbe.observeHazard(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
    p1p2Busy: (nowMs: Long) -> Boolean = { false },
): HazardWatcher {
    val watcher = HazardWatcher()
    scope.launch {
        state.collect { st ->
            if (st.crossingActive) return@collect
            val readings = st.distanceLayer?.readings ?: return@collect
            val nowMs = System.currentTimeMillis()
            val cue = watcher.onSignal(HazardSignal.of(readings, st.width, st.height), nowMs, language())
            if (cue == null) {
                voice.flushPending(playerIdle()) { p1p2Busy(System.currentTimeMillis()) }
                return@collect
            }
            val busyNow = p1p2Busy(nowMs)
            val urgent = shouldCutForHazard(cue.urgent, busyNow)
            voice.onPhrase(cue.phrase, playerIdle(), urgent = urgent, queue = true, busy = { busyNow })
        }
    }
    return watcher
}

/**
 * Guetteur grillage de la sonde, miroir du mobilier : les pans suivis
 * ([dev.sam3vision.core.hazard.FenceOpeningConcepts]) lus dans l'état
 * (lectures de distance + dimensions, déjà publiées par frame), sans toucher
 * à `LiveProbe`. L'annonce (« Grillage fermé devant vous. », « Passage au
 * milieu. ») est douce (attend le silence) : iSee décrit, en douceur. Coupé
 * en mode Traversée (sa voix rouge/vert est propriétaire du son). Lecture
 * séquentielle, comme le trottoir : une alerte confirmée ne s'annule pas.
 */
internal fun LiveProbe.observeFenceOpening(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): FenceOpeningWatcher {
    val watcher = FenceOpeningWatcher()
    scope.launch {
        state.collect { st ->
            if (st.crossingActive) return@collect
            val readings = st.distanceLayer?.readings ?: return@collect
            val signal = FenceOpeningSignal.of(readings, st.width, st.height)
            val cue = watcher.onSignal(signal, System.currentTimeMillis(), language()) ?: return@collect
            voice.onPhrase(cue.phrase, playerIdle(), urgent = false)
        }
    }
    return watcher
}

/**
 * Guetteur véhicules-moteurs de la sonde, miroir du mobilier : mouvement et
 * arrêt (« Voiture en mouvement, à 5 mètres. », « Voiture à l'arrêt, à 2
 * mètres. ») lus dans l'état, sans toucher à `LiveProbe`. Jamais de
 * « Stop » ici (ordre user du 31/10 : le stop ne se dit qu'en Traversée,
 * voitures en mouvement — `CrossingVehicleWatch`) : mouvement comme arrêt
 * parlent doux (seul l'`urgent` du danger qui s'approche coupe).
 * En Traversée, le veto seul parle (coupé ici) — les états moteurs se disent
 * dans la phrase traversée (`CrossingVehicleWatch`, après le feu).
 * Vélos et trottinettes exclus (voir [observeCycles]) : zéro double annonce.
 * Lecture séquentielle, comme le trottoir : une alerte confirmée ne s'annule pas.
 * Frames SAM seules (`frames`) et détections brutes (le ticker 120 ms rejoue des lectures figées qui casseraient la confirmation).
 */
internal fun LiveProbe.observeVehicleState(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
    watcher: VehicleStateWatcher = VehicleStateWatcher(),
): VehicleStateWatcher {
    var lastFrames = -1
    scope.launch {
        state.collect { st ->
            if (st.frames == lastFrames) return@collect
            lastFrames = st.frames
            if (st.crossingActive) return@collect
            val readings = st.distanceLayer?.readings.orEmpty()
            if (st.samDetections.isEmpty() && readings.isEmpty()) return@collect
            val motion = ProbeBackgroundMotion.lastMotion
            val cue = watcher.update(st.samDetections, readings, System.currentTimeMillis(), language(), motion)
                ?: return@collect
            logMotionTransition(watcher.cameraMoved, watcher.lastMotionVerdicts.values, st.width, motion?.dtMs ?: 0L)
            voice.onPhrase(cue.phrase, playerIdle(), urgent = cue.urgent)
        }
    }
    return watcher
}

/**
 * Guetteur cycles de la sonde, miroir des véhicules : vélos et trottinettes,
 * en mouvement ou arrêtés (« Attention, vélo en mouvement, à 2 heures. »),
 * lus dans l'état, sans toucher à `LiveProbe`. Doux et coupé en Traversée,
 * (coupé en Traversée, sans voix douce : le veto seul couvre les cycles).
 * Lecture séquentielle, comme le
 * trottoir : une alerte confirmée ne s'annule pas.
 */
internal fun LiveProbe.observeCycles(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): CycleWatcher {
    val watcher = CycleWatcher()
    scope.launch {
        state.collect { st ->
            if (st.crossingActive) return@collect
            val readings = st.distanceLayer?.readings ?: return@collect
            val cue = watcher.update(
                detectionsOf(readings, st.width, st.height), readings, System.currentTimeMillis(), language())
                ?: return@collect
            voice.onPhrase(cue.phrase, playerIdle(), urgent = false)
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
 * Phrase à dire pour la frame (`null` = silence) : voix rouge/vert en
 * Traversée, sinon guidage (cap route, sinon cap manuel) puis positions.
 * Extraite de `LiveProbe.publish` (plafond 50 lignes/fonction).
 */
internal fun LiveProbe.phraseOf(
    cross: CrossingPatch?,
    placed: List<PlacedLabel>,
    readings: List<DistanceReading>,
    detections: List<Detection>,
    frame: LiveFrame,
    at: Long,
    language: SpeechLanguage,
    hfovDeg: Float,
    scanTrigger: Boolean = false,
): String? {
    if (state.value.crossingActive) {
        return crossingPhraseOf(cross?.cue, placed, detections,
            readings, at, language, frame.objects, frame.width, frame.height, scanTrigger)
    }
    resetCrossingWatches()
    return voice.phrase(at, placed, state.value.steeringCap, frame.passage, hfovDeg = hfovDeg)
}

/**
 * Guetteur trottoir-nommé de la sonde, miroir des dangers : la position lue
 * dans la voix guidée (rien dans l'état ne porte le plan du trottoir, non
 * mesuré), sans toucher à `LiveProbe`. L'annonce (« Trottoir à gauche. »)
 * est douce (attend le silence) : une info de navigation, jamais une
 * consigne — l'ordre de retour passe par les mots du guidage. Coupé en mode
 * Traversée et guidage coupé (la voix des positions nomme déjà le trottoir).
 * Lecture séquentielle, comme les dangers : une alerte confirmée ne s'annule pas.
 */
internal fun LiveProbe.observeSidewalk(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): SidewalkWatcher {
    val watcher = SidewalkWatcher()
    scope.launch {
        state.collect { st ->
            if (st.crossingActive) return@collect
            val position = this@observeSidewalk.voice.lastSidewalk
            val cue = watcher.onPosition(position, System.currentTimeMillis(), language()) ?: return@collect
            voice.onPhrase(cue.phrase, playerIdle(), urgent = false)
        }
    }
    return watcher
}

/** Les huit guetteurs de la sonde (bordure + escaliers + compteur + mobilier + grillage + véhicules + cycles + trottoir), reset commun. */
internal class ProbeHazards(
    val curb: CurbWatcher,
    val stairs: StairWatcher,
    val count: StairCountVoice,
    val hazard: HazardWatcher,
    val fence: FenceOpeningWatcher,
    val vehicle: VehicleStateWatcher,
    val cycle: CycleWatcher,
    val sidewalk: SidewalkWatcher,
) {
    /** Oublie les huit guetteurs et le compte caméra (nouvelle séance). */
    fun reset() {
        curb.reset()
        stairs.reset()
        count.reset()
        hazard.reset()
        fence.reset()
        vehicle.reset()
        cycle.reset()
        sidewalk.reset()
        StairCannyCount.clear()
    }
}

/**
 * Les huit guetteurs en un appel : le ViewModel n'y tient qu'une ligne
 * (plafond 500 — voir `observeCurb`, même contrat). Bordure et véhicules sont
 * construits ici pour que le mobilier lise P1/P2 ([p1p2Busy] : le poteau est P3).
 * [stairsAudible] est la porte des deux voix escaliers (GPS coupé : parlent
 * quand même, sécurité — contrat `IseeVoiceGate`).
 */
internal fun LiveProbe.observeHazards(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
    safety: SafetyVoice,
    stairsAudible: () -> Boolean = playerIdle,
): ProbeHazards {
    val curb = CurbWatcher()
    val vehicle = VehicleStateWatcher()
    val busy: (Long) -> Boolean = { nowMs -> p1p2Busy(nowMs, vehicle.lastMovingAlertMs, curb.lastNearMs) }
    return ProbeHazards(
        observeCurb(scope, voice, playerIdle, language, safety, curb),
        observeStairs(scope, voice, stairsAudible, language),
        observeStairsCount(scope, voice, stairsAudible, language),
        observeHazard(scope, voice, playerIdle, language, busy),
        observeFenceOpening(scope, voice, playerIdle, language),
        observeVehicleState(scope, voice, playerIdle, language, vehicle),
        observeCycles(scope, voice, playerIdle, language),
        observeSidewalk(scope, voice, playerIdle, language),
    )
}


