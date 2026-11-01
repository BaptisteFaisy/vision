package dev.sam3vision.core.live

import kotlin.math.roundToLong

/**
 * Mise en forme de la chronologie : une ligne compacte pour l'écran, une ligne par poste
 * pour le détail, les postes serveur reçus. Purment fonctionnel (aucun Android), testé en
 * JVM — l'UI ne formate jamais un nombre elle-même.
 */
object TimingsText {

    /** Poste non mesuré : trait cadratin, plus lisible qu'un `-1` dans une phrase. */
    const val MISSING: String = "—"

    /** Un poste : nom court (ligne d'état), libellé long (détail), valeur en ms. */
    private class Poste(val short: String, val long: String, val value: (FrameTimings) -> Long)

    /** Les postes de la ligne d'état, dans l'ordre du trajet. */
    private val POSTES = listOf(
        Poste("capt", "capture → jpeg", FrameTimings::encodeMs),
        Poste("up", "jpeg → send", FrameTimings::uploadMs),
        Poste("srv", "server", FrameTimings::serverMs),
        Poste("rtt", "send → response", FrameTimings::rttMs),
        Poste("mask", "response → masks", FrameTimings::receivedToOverlaysMs),
        Poste("pixel", "masks → pixel", FrameTimings::overlaysToPixelMs),
    )

    /**
     * Décomposition optionnelle de l'aller-retour : posée par le transport quand il sait
     * dater les en-têtes et le corps (`WireMarks`). Absente → « — », jamais un zéro.
     */
    private val WIRE_POSTES = listOf(
        Poste("down", "send → headers", FrameTimings::sentToHeadersMs),
        Poste("body", "headers → body", FrameTimings::headersToBodyMs),
        Poste("parse", "body → response", FrameTimings::bodyToReceivedMs),
    )

    /** Ordre du détail : la décomposition de l'aller-retour vient juste après `rtt`. */
    private val DETAIL_POSTES = POSTES.flatMap { poste ->
        if (poste.short == "rtt") listOf(poste) + WIRE_POSTES else listOf(poste)
    }

    /** Ordre de lecture des étages serveur (les clés arrivent du backend en `*_ms`). */
    private val SERVER_STAGES = listOf("preprocess", "vision", "tokenize", "detr", "masks", "queue")

    /**
     * Ligne d'état **unique** : « latency 348 · capt 22 · up 61 · srv 92 · rtt 153 ·
     * mask 37 · pixel 8 ». Chaîne vide avant la première frame mesurée ; `—` pour un
     * poste que le build déployé ne permet pas encore de mesurer.
     */
    fun compact(t: FrameTimings): String {
        if (t.isEmpty) return ""
        // Un seul `StringBuilder` pré-dimensionné : la version `map` + `+` + `joinToString`
        // construisait une liste, six chaînes intermédiaires puis une seconde liste, à
        // chaque frame publiée (mesuré au banc JVM, [LiveFrameBudgetTest]).
        val out = StringBuilder(COMPACT_CAPACITY)
        out.append("latency ").append(ms(t.totalMs))
        for (poste in POSTES) {
            out.append(SEPARATOR).append(poste.short).append(' ').append(ms(poste.value(t)))
        }
        return out.toString()
    }

    /**
     * Détail poste par poste, puis la latence perçue. L'aller-retour est décomposé
     * (« incl. server 92 ms, network + queue 61 ms ») dès qu'un chiffre serveur existe.
     */
    fun detail(t: FrameTimings): List<String> {
        if (t.isEmpty) return emptyList()
        // Le poste `srv` est porté par la ligne `serveur : …` des étages : pas deux fois.
        val lines = DETAIL_POSTES.filter { it.short != SRV && it.value(t) >= 0 }.map { poste(t, it) }
        return if (t.complete) lines + "perceived latency: ${t.captureToPixelMs} ms" else lines
    }

    /**
     * Décomposition du poste `capt` (capture → jpeg), loggée 1 frame sur 20 par
     * l'analyseur caméra : « capsplit age 81 · enc 24 · b64 3 · cb 2 · total 30 ».
     * `age` = capteur → entrée analyseur (expo, readout, livraison, attente fil),
     * `enc` = NV21 → JPEG, `b64` = base64 + horodatage, `cb` = rappel `onFrame`
     * (profondeur, mouvement, réserve/envoi), `total` = analyse complète.
     * `int` = intervalle depuis l'entrée précédente de l'analyseur, c'est-à-dire la
     * **cadence réellement obtenue** (pas celle demandée) : il sépare ce que le
     * matériel impose de ce que l'application retarde. À 30 fps l'exposition ne
     * peut pas dépasser 33 ms ; si `int` vaut 33 et `age` 81, l'écart n'est ni
     * l'exposition ni la cadence.
     * `age + enc + b64 + cb` retrouve le `capt` de la sonde à ~1 ms près.
     *
     * Invisible à `parse_isee_lat.py` (ni `latency`, ni `server:`) : la ligne vit
     * dans le même dump `ISEE_LAT`, sans fausser le verdict.
     */
    fun capsplit(
        ageMs: Long,
        encMs: Long,
        b64Ms: Long,
        cbMs: Long,
        totalMs: Long,
        intervalMs: Long,
    ): String =
        "capsplit age $ageMs · enc $encMs · b64 $b64Ms · cb $cbMs · total $totalMs" +
            " · int $intervalMs"

    /**
     * Frame lunettes rendue puis affichée, en **microsecondes** : « link seq 12 ·
     * lien 30123 · dec 8421 · aff 7110 · percu 45654 ». `lien` = retard variable
     * radio ([GlassesClockOffset]), `dec` = entrée-sortie du rendu, `aff` =
     * sortie → pixel, `percu` = les trois additionnés. `—` = non mesuré.
     *
     * La microseconde (et non la milliseconde du Direct) : le verdict B2
     * (`LUNETTES-RECETTE-TEL`, p95 < 100 ms) se joue à quelques ms, l'arrondi
     * ms mangerait l'étage d'affichage (~8 ms). Invisible à
     * `parse_isee_lat.py` (ni `latency`, ni `server:`) : la ligne vit dans le
     * dump `GlassesLink`, sans fausser le verdict du Direct.
     */
    fun link(seq: Long, linkUs: Long, decodeUs: Long, displayUs: Long, perceivedUs: Long): String =
        "link seq $seq · lien ${us(linkUs)} · dec ${us(decodeUs)} · aff ${us(displayUs)}" +
            " · percu ${us(perceivedUs)}"

    /**
     * Jambe du A/B binaire-vs-base64 : « leg binary » ou « leg text », loggée à la
     * première frame puis au repli texte. Invisible à `parse_isee_lat.py` (ni
     * `latency`, ni `server:`) : la ligne vit dans le même dump `ISEE_LAT` et
     * `scripts/latence-ab-compare.py` s'en sert pour attribuer chaque frame.
     */
    fun leg(binary: Boolean): String = if (binary) "leg binary" else "leg text"

    /**
     * Étages serveur reçus : « server: preprocess 12 ms · vision 47 ms · detr 38 ms ».
     * « server: — » quand `FAST_TIMING` est éteint au déploiement.
     */
    fun servers(timings: Map<String, Double>): String {
        if (timings.isEmpty()) return "server: $MISSING"
        val named = timings.entries.associate { cleanKey(it.key) to it.value }
        val known = SERVER_STAGES.filter(named::containsKey)
        val others = named.keys.filterNot { it in known || it == FrameTimings.SERVER_TOTAL }.sorted()
        val parts = (known + others).joinToString(" · ") { "$it ${named.getValue(it).roundToLong()} ms" }
        return "server: $parts"
    }

    /** Une ligne de détail ; l'aller-retour est décomposé quand un chiffre serveur existe. */
    private fun poste(t: FrameTimings, poste: Poste): String {
        val split = if (poste.short == "rtt" && t.networkMs >= 0) {
            " (incl. server ${t.serverMs} ms, network + queue ${t.networkMs} ms)"
        } else {
            ""
        }
        return "${poste.long}: ${poste.value(t)} ms$split"
    }

    /** Clé serveur lisible : `vision_ms` et `vision` désignent le même étage. */
    private fun cleanKey(key: String): String = key.removeSuffix("_ms")

    /** Millisecondes affichables : le nombre, ou [MISSING] si le poste manque. */
    private fun ms(value: Long): String = if (value >= 0) value.toString() else MISSING

    /** Microsecondes affichables : même contrat que [ms], pour la ligne [link]. */
    private fun us(value: Long): String = if (value >= 0) value.toString() else MISSING

    /** Aller-retour : son chiffre serveur a sa propre ligne dans le détail. */
    private const val SRV = "srv"

    /** Séparateur de la ligne d'état (« · », U+00B7). */
    private const val SEPARATOR = " · "

    /**
     * Capacité du `StringBuilder` de [compact] : « latency 348 » plus sept postes font
     * une centaine de caractères, jamais davantage — pré-dimensionner évite les
     * recopies de croissance.
     */
    private const val COMPACT_CAPACITY = 160
}
