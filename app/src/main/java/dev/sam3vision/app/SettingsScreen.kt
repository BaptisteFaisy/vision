package dev.sam3vision.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.app.glasses.GlassesSection
import dev.sam3vision.core.settings.Settings

/**
 * Réglages : LLM (base URL, clé, modèle) + endpoint SAM + région de la GPU
 * (mode Europe / **mode US**) + section
 * « Distances » (overlay du direct, champ de vision) + section « Voix ElevenLabs »
 * (clé, voix, lecture automatique, test) + section « Dictée Meta » (base URL, clé,
 * modèle ASR, test) + bloc « Lunettes Ray-Ban Meta » (enregistrement auprès de
 * Meta AI, session, capture photo, lunettes simulées en build debug).
 * Chaque moteur a son bouton de test.
 */
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
    onSpeak: (String) -> Unit,
    onStopVoice: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenClassic: () -> Unit,
    onOpenGestures: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf(settings) }
    Column(settingsScroll()) {
        ScreenHeader("Réglages du backend", "Aucune clé n'est écrite dans le code.", onBack, null)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackendFields(draft) { draft = it }
            ObjectsSettings(draft) { draft = it }
            GpuRegionSettings(draft) { draft = it }
            DistanceSettings(draft) { draft = it }
            LocalOverlaySettings(draft) { draft = it }
            TtsSettings(
                draft = draft,
                speaking = speaking,
                onChange = { draft = it },
                onSpeak = onSpeak,
                onStop = onStopVoice,
            )
            DictationSettings(draft) { draft = it }
            SettingsActions(draft, llmConfigured, onSave, onTestLlm, onTestSam, onTestAsr)
            GestureHelp(onOpenGestures)
            ClassicScreens(onOpenLive, onOpenClassic)
            Diagnostics(statusLine)
            GlassesBlock(glasses)
        }
    }
}

/**
 * Le scroll commun de l'écran : fond sombre, barres système prises en compte, clavier
 * compris. Extrait pour que [SettingsScreen] reste sous la garde des 50 lignes.
 */
@Composable
private fun settingsScroll(): Modifier = Modifier
    .fillMaxSize()
    .background(AppColors.background)
    .statusBarsPadding()
    .navigationBarsPadding()
    .imePadding()
    .verticalScroll(rememberScrollState())

/** Bas de page : version de ce build, puis dernière ligne de statut. */
@Composable
private fun Diagnostics(statusLine: String?) {
    BuildInfoBlock()
    statusLine?.let { StatusBanner(it, AppColors.surfaceHigh) }
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

/** Section « Backend » : LLM (base URL, clé, modèle) et endpoint SAM 3.1 optionnel. */
@Composable
private fun BackendFields(draft: Settings, onChange: (Settings) -> Unit) {
    LabeledField("Base URL LLM", draft.llmBaseUrl, false) { onChange(draft.copy(llmBaseUrl = it)) }
    LabeledField("Clé API LLM", draft.llmApiKey, true) { onChange(draft.copy(llmApiKey = it)) }
    LabeledField("Modèle LLM (vision)", draft.llmModel, false) { onChange(draft.copy(llmModel = it)) }
    LabeledField("Base URL SAM 3.1 (vide = région choisie ci-dessous)", draft.samBaseUrl, false) {
        onChange(draft.copy(samBaseUrl = it))
    }
}

/** Enregistrement + un bouton de test par moteur (LLM, SAM, dictée Meta). */
@Composable
private fun SettingsActions(
    draft: Settings,
    llmConfigured: Boolean,
    onSave: (Settings) -> Unit,
    onTestLlm: () -> Unit,
    onTestSam: () -> Unit,
    onTestAsr: () -> Unit,
) {
    BigButton("Enregistrer", true, AppColors.primary, onClick = { onSave(draft) })
    BigButton("Tester le LLM", llmConfigured, AppColors.surfaceHigh, onTestLlm)
    BigButton("Tester SAM", draft.samConfigured(), AppColors.surfaceHigh, onTestSam)
    BigButton("Tester la dictée Meta", draft.asrConfigured, AppColors.surfaceHigh, onTestAsr)
}

/**
 * La page d'accueil iSee n'a **aucun bouton** : les gestes sont la seule interface,
 * et ils ne s'apprennent pas tout seuls. Cette porte rouvre à volonté l'onboarding
 * animé des trois gestes (tap, double tap, appui long) — y compris pour un utilisateur
 * qui a déjà vu l'écran une fois.
 */
@Composable
private fun GestureHelp(onOpenGestures: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Gestes de l'accueil",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        BigButton("Revoir les gestes", true, AppColors.surfaceHigh, onOpenGestures)
        Text(
            "Tap = vidéo, double tap = photo, appui long = parler. Sur l'accueil iSee, " +
                "ces gestes marchent n'importe où sur l'écran.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
    }
}

/**
 * Accès aux écrans hérités, absents de l'accueil iSee (gestes seulement, sans bouton) :
 * le **Direct** (flux SAM en continu, annonces de position) et l'**accueil classique**
 * (photo → analyse avec masques). Sans cette porte, ces écrans n'étaient atteignables
 * par aucun chemin depuis la refonte ihear.
 */
@Composable
private fun ClassicScreens(onOpenLive: () -> Unit, onOpenClassic: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Autres écrans",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        BigButton("Mode Direct (vidéo en continu)", true, AppColors.primary, onOpenLive)
        BigButton("Accueil classique (photo + analyse)", true, AppColors.surfaceHigh, onOpenClassic)
        Text(
            "Le Direct décrit à voix haute la position des objets détectés ; " +
                "l'accueil classique garde la photo, les masques SAM et le chat par image.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
    }
}

/** Section « Dictée Meta » : base URL, clé et modèle du moteur principal (repli ElevenLabs Scribe). */
@Composable
private fun DictationSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Dictée Meta Muse (repli automatique sur ElevenLabs Scribe",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        LabeledField("Dictée Meta — base URL (ASR)", draft.asrBaseUrl, false) {
            onChange(draft.copy(asrBaseUrl = it))
        }
        LabeledField("Dictée Meta — clé API", draft.asrApiKey, true) {
            onChange(draft.copy(asrApiKey = it))
        }
        LabeledField("Dictée Meta — modèle", draft.asrModel, false) { onChange(draft.copy(asrModel = it)) }
        Text(
            "Sans clé Meta, la dictée utilise la clé ElevenLabs ci-dessus (Scribe). " +
                "Appui long n'importe où = dictée annoncée en gros caractères.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
    }
}

/**
 * Champ libellé réutilisé par les réglages backend et la section voix.
 *
 * Les champs secrets sont masqués par défaut (clé sous les yeux d'un voisin,
 * capture d'écran) et **révélables** d'un appui : la clé InferHub ou ElevenLabs
 * embarquée au build doit pouvoir être relue et copiée depuis les Réglages.
 */
@Composable
internal fun LabeledField(label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = AppColors.textMuted, fontSize = 12.sp)
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
                        Text(if (shown) "Masquer" else "Afficher", fontSize = 11.sp)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
