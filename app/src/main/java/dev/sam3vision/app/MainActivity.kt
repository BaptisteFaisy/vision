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
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.app.glasses.bench.BenchEntryPoint
import dev.sam3vision.app.glasses.bench.EXTRA_ANALYZE
import dev.sam3vision.app.glasses.bench.EXTRA_MOCK
import dev.sam3vision.app.glasses.bench.SpikeLaunch
import dev.sam3vision.app.ihear.IhearViewModel
import dev.sam3vision.app.locked.LockedMicBridge

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
 * La racine n'est pas le ComposeView mais [IhearRootLayout] : elle voit chaque geste
 * de la surface de l'app — appui long (micro maintenu, transcription Muse), tap
 * simple (vidéo), double tap (photo), balayages horizontal (page) et vertical
 * (sélection de bulle, avec haptique dédiée). Les gestes ne s'appliquent que quand
 * les panneaux iSee sont à l'écran.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: AppViewModel
    private lateinit var ihear: IhearViewModel
    private lateinit var glasses: GlassesController

    /** Racine des gestes : annulée si l'app passe en arrière-plan en pleine tenue. */
    private lateinit var root: IhearRootLayout

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glasses = GlassesController(applicationContext).also {
            it.bind(this)
            it.initialize()
        }
        viewModel = ViewModelProvider(this)[AppViewModel::class.java]
        ihear = ViewModelProvider(this)[IhearViewModel::class.java]
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
     * Deux entrées par `Intent` : les callbacks de l'app Meta AI (enregistrement,
     * invocation vocale) vont au SDK ; `--ez mock` / `--ez analyze` ouvrent le banc
     * d'essai. Le banc n'étant plus une `Activity`, c'est la seule façon de le
     * lancer depuis adb (voir `tools/phone-lunettes.sh`).
     */
    private fun routeIntent(intent: Intent) {
        glasses.handleIntent(intent)
        if (intent.hasExtra(EXTRA_MOCK) || intent.hasExtra(EXTRA_ANALYZE)) {
            BenchEntryPoint.request(
                SpikeLaunch.fromExtras(
                    mock = intent.getBooleanExtra(EXTRA_MOCK, false).takeIf { intent.hasExtra(EXTRA_MOCK) },
                    analyze = intent.getBooleanExtra(EXTRA_ANALYZE, false).takeIf { intent.hasExtra(EXTRA_ANALYZE) },
                ),
            )
        }
    }

    /**
     * Les deux bandeaux système (heure/batterie en haut, 3 boutons en bas) posés sur la
     * page noir d'iSee. Les barres sont **transparentes** et la fenêtre passe sous elles
     * (edge-to-edge) : le chat descend jusque sous les 3 boutons, où il est masqué en
     * noir par le chat lui-même (`NavBarScrim`). Le thème de l'app est clair
     * (`Theme.Material.Light`), d'où les icônes système en clair et le contraste désactivé.
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

    private fun buildRoot() = IhearRootLayout(this).also { root = it }.apply {
        gesturesEnabled = { ihear.active }
        onHoldStart = ihear::onHoldStart
        onHoldEnd = ihear::onHoldEnd
        onHoldCancel = ihear::onHoldCancel
        onSingleTap = ihear::onSingleTap
        onDoubleTap = ihear::onDoubleTap
        onVerticalDragStart = ihear::onVerticalDragStart
        onVerticalDragMove = { downward -> ihear.onVerticalDragMove(downward) }
        onVerticalSwipeEnd = { committed, downward -> ihear.onVerticalSwipeEnd(committed, downward) }
        addView(
            ComposeView(this@MainActivity).apply { setContent { SamRoot(viewModel, ihear, glasses) } },
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
        if (!isChangingConfigurations) viewModel.releaseGpu()
        ihear.onBackground()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        ihear.onForeground()
        // App ouverte (ou de retour au premier plan) : on réveille la GPU Modal
        // (`/health` silencieux) pour qu'iSee réponde sans cold start.
        viewModel.warmGpu()
        // Micro mains libres : armé **ici**, seul moment où Android autorise un service de
        // premier plan à démarrer (app visible). Il survit ensuite à l'écran verrouillé.
        LockedMicBridge.arm(this)
    }

    /**
     * Split-screen : l'app n'est plus en plein écran, on éteint la GPU comme à
     * `onPause`. Au retour en plein écran, `onResume` (ou la première frame du
     * Direct / de la sonde) la réveillera.
     */
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode) viewModel.releaseGpu()
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
        if (!isChangingConfigurations) ihear.onAppClosed()
        if (isFinishing) glasses.close()
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
     * section Suivi parent) : notification, puis localisation d'arrière-plan,
     * une boîte de dialogue par appui (la demande groupée est ignorée).
     */
    fun ensureSharePermissions() {
        val granted = { permission: String ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
}

/** Thème + navigation : une seule racine Compose sous [IhearRootLayout]. */
@Composable
private fun SamRoot(viewModel: AppViewModel, ihear: IhearViewModel, glasses: GlassesController) {
    MaterialTheme(colorScheme = SamColorScheme) {
        Surface(color = AppColors.background) {
            // Au premier lancement : les trois dialogues système s'enchaînent directement,
            // sans question supplémentaire de l'app.
            IseePermissionRequest(viewModel)
            AppNav(viewModel = viewModel, ihear = ihear, glasses = glasses)
        }
    }
}
