package dev.sam3vision.core.traverse

import dev.sam3vision.core.net.TtsClient

/** ElevenLabs factice : octets ou échec programmés, appels comptés. */
internal class FakeTts(private val outcome: Result<ByteArray>) : TtsClient {
    var calls = 0
    var lastText: String? = null

    override suspend fun synthesize(text: String): Result<ByteArray> {
        calls++
        lastText = text
        return outcome
    }
}

/** Lecteur factice : occupation programmable, ordre des événements rejouable. */
internal class FakePlayer(
    override var isBusy: Boolean = false,
    private val playOk: Boolean = true,
    private val playFileOk: Boolean = true,
) : TraversePlayer {
    val events = mutableListOf<String>()
    var playedBytes: ByteArray? = null
    var playedPath: String? = null

    override fun stop() {
        events += "stop"
        isBusy = false
    }

    override suspend fun play(bytes: ByteArray): Boolean {
        events += "play"
        playedBytes = bytes
        return playOk
    }

    override suspend fun playFile(path: String): Boolean {
        events += "playFile"
        playedPath = path
        return playFileOk
    }
}

/** TTS système factice : langue effective et rendus programmés. */
internal class FakeSystemTts(
    private val language: String = "en-US",
    private val failOn: Set<String> = emptySet(),
    private val store: MutableMap<String, String>,
) : TraverseSystemTts {
    var calls = 0

    override suspend fun renderToFile(phrase: String, path: String): Result<String> {
        calls++
        if (phrase in failOn) return Result.failure(RuntimeException("moteur indisponible"))
        store[phrase] = path
        return Result.success(path)
    }

    override fun effectiveLanguage(): String = language
}

/** Cache factice : présence = entrée non vide dans la carte. */
internal class FakeStore(private val clips: MutableMap<String, String>) : TraverseClipStore {
    override fun pathFor(phrase: String): String = "/cache/${phrase.hashCode()}.wav"

    override fun clipFor(phrase: String): String? = clips[phrase]?.takeIf { it.isNotEmpty() }
}
