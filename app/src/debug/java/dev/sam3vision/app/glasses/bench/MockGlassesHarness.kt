package dev.sam3vision.app.glasses.bench

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitInterface
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import java.io.File

/**
 * Appareil simulé du banc lunettes d'iSee : des Ray-Ban Meta via Mock Device Kit.
 *
 * Aucune vraie paire de lunettes n'est nécessaire. Le cycle de vie réel de la
 * session DAT vit ailleurs (GlassesSession) ; ici on ne fait que le mock :
 * activation, appariement, état de l'appareil et source vidéo.
 *
 * Compilé en **debug** seulement (`mwdat-mockdevice` est en `debugImplementation`) :
 * `MockControl` en est la porte pour le code du banc, `MockGlassesTest` l'appelle
 * directement pour prouver le flux.
 *
 * Toutes les erreurs du SDK remontent dans `log` — jamais d'exception non
 * capturée, un test doit pouvoir afficher la cause au lieu de planter.
 */
object MockGlassesHarness {

    private const val TAG = "MockGlassesHarness"

    /** Instance du kit, conservée entre les appels pour pouvoir la désactiver. */
    private var kit: MockDeviceKitInterface? = null

    /** Lunettes simulées courantes (null tant que `enable` n'a pas réussi). */
    private var glasses: MockGlasses? = null

    /**
     * Active le mock, apparie un appareil simulé et le laisse prêt à streamer
     * (allumé, porté, déplié). Renvoie false si l'appariement échoue.
     */
    fun enable(context: Context, log: (String) -> Unit): Boolean = try {
        val appContext = context.applicationContext
        val mockKit = MockDeviceKit.getInstance(appContext).also { kit = it }

        if (mockKit.isEnabled) {
            log("MockDeviceKit déjà actif")
        } else {
            mockKit.enable(MockDeviceKitConfig())
            log("MockDeviceKit activé (appareil enregistré, permissions simulées accordées)")
        }

        val device = (glasses ?: mockKit.reuseOrPair(log)).also { glasses = it }
        if (device == null) {
            log("Aucune paire de lunettes simulée disponible : appariement impossible")
            false
        } else {
            prepareForStreaming(device, log)
            log("Appareil simulé prêt : allumé, porté, déplié")
            true
        }
    } catch (t: Throwable) {
        log("Échec de l'activation du mock : ${t.describe()}")
        Log.e(TAG, "enable a échoué", t)
        false
    }

    /**
     * Alimente la caméra simulée avec la caméra du téléphone (flux vivant).
     * Exige `android.permission.CAMERA` : refusée, on renvoie false sans crasher.
     */
    fun usePhoneCameraFeed(context: Context, log: (String) -> Unit): Boolean = try {
        val device = glasses
        when {
            !hasPhoneCameraPermission(context) -> {
                log("Permission CAMERA refusée : pas de flux caméra du téléphone (utiliser useVideoFeed)")
                false
            }

            device == null -> {
                log("Aucun appareil simulé : appeler enable() avant usePhoneCameraFeed()")
                false
            }

            else -> {
                device.services.camera.setCameraFeed(CameraFacing.BACK)
                log("Flux caméra simulé = caméra arrière du téléphone")
                true
            }
        }
    } catch (t: Throwable) {
        log("Échec du flux caméra du téléphone : ${t.describe()}")
        Log.e(TAG, "usePhoneCameraFeed a échoué", t)
        false
    }

    /**
     * Alimente la caméra simulée depuis un fichier vidéo h.265 (souvent dans le
     * cache de l'app). C'est la source déterministe pour les tests : l'asset
     * `mock_feed.mp4` de `app/src/androidTest/assets`, recopié dans le cache au
     * moment du test.
     */
    fun useVideoFeed(uri: Uri, log: (String) -> Unit): Boolean = try {
        val device = glasses
        if (device == null) {
            log("Aucun appareil simulé : appeler enable() avant useVideoFeed()")
            false
        } else {
            val hint = fileSizeHint(uri)
            device.services.camera.setCameraFeed(uri)
            log("Flux caméra simulé = vidéo h.265 $uri$hint")
            true
        }
    } catch (t: Throwable) {
        log("Échec de setCameraFeed($uri) : ${t.describe()}")
        Log.e(TAG, "useVideoFeed a échoué", t)
        false
    }

    /** Désapparie l'appareil simulé et rend la main au SDK réel. Best effort. */
    fun disable() {
        val currentGlasses = glasses
        val currentKit = kit
        glasses = null
        kit = null
        runCatching {
            if (currentGlasses != null && currentKit != null) currentKit.unpairDevice(currentGlasses)
        }.onFailure { Log.w(TAG, "unpairDevice a échoué", it) }
        runCatching { currentKit?.disable() }
            .onFailure { Log.w(TAG, "disable a échoué", it) }
    }

    /**
     * Retire **toutes** les paires simulées connues du SDK, y compris celles
     * qu'un run précédent a laissées : après un process tué (ou un build
     * réinstallé), `glasses` et `kit` de cet objet sont vides, mais le SDK, lui,
     * se souvient de l'appareil simulé — et `AutoDeviceSelector` le prendrait
     * pour une vraie paire. Ne journalise que s'il y avait vraiment quelque
     * chose à retirer.
     */
    fun dropSimulatedPairs(context: Context, log: (String) -> Unit) {
        val mockKit = runCatching { MockDeviceKit.getInstance(context.applicationContext) }.getOrNull()
        if (mockKit == null) {
            disable()
            return
        }
        val stale = runCatching { mockKit.pairedDevices.toList() }.getOrDefault(emptyList())
        stale.forEach { device ->
            runCatching { mockKit.unpairDevice(device) }
                .onFailure { Log.w(TAG, "unpairDevice a échoué", it) }
        }
        if (mockKit.isEnabled) {
            runCatching { mockKit.disable() }.onFailure { Log.w(TAG, "disable a échoué", it) }
        }
        glasses = null
        kit = null
        if (stale.isNotEmpty()) {
            log("${stale.size} paire(s) simulée(s) retirée(s) : le sélecteur ne verra qu'une vraie paire")
        }
    }

    /** Réutilise un appareil déjà apparié, sinon apparie des Ray-Ban Meta. */
    private fun MockDeviceKitInterface.reuseOrPair(log: (String) -> Unit): MockGlasses? {
        val existing = pairedDevices.filterIsInstance<MockGlasses>().firstOrNull()
        if (existing != null) {
            log("Réutilisation de l'appareil simulé déjà apparié")
            return existing
        }
        val result = pairGlasses(GlassesModel.RAYBAN_META)
        val paired = result.getOrNull()
        if (paired == null) {
            log("Appariement refusé : ${result.describeFailure()}")
        } else {
            log("Ray-Ban Meta simulées appariées")
        }
        return paired
    }

    /** Allume, porte et déplie l'appareil : le mock refuse de streamer sinon. */
    private fun prepareForStreaming(device: MockGlasses, log: (String) -> Unit): Boolean {
        val steps = listOf<Pair<String, () -> Unit>>(
            "powerOn" to device::powerOn,
            "don" to device::don,
            "unfold" to device::unfold,
        )
        var allOk = true
        for ((name, action) in steps) {
            try {
                action()
                log("  $name OK")
            } catch (t: Throwable) {
                allOk = false
                log("  $name KO : ${t.describe()}")
            }
        }
        return allOk
    }

    private fun hasPhoneCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Petit indice de diagnostic pour un URI `file://` (taille, absence). */
    private fun fileSizeHint(uri: Uri): String {
        if (uri.scheme != "file") return ""
        val file = uri.path?.let(::File) ?: return ""
        return when {
            !file.exists() -> " (fichier introuvable)"
            file.length() == 0L -> " (fichier vide)"
            else -> " (${file.length() / 1024} Ko)"
        }
    }

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private fun <T> com.meta.wearable.dat.core.types.DatResult<T, *>.describeFailure(): String {
        val error = errorOrNull()?.description
        return error ?: exceptionOrNull()?.message ?: "raison inconnue"
    }
}