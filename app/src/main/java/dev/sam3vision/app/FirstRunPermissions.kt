package dev.sam3vision.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Les quatre permissions runtime d'iSee, demandées ensemble au premier lancement :
 * caméra (bulle iSee), micro (dictée, annonces, micro verrouillé), Bluetooth (lunettes
 * Ray-Ban Meta) et notifications — sans elles, la notification du service de premier plan
 * reste invisible et le micro de l'écran verrouillé n'aurait aucun bouton.
 */
val IseePermissions = listOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS,
)

/** Parmi cette liste, celles qui ne figurent pas dans l'ensemble accordé (pur, test JVM). */
fun List<String>.missingFrom(granted: Set<String>): List<String> = filterNot(granted::contains)

/** Permissions d'iSee non accordées sur le téléphone, à l'instant de l'appel. */
fun missingPermissions(context: Context): List<String> =
    IseePermissions.filterNot {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/** État partagé du passage des permissions de premier lancement. */
object IseePermissionFlow {    /** La demande groupée de permissions s'est jouée au moins une fois. */
val handled = mutableStateOf(false)
}/**
 * Au premier lancement, les quatre dialogues système s'enchaînent seuls — Android ne
 * permet pas d'octroyer une permission autrement, aucune question de l'app ne s'ajoute.
 * Tant que le groupe n'a pas répondu (« handled » faux), la bulle caméra patiente.
 * Un refus du micro est annoncé à la voix, comme avant ; un refus de la série n'est pas
 * regoûte dans la même session : l'app redemande au prochain lancement à froid tant
 * qu'il manque au moins une permission.
 */
@Composable
fun IseePermissionRequest(viewModel: AppViewModel) {
    val context = LocalContext.current
    val missing = remember { missingPermissions(context) }
    if (missing.isEmpty()) return
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            IseePermissionFlow.handled.value = true
            if (granted[Manifest.permission.RECORD_AUDIO] == false) viewModel.onMicDenied()
        }
    LaunchedEffect(Unit) { ask.launch(missing.toTypedArray()) }
}
