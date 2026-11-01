package dev.sam3vision.core.live

/**
 * Voile des objets suivis : un label de décor ([BACKGROUND]) garde pastille et
 * voix, mais son masque est peint transparent — un voile à mi-écran (mur bleu
 * du 01/11, rue « coupée en deux ») n'apporte aucune forme actionnable.
 * Pur, testé en JVM (VeilPolicyTest).
 */
object VeilPolicy {

    /** Labels de décor : suivis comme les autres, jamais voilés. */
    private val BACKGROUND = setOf("wall")

    /** `false` = décor : pastille et voix gardées, voile transparent. */
    fun showsVeil(label: String): Boolean = label.trim().lowercase() !in BACKGROUND
}
