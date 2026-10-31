package dev.sam3vision.app.glasses

import android.app.Activity
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.DatResult
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionError
import com.meta.wearable.dat.core.types.PermissionStatus

/** Ce que rend la page d'autorisation de Meta AI, tel que le SDK le rend à l'app. */
typealias PermissionResult = DatResult<PermissionStatus, PermissionError>

/**
 * Ce qu'un verdict d'autorisation change à l'écran, et ce qu'il faut en dire.
 * `null` = permission non concernée par ce verdict : l'état garde sa valeur.
 */
data class PermissionOutcome(
    val cameraAllowed: Boolean? = null,
    val micAllowed: Boolean? = null,
    val message: GlassesMessage,
)

/**
 * Verdict → état + message. Pur, donc testé en JVM : le contrat d'autorisation du SDK est un
 * `ActivityResultContract`, on ne peut le vérifier que par son résultat.
 */
fun permissionOutcome(permission: Permission, result: PermissionResult): PermissionOutcome =
    result.fold(
        onSuccess = { status ->
            val granted = status is PermissionStatus.Granted
            PermissionOutcome(
                cameraAllowed = if (permission == Permission.CAMERA) granted else null,
                micAllowed = if (permission == Permission.MICROPHONE) granted else null,
                message = if (granted) permissionGranted(permission) else permissionRefused(permission),
            )
        },
        onFailure = { error, _ -> PermissionOutcome(message = permissionFailed(permission, error)) },
    )

/** Journal de la chaîne lunettes (recette : `logcat -s GlassesChain`). */
private const val TAG = "GlassesChain"

/**
 * Dit une erreur de session dans la langue de l'écran, et ouvre sa page de réparation quand
 * elle s'y répare (app DAT des lunettes à mettre à jour).
 *
 * Le journal reçoit TOUJOURS l'erreur brute (enum + description SDK) : seul l'affichage est
 * filtré ([sessionErrorWorthTelling]), jamais le diagnostic. Un vrai `STOP_ERROR_GENERIC`
 * devenu `UNEXPECTED` reste donc visible au `logcat` même quand l'écran garde le causal.
 */
internal fun reportSessionError(
    error: DeviceSessionError,
    activity: Activity?,
    note: (GlassesMessage) -> Unit,
    phase: SessionPhase = SessionPhase.IDLE,
    current: GlassesMessage? = null,
) {
    Log.w(TAG, "erreur session : $error — ${error.description}")
    if (!sessionErrorWorthTelling(error, phase, current)) return
    note(sessionErrorMessage(error))
    if (!sessionErrorNeedsGlassesAppUpdate(error)) return
    activity?.let { host -> runCatching { Wearables.openDATGlassesAppUpdate(host) } }
}

/**
 * Relais des autorisations entre l'écran et le contrat du SDK : il ne connaît ni Android ni le
 * contrôleur, seulement trois gestes (demander, écrire l'état, prévenir). Sorti du contrôleur
 * pour y laisser la place aux décisions de session.
 */
internal class GlassesPermissionGate(
    private val requester: (Permission) -> Unit,
    private val hasActivity: () -> Boolean,
    private val update: ((GlassesUiState) -> GlassesUiState) -> Unit,
    private val note: (GlassesMessage) -> Unit,
    private val onCameraGranted: () -> Unit,
) {
    fun request(permission: Permission) {
        if (!hasActivity()) return note(GlassesMessages.noActivity())
        requester(permission)
        note(permissionRequested(permission))
    }

    fun result(permission: Permission, result: PermissionResult) {
        val outcome = permissionOutcome(permission, result)
        update { current ->
            current.copy(
                cameraAllowed = outcome.cameraAllowed ?: current.cameraAllowed,
                micAllowed = outcome.micAllowed ?: current.micAllowed,
            )
        }
        note(outcome.message)
        if (outcome.cameraAllowed == true) onCameraGranted()
    }
}
