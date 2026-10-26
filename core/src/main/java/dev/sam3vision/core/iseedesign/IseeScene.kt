package dev.sam3vision.core.iseedesign

/**
 * Logique pure du design « vidéo iSee » : pagination du gros texte (3 lignes max à
 * l'écran, le reste en pages qui remontent), positions déterministes des satellites
 * autour de la grande bulle, échantillonnage des pixels et tirage des 16 apparences
 * de bulles. Tout est déterministe : même entrée = même rendu, donc testable sur JVM.
 */

/** Les états visibles du design, dans l'ordre de progression d'un message. */
enum class IseeScene {
    /** Prêt : la bulle de verre stagne, texte de l'intro. */
    READY,

    /** Enregistrement vidéo : la carte caméra vit au-dessus du gros texte. */
    VISION,

    /** Analyse : les pixels de la photo fondent dans la bulle. */
    THINKING,

    /** Voix en cours de sortie : marbrures caustiques + révélation du texte. */
    VOICE,

    /** Réponse complète : anneau de lumière autour de la bulle. */
    ANSWERED,

    /** Message passé relu : fluides voyageurs, pill rouille, date si connue. */
    MEMORY,

    /** Écoute : liquide bleu montant + ticks bleues autour de la bulle. */
    LISTENING,
}

/** Largeur du gros texte en colonnes de référence (~19 caractères par ligne). */
private const val LINE_CHARS = 19

/** Le gros texte montre au plus 3 lignes à l'écran, jamais plus. */
const val HEADING_MAX_LINES = 3

/**
 * Le gros texte devient des pages de 3 lignes : le reste glisse par au fil de la
 * révélation. Le glissement garde du contexte : la **dernière des 3 lignes** d'une
 * page devient la **première** de la suivante (la ligne de lecture ne saute pas).
 * Pur : la découpe d'un même texte est toujours la même, et la jointure entre les
 * pages est exacte d'une ligne.
 */
fun textPages(text: String, maxLines: Int = HEADING_MAX_LINES): List<List<String>> {
    val lines = wrap(text)
    if (lines.isEmpty() || lines == listOf("")) return listOf(listOf(""))
    val stride = maxLines - 1
    val pages = mutableListOf<List<String>>()
    var from = 0
    while (from < lines.size) {
        val to = (from + maxLines).coerceAtMost(lines.size)
        pages.add(lines.subList(from, to))
        if (to == lines.size) break
        from += stride
    }
    return pages
}

/**
 * Découpe une phrase en lignes d'au plus [LINE_CHARS] caractères.
 */
fun wrap(text: String, charsCol: Int = LINE_CHARS): List<String> {
    val words = text.split(" ").filter { it.isNotEmpty() }
    if (words.isEmpty()) return if (text.isEmpty()) listOf("") else listOf(text)
    val rows = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
        if (current.isEmpty()) {
            current.append(word)
        } else if (current.length + 1 + word.length <= charsCol) {
            current.append(" ").append(word)
        } else {
            rows.add(current.toString())
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) rows.add(current.toString())
    return rows
}

/**
 * Positions des satellites autour de la grande bulle, en coordonnées locales de
 * la scène (centre 0,0 ; 1 = rayon de la grande bulle). À la façon de la vidéo :
 * deux latéraux à gauche, deux à droite, un au-dessous au centre ; le dernier
 * glisse en haut-gauche s'il y en a plus. Pur, déterministe.
 */
fun satellitePositions(count: Int): List<Pair<Float, Float>> {
    require(count >= 0)
    val spots = mutableListOf<Pair<Float, Float>>(
        -0.86f to -0.05f,
        0.86f to 0.06f,
        -0.64f to -0.62f,
        0.62f to -0.66f,
        0.05f to 0.93f,
        -0.95f to 0.52f,
        0.95f to 0.50f,
        -0.40f to -0.92f,
        0.42f to -0.92f,
        -0.30f to 0.88f,
    )
    if (count >= spots.size) {
        return spots
    }
    return spots.subList(0, count)
}

/**
 * Échantillonne une image (ici : un tableau ARGB plein, ligne après ligne) en une
 * grille [rows]×[cols] de couleurs : chaque cellule est la moyenne de sa zone.
 * Pur, déterministe, testable sur JVM : l'app fournit le tableau depuis n'importe
 * quelle source Android (bitmap de preview, photo), le cœur ne voit que des Int.
 */
fun sampleGrid(
    colors: IntArray,
    width: Int,
    height: Int,
    rows: Int,
    cols: Int,
): IntArray {
    val out = IntArray(rows * cols)
    if (width <= 0 || height <= 0 || rows <= 0 || cols <= 0) return out
    for (cell in 0 until rows * cols) {
        val row = cell / cols
        val col = cell % cols
        // Zone rectangulaire entière, en indices flottants arrondis au pixel.
        val y0 = row * height / rows
        val y1 = (row + 1) * height / rows
        val x0 = col * width / cols
        val x1 = (col + 1) * width / cols
        var sumA = 0L
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L
        for (y in y0 until y1.coerceAtMost(height)) {
            for (x in x0 until x1.coerceAtMost(width)) {
                val c = colors[y * width + x]
                sumA += (c ushr 24) and 0xFF
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
                count++
            }
        }
        if (count == 0L) continue
        fun chan(sum: Long): Int = (sum / count).toInt() and 0xFF
        out[cell] = (chan(sumA) shl 24) or
            (chan(sumR) shl 16) or
            (chan(sumG) shl 8) or
            chan(sumB)
    }
    return out
}

/**
 * Apparences des bulles : 16 motifs verre/fluide, tirés de la vidéo et de
 * variantes « plus premium, moins girly ». Le tirage est un simple modulo d'un
 * hash dispersé : deux bulles voisines ne partagent presque jamais la même
 * apparence, un même identifiant ne change jamais d'apparence.
 */
enum class BubbleLook {
    PLANET_OCEAN, // bleu profond + continents orange (la bulle réponse de la vidéo)
    RAINBOW_MARBLE, // caustiques multicolores (intro, vidéo)
    FLUID_MEMBRANE, // horizontale ondulée
    LAGOON_SWIRL, // tourbillons turquoise
    COPPER_MARBLE, // cuivre chaud
    VIOLET_WORLD, // violet profond (voix)
    TRAIL_WEAVE, // fils de pixels tissés (mémoire)
    MILKY_GLASS, // verre laiteux
    JADE_MARBLE, // jade
    NEON_RIVER, // néon cyan-magenta
    EMBER_WORLD, // braises orange (mémoire de la vidéo)
    SLATE_MARBLE, // ardoise (moins girly)
    ULTRAVIOLET_DARK, // violet sombre à reflets
    GRAPHITE_IRIS, // irisation gris acier
    TEAL_PLANET, // planète turquoise
    CORAL_FLUTTER, // corail doux
}

/** Index d'apparence d'une bulle : dispersé, stable pour un même ID. */
fun bubbleLookIndex(id: Long): Int {
    val hash = (id * -7046029254386353131L) xor (id ushr 31)
    return ((hash ushr 33).toInt() and Int.MAX_VALUE) % BubbleLook.values().size
}

/**
 * Pixels de la photo qui fondent dans la bulle : le plan de déplacement d'une
 * cellule. Pur, déterministe : la même cellule, au même instant, part du même
 * endroit et arrive au même endroit, avec la même trajectoire.
 *
 * @param fromT   position de départ, en parts de scène (0 gauche, 1 droite)
 * @param toT     position d'arrivée, en parts de scène, centre de la bulle
 * @param t       instant de la trajectoire, 0…1 (peut dépasser pour une avance)
 * @return la position (x, y) en parts de scène, la taille de pixel (1 = cellule)
 *   et l'opacité conseillée (0…1).
 */
data class PixelTrip(val x: Float, val y: Float, val size: Float, val alpha: Float)

fun pixelTrip(
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    t: Float,
    seed: Int,
): PixelTrip {
    val clamped = t.coerceAtLeast(0f)
    // Départ décalé : chaque cellule part un peu plus tard que la voisine.
    val start = (seed % 13) / 40f
    val flight = ((clamped - start) / 0.72f).coerceIn(0f, 1f)
    // Trajectoire : le pixel part de sa case, s'incurve vers la bulle en arc
    // (si plus à gauche du centre, il passe par dessus), puis se pose dedans.
    val vx = fromX + (toX - fromX) * flight
    // Survol : l'altitude gagne un dôme placé au centre de course.
    val dome = 4f * flight * (1f - flight)
    val vy = fromY + (toY - fromY) * flight - dome * 0.35f
    val landing = (flight - 0.85f).coerceAtLeast(0f) / 0.15f
    return PixelTrip(
        x = vx,
        y = vy,
        size = 1f - landing * 0.35f,
        alpha = if (flight >= 1f) 0.9f else 1f,
    )
}
