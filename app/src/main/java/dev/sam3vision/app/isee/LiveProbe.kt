package dev.sam3vision.app.isee

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.live.FrameTimings
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.ScreenTimings
import dev.sam3vision.app.ForegroundTracker
import dev.sam3vision.app.GpuWake
import dev.sam3vision.app.MASK_ALPHA
import dev.sam3vision.app.maskColorAt
import dev.sam3vision.app.TRANSPORT_WS
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.Cadence
import dev.sam3vision.core.live.FrameLabels
import dev.sam3vision.core.live.FramePayload
import dev.sam3vision.core.live.FrameRate
import dev.sam3vision.core.live.LatestFrameSlot
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.live.LiveSession
import dev.sam3vision.core.live.LiveTimings
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.app.LiveMaskTransport
import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.app.live.LiveAdaptive
import dev.sam3vision.app.live.LiveFrameCadence
import dev.sam3vision.core.live.placedLabels
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.traverse.TraverseVeto
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LiveClient
import dev.sam3vision.core.net.WireMarks
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Analyse SAM de la caméra d'iSee : quand la caméra est active, chaque frame part à
 * `/live` (une à la fois, cadence adaptative) et revient en masques, distances et
 * statistiques. Caméra coupée, `stop` ferme les sessions : la carte s'éteint.
 *
 * Même brique que le Direct ([LiveSession], [HttpLiveClient], [DistanceMeter],
 * [PositionAnnouncer]) : une seule implémentation de l'appel SAM et de la mesure par
 * frame, deux écrans qui la montrent.
 */
class LiveProbe(
    private val scope: CoroutineScope,
    /** Horloge monotone en ns (même base qu'`imageInfo.timestamp`) ; injectable en test. */
    private val clock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    /** Exécuteur du travail hors thread principal : sérialisation de la frame, masques. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
    /** Moteur (Réglages) : lu via this@LiveProbe (un défaut y verrait le paramètre OFF). */
    @Volatile
    private var depthEngine: DepthBackend = DepthBackend.OFF,
    /**
     * Fabrique de client du direct : les tests y branchent un faux transport, sans réseau.
     * Les knobs du codec masques ([LiveMaskTransport]) voyagent avec le client : la grille
     * réduite demandée au serveur est alors celle que le client sait relire.
     */
    clientFor: ((String, Long, WireMarks?) -> LiveClient)? = null,
    /** Contexte app (moteur de profondeur) ; `null` = profondeur désactivée (tests). */
    appContext: Context? = null,
    /** Mètre de profondeur : les tests y branchent un faux, sans modèle ni Android. */
    depthMeter: DepthMeter? = null,
) {

    private val openClient = clientFor ?: { base: String, timeout: Long, marks: WireMarks? ->
        LiveMaskTransport.clientFor(base, timeout, marks, this@LiveProbe.depthEngine != DepthBackend.OFF)
    }
    /** Profondeur + danger de la sonde : mêmes frames capturées, resets communs. */
    private val meters = ProbeMeters(scope, background, appContext, depthMeter, { depthEngine }) { alert ->
        _state.update { it.copy(dangerAlert = alert?.line()) }
    }
    private val pulse = ProbePulse(scope) { p -> _state.update { it.copy(pulse = p) } }

    private val _state = MutableStateFlow(LiveProbeState())
    val state: StateFlow<LiveProbeState> = _state

    internal var session: LiveSession? = null

    /** Session du **premier** appel (délai long : le conteneur Modal endormi met 30-50 s
     * à démarrer) ; on repasse à la session normale dès qu'une frame est passée. */
    internal var coldSession: LiveSession? = null

    /** Une frame a-t-elle abouti depuis l'activation de la caméra ? */
    private var warmed = false

    /** Cadence affichée : moyenne sur les 8 dernières frames, pas l'écart deux frames. */
    private val rate = FrameRate()

    /** Échéance partagée avec le Direct ; la réserve d'une frame reste locale (ci-dessous). */
    internal val cadence = LiveFrameCadence()

    /** Envois adaptatifs (Q8+Q9) : paliers qualité + keyframes, éteints par défaut. */
    internal val adaptive = LiveAdaptive(baseQuality = ISEE_FRAME_QUALITY)

    /** Qualité JPEG à encoder : la constante éteint, le palier courant allumé. */
    val jpegQuality: Int get() = adaptive.jpegQuality

    /** Distances (sténopé sur hauteurs connues) : 100 % téléphone, aucun appel en plus. */
    internal val distances = DistanceMeter()

    /** Voix de la sonde : guidage (cap route > cap manuel) puis positions. */
    internal val voice = ProbeVoice()
    /** Déclencheur du mode Traversée : vert confirmé, rouge vite, doute une fois. */
    private val crossing = CrossingAnnouncer()

    /** Veto trafic du mode Traversée : un véhicule qui approche force l'attente. */
    private val traverseVeto = TraverseVeto()

/** Chronologie de la frame courante : capture du capteur → pixel affiché. */
    private val chronology = ScreenTimings(clock)

    /**
     * Coloriage des masques avec tampons réutilisés : une table de 256 niveaux par
     * couleur, un bitmap par emplacement en double exemplaire. Mesuré en JVM : 8,50 →
     * 0,66 ms pour 12 masques et −12,8 Mo par frame, là où le chemin direct réalloue
     * tout à chaque image.
     */
    private val painter = MaskPainter({ maskColorAt(it).toArgb() }, MASK_ALPHA)

    /** Langue de la voix : l'anglais est le défaut partout (produit anglophone seul). */
    private var speechLanguage: SpeechLanguage = SpeechLanguage.DEFAULT

    /** Un réveil `/health` est en cours (un seul à la fois). */
    private var warming = false

    /** Premier plan : en arrière-plan les frames sont jetées (la carte reste éteinte). */
    private val foregroundGate = ForegroundTracker()

    /**
     * Change d'endpoint, de focale ou de voix (Réglages) : nouvelle session, masques et
     * distances précédents effacés.
     */
    fun configure(
        baseUrl: String,
        prompts: List<String> = LivePrompts.default,
        announce: Boolean = false,
        guide: Boolean = false,
        lens: LensSpec = LensSpec.Default,
        distancesOn: Boolean = true,
        maxObjects: Int = 0,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        crossingActive: Boolean = false,
        modelsLine: String = "",
        depth: DepthBackend = DepthBackend.OFF,
    ) {
        val clean = baseUrl.trim()
        closeSessions()
        session = openSession(clean, prompts, maxObjects, DEFAULT_TIMEOUT_SECONDS)
        // Session « froide » : même transport, délai long (60 s) — c'est le réveil du
        // conteneur qui parle, pas la frame. Une fois la première analyse passée, on
        // repart sur la session normale à délai court (10 s).
        coldSession = openSession(clean, prompts, maxObjects, COLD_TIMEOUT_SECONDS)
        voice.guide = guide
        voice.announce = announce
        voice.language = language
        speechLanguage = language
        depthEngine = depth
        distances.enabled = distancesOn
        distances.setLens(lens)
resetPipeline()
        _state.update {
            LiveProbeState(
                endpoint = clean,
                gpu = it.gpu,
                modelsLine = modelsLine,
                conceptsSent = LivePrompts.normalize(prompts),
                transport = TRANSPORT_WS,
                error = if (clean.isBlank()) "no endpoint (Settings)" else null,
                crossingActive = crossingActive,
            )
        }
        if (clean.isNotBlank()) {
            markWarming()
            fireWake(clean)
            pulse.start()
        }
    }

    /** Réveil explicite via la fabrique injectée (faux client en test, patient en prod). */
    internal fun fireWake(url: String) {
        scope.launch { GpuWake.wake(url, health = { openClient(it, COLD_TIMEOUT_SECONDS, null).health().map { h -> h.ok } }) }
    }

    /**
     * Session du direct sur [clean], `null` sans endpoint : une seule construction pour
     * la session normale et la session froide (même transport, seul le délai change).
     */
    private fun openSession(
        clean: String,
        prompts: List<String>,
        maxObjects: Int,
        timeoutSeconds: Long,
    ): LiveSession? = if (clean.isBlank()) null else LiveSession(
        openClient(clean, timeoutSeconds, chronology),
        prompts = prompts,
        maxObjects = if (maxObjects > 0) maxObjects else DEFAULT_MAX_OBJECTS,
        cadence = Cadence.Fast,
    )

    /** Début d'enregistrement : on repart de zéro, mais l'endpoint et le GPU restent connus. */
    fun begin() {
        session?.stats?.reset()
        session?.serverStats?.reset()
        coldSession?.stats?.reset()
        coldSession?.serverStats?.reset()
        distances.reset()
resetPipeline()
        _state.update { it.cleared() }
        markWarming()
        pulse.start()
    }

    /** Masques et chiffres remis à zéro (sortie d'écran) sans oublier l'endpoint. */
    fun clear() {
        chronology.reset()
_state.update { it.cleared() }
        pulse.stop()
    }

    /**
     * Remet le pipeline à zéro (cadence, réserve, chronologie, compteurs, voix) :
     * partagé par [configure] et [begin], qui n'ajoutent que leur propre état.
     */
    internal fun resetPipeline() {
        warmed = false
        cadence.reset()
        reserve.clear()
        chronology.reset()
        rate.reset()
        voice.reset()
        pulse.reset()
        crossing.reset()
        traverseVeto.reset()
        meters.reset()
        adaptive.reset()
    }

    /** L'app part derrière : les frames sont jetées jusqu'au retour (aucun envoi SAM). */
    fun onBackground() = foregroundGate.onBackground(running = false)

    /** Retour au premier plan : la frame suivante repart (l'enregistrement continue). */
    fun onForeground() = foregroundGate.onForeground()

    /**
     * Cap demandé à la voix (« va à gauche ») : le guidage recalcule le passage
     * vers ce cap dès la prochaine frame. L'annonceur repart de zéro pour que
     * la nouvelle consigne se dise sans attendre ; sans effet à cap identique.
     * Le cap retombe à tout droit à la prochaine session (voir [begin]).
     */
    fun setSteering(cap: SteeringCap) {
        if (_state.value.steeringCap == cap) return
        voice.reset()
        _state.update { it.copy(steeringCap = cap) }
    }

    /**
     * Frame encodée en réserve (**une seule**) : elle est convertie **pendant** que la
     * requête précédente est en vol, puis part dès que le créneau se libère.
     * Sans elle, chaque cycle payait l'encodage JPEG après la réponse (+30-40 ms).
     * Les horodatages voyagent avec la frame : une frame en réserve garde sa capture.
     *
     * C'est un **emplacement**, pas une file (voir [LatestFrameSlot]) : une frame qui
     * arrive alors que la réserve est pleine remplace l'ancienne, jamais ne s'empile.
     */
    private val reserve = LatestFrameSlot()

    /** Contenu de la frame en réserve, réutilisé d'une frame à l'autre : zéro allocation. */
    private val reserved = FramePayload()

    /** Étiquettes de la frame, sans doublon : un ensemble réutilisé à chaque publication. */
    private val labels = FrameLabels()

    /**
     * Faut-il convertir la frame qui arrive ? Oui si une frame de réserve manque :
     * soit un créneau est libre et la cadence est écoulée, soit une requête est en vol
     * et on prépare la suivante. Un seul buffer : au-delà, l'encodage serait du gâchis.
     */
    fun wantsFrame(): Boolean {
        if (!foregroundGate.foreground) return false
        val active = (if (warmed) session else coldSession) ?: return false
        // Réserve pleine : convertir une image de plus serait du CPU jeté.
        if (reserve.pending) return false
        if (active.saturated) return true
        return cadence.isDue(System.currentTimeMillis())
    }

    /**
     * Frame caméra (JPEG base64, 320 px) : mise en réserve si un créneau est pris,
     * envoyée tout de suite sinon. Rien n'est mis en file au-delà d'une frame : la
     * scène a changé, une vieille image n'apprend rien.
     */
    /**
     * Idem avec l'instant d'**exposition** de la frame (`imageInfo.timestamp`, ns
     * monotones) : c'est lui qui rend la latence per\u00e7ue mesurable de bout en bout.
     */
    fun onFrame(jpegB64: String, captureAtNs: Long = 0L) {
        if (!foregroundGate.foreground || jpegB64.isBlank()) return
        meters.offer(jpegB64) // profondeur + danger, même sans session SAM.
        val encodedAtNs = clock()
        val active = (if (warmed) session else coldSession) ?: run {
            android.util.Log.w("sam3vision", "frame ignorée : aucune session SAM (endpoint vide ?)")
            return
        }
        if (active.saturated) {
            reserve.offer(jpegB64, captureAtNs, encodedAtNs)
            return
        }
        // Créneau libre : `admits` se réduit au garde temporel (`saturated` ⟺ `inFlight`).
        val now = System.currentTimeMillis()
        if (!cadence.admits(active, now)) return
        if (!adaptive.admit()) return // keyframe : propagation locale, rien à envoyer
        dispatch(active, jpegB64, captureAtNs, encodedAtNs, now)
    }

    /**
     * Envoie une frame et note l'instant, pour respecter la cadence adaptative.
     * La cadence affichée se compte **à l'envoi** : deux réponses qui arrivent ensemble
     * après un réveil ne veulent pas dire « 40 images par seconde ».
     */
    private fun dispatch(
        active: LiveSession,
        jpegB64: String,
        captureAtNs: Long,
        encodedAtNs: Long,
        at: Long,
    ) {
        rate.record(at)
        // Échéance posée par l'appelant (`admits`/`markSent`) : `dispatch` envoie, il ne décide pas.
        // Nouvelle frame : la chronologie repart de l'exposition du capteur.
        chronology.beginFrame(captureAtNs, encodedAtNs)
        // Hors du thread principal : la sérialisation de la frame (JSON + base64) et la
        // normalisation des concepts ne doivent jamais tomber dans la boucle de composition.
        scope.launch(background) { runFrame(active, jpegB64, at) }
    }

    /**
     * Une réponse vient d'arriver : si une frame attendait, elle part maintenant.
     * C'est ce qui fait tourner le cycle à la vitesse de la requête et non à celle de
     * la requête **plus** l'encodage.
     */
    private fun drain() {
        val active = (if (warmed) session else coldSession) ?: return
        // Créneau encore pris : la réserve attend son tour, elle n'est pas dupliquée.
        if (active.saturated) return
        if (!reserve.take(reserved)) return
        // La réserve saute le garde (elle a déjà attendu) mais avance l'échéance.
        val now = System.currentTimeMillis()
        cadence.markSent(active, now)
        dispatch(active, reserved.jpegB64, reserved.captureAtNs, reserved.encodedAtNs, now)
    }

    private suspend fun runFrame(active: LiveSession, jpegB64: String, at: Long) {
        val result = active.runFrame(jpegB64)
        if (result == null) { adaptive.onDropped(); return drain() }
        adaptive.onSettled(System.currentTimeMillis() - at, result, jpegB64.length)
        // Réponse complète (corps lu et JSON tourné) : le poste « reçu » est posé ici.
        chronology.markReceived()
        // Frame détachée : elle garde ses marques de rendu quand la suivante démarre.
        val shown = chronology.publishing()
        if (result.isSuccess && !warmed) goWarm()
        // La suivante part **avant** la publication : le créneau est déjà rendu, et les
        // masques (~15 ms) ne doivent pas laisser le GPU inactif.
        drain()
        yield()
        val frame = result.getOrNull()
        if (frame == null) {
            // Échec : le compte d'objets n'est pas remis à zéro — les masques affichés
            // restent les derniers connus, seul le message d'erreur change.
            val error = result.exceptionOrNull() ?: ApiError.BadResponse("SAM call failed")
            android.util.Log.w("sam3vision", "live KO: ${error::class.simpleName} ${error.message}")
            _state.update { it.copy(error = probeFailureText(error), timings = shown.snapshot()) }
            return
        }
        if (_state.value.frames == 0) {
            android.util.Log.i(
                "sam3vision",
                "première analyse : ${frame.objects.size} objets, ${frame.elapsedMs} ms",
            )
        }
        publish(active, frame, jpegB64, at, shown)
    }

    /** Première frame aboutie : le conteneur est chaud, la session froide a fait son travail. */
    private fun goWarm() {
        // La première frame paie le démarrage du conteneur (mesuré : 17 s en WebSocket,
        // 40 s en HTTP) : la laisser dans la moyenne afficherait « 17 192 ms » pour un
        // lien chaud à ~180 ms, et fausserait aussi le régulateur de créneaux.
        (if (warmed) session else coldSession)?.let { it.stats.reset(); it.serverStats.reset() }
        warmed = true
        if (warming) {
            warming = false
            _state.update { it.copy(warming = false) }
        }
    }

    /** Publie une frame analysée : masques recolorés, objets, distances, cadence et latences. */
    private suspend fun publish(
        active: LiveSession, frame: LiveFrame, jpegB64: String, at: Long, shown: LiveTimings,
    ) {
        val overlays = overlaysFor(frame, shown)
        val pinhole = readingsOf(distances, frame)
        val readings = meters.readingsFor(frame, pinhole)
        val placed = placedLabels(frame, readings) +
            dangerPlaced(distances, meters.dangerSnapshot())
        val cross = crossingPatchOf(
            crossing, traverseVeto, _state.value.crossingActive, frame, distances.lensSpec, jpegB64, at, speechLanguage,
        )
        val phrase = if (_state.value.crossingActive) {
            crossingPhraseOf(cross?.cue, placed, detectionsOf(readings, frame.width, frame.height), readings, at, speechLanguage, frame.objects, frame.width, frame.height)
        } else {
            CrossingPeopleWatch.reset()
            CrossingVehicleWatch.reset()
            voice.phrase(
                at, placed, _state.value.steeringCap, frame.passage,
                hfovDeg = distances.lensSpec.widthFovDeg(frame.width, frame.height),
            )
        }
        feedCrossingLateral(_state.value.crossingActive, frame.objects, frame.width, frame.height, readings, distances.pose, distances.lensSpec, at, speechLanguage)
        val steering = steeringCueOf()
        pulse.feed(at, voice.lastDecision, placed)
        _state.update { current ->
            current.copy(
                masks = overlays,
                labels = labelsOf(labels, frame),
                objects = frame.objects.size,
                latencyMs = active.stats.averageMs,
                p95Ms = active.stats.p95Ms,
                serverMs = frame.elapsedMs,
                timings = chronology.snapshot(shown, frame),
                width = frame.width,
                height = frame.height,
                frames = current.frames + 1,
                fps = rate.rate,
                distanceLayer = distanceLayerOf(frame, readings),
                spoken = phrase ?: current.spoken,
                spokenUrgent = if (phrase != null) GuidanceWord.isStopPhrase(phrase) else current.spokenUrgent,
                steering = steering,
                samPassage = frame.passage,
                detour = if (_state.value.crossingActive) false else voice.detour,
                crossing = cross?.state ?: current.crossing, crossingBox = cross?.box, traversePhase = cross?.phase,
                crossingOffer = CrossingOfferWatch.update(frame.objects, at, _state.value.crossingActive, crossingOfferMeters(readings, distances.pose, distances.lensSpec, frame.width, frame.height), frame.height),
                crossingVisible = CrossingOfferWatch.lastVisible,
                transport = transportLabel(active),
                error = null,
            )
        }
    }

    /**
     * Frame effectivement **dessinée** : dernier poste de la chronologie, posé par l'écran
     * après la composition (`withFrameNanos`). Tant que l'écran ne l'appelle pas, la ligne
     * s'arrête aux masques et `pixel` affiche « — ».
     */
    fun noteDrawn() {
        if (!chronology.noteDrawn()) return
        _state.update { current -> current.copy(timings = chronology.snapshot(current.timings)) }
    }

    /**
     * Masques colorisés de la frame, calculés hors du thread principal : décodage selon
     * l'encodage **de l'objet** (`rle`/`poly` sur la grille réduite, `bits` = 1 bit + zlib,
     * sinon PNG gris) puis coloriage par table de 256 niveaux — jamais de luminance sur un masque déjà gris.
     *
     * Le coût est mesuré (une ligne tous les [LOG_EVERY_FRAMES] paquets) : c'est du temps
     * mural entre la réponse réseau et ce qui arrive à l'écran, et il n'est pas dans `latence`.
     */
    private suspend fun overlaysFor(
        frame: LiveFrame, shown: LiveTimings,
    ): List<Bitmap> = withContext(background) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val overlays = painter.overlays(frame)
        chronology.markOverlays(shown)
        painter.noteDecoded(android.os.SystemClock.elapsedRealtime() - startedAt, overlays.size)
        overlays
    }

    /**
     * Marqueur d'attente du premier retour : posé dès que l'endpoint est connu,
     * effacé à l'arrivée des premières frames. Aucun appel réseau ici — la première
     * frame réveille le conteneur Modal d'elle-même.
     */
    private fun markWarming() {
        if (_state.value.endpoint.isBlank() || warming) return
        warming = true
        _state.update { it.copy(warming = true) }
    }

}
