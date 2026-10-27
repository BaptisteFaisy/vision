package dev.sam3vision.core.glasses

import java.io.File
import org.junit.Test

/**
 * Garde de compatibilité : le SDK DAT doit rester à la version que les lunettes servent.
 *
 * Le runtime DAT installé **sur les Ray-Ban Meta** est `1.0.0.0.0` — la seule version que
 * Meta AI y installe (mesuré le 27/10/2026). Un SDK plus récent ouvre la session et les
 * lunettes la coupent aussitôt (« Your glasses need an update »). Si ce test casse, ce n'est
 * pas une formalité : c'est le mode réel qui cesse de fonctionner.
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
                "Ray-Ban Meta est $EXPECTED.0.0 et Meta n'en sert pas d'autre ; relever la " +
                "version fait couper chaque session par les lunettes (lu : ${pin ?: "aucune"})."
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
        const val EXPECTED = "1.0.0"
    }
}