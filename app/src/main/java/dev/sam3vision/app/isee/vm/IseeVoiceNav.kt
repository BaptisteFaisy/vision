package dev.sam3vision.app.isee.vm

import android.Manifest
import android.app.Application
import android.location.LocationManager
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
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.BanGeocoder
import dev.sam3vision.core.nav.GeocodeChain
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.voicenav.VoiceDestinationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
        )
    }
    private val driver: VoiceNavEngineDriver by driverLazy

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
            // Pas de départ surprise : la destination dictée ouvre l'écran de
            // confirmation (« Destination : X », bouton Démarrer) — le guidage
            // ne démarre qu'après un objectif explicite (autoStartOnVoice=false).
        )
    }

    /** État de la navigation vocale (repos, confirmation, guidage). */
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
