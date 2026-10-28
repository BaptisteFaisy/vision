package dev.sam3vision.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp

/** Labeled field reused by the sections; secret fields are masked but revealable with a tap. */
@Composable
internal fun LabeledField(label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = SettingsLight.muted, fontSize = 12.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = !secret,
            visualTransformation = if (secret && !shown) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailingIcon = if (!secret) null else {
                {
                    TextButton(onClick = { shown = !shown }) {
                        Text(if (shown) "Hide" else "Show", fontSize = 11.sp, color = SettingsLight.text)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SettingsLight.text,
                unfocusedTextColor = SettingsLight.text,
                cursorColor = SettingsLight.text,
                focusedBorderColor = SettingsLight.text,
                unfocusedBorderColor = SettingsLight.text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
