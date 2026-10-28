package dev.sam3vision.app.perms

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Vrai si [perm] est accordée à l'app. */
fun hasPerm(context: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

/**
 * Retrouve l'[Activity] derrière un `Context`, même enveloppée dans un
 * `ContextThemeWrapper` (même motif que `findLifecycleOwner` côté tap).
 */
fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()

/** Ouvre la fiche de l'app dans les Réglages système (repli « Ne plus demander »). */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(appSettingsUri(context.packageName)))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/**
 * Demande le micro pour la dictée / navigation vocale. Protocole one-shot :
 * l'appelant passe `request=true` pour démarrer, puis repasse à `false` dans
 * [onGranted], [onDenied] ou [onConsumed] (chaque chemin terminal appelle
 * [onConsumed] exactement une fois).
 */
@Composable
fun MicPermRequest(
    request: Boolean,
    onGranted: () -> Unit,
    onDenied: (String) -> Unit,
    onConsumed: () -> Unit,
) {
    val context = LocalContext.current
    PermFlow(
        kind = PermKind.MIC,
        perms = arrayOf(Manifest.permission.RECORD_AUDIO),
        request = request,
        granted = { hasPerm(context, Manifest.permission.RECORD_AUDIO) },
        accepted = { it[Manifest.permission.RECORD_AUDIO] == true },
        onGranted = onGranted,
        onDenied = onDenied,
        onConsumed = onConsumed,
    )
}

/** Demande la position d'avant-plan (fine ou approximative acceptée). */
@Composable
fun LocationFgPermRequest(
    request: Boolean,
    onGranted: () -> Unit,
    onDenied: (String) -> Unit,
    onConsumed: () -> Unit,
) {
    val context = LocalContext.current
    PermFlow(
        kind = PermKind.LOCATION_FG,
        perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        request = request,
        granted = {
            hasPerm(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPerm(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        },
        accepted = {
            fgGranted(
                it[Manifest.permission.ACCESS_FINE_LOCATION] == true,
                it[Manifest.permission.ACCESS_COARSE_LOCATION] == true,
            )
        },
        onGranted = onGranted,
        onDenied = onDenied,
        onConsumed = onConsumed,
    )
}

/**
 * Demande la position d'arrière-plan, uniquement si l'avant-plan est déjà
 * accordé ([fgGrantedNow] faux → refus immédiat [PermTexts.BG_NEEDS_FG],
 * jamais de dialogue système, qui serait ignoré par Android).
 */
@Composable
fun LocationBgPermRequest(
    fgGrantedNow: Boolean,
    request: Boolean,
    onGranted: () -> Unit,
    onDenied: (String) -> Unit,
    onConsumed: () -> Unit,
) {
    if (!request) return
    if (!fgGrantedNow) {
        LaunchedEffect(request) {
            onDenied(PermTexts.BG_NEEDS_FG)
            onConsumed()
        }
        return
    }
    val context = LocalContext.current
    PermFlow(
        kind = PermKind.LOCATION_BG,
        perms = arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        request = request,
        granted = { hasPerm(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        accepted = { it[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true },
        onGranted = onGranted,
        onDenied = onDenied,
        onConsumed = onConsumed,
    )
}

/** Vrai si le système autorise une ré-explication avant de redemander. */
private fun showRationaleNow(context: Context, perms: Array<String>): Boolean {
    val activity = context.findActivity() ?: return false
    return perms.any { activity.shouldShowRequestPermissionRationale(it) }
}

/**
 * État du flux commun : demande déjà jouée, dialogue visible. Les transitions
 * vivent ici pour garder le composable sous la limite de taille.
 */
private class PermFlowState {
    var askedBefore by mutableStateOf(false)
    var showRationale by mutableStateOf(false)
    var showSettings by mutableStateOf(false)

    /** Réponse du dialogue système : accordé, « Ne plus demander », ou refus simple. */
    fun onSystemResult(
        results: Map<String, Boolean>,
        accepted: (Map<String, Boolean>) -> Boolean,
        context: Context,
        perms: Array<String>,
        copy: PermCopy,
        onGranted: () -> Unit,
        onDenied: (String) -> Unit,
        onConsumed: () -> Unit,
    ) {
        askedBefore = true
        if (accepted(results)) {
            onGranted()
            onConsumed()
        } else if (!showRationaleNow(context, perms)) {
            showSettings = true
        } else {
            onDenied(copy.deniedMessage)
            onConsumed()
        }
    }

    /** Déclenchement : dialogue système, explication ou Réglages selon [nextStep]. */
    fun onTrigger(
        grantedNow: Boolean,
        canRationale: Boolean,
        launch: () -> Unit,
        onGranted: () -> Unit,
        onConsumed: () -> Unit,
    ) {
        when (nextStep(grantedNow, askedBefore, canRationale)) {
            PermStep.PROCEED -> {
                onGranted()
                onConsumed()
            }
            PermStep.ASK -> {
                askedBefore = true
                launch()
            }
            PermStep.RATIONALE -> showRationale = true
            PermStep.SETTINGS -> showSettings = true
        }
    }

    /** L'utilisateur a lu l'explication : on relance le dialogue système. */
    fun onRationaleConfirm(launch: () -> Unit) {
        showRationale = false
        askedBefore = true
        launch()
    }

    /** Repli Réglages : l'appelant réessaiera au retour (état revérifié). */
    fun onSettingsConfirm(context: Context, onConsumed: () -> Unit) {
        showSettings = false
        openAppSettings(context)
        onConsumed()
    }
}

@Composable
private fun rememberPermFlowState(kind: PermKind): PermFlowState = remember(kind) { PermFlowState() }

/**
 * Cœur commun : dialogue système, explication ([PermStep.RATIONALE]) ou repli
 * Réglages ([PermStep.SETTINGS]) selon [nextStep]. `askedBefore` est
 * mémoire de composition : après un refus « Ne plus demander », le dialogue
 * système ne s'affiche plus et le résultat `false` immédiat bascule vers les
 * Réglages, même en première monte.
 */
@Composable
private fun PermFlow(
    kind: PermKind,
    perms: Array<String>,
    request: Boolean,
    granted: () -> Boolean,
    accepted: (Map<String, Boolean>) -> Boolean,
    onGranted: () -> Unit,
    onDenied: (String) -> Unit,
    onConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val copy = remember(kind) { copyFor(kind) }
    val flow = rememberPermFlowState(kind)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        flow.onSystemResult(results, accepted, context, perms, copy, onGranted, onDenied, onConsumed)
    }
    LaunchedEffect(request) {
        if (!request) return@LaunchedEffect
        flow.onTrigger(granted(), showRationaleNow(context, perms), { launcher.launch(perms) }, onGranted, onConsumed)
    }
    PermFlowDialogs(
        copy = copy,
        flow = flow,
        onRationaleConfirm = { flow.onRationaleConfirm { launcher.launch(perms) } },
        onDismiss = { dismissFlow(granted, copy.deniedMessage, onGranted, onDenied, onConsumed) },
        onSettingsConfirm = { flow.onSettingsConfirm(context, onConsumed) },
    )
}

/** Les deux dialogues du flux : explication avant de redemander, repli Réglages. */
@Composable
private fun PermFlowDialogs(
    copy: PermCopy,
    flow: PermFlowState,
    onRationaleConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onSettingsConfirm: () -> Unit,
) {
    if (flow.showRationale) {
        PermDialog(
            title = copy.rationaleTitle,
            message = copy.rationaleMessage,
            confirm = copy.confirmLabel,
            onConfirm = onRationaleConfirm,
            onDismiss = {
                flow.showRationale = false
                onDismiss()
            },
        )
    }
    if (flow.showSettings) {
        PermDialog(
            title = PermTexts.SETTINGS_TITLE,
            message = copy.settingsMessage,
            confirm = PermTexts.OPEN_SETTINGS,
            onConfirm = onSettingsConfirm,
            onDismiss = {
                flow.showSettings = false
                onDismiss()
            },
        )
    }
}

/** Fermeture d'un dialogue : la permission a pu être accordée entre-temps (retour Réglages). */
private fun dismissFlow(
    granted: () -> Boolean,
    deniedMessage: String,
    onGranted: () -> Unit,
    onDenied: (String) -> Unit,
    onConsumed: () -> Unit,
) {
    if (granted()) onGranted() else onDenied(deniedMessage)
    onConsumed()
}

/** Dialogue FR du flux : explication ou repli Réglages, deux boutons larges. */
@Composable
private fun PermDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(PermTexts.LATER) } },
    )
}
