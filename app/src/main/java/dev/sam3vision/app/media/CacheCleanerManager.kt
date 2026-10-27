package dev.sam3vision.app.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Les trois règles de purge du cache iSee, combinées dans une seule classe :
 *
 * 1. **Purge totale sur fermeture (A)** — [purgeAll] : vide la table Room et exécute
 * `context.cacheDir.deleteRecursively()`. Prévu pour `onDestroy()` de l'activité.
 * 2. **Minuteur d'inactivité (C)** — [onPause] programme la purge totale dans
 * [INACTIVITY_MS] ; [onResume] l'annule immédiatement.
 * 3. **Sécurité FIFO (B)** — [enforceFifoAfterWrite] : appelée après chaque écriture
 * multimédia. Au-delà de [MAX_TOTAL_BYTES], le message le plus ancien est détruit
 * (ses fichiers physiques, puis sa ligne Room), jusqu'à repasser sous le seuil.
 */
class CacheCleanerManager(
    private val dao: MessageMultimediaDao,
    private val cacheDir: File,
    private val inactivityMs: Long = INACTIVITY_MS,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
) {

    private val purgeMutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private var inactivityUndo: Runnable? = null

    /** Option A — purge totale : table vide, cache entier supprimé. */
    suspend fun purgeAll() = purgeMutex.withLock {
        withContext(Dispatchers.IO) {
            dao.purgeAll()
            cacheDir.deleteRecursively()
        }
    }

    /** Option C — l'app part en arrière-plan : timer démarré pour [inactivityMs]. */
    fun onPause() {
        cancelInactivityTimer()
        val run = Runnable { launchPurge() }
        inactivityUndo = run
        handler.postDelayed(run, inactivityMs)
    }

    /** Option C — l'app revient au premier plan : timer annulé. */
    fun onResume() = cancelInactivityTimer()

    /** Option B — après chaque écriture : FIFO dès que le cache dépasse le plafond. */
    suspend fun enforceFifoAfterWrite() = purgeMutex.withLock {
        withContext(Dispatchers.IO) {
            // Par lots : 1 mesure du dossier + 2 requêtes par lot, au lieu de N mesures + 2N requêtes.
            while (dirSize(cacheDir) > maxTotalBytes) {
                val batch = dao.oldestBatch(FIFO_BATCH)
                if (batch.isEmpty()) return@withContext
                batch.forEach(::deleteRowFiles)
                dao.deleteByIds(batch.map { it.id })
            }
        }
    }

    /** Annule le minuteur d'inactivité s'il est armé. */
    private fun cancelInactivityTimer() {
        inactivityUndo?.let { handler.removeCallbacks(it) }
        inactivityUndo = null
    }

    private fun launchPurge() {
        CoroutineScope(Dispatchers.IO).launch { purgeAll() }
    }

    /** Supprime les fichiers physiques attachés à une ligne (audio user, ElevenLabs, vidéo). */
    private fun deleteRowFiles(row: MessageMultimedia) {
        listOf(row.cheminAudioUtilisateur, row.cheminAudioElevenLabs, row.cheminVideoEnvironnement)
            .filterNotNull()
            .forEach { path -> File(path).delete() }
    }

    companion object {
        /** Option C : 30 minutes d'arrière-plan consécutives. */
        const val INACTIVITY_MS = 30L * 60L * 1000L

        /** Option B : plafond global du cache. */
        const val MAX_TOTAL_BYTES = 500L * 1024L * 1024L

        /** Lignes purgées par lot : 16 suppressions de fichiers pour 1 mesure du dossier. */
        const val FIFO_BATCH = 16

        /** Taille récursive d'un dossier (0 si absent). Utilisée avant/après chaque écriture. */
        fun dirSize(dir: File): Long {
            val files = dir.listFiles() ?: return 0L
            var total = 0L
            for (file in files) {
                total += when {
                    file.isDirectory -> dirSize(file)
                    else -> file.length()
                }
            }
            return total
        }
    }
}
