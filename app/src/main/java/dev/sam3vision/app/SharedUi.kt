package dev.sam3vision.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.model.SegObject

/** Bandeau d'état (erreur, info) réutilisé sur tous les écrans. */
@Composable
fun StatusBanner(message: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = Color.White,
        fontSize = 13.sp,
        modifier = modifier
            .padding(12.dp)
            .background(color.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Gros bouton pleine largeur. */
@Composable
fun BigButton(
    label: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
    widthFactor: Float = 1f,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = if (color == AppColors.surfaceHigh) AppColors.text else Color.White,
        ),
        modifier = Modifier.fillMaxWidth(widthFactor).height(54.dp),
    ) {
        Text(
            label,
            fontSize = if (widthFactor >= 1f) 16.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (widthFactor >= 1f) 1 else 2,
            overflow = TextOverflow.Visible,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Écran d'absence de permission caméra. */
@Composable
fun PermissionGate(onAllow: () -> Unit) {
    Box(Modifier.fillMaxSize().background(AppColors.background), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(28.dp),
        ) {
            Text("Accès à la caméra requis", color = AppColors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "SAM Vision photographie la scène à analyser ; sans caméra, choisis une image depuis la galerie.",
                color = AppColors.textMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            BigButton("Autoriser", true, AppColors.primary, onAllow)
        }
    }
}

/** En-tête commun aux écrans. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    onSettings: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) OutlinedButton(onBack) { Text("Retour") }
            Spacer(Modifier.weight(1f))
            if (onSettings != null) OutlinedButton(onSettings) { Text("Réglages") }
        }
        Text(title, color = AppColors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = AppColors.textMuted, fontSize = 13.sp)
    }
}

/** Photo + overlays des masques, superposés à la même échelle. */
@Composable
fun PhotoStage(
    bitmap: Bitmap,
    overlays: List<Bitmap>,
    showMasks: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Photo analysée",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (showMasks) {
            overlays.forEach { overlay ->
                Image(
                    bitmap = overlay.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Liste défilante des objets segmentés (label + score). */
@Composable
fun ObjectList(objects: List<SegObject>) {
    if (objects.isEmpty()) return
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(objects.take(24)) { obj ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(obj.label, color = AppColors.text, fontSize = 14.sp)
                Text("${(obj.score * 100).toInt()} %", color = AppColors.textMuted, fontSize = 14.sp)
            }
        }
    }
}

/**
 * Bouton « écouter » + libellé d'état, partagé par l'analyse, le chat et les réglages :
 * une seule implémentation, donc un comportement identique partout.
 */
@Composable
fun SpeakRow(
    state: SpeakState,
    text: String,
    onSpeak: (String) -> Unit,
    onStop: () -> Unit,
    actionLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    val active = speakIsActive(state, text)
    val label = speakLabel(state, text)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(onClick = { if (active) onStop() else onSpeak(text) }) {
            Text(glyphWithLabel(active, actionLabel), fontSize = 13.sp)
        }
        if (label != null) {
            Text(
                label,
                color = if (state is SpeakState.Error) AppColors.danger else AppColors.textMuted,
                fontSize = 12.sp,
            )
        }
    }
}

/** Libellé du bouton : « Écouter », une action nommée, ou « Stop » pendant la lecture. */
private fun glyphWithLabel(active: Boolean, actionLabel: String?): String = when {
    active -> "$STOP_GLYPH Stop"
    actionLabel != null -> "$SPEAK_GLYPH $actionLabel"
    else -> "$SPEAK_GLYPH Écouter"
}
