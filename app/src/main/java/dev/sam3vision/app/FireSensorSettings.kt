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
import dev.sam3vision.core.settings.Settings

/**
 * Réglages techniques du pilotage capteur du feu (`docs/RECETTE-FEU-EXPOSITION.md`) :
 * **un seul interrupteur**, explicite et **persistant** (`Settings.fireExposureCompensation`),
 * **coupé par défaut**. C'est la seule activation utilisateur du lot caméra de l'audit A6.
 *
 * Effet, borné par construction (`core/exposure/ClipGovernor`) : quand le crop de la lampe
 * est écrasé en blanc (≥ 2 % de pixels « canal max ≥ 250 et saturation < 0,15 »), un cran
 * de compensation descend, 700 ms de convergence, puis un second au plus — **jamais plus de
 * deux crans sous 0**, jamais au-dessus. Retour à l'automatisme dès que le crop redevient
 * sain, dès que sa **médiane** passe sous le plancher nocturne, et 3 s après la disparition
 * du feu. **Aucun verrou AE** (mesuré nuisible le 01/11/2026), aucun verrou AWB, aucune
 * région de mesure : sans mesure de feu, ce réglage ne fait rien.
 *
 * Les deux autres leviers du lot (verrou AWB, région AE) restent **non exposés** : leur gain
 * n'est pas mesuré sur appareil et un HAL peut les ignorer ou figer une teinte.
 */
@Composable
internal fun FireSensorSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Fire exposure",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow("Compensate exposure on the light box (bounded)", draft.fireExposureCompensation) {
            onChange(draft.copy(fireExposureCompensation = it))
        }
        Text(
            "At most −2 EV, only while the lamp is blown, never locked, back to auto when the crop " +
                "turns dark or 3 s after the light is gone. Off by default; applies to the next frame.",
            color = SettingsLight.muted,
            fontSize = 12.sp,
        )
    }
}
