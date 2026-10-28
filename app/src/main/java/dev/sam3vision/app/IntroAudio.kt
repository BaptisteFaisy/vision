package dev.sam3vision.app

import android.content.Context
import java.io.File

/**
 * Intro vocale embarquée (`res/raw/intro_isee.mp3`, voix Laure d'ElevenLabs,
 * rendue hors ligne). [SpeechPlayer] lit un chemin de fichier et non une ressource :
 * on recopie l'asset dans le cache une seule fois (le cache peut être vidé par
 * Android, la copie est refaite au besoin).
 */
object IntroAudio {

    private const val ASSET_NAME = "intro_isee.mp3"
    private const val CACHE_DIR = "intro"

    fun ensure(context: Context): File {
        val app = context.applicationContext
        val file = File(File(app.cacheDir, CACHE_DIR).apply { mkdirs() }, ASSET_NAME)
        if (file.length() == 0L) {
            app.resources.openRawResource(R.raw.intro_isee).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }
}