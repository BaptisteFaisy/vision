package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection

/** Détection véhicule factice : label SAM brut, score confiant, boîte pixels 640×480. */
internal fun vehicleDet(label: String, box: List<Float>): Detection =
    Detection(label, 0.9f, box, width = 640, height = 480)

/** Lecture distance factice : mesure portée par la boîte. */
internal fun vehicleReading(label: String, meters: Float, box: List<Float>): DistanceReading =
    DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

/** Boîte centrée verticalement (ratio 0,6) : largeur [widthPx], centre [cx]. */
internal fun vehicleBox(widthPx: Float, cx: Float = 320f): List<Float> {
    val heightPx = widthPx * 0.6f
    return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
}

/** Lecteur factice : occupation programmable, ordre des événements rejouable. */
internal class FakePlayer(
    override var isBusy: Boolean = false,
    private val playFileOk: Boolean = true,
) : TraversePlayer {
    val events = mutableListOf<String>()
    var playedPath: String? = null

    override fun stop() {
        events += "stop"
        isBusy = false
    }

    override suspend fun playFile(path: String): Boolean {
        events += "playFile"
        playedPath = path
        return playFileOk
    }
}

/** MP3 embarqués factices : copies programmées vers le cache. */
internal class FakeBundled(
    private val failOn: Set<String> = emptySet(),
    private val store: MutableMap<String, String>,
) : TraverseBundled {
    var calls = 0

    override fun fill(phrase: String, path: String): Boolean {
        calls++
        if (phrase in failOn) return false
        store[phrase] = path
        return true
    }
}

/** Cache factice : présence = entrée non vide dans la carte. */
internal class FakeStore(private val clips: MutableMap<String, String>) : TraverseClipStore {
    override fun pathFor(phrase: String): String = "/cache/${phrase.hashCode()}.wav"

    override fun clipFor(phrase: String): String? = clips[phrase]?.takeIf { it.isNotEmpty() }
}
