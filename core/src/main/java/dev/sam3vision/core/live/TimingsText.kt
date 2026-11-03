package dev.sam3vision.core.live

import dev.sam3vision.core.mask.DecodeSplit
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
     * mask 37 · pixel 8 », plus « · seq 12 » quand la frame vient des lunettes
     * (jointure E2E ; `parse_isee_lat.py` ignore les tokens inconnus, verdict
     * intact). Chaîne vide avant la première frame mesurée ; `—` pour un poste
     * que le build déployé ne permet pas encore de mesurer.
     */
    fun compact(t: FrameTimings): String {
        if (t.isEmpty) return ""
        // Un seul `StringBuilder` pré-dimensionné : la version `map` + `+` + `joinToString`
        // construisait une liste, six chaînes intermédiaires puis une seconde liste, à
        // chaque frame publiée (mesuré au banc JVM, [LiveFrameBudgetTest]).
        val out = StringBuilder(COMPACT_CAPACITY)
        out.append("latency ")
        appendMs(out, t.totalMs)
        for (poste in POSTES) {
            out.append(SEPARATOR).append(poste.short).append(' ')
            appendMs(out, poste.value(t))
        }
        if (t.seq >= 0L) out.append(SEPARATOR).append("seq ").append(t.seq)
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
    ): String {
        val out = StringBuilder(CAPSPLIT_CAPACITY)
        out.append("capsplit age ").append(ageMs)
        out.append(SEPARATOR).append("enc ").append(encMs)
        out.append(SEPARATOR).append("b64 ").append(b64Ms)
        out.append(SEPARATOR).append("cb ").append(cbMs)
        out.append(SEPARATOR).append("total ").append(totalMs)
        out.append(SEPARATOR).append("int ").append(intervalMs)
        return out.toString()
    }

    /**
     * Décomposition du poste `mask` (réponse → masques), loggée 1 frame sur 20 par
     * le peintre de masques : « decsplit b64 2 · img 28 · up 1 · paint 6 · total 37
     * · masks 12 ». `b64` = base64 → octets, `img` = octets → niveaux (PNG, zlib
     * `M1`, runs RLE, remplissage `poly`), `up` = grille réduite → taille de la
     * frame, `paint` = coloriage par table + écriture dans le bitmap ping-pong,
     * `total` = temps mural réponse → tampons prêts, `masks` = masques décodés.
     * `b64 + img + up + paint` retrouve le `mask` de la sonde à ~1 ms près.
     *
     * Invisible à `parse_isee_lat.py` (ni `latency`, ni `server:`) : la ligne vit
     * dans le même dump `ISEE_LAT`, sans fausser le verdict — même contrat que
     * [capsplit], qui décompose `capt` de la même façon.
     */
    fun decsplit(d: DecodeSplit): String {
        val out = StringBuilder(DECSPLIT_CAPACITY).append("decsplit ")
        appendDecodeStages(out, d)
        out.append(SEPARATOR).append("total ")
        appendMs(out, d.totalMs)
        out.append(SEPARATOR).append("masks ").append(d.masks)
        return out.toString()
    }

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
    fun link(seq: Long, linkUs: Long, decodeUs: Long, displayUs: Long, perceivedUs: Long): String {
        val out = StringBuilder(LINK_CAPACITY)
        out.append("link seq ").append(seq)
        out.append(SEPARATOR).append("lien ")
        appendUs(out, linkUs)
        out.append(SEPARATOR).append("dec ")
        appendUs(out, decodeUs)
        out.append(SEPARATOR).append("aff ")
        appendUs(out, displayUs)
        out.append(SEPARATOR).append("percu ")
        appendUs(out, perceivedUs)
        return out.toString()
    }

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
        // Une seule passe + un seul `StringBuilder` : la version `associate` + filtres +
        // `joinToString` construisait une carte, quatre listes et une chaîne par étage à
        // chaque frame complète (neuf étages en recette — `lat_capture18`).
        val named = HashMap<String, Long>(timings.size * 2)
        for ((key, value) in timings) named[cleanKey(key)] = value.roundToLong()
        val out = StringBuilder(SERVERS_CAPACITY).append("server: ")
        var first = true
        for (stage in SERVER_STAGES) {
            val ms = named.remove(stage) ?: continue
            if (!first) out.append(SEPARATOR) else first = false
            out.append(stage).append(' ').append(ms).append(" ms")
        }
        val others = ArrayList<String>(named.size)
        for (name in named.keys) if (name != FrameTimings.SERVER_TOTAL) others.add(name)
        others.sort()
        for (name in others) {
            if (!first) out.append(SEPARATOR) else first = false
            out.append(name).append(' ').append(named.getValue(name)).append(" ms")
        }
        return out.toString()
    }

    /** Une ligne de détail ; l'aller-retour et les masques sont décomposés quand c'est daté. */
    private fun poste(t: FrameTimings, poste: Poste): String {
        val split = when {
            poste.short == "rtt" && t.networkMs >= 0 ->
                " (incl. server ${t.serverMs} ms, network + queue ${t.networkMs} ms)"
            poste.short == "mask" && t.decode.known ->
                " (${decodeStages(t.decode)}, ${t.decode.masks} masks)"
            else -> ""
        }
        return "${poste.long}: ${poste.value(t)} ms$split"
    }

    /** Les quatre postes du décodage, ordre du trajet : [decsplit] et le détail partagent. */
    private fun decodeStages(d: DecodeSplit): String =
        buildString(DECODE_STAGES_CAPACITY) { appendDecodeStages(this, d) }

    /** Mêmes postes, ajoutés à [out] sans chaîne intermédiaire ([decsplit], chemin chaud). */
    private fun appendDecodeStages(out: StringBuilder, d: DecodeSplit) {
        out.append("b64 ")
        appendMs(out, d.b64Ms)
        out.append(SEPARATOR).append("img ")
        appendMs(out, d.imgMs)
        out.append(SEPARATOR).append("up ")
        appendMs(out, d.upMs)
        out.append(SEPARATOR).append("paint ")
        appendMs(out, d.paintMs)
    }

    /** Clé serveur lisible : `vision_ms` et `vision` désignent le même étage. */
    private fun cleanKey(key: String): String = key.removeSuffix("_ms")

    /** Millisecondes ajoutées : le nombre, ou [MISSING] si le poste manque. */
    private fun appendMs(out: StringBuilder, value: Long) {
        if (value >= 0) out.append(value) else out.append(MISSING)
    }

    /** Microsecondes ajoutées : même contrat que [appendMs], pour la ligne [link]. */
    private fun appendUs(out: StringBuilder, value: Long) {
        if (value >= 0) out.append(value) else out.append(MISSING)
    }

    /** Aller-retour : son chiffre serveur a sa propre ligne dans le détail. */
    private const val SRV = "srv"

    /** Séparateur de la ligne d'état (« · », U+00B7). */
    private const val SEPARATOR = " · "

    /**
     * Capacité du `StringBuilder` de [compact] : « latency 348 » plus sept postes font
     * une centaine de caractères, plus « · seq N » sur le chemin lunettes —
     * pré-dimensionner évite les recopies de croissance.
     */
    private const val COMPACT_CAPACITY = 176

    /** Même contrat que [COMPACT_CAPACITY], pour les lignes échantillonnées. */
    private const val CAPSPLIT_CAPACITY = 96

    /** Même contrat que [COMPACT_CAPACITY], pour la ligne de décodage. */
    private const val DECSPLIT_CAPACITY = 112

    /** Les quatre postes `b64 · img · up · paint` tiennent en une soixantaine de signes. */
    private const val DECODE_STAGES_CAPACITY = 64

    /** Même contrat que [COMPACT_CAPACITY], pour la ligne lunettes. */
    private const val LINK_CAPACITY = 96

    /** Neuf étages à ~20 signes en recette : large sans recopie de croissance. */
    private const val SERVERS_CAPACITY = 224
}
