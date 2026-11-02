package dev.sam3vision.app.isee

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sam3vision.core.iseedesign.focusPull
import dev.sam3vision.core.iseedesign.pictureAlpha
import dev.sam3vision.core.picto.PictoPlaceMode

/**
 * La caméra du design « Portrait » : la rue occupe le haut, sa hauteur suit la parole et se fond dans
 * le blanc par le bas ; pas de cadre, pas de carte. À l'ouverture la rue arrive floue et un peu
 * agrandie, puis devient nette (mise au point). L'aperçu porte toujours les masques SAM et les
 * distances.
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
    ghost: Boolean,
) {
    if (open <= 0.001f && !ghost && state.glassesPhoto == null) return
    val focus = focusPull(open)
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .graphicsLayer { alpha = open.coerceIn(0f, 1f) },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = focus.scale; scaleY = focus.scale }
                .blur(focus.blur.dp),
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
        PictureFade(Modifier.fillMaxSize())
        ModeToggles(probe, viewModel, state.placeMode)
        // Dense = pas d'inset de barre d'état : posé en haut, le panneau passait sous
        // l'horloge et derrière le wordmark « iSee » (constaté sur l'appareil le
        // 2026-10-29). En bas à gauche, au-dessus du fondu, il ne recouvre plus rien :
        // la chip reste en bas à droite, le wordmark garde le haut.
        LiveDebugPanel(probe, Modifier.align(Alignment.BottomStart).padding(8.dp), dense = true)
    }
}

/**
 * Bascules manuelles Traversée et lieu (sans elles, l'ordre vocal seul les arme) :
 * ex-puce de l'ancienne carte, remontée en haut-centre après le fondu.
 */
@Composable
private fun BoxScope.ModeToggles(probe: LiveProbe, viewModel: IseeViewModel, mode: PictoPlaceMode) {
    // Chips chiffres : Traversée et lieu seuls, invisibles aux pixels.
    CrossingChip(
        probe.collectRest(),
        Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        onToggle = { viewModel.modeChips.toggleCrossing() },
        onBounds = viewModel.modeChips.crossing::setBounds,
    )
    PlaceModeChip(
        mode,
        Modifier.align(Alignment.TopCenter).padding(top = 52.dp),
        onToggle = { viewModel.modeChips.togglePlace() },
        onBounds = viewModel.modeChips.place::setBounds,
    )
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
}

/**
 * Le fondu vers le blanc : un dégradé posé sur le bas de la rue, échantillonné sur la courbe
 * de [pictureAlpha] (même courbe que la maquette), plus une ombre douce en haut pour l'en-tête.
 */
@Composable
private fun PictureFade(modifier: Modifier) {
    val stops = remember {
        val n = 12
        Array(n + 1) { i ->
            val y = i / n.toFloat()
            y to Color.White.copy(alpha = 1f - pictureAlpha(y, 1f))
        }
    }
    Box(modifier.background(Brush.verticalGradient(*stops)))
    Box(
        modifier.background(
            Brush.verticalGradient(0f to Color(0x5C0B1220), 0.22f to Color.Transparent, 1f to Color.Transparent),
        ),
    )
}



/** L'ouverture de la caméra : 0 → 1 en 0,8 s quand elle s'allume, 1 → 0 en 0,7 s à la fin. */
@Composable
internal fun rememberCameraOpen(on: Boolean): Float {
    val open = remember { Animatable(0f) }
    LaunchedEffect(on) {
        open.animateTo(if (on) 1f else 0f, tween(if (on) 800 else 700, easing = FastOutSlowInEasing))
    }
    return open.value
}
