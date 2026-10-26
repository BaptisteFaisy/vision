package dev.sam3vision.app.cleanup

import java.io.File

/** Type de capture : seules ces deux familles sont purgées. */
enum class CaptureKind { IMAGE, VIDEO }

/**
 * Décide quels fichiers du périmètre privé sont purgeables. **Aucune API Android ici** :
 * des [File] et une horloge injectée, donc testable en JVM pur.
 *
 * Le parcours est récursif (les sous-dossiers créés par CameraX sont inclus) et ne suit
 * jamais un lien symbolique : un lien vers le stockage partagé ne doit pas devenir une
 * porte de sortie du périmètre.
 */
object CaptureScanner {

    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")
    val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "3gp", "webm")

    /** Famille d'un fichier d'après son extension, ou `null` s'il n'est pas une capture. */
    fun kindOf(file: File): CaptureKind? {
        val ext = file.extension.lowercase()
        return when {
            ext in IMAGE_EXTENSIONS -> CaptureKind.IMAGE
            ext in VIDEO_EXTENSIONS -> CaptureKind.VIDEO
            else -> null
        }
    }

    /** Tous les fichiers de capture sous [roots], récursivement, sans suivre les liens. */
    fun scan(roots: List<File>): List<File> = roots.filter { it.isDirectory }.flatMap { walk(it) }

    /** Fichiers retenus pour suppression, en appliquant le type et la rétention. */
    fun purgeable(roots: List<File>, config: CleanupConfig, nowMs: Long): List<File> =
        scan(roots).filter { isDue(it, config, nowMs) }

    /** Vrai si la famille de [file] est activée dans la config (images et/ou vidéos). */
    fun isKindEnabled(file: File, config: CleanupConfig): Boolean = when (kindOf(file)) {
        CaptureKind.IMAGE -> config.deleteImages
        CaptureKind.VIDEO -> config.deleteVideos
        null -> false
    }

    /** Vrai si [file] est une capture activée, assez vieille et hors écriture en cours. */
    fun isDue(file: File, config: CleanupConfig, nowMs: Long): Boolean {
        val kind = kindOf(file) ?: return false
        val enabled = when (kind) {
            CaptureKind.IMAGE -> config.deleteImages
            CaptureKind.VIDEO -> config.deleteVideos
        }
        return enabled && ageMs(file, nowMs) >= minAgeMs(config) && !isBeingWritten(file, config, nowMs)
    }

    /** Âge du fichier, jamais négatif (horloge système qui recule). */
    fun ageMs(file: File, nowMs: Long): Long = (nowMs - file.lastModified()).coerceAtLeast(0L)

    /** Seuil d'âge : la rétention demandée, ou 0 quand tout doit partir. */
    fun minAgeMs(config: CleanupConfig): Long =
        if (config.maxAgeMinutes <= 0L) 0L else config.maxAgeMinutes * 60_000L

    /** Écriture en cours : fichier touché il y a moins de [CleanupConfig.inProgressGraceSeconds]. */
    fun isBeingWritten(file: File, config: CleanupConfig, nowMs: Long): Boolean =
        ageMs(file, nowMs) < config.inProgressGraceSeconds * 1_000L

    /** Parcours récursif sans lien symbolique et sans exception sur dossier illisible. */
    private fun walk(dir: File): List<File> {
        val children = dir.listFiles() ?: return emptyList()
        return children.flatMap { child ->
            when {
                child.isDirectory && !child.isSymlink() -> walk(child)
                child.isFile && !child.isSymlink() -> listOf(child)
                else -> emptyList()
            }
        }
    }

    /** `java.nio` évite un `canonicalPath` coûteux et une exception sur chemin cassé. */
    private fun File.isSymlink(): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(toPath())
    }.getOrDefault(false)
}
