package dev.sam3vision.core.crossing

import dev.sam3vision.core.model.SegObject
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Harnais de l'éval caisson : la chaîne détection→verdict rejouée en JVM sur
 * des frames synthétiques type caisson SAM (boîtier sombre, petite lentille
 * allumée — le domaine réel de `docs/MESURES-SAM3-FEUX.md` §6, pas la lampe
 * cadrée serré du corpus de calibrage).
 *
 * Chaîne : [CrossingPrompts.selectLightBox] (vrai code) → [cropNorm] (miroir
 * JVM de `CrossingPixels.crop`, `:app` non testable en JVM : crop sur boîte
 * normalisée + réduction au bord 48, plus proche voisin) →
 * [PedLightGate.verdict] (vrai code). Les verdicts attendus sont pinés dans
 * `feuxevel/RECALL-v1.json` (pré-calculés par le miroir Python
 * `tools/light_features.py`, parité verrouillée en CI sur 20 ancres).
 */
internal const val CAISSON_FRAME = 128

/** Bord max du crop, miroir de `CrossingPixels.MAX_EDGE`. */
internal const val CAISSON_MAX_EDGE = 48

/** Caisson éteint : valeur 0,16 < MIN_VALUE — invisible du pipeline. */
private const val HOUSING = 0xFF2A2A2A.toInt()

/** Position verticale de la lentille dans le caisson. */
internal enum class LensSpot { HAUT, BAS, CENTRE }

/** Un cas d'éval : boîte SAM, vérité, peinture de la lentille dans la frame. */
internal class EvalCase(
    val id: String,
    val rect: IntArray,
    val truth: CrossingState,
    val paint: (IntArray) -> Unit,
)

/** Caisson synthétique : lentille disque [color] (null = éteint). */
internal fun syntheticCase(
    id: String,
    rect: IntArray,
    color: Int?,
    radius: Int,
    spot: LensSpot,
    truth: CrossingState,
): EvalCase = EvalCase(id, rect, truth) { frame ->
    if (color != null) {
        val (cx, cy) = lensCenter(rect, spot)
        paintDisc(frame, CAISSON_FRAME, CAISSON_FRAME, cx, cy, radius, color)
    }
}

/** Crop réel embarqué dans la frame (mêmes octets que `feuxfr/`/`feuxfr2/`). */
internal fun embeddedCase(
    id: String,
    rect: IntArray,
    loader: ClassLoader?,
    dir: String,
    name: String,
    truth: CrossingState,
): EvalCase {
    val (pixels, w, h) = pixelsOf(loader, dir, name)
    require(w == rect[2] - rect[0] && h == rect[3] - rect[1]) { "$id : crop $w×$h ≠ boîte" }
    return EvalCase(id, rect, truth) { frame -> blit(frame, pixels, rect) }
}

/** Les 12 cas : balayage de taille (rappel) + sûreté + crops réels (chaîne verte). */
internal fun evalCases(loader: ClassLoader?): List<EvalCase> = listOf(
    syntheticCase("green-h64", intArrayOf(48, 32, 80, 96), GREEN, 8, LensSpot.BAS, CrossingState.Green),
    syntheticCase("green-h32", intArrayOf(56, 48, 72, 80), GREEN, 4, LensSpot.BAS, CrossingState.Green),
    syntheticCase("green-h24", intArrayOf(58, 52, 70, 76), GREEN, 3, LensSpot.BAS, CrossingState.Green),
    syntheticCase("green-h16", intArrayOf(60, 56, 68, 72), GREEN, 2, LensSpot.BAS, CrossingState.Green),
    syntheticCase("green-h12", intArrayOf(61, 58, 67, 70), GREEN, 1, LensSpot.BAS, CrossingState.Green),
    syntheticCase("green-h08", intArrayOf(62, 60, 66, 68), GREEN, 1, LensSpot.BAS, CrossingState.Green),
    syntheticCase("red-h32", intArrayOf(56, 48, 72, 80), RED, 4, LensSpot.HAUT, CrossingState.Red),
    syntheticCase("off-h32", intArrayOf(56, 48, 72, 80), null, 0, LensSpot.BAS, CrossingState.Red),
    syntheticCase("car-disc", intArrayOf(52, 52, 76, 76), GREEN, 8, LensSpot.CENTRE, CrossingState.Red),
    embeddedCase("real-green", intArrayOf(40, 40, 77, 77), loader, "feuxfr", "fr_green_fig_21.ppm", CrossingState.Green),
    embeddedCase("real-red", intArrayOf(40, 40, 88, 88), loader, "feuxfr", "fr_red_fig_548.ppm", CrossingState.Red),
    embeddedCase("real-r1b", intArrayOf(40, 40, 61, 68), loader, "feuxfr2", "r1b_g01.ppm", CrossingState.Green),
)

/** Centre de la lentille (rayon h/8, plancher 1 — disques contenus au caisson). */
private fun lensCenter(rect: IntArray, spot: LensSpot): Pair<Int, Int> {
    val w = rect[2] - rect[0]
    val h = rect[3] - rect[1]
    val cx = rect[0] + w / 2
    val cy = rect[1] + when (spot) {
        LensSpot.HAUT -> h / 3
        LensSpot.CENTRE -> h / 2
        LensSpot.BAS -> 2 * h / 3
    }
    return cx to cy
}

/** Copie du crop réel dans son caisson (mêmes dimensions, vérifiées). */
private fun blit(frame: IntArray, pixels: IntArray, rect: IntArray) {
    val w = rect[2] - rect[0]
    for (i in pixels.indices) {
        frame[(rect[1] + i / w) * CAISSON_FRAME + rect[0] + i % w] = pixels[i]
    }
}

/** Frame 128 : fond noir, caisson sombre, lentille peinte. */
internal fun frameOf(case: EvalCase): IntArray {
    val frame = IntArray(CAISSON_FRAME * CAISSON_FRAME) { BLACK }
    val r = case.rect
    for (y in r[1] until r[3]) {
        for (x in r[0] until r[2]) frame[y * CAISSON_FRAME + x] = HOUSING
    }
    case.paint(frame)
    return frame
}

private val CAR_RECT = intArrayOf(8, 8, 40, 40)
private val SLIVER_RECT = intArrayOf(100, 100, 101, 101)

/** Objets SAM du cas : caisson + voiture à meilleur score + éclat à meilleur score. */
internal fun lightObjects(rect: IntArray): List<SegObject> = listOf(
    SegObject("pedestrian traffic light", 0.90f, rect.map { it.toFloat() }, ""),
    SegObject("car", 0.99f, CAR_RECT.map { it.toFloat() }, ""),
    SegObject("pedestrian traffic light", 0.95f, SLIVER_RECT.map { it.toFloat() }, ""),
)

/** Boîte normalisée choisie par la détection (vrai code). */
internal fun detectBox(rect: IntArray): List<Float>? =
    CrossingPrompts.selectLightBox(lightObjects(rect), CAISSON_FRAME, CAISSON_FRAME)

/** Crop sur boîte normalisée + réduction au bord 48 (miroir `CrossingPixels.crop`). */
internal fun cropNorm(frame: IntArray, norm: List<Float>): Triple<IntArray, Int, Int> {
    val left = (norm[0].coerceIn(0f, 1f) * CAISSON_FRAME).roundToInt().coerceIn(0, CAISSON_FRAME - 1)
    val top = (norm[1].coerceIn(0f, 1f) * CAISSON_FRAME).roundToInt().coerceIn(0, CAISSON_FRAME - 1)
    val right = (norm[2].coerceIn(0f, 1f) * CAISSON_FRAME).roundToInt().coerceIn(left + 1, CAISSON_FRAME)
    val bottom = (norm[3].coerceIn(0f, 1f) * CAISSON_FRAME).roundToInt().coerceIn(top + 1, CAISSON_FRAME)
    val w = right - left
    val h = bottom - top
    val crop = IntArray(w * h) { i -> frame[(top + i / w) * CAISSON_FRAME + left + i % w] }
    return reduceEdge(crop, w, h)
}

/** Réduction au bord 48, plus proche voisin (division entière = plancher). */
internal fun reduceEdge(crop: IntArray, w: Int, h: Int): Triple<IntArray, Int, Int> {
    val nw = minOf(CAISSON_MAX_EDGE, w)
    val nh = minOf(CAISSON_MAX_EDGE, h)
    val out = IntArray(nw * nh) { i -> crop[(i / nw * h / nh) * w + (i % nw * w / nw)] }
    return Triple(out, nw, nh)
}

/** Chaîne complète : détection → crop → verdict (échoue fort sans boîte). */
internal fun runChain(case: EvalCase): CrossingState {
    val norm = detectBox(case.rect) ?: error("boîte non détectée : ${case.id}")
    val (pixels, w, h) = cropNorm(frameOf(case), norm)
    return PedLightGate.verdict(pixels, w, h)
}

/** Doré versionné : verdicts par cas + agrégats de rappel. */
internal data class EvalGolden(
    val version: Int,
    val verdicts: Map<String, String>,
    val recall: Map<String, String>,
)

/** Lecture du doré `feuxevel/RECALL-v1.json` (échoue fort si absent). */
internal fun loadGolden(loader: ClassLoader?): EvalGolden {
    val text = loader?.getResourceAsStream("feuxevel/RECALL-v1.json")?.bufferedReader()?.readText()
        ?: error("ressource manquante : feuxevel/RECALL-v1.json")
    val json = JSONObject(text)
    val cases = json.getJSONArray("cases")
    val verdicts = mutableMapOf<String, String>()
    for (i in 0 until cases.length()) {
        val c = cases.getJSONObject(i)
        verdicts[c.getString("id")] = c.getString("verdict")
    }
    val recall = mutableMapOf<String, String>()
    val node = json.getJSONObject("recall")
    for ((key, value) in node.toMap()) recall[key] = value as String
    return EvalGolden(json.getInt("version"), verdicts, recall)
}

/** Rappel « atteints/total » sur [ids] (verts confirmés parmi les cas cités). */
internal fun recallOf(ids: List<String>, verdicts: Map<String, CrossingState>): String {
    val hit = ids.count { verdicts[it] == CrossingState.Green }
    return "$hit/${ids.size}"
}
