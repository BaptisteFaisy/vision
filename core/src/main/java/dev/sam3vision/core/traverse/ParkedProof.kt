package dev.sam3vision.core.traverse

import dev.sam3vision.core.cycle.CycleConcepts
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.predict.Box
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Preuve « garée » sur fenêtre glissante : un véhicule qui ne bouge pas dans
 * l'image pendant [PROOF_MS] sort du veto trafic, même filmé à la main.
 *
 * Le test paire-de-frames du veto (dérive sous 2 %/s, portée à 0,5 m) est sous
 * le bruit réel : mesuré sur les vidéos Samsung du 04/11 (boîtes SAM, frames
 * 320 px, 5 ips), une garée tremble de 0,02 à 0,07 image/s d'une frame à
 * l'autre et sa portée saute de plusieurs mètres — elle n'était donc jamais
 * prouvée immobile, et sous 15 m elle bloquait à vie (« Don't cross. » sur des
 * vans garés). Sur 1 s, en médianes, la garée reste sous 0,06 image/s alors
 * qu'une voiture qui roule dépasse 0,4 : plus d'un ordre de grandeur d'écart.
 *
 * Monotone côté sûreté : la preuve ne fait qu'exempter ce qui est prouvé, tout
 * le reste garde le veto d'avant. Une piste jeune (moins de [PROOF_MS]), qui
 * dérive ([PROOF_SPEED]), qui grandit ou rétrécit ([GROWTH_RATIO]) ou sans bord
 * libre n'est jamais prouvée. Une preuve acquise tombe au-delà de
 * [RELEASE_SPEED] : l'hystérésis absorbe le tremblé, pas un démarrage (une
 * voiture qui repart dépasse ce seuil en moins d'une seconde à 10 m). La taille
 * se juge, elle, sur [SIZE_WINDOW_MS] et sans hystérésis : le bruit SAM ne s'y
 * accumule pas, une approche si — même lente, elle ne se reprouve pas.
 *
 * Les pistes vont par **véhicule physique**, pas par label : SAM rend souvent
 * `van`, `truck` et `car` sur le même véhicule, et le label bascule d'une
 * frame à l'autre. Une boîte jeune nettement plus petite qu'un véhicule prouvé
 * et recouverte par lui est un morceau de ce véhicule ([isFragment]) : SAM en
 * sème sans cesse, et une frame suffirait à faire dire « Don't cross. ». Sous
 * pan rapide prouvé (`panning`, voir [PAN_SPEED]), rien n'est prouvable — une
 * boîte fixe dans l'image peut y suivre la caméra — et la mémoire repart de zéro.
 *
 * Pur Kotlin testé en JVM, aucune horloge implicite.
 */
class ParkedProof {

    /** Vue datée : boîte normalisée 0..1 et bords coupés par le cadre. */
    private class Sample(val atMs: Long, val box: Box, val cut: Cut)

    /** Bords de la boîte collés au cadre : leur position ne dit rien de l'objet. */
    private data class Cut(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean)

    /** Véhicule suivi : dernière boîte, dernière vue, vues gardées, preuve et statut de morceau. */
    private class Track(var last: Box, var lastSeenMs: Long) {
        val samples = ArrayDeque<Sample>()
        var parked = false
        var fragment = false
    }

    /** Véhicule physique d'une frame : label et boîte les mieux notés, toutes ses boîtes SAM. */
    private class Seen(val label: String, val box: Box, val cut: Cut, val members: MutableList<List<Float>>)

    private val tracks = ArrayList<Track>()

    /** Boîtes SAM (pixels) prouvées garées à la dernière frame. */
    @Volatile
    private var parkedBoxes: Set<List<Float>> = emptySet()

    /** Vrai si [detection] (frame courante) appartient à un véhicule prouvé garé. */
    fun isParked(detection: Detection): Boolean = detection.box in parkedBoxes

    /** Oublie pistes et preuves (nouvelle traversée, nouvelle séance). */
    @Synchronized
    fun reset() {
        tracks.clear()
        parkedBoxes = emptySet()
    }

    /**
     * Avance d'une frame datée de [atMs] : [detections] nourrit les pistes,
     * [panning] (pan rapide prouvé par le fond) efface toute preuve. Sans date
     * ([atMs] < 0), rien n'est prouvé.
     */
    @Synchronized
    fun update(detections: List<Detection>, atMs: Long, panning: Boolean = false) {
        if (atMs < 0L || panning) {
            reset()
            return
        }
        val claimed = HashSet<Track>()
        val judged = vehiclesOf(detections).sortedByDescending { it.box.area }.map { vehicle ->
            val track = trackOf(vehicle, atMs, claimed)
            claimed += track
            feed(track, vehicle, atMs)
            track.parked = judge(track)
            vehicle to track
        }
        tracks.removeAll { atMs - it.lastSeenMs > STALE_MS }
        val proven = judged.filter { (_, track) -> track.parked }.map { (vehicle, _) -> vehicle.box }
        judged.forEach { (vehicle, track) -> track.fragment = isFragment(vehicle, track, proven) }
        parkedBoxes = judged
            .filter { (_, track) -> track.parked || track.fragment }
            .flatMapTo(HashSet()) { (vehicle, _) -> vehicle.members }
    }

    /**
     * Morceau d'un véhicule prouvé : piste encore jeune (jamais jugée seule),
     * majoritairement recouverte par des véhicules [proven], nettement plus
     * petite que le plus grand d'entre eux, et pas lancée. Jamais un deux-roues
     * ([isTwoWheeler]) : une moto qui passe devant un van garé n'hérite pas de
     * sa preuve. L'exemption s'éteint avec la jeunesse : passé [PROOF_MS], la
     * piste se prouve seule ou retombe au veto.
     */
    private fun isFragment(vehicle: Seen, track: Track, proven: List<Box>): Boolean {
        if (track.parked || !isYoung(track)) return false
        if (isTwoWheeler(vehicle.label) || lastSpeed(track) > FRAGMENT_SPEED) return false
        val box = vehicle.box
        val covering = proven.filter { overlap(box, it) > 0f }
        val largest = covering.maxOfOrNull { it.area } ?: return false
        if (box.area >= largest * FRAGMENT_AREA_MAX) return false
        return covering.sumOf { overlap(box, it).toDouble() } >= box.area * FRAGMENT_COVER_MIN
    }

    /** Piste sans fenêtre jugeable : assez de vues récentes, ou elle répond d'elle-même ([judge]). */
    private fun isYoung(track: Track): Boolean = !isJudgeable(recentOf(track))

    /** Vues de la fenêtre de dérive (la plus récente en dernier). */
    private fun recentOf(track: Track): List<Sample> {
        val lastMs = track.samples.last().atMs
        return track.samples.filter { lastMs - it.atMs <= WINDOW_MS }
    }

    /** Assez de vues et assez de recul pour conclure quoi que ce soit. */
    private fun isJudgeable(recent: List<Sample>): Boolean =
        recent.size >= MIN_SAMPLES && recent.last().atMs - recent.first().atMs >= PROOF_MS

    /** Moto, vélo, trottinette : petits et rapides, jamais des morceaux de voiture. */
    private fun isTwoWheeler(label: String): Boolean =
        CycleConcepts.isCycle(label) || VehicleConcepts.keyOf(label) in MOTORCYCLES

    /** Vitesse du centre sur la dernière paire de vues (image/s), 0 sans paire. */
    private fun lastSpeed(track: Track): Float {
        val samples = track.samples
        if (samples.size < 2) return 0f
        val last = samples.last()
        val before = samples[samples.size - 2]
        val seconds = (last.atMs - before.atMs) / MS_PER_SECOND
        if (seconds <= 0f) return 0f
        return hypot(last.box.centerX - before.box.centerX, last.box.centerY - before.box.centerY) / seconds
    }

    /** Aire commune de deux boîtes, 0 si elles ne se touchent pas. */
    private fun overlap(a: Box, b: Box): Float {
        val width = minOf(a.x2, b.x2) - maxOf(a.x1, b.x1)
        val height = minOf(a.y2, b.y2) - maxOf(a.y1, b.y1)
        return if (width <= 0f || height <= 0f) 0f else width * height
    }

    /** Véhicules de la frame : boîtes de même place fusionnées, la mieux notée d'abord. */
    private fun vehiclesOf(detections: List<Detection>): List<Seen> {
        val groups = ArrayList<Seen>()
        for (detection in detections.sortedByDescending { it.score }) {
            if (!VehicleConcepts.isVehicle(detection.label) || detection.score <= TraverseVeto.SCORE_MIN) continue
            val box = normalized(detection) ?: continue
            val same = groups.firstOrNull { it.box.iou(box) >= SAME_VEHICLE_IOU }
            if (same != null) same.members += detection.box
            else groups += Seen(detection.label, box, cutOf(detection), mutableListOf(detection.box))
        }
        return groups
    }

    /** Boîte normalisée 0..1, `null` si illisible, dégénérée ou sans taille d'image. */
    private fun normalized(detection: Detection): Box? {
        val pixels = Box.fromProto(detection.box)?.takeIf { it.isSane } ?: return null
        if (detection.width <= 0 || detection.height <= 0) return null
        val width = detection.width.toFloat()
        val height = detection.height.toFloat()
        return Box(pixels.x1 / width, pixels.y1 / height, pixels.x2 / width, pixels.y2 / height)
    }

    /** Bords de [detection] collés au cadre, à [EDGE_PX] près. */
    private fun cutOf(detection: Detection): Cut {
        val box = detection.box
        return Cut(
            left = box[0] <= EDGE_PX,
            top = box[1] <= EDGE_PX,
            right = box[2] >= detection.width - EDGE_PX,
            bottom = box[3] >= detection.height - EDGE_PX,
        )
    }

    /**
     * Piste libre qui recouvre [vehicle] le mieux, sinon une piste neuve. Un
     * morceau ne prend jamais la place d'une vraie piste : sans ça, le véhicule
     * repartait de zéro dès que SAM rendait une boîte décalée sur une frame.
     */
    private fun trackOf(vehicle: Seen, atMs: Long, claimed: Set<Track>): Track {
        val close = tracks.filter {
            it !in claimed && atMs - it.lastSeenMs <= STALE_MS && it.last.iou(vehicle.box) >= CONTINUITY_IOU
        }
        return close.filterNot { it.fragment }.ifEmpty { close }.maxByOrNull { it.last.iou(vehicle.box) }
            ?: Track(vehicle.box, atMs).also { tracks += it }
    }

    /** Ajoute la vue à la fenêtre et purge ce qui en sort. */
    private fun feed(track: Track, vehicle: Seen, atMs: Long) {
        track.last = vehicle.box
        track.lastSeenMs = atMs
        track.samples.addLast(Sample(atMs, vehicle.box, vehicle.cut))
        while (atMs - track.samples.first().atMs > SIZE_WINDOW_MS) track.samples.removeFirst()
    }

    /**
     * Verdict de la piste. Fenêtre trop courte : la preuve reste ce qu'elle
     * était (un trou de détection ne la casse pas, une piste jeune ne l'acquiert
     * pas). Sinon la dérive récente et la taille tranchent, la dérive
     * s'élargissant une fois la preuve acquise (hystérésis).
     */
    private fun judge(track: Track): Boolean {
        val recent = recentOf(track)
        if (!isJudgeable(recent)) return track.parked
        val speed = speedOf(recent) ?: return false
        val limit = if (track.parked) RELEASE_SPEED else PROOF_SPEED
        return speed <= limit && steadySize(track.samples)
    }

    /** Premier et dernier tiers de [samples] : médianes, donc peu sensibles aux boîtes aberrantes. */
    private fun thirdsOf(samples: List<Sample>): Pair<List<Sample>, List<Sample>> {
        val third = maxOf(1, samples.size / THIRDS)
        return samples.take(third) to samples.takeLast(third)
    }

    /** Dérive en fractions d'image par seconde, `null` si un axe n'a aucun bord libre. */
    private fun speedOf(samples: List<Sample>): Float? {
        val (early, late) = thirdsOf(samples)
        val startMs = samples.first().atMs // dates relatives : un epoch en Float perdrait la minute.
        val seconds = (medianOf(late) { (it.atMs - startMs).toFloat() } - medianOf(early) { (it.atMs - startMs).toFloat() }) / MS_PER_SECOND
        if (seconds <= 0f) return null
        val x = axisOf(samples.any { it.cut.left }, samples.any { it.cut.right }, Box::x1, Box::x2) ?: return null
        val y = axisOf(samples.any { it.cut.top }, samples.any { it.cut.bottom }, Box::y1, Box::y2) ?: return null
        val dx = medianOf(late) { x(it.box) } - medianOf(early) { x(it.box) }
        val dy = medianOf(late) { y(it.box) } - medianOf(early) { y(it.box) }
        return hypot(dx, dy) / seconds
    }

    /** Repère d'un axe : le centre, ou le bord libre quand l'autre est coupé par le cadre. */
    private fun axisOf(lowCut: Boolean, highCut: Boolean, low: (Box) -> Float, high: (Box) -> Float): ((Box) -> Float)? = when {
        lowCut && highCut -> null
        lowCut -> high
        highCut -> low
        else -> { box -> (low(box) + high(box)) / 2f }
    }

    /** Taille tenue sur toute la mémoire : hauteur (largeur si la hauteur est coupée) au bruit SAM près. */
    private fun steadySize(samples: List<Sample>): Boolean {
        val heightCut = samples.any { it.cut.top || it.cut.bottom }
        val widthCut = samples.any { it.cut.left || it.cut.right }
        if (heightCut && widthCut) return false
        val size: (Sample) -> Float = { if (heightCut) it.box.width else it.box.height }
        val (early, late) = thirdsOf(samples)
        val before = medianOf(early, size)
        return abs(medianOf(late, size) - before) <= maxOf(before * GROWTH_RATIO, GROWTH_FLOOR)
    }

    /** Médiane de [value] sur [samples] (non vide). */
    private fun medianOf(samples: List<Sample>, value: (Sample) -> Float): Float {
        val sorted = samples.map(value).sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    companion object {
        /**
         * Fond au-delà duquel rien n'est prouvable (image/s) : seul un pan de cet
         * ordre peut garder une voiture qui roule (≥ 0,4) fixe dans l'image. Les
         * pans lents d'une prise à la main, la fenêtre les absorbe : le seuil du
         * veto (0,03) effaçait toutes les preuves au moindre trou de frames.
         */
        const val PAN_SPEED = 0.2f

        /** Durée d'observation exigée avant toute preuve (ms) : 4 vues à 5 ips. */
        const val PROOF_MS = 600L

        /** Fenêtre glissante de la dérive (ms). */
        const val WINDOW_MS = 1_000L

        /** Mémoire de la taille (ms) : assez longue pour qu'une approche lente y sorte du bruit. */
        const val SIZE_WINDOW_MS = 3_000L

        /** Vues minimales dans la fenêtre : en dessous, la cadence ne prouve rien. */
        const val MIN_SAMPLES = 4

        /** Dérive maximale pour acquérir la preuve (image/s) : garées réelles ≤ 0,06. */
        const val PROOF_SPEED = 0.07f

        /** Dérive qui fait tomber une preuve acquise (image/s) : roulantes réelles ≥ 0,4. */
        const val RELEASE_SPEED = 0.12f

        /** Variation de taille tolérée (grandes boîtes : garées réelles ≤ 6 %, approche à 2 m/s à 12 m : 10 % en 600 ms). */
        const val GROWTH_RATIO = 0.08f

        /** Plancher de variation (fraction d'image, 3 px sur 320) : le tremblé d'une petite boîte se compte en pixels. */
        const val GROWTH_FLOOR = 0.0094f

        /** Au-delà de cette vitesse sur sa dernière paire (image/s), une boîte n'est pas un morceau. */
        const val FRAGMENT_SPEED = 0.3f

        /** Part de sa surface qu'un morceau doit avoir sous des véhicules prouvés. */
        const val FRAGMENT_COVER_MIN = 0.5f

        /** Un morceau reste nettement plus petit que le véhicule (70 % de son aire au plus). */
        const val FRAGMENT_AREA_MAX = 0.7f

        /** Sans vue depuis ce délai, la piste est oubliée (ms). */
        const val STALE_MS = 1_500L

        /** Recouvrement au-delà duquel deux boîtes d'une frame sont le même véhicule. */
        private const val SAME_VEHICLE_IOU = 0.6f

        /** Recouvrement minimal pour rester sur la même piste d'une frame à l'autre. */
        private const val CONTINUITY_IOU = 0.3f

        /** Marge du cadre (px) sous laquelle un bord est coupé. */
        private const val EDGE_PX = 1f

        /** Clés de suivi des motos ([VehicleConcepts.keyOf]) : pas des cycles, mais deux-roues. */
        private val MOTORCYCLES = setOf("motorcycle", "moto")

        /** La fenêtre se compare par tiers : le premier contre le dernier. */
        private const val THIRDS = 3

        private const val MS_PER_SECOND = 1000f
    }
}
