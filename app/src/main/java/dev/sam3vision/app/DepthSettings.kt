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
import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.settings.Settings

/**
 * Section « Profondeur (bêta) » des Réglages : moteur embarqué (DA2 Small par
 * ONNX, MiDaS small par LiteRT — 100 % sur le téléphone, 0 GPU Modal) + bouton
 * de banc (5 inférences sur image synthétique, verdict en bas de page et dans
 * logcat `DepthBench`). Coupée par défaut : le banc valide la latence d'abord.
 */
@Composable
fun DepthSettings(draft: Settings, onChange: (Settings) -> Unit, onTestDepth: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Profondeur (bêta)",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        DepthBackend.entries.forEach { backend ->
            DepthBackendButton(draft, backend, onChange)
        }
        Text(
            "Carte de profondeur monoculaire estimée sur le téléphone : " +
                "Depth Anything V2 Small (26 Mo, le plus précis) ou MiDaS small " +
                "(repli léger). Ni GPU Modal, ni réseau — le modèle tourne ici.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        BigButton("Tester la profondeur (5 inférences)", true, AppColors.surfaceHigh, onTestDepth)
    }
}

/** Un bouton pleine largeur par moteur, le moteur actif en couleur pleine. */
@Composable
private fun DepthBackendButton(draft: Settings, backend: DepthBackend, onChange: (Settings) -> Unit) {
    var color = AppColors.surfaceHigh
    if (draft.depthBackend == backend) {
        color = AppColors.primary
    }
    BigButton(
        label = backend.label,
        enabled = true,
        color = color,
        onClick = { onChange(draft.copy(depthBackend = backend)) },
    )
}
