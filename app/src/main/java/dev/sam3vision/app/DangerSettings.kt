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
 * Section « Danger : approche » des Réglages : YOLO11n-nano détecte les
 * personnes à chaque frame, le tracker les relie entre frames, et une personne
 * qui grossit vite au centre du champ déclenche l'alerte (logcat + statut —
 * la voix viendra plus tard). Inférence 100 % sur le téléphone.
 */
@Composable
fun DangerSettings(onTestDanger: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Danger : approche (YOLO11n)",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Personnes détectées à chaque frame, pistes entre frames, alerte " +
                "quand quelqu'un vient vers toi (temps-avant-contact en secondes). " +
                "Le banc mesure 5 inférences sur image synthétique.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        BigButton("Tester YOLO11n (5 inférences)", true, AppColors.surfaceHigh, onTestDanger)
    }
}
