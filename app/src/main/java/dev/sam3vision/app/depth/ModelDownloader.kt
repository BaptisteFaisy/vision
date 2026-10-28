package dev.sam3vision.app.depth

import android.content.Context
import dev.sam3vision.core.depth.DepthBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Fiche d'un modèle : URL stable (release GitHub) + empreinte de vérification. */
data class DepthModelSpec(
    val fileName: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

/**
 * Catalogue des modèles (un par moteur) : hébergés sur la release
 * `depth-models-v1` du dépôt, vérifiés par sha256 après téléchargement.
 * [AUTO] résout vers DA2 (moteur préféré).
 */
object DepthModels {
    private const val RELEASE =
        "https://github.com/BaptisteFaisy/vision/releases/download/depth-models-v1/"

    val DA2 = DepthModelSpec(
        fileName = "da2_small_uint8.ort",
        url = RELEASE + "da2_small_uint8.ort",
        sha256 = "116d71add3e7ed7ba1a9046e32b01d1ae41fc83e41b260a7b7b3eb37176aa0a7",
        bytes = 27516544,
    )

    val MIDAS = DepthModelSpec(
        fileName = "midas_v21_small.tflite",
        url = RELEASE + "midas_v21_small.tflite",
        sha256 = "93d871071edff1218973ce25ee27ce95ccd20450c70a55e1b89efa3f5a772cdd",
        bytes = 66338288,
    )

    fun forBackend(backend: DepthBackend): DepthModelSpec? = when (backend) {
        DepthBackend.DA2, DepthBackend.AUTO -> DA2
        DepthBackend.MIDAS -> MIDAS
        DepthBackend.OFF -> null
    }
}

/** Stockage privé des modèles (`<filesDir>/depth-models`). */
object DepthModelStore {
    const val DIR = "depth-models"

    fun fileFor(appDir: File, backend: DepthBackend): File? {
        val spec = DepthModels.forBackend(backend) ?: return null
        return File(File(appDir, DIR), spec.fileName)
    }

    fun fileFor(context: Context, backend: DepthBackend): File? =
        fileFor(context.filesDir, backend)
}

/**
 * Téléchargement des modèles à l'exécution (l'APK ne porte plus les ~92 Mo) :
 * fichier déjà présent et empreinte OK ⇒ réutilisé ; sinon GET + vérification
 * sha256 + renommage atomique (un `.tmp` abandonné ne pollue jamais le store).
 * Échec ⇒ `Result.failure` avec un message affichable (banc, ligne de statut).
 */
object ModelDownloader {

    suspend fun ensure(
        context: Context,
        backend: DepthBackend,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = ensureDir(context.filesDir, backend, onProgress)

    suspend fun ensureDir(
        appDir: File,
        backend: DepthBackend,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> {
        val spec = DepthModels.forBackend(backend)
        if (spec == null) {
            return Result.failure(IllegalArgumentException("moteur sans modèle : " + backend.code))
        }
        return ensureSpec(appDir, spec, onProgress = onProgress)
    }

    /**
     * Même garantie sur une fiche explicite (les tests JVM injectent un serveur
     * local). [dir] = sous-dossier du store (`depth-models` par défaut, la
     * segmentation range les siens dans `segment-models`).
     */
    suspend fun ensureSpec(
        appDir: File,
        spec: DepthModelSpec,
        dir: String = DepthModelStore.DIR,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val store = File(appDir, dir)
            store.mkdirs()
            val target = File(store, spec.fileName)
            if (target.isFile && sha256Of(target) == spec.sha256) {
                return@withContext Result.success(target)
            }
            download(spec, target, onProgress)
            Result.success(target)
        } catch (e: Exception) {
            Result.failure(friendly(e))
        }
    }

    private fun download(
        spec: DepthModelSpec,
        target: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        val tmp = File(target.parent, target.name + ".tmp")
        try {
            fetchTo(spec, tmp, onProgress)
            if (sha256Of(tmp) != spec.sha256) {
                throw IllegalStateException("empreinte sha256 invalide (" + spec.fileName + ")")
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun fetchTo(
        spec: DepthModelSpec,
        tmp: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        // Appels explicites (pas de sélection de propriété sur la connexion) :
        // le parseur UBS ne suit pas ces sélections (exit 2 sinon).
        val conn = URL(spec.url).openConnection() as HttpURLConnection
        try {
            conn.setConnectTimeout(15_000)
            conn.setReadTimeout(60_000)
            conn.setInstanceFollowRedirects(true)
            conn.connect()
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("téléchargement refusé (HTTP " + conn.getResponseCode() + ")")
            }
            val total = conn.getContentLengthLong().takeIf { it > 0 } ?: spec.bytes
            conn.getInputStream().use { input ->
                tmp.outputStream().use { output ->
                    copyWithProgress(input, output, total, onProgress)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Copie par blocs de 64 Ko, progression signalée à chaque point de %. */
    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        total: Long,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var lastPct = -1
        for (n in generateSequence { input.read(buf).takeIf { it >= 0 } }) {
            output.write(buf, 0, n)
            done += n
            val pct = ((done * 100) / total.coerceAtLeast(1)).toInt()
            if (pct != lastPct) {
                lastPct = pct
                onProgress(done, total)
            }
        }
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            for (n in generateSequence { input.read(buf).takeIf { it >= 0 } }) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Pannes réseau traduites en clair ; le reste passe tel quel (détail utile). */
    private fun friendly(e: Throwable): Throwable = when (e) {
        is java.net.UnknownHostException,
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
            -> IllegalStateException("réseau indisponible — vérifiez la connexion", e)
        else -> e
    }
}
