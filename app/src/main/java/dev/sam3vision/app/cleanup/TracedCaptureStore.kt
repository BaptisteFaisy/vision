package dev.sam3vision.app.cleanup

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Contrat du nettoyage des traces MediaStore. L'implémentation réelle parle à
 * `ContentResolver` ; les tests en fournissent une fausse (dé-sync comprise).
 */
interface TracedCaptureStore {
    /** Toutes les captures tracées par l'app. */
    suspend fun traced(): List<TracedCapture>

    /**
     * Supprime l'entrée MediaStore tracée. `true` = plus rien à supprimer (supprimée, ou
     * déjà disparue : dé-sync) ; `false` = échec réel (permission, provider en erreur).
     */
    suspend fun delete(entry: TracedCapture): Boolean

    /** Retire la trace de la table, qu'elle ait été supprimée ou non. */
    suspend fun forget(entry: TracedCapture)
}

/**
 * Implémentation Room + MediaStore. La sélection contient **toujours** l'`_ID` tracé, et
 * sur Android 10+ le `OWNER_PACKAGE_NAME` de l'app : un provider mal luné ne peut pas
 * emporter autre chose que le fichier visé.
 */
class RoomTracedCaptureStore(
    private val dao: TracedCaptureDao,
    private val resolver: ContentResolver,
    private val packageName: String,
) : TracedCaptureStore {

    override suspend fun traced(): List<TracedCapture> = dao.all()

    override suspend fun delete(entry: TracedCapture): Boolean {
        val collection = collectionFor(entry.kind) ?: return true // trace orpheline : à oublier
        val selection = selection(entry.mediaStoreId)
        return runCatching {
            // Dé-sync : l'entrée n'existe plus côté MediaStore → rien à supprimer, pas d'erreur.
            if (!exists(collection, selection)) return@runCatching true
            resolver.delete(ContentUris.withAppendedId(collection, entry.mediaStoreId), selection.first, selection.second) >= 0
        }.getOrElse { it is IllegalArgumentException } // URL/_ID invalide = déjà disparu
    }

    /** Vrai si l'entrée tracée existe encore (même `_ID`, même propriétaire). */
    private fun exists(collection: Uri, selection: Pair<String, Array<String>>): Boolean =
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection.first,
            selection.second,
            null,
        )?.use { it.count > 0 } ?: false

    override suspend fun forget(entry: TracedCapture) {
        dao.deleteById(entry.id)
    }

    /** Collection MediaStore selon le type, `null` si le type est inconnu. */
    private fun collectionFor(kind: String): Uri? = when (kind) {
        TracedCapture.KIND_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        TracedCapture.KIND_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> null
    }

    /** `_ID` + propriétaire (Android 10+) : jamais de filtre par date, jamais `Files`. */
    private fun selection(mediaStoreId: Long): Pair<String, Array<String>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns._ID} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?" to
                arrayOf(mediaStoreId.toString(), packageName)
        } else {
            "${MediaStore.MediaColumns._ID} = ?" to arrayOf(mediaStoreId.toString())
        }
}
