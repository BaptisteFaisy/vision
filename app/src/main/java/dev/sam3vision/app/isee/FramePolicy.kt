package dev.sam3vision.app.isee

/**
 * Politique de frame SAM de la page caméra : 320 px envoyés, jamais agrandis.
 * Le capteur est demandé en 640×480 (le direct aidant prend 480 px) :
 * la réduction vers [EDGE] se fait alors dans la passe NV21.
 *
 * Le 2026-10-26, la conversion d'une frame coûtait 12-42 ms côté téléphone parce que
 * l'on demandait 320 px au capteur qui livrait 640×480, redimensionné ensuite pour rien
 * (et en tournant avant de réduire : la rotation coûte le nombre de pixels). On demande
 * donc désormais la taille qu'on envoie, et la règle est verrouillée par des tests.
 *
 * Pure et sans Android : c'est ce qui la rend testable en JVM.
 */
object FramePolicy {

    /** Côté long de l'image envoyée à SAM (px). */
    const val EDGE = 320

    /** Qualité JPEG de l'image envoyée (55 : invisible à l'œil, ~6 Ko par frame). */
    const val QUALITY = 55

    /**
     * Règle de choix de la taille quand le capteur ne peut pas livrer exactement
     * [EDGE]. `LOWER_THEN_HIGHER` = on prend la plus proche **en dessous** (une image
     * légèrement plus petite, jamais un buffer inutile) ; `HIGHER_THEN_LOWER` = l'inverse.
     */
    enum class Fallback { LOWER_THEN_HIGHER, HIGHER_THEN_LOWER }

    /** Règle appliquée : ne jamais demander plus gros que ce qu'on envoie. */
    val FALLBACK: Fallback = Fallback.LOWER_THEN_HIGHER

    /** Côté long d'un buffer caméra (px). */
    fun bufferEdge(width: Int, height: Int): Int = maxOf(width, height)

    /**
     * Côté long de l'image envoyée : celui du buffer, **borné par** [EDGE] et jamais
     * agrandi (un buffer plus petit que [EDGE] part tel quel, plutôt que d'être étiré).
     */
    fun sentEdge(bufferWidth: Int, bufferHeight: Int): Int =
        minOf(bufferEdge(bufferWidth, bufferHeight), EDGE)

    /**
     * Taille à demander au capteur : celle de l'image envoyée, ratio du capteur conservé.
     * Exemple : capteur 640×480 → 320×240 ; 1080×1920 → 180×320.
     *
     * Troncature volontaire (`toInt`), pas arrondi : ne pas factoriser avec
     * `GlassesFramePolicy.scaledDimensions` (arrondi + `null` si inutile) — les deux
     * politiques diffèrent par contrat, pas par oubli.
     */
    fun requestedSize(bufferWidth: Int, bufferHeight: Int): Pair<Int, Int> {
        val edge = sentEdge(bufferWidth, bufferHeight)
        val long = bufferEdge(bufferWidth, bufferHeight)
        if (long <= 0 || edge <= 0) return edge to edge
        val ratio = bufferWidth.toFloat() / bufferHeight.toFloat()
        return if (bufferWidth >= bufferHeight) {
            edge to maxOf(1, (edge / ratio).toInt())
        } else {
            maxOf(1, (edge * ratio).toInt()) to edge
        }
    }

    /** Vrai si le buffer reçu est déjà à l'échelle de l'image envoyée (pas de reduction). */
    fun isNativeScale(bufferWidth: Int, bufferHeight: Int): Boolean =
        bufferEdge(bufferWidth, bufferHeight) <= EDGE
}
