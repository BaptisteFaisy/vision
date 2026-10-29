package dev.sam3vision.app.isee

import android.app.Application
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.glasses.GlassesVoiceRoute
import java.io.File

/**
 * Le lecteur audio d'iSee, construit une fois : chat, analyse, intro et voix de
 * proximité partagent le même, donc une seule phrase à la fois.
 *
 * Extrait du ViewModel (limite de 500 lignes, même motif que `IseePlayback`) : ce
 * qui reste ici est ce que la construction a de propre à iSee — le dossier du MP3
 * dans le cache, et une voix qui vise les lunettes quand leur session est ouverte
 * ([GlassesVoiceRoute]), le haut-parleur sinon.
 */
fun iseeVoicePlayer(
    app: Application,
    onComplete: () -> Unit,
    onPlaybackError: (String) -> Unit,
): SpeechPlayer = SpeechPlayer(
    dir = File(app.cacheDir, "isee-play"),
    onComplete = onComplete,
    onPlaybackError = onPlaybackError,
    output = { GlassesVoiceRoute.current(app) },
)
