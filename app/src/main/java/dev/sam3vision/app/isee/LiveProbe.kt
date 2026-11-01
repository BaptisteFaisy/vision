package dev.sam3vision.app.isee

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.NoLightGate
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
import dev.sam3vision.core.live.PersonLabels
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.app.LiveMaskTransport
import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.app.live.LiveAdaptive
import dev.sam3vision.app.live.LiveFrameCadence
import dev.sam3vision.app.live.LiveLatencyLog
import dev.sam3vision.app.live.MotionLog
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * Analyse SAM de la caméra d'iSee : caméra active, chaque frame part à `/live` (une à la
 * fois, cadence adaptative) et revient en masques, distances et statistiques ; caméra
 * coupée, `stop` ferme les sessions et la carte s'éteint. Même brique que le Direct
 * ([LiveSession], [HttpLiveClient], [DistanceMeter], [PositionAnnouncer]) : une seule
 * implémentation de l'appel SAM et de la mesure par frame, deux écrans qui la montrent.
 */
class LiveProbe(
    private val scope: CoroutineScope,
    /** Horloge monotone en ns (même base qu'`imageInfo.timestamp`) ; injectable en test. */
    private val clock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    /** Exécuteur du travail hors thread principal : sérialisation de la frame, masques. */
    internal val background: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Fabrique de client du direct : les tests y branchent un faux transport, sans réseau.
     * Les knobs du codec masques ([LiveMaskTransport]) voyagent avec le client : la grille
     * réduite demandée au serveur est alors celle que le client sait relire.
     */
    clientFor: ((String, Long, WireMarks?) -> LiveClient)? = null,
    /** Contexte app (profondeur + collecte crops) ; `null` = désactivés (tests). */
    internal val appContext: Context? = null,
    /** Mètre de profondeur (serveur en production) : les tests y branchent un faux. */
    depthMeter: DepthMeter? = null,
    /** Fabrique du verrou Wi-Fi haute-perf : les tests y branchent un faux, sans radio. */
    wifiLockFactory: WifiLockFactory? = null,
) {

    private val openClient = clientFor ?: { base: String, timeout: Long, marks: WireMarks? ->
        LiveMaskTransport.clientFor(base, timeout, marks, withDepth = true)
    }
    /** Profondeur de la sonde (serveur en production) : affine les lectures SAM. */
    internal val meters = ProbeMeters(depthMeter)
    internal val pulse = ProbePulse(scope) { p -> _state.update { it.copy(pulse = p) } }

    /** Radio tenue éveillée pendant le direct : acquis à la 1re frame, rendu par `stop`. */
    internal val wifiLock = LiveWifiLock(appContext, wifiLockFactory ?: androidWifiLockFactory())

    internal val _state = MutableStateFlow(LiveProbeState())
    val state: StateFlow<LiveProbeState> = _state

    internal var session: LiveSession? = null

    /** Session du **premier** appel (délai long : le conteneur Modal endormi met 30-50 s
     * à démarrer) ; on repasse à la session normale dès qu'une frame est passée. */
    internal var coldSession: LiveSession? = null

    /**
     * État foule (audit foule du 01/11) : vrai dès qu'un **corps** figure dans la dernière
     * réponse, faux après [CROWD_EXIT_MS] sans corps (voir [crowdOf]) ; il choisit
     * [Cadence.Crowd] et se lit depuis la voix.
     */
    @Volatile var crowd: Boolean = false
        private set

    /** Instant (ms) du dernier corps vu : l'hystérésis de sortie compte à partir de là. */
    private var bodyAtMs = 0L

    /** Une frame a-t-elle abouti depuis l'activation de la caméra ? */
    private var warmed = false

    /** Cadence affichée : moyenne sur les 8 dernières frames, pas l'écart deux frames. */
    internal val rate = FrameRate()

    /** Échéance partagée avec le Direct ; la réserve d'une frame reste locale (ci-dessous). */
    internal val cadence = LiveFrameCadence()

    /** Envois adaptatifs (Q8+Q9) : paliers qualité + keyframes, allumés par défaut. */
    internal val adaptive = LiveAdaptive(baseQuality = ISEE_FRAME_QUALITY)

    /** Qualité JPEG à encoder : la constante éteint, le palier courant allumé. */
    val jpegQuality: Int get() = adaptive.jpegQuality

    /** Distances (sténopé sur hauteurs connues) : 100 % téléphone, aucun appel en plus. */
    internal val distances = DistanceMeter()

    /** Voix de la sonde : guidage (cap route > cap manuel) puis positions. */
    internal val voice = ProbeVoice()
    /** Rotation 3 groupes + accumulation : un groupe par frame, fusion publiée. */
    internal val rotation = ProbeRotationState()
    /** Déclencheur du mode Traversée : vert confirmé (4 frames), rouge vite (2 frames). */
    internal val crossing = CrossingAnnouncer()

    /** Collecte des crops (voir [LightCropLogger]) : `null` = interrupteur coupé. */
    internal var cropLogger: LightCropLogger? = null

    /** Veto trafic du mode Traversée : un véhicule qui approche force l'attente. */
    internal val traverseVeto = TraverseVeto()

    private val publishMutex = Mutex() // une seule publication à la fois

    /** Porte « sans feu » : 4 frames sans boîte de feu → voix véhicules + « Traversez. ». */
    internal val noLightGate = NoLightGate()
    internal val verdictEngine = busVerdictEngine() // Verdict traversée (SPEC v4), branché au bus.

/** Chronologie de la frame courante : capture du capteur → pixel affiché. */
    internal val chronology = ScreenTimings(clock)

    /**
     * Coloriage des masques avec tampons réutilisés (table de 256 niveaux par couleur,
     * un bitmap par emplacement) : mesuré en JVM 8,50 → 0,66 ms pour 12 masques et
     * −12,8 Mo par frame, là où le chemin direct réalloue tout à chaque image.
     */
    internal val painter = MaskPainter({ maskColorAt(it).toArgb() }, MASK_ALPHA)

    /** Langue de la voix : l'anglais est le défaut partout (produit anglophone seul). */
    internal var speechLanguage: SpeechLanguage = SpeechLanguage.DEFAULT

    /** Un réveil `/health` est en cours (un seul à la fois). */
    private var warming = false

    /** Refus essuyé : la frame suivante part sans attendre l'échéance (voir [wantsFrame]). */
    private var priorityArmed = false

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
        collectCrops: Boolean = false,
        followed: List<String> = emptyList(),
    ) {
        val clean = baseUrl.trim()
        openSessions(clean, prompts, maxObjects)
        rotation.usePrompts(prompts)
        voice.guide = guide
        voice.announce = announce
        voice.language = language
        speechLanguage = language
        distances.enabled = distancesOn
        distances.setLens(lens)
        // Un fichier par configuration : recouper l'interrupteur change de fichier.
        cropLogger = if (collectCrops) appContext?.let { LightCropLogger(java.io.File(it.filesDir, "lightcrops"), dev.sam3vision.app.BuildConfig.VERSION_NAME) } else null
        resetPipeline()
        _state.update {
            LiveProbeState(
                endpoint = clean,
                gpu = it.gpu,
                modelsLine = modelsLine,
                conceptsSent = LivePrompts.normalize(prompts),
                followed = followed,
                transport = TRANSPORT_WS,
                timeoutSec = if (clean.isBlank()) 0L else COLD_TIMEOUT_SECONDS,
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

    /** Réveil explicite via la fabrique injectée (faux client en test, patient en prod) ; `/health` OK = carte chaude, on bascule sans attendre la frame (un vol froid peut coexister avec les vols chauds : transitoire, borné). */
    internal fun fireWake(url: String) {
        scope.launch { GpuWake.wake(url, health = { openClient(it, COLD_TIMEOUT_SECONDS, null).health().map { h -> h.ok } }, onResult = { if (it.getOrNull() == true && !warmed) goWarm() }) }
    }

    /**
     * (Re)ouvre les sessions normale et froide sur [clean] : même transport, délai long
     * (60 s) pour la froide — c'est le réveil du conteneur qui parle, pas la frame. La
     * première analyse passée, on repart sur la normale (10 s) via [goWarm].
     */
    private fun openSessions(clean: String, prompts: List<String>, maxObjects: Int) {
        closeSessions()
        session = openSession(clean, prompts, maxObjects, DEFAULT_TIMEOUT_SECONDS)
        coldSession = openSession(clean, prompts, maxObjects, COLD_TIMEOUT_SECONDS)
        // Handshake froid pendant la liaison caméra : la 1re frame part sans attendre.
        // La session chaude préconnecte à `goWarm` (un socket ouvert ici idlerait 45 s
        // pendant le boot et serait fermé par le serveur avant sa première frame).
        coldSession?.preconnect()
    }

    /**
     * Session du direct sur [clean], `null` sans endpoint : une construction pour la
     * session normale et la froide (même transport, seul le délai change).
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
        globalsNouvelleSeance()
        _state.update { it.cleared() }
        markWarming()
        pulse.start()
    }

    /** Masques et chiffres remis à zéro (sortie d'écran) sans oublier l'endpoint. */
    fun clear() {
        chronology.reset()
_state.update { it.cleared() }
        pulse.stop()
        ProbeBackgroundMotion.reset()
        MotionLog.reset()
    }

    /**
     * Remet le pipeline à zéro (cadence, réserve, chronologie, compteurs, voix, foule,
     * réveil) : partagé par [configure] et [begin]. `warming` est ré-armé ici pour que le
     * [markWarming] qui suit ré-émette l'avis d'armement — sans lui, `begin()` après
     * `configure()` effacerait l'avis sans le redire.
     */
    internal fun resetPipeline() {
        warmed = false
        warming = false
        priorityArmed = false
        cadence.reset()
        reserve.clear()
        chronology.reset()
        rate.reset()
        voice.reset()
        pulse.reset()
        crossing.reset()
        traverseVeto.reset()
        noLightGate.reset()
        crowd = false
        noteCrowd(0)
        meters.reset()
        adaptive.reset()
        rotation.reset()
        ProbeBackgroundMotion.reset()
        MotionLog.reset()
    }

    /** L'app part derrière : les frames sont jetées jusqu'au retour (aucun envoi SAM). */
    fun onBackground() = foregroundGate.onBackground(running = false)

    /** Retour au premier plan : la frame suivante repart (l'enregistrement continue). */
    fun onForeground() = foregroundGate.onForeground()

    /** Cap demandé à la voix (corps déporté, voir [applySteering]). */
    fun setSteering(cap: SteeringCap) = applySteering(cap)

    /**
     * Frame encodée en réserve (**une seule**) : convertie **pendant** que la requête
     * précédente est en vol, elle part dès que le créneau se libère — sans elle chaque
     * cycle payait l'encodage JPEG après la réponse (+30-40 ms). Les horodatages voyagent
     * avec la frame. C'est un **emplacement**, pas une file ([LatestFrameSlot]) : une
     * frame qui arrive sur une réserve pleine remplace l'ancienne, jamais ne s'empile.
     */
    private val reserve = LatestFrameSlot()

    /** Contenu de la frame en réserve, réutilisé d'une frame à l'autre : zéro allocation. */
    private val reserved = FramePayload()

    /** Frames remplacées en réserve (la plus récente gagne) : comptées, jamais silencieuses. */
    val droppedFrames: Long get() = reserve.droppedFrames

    /** Étiquettes de la frame, sans doublon : un ensemble réutilisé à chaque publication. */
    internal val labels = FrameLabels()

    /**
     * Faut-il convertir la frame qui arrive ? Oui si une réserve manque : un créneau est
     * libre et la cadence écoulée, ou une requête est en vol et on prépare la suivante.
     * Réserve pleine mais périmée : on ré-encode (remplacement, jamais de file) pour
     * drainer une frame fraîche (poste `up` divisé par ~2) au lieu d'une frame âgée d'un
     * RTT. Un refus (réserve pleine et fraîche) est **compté** et arme la priorité.
     */
    fun wantsFrame(): Boolean {
        if (!foregroundGate.foreground) return false
        val active = (if (warmed) session else coldSession) ?: return false
        // Réserve pleine et fraîche : convertir serait du CPU jeté — refus
        // compté (jamais silencieux), la suivante est prioritaire. Périmée
        // (> 1 période caméra) : la réalimenter, la suivante part au drain.
        if (reserve.pending && !reserve.stale(clock(), STALE_RESERVE_MS)) { priorityArmed = true; noteSkipped(); return false }
        if (active.saturated || priorityArmed) return true
        return cadence.isDue(System.currentTimeMillis())
    }

    /**
     * Frame caméra (JPEG base64, 320 px) avec son instant d'**exposition**
     * (`imageInfo.timestamp`, ns monotones — c'est lui qui rend la latence perçue
     * mesurable) : mise en réserve si un créneau est pris, envoyée tout de suite sinon.
     * Rien n'est mis en file au-delà d'une frame : la scène a changé, une vieille image
     * n'apprend rien.
     */
    fun onFrame(jpegB64: String, captureAtNs: Long = 0L) {
        if (!foregroundGate.foreground || jpegB64.isBlank()) return
        val exposedAtMs = if (captureAtNs > 0L) captureAtNs / 1_000_000L else System.currentTimeMillis()
        ProbeBackgroundMotion.offer(jpegB64, exposedAtMs) // fond V1 pour les veilleurs.
        val encodedAtNs = clock()
        val active = (if (warmed) session else coldSession) ?: run {
            android.util.Log.w("sam3vision", "frame ignorée : aucune session SAM (endpoint vide ?)")
            return
        }
        if (active.saturated) {
            reserve.offer(jpegB64, captureAtNs, encodedAtNs)
            return
        }
        // Deux créneaux : plein ⟺ saturé (plus ⟺ en vol) — le garde temporel seul décide.
        val now = System.currentTimeMillis()
        if (!probePriority(state.value, priorityArmed) && !cadence.isDue(now)) return
        cadence.markSent(active, now)
        if (!adaptive.admit()) return // keyframe : propagation locale, rien à envoyer
        priorityArmed = false
        dispatch(active, jpegB64, captureAtNs, encodedAtNs, now)
    }

    /**
     * Envoie une frame et note l'instant, pour la cadence adaptative. La cadence affichée
     * se compte **à l'envoi** : deux réponses qui arrivent ensemble après un réveil ne
     * veulent pas dire « 40 images par seconde ».
     */
    private fun dispatch(
        active: LiveSession,
        jpegB64: String,
        captureAtNs: Long,
        encodedAtNs: Long,
        at: Long,
    ) {
        wifiLock.hold() // direct ouvert : la radio ne dort plus (repli silencieux sinon).
        rate.record(at)
        // Échéance posée par l'appelant (`admits`/`markSent`) : `dispatch` envoie, il ne décide pas.
        // Nouvelle frame : la chronologie repart de l'exposition du capteur.
        chronology.beginFrame(captureAtNs, encodedAtNs)
        // Hors du thread principal : la sérialisation de la frame (JSON + base64) et la
        // normalisation des concepts ne doivent jamais tomber dans la boucle de composition.
        val send = rotation.send(state.value.conceptsSent)
        scope.launch(background) { runFrame(active, jpegB64, at, send.prompts, send.rotated) }
    }

    /** Une réponse vient d'arriver : une frame en réserve part maintenant, au lieu
     * d'attendre le prochain cycle (la requête **plus** l'encodage). */
    private fun drain() {
        val active = (if (warmed) session else coldSession) ?: return
        // Créneau encore pris : la réserve attend son tour, elle n'est pas dupliquée.
        if (active.saturated) return
        if (!reserve.take(reserved)) return
        // La réserve saute le garde (elle a déjà attendu) mais avance l'échéance.
        val now = System.currentTimeMillis()
        cadence.markSent(active, now)
        priorityArmed = false
        dispatch(active, reserved.jpegB64, reserved.captureAtNs, reserved.encodedAtNs, now)
    }

    /** Corps de la dernière réponse → [crowd] (hystérésis de [crowdOf]), puis cadence des
     * deux sessions : en foule [Cadence.Crowd] garde le double vol jusqu'à 600 ms. */
    private fun noteCrowd(bodies: Int) {
        val now = clock() / 1_000_000L
        if (bodies > 0) bodyAtMs = now
        crowd = crowdOf(crowd, bodies, bodyAtMs, now)
        val cadence = if (crowd) Cadence.Crowd else Cadence.Fast
        session?.useCadence(cadence)
        coldSession?.useCadence(cadence)
    }

    private suspend fun runFrame(active: LiveSession, jpegB64: String, at: Long, prompts: List<String>, rotated: Boolean) {
        val result = active.runFrameWith(jpegB64, prompts)
        // Réseau parallèle, publication sérialisée (instances partagées).
        publishMutex.withLock {
            if (result == null) { adaptive.onDropped(); return@withLock drain() }
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
            val frame = accumulated(result.getOrNull(), prompts, rotated)
            if (frame == null) {
                // Échec : le compte d'objets n'est pas remis à zéro — les masques affichés
                // restent les derniers connus, seul le message d'erreur change.
                val error = result.exceptionOrNull() ?: ApiError.BadResponse("SAM call failed")
                if (!warmed && error is ApiError.Timeout) sayColdTimeout()
                android.util.Log.w("sam3vision", "live KO: ${error::class.simpleName} ${error.message}")
                _state.update { it.copy(error = probeFailureText(error), timings = shown.snapshot()) }
                return@withLock
            }
            if (_state.value.frames == 0) {
                android.util.Log.i("sam3vision", "première analyse : ${frame.objects.size} objets, ${frame.elapsedMs} ms")
            }
            noteCrowd(frame.objects.count { PersonLabels.isBody(it.label) })
            publish(active, frame, jpegB64, at, shown)
        }
    }

    /** Première frame aboutie : le conteneur est chaud, la session froide a fait son travail. */
    private fun goWarm() {
        // La première frame paie le démarrage du conteneur (mesuré : 17 s en WebSocket, 40 s
        // en HTTP) : la garder dans la moyenne afficherait « 17 192 ms » pour un lien chaud
        // à ~180 ms, et fausserait aussi le régulateur de créneaux.
        (if (warmed) session else coldSession)?.let { it.stats.reset(); it.serverStats.reset() }
        warmed = true
        // La session chaude ouvre son socket pendant que la 1re réponse se publie.
        session?.preconnect()
        if (warming) {
            warming = false
            _state.update { it.copy(warming = false, timeoutSec = DEFAULT_TIMEOUT_SECONDS) }
        }
    }

    /**
     * Frame effectivement **dessinée** : dernier poste de la chronologie, posé par l'écran
     * après la composition (`withFrameNanos`) ; sans lui la ligne s'arrête aux masques et
     * `pixel` affiche « — ».
     */
    fun noteDrawn() {
        if (!chronology.noteDrawn()) return
        val snap = chronology.snapshot(_state.value.timings)
        _state.update { current -> current.copy(timings = snap) }.also { LiveLatencyLog.frame(snap) }
    }

    /** Marqueur d'attente du premier retour : posé dès que l'endpoint est connu, effacé
     * aux premières frames. Aucun appel réseau : la frame réveille le conteneur. */
    private fun markWarming() {
        if (_state.value.endpoint.isBlank() || warming) return
        warming = true
        _state.update { it.copy(warming = true, timeoutSec = COLD_TIMEOUT_SECONDS, spoken = WARM_SPOKEN, spokenSeq = it.spokenSeq + 1) }
    }

}

/**
 * Âge de la réserve au-delà duquel on ré-encode pendant le vol (~1,5 période
 * caméra à 30 fps) : drainer une frame fraîche au lieu d'une frame âgée d'un
 * RTT (poste `up`), pour ~3 encodages jetés par cycle au pire.
 */
private const val STALE_RESERVE_MS = 50L
