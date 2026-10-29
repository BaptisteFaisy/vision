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
import dev.sam3vision.app.LiveDistanceLayer
import dev.sam3vision.core.iseedesign.focusPull
import dev.sam3vision.core.iseedesign.pictureAlpha

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
    probe: LiveProbeState,
    viewModel: IseeViewModel,
    open: Float,
    height: Dp,
    showGlasses: Boolean,
    ghost: Boolean,
) {
    if (open <= 0.001f && !ghost) return
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
        if (state.recordingVideo && !showGlasses) {
            PictoChip(
                state.picto,
                Modifier.align(Alignment.BottomEnd),
                onPicto = { viewModel.picto.onPictoButton() },
                onBounds = viewModel.picto::setChipBounds,
            )
        }
        ProbeNoticeCard(probe)
        PictureFade(Modifier.fillMaxSize())
        // Dense = pas d'inset de barre d'état : posé en haut, le panneau passait sous
        // l'horloge et derrière le wordmark « iSee » (constaté sur l'appareil le
        // 2026-10-29). En bas à gauche, au-dessus du fondu, il ne recouvre plus rien :
        // la chip reste en bas à droite, le wordmark garde le haut.
        LiveDebugPanel(probe, Modifier.align(Alignment.BottomStart).padding(8.dp), dense = true)
    }
}

/**
 * La rue elle-même : les lunettes si [showGlasses], sinon la caméra du téléphone. Les
 * deux sources portent les mêmes masques, distances, flèche et pastilles de pictos.
 */
@Composable
private fun BoxScope.StreetFeed(
    state: IseeUiState,
    glue: CameraGlue,
    probe: LiveProbeState,
    viewModel: IseeViewModel,
    showGlasses: Boolean,
) {
    if (showGlasses) {
        GlassesLive(Modifier.fillMaxSize())
        GlassesMaskLayer(probe.masks, probe.width, probe.height)
        LiveDistanceLayer(probe.distanceLayer, topInset = 0.dp, modifier = Modifier.fillMaxSize())
        SteeringLayer(probe.steering)
    } else {
        CameraLive(glue, probe, viewModel, Modifier.fillMaxSize())
        PictoLayer(state.picto.hits, viewModel.picto::pngOf)
    }
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
