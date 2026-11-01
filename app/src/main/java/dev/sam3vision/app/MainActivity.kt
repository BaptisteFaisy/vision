package dev.sam3vision.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import dev.sam3vision.app.glasses.EXTRA_GLASSES_MOCK_CONNECT
import dev.sam3vision.app.glasses.EXTRA_GLASSES_TALK
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.app.glasses.GlassesSessionLifecycle
import dev.sam3vision.app.glasses.bench.BenchEntryPoint
import dev.sam3vision.app.glasses.bench.EXTRA_ANALYZE
import dev.sam3vision.app.glasses.bench.EXTRA_MOCK
import dev.sam3vision.app.glasses.bench.SpikeLaunch
import dev.sam3vision.app.glasses.debugMockConnect
import dev.sam3vision.app.glasses.refresh
import dev.sam3vision.app.isee.IseeViewModel
import dev.sam3vision.app.locked.LockedMicBridge
import dev.sam3vision.app.perms.canAskBackground

private val SamColorScheme = darkColorScheme(
    primary = AppColors.primary,
    onPrimary = Color.White,
    secondary = AppColors.success,
    background = AppColors.background,
    surface = AppColors.surface,
    onBackground = AppColors.text,
    onSurface = AppColors.text,
    error = AppColors.danger,
    onError = Color.White,
)

/**
 * Activité unique : Compose + navigation par état, thème sombre pour les écrans
 * d'origine, iSee en page d'accueil, lunettes Ray-Ban Meta.
 *
 * La racine n'est pas le ComposeView mais [IseeRootLayout] : elle voit chaque geste
 * de la surface de l'app — appui long (micro maintenu, transcription Muse, photo
 * filmée jointe), tap simple (vidéo), balayage horizontal (reconnu, sans effet).
 * Double tap et balayage vertical supprimés. Les gestes ne s'appliquent que quand
 * les panneaux iSee sont à l'écran.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: AppViewModel
    private lateinit var isee: IseeViewModel
    private lateinit var glasses: GlassesController

    /** Racine des gestes : annulée si l'app passe en arrière-plan en pleine tenue. */
    private lateinit var root: IseeRootLayout

    /** Permission demandée au premier lancement ; un refus est annoncé à la voix. */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) viewModel.onMicDenied()
    }

    /** Géolocalisation demandée au premier lancement (suivi parent) ; optionnelle. */
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    /** Notification (Android 13+) demandée par le bouton « Partager » (Réglages). */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    /**
     * Localisation d'arrière-plan du suivi permanent, demandée seule : Android
     * ignore silencieusement une demande groupée avec d'autres permissions,
     * passée par le bouton « Partager » (Réglages).
     */
    private val backgroundLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    /** Dernière permission demandée : le contrat du SDK ne rend que le verdict. */
    private var pendingGlassesPermission = Permission.CAMERA

    /**
     * Autorisation caméra/micro des lunettes : le SDK n'expose pas de `requestPermission`, mais
     * ce contrat, qui ouvre la page de Meta AI. Déclaré avant `onCreate`, comme les autres.
     */
    private val glassesPermission =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            glasses.onPermissionResult(pendingGlassesPermission, result)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glasses = GlassesController(
            applicationContext,
            // Lus paresseusement à chaque frame : `viewModel` est prêt bien avant.
            frameSettings = { viewModel.state.value.settings },
            permissionRequester = { permission ->
                pendingGlassesPermission = permission
                glassesPermission.launch(permission)
            },
        ).also {
            it.bind(this)
            it.initialize()
        }
        viewModel = ViewModelProvider(this)[AppViewModel::class.java]
        isee = ViewModelProvider(this)[IseeViewModel::class.java]
        applyDarkSystemBars()
        setContentView(buildRoot())
        requestMicIfNeeded()
        requestLocationIfNeeded()
        routeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    /**
     * Cinq entrées par `Intent` : les callbacks de l'app Meta AI (enregistrement,
     * invocation vocale) vont au SDK ; `--ez mock` / `--ez analyze` ouvrent le banc
     * d'essai ; `--es route <nom>` ouvre l'écran nommé ; `--es destination <texte>`
     * et `--es voice <texte>` (debug seul) démarrent un guidage ou injectent un
     * transcript du direct. Ni le banc ni les écrans n'étant des `Activity`,
     * c'est la seule façon de les lancer depuis adb.
     */
    private fun routeIntent(intent: Intent) {
        glasses.handleIntent(intent)
        // Recette du direct lunettes : `--ez glasses_mock_connect true` simule la paire et
        // connecte, sans passer par les Réglages. Sans l'extra, rien ne change.
        if (intent.getBooleanExtra(EXTRA_GLASSES_MOCK_CONNECT, false)) {
            glasses.debugMockConnect()
        }
        // Recette mains-libres (debug seul) : `--es glasses_talk "..."` injecte
        // une question comme si le micro des lunettes l'avait entendue.
        if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_GLASSES_TALK)?.let { glasses.debugTalkTranscript(it) }
        }
        if (intent.hasExtra(EXTRA_MOCK) || intent.hasExtra(EXTRA_ANALYZE)) {
            BenchEntryPoint.request(
                SpikeLaunch.fromExtras(
                    mock = intent.getBooleanExtra(EXTRA_MOCK, false).takeIf { intent.hasExtra(EXTRA_MOCK) },
                    analyze = intent.getBooleanExtra(EXTRA_ANALYZE, false).takeIf { intent.hasExtra(EXTRA_ANALYZE) },
                ),
            )
        }
        // Recette adb générique : `--es route <nom>` ouvre l'écran nommé.
        intent.getStringExtra(EXTRA_ROUTE)?.let { RouteRequest.push(it) }
        // Recette guidage (debug seul) : `--es destination <texte>` démarre un
        // guidage vers la destination, sans passer par la dictée vocale.
        // Recette voix (debug seul) : `--es voice <texte>` injecte un transcript
        // du direct (ordres navigation, traversée), sans passer par la STT.
        if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_DESTINATION)?.let { isee.debugNavTo(it) }
            intent.getStringExtra(EXTRA_VOICE)?.let { NavOrderRequest.push(it) }
        }
    }

    /**
     * Les deux bandeaux système (heure/batterie en haut, 3 boutons en bas) posés sur la
     * page noir d'iSee. Les barres sont **transparentes** et la fenêtre passe sous elles
     * (edge-to-edge) : le chat descend jusque sous les 3 boutons, où il est masqué en
     * noir par le chat lui-même (`NavBarScrim`). Le thème de l'app est sombre
     * (`Theme.Sam3Vision`), d'où les icônes système en clair et le contraste désactivé.
     */
    private fun applyDarkSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun buildRoot() = IseeRootLayout(this).also { root = it }.apply {
        gesturesEnabled = { isee.active }
        onHoldStart = isee::onHoldStart
        onHoldEnd = isee::onHoldEnd
        onHoldCancel = isee::onHoldCancel
        onSingleTap = isee::onSingleTap
        pictoZone = { isee.picto.chipZone() }
        crossingZone = { isee.crossingChip.zone() }
        navZones = { isee.navButtons.zones() }
        addView(
            ComposeView(this@MainActivity).apply { setContent { SamRoot(viewModel, isee, glasses) } },
            MATCH_PARENT,
            MATCH_PARENT,
        )
    }

    /** Option C : minuteur d'inactivité démarré / annulé autour de l'arrière-plan. */
    override fun onPause() {
        root.cancelGesture()
        // L'app n'est plus visible (bouton home, changement d'app, écran éteint) : on coupe
        // le GPU ici et pas à onStop — sur ce téléphone, home ne déclenche pas toujours onStop
        // et un minuteur ne servirait à rien, Android gèle le processus en arrière-plan.
        // Session lunettes-iSee en cours : la carte reste allumée, la veille ne coupe rien.
        if (!isChangingConfigurations && !glassesKeepAlive()) viewModel.releaseGpu()
        isee.onBackground()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        isee.onForeground()
        // Pas de réveil GPU à l'ouverture : la carte ne démarre que quand ça filme,
        // réveillée par GpuWake à la liaison caméra (la 1re frame reste le signal de prêt).
        // Cycle contrat user : quitter (onPause → /release, ~0 $) puis rouvrir → réveil auto.
        // Micro mains libres : armé **ici**, seul moment où Android autorise un service de
        // premier plan à démarrer (app visible). Il survit ensuite à l'écran verrouillé.
        LockedMicBridge.arm(this)
        // Session lunettes : un démarrage du maintien refusé en fond (Android 12+)
        // se rejoue ici, au premier plan.
        if (glassesKeepAlive()) GlassesSessionLifecycle.startBackground(this)
        // Retour de l'app Meta AI : le flux d'enregistrement ne réémet pas
        // toujours, on relit l'état pour que le bloc Lunettes suive.
        glasses.refresh()
    }

    /** Session lunettes-iSee en cours : la veille ne coupe ni la session ni la carte. */
    private fun glassesKeepAlive(): Boolean =
        GlassesSessionLifecycle.wantsBackground(glasses.state.value.phase)

    /**
     * Split-screen : l'app n'est plus en plein écran, on éteint la GPU comme à
     * `onPause`. Au retour en plein écran, la première frame du Direct / de la
     * sonde la réveillera.
     */
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode && !glassesKeepAlive()) viewModel.releaseGpu()
    }

    /**
     * Option A : purge totale de la table Room et du cache à la fermeture. Une rotation
     * n'est pas une fermeture (`isChangingConfigurations`) ; le balayage depuis les apps
     * récentes en est une, même sans `isFinishing`.
     */
    override fun onDestroy() {
        // App quittée pour de bon (pas une simple rotation) : on éteint le GPU, sinon la
        // carte A100 reste facturée jusqu'à sa fenêtre de réduintage.
        if (isFinishing) viewModel.releaseGpu()
        if (!isChangingConfigurations) isee.onAppClosed()
        // Rotation comprise : sans ça, l'ancien contrôleur (et sa session SDK) survivait
        // en fantôme et la session suivante butait dessus (`SESSION_ALREADY_EXISTS`,
        // `UNEXPECTED_ERROR`). La session tombe à la rotation, puis se rouvre proprement.
        if (isFinishing || isChangingConfigurations) glasses.close()
        // App fermée pour de bon : le service du micro verrouillé n'a plus de raison de vivre.
        if (isFinishing) LockedMicBridge.disarm(this)
        super.onDestroy()
    }

    private fun requestMicIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestLocationIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine || !coarse) {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    /**
     * Demandes manquantes pour le suivi permanent (bouton « Partager » de la
     * section Suivi parent) : notification, puis localisation d'avant-plan,
     * puis d'arrière-plan — une boîte de dialogue par appui, et l'arrière-plan
     * seulement si l'avant-plan est déjà accordé (sinon Android l'ignore).
     */
    fun ensureSharePermissions() {
        val granted = { permission: String ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Sans position d'avant-plan, le service démarre mais n'envoie jamais rien
        // (pas de fix GPS) : on la redemande ici, même refusée au premier lancement.
        val fg = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fg) {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
        if (canAskBackground(fg) && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
}

/** Thème + navigation : une seule racine Compose sous [IseeRootLayout]. */
@Composable
private fun SamRoot(viewModel: AppViewModel, isee: IseeViewModel, glasses: GlassesController) {
    MaterialTheme(colorScheme = SamColorScheme) {
        Surface(color = AppColors.background) {
            // Au premier lancement : les trois dialogues système s'enchaînent directement,
            // sans question supplémentaire de l'app.
            IseePermissionRequest(viewModel)
            AppNav(viewModel = viewModel, isee = isee, glasses = glasses)
        }
    }
}
