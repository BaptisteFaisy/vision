package dev.sam3vision.core.net

import java.io.ByteArrayOutputStream
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Multipart Meta écrit à la main, partagé par l'ASR et la génération d'image.
 *
 * `MultipartBody` d'OkHttp ajoute un `Content-Length` par part, que Meta refuse
 * (`400 Malformed multipart body`) : ici une seule longueur, celle de la requête
 * (posée par OkHttp sur le corps assemblé). L'ordre des parts est l'ordre d'appel.
 */
class MetaMultipart(private val boundary: String = freshBoundary()) {

    private val out = ByteArrayOutputStream()

    /** Champ texte simple (`model`, `prompt`, …) : disposition seule, sans type. */
    fun text(name: String, value: String): MetaMultipart = apply {
        writePart(listOf("Content-Disposition: form-data; name=\"$name\""), value.toByteArray(Charsets.UTF_8))
    }

    /** Champ JSON (`request` de l'ASR) : disposition + `application/json`. */
    fun json(name: String, json: String): MetaMultipart = apply {
        writePart(
            listOf(
                "Content-Disposition: form-data; name=\"$name\"",
                "Content-Type: application/json",
            ),
            json.toByteArray(Charsets.UTF_8),
        )
    }

    /** Champ fichier (`audio`, `image`) : disposition + nom + type MIME. */
    fun file(name: String, filename: String, contentType: String, bytes: ByteArray): MetaMultipart = apply {
        writePart(
            listOf(
                "Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"",
                "Content-Type: $contentType",
            ),
            bytes,
        )
    }

    private fun writePart(headers: List<String>, body: ByteArray) {
        out.write(("--$boundary$CRLF${headers.joinToString(CRLF)}$CRLF$CRLF").toByteArray(Charsets.UTF_8))
        out.write(body)
        out.write(CRLF.toByteArray(Charsets.UTF_8))
    }

    /** Corps assemblé : une seule part de fermeture, un seul type MIME global. */
    fun build(): RequestBody {
        out.write(("--$boundary--$CRLF").toByteArray(Charsets.UTF_8))
        return out.toByteArray().toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())
    }

    companion object {
        private const val CRLF = "\r\n"

        /** Frontière `----sam3vision<uuid>` : même forme que l'ASR historique. */
        fun freshBoundary(): String = "----sam3vision" + UUID.randomUUID().toString().replace("-", "")
    }
}
