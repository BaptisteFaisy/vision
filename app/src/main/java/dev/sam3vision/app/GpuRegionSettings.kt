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
import dev.sam3vision.core.settings.SamRegion
import dev.sam3vision.core.settings.Settings

/**
 * Section « GPU SAM — région » des Réglages : mode Europe (eu) ou **mode US** (us).
 * Ne change que l'endpoint appelé ; allumer la carte reste une décision explicite
 * (`~/bin/sam3-gpu on` / `~/bin/sam3-gpu-us on`) et jamais deux GPU à la fois.
 */
@Composable
fun GpuRegionSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "GPU SAM 3.1 — région",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        RegionButtons(draft, onChange)
        Text(
            "Mode US : la carte tourne aux États-Unis (us-east) au lieu de l'Europe. " +
                "Une seule GPU est autorisée à la fois : allume l'app de la région choisie " +
                "(~/bin/sam3-gpu on · ~/bin/sam3-gpu-us on) et éteins l'autre.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        Text(
            "Endpoint actif : ${draft.samEndpoint()}",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
    }
}

/**
 * Un bouton **pleine largeur** par région, la région active en couleur pleine.
 * Mesuré sur le téléphone : côte à côte (0,47), le libellé était coupé en
 * « US · États- » — illisible pour un réglage qui coûte de l'argent.
 */
@Composable
private fun RegionButtons(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SamRegion.entries.forEach { region ->
            val active = draft.samRegion == region
            BigButton(
                label = region.label,
                enabled = true,
                color = if (active) AppColors.primary else AppColors.surfaceHigh,
                onClick = { onChange(pickRegion(draft, region)) },
            )
        }
    }
}

/**
 * Choisir une région efface une URL SAM qui n'était que le preset d'une région
 * (injectée par le build ou héritée d'une ancienne version) : sans ça, elle
 * continuerait de gagner sur le choix. Une URL saisie à la main est conservée.
 */
internal fun pickRegion(draft: Settings, region: SamRegion): Settings = draft.copy(
    samRegion = region,
    samBaseUrl = if (SamRegion.isPreset(draft.samBaseUrl)) "" else draft.samBaseUrl,
)