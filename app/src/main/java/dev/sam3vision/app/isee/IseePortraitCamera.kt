package dev.sam3vision.app.isee

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sam3vision.app.FirstPixelWatcher
import dev.sam3vision.core.iseedesign.IseeScene
import dev.sam3vision.core.iseedesign.focusPull
import dev.sam3vision.core.picto.PictoPlaceMode

/**
 * La caméra du design « Portrait » : la rue est un bloc comme la carte (mêmes coins
 * 16 dp, même ombre, mêmes marges latérales 16 dp), sous le bandeau système
 * blanc, 60 % de l'écran (70 % en guidage), sans trait de séparation (le bord
 * du bloc suffit) ; le voile haut est conservé pour la pastille. À l'ouverture
 * la rue arrive floue et un peu agrandie, puis devient nette (mise au point).
 * L'aperçu porte toujours les masques SAM et les distances.
 *
 * Lunettes connectées ([showGlasses]) : la même rue montre le direct des lunettes à la
 * place de la caméra du téléphone — avec les mêmes masques, pastilles, flèche et debug,
 * car la sonde analyse alors la rue des lunettes (pont [GlassesProbeWiring]). Entre le
 * démarrage du flux et la première image, la rue existe en fantôme ([ghost], alpha nul) :
 * sa surface reçoit les frames, sans elle le direct ne démarrerait jamais.
 */
@Composable
internal fun BoxWithConstraintsScope.PortraitCamera(
    state: IseeUiState,
    glue: CameraGlue,
    probe: LiveProbe,
    viewModel: IseeViewModel,
    open: Float,
    height: Dp,
    showGlasses: Boolean,
    ghost: Boolean, scene: IseeScene,
) {
    if (open <= 0.001f && !ghost && state.glassesPhoto == null) return
    val focus = focusPull(open)
    // Flou nul = netteté : `blur(0.dp)` est l'identité mais garde une passe
    // hors-écran plein cadre à chaque frame — on ne la pose que pendant la
    // mise au point (`focusPull` rend exactement 0 quand `open` vaut 1).
    val blur = if (focus.blur > 0f) Modifier.blur(focus.blur.dp) else Modifier
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = cameraBlockTop(statusBarTopDp()))
            .padding(horizontal = IseeBlockSide)
            .fillMaxWidth()
            .height(cameraBlockHeight(height, statusBarTopDp()))
            .shadow(6.dp, IseeBlockShape)
            .clip(IseeBlockShape)
            .background(Color(0xFF14161B))
            .graphicsLayer { alpha = open.coerceIn(0f, 1f) },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = focus.scale; scaleY = focus.scale }
                .then(blur),
        ) {
            StreetFeed(state, glue, probe, viewModel, showGlasses)
        }
        if (state.placeMode.pictoEnabled && state.recordingVideo && !showGlasses) {
            PictoChip(
                state.picto,
                Modifier.align(Alignment.BottomEnd),
                onPicto = { viewModel.picto.onPictoButton() },
                onBounds = viewModel.picto::setChipBounds,
            )
        }
        ProbeNoticeScoped(probe)
        HeaderShade(Modifier.fillMaxSize())
        ModeToggles(probe, viewModel, state.placeMode, scene, open)
    }
}

/**
 * Rangée haut-centre de la rue : lieu, Traversée, pastille d'état (demande
 * user 2026-11-04). La pastille n'y est que caméra franchement ouverte (même
 * seuil que l'en-tête : pas de doublon pendant l'ouverture). Écart resserré
 * et rangée décalée à gauche (demande user 2026-11-04) : LISTENING, le mot
 * le plus long de la pastille, garde sa ligne entière.
 */
@Composable
private fun BoxScope.ModeToggles(probe: LiveProbe, viewModel: IseeViewModel, mode: PictoPlaceMode, scene: IseeScene, open: Float) {
    // Chips chiffres : Traversée et lieu seuls, invisibles aux pixels.
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = 12.dp).offset(x = -8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlaceModeChip(
            mode,
            Modifier,
            onToggle = { viewModel.modeChips.togglePlace() },
            onBounds = viewModel.modeChips.place::setBounds,
        )
        CrossingChip(
            probe.collectRest(),
            Modifier,
            onToggle = { viewModel.modeChips.toggleCrossing() },
            onBounds = viewModel.modeChips.crossing::setBounds,
        )
        if (open > 0.5f) StatusChip(scene, onPicture = true)
    }
}

/**
 * La rue elle-même : la photo figée si elle est affichée, les lunettes si [showGlasses], sinon la caméra du téléphone. Les
 * deux sources portent les mêmes masques, distances, flèche et pastilles de pictos.
 */
@Composable
private fun BoxScope.StreetFeed(
    state: IseeUiState,
    glue: CameraGlue,
    probe: LiveProbe,
    viewModel: IseeViewModel,
    showGlasses: Boolean,
) {
    val photo = state.glassesPhoto
    if (photo != null) {
        GlassesPhotoStreet(photo, Modifier.fillMaxSize())
        DescribePhotoButton(viewModel.navButtons, viewModel::describeGlassesPhoto, Modifier.padding(bottom = 16.dp))
    } else if (showGlasses) {
        GlassesLive(Modifier.fillMaxSize())
        GlassesMasksScoped(probe)
        ProbeVideoRest(probe)
    } else {
        CameraLive(glue, probe, viewModel, Modifier.fillMaxSize())
        // Dehors, aucun picto affiché : ni cadre, ni carte « en grand ».
        if (state.placeMode.pictoEnabled) {
            PictoLayer(state.picto.hits, viewModel.picto::pngOf)
        }
    }
}

/** Masques des lunettes, scopés pixels : invisibles aux chiffres et à la voix. */
@Composable
private fun GlassesMasksScoped(probe: LiveProbe) {
    val pixels = probe.collectPixels()
    GlassesMaskLayer(pixels.masks, pixels.width, pixels.height)
    // Miroir du chemin téléphone (`IseeCameraWiring`) : sans ce guet, `noteDrawn`
    // n'est jamais appelé sur le chemin lunettes et la ligne `latency` (E2E) ne sort jamais.
    FirstPixelWatcher(pixels.pixelSeq, pixels.pixelSeq > 0) { probe.noteDrawn() }
}

/**
 * L'ombre douce du haut du bloc, pour la pastille : le bas du bloc a son
 * propre bord (ni nuage blanc, ni trait de séparation).
 */
@Composable
private fun HeaderShade(modifier: Modifier) {
    // Dégradé fixe : le reconstruire à chaque tick d'ouverture (un recompose
    // par frame pendant 0,8 s) alloue pour rien.
    val shade = remember {
        Brush.verticalGradient(0f to Color(0x5C0B1220), 0.22f to Color.Transparent, 1f to Color.Transparent)
    }
    Box(modifier.background(shade))
}

/**
 * Haut du bandeau système, pour caler le bloc caméra dessous : l'aperçu natif
 * recouvrirait sinon le bandeau blanc et ses icônes (constaté sur appareil).
 */
@Composable
private fun statusBarTopDp(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** L'ouverture de la caméra : 0 → 1 en 0,8 s quand elle s'allume, 1 → 0 en 0,7 s à la fin. */
@Composable
internal fun rememberCameraOpen(on: Boolean): Float {
    val open = remember { Animatable(0f) }
    LaunchedEffect(on) {
        open.animateTo(if (on) 1f else 0f, tween(if (on) 800 else 700, easing = FastOutSlowInEasing))
    }
    return open.value
}
