package dev.sam3vision.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.sam3vision.core.share.validatedShareLink

private val CaregiverInk = Color(0xFF0B1220)

/** Le caregiver peut importer le lien d'un autre appareil, et le remplacer à tout moment. */
@Composable
internal fun CaregiverLinkDialog(link: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by remember(link) { mutableStateOf(link) }
    val valid = validatedShareLink(draft)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        titleContentColor = CaregiverInk,
        textContentColor = CaregiverInk,
        title = { Text("Lien de ton proche") },
        text = { CaregiverLinkField(draft) { draft = it } },
        confirmButton = {
            TextButton(onClick = { valid?.let(onSave) }, enabled = valid != null) {
                Text("Suivre", color = if (valid != null) CaregiverInk else Color.Gray)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Annuler", color = CaregiverInk) } },
    )
}

@Composable
private fun CaregiverLinkField(draft: String, onChange: (String) -> Unit) {
    Column {
        Text("Colle le lien reçu par message depuis l'app de ton proche.")
        OutlinedTextField(
            value = draft,
            onValueChange = onChange,
            label = { Text("Lien de suivi (?share=…)") },
            singleLine = true,
            isError = draft.isNotBlank() && validatedShareLink(draft) == null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = CaregiverInk, unfocusedTextColor = CaregiverInk,
                focusedBorderColor = CaregiverInk, unfocusedBorderColor = Color.Gray,
                focusedLabelColor = CaregiverInk, unfocusedLabelColor = Color.Gray,
                cursorColor = CaregiverInk,
            ),
        )
    }
}
