package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.app.glasses.GlassesSection
import dev.sam3vision.core.net.GpuStatus
import dev.sam3vision.core.settings.CaregiverRole
import dev.sam3vision.core.settings.Settings

/** Settings in English, white page, black text: THE USER, META GLASSES, TECHNICAL SETTINGS. */
@Composable
fun SettingsScreen(
    settings: Settings,
    statusLine: String?,
    llmConfigured: Boolean,
    glasses: GlassesController,
    speaking: SpeakState,
    gpu: GpuStatus,
    onSave: (Settings) -> Unit,
    onTestLlm: () -> Unit,
    onTestSam: () -> Unit,
    onTestAsr: () -> Unit,
    onTestDepth: () -> Unit,
    onSpeak: (String) -> Unit,
    onStopVoice: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenClassic: () -> Unit,
    onOpenSegment: () -> Unit,
    onOpenGestures: () -> Unit,
    onPickRole: (Settings) -> Unit,
    onStartTracking: (Settings) -> Unit,
    onStopTracking: () -> Unit,
    onBack: () -> Unit,
) {
    // Suit le seed async : sans clé, le brouillon garderait une clé vide.
    var draft by remember(settings) { mutableStateOf(settings) }
    Column(settingsLightScroll()) {
        SettingsHeader(onBack = onBack)
        SettingsBody(
            draft = draft,
            onDraft = { draft = it },
            glasses = glasses,
            llmConfigured = llmConfigured,
            speaking = speaking,
            gpu = gpu,
            onSpeak = onSpeak,
            onStopVoice = onStopVoice,
            onSave = onSave,
            onTestLlm = onTestLlm,
            onTestSam = onTestSam,
            onTestAsr = onTestAsr,
            onTestDepth = onTestDepth,
            onPickRole = onPickRole,
            onStartTracking = onStartTracking,
            onStopTracking = onStopTracking,
            nav = NavCallbacks(onOpenLive, onOpenClassic, onOpenSegment, onOpenGestures),
            statusLine = statusLine,
        )
    }
}

/**
 * Milieu de page : prédiction, overlay, navigation et profondeur embarquée.
 * Extrait pour que l'appelant reste sous la garde des 50 lignes.
 */
@Composable
private fun PerceptionSettings(
    draft: Settings,
    onTestDepth: () -> Unit,
    onChange: (Settings) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PredictionSettings(draft) { onChange(it) }
        LocalOverlaySettings(draft) { onChange(it) }
        NavigationToggle(draft) { onChange(it) }
        DebugMapSettings(draft) { onChange(it) }
        DepthSettings(draft, onChange, onTestDepth)
    }
}

/** White scroll of the page: system bars and keyboard accounted for. */
@Composable
private fun settingsLightScroll(): Modifier = Modifier
    .fillMaxSize()
    .background(SettingsLight.background)
    .statusBarsPadding()
    .navigationBarsPadding()
    .imePadding()
    .verticalScroll(rememberScrollState())

/** Header: a chevron to go back, then the page title — no text button. */
@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChevronBack(onBack = onBack)
            Text(
                "Settings",
                color = SettingsLight.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Uppercase section title of the page. */
@Composable
private fun SectionTitle(text: String) {
    Text(text, color = SettingsLight.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

/** THE USER: who holds this phone. On/off role toggle, then the invite field
 * (caregiver) or the share button (blind person), then save opens the right home. */
@Composable
internal fun UserSection(
    draft: Settings,
    onPickRole: (Settings) -> Unit,
    onStartTracking: (Settings) -> Unit,
    onStopTracking: () -> Unit,
    onChange: (Settings) -> Unit,
) {
    val isCaregiver = draft.caregiverRole == CaregiverRole.Caregiver
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("THE USER")
        RoleSegmentedControl(
            isCaregiver = isCaregiver,
            onBlind = { onChange(draft.copy(caregiverRole = CaregiverRole.Blind)) },
            onCaregiver = { onChange(draft.copy(caregiverRole = CaregiverRole.Caregiver)) },
        )
        if (isCaregiver) {
            CaregiverInviteBlock(draft, onChange)
        } else {
            BlindShareBlock(draft, onStartTracking, onStopTracking)
        }
        GlassButton(
            label = if (isCaregiver) "Save & open caregiver map" else "Save & back to iSee",
            enabled = !isCaregiver || draft.caregiverInvite.isNotBlank(),
            primary = false,
            onClick = { onPickRole(draft) },
        )
    }
}

/** Blind / Caregiver reliés : un seul segment blanc à contour noir, pastille à coche sur le choix actif. */
@Composable
private fun RoleSegmentedControl(isCaregiver: Boolean, onBlind: () -> Unit, onCaregiver: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .border(1.dp, SettingsLight.text, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoleSegmentCell(
            label = "Blind person",
            selected = !isCaregiver,
            onClick = onBlind,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(width = 1.dp, height = 24.dp).background(SettingsLight.text))
        RoleSegmentCell(
            label = "Caregiver",
            selected = isCaregiver,
            onClick = onCaregiver,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RoleSegmentCell(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(18.dp)
                    .border(1.5.dp, SettingsLight.text, CircleShape)
                    .padding(1.dp),
            ) {
                Text("\u2713", color = SettingsLight.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(8.dp))
        }
        Text(
            label,
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** META GLASSES: the Ray-Ban Meta block on a white card (its tested French strings stay). */
@Composable
internal fun MetaGlassesSection(glasses: GlassesController) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("META GLASSES")
        Column(
            Modifier.fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(1.dp, SettingsLight.border, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            GlassesBlock(glasses)
        }
    }
}

/** Blind person: the WhatsApp share + start/stop of the permanent tracking. */
@Composable
private fun BlindShareBlock(
    draft: Settings,
    onStartTracking: (Settings) -> Unit,
    onStopTracking: () -> Unit,
) {
    val trackingActive by ShareTracking.running.collectAsState()
    if (trackingActive) {
        GlassButton("Stop tracking", true, false, onStopTracking)
    } else {
        GlassButton(
            "\uD83D\uDCE4 Share tracking",
            true,
            false,
            onClick = { onStartTracking(draft) },
        )
    }
}

/** The invite link or code, pasted by the caregiver (required before the map opens). */
@Composable
private fun CaregiverInviteBlock(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LabeledField("Invite link or code", draft.caregiverInvite, false) {
            onChange(draft.copy(caregiverInvite = it))
        }
    }
}

/** Bloc « Lunettes Ray-Ban Meta » : lit l'état du contrôleur et délègue ses actions. */
@Composable
private fun GlassesBlock(glasses: GlassesController) {
    val glassesState by glasses.state.collectAsState()
    GlassesSection(
        state = glassesState,
        debugBuild = BuildConfig.DEBUG,
        onRegister = glasses::register,
        onConnect = glasses::connect,
        onCapture = { glasses.capture() },
        onDisconnect = glasses::disconnect,
        onStartMock = glasses::startMockDevice,
        onMockPress = { glasses.mockPress() },
        onStopMock = glasses::stopMockDevice,
    )
}

/** Navigation sortante de la page, groupée pour la garde des 50 lignes. */
internal data class NavCallbacks(
    val onOpenLive: () -> Unit,
    val onOpenClassic: () -> Unit,
    val onOpenSegment: () -> Unit,
    val onOpenGestures: () -> Unit,
)

/** Section TECHNICAL SETTINGS: everything the blind user never touches. */
@Composable
internal fun TechnicalSection(
    draft: Settings,
    onChange: (Settings) -> Unit,
    llmConfigured: Boolean,
    speaking: SpeakState,
    onSpeak: (String) -> Unit,
    onStopVoice: () -> Unit,
    onSave: (Settings) -> Unit,
    onTestLlm: () -> Unit,
    onTestSam: () -> Unit,
    onTestAsr: () -> Unit,
    onTestDepth: () -> Unit,
    onStartTracking: (Settings) -> Unit,
    onStopTracking: () -> Unit,
    nav: NavCallbacks,
    statusLine: String?,
    gpu: GpuStatus,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("TECHNICAL SETTINGS")
        BackendFields(draft) { onChange(it) }
        ModelPinBlock(draft, statusLine)
        ObjectsSettings(draft) { onChange(it) }
        GpuRegionSettings(draft, gpu) { onChange(it) }
        DistanceSettings(draft) { onChange(it) }
        PerceptionSettings(draft, onTestDepth) { onChange(it) }
        TtsSettings(
            draft = draft,
            speaking = speaking,
            onChange = { onChange(it) },
            onSpeak = onSpeak,
            onStop = onStopVoice,
        )
        DictationSettings(draft) { onChange(it) }
        GoogleMapsSettings(draft, onChange)
        ParentShareSettings(draft, onStartTracking, onStopTracking) { onChange(it) }
        SettingsActions(draft, llmConfigured, onSave, onTestLlm, onTestSam, onTestAsr)
        GestureHelp(nav.onOpenGestures)
        ClassicScreens(nav.onOpenLive, nav.onOpenClassic, nav.onOpenSegment)
        Diagnostics(statusLine)
    }
}

/** Bottom of the page: version of this build, then the last status line. */
@Composable
private fun Diagnostics(statusLine: String?) {
    BuildInfoBlock()
    statusLine?.let { StatusBanner(it, SettingsLight.muted) }
}

/** « Backend » fields: LLM (base URL, key, model) and optional SAM 3.1 endpoint. */
@Composable
private fun BackendFields(draft: Settings, onChange: (Settings) -> Unit) {
    LabeledField("LLM base URL", draft.llmBaseUrl, false) { onChange(draft.copy(llmBaseUrl = it)) }
    LabeledField("LLM API key", draft.llmApiKey, true) { onChange(draft.copy(llmApiKey = it)) }
    LabeledField("LLM model (vision)", draft.llmModel, false) { onChange(draft.copy(llmModel = it)) }
    LabeledField("Direct narration key (empty = muted narration)", draft.liveLlmApiKey, true) {
        onChange(draft.copy(liveLlmApiKey = it))
    }
    LabeledField("SAM 3.1 base URL (empty = chosen region below)", draft.samBaseUrl, false) {
        onChange(draft.copy(samBaseUrl = it))
    }
    LabeledField("Valhalla route URL (empty = Maps guidance only)", draft.routeBaseUrl, false) {
        onChange(draft.copy(routeBaseUrl = it))
    }
}

/** Save + one test button per engine (LLM, SAM, Meta dictation). */
@Composable
private fun SettingsActions(
    draft: Settings,
    llmConfigured: Boolean,
    onSave: (Settings) -> Unit,
    onTestLlm: () -> Unit,
    onTestSam: () -> Unit,
    onTestAsr: () -> Unit,
) {
    GlassButton("Save", true, false, onClick = { onSave(draft) })
    GlassButton("Test LLM", llmConfigured, false, onTestLlm)
    GlassButton("Test SAM", draft.samConfigured(), false, onTestSam)
    GlassButton("Test Meta dictation", draft.asrConfigured, false, onTestAsr)
}

/** The iSee home has **no buttons**: this door replays the animated onboarding (tap, long press). */
@Composable
private fun GestureHelp(onOpenGestures: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Home gestures",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        GlassButton("Replay gestures", true, false, onOpenGestures)
    }
}

/** « Direct » box prediction (T8), off by default until the perceived latency is measured. */
@Composable
private fun PredictionSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Direct — box prediction (T8)",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow(
            "Boxes predicted between inferences (experimental, unmeasured)",
            draft.boxPredictionEnabled,
        ) { onChange(draft.copy(boxPredictionEnabled = it)) }
    }
}

/** « Meta dictation » section: base URL, key and model of the main engine (Meta Muse). */
@Composable
private fun DictationSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Meta dictation (automatic fallback to ElevenLabs Scribe)",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        LabeledField("Meta dictation — base URL (ASR)", draft.asrBaseUrl, false) {
            onChange(draft.copy(asrBaseUrl = it))
        }
        LabeledField("Meta dictation — API key", draft.asrApiKey, true) {
            onChange(draft.copy(asrApiKey = it))
        }
        LabeledField("Meta dictation — model", draft.asrModel, false) { onChange(draft.copy(asrModel = it)) }
    }
}
