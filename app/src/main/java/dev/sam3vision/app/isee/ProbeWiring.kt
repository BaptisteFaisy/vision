package dev.sam3vision.app.isee

import android.app.Application
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.debugModelsLine
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
import dev.sam3vision.core.live.SteeringCue
import dev.sam3vision.core.live.TwinLiteWord
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairConcepts
import dev.sam3vision.core.stairs.StairLabelSignals
import dev.sam3vision.core.stairs.StairSignal
import dev.sam3vision.core.stairs.StairWatcher
import dev.sam3vision.core.settings.resolveSamEndpoint
import dev.sam3vision.core.traverse.VehicleStateWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
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
 * « Suivre ») traverse inchangée.
 */
internal fun LiveProbe.configureFrom(
    settings: Settings,
    prompts: List<String> = LivePrompts.default,
    crossingActive: Boolean = false,
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
        lens = LensSpec.fromSettings(settings.cameraFovDeg),
        distancesOn = settings.distanceOverlayEnabled,
        maxObjects = settings.objectsOnScreen,
        language = settings.speechLanguage,
        crossingActive = crossingActive,
        modelsLine = debugModelsLine(settings),
        depth = settings.depthBackend,
    )
}

/**
 * Ordre « Find the X » : [label] rejoint les concepts SAM de la sonde quand
 * il n'y est pas déjà (comparaison insensible à la casse) — la session
 * repart comme à tout changement de prompts. En mode Traversée les prompts
 * feu+voitures ne sont pas touchés (sécurité) ; un 17e concept ou un label
 * de plus de 32 caractères est refusé par [LivePrompts.normalize], sans
 * reconfiguration. Pur hors sonde, testé en JVM.
 */
internal fun LiveProbe.ensureConcept(settings: Settings, label: String) {
    if (state.value.crossingActive) return
    val clean = label.trim()
    if (clean.isEmpty()) return
    val current = state.value.conceptsSent
    if (current.any { it.trim().equals(clean, ignoreCase = true) }) return
    val next = LivePrompts.normalize(current + clean)
    if (next == current) return
    android.util.Log.i("sam3vision", "find : « $clean » ajouté aux masques SAM (${next.size} suivis)")
    configureFrom(settings, next, false)
}

/**
 * Chaque nouvelle phrase de la sonde est confiée à la voix (une seule fois par
 * phrase). [playerIdle] vrai = aucune bulle du chat en cours de lecture.
 * Dernier-gagnant : une phrase plus fraîche annule la synthèse en cours au lieu
 * de faire la queue derrière — une consigne datée ne sert à rien. La même phrase
 * ré-émise par les frames suivantes n'annule rien (`distinctUntilChanged`) :
 * annuler à chaque frame, c'était ne jamais finir une synthèse lente.
 * Les mots du guidage partent en clips (< 100 ms), le reste (positions) en
 * synthèse réseau. L'urgence vient de l'état ([LiveProbeState.spokenUrgent],
 * mot stop) : seul le stop coupe la lecture en cours, chat compris
 * (« obstacle, arrêtez-vous. » TwinLite, « Stop. » SAM urgence externe) —
 * les mots doux attendent le silence. Le flag ne change qu'avec la phrase,
 * donc la paire reste aussi stable que la phrase seule.
 */
internal fun LiveProbe.observeSpoken(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
) {
    scope.launch {
        state.map { it.spoken to it.spokenUrgent }.distinctUntilChanged().collectLatest { (phrase, urgent) ->
            val text = phrase ?: return@collectLatest
            val clipId = GuidanceWord.clipIdFor(text) ?: TwinLiteWord.clipIdFor(text)
            if (clipId != null) voice.speakWord(clipId, text, language(), playerIdle(), urgent = urgent)
            else voice.onPhrase(text, playerIdle(), urgent = urgent)
        }
    }
}

/**
 * Guetteur trottoir de la sonde : le signal est relu dans l'état (lectures de
 * distance + dimensions, déjà publiées par frame), sans toucher à `LiveProbe`
 * (499/500 lignes — tout câblage vit ici, comme `observeSpoken`). Rend le
 * guetteur pour le `reset()` de début de séance. La marche imminente
 * ([CurbTier.NEAR]) parle en urgent, même en mode Traversée (un bord sous le
 * pied ne attend pas le vert) ; la pré-alerte ([CurbTier.FAR]) et le bateau
 * ([CurbTier.NONE]) sont doux et coupés en Traversée (sa voix rouge/vert est
 * propriétaire du son). Priorités ([ProbeVoice.stopRecent]) : stop, puis
 * marche imminente (sauf stop parlé il y a moins de 2 s), puis le reste.
 * Lecture séquentielle (`collect`) : une alerte confirmée est rare, et elle
 * ne doit pas être annulée par la frame suivante.
 */
internal fun LiveProbe.observeCurb(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): CurbWatcher {
    val watcher = CurbWatcher()
    scope.launch {
        state.collect { st ->
            val readings = st.distanceLayer?.readings ?: return@collect
            val signal = CurbSignal.of(readings, st.width, st.height)
            val nowMs = System.currentTimeMillis()
            val cue = watcher.onSignal(signal, nowMs, language()) ?: return@collect
            if (!shouldSpeakCurb(cue, st.crossingActive, this@observeCurb.voice.stopRecent(nowMs))) return@collect
            voice.onPhrase(cue.phrase, playerIdle(), urgent = cue.tier == CurbTier.NEAR)
        }
    }
    return watcher
}

/**
 * Parole bordure : la marche imminente ([CurbTier.NEAR]) passe même en
 * Traversée, sauf stop parlé il y a moins de 2 s (le stop prime) ; la
 * pré-alerte ([CurbTier.FAR]) et le bateau ([CurbTier.NONE]) sont doux et
 * coupés en Traversée. Pure, testée en JVM.
 */
internal fun shouldSpeakCurb(cue: CurbCue, crossingActive: Boolean, stopRecent: Boolean): Boolean = when {
    cue.tier == CurbTier.NEAR && stopRecent -> false
    cue.tier == CurbTier.NEAR -> true
    crossingActive -> false
    else -> true
}

/**
 * Guetteur escaliers de la sonde, miroir du trottoir : les libellés SAM
 * « stairs » lus dans l'état (lectures de distance + dimensions, déjà
 * publiées par frame), sans toucher à `LiveProbe`. Le sens vient des mots du
 * libellé (« stairs going up », exact) sinon de la hauteur de la boîte
 * ([StairLabelSignals]) ; l'annonce est douce (attend le silence) : une info
 * de navigation, jamais une consigne d'évitement. Coupé en mode Traversée.
 * Voix montée : compte à rebours (« Plus que N marches. ») puis dernière
 * marche, par compteur lissé ou par profondeur (< 1 m, lue ici — la voie
 * labels ne compte pas). Lecture séquentielle, comme le trottoir : une alerte
 * confirmée ne s'annule pas.
 */
internal fun LiveProbe.observeStairs(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): StairWatcher {
    // Deux frames suffisent côté voix (le flux Canny d'affichage garde trois) :
    // un escalier nommé deux fois de suite n'est plus un clignotement.
    val watcher = StairWatcher(confirmFrames = 2)
    scope.launch {
        state.collect { st ->
            if (st.crossingActive) return@collect
            val readings = st.distanceLayer?.readings ?: return@collect
            val found = StairLabelSignals.of(readings, st.width, st.height)
            // Profondeur du marchement le plus proche : la dernière marche par
            // profondeur (< 1 m) parle même quand le compteur est indisponible
            // (voie labels : lineCount = 0 = non compté, pas « zéro marche »).
            val stairsMeters = readings.filter { StairConcepts.isStairs(it.label) }
                .mapNotNull { it.meters.takeIf { m -> m.isFinite() } }.minOrNull()
            val signal = if (found.detected) {
                StairSignal(detected = true, confidence = 1f, lineCount = 0, roiTop = 0, roiBottom = 0)
            } else {
                StairSignal.none()
            }
            val cue = watcher.onSignal(
                signal, System.currentTimeMillis(), language(), found.direction, found.centerX, stairsMeters)
                ?: return@collect
            voice.onPhrase(cue.phrase, playerIdle(), urgent = false)
        }
    }
    return watcher
}

/**
 * Guetteur mobilier de la sonde, miroir du trottoir : le mobilier suivi
 * ([dev.sam3vision.core.hazard.HazardConcepts]) lu dans l'état, sans toucher
 * à `LiveProbe` (lectures de distance + dimensions, déjà publiées par
 * frame). L'annonce (« Attention à la barrière. ») est douce (attend le
 * silence) : iSee décrit, en douceur. Coupé en mode Traversée. Lecture
 * séquentielle, comme le trottoir : une alerte confirmée ne s'annule pas.
 */
internal fun LiveProbe.observeHazard(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): HazardWatcher {
    val watcher = HazardWatcher()
    scope.launch {
        state.collect { st ->
            if (st.crossingActive) return@collect
            val readings = st.distanceLayer?.readings ?: return@collect
            val signal = HazardSignal.of(readings, st.width, st.height)
            val cue = watcher.onSignal(signal, System.currentTimeMillis(), language()) ?: return@collect
            voice.onPhrase(cue.phrase, playerIdle(), urgent = false)
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
 * mètres. ») lus dans l'état, sans toucher à `LiveProbe`. L'annonce est
 * douce (attend le silence) : en Traversée, le veto seul parle (coupé ici).
 * Vélos et trottinettes exclus (voir [observeCycles]) : zéro double annonce.
 * Lecture séquentielle, comme le trottoir : une alerte confirmée ne s'annule pas.
 */
internal fun LiveProbe.observeVehicleState(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): VehicleStateWatcher {
    val watcher = VehicleStateWatcher()
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
 * Guetteur cycles de la sonde, miroir des véhicules : vélos et trottinettes,
 * en mouvement ou arrêtés (« Attention, vélo en mouvement, à 2 heures. »),
 * lus dans l'état, sans toucher à `LiveProbe`. Doux et coupé en Traversée,
 * comme les véhicules (le veto seul parle). Lecture séquentielle, comme le
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
 * Traversée (ses prompts feu+voitures ne disent pas où marcher).
 */
internal fun LiveProbe.steeringCueOf(): SteeringCue? =
    if (state.value.crossingActive) null else voice.cue()

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

/** Les sept guetteurs de la sonde (bordure + escaliers + mobilier + grillage + véhicules + cycles + trottoir), reset commun. */
internal class ProbeHazards(
    val curb: CurbWatcher,
    val stairs: StairWatcher,
    val hazard: HazardWatcher,
    val fence: FenceOpeningWatcher,
    val vehicle: VehicleStateWatcher,
    val cycle: CycleWatcher,
    val sidewalk: SidewalkWatcher,
) {
    /** Oublie les sept guetteurs (nouvelle séance). */
    fun reset() {
        curb.reset()
        stairs.reset()
        hazard.reset()
        fence.reset()
        vehicle.reset()
        cycle.reset()
        sidewalk.reset()
    }
}

/**
 * Les sept guetteurs en un appel : le ViewModel n'y tient qu'une ligne
 * (plafond 500 — voir `observeCurb`, même contrat).
 */
internal fun LiveProbe.observeHazards(
    scope: CoroutineScope,
    voice: ProximityVoice,
    playerIdle: () -> Boolean,
    language: () -> SpeechLanguage,
): ProbeHazards = ProbeHazards(
    observeCurb(scope, voice, playerIdle, language),
    observeStairs(scope, voice, playerIdle, language),
    observeHazard(scope, voice, playerIdle, language),
    observeFenceOpening(scope, voice, playerIdle, language),
    observeVehicleState(scope, voice, playerIdle, language),
    observeCycles(scope, voice, playerIdle, language),
    observeSidewalk(scope, voice, playerIdle, language),
)