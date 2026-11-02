package dev.sam3vision.core.live

/**
 * Voile des objets suivis : un label de décor ([BACKGROUND]) garde pastille et
 * voix, mais son masque est peint transparent — un voile à mi-écran (mur bleu
 * du 01/11, rue « coupée en deux ») n'apporte aucune forme actionnable.
 * Pur, testé en JVM (VeilPolicyTest).
 *
 * Le label renvoyé est le prompt tel que saisi (contrat serveur, pas le
 * concept anglais) : chaque famille est donc listée dans les deux langues,
 * miroir des alias serveur (`mur`, `murs`, `muret`, `murets` → `wall`).
 * Sans elles, le mur tapé en français voilait encore la rue en deux (01/11).
 * Le sol plein-champ dehors (trottoir, passage piéton) coupait la rue du
 * même trait horizontal persistant (02/11) : même remède. La bordure, elle,
 * voile : détectée, elle reste visible à la caméra (ordre user du 02/11).
 */
object VeilPolicy {

    /** Labels de décor : suivis comme les autres, jamais voilés. */
    private val BACKGROUND = setOf(
        "wall", "walls", "mur", "murs", "muret", "murets",
        "sidewalk", "sidewalks", "trottoir", "trottoirs",
        "crosswalk", "crosswalks",
        "passage piéton", "passages piétons", "passage pieton", "passages pietons",
    )

    /** `false` = décor : pastille et voix gardées, voile transparent. */
    fun showsVeil(label: String): Boolean = label.trim().lowercase() !in BACKGROUND
}
