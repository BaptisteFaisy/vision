package dev.sam3vision.app.ihear

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.iseedesign.DISSOLVE_MS
import dev.sam3vision.core.iseedesign.HEADING_MAX_LINES
import dev.sam3vision.core.iseedesign.bubbleLookIndex
import dev.sam3vision.core.iseedesign.DissolvePlan
import dev.sam3vision.core.iseedesign.IseeScene
import dev.sam3vision.core.iseedesign.dissolvePlan
import dev.sam3vision.core.iseedesign.iSeeGeometry
import dev.sam3vision.core.iseedesign.textPages
import kotlinx.coroutines.delay

/**
 * L'écran iSee au design de la vidéo : fond dégradé clair, grande bulle de verre
 * entourée de satellites (une apparence par message), gros texte paginé sur
 * 3 lignes max, pill d'état en haut à droite, dots de pagination et pill
 * d'instructions en bas. À la sortie d'une capture, la carte caméra se dissolve
 * en damier pendant que sa gerbe de pixels plonge dans la bulle qui grandit
 * jusqu'au centre — le « monde » posé par les pixels reste collé à la réponse
 * tant qu'elle est sélectionnée.
 */
@Composable
fun IseeScenePane(state: IhearUiState, viewModel: IhearViewModel, modifier: Modifier = Modifier) {
    val glue = rememberCameraGlue(viewModel)
    val probe by viewModel.probe.state.collectAsState()
    val entry = selectedEntry(state)
    val view = iseeSceneView(state, entry)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to IseeSceneColors.bgTop,
                    0.87f to IseeSceneColors.bgLower,
                    1f to IseeSceneColors.bgBottom,
                ),
            ),
    ) {
        SceneScaffold(state, viewModel, glue, probe, entry, view, maxWidth, maxHeight)
    }
}

/** Tout le contenu du pane : bandeaux système, commande, scène, gros texte, dots
 * et pill d'instructions — posé dans un BoxWithConstraints déjà dimensionné. */
@Composable
private fun BoxWithConstraintsScope.SceneScaffold(
    state: IhearUiState,
    viewModel: IhearViewModel,
    glue: CameraGlue,
    probe: LiveProbeState,
    entry: ChatEntry?,
    view: IseeSceneView,
    width: Dp,
    height: Dp,
) {
    SystemBarScrims()
    Header(view.scene, Modifier.fillMaxWidth())

    // Grille vivante de la capture : échantillonnée ici, partagée carte/dissolution.
    val grid = rememberGridState(state, glue)

    // La dissolution et le monde qu'elle pose, pilotés par la scène.
    val (dissolve, world) = rememberDissolution(state, view.scene, grid, width, height)

    val total = bubbleCount(state.entries.size)
    val layers = iseeSceneLayers(view.scene, total, dissolving = dissolve.second != null)
    SceneArea(
        layers = layers,
        scene = view.scene,
        state = state,
        glue = glue,
        probe = probe,
        viewModel = viewModel,
        entry = entry,
        width = width,
        height = height,
        grid = grid,
        dissolve = dissolve,
        world = world,
    )
    HeadingArea(view, height)
    PageDotsLayer(layers, total, state.selected, height)
    InstructionPill(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = navInsetDp() + 10.dp),
    )
}

/** La grille de capture : rafraîchie en continu pendant l'enregistrement, une seule
 * fois pour une photo ; vide sinon. Élevée au niveau du pane pour que la dissolution
 * parte exactement de ce que la carte montrait. */
@Composable
private fun rememberGridState(state: IhearUiState, glue: CameraGlue): PixelGrid {
    var grid by remember { mutableStateOf(PixelGrid(IntArray(0), GRID_COLS, GRID_ROWS)) }
    LaunchedEffect(state.recordingVideo, state.photoPath) {
        when {
            state.recordingVideo -> while (true) {
                grid = samplePreviewGrid(glue.preview.bitmap)
                delay(SAMPLE_MS)
            }
            else -> grid = samplePhotoGrid(state.photoPath)
        }
    }
    return grid
}

/** L'état partagé de la dissolution : horloge, run en cours, monde posé. */
private class DissolveClock {
    val clock: Animatable<Float, AnimationVector1D> = Animatable(0f)
    var run by mutableStateOf<DissolveRun?>(null)
    var world by mutableStateOf<WorldMemory?>(null)
}

/** L'horloge partagée de la dissolution, et l'amorçage du run. */
@Composable
private fun rememberDissolution(
    state: IhearUiState,
    scene: IseeScene,
    grid: PixelGrid,
    width: Dp,
    height: Dp,
): Pair<Pair<Animatable<Float, AnimationVector1D>, DissolveRun?>, WorldMemory?> {
    val cv = remember { DissolveClock() }
    armDissolve(cv, state, scene, grid, width, height)
    tumClockIn(cv)
    // Le monde se lie au dernier message arrivé (la réponse), pas à la question.
    val newest = state.entries.lastOrNull()
    LaunchedEffect(newest?.id) {
        val w = cv.world
        if (w != null && w.boundId == null && newest != null) w.boundId = newest.id
    }
    return (cv.clock to cv.run) to cv.world
}

/** L'horloge ne court que pour le run courant ; à son terme, il s'envole et le
 * monde reste sur la bulle (accroché à la réponse qui sera arrivée au fil). */
@Composable
private fun tumClockIn(cv: DissolveClock) {
    LaunchedEffect(cv.run) {
        if (cv.run != null) {
            cv.clock.snapTo(0f)
            cv.clock.animateTo(1f, tween(DISSOLVE_MS, easing = LinearEasing))
            cv.run = null
        } else {
            cv.clock.snapTo(0f)
        }
    }
}

/** Une nouvelle capture réarme la transition ; la scène qui lâche VISION la lance. */
@Composable
private fun armDissolve(
    cv: DissolveClock,
    state: IhearUiState,
    scene: IseeScene,
    grid: PixelGrid,
    width: Dp,
    height: Dp,
) {
    var armed by remember { mutableStateOf(true) }
    val density = LocalDensity.current
    LaunchedEffect(state.recordingVideo, state.photoPath) {
        if (state.recordingVideo || state.photoPath != null) armed = true
    }
    LaunchedEffect(scene, grid) {
        when (scene) {
            IseeScene.VISION -> Unit
            IseeScene.THINKING, IseeScene.VOICE, IseeScene.ANSWERED -> {
                if (armed && cv.run == null && grid.colors.isNotEmpty()) {
                    armed = false
                    val geom = iSeeGeometry(with(density) { width.toPx() }, with(density) { height.toPx() })
                    val plan = dissolvePlan(geom)
                    cv.run = DissolveRun(grid, plan, geom)
                    cv.world = WorldMemory(grid, plan)
                }
            }
            else -> Unit
        }
    }
}

/** Les deux bandeaux système en blanc : l'heure/batterie en haut, les 3 boutons en bas. */
@Composable
private fun BoxWithConstraintsScope.SystemBarScrims() {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxWidth().height(top).background(Color.White))
    Box(
        Modifier.fillMaxWidth().height(bottom).background(Color.White)
            .align(Alignment.BottomCenter),
    )
}

/** Le crochet de navigation (raccourci : l'inset de navigation du téléphone). */
@Composable
internal fun navInsetDp(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** En-tête : wordmark « iSee » (point noir du i) et pill d'état à droite. */
@Composable
private fun Header(scene: IseeScene, modifier: Modifier = Modifier) {
    Row(
        modifier
            .statusBarsPadding()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("iSee", color = IseeSceneColors.wordmark, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        StatusPill(scene)
    }
}

/** Pill d'état : fond / texte / mots par scène, en majuscules. */
@Composable
private fun StatusPill(scene: IseeScene) {
    val (bg, fg, label) = pillOf(scene)
    Text(
        label,
        color = fg,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Couleur de la pill et mot selon la scène — les couleurs mesurées de la vidéo. */
private fun pillOf(scene: IseeScene): Triple<Color, Color, String> = when (scene) {
    IseeScene.VISION -> Triple(IseeSceneColors.pillActiveBg, Color.White, "VISION")
    IseeScene.LISTENING -> Triple(IseeSceneColors.pillListenBg, Color.White, "LISTENING")
    IseeScene.THINKING -> Triple(IseeSceneColors.pillActiveBg, Color.White, "THINKING")
    IseeScene.VOICE -> Triple(IseeSceneColors.pillVoiceBg, Color.White, "VOICE")
    IseeScene.ANSWERED -> Triple(IseeSceneColors.pillDoneBg, IseeSceneColors.pillDoneText, "ANSWERED")
    IseeScene.MEMORY -> Triple(IseeSceneColors.pillMemoryBg, Color.White, "MEMORY")
    IseeScene.READY -> Triple(IseeSceneColors.pillQuietBg, IseeSceneColors.pillQuietText, "READY")
}

/**
 * Ce que la scène montre en plus du décor fixe (bandeaux, en-tête, gros texte,
 * pill) : la carte caméra — la grande bulle en est le strict complément — et les
 * dots de pagination. Pur : même scène, même écran, testable sur JVM.
 */
internal data class IseeSceneLayers(val cameraCard: Boolean, val pageDots: Boolean) {
    /** La bulle occupe la scène chaque fois que la carte caméra ne le fait pas. */
    val bubble: Boolean get() = !cameraCard
}

/**
 * Les règles de visibilité du design : la carte caméra n'apparaît que dans la scène
 * VISION, et jamais pendant qu'elle se dissout (la carte gelée prend sa place) ; les
 * dots n'apparaissent qu'hors VISION et seulement s'il y a plusieurs bulles.
 */
internal fun iseeSceneLayers(scene: IseeScene, bubbles: Int, dissolving: Boolean): IseeSceneLayers {
    val cameraCard = scene == IseeScene.VISION && !dissolving
    return IseeSceneLayers(cameraCard = cameraCard, pageDots = scene != IseeScene.VISION && bubbles > 1)
}

/** La scène : la carte vivante (VISION), ou la bulle — et pendant la dissolution la
 * carte gelée sous la gerbe, toutes les deux posées au-dessus de la bulle. */
@Composable
private fun BoxWithConstraintsScope.SceneArea(
    layers: IseeSceneLayers,
    scene: IseeScene,
    state: IhearUiState,
    glue: CameraGlue,
    probe: LiveProbeState,
    viewModel: IhearViewModel,
    entry: ChatEntry?,
    width: Dp,
    height: Dp,
    grid: PixelGrid,
    dissolve: Pair<Animatable<Float, AnimationVector1D>, DissolveRun?>,
    world: WorldMemory?,
) {
    val (clock, run) = dissolve
    if (layers.bubble) BigBubble(state, entry, scene, world, dissolve)
    if (run != null) {
        FrozenCard(run)
        DissolveOverlay(run, clock, Modifier.fillMaxSize())
    }
    if (layers.cameraCard) VisionCard(state, glue, probe, viewModel, grid, width, height)
}

/** Grande bulle + satellites dans un plein écran : géométrie du cœur, morph du
 * dissolve, monde des pixels atterris, ticks rayonnants violets ou bleus. */
@Composable
private fun BoxWithConstraintsScope.BigBubble(
    state: IhearUiState,
    entry: ChatEntry?,
    scene: IseeScene,
    world: WorldMemory?,
    dissolve: Pair<Animatable<Float, AnimationVector1D>, DissolveRun?>,
) {
    val (clock, run) = dissolve
    val look = bubbleLookIndex(entry?.id ?: INTRO_BUBBLE_ID)
    val answered = scene == IseeScene.ANSWERED
    val tickColor = when (scene) {
        IseeScene.VOICE -> IseeSceneColors.pillVoiceBg
        IseeScene.LISTENING -> IseeSceneColors.pillListenBg
        else -> null
    }
    val tickPhase = rememberInfiniteTransition(label = "ticks").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "tick-phase",
    )
    Canvas(Modifier.fillMaxSize()) {
        bigBubbleBody(
            look = look,
            answered = answered,
            clock = clock.value,
            run = run,
            world = world,
            entry = entry,
            tickPhase = tickPhase.value,
            tickColor = tickColor,
        )
    }
}

/* ————— Gros texte paginé, dots, pill d'instructions ————— */

/** Label (YOU/ISEE) + gros texte : au plus 3 lignes à l'écran, le reste glisse. */
@Composable
private fun BoxWithConstraintsScope.HeadingArea(view: IseeSceneView, height: Dp) {
    var page by remember(view.heading) { mutableIntStateOf(0) }
    val pages = remember(view.heading) { textPages(view.heading) }
    LaunchedEffect(view.heading) {
        while (page < pages.size - 1) {
            delay(3800)
            page++
        }
    }
    val pageIndex = page.coerceIn(0, pages.size - 1)
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .offset(y = height * 0.615f)
            .padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        view.label?.let {
            Text(
                it,
                color = IseeSceneColors.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        AnimatedContent(
            targetState = pages[pageIndex],
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn(tween(300))) togetherWith
                    (slideOutVertically { -it / 2 } + fadeOut(tween(220)))
            },
            label = "heading-page",
        ) { pageText ->
            Text(
                pageText.joinToString("\n"),
                color = if (view.fromUser) IseeSceneColors.headingUser else IseeSceneColors.headingDark,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 46.sp,
                textAlign = TextAlign.Center,
                maxLines = HEADING_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(1f),
            )
        }
    }
}

/** Les dots de pagination, posés bas-droite au-dessus de la pill d'instructions. */
@Composable
private fun BoxWithConstraintsScope.PageDotsLayer(
    layers: IseeSceneLayers,
    total: Int,
    selected: Int,
    height: Dp,
) {
    if (!layers.pageDots) return
    PageDots(
        total = total,
        selected = selected,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 26.dp, bottom = height * 0.19f),
    )
}

/** Dots de pagination : l'actif plus gros et bleu, les autres plus petits et gris. */
@Composable
private fun PageDots(total: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val active = i == selected
            Box(
                Modifier
                    .size(if (active) 9.dp else 6.dp)
                    .background(
                        color = if (active) IseeSceneColors.dotActive else IseeSceneColors.dotInactive,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/** La pill d'instructions du bas : actions fortes sombres, verbes de service gris. */
@Composable
private fun InstructionPill(modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            val strong = SpanStyle(color = IseeSceneColors.instructStrong, fontWeight = FontWeight.Bold)
            val soft = SpanStyle(color = IseeSceneColors.instructGray)
            withStyle(strong) { append("Tap") }
            withStyle(soft) { append(" see") }
            withStyle(strong) { append("   Hold") }
            withStyle(soft) { append(" ask") }
            withStyle(strong) { append("   Swipe") }
            withStyle(soft) { append(" remember") }
        },
        color = IseeSceneColors.instructStrong,
        fontSize = 13.sp,
        modifier = Modifier
            .background(IseeSceneColors.instructBg, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}
