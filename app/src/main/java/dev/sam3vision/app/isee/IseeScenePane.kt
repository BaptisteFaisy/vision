package dev.sam3vision.app.isee

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sam3vision.core.iseedesign.IseePortrait
import dev.sam3vision.core.iseedesign.IseeScene
import dev.sam3vision.core.iseedesign.PortraitGeometry
import dev.sam3vision.core.iseedesign.portraitGeometry
import dev.sam3vision.core.iseedesign.voiceEnvelope

/**
 * L'écran iSee au design « Portrait » : fond blanc, une seule boule de verre au liquide marbré
 * (plus de satellites, plus de pixels), et seulement la phrase en cours, en gros, sous la boule.
 * Quand la caméra tourne (vidéo ou photo), la rue occupe le haut de l'écran et se fond dans le
 * blanc ; la boule rétrécit et se pose sur la jointure. Le user parle : liquide bleu, mots qui
 * s'allument en bleu puis passent au noir. iSee parle : liquide ambre, couronne ambre autour de
 * la boule, mot en cours en ambre. Toute l'interaction reste aux gestes de `IseeRootLayout`.
 *
 * En-tête et pastille dans `IseeSceneHeader.kt`, règles de couches dans `IseeSceneLayers.kt`.
 */
@Composable
fun IseeScenePane(state: IseeUiState, viewModel: IseeViewModel, modifier: Modifier = Modifier) {
    val glue = rememberCameraGlue(viewModel)
    val probe by viewModel.probe.state.collectAsState()
    val entry = selectedEntry(state)
    val view = iseeSceneView(state, entry)
    BoxWithConstraints(modifier.fillMaxSize().background(Color.White)) {
        PortraitScene(state, viewModel, glue, probe, view, maxWidth, maxHeight)
    }
}

/** La scène complète, posée dans un BoxWithConstraints déjà dimensionné. */
@Composable
private fun BoxWithConstraintsScope.PortraitScene(
    state: IseeUiState,
    viewModel: IseeViewModel,
    glue: CameraGlue,
    probe: LiveProbeState,
    view: IseeSceneView,
    width: Dp,
    height: Dp,
) {
    val captureLive = state.recordingVideo || state.photoPath != null
    val layers = iseeSceneLayers(bubbleCount(state.entries.size), dissolving = false, captureLive = captureLive)
    val open = rememberCameraOpen(layers.cameraCard)
    val density = LocalDensity.current
    val geo = with(density) { portraitGeometry(width.toPx(), height.toPx(), open) }
    val time = rememberSceneTime()
    PortraitCamera(state, glue, probe, viewModel, open, height)
    OrbLayer(view.scene, geo, time, open)
    Header(view.scene, onPicture = open > 0.5f, modifier = Modifier.fillMaxWidth())
    with(density) { SpokenLine(view, state, (geo.textTop).toDp(), compact = open > 0.5f) }
    BottomScrim()
}

/** Le temps de la scène en secondes, pour le marbre et la voix (boucle de 10 min). */
@Composable
private fun rememberSceneTime(): Float {
    val t = rememberInfiniteTransition(label = "isee-time")
    return t.animateFloat(0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing)), label = "t").value
}

/** La boule, et la couronne de barres ambre quand iSee parle. */
@Composable
private fun OrbLayer(scene: IseeScene, geo: PortraitGeometry, time: Float, open: Float) {
    val look = orbLookOf(scene, time, open)
    val paint = rememberOrbPainter()
    val breathe = 1f + 0.012f * kotlin.math.sin(time * 1.3f) + 0.03f * look.you * look.amp
    val lift = geo.orbR * 0.02f * kotlin.math.sin(time * 0.9f) * (1f - open)
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(geo.orbX, geo.orbY + lift)
        val r = geo.orbR * breathe
        drawOrbShadow(c, r, 1f - open)
        paint(this, c, r, look)
        if (look.see > 0.01f) drawVoiceCrown(c, r, time, look.see, look.amp)
    }
}

/** Ce que montre la boule selon la scène : bleu à l'écoute, ambre quand iSee parle. */
internal fun orbLookOf(scene: IseeScene, time: Float, open: Float): OrbLook {
    val you = if (scene == IseeScene.LISTENING) 1f else 0f
    val see = if (scene == IseeScene.VOICE) 1f else 0f
    val amp = if (you + see > 0f) voiceEnvelope(time, if (see > 0f) 3 else 1) else 0f
    val level = 0.4f + 0.08f * kotlin.math.sin(time * 0.5f) + 0.08f * see - 0.06f * open
    return OrbLook(time = time, you = you, see = see, amp = amp, level = level)
}

/** Ombre de contact sous la boule qui flotte, plus une légère caustique ambre. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrbShadow(c: Offset, r: Float, k: Float) {
    if (k <= 0.01f) return
    val center = Offset(c.x, c.y + r * 1.17f)
    drawOval(
        brush = Brush.radialGradient(listOf(Color(0x290B1220), Color.Transparent), center, r * 0.85f),
        topLeft = Offset(center.x - r * 0.85f, center.y - r * 0.09f),
        size = androidx.compose.ui.geometry.Size(r * 1.7f, r * 0.18f),
        alpha = k,
    )
}

/** La couronne de 120 barres fines qui monte du verre avec la voix d'iSee. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVoiceCrown(c: Offset, r: Float, t: Float, k: Float, amp: Float) {
    val n = 120
    val see = Color(IseePortrait.SEE)
    for (i in 0 until n) {
        val a = i / n.toFloat() * 2f * Math.PI.toFloat()
        val e = 0.5f + 0.28f * kotlin.math.sin(a * 3 + t * 2.1f) + 0.18f * kotlin.math.sin(a * 7 - t * 3.3f + 1.3f) +
            0.12f * kotlin.math.sin(a * 13 + t * 5.2f)
        val len = r * (0.02f + 0.2f * amp * e * e) * k
        val r0 = r * 1.07f
        val dx = kotlin.math.cos(a)
        val dy = kotlin.math.sin(a)
        drawLine(
            color = see.copy(alpha = (k * (0.3f + 0.6f * e)).coerceIn(0f, 1f)),
            start = Offset(c.x + dx * r0, c.y + dy * r0),
            end = Offset(c.x + dx * (r0 + len), c.y + dy * (r0 + len)),
            strokeWidth = (r * 0.011f).coerceAtLeast(1f),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** Bandeau blanc derrière les boutons système du bas. */
@Composable
private fun BoxWithConstraintsScope.BottomScrim() {
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 0.dp).background(Color.White).padding(bottom = navInsetDp()))
}

/** Le crochet de navigation (raccourci : l'inset de navigation du téléphone). */
@Composable
internal fun navInsetDp(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
