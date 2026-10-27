package dev.sam3vision.app.isee

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import dev.sam3vision.core.iseedesign.BubbleLook
import dev.sam3vision.core.iseedesign.DissolvePlan
import dev.sam3vision.core.iseedesign.bubbleMorph
import dev.sam3vision.core.iseedesign.iSeeGeometry
import dev.sam3vision.core.iseedesign.satelliteAlpha
import dev.sam3vision.core.iseedesign.satellitePositions
import dev.sam3vision.core.iseedesign.SceneGeom
import dev.sam3vision.core.iseedesign.glint
import dev.sam3vision.core.iseedesign.lerp
import dev.sam3vision.core.iseedesign.tickLength
import dev.sam3vision.core.iseedesign.worldAlpha
import dev.sam3vision.core.iseedesign.worldGlint
import dev.sam3vision.core.iseedesign.worldSpot
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dessin de la bulle « verre / fluide » de la vidéo : un dégradé radial posé en
 * amont du centre (la lumière vient du haut-gauche), un liséré, deux reflets
 * spéculaires, puis le motif propre au look. Chaque look est une paire de couleurs
 * et un type de motif : les 16 apparences partagent la même mécanique de dessin.
 *
 * Pendant la dissolution, la bulle est aussi un « monde » : les pixels atterris
 * peignent leurs taches ([WorldPaint]) au-dessus du motif, en dessous des reflets.
 */
data class LookSpec(
    /** Couleurs du dégradé radial, du bord vers le centre. */
    val rim: Color,
    val core: Color,
    val ring: Color,
    val caustic: Color,
    val highlight: Color,
    val pattern: Pattern,
)

/** Motifs propres aux 16 apparences, posés au-dessus du dégradé. */
enum class Pattern { PLANET, MARBLE, FLUID, GLASS, RIVER, EMBER }

/** Table de spécification : chaque look de [BubbleLook] y a une entrée (dans le
 * même ordre), et l'apparence d'un identifiant ne bouge jamais. */
fun specOf(look: BubbleLook): LookSpec {
    val values = BubbleLook.values()
    val i = look.ordinal.coerceIn(0, values.size - 1)
    return if (i < SPECS.size) SPECS[i] else SPECS[0]
}

/** Un seul tableau, dans l'ordre exact de l'énumération du cœur. */
private val SPECS: List<LookSpec> = listOf(
    // PLANET_OCEAN : bleu profond, continents (réponse IA de la vidéo)
    spec(0x0B2C6B, 0x1F63C8, 0x9FC2FF, 0xE07A3C, 0xC9DEFF, Pattern.PLANET),
    // RAINBOW_MARBLE : caustiques multicolores (l'intro de la vidéo)
    spec(0x2743C7, 0x53C8DF, 0xEAEFFF, 0xC74FB6, 0xF7E9FF, Pattern.MARBLE),
    // FLUID_MEMBRANE : horizontale ondulée
    spec(0x1226AB, 0x4FA8E8, 0xB9E2FF, 0x2AD3B8, 0xE7F6FF, Pattern.FLUID),
    // LAGOON_SWIRL : tourbillons turquoise
    spec(0x0E4F63, 0x2FB9C9, 0xC8F4F7, 0x0FA8C9, 0xE4FEFF, Pattern.RIVER),
    // COPPER_MARBLE : cuivre chaud
    spec(0x7A3A18, 0xC97B3F, 0xFFDFC2, 0xE8A060, 0xFFF0E2, Pattern.MARBLE),
    // VIOLET_WORLD : violet profond (voix)
    spec(0x3A1162, 0x8B2BD9, 0xE4C4FF, 0xB15CF0, 0xF3E3FF, Pattern.PLANET),
    // TRAIL_WEAVE : fils de pixels tissés (mémoire)
    spec(0x23114A, 0x6E3FA8, 0xD9C6F2, 0x8A4FD3, 0xEFE3FF, Pattern.GLASS),
    // MILKY_GLASS : verre laiteux
    spec(0x8D97B2, 0xC9D2E7, 0xF4F7FF, 0xAEBCD8, 0xEFF4FF, Pattern.GLASS),
    // JADE_MARBLE : jade
    spec(0x0E5C43, 0x45B78A, 0xD2F5E6, 0xC9E36A, 0xE7FBF2, Pattern.MARBLE),
    // NEON_RIVER : néon cyan-magenta
    spec(0x081C3F, 0x1447B8, 0x9DD7FF, 0xDC41B0, 0xBFEFFF, Pattern.RIVER),
    // EMBER_WORLD : braises orange (mémoire de la vidéo)
    spec(0x5A1B0C, 0xC7481F, 0xFFD9AE, 0xF08A2E, 0xFFEDD8, Pattern.EMBER),
    // SLATE_MARBLE : ardoise (moins girly)
    spec(0x1E2A38, 0x5A7185, 0xD4DCE7, 0x89A0B4, 0xE9EFF6, Pattern.MARBLE),
    // ULTRAVIOLET_DARK : violet sombre à reflets
    spec(0x1C0B33, 0x5930B0, 0xC9A9F5, 0x7A4FD9, 0xE0CDFF, Pattern.FLUID),
    // GRAPHITE_IRIS : irisation gris acier
    spec(0x232B2B, 0x64857F, 0xD6E5E0, 0x50B0A5, 0xEAF5F1, Pattern.PLANET),
    // TEAL_PLANET : planète turquoise
    spec(0x06424D, 0x1E96A8, 0xC3F1F2, 0x153E6F, 0xE3FCFC, Pattern.PLANET),
    // CORAL_FLUTTER : corail doux
    spec(0x823A45, 0xE07A82, 0xFFDDE0, 0xF0A3A9, 0xFFF1F2, Pattern.FLUID),
)

private fun spec(rim: Long, core: Long, ring: Long, caustic: Long, highlight: Long, pattern: Pattern) = LookSpec(
    rim = col(rim), core = col(core), ring = col(ring), caustic = col(caustic),
    highlight = col(highlight), pattern = pattern,
)

/** Le monde posé sur la bulle pendant la dissolution : grille figée + instant t. */
class WorldPaint(
    val colors: IntArray,
    val plan: DissolvePlan,
    /** La géométrie au moment du dessin (la bulle morphée suit le dissolve). */
    val geom: SceneGeom,
    /** Instant de la dissolution : au-delà de 1, le monde est plein. */
    val t: Float,
)

/** Dessine la bulle de verre : dégradé, motif du look, [WorldPaint] optionnel,
 * reflets, puis halo/réponse et ticks d'écoute. Centre (cx, cy) et rayon [r] en px,
 * posés par le pane de dessous — le morph de la bulle suit le dissolve. */
fun DrawScope.drawIseeBubble(
    spec: LookSpec,
    answered: Boolean,
    cx: Float,
    cy: Float,
    r: Float,
    world: WorldPaint? = null,
    tickPhase: Float = 0f,
    tickColor: Color? = null,
) {
    drawIseeBody(spec, cx, cy, r)
    drawIseePattern(spec, cx, cy, r)
    world?.let { drawWorldOnBubble(it, cx, cy, r) }
    drawIseeHighlights(spec, cx, cy, r)
    if (answered) drawIseeHalo(cx, cy, r)
    drawIseeTicks(cx, cy, r, tickPhase, tickColor)
}

/** Corps : dégradé radial décentré (lumière haut-gauche), liséré plein bord. */
private fun DrawScope.drawIseeBody(spec: LookSpec, cx: Float, cy: Float, r: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(spec.core, spec.rim),
            center = Offset(cx - r * 0.32f, cy - r * 0.36f),
            radius = r * 1.5f,
        ),
        radius = r,
        center = Offset(cx, cy),
    )
    drawCircle(spec.ring.copy(alpha = 0.9f), radius = r, center = Offset(cx, cy), style = Stroke(1.6.dp.toPx()))
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, spec.rim.copy(alpha = 0.35f)),
            center = Offset(cx, cy),
            radius = r,
        ),
        radius = r * 0.995f,
        center = Offset(cx, cy),
    )
}

/** Motif du look, au-dessus du corps, en coordonnées normalisées du rayon. */
private fun DrawScope.drawIseePattern(spec: LookSpec, cx: Float, cy: Float, r: Float) {
    when (spec.pattern) {
        Pattern.PLANET -> continents(cx, cy, r, spec)
        Pattern.MARBLE -> marbling(cx, cy, r, spec)
        Pattern.FLUID -> membrane(cx, cy, r, spec)
        Pattern.GLASS -> softVeil(cx, cy, r, spec)
        Pattern.RIVER -> caustic(cx, cy, r, spec)
        Pattern.EMBER -> sparks(cx, cy, r, spec)
    }
}

/** Reflets : une virgule brillante en haut-gauche, un petit contre-reflet bas. */
private fun DrawScope.drawIseeHighlights(spec: LookSpec, cx: Float, cy: Float, r: Float) {
    val hx = cx - r * 0.42f
    val hy = cy - r * 0.46f
    drawCircle(spec.highlight.copy(alpha = 0.75f), radius = r * 0.16f, center = Offset(hx, hy))
    drawCircle(
        spec.highlight.copy(alpha = 0.35f),
        radius = r * 0.07f,
        center = Offset(hx + r * 0.10f, hy + r * 0.30f),
    )
    drawCircle(
        spec.core.copy(alpha = 0.30f),
        radius = r * 0.13f,
        center = Offset(cx + r * 0.34f, cy + r * 0.42f),
    )
}

/** Anneau de lumière autour de la bulle quand la réponse est complète. */
private fun DrawScope.drawIseeHalo(cx: Float, cy: Float, r: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF5165F1).copy(alpha = 0f), Color(0xFF5165F1).copy(alpha = 0.38f)),
            center = Offset(cx, cy),
            radius = r * 1.14f,
        ),
        radius = r * 1.06f,
        center = Offset(cx, cy),
        style = Stroke(r * 0.07f),
    )
}

/** Ticks d'écoute : 26 segments rayonnants dont la longueur pulse, comme la vidéo. */
private fun DrawScope.drawIseeTicks(cx: Float, cy: Float, r: Float, phase: Float, color: Color?) {
    if (color == null) return
    val count = TICK_COUNT
    for (i in 0 until count) {
        val angle = i * (2f * Math.PI.toFloat() / count)
        val dx = cos(angle)
        val dy = sin(angle)
        val len = r * tickLength(i, phase)
        val start = r * 1.08f
        drawLine(
            color = color.copy(alpha = 0.85f),
            start = Offset(cx + dx * start, cy + dy * start),
            end = Offset(cx + dx * (start + len), cy + dy * (start + len)),
            strokeWidth = 2.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Nombre de ticks rayonnants autour de la bulle. */
internal const val TICK_COUNT = 26

/**
 * Le monde de la dissolution : clip dans le disque, tache par tache, à la place
 * d'atterrissage de chaque pixel (plan), la couleur éclaircie pendant le pop.
 * Les taches hors du disque (bords de la carte écrasés au globe) sont absorbées
 * par le clip — le globe reste un disque.
 */
private fun DrawScope.drawWorldOnBubble(paint: WorldPaint, cx: Float, cy: Float, r: Float) {
    val colors = paint.colors
    if (colors.isEmpty() || paint.t <= 0f) return
    val path = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(cx - r * 0.985f, cy - r * 0.985f, cx + r * 0.985f, cy + r * 0.985f))
    }
    clipPath(path) {
        val cellW = r * 2f / dev.sam3vision.core.iseedesign.CARD_COLS * 1.05f
        val cellH = r * 2f / dev.sam3vision.core.iseedesign.CARD_ROWS * 1.35f
        val cnt = minOf(colors.size, paint.plan.size())
        for (i in 0 until cnt) {
            val spot = worldSpot(paint.plan.paths[i], paint.geom)
            val alpha = worldAlpha(paint.plan.paths[i], paint.t)
            if (alpha <= 0f) continue
            val pop = worldGlint(paint.plan.paths[i], paint.t)
            val color = glint(colors[i], pop)
            val w = cellW * lerp(1.5f, 1f, alpha)
            val h = cellH * lerp(1.5f, 1f, alpha)
            drawRect(
                color = Color(color),
                topLeft = Offset(spot.first - w / 2f, spot.second - h / 2f),
                size = androidx.compose.ui.geometry.Size(w, h),
            )
        }
    }
}

/**
 * Satellite posé autour de la grande bulle : mini bulle de verre, même mécanique
 * (dégradé décentré, liséré, reflet), posée exactement au spot demandé.
 */
fun DrawScope.drawSatellite(spec: LookSpec, alpha: Float = 1f, cx: Float, cy: Float, r: Float) {
    val c = Offset(cx, cy)
    fun a(color: Color): Color = color.copy(alpha = color.alpha * alpha)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(a(spec.core), a(spec.rim)),
            center = Offset(cx - r * 0.32f, cy - r * 0.36f),
            radius = r * 1.5f,
        ),
        radius = r,
        center = c,
    )
    drawCircle(a(spec.ring).copy(alpha = 0.9f * alpha), radius = r, center = c, style = Stroke(1.2.dp.toPx()))
    drawCircle(a(spec.highlight).copy(alpha = 0.7f * alpha), radius = r * 0.22f, center = Offset(cx - r * 0.30f, cy - r * 0.34f))
    drawCircle(a(spec.caustic).copy(alpha = 0.30f * alpha), radius = r * 0.45f, center = Offset(cx + r * 0.15f, cy + r * 0.22f))
}

/** ————— motifs individuels ————— */

/** Continents : une péninsule sombre + archipels posés comme sur une planète. */
private fun DrawScope.continents(cx: Float, cy: Float, r: Float, spec: LookSpec) {
    val land = spec.caustic.copy(alpha = 0.55f)
    drawCircle(land, radius = r * 0.30f, center = Offset(cx + r * 0.28f, cy - r * 0.22f))
    drawCircle(land.copy(alpha = 0.8f), radius = r * 0.16f, center = Offset(cx - r * 0.30f, cy + r * 0.18f))
    drawCircle(land.copy(alpha = 0.55f), radius = r * 0.10f, center = Offset(cx - r * 0.08f, cy + r * 0.52f))
}

/** Marbrure : deux caustiques croisées, comme le verre coloré de la vidéo. */
private fun DrawScope.marbling(cx: Float, cy: Float, r: Float, spec: LookSpec) {
    val a = spec.caustic.copy(alpha = 0.5f)
    drawCircle(a, radius = r * 0.42f, center = Offset(cx - r * 0.22f, cy + r * 0.10f))
    drawCircle(a.copy(alpha = 0.4f), radius = r * 0.30f, center = Offset(cx + r * 0.30f, cy + r * 0.30f))
    drawCircle(spec.core.copy(alpha = 0.35f), radius = r * 0.26f, center = Offset(cx + r * 0.06f, cy - r * 0.12f))
}

/** Membrane fluide : trois bandes ondulées horizontales. */
private fun DrawScope.membrane(cx: Float, cy: Float, r: Float, spec: LookSpec) {
    val a = spec.caustic.copy(alpha = 0.42f)
    for (k in 0 until 3) {
        val yk = cy + (k - 1) * r * 0.34f
        drawCircle(a, radius = r * (0.46f - k * 0.05f), center = Offset(cx - r * (0.16f + k * 0.14f), yk))
        drawCircle(a.copy(alpha = 0.28f), radius = r * 0.22f, center = Offset(cx + r * (0.30f - k * 0.08f), yk))
    }
}

/** Voile doux : un seul halo de couleur posé bas, quasi vitre. */
private fun DrawScope.softVeil(cx: Float, cy: Float, r: Float, spec: LookSpec) {
    drawCircle(spec.caustic.copy(alpha = 0.35f), radius = r * 0.55f, center = Offset(cx + r * 0.10f, cy + r * 0.24f))
    drawCircle(spec.core.copy(alpha = 0.25f), radius = r * 0.32f, center = Offset(cx - r * 0.20f, cy - r * 0.10f))
}

/** Caustiques : trois nappes qui se chevauchent (turquoise, spearmint, bleu). */
private fun DrawScope.caustic(cx: Float, cy: Float, r: Float, spec: LookSpec) {
    val a = spec.caustic.copy(alpha = 0.45f)
    drawCircle(a, radius = r * 0.48f, center = Offset(cx - r * 0.18f, cy - r * 0.14f))
    drawCircle(a.copy(alpha = 0.5f), radius = r * 0.36f, center = Offset(cx + r * 0.34f, cy + r * 0.16f))
    drawCircle(spec.core.copy(alpha = 0.40f), radius = r * 0.42f, center = Offset(cx + r * 0.02f, cy + r * 0.46f))
}

/** Braises : trois points chauds ronds sur fond sombre. */
private fun DrawScope.sparks(cx: Float, cy: Float, r: Float, spec: LookSpec) {
    val hot = spec.caustic
    drawCircle(hot.copy(alpha = 0.75f), radius = r * 0.13f, center = Offset(cx - r * 0.22f, cy - r * 0.30f))
    drawCircle(hot.copy(alpha = 0.6f), radius = r * 0.08f, center = Offset(cx + r * 0.26f, cy + r * 0.06f))
    drawCircle(hot.copy(alpha = 0.45f), radius = r * 0.06f, center = Offset(cx + r * 0.02f, cy + r * 0.44f))
}

/** Six satellites autour de la grande bulle, comme sur les frames de la vidéo. */
internal const val SATELLITE_COUNT = 6

/** Le contenu du Canvas de la grande bulle : géométrie du cœur, satellites (chacun
 * un look voisin, tous cueillis que [alpha] pendant le dissolve), le monde posé par
 * les pixels atterris, puis la bulle et ses ticks d'écoute. */
internal fun DrawScope.bigBubbleBody(
    look: Int,
    answered: Boolean,
    clock: Float,
    run: DissolveRun?,
    world: WorldMemory?,
    entry: ChatEntry?,
    tickPhase: Float,
    tickColor: Color?,
) {
    val t = clock
    val morph = if (run != null) bubbleMorph(t) else 1f
    val g = iSeeGeometry(size.width, size.height, morph)
    val shown = run != null
    val tm = if (shown) t else 1f
    val paint = world?.let { w ->
        val visible = shown || w.boundId == null || w.boundId == entry?.id
        if (visible && w.grid.colors.isNotEmpty()) WorldPaint(w.grid.colors, w.plan, g, tm) else null
    }
    for ((pitch, pos) in satellitePositions(SATELLITE_COUNT).withIndex()) {
        val spec = specOf(BubbleLook.values()[(look + pitch + 1) % BubbleLook.values().size])
        drawSatellite(
            spec = spec,
            alpha = if (shown) satelliteAlpha(morph) else 1f,
            cx = g.bubbleCx + pos.first * g.bubbleR,
            cy = g.bubbleCy + pos.second * g.bubbleR,
            r = g.satelliteR,
        )
    }
    drawIseeBubble(
        spec = specOf(BubbleLook.values()[look]),
        answered = answered,
        cx = g.bubbleCx,
        cy = g.bubbleCy,
        r = g.bubbleR,
        world = paint,
        tickPhase = tickPhase,
        tickColor = tickColor,
    )
}

/** Couleur depuis un long littéral ARGB. */
private fun col(argb: Long): Color = Color(argb.toInt() and 0xFFFFFFFF.toInt())
