package dev.sam3vision.app.isee

import dev.sam3vision.core.picto.PictoTemplate
import java.io.File

/** Dossier des repères dans les fichiers privés : le seul magasin, Direct comme iSee. */
const val PICTOS_DIR = "pictos"

/** Dossier des repères pour un [filesDir] : l'unique construction du chemin, Direct comme iSee. */
fun pictosDir(filesDir: File): File = File(filesDir, PICTOS_DIR)

/**
 * Un repère validé : son nom, son PNG et son gabarit de reconnaissance.
 * Les octets sont chargés d'un coup : quelques pictos de quelques Ko chacun.
 */
data class StoredPicto(
    val id: String,
    val label: String,
    val png: ByteArray,
    val template: PictoTemplate,
)

/**
 * Les repères, dans un dossier persistant (`filesDir/pictos`, jamais le cache :
 * la purge FIFO ne doit pas manger les objets appris). Un dossier par picto —
 * `label.txt`, `picto.png`, `template.bin` — pas d'index à corrompre : lister,
 * c'est lister les dossiers. Une entrée illisible est ignorée, pas fatale.
 *
 * Entrées-sorties synchrones : l'appelant travaille hors du thread principal.
 */
class PictoStore(private val root: File) {

    /**
     * Tous les repères valides, par ordre de création. Rapatrie d'abord les
     * égarés ([adoptLoose]) : le Direct écrivait à la racine avant l'unification.
     */
    fun list(): List<StoredPicto> {
        adoptLoose()
        return root.listFiles()?.sortedBy { it.name }?.mapNotNull { readDir(it) } ?: emptyList()
    }

    /**
     * Rapatrie les repères créés avant l'unification du magasin (dossiers
     * `picto-*` restés à la racine des fichiers privés) dans ce dossier.
     * Ne fait rien quand ce magasin n'est pas le dossier [PICTOS_DIR]
     * (tests, ancien chemin), ni quand il n'y a plus rien à rapatrier.
     * Seuls les dossiers valides voyagent ; un homonyme déjà présent est
     * laissé en place, jamais écrasé.
     */
    private fun adoptLoose() {
        if (root.name != PICTOS_DIR) return
        val parent = root.parentFile ?: return
        root.mkdirs()
        parent.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(PICTO_PREFIX) }
            ?.forEach { dir ->
                val target = File(root, dir.name)
                if (!target.exists() && readDir(dir) != null) dir.renameTo(target)
            }
    }

    /** PNG d'un repère pour l'overlay, `null` si inconnu ou illisible. */
    fun pngOf(id: String): ByteArray? = readDir(File(root, id))?.png

    /** Enregistre un repère validé ; lève sur écriture impossible. */
    fun save(label: String, png: ByteArray, template: PictoTemplate): StoredPicto {
        val dir = newPictoDir()
        dir.mkdirs()
        File(dir, LABEL_FILE).writeText(label.trim().ifEmpty { DEFAULT_LABEL }, Charsets.UTF_8)
        File(dir, PNG_FILE).writeBytes(png)
        File(dir, TEMPLATE_FILE).writeBytes(encodeTemplate(template))
        return readDir(dir) ?: throw IllegalStateException("repère illisible après écriture")
    }

    /**
     * Dossier neuf `picto-<millis>` : en cas d'homonyme (deux créations la même
     * milliseconde, Direct + iSee), le millis avance jusqu'à un nom libre —
     * jamais d'écrasement.
     */
    internal fun newPictoDir(nowMs: Long = System.currentTimeMillis()): File {
        var stamp = nowMs
        var dir = File(root, "$PICTO_PREFIX$stamp")
        while (dir.exists()) {
            stamp += 1
            dir = File(root, "$PICTO_PREFIX$stamp")
        }
        return dir
    }

    private fun readDir(dir: File): StoredPicto? {
        if (!dir.isDirectory) return null
        return runCatching {
            val label = File(dir, LABEL_FILE).readText(Charsets.UTF_8).trim()
            val png = File(dir, PNG_FILE).readBytes()
            val template = decodeTemplate(File(dir, TEMPLATE_FILE).readBytes())
            if (label.isEmpty() || png.isEmpty() || template == null) null
            else StoredPicto(dir.name, label, png, template)
        }.getOrNull()
    }

    private fun encodeTemplate(template: PictoTemplate): ByteArray {
        val out = ByteArray(2 + template.gray.size)
        out[0] = (template.edge shr 8).toByte()
        out[1] = template.edge.toByte()
        template.gray.forEachIndexed { i, v -> out[i + 2] = v.toByte() }
        return out
    }

    private fun decodeTemplate(bytes: ByteArray): PictoTemplate? {
        if (bytes.size < 3) return null
        val edge = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (edge <= 0 || bytes.size != 2 + edge * edge) return null
        return PictoTemplate(IntArray(edge * edge) { i -> bytes[i + 2].toInt() and 0xFF }, edge)
    }

    companion object {
        private const val LABEL_FILE = "label.txt"
        private const val PNG_FILE = "picto.png"
        private const val TEMPLATE_FILE = "template.bin"
        private const val PICTO_PREFIX = "picto-"

        /** Nom quand l'aidant valide sans rien saisir. */
        const val DEFAULT_LABEL = "Objet"
    }
}
