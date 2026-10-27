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
import dev.sam3vision.core.settings.CaregiverRole
import dev.sam3vision.core.settings.Settings

/**
 * Réglages : rôle du téléphone (**Caregiver** : « Blind person » par défaut, ou la
 * page de suivi du proche) + LLM (base URL, clé, modèle) + endpoint SAM + région de la GPU
 * (mode Europe / **mode US**) + section « Distances » (overlay du direct, champ
 * de vision) + section « Voix ElevenLabs » (clé, voix, lecture automatique,
 * test) + section « Dictée Meta » (base URL, clé, modèle ASR, test) + section
 * « Suivi parent » (lien du site, boutons Partager / Arrêter le suivi
 * permanent) + bloc « Lunettes Ray-Ban Meta » (enregistrement auprès de
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
    onPickRole: (Settings) -> Unit,
    onStartTracking: (siteUrl: String, shareId: String) -> Unit,
    onStopTracking: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf(settings) }
    Column(settingsScroll()) {
        ScreenHeader("Réglages du backend", "Aucune clé n'est écrite dans le code.", onBack, null)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CaregiverSettings(draft, onPickRole)
            BackendFields(draft) { draft = it }
            ObjectsSettings(draft) { draft = it }
            GpuRegionSettings(draft) { draft = it }
            DistanceSettings(draft) { draft = it }
            PredictionSettings(draft) { draft = it }
            LocalOverlaySettings(draft) { draft = it }
            NavigationToggle(draft) { draft = it }
            TtsSettings(
                draft = draft,
                speaking = speaking,
                onChange = { draft = it },
                onSpeak = onSpeak,
                onStop = onStopVoice,
            )
            DictationSettings(draft) { draft = it }
            ParentShareSettings(draft, onStartTracking, onStopTracking) { draft = it }
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

/**
 * Section « Caregiver » : le rôle de ce téléphone. « Blind person » (défaut) ouvre
 * iSee ; « Caregiver » ouvre la carte du proche — la page du site partagée par
 * « Partager le suivi ». Le choix est enregistré immédiatement (il survit au
 * redémarrage) et ouvre la page quand c'est Caregiver.
 */
@Composable
private fun CaregiverSettings(draft: Settings, onPickRole: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Caregiver", color = AppColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Rôle de ce téléphone : « Blind person » (défaut) garde iSee à l'ouverture, " +
                "« Caregiver » ouvre la carte du proche — la même page que le lien partagé.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        BigButton(
            "Blind person (défaut)",
            true,
            roleColor(draft, CaregiverRole.Blind),
            onClick = { onPickRole(draft.copy(caregiverRole = CaregiverRole.Blind)) },
        )
        BigButton(
            "Caregiver",
            true,
            roleColor(draft, CaregiverRole.Caregiver),
            onClick = { onPickRole(draft.copy(caregiverRole = CaregiverRole.Caregiver)) },
        )
        Text(
            "Le rôle est enregistré tout de suite, sans passer par « Enregistrer » ; " +
                "choisir Caregiver ouvre la carte du proche.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
    }
}

/** Couleur du bouton de rôle : bleu pour le rôle actif, gris pour l'autre. */
private fun roleColor(draft: Settings, role: CaregiverRole) =
    if (draft.caregiverRole == role) AppColors.primary else AppColors.surfaceHigh

/** Section « Backend » : LLM (base URL, clé, modèle) et endpoint SAM 3.1 optionnel. */
@Composable
private fun BackendFields(draft: Settings, onChange: (Settings) -> Unit) {
    LabeledField("Base URL LLM", draft.llmBaseUrl, false) { onChange(draft.copy(llmBaseUrl = it)) }
    LabeledField("Clé API LLM", draft.llmApiKey, true) { onChange(draft.copy(llmApiKey = it)) }
    LabeledField("Modèle LLM (vision)", draft.llmModel, false) { onChange(draft.copy(llmModel = it)) }
    LabeledField("Clé narration Direct (vide = narration muette)", draft.liveLlmApiKey, true) {
        onChange(draft.copy(liveLlmApiKey = it))
    }
    LabeledField("Base URL SAM 3.1 (vide = région choisie ci-dessous)", draft.samBaseUrl, false) {
        onChange(draft.copy(samBaseUrl = it))
    }
}

/**
 * Section « Suivi parent » : lien du site envoyé par « Partager le suivi »
 * (message WhatsApp) + démarrage/arrêt du suivi permanent. Lien générique tant
 * qu'un seul proche est suivi (pas de cloisonnement par utilisateur pour l'instant).
 */
@Composable
private fun ParentShareSettings(
    draft: Settings,
    onStartTracking: (siteUrl: String, shareId: String) -> Unit,
    onStopTracking: () -> Unit,
    onChange: (Settings) -> Unit,
) {
    // L'état du service est lu ici : l'écran qui appelle reste sous la garde des 50 lignes.
    val trackingActive by ShareTracking.running.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Suivi parent", color = AppColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        LabeledField("Lien du site de suivi", draft.parentSiteUrl, false) {
            onChange(draft.copy(parentSiteUrl = it))
        }
        SwitchRow("Envoyer la position + objets au site pendant le Direct", draft.shareEnabled) {
            onChange(draft.copy(shareEnabled = it))
        }
        LabeledField("Identifiant du flux (?share=, un par proche suivi)", draft.shareId, false) {
            onChange(draft.copy(shareId = it))
        }
        LabeledField("Clé d'écriture du relais (vide = relais sans clé)", draft.shareWriteKey, true) {
            onChange(draft.copy(shareWriteKey = it))
        }
        Text(
            "Partager envoie le lien du site à un proche par WhatsApp, puis démarre le suivi " +
                "permanent (notification en haut de l'écran). Le bouton « Arrêter le suivi », " +
                "ci-dessous ou dans la notification, le coupe.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
        if (trackingActive) {
            BigButton("Arrêter le suivi", true, AppColors.danger, onStopTracking)
        } else {
            BigButton("📤 Partager le suivi", true, AppColors.primary, onClick = { onStartTracking(draft.parentSiteUrl, draft.shareId) })
        }
        Text(
            "En poche (écran éteint), la position seule continue d'être envoyée toutes les " +
                "15 s pour la batterie ; écran allumé, le partage complet reste à la cadence " +
                "de 2 s. Les objets entourés réapparaissent dès que le Direct rouvre.",
            color = AppColors.textMuted,
            fontSize = 11.sp,
        )
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

/**
 * Section « Direct » : la **prédiction locale des boîtes** (T8), éteinte par défaut.
 *
 * Le branchement existe, mais il n'est allumé qu'après la mesure de la latence
 * perçue : une boîte qui bouge peut être pire qu'une boîte qui saute. Tant que la
 * mesure n'a pas rendu son verdict, le direct reste celui d'avant (masques serveur).
 */
@Composable
private fun PredictionSettings(draft: Settings, onChange: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Direct — prédiction des boîtes (T8)",
            color = AppColors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        SwitchRow(
            "Boîtes prédites entre deux inférences (expérimental, non mesuré)",
            draft.boxPredictionEnabled,
        ) { onChange(draft.copy(boxPredictionEnabled = it)) }
        Text(
            "Éteint par défaut : c'est la mesure de la latence perçue qui décide, " +
                "pas l'inverse. La distance affichée n'est jamais prédite.",
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
