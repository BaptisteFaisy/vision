package dev.sam3vision.app.isee

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rendu des trois cartes de l'onboarding iSee : tout est dessiné au Canvas, en
 * **fractions de la carte**, donc sans déformation quelle que soit la taille de
 * l'écran et sans aucun asset embarqué. Le doigt est stylisé-réaliste (dégradé de
 * peau, ombre portée, brillance de phalange) ; rien n'est photoréaliste.
 *
 * La pose vient de [onboardingPose] (pure, testée en JVM) : ce fichier ne décide de
 * rien, il peint.
 */

private val StageTop = Color(0xFF10151F)
private val StageBottom = Color(0xFF182034) // léger halo bleu en bas de la carte
private val ScreenBg = Color(0xFF0C1018)
private val ScreenEdge = Color(0xFF2A3446)
private val SkinLight = Color(0xFFF6D2B3)
private val SkinBase = Color(0xFFE8B492)
private val SkinShade = Color(0xFFC68B62)
private val RecRed = Color(0xFFE5484D)
private val AccentBlue = Color(0xFF7CB6FF)
private val PressGlow = Color(0x447CB6FF)

/** Rect horizontal du mini-écran, en fractions de la carte. */
private const val SCREEN_LEFT = 0.10f
private const val SCREEN_RIGHT = 0.90f
private const val SCREEN_TOP = 0.66f
private const val SCREEN_BOTTOM = 0.97f

/** L'ensemble d'une scène, de l'arrière-plan jusqu'au doigt par-dessus. */
internal fun DrawScope.drawOnboardingStage(kind: OnboardingGesture, pose: OnboardingPose) {
    drawStage()
    drawMiniScreen(pose)
    drawPressGlow(pose)
    drawTapRipple(pose)
    if (kind == OnboardingGesture.Hold) drawHoldWaves(pose)
    if (pose.flash >= 0f) drawFlashBurst(pose)
    drawFinger(pose)
}

/** Arrière-plan de la carte : dégradé doux + halo bleu autour du contact. */
private fun DrawScope.drawStage() {
    drawRect(Brush.verticalGradient(listOf(StageTop, StageBottom), startY = 0f, endY = size.height))
    val r = size.width * 0.40f
    drawOval(
        Brush.radialGradient(listOf(Color(0x22508EF7), Color.Transparent)),
        topLeft = Offset(size.width * 0.5f - r, SCREEN_TOP * size.height - r * 0.7f),
        size = Size(r * 2f, r * 1.4f),
    )
}

/** Le mini-écran en bas de la carte + ses indices UI (barres, REC, photo). */
private fun DrawScope.drawMiniScreen(pose: OnboardingPose) {
    val left = SCREEN_LEFT * size.width
    val top = SCREEN_TOP * size.height
    val right = SCREEN_RIGHT * size.width
    val bottom = SCREEN_BOTTOM * size.height
    val radius = CornerRadius(14f, 14f)
    val frame = Size(right - left, bottom - top)
    drawRoundRect(color = ScreenBg, topLeft = Offset(left, top), size = frame, cornerRadius = radius)
    drawRoundRect(
        color = ScreenEdge,
        topLeft = Offset(left, top),
        size = frame,
        cornerRadius = radius,
        style = Stroke(width = 3f),
    )
    val innerLeft = left + frame.width * 0.08f
    drawRoundRect(
        color = Color(0xFF2B3648),
        topLeft = Offset(innerLeft, top + frame.height * 0.45f),
        size = Size(frame.width * 0.52f, frame.height * 0.11f),
        cornerRadius = CornerRadius(10f, 10f),
    )
    drawRoundRect(
        color = Color(0xFF252E3E),
        topLeft = Offset(innerLeft, top + frame.height * 0.68f),
        size = Size(frame.width * 0.74f, frame.height * 0.11f),
        cornerRadius = CornerRadius(10f, 10f),
    )
    drawRecDot(pose.recOn, left, top, right)
    if (pose.cameraFlash) drawPhotoIcon(left, top, right, bottom)
}

/**
 * Pastille du mode vidéo : rouge pendant la « vidéo », grise sinon — le même
 * code couleur que le vrai badge REC de la page caméra de l'app.
 */
private fun DrawScope.drawRecDot(on: Boolean, left: Float, top: Float, right: Float) {
    val cx = right - (right - left) * 0.18f
    val cy = top + (size.height - top) * 0.18f
    drawCircle(
        color = if (on) RecRed else Color(0xFF39445A),
        radius = size.width * 0.035f,
        center = Offset(cx, cy),
    )
}

/** Petit boîtier photo (rect + objectif), allumé par l'éclat quand il y en a un. */
private fun DrawScope.drawPhotoIcon(left: Float, top: Float, right: Float, bottom: Float) {
    val w = (right - left) * 0.16f
    val h = w * 0.72f
    val boxLeft = right - (right - left) * 0.34f
    val boxTop = top + (bottom - top) * 0.12f
    drawRoundRect(
        color = AccentBlue,
        topLeft = Offset(boxLeft, boxTop),
        size = Size(w, h),
        cornerRadius = CornerRadius(6f, 6f),
    )
    drawCircle(Color.White, radius = w * 0.22f, center = Offset(boxLeft + w / 2f, boxTop + h / 2f))
}

/** Centre du mini-écran : point de contact des ondes et des éclats. */
private fun DrawScope.contactPoint(): Offset = Offset(
    size.width * 0.5f,
    SCREEN_TOP * size.height + (SCREEN_BOTTOM - SCREEN_TOP) * size.height * 0.20f,
)

/** Halo bleu doux sous la pointe quand elle touche le mini-écran. */
private fun DrawScope.drawPressGlow(pose: OnboardingPose) {
    if (pose.press <= 0.01f) return
    val r = size.width * 0.26f
    drawOval(
        Brush.radialGradient(listOf(PressGlow.copy(alpha = PressGlow.alpha * pose.press), Color.Transparent)),
        topLeft = Offset(size.width * 0.5f - r, pose.tip * size.height - r * 0.5f),
        size = Size(r * 2f, r),
    )
}

/** Anneau du tap : un cercle qui grandit et s'estompe depuis le point de contact. */
private fun DrawScope.drawTapRipple(pose: OnboardingPose) {
    val k = pose.ripple
    if (k !in 0f..1f) return
    drawCircle(
        color = AccentBlue.copy(alpha = 0.65f * (1f - k)),
        radius = size.width * (0.10f + 0.24f * k),
        center = contactPoint(),
        style = Stroke(width = size.width * 0.018f * (1f - k * 0.4f)),
    )
}

/** Éclat blanc de la photo : rayons qui sortent du contact et s'effacent. */
private fun DrawScope.drawFlashBurst(pose: OnboardingPose) {
    val k = pose.flash
    if (k !in 0f..1f) return
    val alpha = (1f - k) * 0.85f
    val center = contactPoint()
    val radius = size.width * (0.16f + 0.30f * k)
    drawCircle(color = Color.White.copy(alpha = alpha * 0.30f), radius = radius * 1.4f, center = center)
    repeat(6) { i ->
        val angle = (i * 60f) * (Math.PI / 180.0).toFloat() + (k * 0.6f)
        drawLine(
            color = Color.White.copy(alpha = alpha),
            start = center,
            end = Offset(center.x + sin(angle) * radius, center.y - cos(angle) * radius),
            strokeWidth = size.width * 0.02f,
        )
    }
}

/** Ondes concentriques de la voix pendant l'appui maintenu. */
private fun DrawScope.drawHoldWaves(pose: OnboardingPose) {
    val phase = pose.wavePhase
    if (phase < 0f) return
    val center = Offset(size.width * 0.5f, contactPoint().y + size.height * 0.01f)
    repeat(3) { i ->
        val k = (phase + i / 3f) % 1f
        drawCircle(
            color = AccentBlue.copy(alpha = 0.45f * (1f - k)),
            radius = size.width * (0.16f + 0.34f * k),
            center = center,
            style = Stroke(width = size.width * 0.016f),
        )
    }
}

/** Ombre portée du doigt, corps du doigt, brillance de phalange. */
private fun DrawScope.drawFinger(pose: OnboardingPose) {
    val cx = size.width * 0.5f
    val tipY = pose.tip * size.height
    val shaftTop = tipY - size.height * (0.62f + 0.10f * pose.press)
    val halfW = size.width * 0.155f

    // Ombre sur le mini-écran : plus dense quand le doigt est proche.
    drawOval(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.16f + 0.24f * pose.press), Color.Transparent)),
        topLeft = Offset(cx - halfW * 1.35f, tipY + size.height * 0.10f),
        size = Size(halfW * 2.7f, halfW * 0.9f),
    )

    val body = fingerBody(cx, tipY, shaftTop, halfW)
    drawPath(
        body,
        Brush.verticalGradient(listOf(SkinLight, SkinBase, SkinShade), startY = shaftTop, endY = tipY),
    )
    drawPath(
        body,
        Brush.verticalGradient(listOf(Color(0x20C68B62), Color(0x408A5A32)), startY = shaftTop, endY = tipY),
        style = Stroke(width = 2f),
    )
    drawCircle(
        color = Color(0x33FFFFFF),
        radius = halfW * 0.55f,
        center = Offset(cx - halfW * 0.25f, tipY - size.height * 0.26f),
    )
}

/** Silhouette du doigt : fût vertical + pointe arrondie vers le bas. */
private fun fingerBody(cx: Float, tipY: Float, shaftTop: Float, halfW: Float): Path = Path().apply {
    moveTo(cx - halfW, shaftTop)
    lineTo(cx - halfW, tipY - halfW * 0.9f)
    quadraticTo(cx - halfW + halfW * 0.1f, tipY - halfW * 0.2f, cx, tipY + halfW * 0.16f)
    quadraticTo(cx + halfW - halfW * 0.1f, tipY - halfW * 0.2f, cx + halfW, tipY - halfW * 0.9f)
    lineTo(cx + halfW, shaftTop)
    close()
}