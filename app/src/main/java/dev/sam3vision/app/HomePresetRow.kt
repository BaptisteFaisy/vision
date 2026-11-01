package dev.sam3vision.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sam3vision.core.live.HomeModes
import dev.sam3vision.core.live.LivePrompts

/**
 * Pastilles de presets du direct : Rue (liste unique) + les trois presets
 * maison. [prompts] sont les concepts suivis (l'état du direct) : la pastille
 * active est verte, les autres neutres. Un tap relance le direct sur le preset
 * ([onPreset]), sans quitter l'écran — comme le champ « Follow », par le même
 * chemin (`restartWith`, `LiveScreen`).
 */
@Composable
fun HomePresetRow(prompts: List<String>, onPreset: (List<String>) -> Unit) {
    val maison = HomeModes.activeId(prompts)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), Arrangement.spacedBy(8.dp)) {
        Chip(
            "Street",
            if (maison == null && prompts == LivePrompts.default) AppColors.success else AppColors.surfaceHigh,
            { onPreset(LivePrompts.default) },
        )
        HomeModes.ALL.forEach { preset ->
            Chip(
                preset.label,
                if (maison == preset.id) AppColors.success else AppColors.surfaceHigh,
                { onPreset(preset.prompts) },
            )
        }
    }
}
