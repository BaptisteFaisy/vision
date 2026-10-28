package dev.sam3vision.app.voicenav

import android.content.Context
import dev.sam3vision.app.navigation.VoiceNavTracker
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.AndroidTtsDriver
import dev.sam3vision.app.speech.VoiceNavPhrases
import dev.sam3vision.app.speech.VoiceNavSpeaker
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.GeocodedPlace
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.nav.ManeuverAction
import dev.sam3vision.core.nav.NavEvent
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
 * - [onUpdate] reçoit l'état instantané (→ `session.onGuidanceUpdate`).
 *
 * Voix : virages francs par [VoiceNavSpeaker.onTurn] (« Dans 100 mètres,
 * tournez à gauche, rue X »), le reste en phrase littérale du moteur
 * ([VoiceNavPhrases.ahead]) — un demi-tour ou un giratoire n'a pas de verbe
 * juste en caps, et une mauvaise consigne vaut moins que la phrase exacte.
 * L'arrivée ne passe PAS par le haut-parleur : [onUpdate] la signale et la
 * session annonce (« Arrivée à destination », une seule voix).
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
) {

    private val app = appContext.applicationContext
    private val tracker = VoiceNavTracker(app, onEvent = ::handleEvent)
    private val ttsLazy = lazy { SystemTtsEngine(AndroidTtsDriver(app)) }
    private val speakerLazy = lazy { VoiceNavSpeaker(ttsLazy.value, settings) }
    private val speaker by speakerLazy

    private var legs: GuidingLegs? = null
    private var rerouting = false

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
        legs = GuidingLegs(nav, tailDistances(nav.maneuvers), RoutePoint(place.latitude, place.longitude))
        runCatching { tracker.start() }
        runCatching { speaker.onStarted(destination) }
        return true
    }

    /** Coupe l'écoute, la parole et la mémoire ; réutilisable après. */
    fun stop() {
        legs = null
        rerouting = false
        runCatching { tracker.stop() }
        if (speakerLazy.isInitialized()) {
            runCatching { speaker.stop() }
            runCatching { speaker.reset() }
        }
    }

    /** Libération finale : le canal vocal n'accepte plus rien ensuite. */
    fun shutdown() {
        stop()
        if (speakerLazy.isInitialized()) runCatching { speaker.shutdown() }
    }

    /** Un événement moteur : parole + écran, jamais d'exception. */
    private fun handleEvent(event: NavEvent) {
        runCatching {
            when (event) {
                is NavEvent.ManeuverAhead -> {
                    speakManeuver(event)
                    onUpdate(updateFor(event))
                }
                is NavEvent.Arrived -> {
                    onUpdate(GuidanceUpdate(VoiceNavPhrases.ARRIVED, 0, 0, 0, arrived = true))
                    stop()
                }
                is NavEvent.OffRoute -> {
                    speaker.onOffRoute()
                    launchReroute()
                }
                is NavEvent.BackOnRoute -> speaker.onBackOnRoute()
            }
        }
    }

    /** Virage franc au cap parlé, le reste en phrase littérale du moteur. */
    private fun speakManeuver(event: NavEvent.ManeuverAhead) {
        val cap = event.action.toSteeringCap()
        if (cap == null) {
            speaker.onLiteral(VoiceNavPhrases.ahead(event.action, event.street, event.distM))
        } else {
            speaker.onTurn(cap, event.distM.toFloat(), event.street)
        }
    }

    /** État écran : instruction + « dans X » + reste estimé du trajet. */
    private fun updateFor(event: NavEvent.ManeuverAhead): GuidanceUpdate {
        val distM = event.distM.toLong().coerceAtLeast(0)
        val remaining = remainingMeters(event)
        return GuidanceUpdate(
            instruction = VoiceNavPhrases.ahead(event.action, event.street, event.distM),
            distanceToManeuverMeters = distM,
            remainingDistanceMeters = remaining,
            remainingDurationSeconds = walkSeconds(remaining),
        )
    }

    /** Reste estimé : « dans X » + queue des manœuvres suivantes. */
    private fun remainingMeters(event: NavEvent.ManeuverAhead): Long {
        val legs = legs ?: return event.distM.toLong().coerceAtLeast(0)
        val index = legs.route.maneuvers.indexOf(runCatching { tracker.nextManeuver() }.getOrNull())
        if (index < 0) return event.distM.toLong().coerceAtLeast(0)
        return (event.distM + legs.tails[index]).toLong().coerceAtLeast(0)
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
            runCatching { speaker.onRerouted() }
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
