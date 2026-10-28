package dev.sam3vision.app.voicenav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.core.nav.DestinationQuery

/**
 * Saisie clavier de secours : quand la dictée échoue (bruit, malentendant),
 * l'utilisateur tape l'adresse au lieu de la dicter.
 *
 * Replié au départ (un simple lien sous « Dicter une autre destination »),
 * le champ s'ouvre au clavier à l'appui, se valide par « Valider » ou la
 * touche Terminé, puis se referme vide. [onSubmitText] reçoit le texte brut
 * saisi — c'est l'appelant ([VoiceNavViewModel.submitTypedDestination], câblé
 * par l'intégrateur) qui le convertit via [typedDestinationQuery].
 */
@Composable
fun DestinationTextField(
    onSubmitText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    if (!expanded) {
        TextButton(
            onClick = { expanded = true },
            modifier = modifier.fillMaxWidth(),
        ) {
            Text("Saisir une adresse au clavier", fontSize = 18.sp, color = AppColors.primary)
        }
        return
    }
    var value by remember { mutableStateOf("") }
    fun submit() {
        if (typedDestinationQuery(value) == null) return
        onSubmitText(value)
        value = ""
        expanded = false
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AddressField(value, onValueChange = { value = it }, onDone = ::submit)
        Button(
            onClick = ::submit,
            enabled = value.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.success),
        ) {
            Text("Valider", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Champ adresse gros texte (thème sombre [AppColors]), une seule ligne. */
@Composable
private fun AddressField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Adresse", color = AppColors.textMuted, fontSize = 18.sp) },
        placeholder = { Text("Ex. : gare de Lyon", color = AppColors.textMuted, fontSize = 20.sp) },
        singleLine = true,
        textStyle = TextStyle(color = AppColors.text, fontSize = 22.sp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = AppColors.text,
            unfocusedTextColor = AppColors.text,
            cursorColor = AppColors.primary,
            focusedBorderColor = AppColors.primary,
            unfocusedBorderColor = AppColors.textMuted,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Pseudo-transcript dictée équivalent au texte [raw] saisi au clavier :
 * « aller à … », reconnu par le routeur existant ([VoiceNavSession] intacte,
 * aucun parseur ajouté). Normalisation partagée ([DestinationQuery]) : espaces
 * rognés et réduits, ponctuation des bords retirée, `null` si rien à dire.
 */
fun typedDestinationQuery(raw: String): String? {
    val clean = DestinationQuery.normalize(raw).getOrNull() ?: return null
    return "aller à $clean"
}
