package dev.sam3vision.app.isee

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.iseedesign.IseePortrait
import dev.sam3vision.core.iseedesign.IseeScene

/**
 * En-tête de la scène iSee : pastille d'état seule (wordmark retiré le
 * 2026-11-03 sur demande user). Tiré de `IseeScenePane.kt` : que de
 * l'affichage, aucune logique de capture.
 */
/** En-tête : la pastille d'état en haut à droite ; caméra ouverte, elle rejoint la rangée des puces (voir `ModeToggles`), l'en-tête reste vide. */
@Composable
internal fun Header(scene: IseeScene, onPicture: Boolean, modifier: Modifier = Modifier) {
    if (onPicture) return
    Row(modifier.statusBarsPadding().padding(horizontal = 22.dp, vertical = 10.dp), horizontalArrangement = Arrangement.End) {
        StatusChip(scene, onPicture)
    }
}

/** Pastille d'état : bleu à l'écoute, ambre quand iSee parle, discrète sinon. */
@Composable
internal fun StatusChip(scene: IseeScene, onPicture: Boolean) {
    val (bg, fg) = when (scene) {
        IseeScene.LISTENING -> Color(IseePortrait.YOU) to Color.White
        IseeScene.VOICE -> Color(IseePortrait.SEE) to Color(IseePortrait.INK)
        else -> (if (onPicture) Color(0x38FFFFFF) else Color.White) to (if (onPicture) Color.White else Color(IseePortrait.SUB))
    }
    Text(
        chipLabel(scene),
        color = fg,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.3.sp,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Le mot de la pastille, en anglais comme le reste de l'interface. */
internal fun chipLabel(scene: IseeScene): String = when (scene) {
    IseeScene.LISTENING -> "LISTENING"
    IseeScene.VISION -> "SEEING"
    IseeScene.THINKING -> "THINKING"
    IseeScene.VOICE -> "SPEAKING"
    IseeScene.MEMORY -> "MEMORY"
    IseeScene.ANSWERED, IseeScene.READY -> "READY"
}
