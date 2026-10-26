package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.settings.Settings
import java.util.Locale

/**
 * Section « Distances » des réglages : bascule de l'overlay du direct et champ de
 * vision de repli (`0` = lecture directe du capteur, la voie normale).
 */
@Composable
internal fun DistanceSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Distances",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        SwitchRow("Distances sur le direct", draft.distanceOverlayEnabled) {
            onChange(draft.copy(distanceOverlayEnabled = it))
        }
        Text(
            "Une distance par objet suivi, estimée par sténopé à partir de sa hauteur réelle " +
                "connue (personne 1,70 m, voiture 1,45 m…). Précision ±15-20 %, calcul sur le " +
                "téléphone : aucun GPU, aucune requête.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        LabeledField(
            label = "Champ de vision caméra (°, 0 = lecture du capteur)",
            value = fovText(draft.cameraFovDeg),
            secret = false,
        ) { text -> onChange(draft.copy(cameraFovDeg = parseFov(text))) }
    }
}

/** Valeur affichée du champ de vision : vide quand c'est le capteur qui décide. */
internal fun fovText(deg: Float): String =
    if (deg > 0f) String.format(Locale.FRANCE, "%.1f", deg).removeSuffix(",0") else ""

/** Saisie tolérante du champ de vision : virgule acceptée, bornes de [LensSpec], 0 = auto. */
internal fun parseFov(text: String): Float {
    val value = text.replace(',', '.').trim().toFloatOrNull() ?: return 0f
    return if (value <= 0f) 0f else value.coerceIn(LensSpec.FOV_MIN, LensSpec.FOV_MAX)
}