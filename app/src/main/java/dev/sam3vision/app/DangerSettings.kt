package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Section « Danger: approach » des Réglages : YOLO11n-nano détecte les
 * personnes à chaque frame, le tracker les relie entre frames, et une personne
 * qui grossit vite au centre du champ déclenche l'alerte (logcat + statut —
 * la voix viendra plus tard). Inférence 100 % sur le téléphone.
 */
@Composable
fun DangerSettings(onTestDanger: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Danger: approach (YOLO11n)",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "People detected on every frame, tracks across frames, alert " +
                "when someone comes toward you (time-to-contact in seconds). " +
                "The bench measures 5 inferences on a synthetic image.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        BigButton("Test YOLO11n (5 inferences)", true, AppColors.surfaceHigh, onTestDanger)
    }
}
