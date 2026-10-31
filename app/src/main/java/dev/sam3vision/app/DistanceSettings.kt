package dev.sam3vision.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.live.CalibrationSnap
import dev.sam3vision.app.live.CalibrationSnapshots
import dev.sam3vision.app.live.CalibrationStore
import dev.sam3vision.app.live.LensOrigin
import dev.sam3vision.app.live.LensQualityStore
import dev.sam3vision.app.live.SavedCalibration
import dev.sam3vision.app.pose.CameraHeightStore
import dev.sam3vision.core.distance.Calibration
import dev.sam3vision.core.distance.CalibrationSolver
import dev.sam3vision.core.distance.CameraPose
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.settings.Settings
import java.util.Locale

/**
 * "Distances" settings section: Direct overlay switch and fallback field of
 * view (`0` = straight sensor reading, the normal path).
 */
@Composable
internal fun DistanceSettings(draft: Settings, onChange: (Settings) -> Unit) {
    val context = LocalContext.current
    val heights = remember(context) { CameraHeightStore(context) }
    val calibrationStore = remember(context) { CalibrationStore(context) }
    val lensStore = remember(context) { LensQualityStore(context) }
    var heightM by remember { mutableStateOf(heights.get()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Distances",
            color = SettingsLight.text,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        SwitchRow("Distances on Direct", draft.distanceOverlayEnabled) {
            onChange(draft.copy(distanceOverlayEnabled = it))
        }
        LabeledField(
            label = "Camera field of view (°, 0 = sensor reading)",
            value = fovText(draft.cameraFovDeg),
            secret = false,
        ) { text ->
            // A hand-typed field is not a measurement: the solved flag falls, so the
            // status never calls a typed 67° "measured".
            calibrationStore.setCalibrated(false)
            onChange(draft.copy(cameraFovDeg = parseFov(text)))
        }
        LabeledField(
            label = "Camera height in meters (shoulder ~1.5)",
            value = heightText(heightM),
            secret = false,
        ) { text ->
            val parsed = parseHeight(text) ?: return@LabeledField
            heightM = parsed
            heights.set(parsed)
        }
        LensFallbackWarning(lensStore)
        CalibrationBlock(draft, onChange, calibrationStore)
    }
}

/**
 * Warning when the last camera bind fell back to the 67° default (mute driver,
 * nothing set): distances then run on a guess, worth one line. Silent when the
 * sensor answered or no bind was ever noted.
 */
@Composable
private fun LensFallbackWarning(lensStore: LensQualityStore) {
    val fallback = remember { lensStore.lastOrigin() == LensOrigin.FALLBACK }
    if (fallback) {
        Text(
            "Warning: sensor silent, distances run on the 67° fallback field",
            color = SettingsLight.text,
            fontSize = 13.sp,
        )
    }
}

/**
 * Guided calibration: with a helper standing at a taped distance, the Direct
 * watches ([CalibrationSnapshots]), then this solves the field of view from the
 * helper's exact height — the one setting that kills the focal-length bias.
 * The solved value lands in [Settings.cameraFovDeg] (top priority, as a forced
 * field); [CalibrationStore] remembers it was measured, not typed.
 */
@Composable
private fun CalibrationBlock(draft: Settings, onChange: (Settings) -> Unit, store: CalibrationStore) {
    val snap by CalibrationSnapshots.latest.collectAsState()
    var heightText by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf("") }
    var calibrated by remember(draft.cameraFovDeg) { mutableStateOf(store.isCalibrated()) }
    var saved by remember(draft.cameraFovDeg) { mutableStateOf(store.lastCalibration()) }
    var message by remember { mutableStateOf<String?>(null) }
    val fresh = CalibrationSnapshots.isFresh(snap, System.currentTimeMillis())
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CalibrationStatus(snap, calibrated, saved, draft.cameraFovDeg)
        LabeledField(
            label = "Helper height in meters (e.g. 1.78)",
            value = heightText,
            secret = false,
        ) { heightText = it }
        LabeledField(
            label = "Taped distance in meters (e.g. 2.00)",
            value = distanceText,
            secret = false,
        ) { distanceText = it }
        GlassButton("Calibrate from last sighting", fresh, true, onClick = {
            val solved = calibrationResult(heightText, distanceText, snap)
            if (solved == null) {
                message = "Cannot solve: re-aim at the helper and check both numbers"
            } else {
                onChange(draft.copy(cameraFovDeg = solved.fovDeg))
                store.saveCalibration(solved.fovDeg, solved.toleranceDeg)
                calibrated = true
                saved = store.lastCalibration()
                message = "Calibrated: ${fovText(solved.fovDeg)}° ±${String.format(Locale.US, "%.1f", solved.toleranceDeg)}°"
            }
        })
        if (calibrated) {
            GlassButton("Clear calibration (back to sensor)", true, false, onClick = {
                onChange(draft.copy(cameraFovDeg = 0f))
                store.setCalibrated(false)
                calibrated = false
                saved = null
                message = null
            })
        }
        message?.let { Text(it, color = SettingsLight.text, fontSize = 13.sp) }
    }
}

/** État d'étalonnage + dernière visée, en deux lignes (+ alerte si jamais calibré). */
@Composable
private fun CalibrationStatus(
    snap: CalibrationSnap?,
    calibrated: Boolean,
    saved: SavedCalibration?,
    fovDeg: Float,
) {
    Text(
        calibrationLine(calibrated, saved, fovDeg, System.currentTimeMillis()),
        color = SettingsLight.text,
        fontSize = 13.sp,
    )
    val now = System.currentTimeMillis()
    Text(
        snap?.let { "Last person seen: ${it.boxHeightPx.toInt()} px (${(now - it.atMs) / 1000} s ago)" }
            ?: "Point the Direct at the helper first, then come back here",
        color = SettingsLight.text,
        fontSize = 13.sp,
    )
}

/**
 * Ligne d'état d'étalonnage : mesure persistée (champ ± tolérance, âge) quand
 * elle existe, alerte sinon — « jamais calibré » se voit, les distances ne
 * tournent plus en silence sur un champ par défaut.
 */
internal fun calibrationLine(calibrated: Boolean, saved: SavedCalibration?, fovDeg: Float, nowMs: Long): String {
    if (saved != null) {
        val age = (nowMs - saved.atMs).takeIf { it >= 0L }?.let { " (${ageText(it)} ago)" } ?: ""
        val tolerance = String.format(Locale.US, "%.1f", saved.toleranceDeg)
        return "Calibration: measured ${fovText(saved.fovDeg)}° ±${tolerance}°$age"
    }
    if (calibrated) return "Calibration: measured ${fovText(fovDeg)}°"
    return "Calibration: not calibrated — distances run on the sensor/default field"
}

/** Âge compact : 40 s, 12 min, 3 h, 5 d. */
internal fun ageText(ageMs: Long): String {
    val seconds = ageMs / 1000L
    if (seconds < 60L) return "$seconds s"
    val minutes = seconds / 60L
    if (minutes < 60L) return "$minutes min"
    val hours = minutes / 60L
    if (hours < 48L) return "$hours h"
    return "${hours / 24L} d"
}

/**
 * Champ résolu depuis les saisies et la visée, `null` si quoi que ce soit cloche
 * (visée périmée, saisie hors bornes, champ hors téléphones) : l'appelant affiche
 * l'échec au lieu d'écrire une valeur douteuse.
 */
internal fun calibrationResult(heightText: String, distanceText: String, snap: CalibrationSnap?): Calibration? {
    val box = snap?.takeIf { CalibrationSnapshots.isFresh(it, System.currentTimeMillis()) } ?: return null
    val height = parseCalibrationMeters(heightText, CalibrationSolver.MIN_HEIGHT_M, CalibrationSolver.MAX_HEIGHT_M)
        ?: return null
    val distance = parseCalibrationMeters(distanceText, CalibrationSolver.MIN_DISTANCE_M, CalibrationSolver.MAX_DISTANCE_M)
        ?: return null
    return CalibrationSolver.solve(height, distance, box.boxHeightPx, box.imageWidthPx)
}

/** Displayed field-of-view value: empty when the sensor decides. */
internal fun fovText(deg: Float): String =
    if (deg > 0f) String.format(Locale.US, "%.1f", deg).removeSuffix(".0") else ""

/** Tolerant field-of-view input: comma accepted, [LensSpec] bounds, 0 = auto. */
internal fun parseFov(text: String): Float {
    val value = text.replace(',', '.').trim().toFloatOrNull() ?: return 0f
    return if (value <= 0f) 0f else value.coerceIn(LensSpec.FOV_MIN, LensSpec.FOV_MAX)
}

/** Displayed camera height: one decimal, US dot. */
internal fun heightText(meters: Float): String =
    String.format(Locale.US, "%.1f", meters)

/** Tolerant height input: comma accepted, hand-held bounds, `null` when unreadable. */
internal fun parseHeight(text: String): Float? {
    val value = text.replace(',', '.').trim().toFloatOrNull() ?: return null
    if (!value.isFinite()) return null
    return value.coerceIn(CameraPose.MIN_HEIGHT_M, CameraPose.MAX_HEIGHT_M)
}

/**
 * Tolerant calibration input in meters: comma accepted, `null` when unreadable
 * or outside [min]..[max] (no clamping here — a mistyped height must fail loudly,
 * not silently calibrate on a wrong number).
 */
internal fun parseCalibrationMeters(text: String, min: Float, max: Float): Float? {
    val value = text.replace(',', '.').trim().toFloatOrNull() ?: return null
    if (!value.isFinite() || value !in min..max) return null
    return value
}
