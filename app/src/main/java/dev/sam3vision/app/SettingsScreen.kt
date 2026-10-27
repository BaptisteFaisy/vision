package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.app.glasses.GlassesSection
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
    onSave: (Settings) -> Unit,
    onTestLlm: () -> Unit,
    onTestSam: () -> Unit,
    onTestAsr: () -> Unit,
    onTestDepth: () -> Unit,
    onSpeak: (String) -> Unit,
    onStopVoice: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenClassic: () -> Unit,
    onOpenGestures: () -> Unit,
    onPickRole: (Settings) -> Unit,
    onStartTracking: (Settings) -> Unit,
    onStopTracking: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf(settings) }
    Column(settingsLightScroll()) {
        SettingsHeader(onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            UserSection(draft = draft, onPickRole = onPickRole, onChange = { draft = it })
            MetaGlassesSection(glasses = glasses)
            TechnicalSection(
                draft = draft,
                onChange = { draft = it },
                llmConfigured = llmConfigured,
                speaking = speaking,
                onSpeak = onSpeak,
                onStopVoice = onStopVoice,
                onSave = onSave,
                onTestLlm = onTestLlm,
                onTestSam = onTestSam,
                onTestAsr = onTestAsr,
                onTestDepth = onTestDepth,
                onStartTracking = onStartTracking,
                onStopTracking = onStopTracking,
                onOpenLive = onOpenLive,
                onOpenClassic = onOpenClassic,
                onOpenGestures = onOpenGestures,
                statusLine = statusLine,
            )
        }
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
        Text(
            "No key is ever written into the code.",
            color = SettingsLight.muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** Uppercase section title of the page. */
@Composable
private fun SectionTitle(text: String) {
    Text(text, color = SettingsLight.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

/** THE USER: who holds this phone. Role + invite, then one glass button saves and opens the right home. */
@Composable
private fun UserSection(
    draft: Settings,
    onPickRole: (Settings) -> Unit,
    onChange: (Settings) -> Unit,
) {
    val isCaregiver = draft.caregiverRole == CaregiverRole.Caregiver
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("THE USER")
        Text(
            "This phone is held by a blind person (iSee opens at launch), " +
                "or by a caregiver (the tracking map opens).",
            color = SettingsLight.muted,
            fontSize = 12.sp,
        )
        GlassButton(
            label = "Blind person (default)",
            enabled = true,
            primary = !isCaregiver,
            onClick = { onChange(draft.copy(caregiverRole = CaregiverRole.Blind)) },
        )
        GlassButton(
            label = "Caregiver",
            enabled = true,
            primary = isCaregiver,
            onClick = { onChange(draft.copy(caregiverRole = CaregiverRole.Caregiver)) },
        )
        if (isCaregiver) CaregiverInviteBlock(draft, onChange)
        GlassButton(
            label = if (isCaregiver) "Save & open caregiver map" else "Save & back to iSee",
            enabled = !isCaregiver || draft.caregiverInvite.isNotBlank(),
            primary = true,
            onClick = { onPickRole(draft) },
        )
    }
}

/** META GLASSES: the Ray-Ban Meta block (its tested French strings stay, only the frame is English). */
@Composable
private fun MetaGlassesSection(glasses: GlassesController) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("META GLASSES")
        Text(
            "Ray-Ban Meta glasses, paired through the Meta AI app.",
            color = SettingsLight.muted,
            fontSize = 12.sp,
        )
        Column(
            Modifier.fillMaxWidth()
                .background(AppColors.surface, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            GlassesBlock(glasses)
        }
    }
}

/** The invite link or code, pasted by the caregiver (required before the map opens). */
@Composable
private fun CaregiverInviteBlock(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LabeledField("Invite link or code", draft.caregiverInvite, false) {
            onChange(draft.copy(caregiverInvite = it))
        }
        Text(
            "Paste the link or the code the blind person sent you — " +
                "the caregiver map needs it to open the right tracking.",
            color = SettingsLight.muted,
            fontSize = 11.sp,
        )
        if (draft.caregiverInvite.isBlank()) {
            Text(
                "Add the invite first: without it the map cannot open.",
                color = SettingsLight.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
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

/** Section TECHNICAL SETTINGS: everything the blind user never touches. */
@Composable
private fun TechnicalSection(
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
    onOpenLive: () -> Unit,
    onOpenClassic: () -> Unit,
    onOpenGestures: () -> Unit,
    statusLine: String?,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("TECHNICAL SETTINGS")
        BackendFields(draft) { onChange(it) }
        ObjectsSettings(draft) { onChange(it) }
        GpuRegionSettings(draft) { onChange(it) }
        DistanceSettings(draft) { onChange(it) }
        PredictionSettings(draft) { onChange(it) }
        LocalOverlaySettings(draft) { onChange(it) }
        NavigationToggle(draft) { onChange(it) }
        DepthSettings(draft, { onChange(it) }, onTestDepth)
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
        GestureHelp(onOpenGestures)
        ClassicScreens(onOpenLive, onOpenClassic)
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
}

/** « Parent tracking »: the site link sent by WhatsApp + start/stop of the permanent tracking. */
@Composable
private fun ParentShareSettings(
    draft: Settings,
    onStartTracking: (Settings) -> Unit,
    onStopTracking: () -> Unit,
    onChange: (Settings) -> Unit,
) {
    val trackingActive by ShareTracking.running.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Parent tracking",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        LabeledField("Tracking site link", draft.parentSiteUrl, false) {
            onChange(draft.copy(parentSiteUrl = it))
        }
        SwitchRow("Send position + objects to the site during Direct", draft.shareEnabled) {
            onChange(draft.copy(shareEnabled = it))
        }
        LabeledField("Stream id (?share=, one per tracked relative)", draft.shareId, false) {
            onChange(draft.copy(shareId = it))
        }
        LabeledField("Relay write key (empty = keyless relay)", draft.shareWriteKey, true) {
            onChange(draft.copy(shareWriteKey = it))
        }
        Text(
            "Share sends the site link to a relative over WhatsApp, then starts the " +
                "permanent tracking (a notification at the top of the screen). The " +
                "« Stop tracking » button, below or in the notification, stops it.",
            color = SettingsLight.muted,
            fontSize = 11.sp,
        )
        if (trackingActive) {
            GlassButton("Stop tracking", true, false, onStopTracking)
        } else {
            GlassButton("\uD83D\uDCE4 Share tracking", true, true, onClick = { onStartTracking(draft) })
        }
        Text(
            "In a pocket (screen off), only the position keeps being sent every 15 s " +
                "for the battery; screen on, the full sharing stays at the 2 s pace. " +
                "Circled objects reappear as soon as Direct reopens.",
            color = SettingsLight.muted,
            fontSize = 11.sp,
        )
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
    GlassButton("Save", true, true, onClick = { onSave(draft) })
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
        Text(
            "Tap = video, long press = speak (the filmed photo goes with it). " +
                "On the iSee home, these gestures work anywhere on the screen.",
            color = SettingsLight.muted,
            fontSize = 11.sp,
        )
    }
}

/** Door to the legacy screens (**Direct**, **classic home**), unreachable from the gesture-only iSee home. */
@Composable
private fun ClassicScreens(onOpenLive: () -> Unit, onOpenClassic: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Other screens",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        GlassButton("Direct mode (continuous video)", true, true, onOpenLive)
        GlassButton("Classic home (photo + analysis)", true, false, onOpenClassic)
        Text(
            "Direct speaks aloud the position of detected objects; the classic home " +
                "keeps the photo, the SAM masks and the per-image chat.",
            color = SettingsLight.muted,
            fontSize = 11.sp,
        )
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
        Text(
            "Off by default: the perceived-latency measurement decides, not the " +
                "other way round. The displayed distance is never predicted.",
            color = SettingsLight.muted,
            fontSize = 11.sp,
        )
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
        Text(
            "Without a Meta key, dictation uses the ElevenLabs key above (Scribe). " +
                "Long press anywhere = dictation announced in big characters.",
            color = SettingsLight.muted,
            fontSize = 11.sp,
        )
    }
}

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
                        Text(if (shown) "Hide" else "Show", fontSize = 11.sp)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SettingsLight.text,
                unfocusedTextColor = SettingsLight.text,
                cursorColor = SettingsLight.primary,
                focusedBorderColor = SettingsLight.primary,
                unfocusedBorderColor = SettingsLight.border,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
