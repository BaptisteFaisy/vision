package dev.sam3vision.core.nav

/**
 * Seuils du moteur, en mètres sauf [offRouteFixes] : tout est injecté pour
 * des tests déterministes (distances courtes, pas d'attente GPS réelle).
 */
data class NavTuning(
    /** Passer à moins de ça d'une manœuvre la valide. */
    val advanceRadiusM: Double = 12.0,
    /** À moins de ça de la destination, c'est l'arrivée. */
    val arrivalRadiusM: Double = 20.0,
    /** Première annonce (« dans 100 mètres »). */
    val announceFarM: Double = 100.0,
    /** Rappel proche (« dans 25 mètres »). */
    val announceNearM: Double = 25.0,
    /** Au-delà de cet écart latéral, le fix est suspect. */
    val offRouteM: Double = 30.0,
    /** En deçà, un écarté est de nouveau sur l'itinéraire. */
    val rejoinM: Double = 15.0,
    /** Suspects consécutifs avant de déclarer l'écart. */
    val offRouteFixes: Int = 3,
    /** Fixes moins précis que ça : ignorés (lus mais sans effet). */
    val maxAccM: Float = 25f,
)

/** État visible du moteur : [Guiding], écarté, arrivé, ou sans itinéraire. */
enum class NavState { Idle, Guiding, OffRoute, Finished }

/**
 * Événements de guidage, dans l'ordre d'émission : annonce d'une manœuvre
 * ([ManeuverAhead], une fois de loin puis une fois de près), écart
 * ([OffRoute] — **déclencheur de recalcul** : l'appelant recharge un
 * itinéraire via [VoiceNavEngine.reroute]), retour ([BackOnRoute]) et
 * arrivée ([Arrived], une seule fois).
 */
sealed interface NavEvent {
    /** Manœuvre [action] dans [distM] mètres ([near] = rappel proche). */
    data class ManeuverAhead(
        val action: ManeuverAction,
        val street: String?,
        val distM: Double,
        val near: Boolean,
    ) : NavEvent

    /** Écarté de [crossTrackM] mètres : l'appelant doit recalculer. */
    data class OffRoute(val crossTrackM: Double) : NavEvent

    /** De nouveau sur l'itinéraire, sans recalcul. */
    data class BackOnRoute(val crossTrackM: Double) : NavEvent

    /** Arrivé à destination (le moteur s'arrête). */
    object Arrived : NavEvent
}

/**
 * Moteur de guidage vocal pur (JVM seule, sans Android, sans horloge) :
 * progression sur l'itinéraire au fil des [NavFix], annonce de la prochaine
 * manœuvre (loin puis près), détection d'écart + déclencheur de recalcul,
 * arrivée.
 *
 * Déterministe : mêmes fixes ⇒ mêmes événements. Le temps vient des fixes
 * ([NavFix.atMs]) — fixes hors d'ordre ou périmés ignorés — jamais de
 * l'horloge ; aucune annonce n'est répétée (déclenchement sur front).
 */
class VoiceNavEngine(private val tuning: NavTuning = NavTuning()) {

    private var route: NavRoute? = null
    private var nextIndex = 1
    private var announcedFar = false
    private var announcedNext = false
    private var suspectFixes = 0
    private var offRoute = false
    private var finished = false
    private var lastAtMs = Long.MIN_VALUE

    /** État courant : [NavState.Idle] sans itinéraire, sinon guidage, écart ou fin. */
    val state: NavState
        get() = when {
            route == null -> NavState.Idle
            finished -> NavState.Finished
            offRoute -> NavState.OffRoute
            else -> NavState.Guiding
        }

    /** Prochaine manœuvre visée, `null` sans itinéraire ou une fois arrivé. */
    fun nextManeuver(): NavManeuver? {
        val maneuvers = route?.maneuvers ?: return null
        if (finished || nextIndex > maneuvers.lastIndex) return null
        return maneuvers[nextIndex]
    }

    /**
     * Démarre le guidage sur [candidate] (validé par [NavRoute.validate]) :
     * réinitialise progression, écart et arrivée. Échec [RouteError] sinon.
     */
    fun start(candidate: NavRoute): Result<Unit> {
        val valid = NavRoute.validate(candidate).getOrElse { return Result.failure(it) }
        route = valid
        nextIndex = 1
        announcedFar = false
        announcedNext = false
        suspectFixes = 0
        offRoute = false
        finished = false
        lastAtMs = Long.MIN_VALUE
        return Result.success(Unit)
    }

    /** Recharge un itinéraire après [NavEvent.OffRoute] : comme [start]. */
    fun reroute(candidate: NavRoute): Result<Unit> = start(candidate)

    /** Oublie l'itinéraire (fin du guidage, destination annulée). */
    fun stop() {
        route = null
        finished = false
        offRoute = false
    }

    /**
     * Avance le guidage d'un fix GPS : arrivée, passage de manœuvres,
     * écart éventuel, annonce éventuelle — dans cet ordre. Fix inexploitable,
     * trop grossier ou plus vieux que le précédent ⇒ liste vide.
     */
    fun onFix(fix: NavFix): List<NavEvent> {
        val maneuvers = route?.maneuvers ?: return emptyList()
        if (finished || !fix.isUsable()) return emptyList()
        if (fix.atMs <= lastAtMs || fix.accM > tuning.maxAccM) return emptyList()
        lastAtMs = fix.atMs
        if (hasArrived(fix, maneuvers)) {
            finished = true
            return listOf(NavEvent.Arrived)
        }
        advanceThrough(fix, maneuvers)
        offRouteEvent(fix, maneuvers)?.let { return listOf(it) }
        return announceAhead(fix, maneuvers)
    }

    /** À portée d'arrivée (point de la dernière manœuvre) ? */
    private fun hasArrived(fix: NavFix, maneuvers: List<NavManeuver>): Boolean {
        val dest = maneuvers.last()
        return NavGeo.haversineMeters(fix.lat, fix.lon, dest.lat, dest.lon) <= tuning.arrivalRadiusM
    }

    /** Valide les manœuvres traversées (saut de tunnel : plusieurs d'un coup). */
    private fun advanceThrough(fix: NavFix, maneuvers: List<NavManeuver>) {
        while (nextIndex < maneuvers.lastIndex) {
            val target = maneuvers[nextIndex]
            val dist = NavGeo.haversineMeters(fix.lat, fix.lon, target.lat, target.lon)
            if (dist > tuning.advanceRadiusM) break
            nextIndex++
            announcedFar = false
            announcedNext = false
            suspectFixes = 0
        }
    }

    /**
     * Écart latéral minimal aux segments restants (de la dernière manœuvre
     * validée à l'arrivée), en mètres.
     */
    private fun crossTrackM(fix: NavFix, maneuvers: List<NavManeuver>): Double {
        var best = Double.MAX_VALUE
        val from = (nextIndex - 1).coerceAtLeast(0)
        for (i in from until maneuvers.lastIndex) {
            val a = maneuvers[i]
            val b = maneuvers[i + 1]
            val dist = NavGeo.segmentMeters(fix.lat, fix.lon, a.lat, a.lon, b.lat, b.lon)
            if (dist < best) best = dist
        }
        return best
    }

    /**
     * Détecte l'écart : [NavEvent.OffRoute] après [NavTuning.offRouteFixes]
     * suspects consécutifs (déclencheur de recalcul pour l'appelant),
     * [NavEvent.BackOnRoute] au retour sans recalcul, `null` sinon.
     */
    private fun offRouteEvent(fix: NavFix, maneuvers: List<NavManeuver>): NavEvent? {
        val cross = crossTrackM(fix, maneuvers)
        if (offRoute) {
            if (cross > tuning.rejoinM) return null
            offRoute = false
            suspectFixes = 0
            return NavEvent.BackOnRoute(cross)
        }
        if (cross <= tuning.offRouteM) {
            suspectFixes = 0
            return null
        }
        suspectFixes++
        if (suspectFixes < tuning.offRouteFixes) return null
        offRoute = true
        return NavEvent.OffRoute(cross)
    }

    /**
     * Annonce la prochaine manœuvre sur front : une fois de loin
     * ([NavTuning.announceFarM]), une fois de près ([NavTuning.announceNearM]).
     */
    private fun announceAhead(fix: NavFix, maneuvers: List<NavManeuver>): List<NavEvent> {
        val target = maneuvers[nextIndex]
        val dist = NavGeo.haversineMeters(fix.lat, fix.lon, target.lat, target.lon)
        if (!announcedNext && dist <= tuning.announceNearM) {
            announcedFar = true
            announcedNext = true
            return listOf(NavEvent.ManeuverAhead(target.action, target.street, dist, near = true))
        }
        if (!announcedFar && dist <= tuning.announceFarM) {
            announcedFar = true
            return listOf(NavEvent.ManeuverAhead(target.action, target.street, dist, near = false))
        }
        return emptyList()
    }
}
