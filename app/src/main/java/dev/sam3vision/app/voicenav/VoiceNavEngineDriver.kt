package dev.sam3vision.app.voicenav

import android.content.Context
import dev.sam3vision.app.navigation.VoiceNavTracker
import dev.sam3vision.app.isee.OutdoorLevel
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.AndroidTtsDriver
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.app.speech.VoiceNavEvent
import dev.sam3vision.app.speech.VoiceNavPhrases
import dev.sam3vision.app.speech.VoiceNavPolicy
import dev.sam3vision.app.speech.VoiceNavSpeaker
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.DetourReroute
import dev.sam3vision.core.nav.GeocodedPlace
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.nav.ManeuverAction
import dev.sam3vision.core.nav.NavEvent
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavGeo
import dev.sam3vision.core.nav.NavManeuver
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.nav.NavState
import dev.sam3vision.core.nav.PeriodicGuidance
import dev.sam3vision.core.nav.PeriodicTuning
import dev.sam3vision.core.nav.WalkDirective
import dev.sam3vision.core.nav.toNavRoute
import dev.sam3vision.core.net.HttpRouteClient
import dev.sam3vision.core.net.RouteClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Allure piétonne moyenne : la durée restante affichée est une estimation. */
internal const val WALK_METERS_PER_SECOND = 1.3

/**
 * Pilote du guidage embarqué : géocodage → itinéraire Valhalla → moteur →
 * voix système + écran. Le [VoiceNavSession] l'appelle à « Démarrer »
 * (crochet `engineStart`) ; `false` = repos + annonce, `true` = guidage in-app.
 *
 * - [geocode] résout la destination (chaîne GMS puis BAN, triée par proximité) ;
 * - [originNow] donne la position du départ (suspend : attend un fix frais) ;
 * - [routeClient] joint Valhalla (`POST /route` sur l'URL des Réglages) ;
 * - [onUpdate] reçoit l'état instantané (→ `session.onGuidanceUpdate`) ;
 * - [onRouteCap] reçoit le cap de chaque manœuvre (`null` en fin de guidage),
 *   pour la sonde SAM (`LiveProbe.setRouteCap`) : tant qu'il est posé, la
 *   sonde marche le couloir de la route (fusion itinéraire × obstacle).
 * - [speakRoute] dit les manœuvres par la voix réseau ElevenLabs (`null` = voix système) :
 *   la phrase (même politique que le canal système) + son urgence (sortie de
 *   trace et recalcul coupent, le reste attend). Une seule voix : GPS et
 *   obstacles partagent le même tuyau, jamais deux voix en même temps.
 *   Niveaux SPEC v4 : manœuvres = 11, rappel 15 s = 3 (l'intégrateur les
 *   porte au bus vocal).
 * - [stopRoute] coupe la parole route (fin de guidage, user qui parle),
 *   miroir de [speakRoute] ;
 * - [routeReaderBusy] lit le lecteur partagé (voix réseau) : le garde
 *   [isSpeaking] marque la route occupée sur son état réel, jamais sur le
 *   TTS système qui ne parle pas sur ce chemin.
 *
 * Priorité à la voix du user : tant qu'il parle ([userSpeaking]), la voix
 * ordinaire se tait — sauf urgence ([VoiceNavPolicy.isUrgent]) qui coupe.
 * [stopVoice] coupe la parole en cours quand le user prend la parole,
 * sans toucher au guidage lui-même.
 *
 * Voix : virages francs par [VoiceNavSpeaker.onTurn] (préavis « Continuez
 * dans cette direction, rue X » puis ordre sec « Tournez à gauche,
 * rue X »), le reste en phrase littérale du moteur
 * ([VoiceNavPhrases.ahead]) — un demi-tour ou un giratoire n'a pas de verbe
 * juste en caps, et une mauvaise consigne vaut moins que la phrase exacte.
 * L'arrivée ne passe PAS par le haut-parleur : [onUpdate] la signale et la
 * session annonce (« Arrivée à destination », une seule voix).
 * Rappel toutes les 15 s ([PeriodicGuidance], au premier fix qui suit
 * l'échéance) : mot seul redit de près ; une annonce moteur recale la cadence.
 *
 * Traversée : le moteur remplace l'annonce par [NavEvent.CrossingAhead]
 * quand la cible est un passage protégé — simple préavis parlé (« Crosswalk
 * in X »), de loin comme de près. Le pilote n'arme plus le mode traversée :
 * l'user le bascule lui-même (puce, ordre vocal « crossing mode on/off »,
 * « crosswalk on/off »).
 * [NavEvent.CrossingPassed] annonce « Crossing finished. », le guidage
 * reprend.
 *
 * Ne lève jamais après construction : tout échec de démarrage rend `false`,
 * tout échec en guidage est avalé (le moteur réémettra).
 */
class VoiceNavEngineDriver(
    appContext: Context,
    private val settings: () -> Settings,
    private val geocode: suspend (String, RoutePoint?) -> Result<GeocodedPlace>,
    private val originNow: suspend () -> RoutePoint?,
    private val routeClient: (String) -> RouteClient = ::HttpRouteClient,
    private val scope: CoroutineScope,
    private val onUpdate: (GuidanceUpdate) -> Unit,
    private val onRouteCap: (SteeringCap?) -> Unit = {},
    private val speakRoute: (suspend (phrase: String, level: Int) -> Unit)? = null,
    private val stopRoute: () -> Unit = {},
    /** Lecture réelle du lecteur partagé (voix réseau) : lue par [isSpeaking]. */
    private val routeReaderBusy: () -> Boolean = { false },
    /** L'utilisateur parle (dictée en cours) : la voix ordinaire se tait. */
    private val userSpeaking: () -> Boolean = { false },
) {

    private val app = appContext.applicationContext
    private val tracker = VoiceNavTracker(app, onEvent = ::handleEvent, onFix = ::onTrackerFix)
    private val ttsLazy = lazy { SystemTtsEngine(AndroidTtsDriver(app)) }
    private val speakerLazy = lazy { VoiceNavSpeaker(ttsLazy.value, settings, userSpeaking = userSpeaking) }
    private val speaker by speakerLazy
    private val routeAnnouncer = VoiceNavAnnouncer()
    private val detourReroute = DetourReroute()
    private val periodic = PeriodicGuidance(PeriodicTuning(repeatMs = GPS_REMINDER_MS))
    /** Un événement moteur a parlé sur le fix en cours : le rappel se tait, cadence recalée. */
    private var fixHadEvent = false
    /** Dernier mot de manœuvre (« Left. »/« Right. »/«») : le rappel 15 s le redit de près. */
    private var lastInstruction: String? = null

    @Volatile
    private var legs: GuidingLegs? = null
    private var rerouting = false

    /**
     * Démarre le guidage vers [destination] : `true` si le moteur tourne
     * (voix « Navigation vers X » dite), `false` si un maillon manque
     * (adresse, position, réseau, itinéraire) — sinon repos + annonce.
     */
    suspend fun start(destination: String): Boolean {
        // Origine lue tôt : elle trie les candidats du géocodage par proximité.
        val origin = try {
            originNow()
        } catch (_: Exception) {
            null
        } ?: return false
        // Appels `suspend` (`runCatching` ne l'est pas) : try/catch directs.
        val place = try {
            geocode(destination, origin)
        } catch (_: Exception) {
            null
        }?.getOrNull() ?: return false

        // Client Valhalla sur l'URL des Réglages.
        val base = runCatching { settings().routeBaseUrl.trim().trimEnd('/') }.getOrDefault("") ?: return false
        if (base.isBlank()) return false
        val trip = try {
            routeClient(base).route(origin, RoutePoint(place.latitude, place.longitude))
        } catch (_: Exception) {
            null
        }?.getOrNull() ?: return false
        val nav = trip.toNavRoute(place.label).getOrNull() ?: return false
        if (runCatching { tracker.guide(nav) }.getOrNull()?.isSuccess != true) return false
        // Écoute GPS réellement démarrée : sinon aucun écran de guidage muet,
        // sinon repos + annonce (voir `VoiceNavTracker.start`).
        if (!runCatching { tracker.start() }.getOrDefault(false)) {
            stop()
            return false
        }
        legs = GuidingLegs(nav, tailDistances(nav.maneuvers), RoutePoint(place.latitude, place.longitude))
        lastInstruction = null
        detourReroute.reset()
        periodic.reset()
        fixHadEvent = false
        say(VoiceNavEvent.Started(destination)) { speaker.onStarted(destination) }
        return true
    }

    /**
     * Parole d'un événement route (niveau 11, SPEC v4) : voix réseau quand
     * elle est câblée ([speakRoute], postée au bus par l'appelant), sinon
     * voix système ([system]). Plus d'urgence route : tout événement GPS
     * parle au même rang, le bus arbitre. Ne lève jamais.
     */
    private fun say(event: VoiceNavEvent, system: () -> String?) {
        val laure = speakRoute
        if (laure == null) {
            runCatching { system() }
            return
        }
        val phrase = routeAnnouncer.onEvent(System.currentTimeMillis(), event, settings().speechLanguage)
            ?: return
        scope.launch { runCatching { laure(phrase, OutdoorLevel.GPS_EVENT) } }
    }

    /**
     * Détour obstacle de la sonde : subi assez longtemps ([DetourReroute]),
     * l'itinéraire est recalculé depuis la position GPS temps réel — le
     * meilleur chemin tient compte des obstacles (le local contourne, le
     * global replanifie). Sans guidage en cours, sans effet.
     */
    fun onDetour(detour: Boolean, nowMs: Long) {
        if (detourReroute.onDetour(detour, nowMs)) launchReroute()
    }

    /** Coupe l'écoute, la parole et la mémoire ; réutilisable après. */
    fun stop() {
        legs = null
        lastInstruction = null
        rerouting = false
        runCatching { onRouteCap(null) }
        runCatching { stopRoute() }
        detourReroute.reset()
        periodic.reset()
        fixHadEvent = false
        runCatching { tracker.stop() }
        if (speakerLazy.isInitialized()) {
            runCatching { speaker.stop() }
            runCatching { speaker.reset() }
        }
    }

    /**
     * L'utilisateur prend la parole : la parole en cours est coupée (voix
     * système comme lecteur partagé), la mémoire du guidage intacte (le
     * moteur réémettra). Idempotent, sans effet si la voix n'a jamais parlé.
     */
    fun stopVoice() {
        if (speakerLazy.isInitialized()) runCatching { speaker.stop() }
        runCatching { stopRoute() }
    }

    /** Libération finale : le canal vocal n'accepte plus rien ensuite. */
    fun shutdown() {
        stop()
        if (speakerLazy.isInitialized()) runCatching { speaker.shutdown() }
    }

    /**
     * Vrai si la voix route parle (arbitre vocal : l'obstacle non urgent
     * attend). Voix réseau ([speakRoute]) : état réel du lecteur partagé
     * ([routeReaderBusy]) ; voix système : haut-parleur paresseux, jamais
     * démarré = silencieux (jamais initialisé ici).
     */
    fun isSpeaking(): Boolean {
        if (speakRoute != null) return routeSpeechActive(true, runCatching { routeReaderBusy() }.getOrDefault(false), false)
        val speakerActive = if (speakerLazy.isInitialized()) speaker.isSpeaking() else false
        return routeSpeechActive(false, false, speakerActive)
    }


    /** Itinéraire en cours de guidage, `null` au repos (carte de debug). */
    fun debugRoute(): NavRoute? = legs?.route

    /** Dernier fix GPS reçu, `null` sans fix (carte de debug). */
    fun debugFix(): NavFix? = tracker.lastFix

    /** Un événement moteur : parole + écran, jamais d'exception. */
    private fun handleEvent(event: NavEvent) {
        runCatching {
            fixHadEvent = true
            when (event) {
                is NavEvent.ManeuverAhead -> {
                    runCatching { onRouteCap(routeCapFor(event.action)) }
                    speakManeuver(event)
                    onUpdate(updateFor(event))
                }
                is NavEvent.CrossingAhead -> {
                    speakCrossing(event)
                    onUpdate(updateForCrossing(event.distM))
                }
                NavEvent.CrossingPassed -> onCrossingPassed()
                is NavEvent.Arrived -> {
                    onUpdate(GuidanceUpdate(VoiceNavPhrases.ARRIVED, 0, 0, 0, arrived = true))
                    stop()
                }
                is NavEvent.OffRoute -> {
                    say(VoiceNavEvent.OffRoute) { speaker.onOffRoute() }
                    launchReroute()
                }
                is NavEvent.BackOnRoute -> say(VoiceNavEvent.BackOnRoute) { speaker.onBackOnRoute() }
            }
        }
    }

    /** Virage franc au cap parlé, le reste en phrase littérale du moteur — sauf les lignes droites moteur (départ, suite : silence, ordre user 02/11). */
    private fun speakManeuver(event: NavEvent.ManeuverAhead) {
        if (event.action == ManeuverAction.Start || event.action == ManeuverAction.Continue) return
        val cap = event.action.toSteeringCap()
        if (cap == null) {
            val phrase = VoiceNavPhrases.ahead(event.action, event.street, event.distM)
            say(VoiceNavEvent.Literal(phrase)) { speaker.onLiteral(phrase) }
        } else {
            say(VoiceNavEvent.Turn(cap, event.distM.toFloat(), event.street)) {
                speaker.onTurn(cap, event.distM.toFloat(), event.street)
            }
        }
    }

    /** Traversée en vue : simple préavis parlé, le mode ne s'arme plus seul. */
    private fun speakCrossing(event: NavEvent.CrossingAhead) {
        val phrase = VoiceNavPhrases.crossingAhead(event.distM)
        say(VoiceNavEvent.Literal(phrase)) { speaker.onLiteral(phrase) }
    }

    /**
     * Fix brut après le dispatch moteur : un événement vient-il de parler
     * sur ce fix ([fixHadEvent]) ? Oui → cadence recalée, silence. Non →
     * rappel 15 s ([PeriodicGuidance]) si le guidage court. Ne lève jamais.
     */
    private fun onTrackerFix(fix: NavFix) {
        runCatching { tickPeriodic(fix) }
    }

    /** Un tick de rappel : silence si le moteur a parlé, sinon consigne. */
    private fun tickPeriodic(fix: NavFix) {
        if (fixHadEvent) {
            fixHadEvent = false
            periodic.noteSpoken(fix.atMs)
            return
        }
        if (tracker.state != NavState.Guiding) return
        val target = runCatching { tracker.nextManeuver() }.getOrNull() ?: return
        // Mot seul de près (ordre user 02/11) : un « left » à 200 m sans
        // distance ferait tourner trop tôt — le rappel se tait au-delà du
        // rayon d'ordre sec, avant la cadence (tick non consommé).
        val nearM = NavGeo.haversineMeters(fix.lat, fix.lon, target.lat, target.lon)
        if (!nearM.isFinite() || nearM > VoiceNavPolicy.TURN_NOW_M) return
        val directive = periodic.onFix(fix, target) ?: return
        speakPeriodic(directive)
    }

    /**
     * Parole du rappel 15 s (mot seul de près) : voix réseau quand elle est
     * câblée ([speakRoute], hors porte — le rappel redit exprès, la porte ne
     * sait pas ; l'aval arbitre : user, lecteur occupé, doublon récent),
     * sinon voix système ([VoiceNavSpeaker.onRepeat], silence seul).
     * Consigne blanche (ligne droite) : silence. Ne lève jamais.
     */
    private fun speakPeriodic(directive: WalkDirective) {
        val instruction = lastInstruction?.takeIf { it.isNotBlank() }
            ?: VoiceNavPhrases.phrase(periodicEventOf(directive), SpeechLanguage.En)
        if (instruction.isBlank()) return
        val laure = speakRoute
        if (laure == null) {
            runCatching { speaker.onRepeat(instruction) }
            return
        }
        scope.launch { runCatching { laure(instruction, OutdoorLevel.GPS_REMINDER) } }
    }

    /** Traversée passée : annonce, le guidage reprend. */
    private fun onCrossingPassed() {
        say(VoiceNavEvent.Literal(VoiceNavPhrases.CROSSING_DONE)) { speaker.onLiteral(VoiceNavPhrases.CROSSING_DONE) }
    }

    /** État écran : instruction + « dans X » + reste estimé du trajet. */
    private fun updateFor(event: NavEvent.ManeuverAhead): GuidanceUpdate {
        // Mot vocal redit par le rappel : virage franc proche (« Left. »),
        // «» sinon — l'écran garde ahead() dans tous les cas.
        val word = VoiceNavPhrases.maneuverWord(
            event.action.toSteeringCap(),
            VoiceNavPolicy.isTurnNow(event.distM),
        )
        return buildUpdate(
            VoiceNavPhrases.ahead(event.action, event.street, event.distM),
            event.distM,
            word,
        )
    }

    /** État écran d'une traversée : préavis + « dans X » + reste estimé. */
    private fun updateForCrossing(distM: Double): GuidanceUpdate =
        // Mot de manœuvre gardé : la traversée a ses propres verdicts (100+).
        buildUpdate(VoiceNavPhrases.crossingAhead(distM), distM, null)

    /** État écran : instruction + « dans X » + reste estimé du trajet. */
    private fun buildUpdate(instruction: String, distM: Double, voiceWord: String?): GuidanceUpdate {
        if (voiceWord != null) lastInstruction = voiceWord
        val near = distM.toLong().coerceAtLeast(0)
        val remaining = remainingMeters(distM)
        return GuidanceUpdate(
            instruction = instruction,
            distanceToManeuverMeters = near,
            remainingDistanceMeters = remaining,
            remainingDurationSeconds = walkSeconds(remaining),
        )
    }

    /** Reste estimé : « dans X » + queue des manœuvres suivantes. */
    private fun remainingMeters(distM: Double): Long {
        val legs = legs ?: return distM.toLong().coerceAtLeast(0)
        val index = legs.route.maneuvers.indexOf(runCatching { tracker.nextManeuver() }.getOrNull())
        if (index < 0) return distM.toLong().coerceAtLeast(0)
        return (distM + legs.tails[index]).toLong().coerceAtLeast(0)
    }

    /** Recalcul sérialisé : un seul à la fois, le moteur réémettra sinon. */
    private fun launchReroute() {
        if (rerouting) return
        rerouting = true
        scope.launch {
            try {
                rerouteNow()
            } finally {
                rerouting = false
            }
        }
    }

    /** Nouveau départ = position actuelle, même destination (sans regéocoder). */
    private suspend fun rerouteNow() {
        val legs = legs ?: return
        val origin = try {
            originNow()
        } catch (_: Exception) {
            null
        } ?: return
        val base = runCatching { settings().routeBaseUrl.trim().trimEnd('/') }.getOrDefault("") ?: return
        if (base.isBlank()) return
        val trip = try {
            routeClient(base).route(origin, legs.dest)
        } catch (_: Exception) {
            null
        }?.getOrNull() ?: return
        val nav = trip.toNavRoute(legs.route.destinationName).getOrNull() ?: return
        if (runCatching { tracker.reroute(nav) }.getOrNull()?.isSuccess == true) {
            this.legs = legs.copy(route = nav, tails = tailDistances(nav.maneuvers))
            say(VoiceNavEvent.Rerouted) { speaker.onRerouted() }
        }
    }

    /** Itinéraire en cours : tracé, queues précalculées, destination fixe. */
    private data class GuidingLegs(
        val route: NavRoute,
        val tails: DoubleArray,
        val dest: RoutePoint,
    )
}

/**
 * Cap parlé d'une action, ou `null` (phrase littérale) : seuls les virages
 * francs ont un verbe juste en caps — départ, ligne droite annoncée,
 * demi-tour, giratoire et arrivée se disent avec la phrase du moteur.
 */
internal fun ManeuverAction.toSteeringCap(): SteeringCap? = when (this) {
    ManeuverAction.TurnLeft, ManeuverAction.SlightLeft -> SteeringCap.Left
    ManeuverAction.TurnRight, ManeuverAction.SlightRight -> SteeringCap.Right
    ManeuverAction.Start, ManeuverAction.Continue,
    ManeuverAction.UTurn, ManeuverAction.Roundabout,
    ManeuverAction.Arrive -> null
}

/**
 * Cap marché pour [action] : le virage franc, sinon tout droit (demi-tour,
 * giratoire et départ se marchent au centre, la phrase dit le reste).
 * Total, contrairement à [toSteeringCap] : la sonde veut un couloir, jamais
 * `null`. Pur, testé en JVM.
 */
internal fun routeCapFor(action: ManeuverAction): SteeringCap =
    action.toSteeringCap() ?: SteeringCap.Straight

/**
 * Événement parlé d'une consigne de rappel : virages et ligne droite par
 * les événements existants (la langue de la voix est suivie), demi-tour,
 * traversée et giratoire en littéral anglais (comme [VoiceNavPhrases.ahead],
 * jamais de rue ni de distance). Pur, testé en JVM.
 */
internal fun periodicEventOf(directive: WalkDirective): VoiceNavEvent = when (directive) {
    WalkDirective.Straight -> VoiceNavEvent.ContinueStraight(null)
    WalkDirective.Left -> VoiceNavEvent.Turn(SteeringCap.Left, null, null)
    WalkDirective.Right -> VoiceNavEvent.Turn(SteeringCap.Right, null, null)
    WalkDirective.TurnAround -> VoiceNavEvent.Literal(VoiceNavPhrases.TURN_AROUND)
    WalkDirective.Cross -> VoiceNavEvent.Literal(VoiceNavPhrases.CROSS_NOW)
    WalkDirective.Roundabout -> VoiceNavEvent.Literal(VoiceNavPhrases.ROUNDABOUT_FOLLOW)
}

/**
 * Queues de manœuvres : `tails[i]` = distance à vol d'oiseau de la manœuvre
 * `i` à l'arrivée, en suivant les manœuvres. Pure, testée en JVM.
 */
internal fun tailDistances(maneuvers: List<NavManeuver>): DoubleArray {
    val tails = DoubleArray(maneuvers.size)
    var acc = 0.0
    for (i in maneuvers.lastIndex downTo 1) {
        val a = maneuvers[i - 1]
        val b = maneuvers[i]
        acc += NavGeo.haversineMeters(a.lat, a.lon, b.lat, b.lon)
        tails[i - 1] = acc
    }
    return tails
}

/** Durée affichée pour [meters] restants, à allure piétonne. Pure. */
internal fun walkSeconds(meters: Long): Long =
    (meters.coerceAtLeast(0) / WALK_METERS_PER_SECOND).toLong()
