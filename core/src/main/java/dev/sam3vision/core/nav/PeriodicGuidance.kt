package dev.sam3vision.core.nav

import kotlin.math.abs

/**
 * Seuils du rappel périodique, injectés pour des tests déterministes.
 */
data class PeriodicTuning(
    /** Silence minimal entre deux rappels (le fix qui suit l'échéance parle). */
    val repeatMs: Long = 5_000L,
    /** Demi-ouverture du cône « tout droit » autour du cap de la cible. */
    val straightConeDeg: Double = 30.0,
    /** Au-delà de cet écart au cap, c'est un demi-tour, plus un virage. */
    val turnAroundDeg: Double = 150.0,
    /** Traversée à moins de ça + user immobile : ordre de traverser. */
    val crossingNearM: Double = 25.0,
    /** Sur la cible à moins de ça : on la traverse, « tout droit ». */
    val onTargetM: Double = 3.0,
)

/**
 * Consigne de rappel : six ordres, jamais de nom de rue, jamais de distance.
 * [Roundabout] n'existe qu'en repli sans cap (marcher vers un giratoire se
 * barre en relatif comme le reste).
 */
enum class WalkDirective {
    Straight,
    Left,
    Right,
    TurnAround,
    Cross,
    Roundabout,
}

/**
 * Rappel de guidage toutes les 5 s (pur JVM, sans Android, sans horloge) :
 * entre les annonces du moteur (une manœuvre se dit deux fois puis se tait),
 * le rappel redit où aller au premier fix GPS qui suit l'échéance.
 *
 * Cap utilisé : le cap **suivi** ([NavFix.headingDeg], direction de marche
 * donnée par le GPS quand l'utilisateur avance) — jamais l'orientation du
 * téléphone (boussole) : l'appareil est souvent en poche ou tenu de travers,
 * son orientation ne dit pas où marche son porteur. Immobile ou sans cap, le
 * rappel se replie sur la consigne absolue de la manœuvre (un virage à gauche
 * du tracé se dit « à gauche »).
 *
 * La cible est la prochaine manœuvre du tracé (pas l'arrivée à vol d'oiseau :
 * le rappel fait suivre les rues). Déterministe : le temps vient des fixes
 * ([NavFix.atMs]) — sans fix frais, silence, jamais de consigne périmée.
 */
class PeriodicGuidance(private val tuning: PeriodicTuning = PeriodicTuning()) {

    private var lastSpokenAtMs: Long? = null

    /**
     * Consigne pour [fix] vers [target], `null` s'il faut se taire (cible
     * inconnue, fix inexploitable, échéance pas atteinte). Une consigne rendue
     * est comptée dite (même contrat que l'annonceur : décider, c'est dire).
     */
    fun onFix(fix: NavFix, target: NavManeuver?): WalkDirective? {
        if (target == null || !fix.isUsable()) return null
        val last = lastSpokenAtMs
        if (last != null && fix.atMs - last < tuning.repeatMs) return null
        lastSpokenAtMs = fix.atMs
        return directiveFor(fix, target)
    }

    /**
     * Une consigne moteur vient de parler à [atMs] : le rappel repart de là
     * (pas de doublon sur le dos d'une annonce de virage).
     */
    fun noteSpoken(atMs: Long) {
        lastSpokenAtMs = atMs
    }

    /** Nouvelle session : le prochain fix parle. */
    fun reset() {
        lastSpokenAtMs = null
    }

    /**
     * Consigne vers [target] : relative au cap suivi quand il est connu
     * (écart signé au cap de la cible), absolue de la manœuvre sinon.
     */
    private fun directiveFor(fix: NavFix, target: NavManeuver): WalkDirective {
        val distM = NavGeo.haversineMeters(fix.lat, fix.lon, target.lat, target.lon)
        val heading = fix.headingDeg?.takeIf { it.isFinite() } ?: return fallbackFor(target, distM)
        if (!distM.isFinite() || distM < tuning.onTargetM) return WalkDirective.Straight
        val bearing = NavGeo.bearingDeg(fix.lat, fix.lon, target.lat, target.lon)
        if (!bearing.isFinite()) return WalkDirective.Straight
        return steer(wrap180(bearing - heading))
    }

    /**
     * Repli sans cap : traversée proche = ordre de traverser (l'utilisateur
     * est à l'arrêt au bord du trottoir, c'est le moment de le dire),
     * sinon verbe absolu de la manœuvre.
     */
    private fun fallbackFor(target: NavManeuver, distM: Double): WalkDirective {
        if (target.crossing && distM <= tuning.crossingNearM) return WalkDirective.Cross
        return when (target.action) {
            ManeuverAction.TurnLeft, ManeuverAction.SlightLeft -> WalkDirective.Left
            ManeuverAction.TurnRight, ManeuverAction.SlightRight -> WalkDirective.Right
            ManeuverAction.UTurn -> WalkDirective.TurnAround
            ManeuverAction.Roundabout -> WalkDirective.Roundabout
            ManeuverAction.Start, ManeuverAction.Continue, ManeuverAction.Arrive -> WalkDirective.Straight
        }
    }

    /**
     * Barre en relatif : dans le cône, tout droit ; au-delà du demi-tour,
     * demi-tour ; entre les deux, du côté signé (positif = cible à droite).
     */
    private fun steer(deltaDeg: Double): WalkDirective {
        val abs = abs(deltaDeg)
        if (abs <= tuning.straightConeDeg) return WalkDirective.Straight
        if (abs > tuning.turnAroundDeg) return WalkDirective.TurnAround
        return if (deltaDeg > 0) WalkDirective.Right else WalkDirective.Left
    }
}

/**
 * Écart signé ramené dans [-180, 180] : positif = cible à droite du cap.
 * NaN si l'entrée n'est pas finie. Pur, testé en JVM.
 */
internal fun wrap180(deg: Double): Double {
    if (!deg.isFinite()) return Double.NaN
    var shifted = (deg + 180.0) % 360.0
    if (shifted < 0) shifted += 360.0
    return shifted - 180.0
}
