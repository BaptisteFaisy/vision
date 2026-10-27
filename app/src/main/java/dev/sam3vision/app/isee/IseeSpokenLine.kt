package dev.sam3vision.app.isee

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.iseedesign.IseePortrait
import dev.sam3vision.core.iseedesign.SpokenCaption

/**
 * Seulement ce qui est dit maintenant, en gros, sous la boule. Les mots s'allument au fil de la
 * voix : cinq mots au plus, deux lignes, puis le passage suivant. Hors parole : rien.
 */
@Composable
internal fun BoxWithConstraintsScope.SpokenLine(caption: SpokenCaption?, fromUser: Boolean, top: Dp, compact: Boolean) {
    if (caption == null) return
    val room = (maxHeight - top - navInsetDp()).coerceAtLeast(0.dp)
    Column(
        Modifier.align(Alignment.TopCenter).offset(y = top).padding(horizontal = 24.dp).heightIn(max = room),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (fromUser) "YOU" else "ISEE",
            color = Color(if (fromUser) IseePortrait.YOU_INK else IseePortrait.SEE_INK),
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        CaptionText(caption, fromUser, compact)
    }
}

/** Ajustement pour un mot très long ou une grande police système : aucune ellipse. */
@Composable
private fun CaptionText(caption: SpokenCaption, fromUser: Boolean, compact: Boolean) {
    var fontSize by remember(caption.text, compact) { mutableStateOf(if (compact) 30f else 38f) }
    Text(
        spokenText(caption.text, caption.shown, fromUser),
        fontSize = fontSize.sp,
        lineHeight = (fontSize + 3f).sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.7).sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        onTextLayout = { if (it.hasVisualOverflow && fontSize > 12f) fontSize -= 2f },
    )
}

/**
 * La phrase colorée mot à mot. [shown] mots sont dits : le dernier dit est dans la couleur de
 * qui parle, les précédents en noir, les suivants en gris très clair. Pur, testable sur JVM.
 */
internal fun spokenText(text: String, shown: Int, fromUser: Boolean): AnnotatedString = buildAnnotatedString {
    val words = text.split(" ").filter { it.isNotEmpty() }
    val now = if (fromUser) IseePortrait.YOU_INK else IseePortrait.SEE_INK
    words.forEachIndexed { i, w ->
        val colour = when {
            shown >= words.size -> IseePortrait.INK
            i < shown - 1 -> IseePortrait.INK
            i == shown - 1 -> now
            else -> IseePortrait.DIM
        }
        withStyle(SpanStyle(color = Color(colour))) { append(w) }
        if (i < words.size - 1) append(" ")
    }
}
