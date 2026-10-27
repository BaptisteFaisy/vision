package dev.sam3vision.app.ihear

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.iseedesign.IseePortrait
import dev.sam3vision.core.iseedesign.IseeScene
import dev.sam3vision.core.iseedesign.wordsSpoken

/**
 * Seulement ce qui est dit maintenant, en gros, sous la boule. Les mots s'allument au fil de la
 * voix : le mot en cours dans la couleur de qui parle (bleu le user, ambre iSee), les mots déjà
 * dits en noir, ceux à venir en gris très clair. Hors parole, toute la phrase est en noir.
 */
@Composable
internal fun BoxWithConstraintsScope.SpokenLine(view: IseeSceneView, state: IhearUiState, top: Dp, compact: Boolean) {
    val speaking = view.scene == IseeScene.VOICE || view.scene == IseeScene.LISTENING
    val shown = rememberWordsShown(view.heading, speaking)
    Column(
        Modifier.align(Alignment.TopCenter).offset(y = top).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        view.label?.let {
            Text(
                it,
                color = Color(if (view.fromUser) IseePortrait.YOU_INK else IseePortrait.SEE_INK),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Text(
            spokenText(view.heading, if (speaking) shown else Int.MAX_VALUE, view.fromUser),
            fontSize = if (compact) 34.sp else 42.sp,
            lineHeight = if (compact) 36.sp else 44.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1.2).sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Combien de mots sont dits : l'horloge démarre quand la phrase commence à être parlée. */
@Composable
private fun rememberWordsShown(text: String, speaking: Boolean): Int {
    var elapsed by remember(text) { mutableLongStateOf(0L) }
    LaunchedEffect(text, speaking) {
        if (!speaking) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { elapsed = it - start }
    }
    return wordsSpoken(text, elapsed, null)
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
