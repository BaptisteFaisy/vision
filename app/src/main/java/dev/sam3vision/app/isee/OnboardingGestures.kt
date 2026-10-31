package dev.sam3vision.app.isee

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.BigButton

/**
 * Onboarding des **deux gestes** de la page iSee : un doigt dessiné (Canvas, aucun
 * asset embarqué) rejoue en boucle tap = vidéo, appui long = dicter (la photo
 * filmée part avec la question). Les cartes remplacent les boutons : la page
 * d'accueil n'en a aucun, le geste est la seule interface.
 *
 * L'écran est **rejouable** depuis les Réglages (« Replay gestures ») : c'est un
 * écran de la pile de navigation ([dev.sam3vision.app.Route.Onboarding]), pas une
 * garde de premier lancement — l'accueil iSee est utilisable tout de suite.
 *
 * La pose du doigt est une **fonction pure** ([onboardingPose], testée en JVM) ; le
 * rendu vit dans `OnboardingGestureArt.kt`.
 */

/** Les deux gestes expliqués, dans l'ordre du discours d'accueil. */
enum class OnboardingGesture { Tap, Hold }

/** Snapshot complet de l'état d'une carte : la pose pousse tout le dessin. */
internal data class OnboardingPose(
    /** Position verticale de la pointe, en fraction de la carte (0 = haut). */
    val tip: Float,
    /** La pointe touche le mini-écran. */
    val pressed: Boolean,
    /** 0..1 : lissage de l'écrasement du doigt et de son ombre. */
    val press: Float,
    /** [ONBOARDING_OFF] ou 0..1 : anneau du tap. */
    val ripple: Float,
    /** [ONBOARDING_OFF] ou 0..1 : éclat de la photo. */
    val flash: Float,
    /** [ONBOARDING_OFF] ou 0..1 : ondes de la voix. */
    val wavePhase: Float,
    /** Pastille REC allumée (vidéo en cours). */
    val recOn: Boolean,
    /** Icône photo allumée sur le mini-écran. */
    val cameraFlash: Boolean,
)

/** Valeur d'un canal éteint : aucune forme n'est dessinée pour cette phase. */
internal const val ONBOARDING_OFF = -1f

/** Repères verticaux partagés : pointe au repos vs pointe en contact. */
internal const val ONBOARDING_REST_Y = 0.20f
internal const val ONBOARDING_TOUCH_Y = 0.70f

/** Durée d'une boucle, par geste : les deux cartes ne pulsent pas ensemble. */
internal fun onboardingPeriodMs(kind: OnboardingGesture): Int = when (kind) {
    OnboardingGesture.Tap -> 2_000
    OnboardingGesture.Hold -> 3_200
}

/** Pose du geste [kind] à l'instant [t] (0..1 d'une boucle) : fonction pure. */
internal fun onboardingPose(kind: OnboardingGesture, t: Float): OnboardingPose = when (kind) {
    OnboardingGesture.Tap -> tapPose(t)
    OnboardingGesture.Hold -> holdPose(t)
}

/** Une pression lissée (montée, tenue, descente) sur la fenêtre [a..d]. */
internal fun pressAmount(t: Float, a: Float, b: Float, c: Float, d: Float): Float = when {
    t <= a || t >= d -> 0f
    t < b -> ease((t - a) / (b - a))
    t <= c -> 1f
    else -> ease((d - t) / (d - c))
}

/** Smoothstep : démarrage et arrêt très doux, le geste reste lisible. */
private fun ease(x: Float) = x * x * (3f - 2f * x)

/** Tap simple : une pression, la vidéo tourne, une nouvelle pression stoppe. */
private fun tapPose(t: Float): OnboardingPose {
    val press = pressAmount(t, 0.14f, 0.22f, 0.56f, 0.68f)
    val ripple = if (t >= 0.22f && t < 0.56f) (t - 0.22f) / 0.34f else ONBOARDING_OFF
    return pose(press, ripple, ONBOARDING_OFF, ONBOARDING_OFF, recOn = press > 0.5f, cameraFlash = false)
}

/** Appui long : le doigt reste posé, les ondes « voix » pulsent. */
private fun holdPose(t: Float): OnboardingPose {
    val press = if (t < 0.14f) ease(t / 0.14f) else 1f
    val wavePhase = if (t >= 0.18f) (t - 0.18f) / 0.82f else ONBOARDING_OFF
    return pose(press, ONBOARDING_OFF, ONBOARDING_OFF, wavePhase, recOn = false, cameraFlash = false)
}

/** Fusionne un cas en [OnboardingPose] : pointe interpolée + états du mini-écran. */
private fun pose(
    press: Float,
    ripple: Float,
    flash: Float,
    wavePhase: Float,
    recOn: Boolean,
    cameraFlash: Boolean,
): OnboardingPose = OnboardingPose(
    tip = ONBOARDING_REST_Y + (ONBOARDING_TOUCH_Y - ONBOARDING_REST_Y) * press,
    pressed = press > 0.5f,
    press = press,
    ripple = ripple,
    flash = flash,
    wavePhase = wavePhase,
    recOn = recOn,
    cameraFlash = cameraFlash,
)

/** Texte d'une carte : numéro + geste en haut, conséquence en bas. */
private data class OnboardingCard(val kind: OnboardingGesture, val title: String, val hint: String)

private val ONBOARDING_CARDS = listOf(
    OnboardingCard(OnboardingGesture.Tap, "1 · One tap", "The video starts. Another tap stops it."),
    OnboardingCard(
        OnboardingGesture.Hold,
        "2 · Long press",
        "Speak while holding: the filmed photo goes with the question.",
    ),
)

/** Les deux cartes côte à côte : le geste compte partout sur l'écran. */
@Composable
fun OnboardingGestureRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ONBOARDING_CARDS.forEach { card -> Box(Modifier.weight(1f)) { OnboardingGestureCard(card) } }
    }
}

/** Une carte = scène Canvas en haut + légende en bas, animée en boucle. */
@Composable
private fun OnboardingGestureCard(card: OnboardingCard) {
    val transition = rememberInfiniteTransition(label = "onboarding-${card.kind.name}")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = onboardingPeriodMs(card.kind), easing = LinearEasing),
        ),
        label = "onboarding-time-${card.kind.name}",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            drawOnboardingStage(card.kind, onboardingPose(card.kind, time))
        }
        Text(
            text = card.title,
            color = IseeSceneColors.headingDark,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = card.hint,
            color = IseeSceneColors.label,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/**
 * L'écran complet, dans la pile de navigation (Réglages → « Replay gestures ») :
 * fond clair du design iSee, les trois cartes, et un bouton de retour.
 */
@Composable
fun OnboardingGesturesScreen(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(IseeSceneColors.bgTop, IseeSceneColors.bgBottom)))
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("iSee", color = IseeSceneColors.wordmark, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "The two gestures",
            color = IseeSceneColors.headingDark,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Anywhere on the screen — no button to aim for.",
            color = IseeSceneColors.label,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        OnboardingGestureRow(Modifier.fillMaxWidth())
        Text(
            "These gestures drive the iSee home page. To review this page: " +
                "Settings, then “Replay gestures”.",
            color = IseeSceneColors.label,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        BigButton("Got it", true, IseeSceneColors.pillActiveBg, onClose)
    }
}