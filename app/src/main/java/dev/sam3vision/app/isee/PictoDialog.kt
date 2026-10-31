package dev.sam3vision.app.isee

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.sam3vision.app.isee.vm.PictoPhase
import dev.sam3vision.app.isee.vm.PictoUiState

/**
 * La relecture aidant : le pictogramme généré, le nom de l'objet, et trois
 * choix — valider (le repère arme la détection), régénérer (avec le nom
 * comme indice), annuler (rien n'est gardé). Pendant la génération (~10 s),
 * la même boîte dit l'attente et ne propose rien (pas de double facture).
 */
@Composable
internal fun PictoDialog(state: PictoUiState, viewModel: IseeViewModel) {
    when (state.phase) {
        PictoPhase.Generating -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Pictogram") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Generating… (~10 s)")
                }
            },
            confirmButton = {},
        )
        PictoPhase.Review -> state.draftPng?.let { png ->
            var name by remember(png) { mutableStateOf(state.draftLabel) }
            val image = remember(png) {
                runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }.getOrNull()?.asImageBitmap()
            }
            AlertDialog(
                onDismissRequest = { viewModel.picto.dismissDraft() },
                title = { Text("Pictogram") },
                text = {
                    Column {
                        image?.let {
                            Image(it, contentDescription = "Suggested pictogram", Modifier.size(128.dp))
                        }
                        Text(
                            "This drawing will mark the object on the video. Recognizable?",
                            Modifier.padding(vertical = 8.dp),
                        )
                        TextField(value = name, onValueChange = { name = it }, label = { Text("Object name") })
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = { viewModel.picto.regenerateDraft(name) }) { Text("Regenerate") }
                        TextButton(onClick = { viewModel.picto.confirmDraft(name) }) { Text("Confirm") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.picto.dismissDraft() }) { Text("Cancel") }
                },
            )
        }
        PictoPhase.Idle, PictoPhase.Shooting -> Unit
    }
}
