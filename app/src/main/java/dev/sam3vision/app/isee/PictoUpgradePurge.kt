package dev.sam3vision.app.isee

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Une version neuve ne réveille jamais les pictos d'avant : à la première
 * ouverture après mise à jour, le magasin (`filesDir/pictos`) et les égarés
 * (`picto-*` restés à la racine des fichiers privés) sont purgés avant tout
 * chargement — aucun repère créé sous une ancienne version ne survit, ni sur
 * disque ni en mémoire. Première installation : rien à purger, la version est
 * juste enregistrée. N'échoue jamais : une purge ratée ne bloque pas le démarrage.
 */
object PictoUpgradePurge {

    internal const val PREFS = "picto_store"
    internal const val KEY_LAST_VERSION = "last_version_code"

    /**
     * Purge le magasin et les égarés si [currentVersion] diffère de [lastVersion]
     * (`null` = jamais enregistré : première installation, rien à purger).
     * Vrai quand une purge a eu lieu.
     */
    fun purgeIfUpgraded(filesDir: File, currentVersion: Long, lastVersion: Long?): Boolean {
        if (lastVersion == null || lastVersion == currentVersion) return false
        File(filesDir, PICTOS_DIR).listFiles()?.forEach { it.deleteRecursively() }
        filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(PictoStore.PICTO_PREFIX) }
            ?.forEach { it.deleteRecursively() }
        return true
    }

    /** Entrée Android (SamApp) : version lue, purge éventuelle, version enregistrée. */
    fun maybePurge(context: Context) {
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = if (prefs.contains(KEY_LAST_VERSION)) {
                prefs.getLong(KEY_LAST_VERSION, -1L)
            } else {
                null
            }
            purgeIfUpgraded(context.filesDir, info.longVersionCode, last)
            if (last != info.longVersionCode) {
                prefs.edit().putLong(KEY_LAST_VERSION, info.longVersionCode).apply()
            }
        }
    }
}
