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
 * Section « Depth (beta) » des Réglages : moteur embarqué DA2 Small par
 * ONNX (100 % sur le téléphone, 0 GPU Modal) + bouton de banc (5 inférences
 * sur image synthétique, verdict en bas de page et dans logcat `DepthBench`).
 * Toujours active (DA2, seuil < 200 ms tenu) — plus d'option Coupée.
 */
@Composable
fun DepthSettings(onTestDepth: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Depth (beta)",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Monocular depth map estimated on the phone " +
                "(Depth Anything V2 Small), always on. Model downloaded once at the " +
                "first run (~26 MB), 100% local inference after that — " +
                "no Modal GPU.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        BigButton("Test depth (5 inferences)", true, AppColors.surfaceHigh, onTestDepth)
    }
}
