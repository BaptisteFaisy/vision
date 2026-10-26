package dev.sam3vision.app

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.app.ihear.IhearViewModel

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
        routeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    /** Callbacks de l'app Meta AI (enregistrement, invocation vocale) passés au SDK. */
    private fun routeIntent(intent: Intent) {
        glasses.handleIntent(intent)
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
        super.onDestroy()
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
