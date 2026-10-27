package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.core.distance.DistanceText

/** Bandeau du haut : retour, réglages, mode de vision, ligne d'état du direct, erreurs. */
@Composable
fun LiveTopBar(
    state: LiveUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onApply: () -> Unit,
    failure: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleSam: () -> Unit,
    onToggleTracker: () -> Unit,
    onToggleLocal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Arrêter", AppColors.danger, onBack)
            Chip("Réglages", AppColors.surfaceHigh, onOpenSettings)
            SamModeChip(state.samEnabled, onToggleSam)
            TrackerChip(state.trackerEnabled, onToggleTracker)
            LocalModeChip(state, onToggleLocal)
            TransportChip(state.transport)
        }
        LiveStatusLines(state)
        state.spoken?.let { phrase ->
            Text(
                "🔊 $phrase",
                color = AppColors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .background(AppColors.primary.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        PromptRow(draft, onDraftChange, onApply)
        (failure ?: state.error)?.let { StatusBanner(it, AppColors.danger) }
    }
}

/**
 * Ligne d'état du direct, puis la **chronologie par étage sur une seule ligne** (jamais de
 * retour à la ligne) : rien affiché tant qu'aucun poste n'est mesuré.
 */
@Composable
private fun LiveStatusLines(state: LiveUiState) {
    Text(
        state.statusLine,
        color = AppColors.text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(top = 8.dp)
            .background(AppColors.surface.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
    if (state.chronologie.isBlank()) return
    Text(state.chronologie, color = AppColors.success, fontSize = 11.sp, maxLines = 1)
}

/**
 * Pastille du transport du direct : « Tracker » = sessions à ids stables (`/session` +
 * `/frame`), « WebSocket » = `/live`, « WebSocket KO » = socket tombé (aucun repli POST,
 * donc une chute se voit tout de suite).
 */
@Composable
fun TransportChip(transport: String) {
    if (transport.isBlank()) return
    val color = when (transport) {
        TRANSPORT_WS, TRANSPORT_TRACK -> AppColors.success
        TRANSPORT_WS_KO -> AppColors.danger
        else -> AppColors.surfaceHigh
    }
    Chip(transport, color, null)
}

/**
 * Pastille du transport : « Tracker » bascule le direct sur les sessions du tracker
 * (ids stables d'une frame à l'autre), « Live » le laisse sur `/live`.
 */
@Composable
fun TrackerChip(trackerEnabled: Boolean, onToggle: () -> Unit) {
    val label = if (trackerEnabled) "Tracker" else "Live"
    Chip(label, if (trackerEnabled) AppColors.success else AppColors.surfaceHigh, onToggle)
}

/**
 * Pastille du mode du direct : « Vision » = l'image part directement au LLM (sans GPU),
 * « SAM » = masques, objets, distances et journal. La pastille entière bascule le mode.
 */
@Composable
fun SamModeChip(samEnabled: Boolean, onToggle: () -> Unit) {
    val label = if (samEnabled) "SAM" else "Vision"
    Chip(label, if (samEnabled) AppColors.success else AppColors.primary, onToggle)
}

/**
 * Pastille du calque local : « Local ON » quand il peint, « Local OFF » quand il est
 * éteint, « Local … » quand il est allumé mais **inerte** (sans estimateur, keyframe
 * périmé, confiance basse). Même structure que [SamModeChip] : aucune primitive neuve,
 * et un calque inerte ne se présente jamais comme un gain.
 */
@Composable
fun LocalModeChip(state: LiveUiState, onToggle: () -> Unit) {
    val label = when {
        !state.localEnabled -> "Local OFF"
        state.localActive -> "Local ON"
        else -> "Local …"
    }
    Chip(label, if (state.localActive) AppColors.success else AppColors.surfaceHigh, onToggle)
}

/**
 * Bandeau du bas : labels vus, accès aux questions sur la mémoire du direct,
 * et analyse de la frame courante.
 */
@Composable
fun LiveBottomBar(
    state: LiveUiState,
    memoryOpen: Boolean,
    onToggleMemory: () -> Unit,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NarrationCard(state)
        val labels = objectChips(state)
        if (labels.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { Chip(it, AppColors.primary, null) }
            }
        }
        BigButton(
            if (memoryOpen) "Fermer les questions" else "Poser une question",
            true,
            AppColors.primary,
            onToggleMemory,
        )
        if (state.hasFrame) {
            Chip("Analyser la frame", AppColors.surfaceHigh, onOpenChat)
        }
    }
}

/**
 * Description approfondie du LLM sur la frame courante, mise à jour en continu.
 * L'ancienne description reste affichée pendant un échec : elle garde le contexte.
 */
@Composable
fun NarrationCard(state: LiveUiState, modifier: Modifier = Modifier) {
    val failed = state.narration.isBlank() && !state.narrationError.isNullOrBlank()
    val text = state.narration.ifBlank { state.narrationError.orEmpty() }
    when {
        text.isNotBlank() -> Text(
            text,
            color = AppColors.text,
            fontSize = 13.sp,
            maxLines = 3,
            modifier = modifier
                .fillMaxWidth()
                .background(
                    (if (failed) AppColors.danger else AppColors.surface).copy(alpha = 0.9f),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        state.narrationBusy -> Text(
            "Analyse LLM de la scène…",
            color = AppColors.textMuted,
            fontSize = 12.sp,
            modifier = modifier
                .fillMaxWidth()
                .background(AppColors.surface.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Pastilles du bas : une par concept suivi, avec sa distance la plus courte
 * (« personne 2,1 m ») quand elle est mesurée, sinon le concept seul.
 */
fun objectChips(state: LiveUiState, max: Int = 5): List<String> {
    val readings = state.distanceLayer?.readings.orEmpty()
    val measured = DistanceText.perLabel(readings)
    if (measured.isNotEmpty()) {
        return measured.take(max).map { (label, meters) -> "$label ${DistanceText.format(meters)}" }
    }
    return state.frame?.objects?.map { it.label }?.distinct()?.take(max).orEmpty()
}

/** Pastille : cliquable si [onClick] fourni, sinon simple étiquette. */
@Composable
fun Chip(label: String, color: Color, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(12.dp)
    val decorated = Modifier.background(color.copy(alpha = 0.9f), shape).padding(horizontal = 12.dp, vertical = 7.dp)
    if (onClick == null) {
        Box(decorated) { Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1) }
    } else {
        TextButton(onClick = onClick, modifier = decorated) {
            Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1)
        }
    }
}

/** Ligne d'édition des concepts suivis (virgules), appliquée sans quitter le direct. */
@Composable
fun PromptRow(draft: String, onChange: (String) -> Unit, onApply: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text("personne, table, sac…", color = AppColors.textMuted, fontSize = 13.sp) },
            modifier = Modifier.weight(1f),
        )
        Chip("Suivre", AppColors.primary, onApply)
    }
}
