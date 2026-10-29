package dev.sam3vision.app.tap

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.BigButton
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.cleanup.CapturePaths
import dev.sam3vision.app.glasses.GlassesVoiceRoute
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.net.HttpSam31VideoClient
import dev.sam3vision.core.net.HttpSamClient
import dev.sam3vision.core.settings.Settings
import java.io.File

/**
 * Écran du mode tap : **un** tap court n'importe où lance la vidéo, le suivant l'arrête et
 * l'envoie à SAM 3.1. Rien d'autre n'est prononcé que « Launched », « Sent », « Failed ».
 *
 * Le tap est capté par [TapAreaView] (GestureDetector, seuils par défaut) ; les barres
 * système (retour/accueil/récents) sont hors zone. TalkBack déclenche la même action.
 */
@Composable
fun TapCaptureScreen(
    settings: Settings,
    onBack: () -> Unit,
    /** Lancé par un tap sur l'accueil : la session démarre sans second tap. */
    autoStart: Boolean = false,
    longPressAction: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val insets = WindowInsets.systemBars
    var status by remember { mutableStateOf<TapStatus>(TapStatus.Idle) }
    val dependencies = rememberTapDependencies(LocalContext.current, rememberCoroutineScope(), settings)
    val session = rememberTapSession(dependencies, autoStart) { status = it }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> TapAreaView(ctx, onShortTap = session::onTap, longPressAction = longPressAction) },
            update = { view -> view.setSystemInsets(insets.getTop(density), insets.getBottom(density)) },
            modifier = Modifier.fillMaxSize(),
        )
        TapOverlay(status, onBack, TapConfig.sendConfigured)
    }
}

/**
 * Session du mode tap : construite une fois, liée à la caméra, préchauffe les trois clips
 * ElevenLabs et réclame les permissions au premier affichage. Tout est libéré à la sortie.
 */
@Composable
private fun rememberTapSession(
    dependencies: TapDependencies,
    autoStart: Boolean,
    onStatus: (TapStatus) -> Unit,
): TapSession {
    val context = LocalContext.current
    val session = remember(dependencies) {
        TapSession(
            scope = dependencies.scope,
            video = dependencies.video,
            announcements = dependencies.announcements,
            // `POST /video` d'abord, repli photo vers `/segment` sur 404 de route manquante.
            sender = senderWithFallback(dependencies.videoClient, dependencies.segmentClient),
            onStatus = onStatus,
            newVideoFile = { CapturePaths.newVideoFile(context) },
            permissionsGranted = { tapPermissionsGranted(context) },
            owner = { context.findLifecycleOwner() },
        )
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { !it }) onStatus(TapStatus.Failed("permission caméra ou micro refusée"))
    }
    LaunchedEffect(dependencies) {
        dependencies.video.bind(context.findLifecycleOwner()).onFailure {
            onStatus(TapStatus.Failed(it.message ?: "caméra indisponible"))
        }
        dependencies.announcements.prewarm()
        if (!tapPermissionsGranted(context)) permissions.launch(TAP_PERMISSIONS)
        // Un tap sur l'accueil vaut premier tap : la session démarre d'elle-même.
        // `onTap` est prévu pour ne rien perdre (liaison caméra en cours, permissions).
        if (autoStart) session.onTap()
    }
    DisposableEffect(dependencies) {
        onDispose {
            session.release()
            dependencies.video.release()
            dependencies.speech.stop()
        }
    }
    return session
}

/** Textes à l'écran : l'annonce vocale reste seule sur la piste audio, l'écrit la complète. */
@Composable
private fun TapOverlay(status: TapStatus, onBack: () -> Unit, sendReady: Boolean) {
    val (title, detail, color) = statusText(status, sendReady)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = color, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = AppColors.textMuted, fontSize = 15.sp)
        }
        BigButton("Quitter", true, AppColors.surfaceHigh, onBack)
    }
}

/** Libellé + couleur par état, sans dépendre du son (accessible aux malvoyants). */
private fun statusText(status: TapStatus, sendReady: Boolean): Triple<String, String, Color> = when (status) {
    TapStatus.Idle if (!sendReady) -> Triple(
        "Envoi désactivé",
        TapConfig.sendBlockedReason() ?: "endpoint SAM 3.1 non configuré.",
        AppColors.warning,
    )
    TapStatus.Idle -> Triple("Touchez l'écran", "Un tap lance la vidéo ; un second tap l'envoie à SAM 3.1.", AppColors.text)
    TapStatus.Recording -> Triple("Enregistrement", "Touchez l'écran pour arrêter et envoyer.", AppColors.danger)
    TapStatus.Sending -> Triple("Envoi…", "La vidéo part à SAM 3.1.", AppColors.warning)
    is TapStatus.Sent -> Triple("Envoyé", status.summary, AppColors.success)
    is TapStatus.Failed -> Triple("Échec", status.reason, AppColors.danger)
}

/** Dépendances de l'écran, construites une fois par configuration de réglages. */
private class TapDependencies(
    val scope: kotlinx.coroutines.CoroutineScope,
    val video: TapVideoSession,
    val announcements: AnnouncementPlayer,
    val speech: SpeechPlayer,
    val videoClient: HttpSam31VideoClient,
    /** Repli photo (`/segment`) quand le serveur n'expose pas `/video`. */
    val segmentClient: HttpSamClient,
)

@Composable
private fun rememberTapDependencies(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    settings: Settings,
): TapDependencies {
    val key = TapConfig.elevenLabsKey(settings)
    val voice = TapConfig.elevenLabsVoice(settings)
    return remember(key, voice, settings.ttsSpeed, context) {
        val speech = SpeechPlayer(
            dir = File(context.cacheDir, "tap-clips"),
            speed = settings.ttsSpeed,
            // Lunettes en session : les annonces sortent dans la paire.
            output = { GlassesVoiceRoute.current(context) },
        )
        TapDependencies(
            scope = scope,
            video = TapVideoSession(context),
            announcements = AnnouncementPlayer(
                dir = TapConfig.clipDir(context, voice),
                tts = ElevenLabsTtsClient(key, voice),
                player = speech,
                scope = scope,
            ),
            speech = speech,
            videoClient = HttpSam31VideoClient(TapConfig.sam31Endpoint, TapConfig.ON_DEVICE_INFERENCE),
            segmentClient = HttpSamClient(TapConfig.sam31Endpoint),
        )
    }
}
