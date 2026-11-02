package dev.sam3vision.core.traverse

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
