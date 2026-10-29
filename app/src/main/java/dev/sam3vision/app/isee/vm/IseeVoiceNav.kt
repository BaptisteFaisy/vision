package dev.sam3vision.app.isee.vm

import android.Manifest
import android.app.Application
import android.location.LocationManager
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.navigation.AndroidGeocoderSource
import dev.sam3vision.app.navigation.AndroidNavigationLauncher
import dev.sam3vision.app.perms.hasPerm
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.app.voicenav.GuidanceLifecycle
import dev.sam3vision.app.voicenav.ValhallaRouteLoader
import dev.sam3vision.app.voicenav.VoiceNavEngineDriver
import dev.sam3vision.app.voicenav.VoiceNavSession
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.GuidanceOrder
import dev.sam3vision.core.nav.StopGuidanceParser
import dev.sam3vision.core.nav.BanGeocoder
import dev.sam3vision.core.nav.GeocodeChain
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavRoute
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.voicenav.VoiceDestinationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Recette debug : 60 sondages de 500 ms = 30 s max pour le résumé. */
private const val DEBUG_NAV_POLLS = 60
private const val DEBUG_NAV_POLL_MS = 500L

/**
 * Navigation vocale in-app de l'écran iSee : chaîne GMS puis BAN, résumés
 * Valhalla, session + pilote. Extrait d'`IseeViewModel` (limite 500 lignes).
 *
 * Paresseux : ni géocodeur, ni TTS système, ni GPS ne sont touchés tant
 * qu'aucune dictée ne passe par le chemin configuré (`routeConfigured`).
 * Ce chemin est actif dès que `routeBaseUrl` est renseignée (seed du build
 * dans `SettingsSeed`). [speak] est la voix de navigation (`voice::say`).
 */
class IseeVoiceNav(
    app: Application,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
    private val speak: suspend (String) -> Unit,
    /** Le pilote y arme/coupe le mode traversée (rappel proche / passage). */
    private val onAutoCrossing: (Boolean) -> Unit = {},
    /** Parole route via Laure (manœuvres GPS, même voix que les obstacles). */
    private val speakRoute: (suspend (phrase: String, urgent: Boolean) -> Unit)? = null,
    /** Coupe la parole route (fin de guidage). */
    private val stopRoute: () -> Unit = {},
    /** Cap route poussé à la sonde (`null` = fin, retour au cap manuel). */
    private val onRouteCap: (SteeringCap?) -> Unit = {},
    /** L'utilisateur parle (dictée) : la voix ordinaire du guidage se tait. */
    private val userSpeaking: () -> Boolean = { false },
    /** Ordre « désactive / active le GPS » : persiste le réglage guidage. */
    private val setGuidance: (Boolean) -> Unit = {},
) {

    private val context = app.applicationContext

    private val chain: GeocodeChain by lazy {
        GeocodeChain(AndroidGeocoderSource(context), BanGeocoder())
    }

    private val driverLazy = lazy {
        VoiceNavEngineDriver(
            appContext = context,
            settings = settings,
            geocode = { address -> chain.geocode(address) },
            originNow = ::lastKnownOrigin,
            scope = scope,
            onUpdate = ::pushGuidanceUpdate,
            onAutoCrossing = onAutoCrossing,
            speakRoute = speakRoute,
            stopRoute = stopRoute,
            onRouteCap = onRouteCap,
            userSpeaking = userSpeaking,
        )
    }
    private val driver: VoiceNavEngineDriver by driverLazy

    /** Porte de la voix ambiante (annonces, voix du sol) : GPS désactivé = fermée. */
    val voiceGate = IseeVoiceGate()

    /** Session de navigation (entrée vocale, confirmation, guidage). */
    val session: VoiceNavSession by lazy {
        val loader = ValhallaRouteLoader(
            geocode = { address -> chain.geocode(address) },
            originNow = ::lastKnownOrigin,
            baseUrl = { settings().routeBaseUrl },
        )
        VoiceNavSession(
            launcher = AndroidNavigationLauncher(context),
            guidanceEnabled = { settings().navigationGuidanceEnabled },
            announcer = NavigationAnnouncer({ settings() }) { _, text -> speak(text) },
            voice = VoiceNavAnnouncer({ settings() }) { _, text -> speak(text) },
            routeLoader = { destination -> loader(destination) },
            extraParsers = listOf(VoiceDestinationParser::parse),
            engineStart = { destination, _ -> driver.start(destination) },
            onSessionIdle = { driver.stop() },
            // Ordres « désactive / active le GPS » : la porte de voix suit.
            onGpsDisabled = {
                voiceGate.muteGps()
                setGuidance(false)
            },
            onGpsEnabled = {
                voiceGate.unmuteGps()
                setGuidance(true)
            },
            // Pas de départ surprise : la destination dictée ouvre l'écran de
            // confirmation (question vocale + bouton Démarrer) — le guidage
            // ne démarre qu'après un objectif explicite (autoStartOnVoice=false).
        )
    }

    /**
     * Ordre GPS dicté (« arrête / désactive / active le GPS ») : routé tel
     * quel à la session, sans exiger l'URL Valhalla — l'ordre est honoré
     * même en guidage Maps. `true` si le transcript était un ordre (consommé).
     */
    suspend fun runOrder(text: String): Boolean {
        val outcome = runCatching { StopGuidanceParser.parse(text) }.getOrDefault(GuidanceOrder.NONE)
        if (outcome == GuidanceOrder.NONE) return false
        return session.onVoiceTranscript(text)
    }

    /** L'utilisateur prend la parole : la voix du guidage en cours se tait. */
    fun stopVoice() {
        if (driverLazy.isInitialized()) runCatching { driver.stopVoice() }
    }

    /** Réglages « guidage GPS » rallumés hors ordre : la porte se rouvre. */
    fun resumeGps() {
        if (voiceGate.ambientAllowed()) return
        runCatching { if (settings().navigationGuidanceEnabled) voiceGate.unmuteGps() }
    }

    /**
     * Synchronise la porte avec les réglages persistés (au chargement de
     * session) : guidage désactivé (par ordre vocal, réglages ou relance)
     * → la porte reste fermée même après un redémarrage — la voix ambiante
     * ne reparle qu'au retour de l'utilisateur (question, photo, caméra).
     */
    fun syncGate() {
        if (settings().navigationGuidanceEnabled) voiceGate.unmuteGps() else voiceGate.muteGps()
    }

    /** État de la navigation vocale (repos, confirmation, guidage, carte). */
    val state: StateFlow<VoiceNavUiState> get() = session.state

    /**
     * Quitter l'écran navigation : confirmation annulée ou guidage arrêté
     * (annonce « Guidage arrêté » + moteur coupé via le repos de session).
     * Le service de fond est toujours coupé aussi : après une rotation, la
     * mise en fond l'a démarré et la sortie d'écran suit aussitôt — sans ça
     * il resterait orphelin. En fond maintenu, le tracker et la voix du
     * pilote continuent seuls (rien ne les coupe) jusqu'à ce retour.
     */
    fun dismiss() {
        GuidanceLifecycle.stopBackground(context)
        session.cancelConfirmation()
        scope.launch { session.stopGuidance() }
    }

    /** Libération finale (moteur TTS système). */
    fun shutdown() {
        runCatching { driver.shutdown() }
    }

    /**
     * La sonde signale l'état du mode traversée (coupe manuelle : le
     * guidage reprend). Sans effet si le pilote n'a jamais démarré (il ne
     * s'initialise pas pour une bascule hors guidage).
     */
    fun onTraverseModeChanged(active: Boolean) {
        if (driverLazy.isInitialized()) {
            runCatching { driver.onTraverseModeChanged(active) }
        }
    }

    /**
     * Détour obstacle de la sonde : transmis au pilote (recalcul Valhalla
     * depuis le GPS temps réel s'il dure). Sans effet si le pilote n'a
     * jamais démarré.
     */
    fun onProbeDetour(detour: Boolean, nowMs: Long) {
        if (driverLazy.isInitialized()) {
            runCatching { driver.onDetour(detour, nowMs) }
        }
    }

    /**
     * Vrai si la voix route parle (arbitre vocal : l'obstacle non urgent
     * attend). N'initialise jamais le pilote paresseux : jamais démarré =
     * silencieux, hors guidage le comportement est inchangé.
     */
    fun isRouteSpeaking(): Boolean =
        if (driverLazy.isInitialized()) driver.isSpeaking() else false

    /** Itinéraire guidé, `null` au repos ou pilote jamais démarré (carte de debug). */
    fun debugRoute(): NavRoute? =
        if (driverLazy.isInitialized()) runCatching { driver.debugRoute() }.getOrNull() else null

    /** Dernier fix GPS, `null` sans fix ou pilote jamais démarré (carte de debug). */
    fun debugFix(): NavFix? =
        if (driverLazy.isInitialized()) runCatching { driver.debugFix() }.getOrNull() else null

    /**
     * Recette adb `--es destination <texte>` (builds debug seuls) : injecte
     * une dictée « aller à … » puis confirme dès le résumé chargé — le
     * guidage démarre sans voix. Hors debug ou destination blanche : sans
     * effet. Ne lève jamais.
     */
    fun debugNavTo(destination: String) {
        if (!BuildConfig.DEBUG || destination.isBlank()) return
        scope.launch { runCatching { startDebugGuidance(destination) } }
    }

    /** Dictée injectée puis confirmation dès le résumé (30 s max). */
    private suspend fun startDebugGuidance(destination: String) {
        if (!session.onVoiceTranscript("aller à $destination")) return
        repeat(DEBUG_NAV_POLLS) {
            when (val current = state.value) {
                is VoiceNavUiState.Guiding -> return
                is VoiceNavUiState.Confirming -> if (!current.loading) {
                    session.confirmDestination()
                    return
                }
                else -> Unit
            }
            delay(DEBUG_NAV_POLL_MS)
        }
    }

    /** Le pilote pousse ici l'état du moteur (instruction, distances, arrivée). */
    private fun pushGuidanceUpdate(update: GuidanceUpdate) {
        scope.launch { session.onGuidanceUpdate(update) }
    }

    /** Dernière position connue (GPS puis réseau), `null` sans permission ni fix. */
    private fun lastKnownOrigin(): RoutePoint? {
        try {
            if (!hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
                !hasPerm(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                return null
            }
            val locations = context.getSystemService(LocationManager::class.java) ?: return null
            val fix = runCatching {
                locations.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }.getOrNull()
                ?: runCatching {
                    locations.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }.getOrNull()
            fix ?: return null
            if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) return null
            return RoutePoint(fix.latitude, fix.longitude)
        } catch (_: SecurityException) {
            return null
        }
    }
}
