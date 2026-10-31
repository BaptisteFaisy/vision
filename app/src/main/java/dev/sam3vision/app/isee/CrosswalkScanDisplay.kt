package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrosswalkScanPhase
import dev.sam3vision.core.crossing.CrosswalkScanText
import dev.sam3vision.core.crossing.ScanSide
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ce que l'écran montre du scan traversée, en miroir de la voix : la
 * consigne ou le constat en cours ([current]) plus les constats latéraux
 * déjà rendus ([left], [right]). `null` partout ailleurs (voir
 * [crosswalkScanLinesOf]) : scan inactif = écran inchangé.
 *
 * Les constats affichés disent « en mouvement » là où la voix dit « Rien à
 * gauche. » / « Attention, voiture à gauche. » (ordre user : le visible nomme
 * le mouvement, pas le silence). La consigne en cours reprend la voix au mot
 * près ; un constat en cours reprend le texte affiché (pas celui de la voix),
 * pour ne pas lire deux phrases différentes pour le même côté.
 */
data class CrosswalkScanLines(
    /** Consigne/constat en cours (`null` = silence : re-vérification, feu couvert). */
    val current: String?,
    /** Constat gauche déjà rendu (`null` = pas encore parlé ce cycle). */
    val left: String?,
    /** Constat droit déjà rendu (`null` = pas encore parlé ce cycle). */
    val right: String?,
    /** Couleur du bandeau ([scanAccent]) : calculée où la langue est connue. */
    val accent: Int,
)

/**
 * Constat libre affiché du côté : aucun véhicule en mouvement vu.
 * « Pas de voiture à gauche en mouvement. » (ordre user).
 */
fun scanDisplayNothing(side: ScanSide, language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
    when (language) {
        SpeechLanguage.Fr -> when (side) {
            ScanSide.Left -> "Pas de voiture à gauche en mouvement."
            ScanSide.Right -> "Pas de voiture à droite en mouvement."
        }
        SpeechLanguage.En -> when (side) {
            ScanSide.Left -> "No car moving on the left."
            ScanSide.Right -> "No car moving on the right."
        }
    }

/**
 * Constat danger affiché du côté : un véhicule en mouvement vu.
 * « Voiture à gauche ! » (ordre user).
 */
fun scanDisplayDanger(side: ScanSide, language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
    when (language) {
        SpeechLanguage.Fr -> when (side) {
            ScanSide.Left -> "Voiture à gauche !"
            ScanSide.Right -> "Voiture à droite !"
        }
        SpeechLanguage.En -> when (side) {
            ScanSide.Left -> "Car on the left!"
            ScanSide.Right -> "Car on the right!"
        }
    }

/** Constat affiché du côté, libre ou danger. */
fun scanDisplayConstat(side: ScanSide, danger: Boolean, language: SpeechLanguage): String =
    if (danger) scanDisplayDanger(side, language) else scanDisplayNothing(side, language)

/**
 * Lignes affichées pour ([phase], constats latchés) : `null` quand le scan
 * est inactif ([CrosswalkScanPhase.Idle], [CrosswalkScanPhase.Done]) — rien à
 * l'écran. Les constats latchés (`null` = pas encore parlés ce cycle, le
 * guetteur les efface à chaque nouveau cycle) sont la seule porte des lignes
 * latérales : pas de latch, pas de ligne. Un constat en cours sans latch
 * (théoriquement impossible : la voix couvre) tait la ligne plutôt que de
 * deviner. Pur, testé en JVM.
 */
fun crosswalkScanLinesOf(
    phase: CrosswalkScanPhase,
    leftDanger: Boolean?,
    rightDanger: Boolean?,
    language: SpeechLanguage,
): CrosswalkScanLines? {
    if (phase == CrosswalkScanPhase.Idle || phase == CrosswalkScanPhase.Done) return null
    val current = when (phase) {
        CrosswalkScanPhase.Stop -> GuidanceWord.Stop.text(language)
        CrosswalkScanPhase.Left -> CrosswalkScanText.left(language)
        CrosswalkScanPhase.Right -> CrosswalkScanText.right(language)
        CrosswalkScanPhase.ReportLeft -> leftDanger?.let { scanDisplayConstat(ScanSide.Left, it, language) }
        CrosswalkScanPhase.ReportRight -> rightDanger?.let { scanDisplayConstat(ScanSide.Right, it, language) }
        CrosswalkScanPhase.Rewait -> null
        CrosswalkScanPhase.Idle, CrosswalkScanPhase.Done -> null
    }
    return CrosswalkScanLines(
        current = current,
        left = leftDanger?.let { scanDisplayConstat(ScanSide.Left, it, language) },
        right = rightDanger?.let { scanDisplayConstat(ScanSide.Right, it, language) },
        accent = scanAccent(leftDanger, rightDanger),
    )
}

/**
 * Lignes du bandeau : la consigne/constat en cours puis les constats déjà
 * rendus, sans doublon (un constat en cours est aussi le latché du côté).
 */
fun scanBannerLines(lines: CrosswalkScanLines): List<String> =
    listOfNotNull(lines.current, lines.left, lines.right).distinct()

/**
 * Couleur du bandeau scan (mêmes teintes que le verdict) : rouge dès qu'un
 * côté constate un danger, vert quand les deux côtés sont libres, ambre
 * pendant (consignes, premier constat libre seul).
 */
fun scanAccent(leftDanger: Boolean?, rightDanger: Boolean?): Int {
    if (leftDanger == true || rightDanger == true) return CROSSING_ACCENT_RED
    if (leftDanger == false && rightDanger == false) return CROSSING_ACCENT_GREEN
    return CROSSING_ACCENT_OFFER
}

/**
 * Constat (côté + danger) reconnu dans une phrase du scan (`null` = pas un
 * constat : silence, « Stop. », consigne). Le guetteur y lit les résultats
 * que la machine ne publie pas, sans toucher au cœur. Pur, testé en JVM.
 */
fun scanReportOf(phrase: String?, language: SpeechLanguage): Pair<ScanSide, Boolean>? {
    if (phrase == null) return null
    for (side in ScanSide.entries) {
        if (phrase == CrosswalkScanText.nothing(side, language)) return side to false
        if (phrase == CrosswalkScanText.danger(side, language)) return side to true
    }
    return null
}
