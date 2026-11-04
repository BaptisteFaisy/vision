package dev.sam3vision.core.glasses

import java.io.File
import org.junit.Test

/**
 * Garde de compatibilité : le SDK DAT doit rester à la version que les lunettes servent.
 *
 * Le runtime DAT servi **aux Ray-Ban Meta** est `1.0.1.x` depuis la vague Meta AI 296
 * + mise à jour sécurité obligatoire du 29/10/2026 (avec le SDK 1.0.0, les lunettes
 * coupent chaque session pendant le handshake — `SESSION_ENDED_BY_DEVICE` sans
 * jamais `STARTED`, mesuré le 04/11/2026). Si ce test casse, ce n'est pas une
 * formalité : c'est le mode réel qui cesse de fonctionner.
 */
class DatPinTest {

    @Test
    fun `le SDK DAT reste sur la version servie aux lunettes`() {
        val pin = Regex("""^\s*mwdat\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(versionFile().readText())
            ?.groupValues
            ?.get(1)
        check(pin == EXPECTED) {
            "gradle/libs.versions.toml : mwdat doit rester à $EXPECTED — le runtime des " +
                "Ray-Ban Meta servi par Meta AI 296+ est 1.0.1.x ; un SDK plus vieux fait " +
                "couper chaque session pendant le handshake (lu : ${pin ?: "aucune"})."
        }
    }

    /** Le fichier de versions, trouvé en remontant depuis le dossier du module de test. */
    private fun versionFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "gradle/libs.versions.toml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("gradle/libs.versions.toml introuvable depuis ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val EXPECTED = "1.0.1"
    }
}