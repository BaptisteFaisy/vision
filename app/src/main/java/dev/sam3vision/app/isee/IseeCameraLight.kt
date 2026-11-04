package dev.sam3vision.app.isee

/**
 * Mesure lumière et piqué des frames iSee : la luminance moyenne nourrit le
 * gouverneur d'exposition, le flou est diagnostiqué (compté, jamais veté).
 * Pur, testé en JVM, extrait de `IseeCameraPane` (plafond 500 lignes).
 *
 * Il n'y a plus de veto nuit/noir : toute frame convertie part (ordre user,
 * la garde bloquait les tests et masquait l'état du lien).
 */

/**
 * Variance laplacienne sous laquelle une frame compte floue : seuil indicatif, à valider
 * sur matériel — le flou est diagnostiqué ([IseeCameraHost.blurryFrames]), jamais veté.
 */
const val BLUR_VAR_MIN = 50.0

/**
 * Luminance moyenne (0-255) d'un plan de luminance (passe NV21). Plan vide → 255.
 */
fun meanLuma(luma: ByteArray): Double {
    if (luma.isEmpty()) return 255.0
    var sum = 0L
    for (byte in luma) sum += byte.toInt() and 0xFF
    return sum.toDouble() / luma.size
}

/**
 * Luminance moyenne (0-255) depuis des pixels ARGB (repli `Bitmap`), au Rec.601
 * entier (77/150/29 : 0,30/0,59/0,11). Pixels absents → 255, comme [meanLuma].
 */
fun meanLumaFromArgb(argb: IntArray): Double {
    if (argb.isEmpty()) return 255.0
    var sum = 0L
    for (pixel in argb) {
        val red = pixel shr 16 and 0xFF
        val green = pixel shr 8 and 0xFF
        val blue = pixel and 0xFF
        sum += (red * 77 + green * 150 + blue * 29) shr 8
    }
    return sum.toDouble() / argb.size
}

/**
 * Variance du laplacien 4-voisins sur [luma] ([width]×[height]) : le piqué de la
 * frame — une image nette varie fort entre voisins, une floue pas. `NaN` si les
 * dimensions sont inexploitables (jamais veté : `NaN` ne passe sous aucun seuil).
 */
fun laplacianVariance(luma: ByteArray, width: Int, height: Int): Double {
    if (width < 3 || height < 3 || luma.size < width * height) return Double.NaN
    var sum = 0.0
    var squares = 0.0
    var count = 0L
    fun at(x: Int, y: Int): Int = luma[y * width + x].toInt() and 0xFF
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val lap = 4 * at(x, y) - at(x - 1, y) - at(x + 1, y) - at(x, y - 1) - at(x, y + 1)
            sum += lap
            squares += lap.toDouble() * lap
            count += 1
        }
    }
    if (count == 0L) return Double.NaN
    val mean = sum / count
    return squares / count - mean * mean
}
