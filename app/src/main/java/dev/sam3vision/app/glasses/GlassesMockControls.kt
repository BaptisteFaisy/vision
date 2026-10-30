package dev.sam3vision.app.glasses

import android.util.Log
import com.meta.wearable.dat.inputs.types.CapturePressType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Journal de la chaîne lunettes (recette : `logcat -s GlassesChain`). */
private const val TAG = "GlassesChain"

/** La connexion auto attend l'enregistrement jusque-là, puis explique au lieu d'insister. */
private const val MOCK_CONNECT_TIMEOUT_MS = 10_000L

/**
 * Lunettes simulées (Mock Device Kit), extraites du contrôleur (limite 500
 * lignes) : démarrage/arrêt, injection d'appuis et d'invocation vocale, et
 * connexion de recette. Comportement inchangé, à la ligne près.
 */
internal class GlassesMockControls(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val mock: GlassesMockDevice,
    private val state: MutableStateFlow<GlassesUiState>,
    private val note: (GlassesMessage) -> Unit,
    private val initialize: () -> Unit,
    private val connect: () -> Unit,
) {
    /** Lunettes simulées : démo sans matériel, et plan B si l'appairage coince. */
    fun start() {
        initialize()
        if (!mock.start()) {
            Log.w(TAG, "recette : mock indisponible")
            return note(GlassesMessages.mockUnavailable())
        }
        Log.i(TAG, "recette : mock actif")
        state.update {
            it.copy(mockActive = true, deviceName = it.deviceName ?: "Ray-Ban Meta (simulées)", registered = true)
        }
        note(GlassesMessages.mockReady())
    }

    /** Injecte un appui sur le bouton capture, comme le ferait un vrai porteur. */
    fun press(type: CapturePressType) {
        if (!mock.press(type)) note(GlassesMessages.noMockPress())
    }

    /**
     * Injecte « Hey Meta, ouvre iSee » côté appareil. `false` = le mock ne sait
     * pas injecter : l'appelant simule alors côté app ([GlassesController.mockVoiceLaunch]).
     */
    fun tryVoiceLaunch(): Boolean = mock.simulateVoiceLaunch()

    fun stop() {
        mock.stop()
        state.update { it.copy(mockActive = false) }
    }

    /**
     * Raccourci de recette : lunettes simulées, puis connexion dès que l'enregistrement le
     * permet (extra adb `glasses_mock_connect`). Inerte en release — le mock y est
     * indisponible (`mockUnavailable`) et rien ne se connecte tout seul.
     */
    fun debugConnect() {
        initialize()
        start()
        scope.launch {
            val deadline = clock() + MOCK_CONNECT_TIMEOUT_MS
            while (clock() < deadline) {
                val snapshot = state.value
                // Une session vit déjà (ou naît) : rien à faire, surtout pas un doublon.
                if (snapshot.connected || snapshot.phase == SessionPhase.STARTING) {
                    Log.i(TAG, "recette : session déjà là (${snapshot.phase}), rien à faire")
                    return@launch
                }
                if (snapshot.canConnect) {
                    Log.i(TAG, "recette : connexion")
                    connect()
                    return@launch
                }
                delay(250)
            }
            note(GlassesMessages.mockConnectTimeout())
        }
    }
}
