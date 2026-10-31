package dev.sam3vision.app.speech

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.nav.ManeuverAction
import dev.sam3vision.core.nav.NavEvent
import dev.sam3vision.core.speech.SpeechLanguage
import java.util.Locale

/**
 * Événement de guidage vocal embarqué : un virage, une arrivée, une sortie de
 * trace… Chaque valeur est un **hook de politique** : le moteur de navigation
 * (itinéraire Valhalla + position GPS) les émet, [VoiceNavAnnouncer] décide
 * s'il faut parler, [VoiceNavSpeaker] prononce via le TTS système.
 *
 * À ne pas confondre avec le handoff Google Maps ([NavigationHook]) : là,
 * c'est Maps qui parle et iSee ne sait rien (ni virage, ni arrivée). Ici,
 * l'arrivée est un événement du moteur embarqué, qui suit la position —
 * l'interdit « iSee n'annonce pas d'arrivée » de `docs/NAVIGATION.md` vise
 * le handoff, pas ce module.
 *
 * Pur (aucun Android) : les phrases ([VoiceNavPhrases]) et les signatures
 * ([VoiceNavPolicy]) sont testées en JVM.
 */
sealed interface VoiceNavEvent {
    /** Guidage démarré vers [destination] (texte dicté, tel quel). */
    data class Started(val destination: String) : VoiceNavEvent

    /**
     * Virage à [cap] ([SteeringCap.Left] ou `Right`), à [meters] mètres
     * ([street] si la rue est connue) : préavis directionnel de loin,
     * ordre sec sans distance de près ([VoiceNavPolicy.TURN_NOW_M]).
     * Un cap [SteeringCap.Straight] n'est pas un virage : il se dit comme
     * [ContinueStraight] (clémence, jamais d'erreur).
     */
    data class Turn(
        val cap: SteeringCap,
        val meters: Float? = null,
        val street: String? = null,
    ) : VoiceNavEvent

    /** Ligne droite, encore [meters] mètres si connu. */
    data class ContinueStraight(val meters: Float? = null) : VoiceNavEvent

    /** Arrivée à [destination] (vide = arrivée sans nom). */
    data class Arrival(val destination: String) : VoiceNavEvent

    /** Sortie de trace : urgent, coupe la phrase en cours. */
    data object OffRoute : VoiceNavEvent

    /** Nouvel itinéraire après recalcul : urgent, suit [OffRoute] de près. */
    data object Rerouted : VoiceNavEvent

    /** Fin de guidage sur ordre de l'utilisateur : urgent, parle tout de suite. */
    data object Stopped : VoiceNavEvent

    /** Retour sur le tracé sans recalcul : informatif, ne coupe rien. */
    data object BackOnRoute : VoiceNavEvent

    /**
     * Phrase littérale du moteur ([VoiceNavPhrases.ahead] : demi-tour,
     * giratoire, « Destination dans X ») : les caps [SteeringCap] ne savent
     * dire que gauche/droit/droite, un demi-tour n'y a pas de verbe juste —
     * mieux vaut la phrase exacte du moteur qu'un cap approximatif.
     */
    data class Literal(val phrase: String) : VoiceNavEvent
}

/**
 * Phrases du guidage vocal, dans la langue de la voix ([SpeechLanguage]).
 *
 * Français (contrat, miroirs `nav_voice_*` dans `strings.xml`) :
 * « Continuez dans cette direction[, rue X]. » de loin,
 * « Tournez à gauche[, rue X]. » de près (jamais de distance de virage —
 * [VoiceNavPolicy.TURN_NOW_M]), « Continuez tout droit. »,
 * « Vous êtes arrivé à X. »,
 * « Vous vous êtes écarté de l'itinéraire. Recalcul en cours. »,
 * « Nouvel itinéraire calculé. », « Guidage arrêté. »,
 * « De retour sur l'itinéraire. » (+ phrases littérales du moteur).
 *
 * Distances prononcées via [DistanceText.spoken] (singuliers et décimales
 * déjà réglés), arrondies vers le bas ([roundNavMeters]) et en kilomètres
 * au-delà d'un kilomètre — on n'annonce jamais plus loin que le réel.
 * Pures et sans Android : testées en JVM.
 */
object VoiceNavPhrases {

    /** Phrase prononcée pour [event], jamais blanche. */
    fun phrase(event: VoiceNavEvent, language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (event) {
            is VoiceNavEvent.Started -> started(event.destination, language)
            is VoiceNavEvent.Turn -> turn(event, language)
            is VoiceNavEvent.ContinueStraight -> continueStraight(event.meters, language)
            is VoiceNavEvent.Arrival -> arrival(event.destination, language)
            VoiceNavEvent.OffRoute -> offRoute(language)
            VoiceNavEvent.Rerouted -> rerouted(language)
            VoiceNavEvent.Stopped -> stopped(language)
            VoiceNavEvent.BackOnRoute -> backOnRoute(language)
            is VoiceNavEvent.Literal -> event.phrase.ifBlank { stopped(language) }
        }

    /** Départ : la branche FR garde le texte historique, l'anglais suit le contrat. */
    private fun started(destination: String, language: SpeechLanguage): String =
        when (language) {
            SpeechLanguage.Fr -> "Navigation vers ${destination.trim()}"
            SpeechLanguage.En -> "Navigating to ${destination.trim()}"
        }

    /**
     * Virage franc : préavis directionnel de loin, ordre sec sans distance
     * de près ([VoiceNavPolicy.TURN_NOW_M]) ; cap droit = ligne droite.
     */
    private fun turn(event: VoiceNavEvent.Turn, language: SpeechLanguage): String {
        if (event.cap == SteeringCap.Straight) return continueStraight(event.meters, language)
        val side = if (event.cap == SteeringCap.Left) sideWord(Side.Left, language) else sideWord(Side.Right, language)
        val street = event.street?.trim().orEmpty()
        val tail = if (street.isEmpty()) "." else ", $street."
        if (!VoiceNavPolicy.isTurnNow(event.meters)) return turnAhead(tail, language)
        return when (language) {
            SpeechLanguage.Fr -> "Tournez à $side$tail"
            SpeechLanguage.En -> "Turn $side$tail"
        }
    }

    /** Préavis lointain d'un virage franc : la direction, jamais la distance. */
    private fun turnAhead(tail: String, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Continuez dans cette direction$tail"
        SpeechLanguage.En -> "Continue in this direction$tail"
    }

    /** « Continuez tout droit[ pendant 200 mètres]. » */
    private fun continueStraight(meters: Float?, language: SpeechLanguage): String {
        val distance = navDistance(meters, language)
        return when (language) {
            SpeechLanguage.Fr -> if (distance == null) "Continuez tout droit." else "Continuez tout droit pendant $distance."
            SpeechLanguage.En -> if (distance == null) "Continue straight ahead." else "Continue straight ahead for $distance."
        }
    }

    /** « Vous êtes arrivé à X. » (destination vide = « Vous êtes arrivé. »). */
    private fun arrival(destination: String, language: SpeechLanguage): String {
        val clean = destination.trim()
        return when (language) {
            SpeechLanguage.Fr -> if (clean.isEmpty()) "Vous êtes arrivé." else "Vous êtes arrivé à $clean."
            SpeechLanguage.En -> if (clean.isEmpty()) "You have arrived." else "You have arrived at $clean."
        }
    }

    /** Hors-trace : la sortie se dit avec le recalcul, jamais seule. */
    private fun offRoute(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Vous vous êtes écarté de l'itinéraire. Recalcul en cours."
        SpeechLanguage.En -> "You are off route. Recalculating."
    }

    /** Nouvel itinéraire calculé après recalcul. */
    private fun rerouted(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Nouvel itinéraire calculé."
        SpeechLanguage.En -> "New route calculated."
    }

    /** Fin de guidage sur ordre de l'utilisateur. */
    private fun stopped(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "Guidage arrêté."
        SpeechLanguage.En -> "Guidance stopped."
    }

    /** Retour sur le tracé (point final : style des phrases parlées). */
    private fun backOnRoute(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> "De retour sur l'itinéraire."
        SpeechLanguage.En -> "Back on route."
    }

    /** Distance prononcée, `null` si inconnue : mètres puis kilomètres. */
    private fun navDistance(meters: Float?, language: SpeechLanguage): String? {
        val rounded = VoiceNavPolicy.roundNavMeters(meters) ?: return null
        if (rounded < 1_000f) return DistanceText.spoken(rounded, language)
        return kilometers(rounded, language)
    }

    /** « 1,2 kilomètre », « 2 kilomètres » (toujours arrondi vers le bas). */
    private fun kilometers(rounded: Float, language: SpeechLanguage): String {
        val tenth = ((rounded / 1_000f) * 10).toInt()
        val locale = if (language == SpeechLanguage.Fr) Locale.FRANCE else Locale.US
        val text = String.format(locale, "%.1f", tenth / 10f)
        return when (language) {
            SpeechLanguage.Fr -> "$text ${if (tenth < 20) "kilomètre" else "kilomètres"}"
            SpeechLanguage.En -> "$text ${if (tenth == 10) "kilometer" else "kilometers"}"
        }
    }

    /** Gauche/droite prononcée, dans la langue de la voix. */
    private fun sideWord(side: Side, language: SpeechLanguage): String = when (language) {
        SpeechLanguage.Fr -> if (side == Side.Left) "gauche" else "droite"
        SpeechLanguage.En -> if (side == Side.Left) "left" else "right"
    }

    /** Côté de virage, sans le cap droit (traité comme ligne droite). */
    private enum class Side { Left, Right }

    // ── Événements moteur (NavEvent) : textes du contrat moteur ──
    // « You have left the route. Recalculating. »,
    // « Back on route. », « You have arrived at X »,
    // « Continue in this direction, rue de la Paix » (far) then
    // « Turn right, rue de la Paix » (near, no distance).
    // Miroirs `nav_voice_left_route`, `nav_voice_back_on_route`,
    // `nav_voice_arrived[_plain]`, `nav_voice_maneuver` dans `strings.xml`.

    /** Écart : l'appelant recalcule pendant que la voix prévient. */
    const val OFF_ROUTE = "You have left the route. Recalculating."

    /** Retour sur le tracé sans recalcul. */
    const val BACK_ON_ROUTE = "Back on route."

    /** Arrivé, sans destination nommée. */
    const val ARRIVED_PLAIN = "You have arrived at your destination"

    /**
     * Phrase d'un événement moteur [event] : annonce de manœuvre, traversée,
     * arrivée (habillée de [destinationName] quand il est connu), écart ou
     * retour.
     */
    fun event(event: NavEvent, destinationName: String?): String = when (event) {
        is NavEvent.ManeuverAhead -> ahead(event.action, event.street, event.distM)
        is NavEvent.CrossingAhead -> crossingAhead(event.distM)
        NavEvent.CrossingPassed -> CROSSING_DONE
        is NavEvent.Arrived -> arrived(destinationName)
        is NavEvent.OffRoute -> OFF_ROUTE
        is NavEvent.BackOnRoute -> BACK_ON_ROUTE
    }

    /**
     * Annonce d'une manœuvre à venir : les virages francs (même ensemble
     * que [toSteeringCap][dev.sam3vision.app.voicenav.toSteeringCap]) se
     * disent en deux temps — préavis directionnel de loin, ordre sec sans
     * distance de près ([VoiceNavPolicy.TURN_NOW_M]) ; le reste
     * (« [verb] in [distance] ») et l'arrivée à venir
     * (« Destination in X ») sont inchangés. Rue inconnue : la phrase
     * omet la rue au lieu d'inventer.
     */
    fun ahead(action: ManeuverAction, street: String?, distM: Double): String {
        val where = street?.trim()?.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""
        if (isFrankTurn(action) && !VoiceNavPolicy.isTurnNow(distM)) {
            return "Continue in this direction$where"
        }
        val verb = verb(action)
        if (isFrankTurn(action)) return "$verb$where"
        val distance = DistanceText.spoken(distM.toFloat(), SpeechLanguage.En)
        if (action == ManeuverAction.Arrive) return "Destination in $distance"
        return "$verb in $distance$where"
    }

    /** Virages francs : les actions routées au cap parlé (tourner, serrer). */
    private fun isFrankTurn(action: ManeuverAction): Boolean = when (action) {
        ManeuverAction.TurnLeft, ManeuverAction.TurnRight,
        ManeuverAction.SlightLeft, ManeuverAction.SlightRight -> true
        ManeuverAction.Start, ManeuverAction.Continue,
        ManeuverAction.UTurn, ManeuverAction.Roundabout,
        ManeuverAction.Arrive -> false
    }

    /** « You have arrived at X » (ou [ARRIVED_PLAIN] sans nom). */
    fun arrived(destinationName: String?): String {
        val name = destinationName?.trim()?.takeIf { it.isNotEmpty() } ?: return ARRIVED_PLAIN
        return "You have arrived at $name"
    }

    /**
     * « Crosswalk in X » : préavis de traversée (miroir
     * `nav_voice_crossing` dans `strings.xml`). Sans rue : Valhalla ne
     * donne pas de nom aux traversées.
     */
    fun crossingAhead(distM: Double): String {
        val distance = DistanceText.spoken(distM.toFloat(), SpeechLanguage.En)
        return "Crosswalk in $distance"
    }

    /** Traversée passée : le guidage reprend (miroir `nav_voice_crossing_done`). */
    const val CROSSING_DONE = "Crossing finished."

    // ── Confirmation (session) : complètent NavigationPhrases ──
    // Question vocale (toujours en anglais, exigence produit), « Here is the
    // page for X », « Guidance stopped », « Arrived at destination ». Miroirs
    // `nav_confirm_question[_no_time]`, `nav_confirm_cancelled`,
    // `nav_confirm_loading`, `nav_place_opened`, `nav_stop_done`,
    // `nav_arrived` dans `strings.xml`.

    /**
     * Question de confirmation, toujours en anglais : « Do you really want
     * to go to X? Travel time: Y. Answer yes or no. » — [destination] brute,
     * espaces rognés. [durationEn] `null` (résumé indisponible, repli Maps) :
     * la question omet le temps au lieu d'inventer.
     */
    fun confirmQuestion(destination: String, durationEn: String?): String {
        val clean = destination.trim()
        val time = durationEn?.trim()?.takeIf { it.isNotEmpty() }?.let { " Travel time: $it." } ?: ""
        return "Do you really want to go to $clean?$time Answer yes or no."
    }

    /** Confirmation refusée (« no ») : accusé bref, en anglais. */
    const val CONFIRM_CANCELLED = "Cancelled."

    /** « yes » pendant le chargement du résumé : patienter, en anglais. */
    const val CONFIRM_LOADING = "Your route is still loading, one moment."

    /** « Here is the page for X » — guidage éteint, fiche lieu ouverte. */
    fun placeOpened(destination: String): String = "Here is the page for ${destination.trim()}"

    /** Ordre stop consommé, session fermée. */
    const val STOP_DONE = "Guidance stopped"

    /** GPS désactivé sur ordre de l'utilisateur (« turn off the GPS »). */
    const val GPS_DISABLED = "GPS disabled"

    /** GPS réactivé sur ordre de l'utilisateur (« turn on the GPS »). */
    const val GPS_ENABLED = "GPS enabled"

    /** Arrivée signalée par le moteur d'itinéraire (jamais devinée). */
    const val ARRIVED = "Arrived at destination"

    /** Verbe d'annonce d'une action (le départ ne s'annonce jamais). */
    private fun verb(action: ManeuverAction): String = when (action) {
        ManeuverAction.Start -> "Go"
        ManeuverAction.Continue -> "Continue straight ahead"
        ManeuverAction.SlightLeft -> "Bear left"
        ManeuverAction.TurnLeft -> "Turn left"
        ManeuverAction.SlightRight -> "Bear right"
        ManeuverAction.TurnRight -> "Turn right"
        ManeuverAction.UTurn -> "Make a U-turn"
        ManeuverAction.Roundabout -> "At the roundabout, follow the road"
        ManeuverAction.Arrive -> "You are arriving"
    }
}

/**
 * Politique anti-répétition des événements : signature ([key]) et urgence
 * ([isUrgent]), branchées sur [AnnouncementGate][dev.sam3vision.core.live.AnnouncementGate]
 * par [VoiceNavAnnouncer].
 *
 * Les signatures sont toujours françaises (comme [dev.sam3vision.core.live.Guidance.key],
 * pour ne pas rebavarder au changement de langue). Un virage franc porte sa
 * phase (`ahead` puis `now`) au lieu de la distance : le préavis lointain se
 * dit une seule fois, l'ordre sec une seule fois — deux annonces, pas une
 * répétition. Pur et testé en JVM.
 */
object VoiceNavPolicy {

    /**
     * Seuil de l'ordre sec, en mètres : à cette distance ou moins (ou sans
     * distance), le virage franc se dit « Tournez à gauche[, rue X]. » ;
     * au-delà, préavis « Continuez dans cette direction[, rue X]. », dit
     * une seule fois (signature [key]). Aligné sur
     * [NavTuning.announceNearM][dev.sam3vision.core.nav.NavTuning] : le
     * rappel proche du moteur parle l'ordre sec, l'annonce lointaine le
     * préavis — jamais « dans X mètres » pour un virage franc.
     */
    const val TURN_NOW_M = 25f

    /** Ordre sec (près, inconnu ou invalide) plutôt que préavis lointain ? */
    fun isTurnNow(meters: Float?): Boolean =
        meters == null || !meters.isFinite() || meters <= TURN_NOW_M

    /** Même décision sur une distance moteur (voir [ahead][VoiceNavPhrases.ahead]). */
    fun isTurnNow(distM: Double): Boolean =
        !distM.isFinite() || distM <= TURN_NOW_M

    /**
     * Distance conservatrice : `null` si inconnue ou invalide, telle quelle sous
     * 30 m, au dizaine en dessous sous 100 m, à la cinquantaine en dessous au-delà.
     * On annonce toujours moins que le réel — jamais « dans 150 mètres » à 127 m.
     */
    fun roundNavMeters(meters: Float?): Float? {
        if (meters == null || !meters.isFinite() || meters <= 0f) return null
        if (meters < 30f) return meters
        if (meters < 100f) return ((meters / 10f).toInt() * 10).toFloat()
        return ((meters / 50f).toInt() * 50).toFloat()
    }

    /** Signature anti-répétition de [event], toujours française. */
    fun key(event: VoiceNavEvent): String = when (event) {
        is VoiceNavEvent.Started -> "started"
        is VoiceNavEvent.Turn -> turnKey(event)
        is VoiceNavEvent.ContinueStraight -> straightKey(event.meters)
        is VoiceNavEvent.Arrival -> "arrival"
        VoiceNavEvent.OffRoute -> "offroute"
        VoiceNavEvent.Rerouted -> "rerouted"
        VoiceNavEvent.Stopped -> "stopped"
        VoiceNavEvent.BackOnRoute -> "backonroute"
        is VoiceNavEvent.Literal -> "literal|${event.phrase.trim()}"
    }

    /** Urgent = parle tout de suite, délai écoulé ou non, en coupant la phrase en cours. */
    fun isUrgent(event: VoiceNavEvent): Boolean = when (event) {
        VoiceNavEvent.OffRoute, VoiceNavEvent.Rerouted, VoiceNavEvent.Stopped -> true
        is VoiceNavEvent.Arrival -> true
        else -> false
    }

    /** `turn|left|ahead|rue de rivoli` (cap droit = signature de ligne droite). */
    private fun turnKey(event: VoiceNavEvent.Turn): String {
        if (event.cap == SteeringCap.Straight) return straightKey(event.meters)
        val side = if (event.cap == SteeringCap.Left) "left" else "right"
        val phase = if (isTurnNow(event.meters)) "now" else "ahead"
        val street = event.street?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "-"
        return "turn|$side|$phase|$street"
    }

    /** `straight|200` (distance arrondie, `?` si inconnue). */
    private fun straightKey(meters: Float?): String {
        val bucket = roundNavMeters(meters)?.toInt()?.toString() ?: "?"
        return "straight|$bucket"
    }
}

