package dev.sam3vision.app.isee.preview

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.isee.ChatEntry
import dev.sam3vision.app.isee.DissolveOverlay
import dev.sam3vision.app.isee.DissolveRun
import dev.sam3vision.app.isee.FrozenCard
import dev.sam3vision.app.isee.GRID_COLS
import dev.sam3vision.app.isee.GRID_ROWS
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.isee.INTRO_BUBBLE_ID
import dev.sam3vision.app.isee.IseeSceneColors
import dev.sam3vision.app.isee.IseeSceneView
import dev.sam3vision.app.isee.PixelGrid
import dev.sam3vision.app.isee.bigBubbleBody
import dev.sam3vision.app.isee.drawIseeBubble
import dev.sam3vision.app.isee.iseeSceneView
import dev.sam3vision.app.isee.selectedEntry
import dev.sam3vision.app.isee.specOf
import dev.sam3vision.core.iseedesign.BubbleLook
import dev.sam3vision.core.iseedesign.HEADING_MAX_LINES
import dev.sam3vision.core.iseedesign.IseeScene
import dev.sam3vision.core.iseedesign.bubbleLookIndex
import dev.sam3vision.core.iseedesign.dissolvePlan
import dev.sam3vision.core.iseedesign.iSeeGeometry
import dev.sam3vision.core.iseedesign.sampleGrid
import dev.sam3vision.core.iseedesign.textPages

/**
 * Le banc de previews de l'écran iSee : les états qui n'étaient relisibles qu'au
 * téléphone (intro, message du user, réponse, mémoire, écoute, carte caméra,
 * dissolution) sont désormais rendus **hors appareil**, dans Android Studio.
 *
 * Ce que ces previews dessinent :
 * - le fond dégradé et la grande bulle avec ses six satellites, par le **vrai**
 *   code de rendu (`bigBubbleBody`, `drawIseeBubble`) ;
 * - la carte caméra et la dissolution, par les **vrais** composables du pane
 *   (`FrozenCard`, `DissolveOverlay`) ;
 * - le libellé et le gros texte, par la même pagination que l'écran
 *   (`textPages`), dans la couleur de la scène (bleu pour le user).
 *
 * Ce qu'elles ne dessinent pas, et pourquoi : la pill d'état, la pill
 * d'instructions et les dots de pagination sont **privés** à `IseeScenePane.kt`
 * (et la caméra a besoin d'un `IseeViewModel` + d'une `CameraGlue`). Ce sont donc
 * des gabarits de preview, pas une duplication du rendu de production ; le chrome
 * complet est listé comme suite dans la PR.
 */

/** Format des previews de scène : un téléphone 360 × 740 dp. */
private const val PREVIEW_W = 360
private const val PREVIEW_H = 740

/** Colonnes de la planche des 16 apparences. */
private const val LOOK_COLS = 4

/** Les questions/réponses d'exemple des previews (aucune donnée réelle). */
private const val PREVIEW_QUESTION = "What is in front of me right now?"
private const val PREVIEW_ANSWER =
    "A wooden table with a cup of coffee and a book, and a window with daylight."

/**
 * Les états d'exemple des previews : chacun est un [IseeUiState] réel, lu par
 * les vraies fonctions de scène (`selectedEntry`, `iseeSceneView`) — la preview
 * montre donc exactement ce que l'écran montrerait.
 */
internal object IseePreviewStates {
    private fun user(id: Long = 101L) = ChatEntry.UserBubble(id, PREVIEW_QUESTION, null)
    private fun ai(id: Long = 101L) = ChatEntry.AiBubble(id, PREVIEW_ANSWER, null)

    /** Ouverture de l'app : aucune bulle d'historique, l'intro seule. */
    val intro = IseeUiState()

    /** Le user vient de parler : gros texte bleu, bulle au repos. */
    val userMessage = IseeUiState(entries = listOf(user()), selected = 1)

    /** Réponse complète : la bulle porte son anneau de lumière. */
    val answered = IseeUiState(entries = listOf(user(), ai()), selected = 2)

    /** Message passé relu : plus de bulle active, on remonte dans le fil. */
    val memory = IseeUiState(entries = listOf(user(), ai()), selected = 1)

    /** Écoute en cours : les ticks bleus entourent la bulle. */
    val listening = IseeUiState(entries = listOf(user(), ai()), selected = 2, holding = true)

    /** Voix en sortie : les ticks violets remplacent les bleus. */
    val voice = IseeUiState(entries = listOf(user(), ai()), selected = 2, speakingEntryId = 101L)

    /** Aucun message du tout (ni intro) : la scène est vide, READY, sans texte. */
    val blank = IseeUiState(selected = 9)

    /** Les variantes rendues par le preview paramétré. */
    val variants: List<IseeUiState> = listOf(intro, userMessage, answered, memory, listening, voice, blank)
}

/** Le provider des previews paramétrées : une variante par état d'exemple. */
internal class IseePreviewStateProvider : PreviewParameterProvider<IseeUiState> {
    override val values: Sequence<IseeUiState> = IseePreviewStates.variants.asSequence()
}

/**
 * Les fixtures des previews : une image **synthétique** (dégradé 64×40, aucune
 * photo, aucun fichier), échantillonnée par la vraie routine du cœur
 * (`sampleGrid`) — donc la grille du pane (26×16) est celle que produit une capture.
 */
internal object IseePreviewFixtures {
    const val IMAGE_W = 64
    const val IMAGE_H = 40

    /** Un dégradé ARGB déterministe : bleu en haut, vert chaud en bas. */
    fun image(): IntArray {
        val out = IntArray(IMAGE_W * IMAGE_H)
        for (y in 0 until IMAGE_H) {
            for (x in 0 until IMAGE_W) {
                val r = 40 + x * 180 / IMAGE_W
                val g = 90 + y * 120 / IMAGE_H
                val b = 200 - x * 90 / IMAGE_W
                out[y * IMAGE_W + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** La grille 26×16 du pane, échantillonnée depuis [image]. */
    fun grid(): PixelGrid = PixelGrid(
        colors = sampleGrid(image(), IMAGE_W, IMAGE_H, GRID_ROWS, GRID_COLS),
        cols = GRID_COLS,
        rows = GRID_ROWS,
    )
}

/* ————— previews de scène ————— */

/** Les sept scènes du design, rendues en un seul preview paramétré. */
@Preview(name = "iSee — les scènes", showBackground = true, widthDp = PREVIEW_W, heightDp = PREVIEW_H)
@Composable
private fun PreviewIseeScenes(@PreviewParameter(IseePreviewStateProvider::class) state: IseeUiState) {
    PreviewIseeScene(state)
}

/** L'ouverture : l'intro seule, avant toute question. */
@Preview(name = "iSee — scène vide (intro)", showBackground = true, widthDp = PREVIEW_W, heightDp = PREVIEW_H)
@Composable
private fun PreviewSceneIntro() {
    PreviewIseeScene(IseePreviewStates.intro)
}

/** Le message de l'utilisateur : le gros texte est bleu. */
@Preview(name = "iSee — message du user (bleu)", showBackground = true, widthDp = PREVIEW_W, heightDp = PREVIEW_H)
@Composable
private fun PreviewSceneUser() {
    PreviewIseeScene(IseePreviewStates.userMessage)
}

/** La scène iSee : la réponse, la bulle et son anneau de lumière. */
@Preview(name = "iSee — la scène iSee", showBackground = true, widthDp = PREVIEW_W, heightDp = PREVIEW_H)
@Composable
private fun PreviewSceneAnswered() {
    PreviewIseeScene(IseePreviewStates.answered)
}

/** Les 16 apparences de bulle, côte à côte : le banc de rendu des looks. */
@Preview(name = "iSee — les 16 apparences", showBackground = true, widthDp = PREVIEW_W, heightDp = 360)
@Composable
private fun PreviewBubbleLooks() {
    Canvas(Modifier.fillMaxSize().background(IseeSceneColors.bgTop)) {
        val cellW = size.width / LOOK_COLS
        val cellH = size.height / LOOK_COLS
        val r = minOf(cellW, cellH) * 0.34f
        BubbleLook.values().forEachIndexed { i, look ->
            drawIseeBubble(
                spec = specOf(look),
                answered = false,
                cx = (i % LOOK_COLS + 0.5f) * cellW,
                cy = (i / LOOK_COLS + 0.5f) * cellH,
                r = r,
            )
        }
    }
}

/* ————— previews de la carte caméra ————— */

/** La carte caméra pendant la capture : la mosaïque vit dans le cadre. */
@Preview(name = "iSee — carte caméra (avant photo)", showBackground = true, widthDp = 360, heightDp = 150)
@Composable
private fun PreviewCameraBefore() {
    PreviewCameraCard(afterPhoto = false)
}

/** La carte caméra après la photo : la photo occupe la carte, mosaïque éteinte. */
@Preview(name = "iSee — carte caméra (après photo)", showBackground = true, widthDp = 360, heightDp = 150)
@Composable
private fun PreviewCameraAfter() {
    PreviewCameraCard(afterPhoto = true)
}

/* ————— previews de la dissolution ————— */

/** La dissolution à t=0 : la carte est encore entière, rien n'a quitté. */
@Preview(name = "iSee — dissolution t=0", showBackground = true, widthDp = PREVIEW_W, heightDp = PREVIEW_H)
@Composable
private fun PreviewDissolveStart() {
    PreviewDissolve(0f)
}

/** La dissolution à t=0,5 : la carte se désagrège, la gerbe est en vol. */
@Preview(name = "iSee — dissolution t=0,5", showBackground = true, widthDp = PREVIEW_W, heightDp = PREVIEW_H)
@Composable
private fun PreviewDissolveMid() {
    PreviewDissolve(0.5f)
}

/** La dissolution à t=1 : la gerbe est atterrie, le monde est dans la bulle. */
@Preview(name = "iSee — dissolution t=1", showBackground = true, widthDp = PREVIEW_W, heightDp = PREVIEW_H)
@Composable
private fun PreviewDissolveEnd() {
    PreviewDissolve(1f)
}

/* ————— le rendu partagé par les previews ————— */

/** Le fond dégradé du pane, exactement les mêmes couleurs. */
@Composable
private fun IseeBackdrop(content: @Composable BoxWithConstraintsScope.() -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to IseeSceneColors.bgTop,
                    0.87f to IseeSceneColors.bgLower,
                    1f to IseeSceneColors.bgBottom,
                ),
            ),
        content = content,
    )
}

/** Une scène iSee : la bulle (vrai code de rendu) puis le libellé et le gros texte. */
@Composable
private fun PreviewIseeScene(state: IseeUiState) {
    val entry = selectedEntry(state)
    val view = iseeSceneView(state, entry)
    IseeBackdrop {
        SceneCanvas(view, entry)
        HeadingChrome(view, maxHeight)
    }
}

/** La grande bulle et ses satellites : le Canvas du pane, à l'arrêt de l'animation. */
@Composable
private fun SceneCanvas(view: IseeSceneView, entry: ChatEntry?) {
    val look = bubbleLookIndex(entry?.id ?: INTRO_BUBBLE_ID)
    Canvas(Modifier.fillMaxSize()) {
        bigBubbleBody(
            look = look,
            answered = view.scene == IseeScene.ANSWERED,
            clock = 1f,
            run = null,
            world = null,
            entry = entry,
            tickPhase = 0f,
            tickColor = previewTickColor(view.scene),
        )
    }
}

/** La couleur des ticks d'écoute — miroir du mapping privé du pane. */
private fun previewTickColor(scene: IseeScene): Color? = when (scene) {
    IseeScene.VOICE -> IseeSceneColors.pillVoiceBg
    IseeScene.LISTENING -> IseeSceneColors.pillListenBg
    else -> null
}

/** Le libellé (YOU/ISEE) et le gros texte, à la position de l'écran, dans sa couleur. */
@Composable
private fun BoxWithConstraintsScope.HeadingChrome(view: IseeSceneView, height: Dp) {
    val page = remember(view.heading) { textPages(view.heading).first() }
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
        Text(
            page.joinToString("\n"),
            color = if (view.fromUser) IseeSceneColors.headingUser else IseeSceneColors.headingDark,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 46.sp,
            textAlign = TextAlign.Center,
            maxLines = HEADING_MAX_LINES,
        )
    }
}

/** La carte caméra : même cadre que le pane, contenu figé (pas de caméra en preview). */
@Composable
private fun PreviewCameraCard(afterPhoto: Boolean) {
    Box(
        Modifier
            .size(320.dp, 96.dp)
            .clip(RoundedCornerShape(26.dp))
            .border(3.dp, IseeSceneColors.cameraFrame, RoundedCornerShape(26.dp))
            .background(Color(0xFF14161B)),
    ) {
        StaticMosaic(IseePreviewFixtures.grid(), full = afterPhoto)
    }
}

/**
 * La mosaïque figée : pleine (la photo posée dans la carte) ou en damier espacé
 * (l'image vit dessous). `PixelMosaic` anime son front de remplissage — à t=0
 * aucune cellule n'est encore posée, donc un instantané est dessiné ici.
 */
@Composable
private fun StaticMosaic(grid: PixelGrid, full: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val cols = grid.cols.coerceAtLeast(1)
        val rows = grid.rows.coerceAtLeast(1)
        val cellW = size.width / cols
        val cellH = size.height / rows
        val gap = if (full) 0f else 0.14f
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val i = row * cols + col
                if (i >= grid.colors.size) break
                drawRect(
                    Color(grid.colors[i]),
                    Offset(col * cellW + cellW * gap / 2f, row * cellH + cellH * gap / 2f),
                    Size(cellW * (1f - gap), cellH * (1f - gap)),
                )
            }
        }
    }
}

/** La dissolution à l'instant t : la vraie carte gelée et le vrai overlay de gerbe. */
@Composable
private fun PreviewDissolve(t: Float) {
    val grid = remember { IseePreviewFixtures.grid() }
    IseeBackdrop {
        val density = LocalDensity.current
        val geom = iSeeGeometry(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() })
        val run = remember(geom) { DissolveRun(grid, dissolvePlan(geom), geom) }
        FrozenCard(run)
        DissolveOverlay(run, remember(t) { Animatable(t) }, Modifier.fillMaxSize())
    }
}
