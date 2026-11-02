package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.live.FireSensorFlags

/**
 * Réglages techniques du pilotage capteur du feu (`docs/RECETTE-FEU-EXPOSITION.md`) :
 * deux drapeaux **runtime**, non persistés — ils retombent à leur défaut au redémarrage,
 * par prudence, et s'appliquent dès la frame suivante (aucun rebind de caméra).
 *
 *  - « EV on the light box » : gouverneur d'exposition sur le crop de la lampe
 *    (2 crans au plus, rend la main après 3 s sans feu). Allumé par défaut.
 *  - « AWB lock + AE region » : verrou de teinte temporisé et région AE à deux points.
 *    Éteint par défaut, le temps de la recette : c'est le seul levier qu'un HAL peut
 *    ignorer ou qui peut figer une teinte.
 */
@Composable
internal fun FireSensorSettings() {
    var ev by remember { mutableStateOf(FireSensorFlags.evOnLightBox) }
    var awb by remember { mutableStateOf(FireSensorFlags.awbLockAndMetering) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Fire exposure", color = SettingsLight.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        SwitchRow("EV on the light box", ev) {
            ev = it
            FireSensorFlags.evOnLightBox = it
        }
        SwitchRow("AWB lock + AE region (A/B)", awb) {
            awb = it
            FireSensorFlags.awbLockAndMetering = it
        }
        Text(
            "Runtime flags, reset on restart. Protocol: docs/RECETTE-FEU-EXPOSITION.md.",
            color = SettingsLight.muted,
            fontSize = 12.sp,
        )
    }
}
