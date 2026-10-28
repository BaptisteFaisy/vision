package dev.sam3vision.core.picto

/** Modèle de génération d'image Meta (même base URL et même clé que l'ASR). */
const val IMAGE_MODEL = "muse-image-1.0"

/**
 * Format demandé : carré. Sur ces endpoints `size` ne règle que le rapport
 * d'aspect, pas les pixels exacts — le PNG rendu est remis à l'échelle côté app.
 */
const val IMAGE_SIZE_SQUARE = "1024x1024"

/**
 * Consigne envoyée avec la photo : redessiner l'objet principal centré en
 * pictogramme minimaliste — silhouette noire épaisse sur fond jaune uni, sans
 * ombre, sans texte, sans décor. En anglais : le modèle y est plus fiable.
 * [hint] est le nom donné par l'aidant (« boîte de médicaments »), joint tel
 * quel quand il est renseigné pour lever les ambiguïtés (deux objets proches).
 */
fun pictoPrompt(hint: String): String {
    val subject = hint.trim().ifEmpty { "the main centered object" }
    return "Redraw $subject from the input photo as a minimalist pictogram: " +
        "thick black silhouette on a solid bright-yellow background, flat vector style, " +
        "high contrast, no shadows, no text, no letters, no background clutter, " +
        "single centered symbol filling most of the square."
}
