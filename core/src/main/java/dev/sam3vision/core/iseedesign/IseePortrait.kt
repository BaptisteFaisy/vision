package dev.sam3vision.core.iseedesign

/**
 * Le design « Portrait » de l'écran iSee (choisi sur la maquette web) : logique pure, testable
 * sur JVM. La rue occupe le haut de l'écran et se fond dans le blanc ; la boule de verre est
 * posée sous l'image, le court passage en cours dessous. Couleurs « Amber » : le user en bleu,
 * iSee en ambre. Aucun pixel sur la boule : verre + liquide marbré seulement.
 */
object IseePortrait {
    /** Couleurs Amber, en ARGB. */
    const val YOU = 0xFF0B5FFF.toInt()
    const val YOU_INK = 0xFF0A50D8.toInt()
    const val SEE = 0xFFF5A300.toInt()
    const val SEE_INK = 0xFFC98200.toInt()
    const val INK = 0xFF0B1220.toInt()
    const val SUB = 0xFF6A7486.toInt()

    /** Mots pas encore dits : l'encre à 18 % (gris très clair). */
    const val DIM = 0x2E0B1220

    /** Les cinq teintes du liquide au repos, quand le user parle, quand iSee parle. */
    val IDLE = intArrayOf(0xFF0B5FFF.toInt(), 0xFF3FB8FF.toInt(), 0xFFFFC93C.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFE8A3.toInt())
    val PAL_YOU = intArrayOf(0xFF0A4FD8.toInt(), 0xFF0B5FFF.toInt(), 0xFF4C8DFF.toInt(), 0xFF93BBFF.toInt(), 0xFF3FB8FF.toInt())
    val PAL_SEE = intArrayOf(0xFFF59E0B.toInt(), 0xFFFFC93C.toInt(), 0xFFFFB020.toInt(), 0xFFFFE08A.toInt(), 0xFFFF8A3D.toInt())

    /** Boule au repos : centre à 40 % de la hauteur, rayon 33,5 % de la largeur. */
    const val REST_Y = 0.40f
    const val REST_R = 0.335f

    /** Caméra ouverte : plus haute au repos, compacte pendant la parole. */
    const val PICTURE_H = 0.58f
    const val PICTURE_IDLE_H = 0.64f
    const val PICTURE_SPEAKING_H = 0.50f
    const val CAM_Y = 0.555f
    const val CAM_R = 0.20f

    /** La rue se fond dans le blanc sur ses 34 % du bas. */
    const val FADE = 0.34f
}

/** Géométrie d'une image de l'écran, en pixels (y vers le bas). */
data class PortraitGeometry(
    val orbX: Float,
    val orbY: Float,
    val orbR: Float,
    /** Hauteur de la rue (0 = caméra fermée). */
    val pictureH: Float,
    /** Haut de la phrase en cours. */
    val textTop: Float,
)

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Lissage d'une ouverture 0..1 (smoothstep). */
fun smoothOpen(k: Float): Float {
    val u = k.coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

/**
 * Où sont la boule, la rue et la phrase pour un écran [width]×[height] quand la caméra est
 * ouverte à [open] (0 fermée, 1 ouverte). La phrase est toujours sous la boule.
 */
fun portraitGeometry(width: Float, height: Float, open: Float, speech: Float = 0f): PortraitGeometry {
    val k = smoothOpen(open)
    val talking = smoothOpen(speech)
    val cameraR = minOf(width * IseePortrait.CAM_R, height * 0.105f)
    val r = mix(width * IseePortrait.REST_R, cameraR, k)
    val picture = height * mix(IseePortrait.PICTURE_IDLE_H, IseePortrait.PICTURE_SPEAKING_H, talking)
    // Même la couronne et la respiration restent sous l'image, sans recouvrement.
    val cameraY = picture + cameraR * 1.32f + width * 0.025f
    val y = mix(height * IseePortrait.REST_Y, cameraY, k)
    return PortraitGeometry(
        orbX = width / 2f,
        orbY = y,
        orbR = r,
        pictureH = picture * k,
        textTop = y + r * 1.3f + width * 0.03f,
    )
}

/**
 * Opacité de la rue à la hauteur [y] (pixels) : pleine en haut, elle se fond dans le blanc sur
 * ses [IseePortrait.FADE] du bas (courbe douce), nulle sous la rue.
 */
fun pictureAlpha(y: Float, pictureH: Float): Float {
    if (pictureH <= 0f || y >= pictureH) return 0f
    val fromBottom = (pictureH - y) / pictureH
    val u = (fromBottom / IseePortrait.FADE).coerceIn(0f, 1f)
    val s = u * u * (3f - 2f * u)
    return s * s * (3f - 2f * s)
}

/**
 * Mise au point à l'ouverture : la rue arrive floue et un peu agrandie, puis devient nette.
 * Rend le rayon de flou (0..[maxBlur]) et l'échelle (1,05 → 1) pour une ouverture [open].
 */
data class Focus(val blur: Float, val scale: Float)

fun focusPull(open: Float, maxBlur: Float = 24f): Focus {
    val k = smoothOpen(open)
    val sharp = ((k - 0.3f) / 0.7f).coerceIn(0f, 1f)
    return Focus(blur = maxBlur * (1f - sharp * sharp * (3f - 2f * sharp)), scale = mix(1.05f, 1f, k))
}

/**
 * Enveloppe de voix déterministe (syllabes ~5,5/s sur des groupes de mots, petites pauses) pour
 * animer la boule quand aucune amplitude réelle n'est disponible. [t] en secondes, 0..1.
 */
fun voiceEnvelope(t: Float, seed: Int = 1): Float {
    val k = seed * 1.37f
    val syll = Math.pow(kotlin.math.abs(kotlin.math.sin(t * 17.3f + k)).toDouble(), 1.6).toFloat()
    val word = 0.55f + 0.45f * kotlin.math.sin(t * 4.1f + k * 2.1f) * kotlin.math.sin(t * 2.3f + k)
    val pause = smoothOpen((kotlin.math.sin(t * 1.9f + k * 3f) + 0.75f) * 3f)
    return ((0.25f + 0.75f * syll) * word * pause * 1.15f).coerceIn(0f, 1f)
}

/**
 * Combien de mots de [text] sont dits après [elapsedMs] d'une voix de [durationMs] : la phrase
 * s'allume mot à mot. Sans durée connue, ~2,6 mots/s.
 */
fun wordsSpoken(text: String, elapsedMs: Long, durationMs: Long?): Int {
    val n = text.split(" ").count { it.isNotEmpty() }
    if (n == 0) return 0
    val total = durationMs?.takeIf { it > 0 } ?: (n * 385L)
    val u = ((elapsedMs - 150L).toFloat() / (total - 300L).coerceAtLeast(300L)).coerceIn(0f, 1f)
    return minOf(n, (u * n).toInt() + if (elapsedMs > 150L) 1 else 0)
}
