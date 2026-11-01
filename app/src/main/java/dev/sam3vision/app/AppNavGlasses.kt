package dev.sam3vision.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.app.glasses.GlassesEvent
import dev.sam3vision.app.glasses.GlassesSessionKeepAlive
import dev.sam3vision.app.glasses.GlassesSessionLifecycle
import dev.sam3vision.app.glasses.sessionControl
import dev.sam3vision.app.isee.IseeViewModel
import dev.sam3vision.core.glasses.CaptureAction

/**
 * Destination d'une photo prise sur les lunettes : `null`, toujours — elle
 * s'affiche sur l'accueil iSee (personne accompagnée, [IseeViewModel.onGlassesPhoto]),
 * jamais poussée vers une autre page (ni Analyse ni Chat). Pure : verrou anti-régression en JVM.
 */
internal fun routeForGlassesPhoto(action: CaptureAction): Route? = null

/**
 * Les lunettes nourrissent iSee : photos posées sans changer de page,
 * questions parlées comme des dictées. Extrait d'`AppNav` (garde 500 lignes).
 */
@Composable
internal fun GlassesBridge(glasses: GlassesController, isee: IseeViewModel, push: (Route) -> Unit) {
    val context = LocalContext.current
    // Session en cours : le service de premier plan la garde en vie quand le
    // téléphone passe en veille — sans lui le processus est gelé et la session
    // tombe. Au repos, le service s'efface.
    LaunchedEffect(glasses) {
        glasses.state.collect { state ->
            if (GlassesSessionLifecycle.wantsBackground(state.phase)) GlassesSessionLifecycle.startBackground(context)
            else GlassesSessionLifecycle.stopBackground(context)
        }
    }
    // Bouton « Arrêter » de la notification : la session tombe pour de bon.
    LaunchedEffect(glasses) {
        GlassesSessionKeepAlive.stopRequests.collect { glasses.disconnect() }
    }
    // Composition détruite (activité finie) : le maintien ne survit pas à l'app.
    DisposableEffect(glasses) {
        onDispose { GlassesSessionLifecycle.stopBackground(context) }
    }
    LaunchedEffect(glasses) {
        // Ordre vocal « iSee » : iSee peut basculer la session des lunettes.
        isee.glassesSession = glasses.sessionControl()
        glasses.events.collect { event ->
            when (event) {
                is GlassesEvent.PhotoTaken -> {
                    isee.onGlassesPhoto(event.file, event.action)
                    routeForGlassesPhoto(event.action)?.let(push)
                }
                // Écoute ouverte : iSee se tait pour ne pas couvrir la question.
                GlassesEvent.TalkListening -> isee.onGlassesListening()
                // Question entendue : texte + voix, comme une dictée (sans photo).
                is GlassesEvent.VoiceHeard -> isee.onGlassesHeard(event.text, event.pcm)
                // Fin d'écoute sans question : iSee reprend seul, rien à faire.
                else -> Unit
            }
        }
    }
}
