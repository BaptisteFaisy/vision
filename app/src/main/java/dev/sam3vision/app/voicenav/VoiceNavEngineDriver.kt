package dev.sam3vision.app.voicenav

import android.content.Context
import dev.sam3vision.app.navigation.VoiceNavTracker
import dev.sam3vision.app.speech.SpeechArbitration
import dev.sam3vision.app.speech.SpeechPriority
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.AndroidTtsDriver
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.app.speech.VoiceNavEvent
import dev.sam3vision.app.speech.VoiceNavPhrases
import dev.sam3vision.app.speech.VoiceNavSpeaker
import dev.sam3vision.core.crossing.CrossingText
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
import dev.sam3vision.core.nav.toNavRoute
import dev.sam3vision.core.net.HttpRouteClient
import dev.sam3vision.core.net.RouteClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Allure piétonne moyenne : la durée restante affichée est une estimation. */
internal const val WALK_METERS_PER_SECOND = 1.3

/**
 * Pilote du guidage embarqué : géocodage → itinéraire Valhalla → moteur →
 * voix système + écran. Le [VoiceNavSession] l'appelle à « Démarrer »
 * (crochet `engineStart`) ; `false` = repli Maps, `true` = guidage in-app.
 *
 * - [geocode] résout la destination (chaîne GMS puis BAN) ;
 * - [originNow] donne la position du départ (`null` = repli Maps) ;
 * - [routeClient] joint Valhalla (`POST /route` sur l'URL des Réglages) ;
 * - [onUpdate] reçoit l'état instantané (→ `session.onGuidanceUpdate`) ;
 * - [onRouteCap] reçoit le cap de chaque manœuvre (`null` en fin de guidage),
 *   pour la sonde SAM (`LiveProbe.setRouteCap`) : tant qu'il est posé, la
 *   sonde marche le couloir de la route (fusion itinéraire × obstacle).
 * - [onAutoCrossing] bascule le mode traversée (`true` = le rappel proche
 *   d'une traversée l'arme, `false` = traversée passée ou guidage coupé).
 * - [speakRoute] dit les manœuvres par la voix Laure (`null` = voix système) :
 *   la phrase (même politique que le canal système) + son urgence (sortie de
 *   trace et recalcul coupent, le reste attend). Une seule voix : GPS et
 *   obstacles partagent le même tuyau, jamais deux voix en même temps.
 * - [stopRoute] coupe la parole route (fin de guidage), miroir de [speakRoute].
 *
 * Voix : virages francs par [VoiceNavSpeaker.onTurn] (« Dans 100 mètres,
 * tournez à gauche, rue X »), le reste en phrase littérale du moteur
 * ([VoiceNavPhrases.ahead]) — un demi-tour ou un giratoire n'a pas de verbe
 * juste en caps, et une mauvaise consigne vaut moins que la phrase exacte.
 * L'arrivée ne passe PAS par le haut-parleur : [onUpdate] la signale et la
 * session annonce (« Arrivée à destination », une seule voix).
 *
 * Traversée : le moteur remplace l'annonce par [NavEvent.CrossingAhead]
 * quand la cible est un passage protégé — de loin, simple préavis parlé ;
 * de près, le mode traversée s'active ([onAutoCrossing]) et sa voix prend
 * le relais (vert + veto véhicules) pendant que la progression se tait.
 * [NavEvent.CrossingPassed] (ou [onTraverseModeChanged]`(false)`, coupe
 * manuelle) rend la parole au guidage. Seul le mode activé ici est coupé
 * ici : un mode manuel survit au passage ([autoCrossing]).
 *
 * Ne lève jamais après construction : tout échec de démarrage rend `false`,
 * tout échec en guidage est avalé (le moteur réémettra).
 */
class VoiceNavEngineDriver(
    appContext: Context,
    private val settings: () -> Settings,
    private val geocode: suspend (String) -> Result<GeocodedPlace>,
    private val originNow: () -> RoutePoint?,
    private val routeClient: (String) -> RouteClient = ::HttpRouteClient,
    private val scope: CoroutineScope,
    private val onUpdate: (GuidanceUpdate) -> Unit,
    private val onRouteCap: (SteeringCap?) -> Unit = {},
    private val onAutoCrossing: (Boolean) -> Unit = {},
    private val speakRoute: (suspend (phrase: String, urgent: Boolean) -> Unit)? = null,
    private val stopRoute: () -> Unit = {},
) {

    private val app = appContext.applicationContext
    private val tracker = VoiceNavTracker(app, onEvent = ::handleEvent)
    private val ttsLazy = lazy { SystemTtsEngine(AndroidTtsDriver(app)) }
    private val speakerLazy = lazy { VoiceNavSpeaker(ttsLazy.value, settings) }
    private val speaker by speakerLazy
    private val routeAnnouncer = VoiceNavAnnouncer()
    private val detourReroute = DetourReroute()

    @Volatile
    private var legs: GuidingLegs? = null
    private var rerouting = false

    /** Mode traversée activé par le guidage (lui seul est coupé ici). */
    @Volatile private var autoCrossing = false

    /**
     * Démarre le guidage vers [destination] : `true` si le moteur tourne
     * (voix « Navigation vers X » dite), `false` si un maillon manque
     * (adresse, position, réseau, itinéraire) — l'appelant bascule sur Maps.
     */
    suspend fun start(destination: String): Boolean {
        // Appels `suspend` (`runCatching` ne l'est pas) : try/catch directs.
        val place = try {
            geocode(destination)
        } catch (_: Exception) {
            null
        }?.getOrNull() ?: return false
        val origin = runCatching { originNow() }.getOrNull() ?: return false
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
        // l'appelant bascule sur Maps (voir `VoiceNavTracker.start`).
        if (!runCatching { tracker.start() }.getOrDefault(false)) {
            stop()
            return false
        }
        legs = GuidingLegs(nav, tailDistances(nav.maneuvers), RoutePoint(place.latitude, place.longitude))
        detourReroute.reset()
        say(VoiceNavEvent.Started(destination)) { speaker.onStarted(destination) }
        return true
    }

    /**
     * Parole d'un événement route : voix Laure quand elle est câblée
     * ([speakRoute], même politique que le canal système), sinon voix système
     * ([system]). L'urgence vient de [SpeechArbitration] (sortie de trace,
     * recalcul : coupe). Ne lève jamais.
     */
    private fun say(event: VoiceNavEvent, system: () -> String?) {
        val laure = speakRoute
        if (laure == null) {
            runCatching { system() }
            return
        }
        val phrase = routeAnnouncer.onEvent(System.currentTimeMillis(), event, settings().speechLanguage)
            ?: return
        val danger = SpeechArbitration.priorityOf(event) == SpeechPriority.Danger
        scope.launch { runCatching { laure(phrase, danger) } }
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
        rerouting = false
        clearAutoCrossing()
        runCatching { onRouteCap(null) }
        runCatching { stopRoute() }
        detourReroute.reset()
        runCatching { tracker.stop() }
        if (speakerLazy.isInitialized()) {
            runCatching { speaker.stop() }
            runCatching { speaker.reset() }
        }
    }

    /**
     * La sonde signale l'état du mode traversée : coupé manuellement, le
     * guidage reprend la parole (le mode auto ne se réarmera pas — le front
     * proche est consommé). Activé manuellement : sans effet ici.
     */
    fun onTraverseModeChanged(active: Boolean) {
        if (!active) autoCrossing = false
    }

    /** Libération finale : le canal vocal n'accepte plus rien ensuite. */
    fun shutdown() {
        stop()
        if (speakerLazy.isInitialized()) runCatching { speaker.shutdown() }
    }

    /** Itinéraire en cours de guidage, `null` au repos (carte de debug). */
    fun debugRoute(): NavRoute? = legs?.route

    /** Dernier fix GPS reçu, `null` sans fix (carte de debug). */
    fun debugFix(): NavFix? = tracker.lastFix

    /** Un événement moteur : parole + écran, jamais d'exception. */
    private fun handleEvent(event: NavEvent) {
        runCatching {
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

    /** Virage franc au cap parlé, le reste en phrase littérale du moteur. */
    private fun speakManeuver(event: NavEvent.ManeuverAhead) {
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

    /**
     * Traversée en vue : de loin, simple préavis ; de près, le mode
     * traversée s'arme (une seule fois) et sa voix prend le relais.
     */
    private fun speakCrossing(event: NavEvent.CrossingAhead) {
        if (!event.near) {
            val phrase = VoiceNavPhrases.crossingAhead(event.distM)
            say(VoiceNavEvent.Literal(phrase)) { speaker.onLiteral(phrase) }
            return
        }
        if (autoCrossing) return
        autoCrossing = true
        val phrase = CrossingText.mode(true, settings().speechLanguage)
        say(VoiceNavEvent.Literal(phrase)) { speaker.onLiteral(phrase) }
        runCatching { onAutoCrossing(true) }
    }

    /** Traversée passée : le mode auto se coupe, le guidage reprend. */
    private fun onCrossingPassed() {
        if (!autoCrossing) return
        autoCrossing = false
        say(VoiceNavEvent.Literal(VoiceNavPhrases.CROSSING_DONE)) { speaker.onLiteral(VoiceNavPhrases.CROSSING_DONE) }
        runCatching { onAutoCrossing(false) }
    }

    /** Fin du guidage : le mode auto éventuel se coupe, sans annonce. */
    private fun clearAutoCrossing() {
        if (!autoCrossing) return
        autoCrossing = false
        runCatching { onAutoCrossing(false) }
    }

    /** État écran : instruction + « dans X » + reste estimé du trajet. */
    private fun updateFor(event: NavEvent.ManeuverAhead): GuidanceUpdate =
        buildUpdate(
            VoiceNavPhrases.ahead(event.action, event.street, event.distM),
            event.distM,
        )

    /** État écran d'une traversée : préavis + « dans X » + reste estimé. */
    private fun updateForCrossing(distM: Double): GuidanceUpdate =
        buildUpdate(VoiceNavPhrases.crossingAhead(distM), distM)

    /** État écran : instruction + « dans X » + reste estimé du trajet. */
    private fun buildUpdate(instruction: String, distM: Double): GuidanceUpdate {
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
        val origin = runCatching { originNow() }.getOrNull() ?: return
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
