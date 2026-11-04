package dev.sam3vision.app.isee

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.isee.vm.PictoPhase
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.core.iseedesign.IseePortrait
import dev.sam3vision.core.iseedesign.IseeScene
import dev.sam3vision.core.iseedesign.PortraitGeometry
import dev.sam3vision.core.iseedesign.SpeechPosition
import dev.sam3vision.core.iseedesign.portraitGeometry
import dev.sam3vision.core.iseedesign.voiceEnvelope

/**
 * L'écran iSee au design « Portrait » : fond blanc, une seule boule de verre au liquide marbré
 * (plus de satellites, plus de pixels), et seulement la phrase en cours, en gros, sous la boule.
 * Quand la caméra tourne (vidéo ou photo), la rue occupe le haut de l'écran et se fond dans le
 * blanc ; la boule rétrécit et reste dessous. En guidage elle s'efface (rue agrandie,
 * phrase gardée). Le user parle : liquide bleu, mots qui
 * s'allument en bleu puis passent au noir. iSee parle : liquide ambre, couronne ambre autour de
 * la boule, mot en cours en ambre. Toute l'interaction reste aux gestes de `IseeRootLayout`.
 *
 * En-tête et pastille dans `IseeSceneHeader.kt`, règles de couches dans `IseeSceneLayers.kt`.
 */
@Composable
fun IseeScenePane(
    state: IseeUiState,
    viewModel: IseeViewModel,
    modifier: Modifier = Modifier,
) {
    // Barre de statut : cachée au niveau navigation (AppNav, iSee + direct).
    val glue = rememberCameraGlue(viewModel)
    // Pas de collecte ici : la sonde descend telle quelle et chaque feuille collecte sa
    // tranche (pixels/chiffres/notice, voir ProbeSlices) — sinon chaque écriture (deux par
    // frame, plus les battements) réexécutait toute la scène.
    val glassesLive by GlassesVideoLink.live.collectAsState()
    val glassesStreaming by GlassesVideoLink.streaming.collectAsState()
    val entry = selectedEntry(state)
    val view = iseeSceneView(state, entry)
    BoxWithConstraints(modifier.fillMaxSize().background(Color.White)) {
        PortraitScene(state, viewModel, glue, viewModel.probe, view, maxWidth, maxHeight, glassesLive, glassesStreaming)
    }
}

/** La scène complète, posée dans un BoxWithConstraints déjà dimensionné. */
@Composable
private fun BoxWithConstraintsScope.PortraitScene(
    state: IseeUiState,
    viewModel: IseeViewModel,
    glue: CameraGlue,
    probe: LiveProbe,
    view: IseeSceneView,
    width: Dp,
    height: Dp,
    glassesLive: Boolean,
    glassesStreaming: Boolean,
) {
    val captureLive = state.recordingVideo
    val layers = iseeSceneLayers(
        bubbleCount(state.entries.size),
        dissolving = false,
        captureLive = captureLive,
        glassesLive = glassesLive,
        glassesStreaming = glassesStreaming,
        photoDisplayed = state.glassesPhoto != null,
    )
    val open = rememberCameraOpen(layers.cameraCard || layers.glassesFeed || layers.photoFeed)
    val position = rememberSpeechPosition(viewModel, state.speakingEntryId)
    val showGlasses = rememberGlassesFeed(layers, open)
    val navState by viewModel.voiceNavState.collectAsState()
    val density = LocalDensity.current
    // Un seul calcul par compose : le guidage est lu quatre fois plus bas.
    val guidance = isGuidanceOverlay(navState)
    val bottom = navInsetDp()
    // Bloc caméra 60 %, comme la carte en grand (70 % en guidage, où
    // la carte prend le bas, la boule s'efface et la phrase reste).
    // Mémorisée : seul `open` et le guidage la changent, pas les recomposes
    // voisins (parole, sonde, navigation).
    val geo = remember(density, width, height, open, guidance) {
        with(density) { portraitGeometry(width.toPx(), (height - bottom).toPx(), open, 1f, guidance) }
    }
    with(density) { PortraitCamera(state, glue, probe, viewModel, open, geo.pictureH.toDp(), showGlasses, layers.glassesGhost) }
    GlassesProbeLayer(state, viewModel.probe, glassesLive, glassesStreaming, guidance)
    Header(view.scene, onPicture = open > 0.5f, modifier = Modifier.fillMaxWidth())
    // navState est collecté plus haut : la taille de la rue en dépend.
    SceneGuidanceLayer(viewModel) {
        SceneOrbLayer(state, view, geo, open, position, navState)
    }
    BottomScrim(bottom)
    SceneStatusBar(state, viewModel, guidance, bottom)
    // Génération picto : le bloc fin semi-transparent survit à la rue
    // (génération lancée, vidéo coupée), jamais dehors.
    if (state.placeMode.pictoEnabled && state.picto.phase == PictoPhase.Generating) PictoGeneratingCard()
}


/**
 * Ligne de statut du bas : sans elle, `IseeUiState.status` (échecs de dictée, LLM, micro)
 * n'est lu par aucun composable et l'écran reste figé sur l'attente — câblage d'origine
 * (0db26f2), perdu par 580b2e5.
 *
 * Deux exceptions voulues : en **guidage** la ligne de confort disparaît (la carte prend la
 * place), mais une **panne du direct** s'affiche quand même — c'est justement le mode où
 * l'on marche, donc celui où des masques figés font croire que tout va bien.
 */
@Composable
private fun BoxScope.SceneStatusBar(state: IseeUiState, viewModel: IseeViewModel, guidance: Boolean, bottom: Dp) {
    // La sonde écrit deux fois par frame : ce corps se réexécute à chaque
    // tick, mais le modificateur stable laisse la feuille intacte quand
    // le texte ne change pas (l'expiration du « Back online » est gardée :
    // la ligne est toujours recalculée sur l'état frais).
    val outage = outageLineOf(viewModel.probe.state.collectAsState().value)
    if (guidance && outage == null) return
    val bar = remember(bottom) { Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 10.dp) }
    SceneBottomBar(state, outage, bar)
}

/**
 * Pont lunettes → sonde : nourri seulement quand le direct vit, qu'aucune photo n'est
 * figée à l'écran et qu'un live est demandé — guidage ou Traversée ([glassesProbeFeed]).
 * Le guidage se lit sur `isGuidanceOverlay(navState)`, le signal que la scène utilise déjà
 * pour agrandir la rue, effacer la boule et poser la carte — donc le même « mode route »
 * que celui vu par l'utilisateur. La Traversée est lue sur la tranche chiffres
 * ([LiveProbe.collectRest]) : cette feuille légère recompose à l'écriture #2, mais le
 * [GlassesProbeWiring] keyé sur le booléen ne redémarre qu'aux transitions.
 */
@Composable
private fun GlassesProbeLayer(
    state: IseeUiState,
    probe: LiveProbe,
    glassesLive: Boolean,
    glassesStreaming: Boolean,
    guidance: Boolean,
) {
    val crossingActive = probe.collectRest().crossingActive
    val feed = glassesProbeFeed(
        glassesLive = glassesLive,
        glassesStreaming = glassesStreaming,
        photoDisplayed = state.glassesPhoto != null,
        guidance = guidance,
        crossingActive = crossingActive,
    )
    GlassesProbeWiring(probe, feed, guidance)
}

/**
 * La boule et la phrase en cours. En guidage caméra ouverte, la boule
 * s'efface entre la rue et la carte mais la phrase d'iSee reste affichée
 * en direct ; quand le user parle, son transcript prend le milieu. Extrait pour garder
 * `PortraitScene` sous le plafond de 50 lignes.
 */
@Composable
private fun BoxWithConstraintsScope.SceneOrbLayer(
    state: IseeUiState,
    view: IseeSceneView,
    geo: PortraitGeometry,
    open: Float,
    position: SpeechPosition?,
    navState: VoiceNavUiState,
) {
    val density = LocalDensity.current
    val fromUser = state.holding && state.userSpeaking
    if (showGuidanceTranscript(navState, state)) {
        with(density) {
            SpokenLine(currentCaption(state, position), fromUser, (geo.orbY - geo.orbR).toDp(), compact = open > 0.5f)
        }
    } else if (hideGuidanceOrb(navState, open > 0.5f)) {
        // Boule effacée : la phrase d'iSee reste entre la rue agrandie et la carte.
        with(density) {
            SpokenLine(currentCaption(state, position), fromUser, geo.pictureH.toDp() + 8.dp, compact = true)
        }
    } else {
        OrbClockLayer(view.scene, geo, open)
        with(density) {
            SpokenLine(currentCaption(state, position), fromUser, geo.textTop.toDp(), compact = open > 0.5f)
        }
    }
}

/** Boucle du temps de la scène : 10 minutes, comme l'ancienne transition infinie. */
internal const val SCENE_LOOP_MS = 600_000L

/**
 * Temps de la scène en secondes entre deux lectures d'une horloge monotone (ms) : avance
 * toujours, même quand les animations système sont coupées (`animator_duration_scale=0`
 * fige `rememberInfiniteTransition` — constaté sur le Redmi le 2026-10-28 : la boule
 * restait pixel-identique à 4 s d'intervalle). Pure : testée sur JVM.
 */
internal fun sceneTimeSeconds(startMs: Long, nowMs: Long): Float =
    ((nowMs - startMs) % SCENE_LOOP_MS).toFloat() / 1000f

/**
 * Le temps de la scène en secondes, pour le marbre et la voix (boucle de 10 min).
 * Calé sur la vsync (`withFrameNanos`, un tick par frame affichée au plus) ; gelé
 * quand la scène est sans voix (`amp == 0` : ni écoute ni parole) — le décor ne
 * bouge plus, aucune recompose tant que personne ne parle.
 */
@Composable
private fun rememberSceneTime(scene: IseeScene): Float {
    var time by remember { mutableStateOf(0f) }
    val voiceActive = scene == IseeScene.LISTENING || scene == IseeScene.VOICE
    LaunchedEffect(voiceActive) {
        if (!voiceActive) return@LaunchedEffect
        // Reprise en douceur : l'horloge repart du temps figé, sans saut.
        val start = SystemClock.elapsedRealtime() - (time * 1000f).toLong()
        while (true) {
            withFrameNanos { }
            time = sceneTimeSeconds(start, SystemClock.elapsedRealtime())
        }
    }
    return time
}

/**
 * Lit l'horloge de la scène au plus près de son unique lecteur, la boule : lue plus haut
 * (dans [PortraitScene]), chaque tick à 60 Hz réévaluait tout le sous-arbre de la scène.
 * Ce relais garde [OrbLayer] gelée à l'octet (`tools/orb-frozen-baseline.json`,
 * docs/ISEE-DESIGN-PORTRAIT.md §6) tout en n'invalidant que la couche de la boule.
 */
@Composable
private fun OrbClockLayer(scene: IseeScene, geo: PortraitGeometry, open: Float) {
    OrbLayer(scene, geo, rememberSceneTime(scene), open)
}

/** La boule, et la couronne de barres ambre quand iSee parle. */
@Composable
private fun OrbLayer(scene: IseeScene, geo: PortraitGeometry, time: Float, open: Float) {
    val look = orbLookOf(scene, time, open)
    val paint = rememberOrbPainter()
    val breathe = 1f + 0.012f * kotlin.math.sin(time * 1.3f) + 0.03f * look.you * look.amp
    val lift = geo.orbR * 0.02f * kotlin.math.sin(time * 0.9f) * (1f - open)
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(geo.orbX, geo.orbY + lift)
        val r = geo.orbR * breathe
        drawOrbShadow(c, r, 1f)
        paint(this, c, r, look)
        if (look.see > 0.01f) drawVoiceCrown(c, r, time, look.see, look.amp)
    }
}

/** Ce que montre la boule selon la scène : bleu à l'écoute, ambre quand iSee parle. */
internal fun orbLookOf(scene: IseeScene, time: Float, open: Float): OrbLook {
    val you = if (scene == IseeScene.LISTENING) 1f else 0f
    val see = if (scene == IseeScene.VOICE) 1f else 0f
    val amp = if (you + see > 0f) voiceEnvelope(time, if (see > 0f) 3 else 1) else 0f
    val level = 0.4f + 0.08f * kotlin.math.sin(time * 0.5f) + 0.08f * see - 0.06f * open
    return OrbLook(time = time, you = you, see = see, amp = amp, level = level)
}

/** Pénombre diffuse, ombre portée et cœur de contact, fondus sur le sol blanc. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrbShadow(c: Offset, r: Float, k: Float) {
    if (k <= 0.01f) return
    // Demi-largeur, demi-hauteur, opacité : le contact se resserre vers le centre.
    val lobes = listOf(
        Triple(0.94f, 0.18f, 0.09f),
        Triple(0.66f, 0.085f, 0.14f),
        Triple(0.34f, 0.032f, 0.18f),
    )
    val center = Offset(c.x + r * 0.045f, c.y + r * 1.13f)
    val ink = Color(IseePortrait.INK)
    lobes.forEach { (width, height, opacity) ->
        // Comprimer le dégradé lui-même évite le bord dur d'un disque rogné en ovale.
        withTransform({
            translate(center.x, center.y)
            scale(1f, height / width, pivot = Offset.Zero)
        }) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to ink.copy(alpha = opacity),
                    0.28f to ink.copy(alpha = opacity * 0.78f),
                    0.58f to ink.copy(alpha = opacity * 0.32f),
                    0.82f to ink.copy(alpha = opacity * 0.06f),
                    1f to ink.copy(alpha = 0f),
                    center = Offset.Zero,
                    radius = r * width,
                ),
                radius = r * width,
                center = Offset.Zero,
                alpha = k,
            )
        }
    }
}

/** La couronne de 120 barres fines qui monte du verre avec la voix d'iSee. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVoiceCrown(c: Offset, r: Float, t: Float, k: Float, amp: Float) {
    val n = 120
    val see = Color(IseePortrait.SEE)
    for (i in 0 until n) {
        val a = i / n.toFloat() * 2f * Math.PI.toFloat()
        val e = 0.5f + 0.28f * kotlin.math.sin(a * 3 + t * 2.1f) + 0.18f * kotlin.math.sin(a * 7 - t * 3.3f + 1.3f) +
            0.12f * kotlin.math.sin(a * 13 + t * 5.2f)
        val len = r * (0.02f + 0.2f * amp * e * e) * k
        val r0 = r * 1.07f
        val dx = kotlin.math.cos(a)
        val dy = kotlin.math.sin(a)
        drawLine(
            color = see.copy(alpha = (k * (0.3f + 0.6f * e)).coerceIn(0f, 1f)),
            start = Offset(c.x + dx * r0, c.y + dy * r0),
            end = Offset(c.x + dx * (r0 + len), c.y + dy * (r0 + len)),
            strokeWidth = (r * 0.011f).coerceAtLeast(1f),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** Bandeau blanc derrière les boutons système du bas. */
@Composable
private fun BoxWithConstraintsScope.BottomScrim(bottom: Dp) {
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White).padding(bottom = bottom))
}

/** Le crochet de navigation (raccourci : l'inset de navigation du téléphone). */
@Composable
internal fun navInsetDp(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * Source affichée, avec mémoire : la dernière image des lunettes reste figée pendant
 * le fondu de fermeture (`showGlassesFeed`). L'affectation est un point fixe stable.
 */
@Composable
private fun rememberGlassesFeed(layers: IseeSceneLayers, open: Float): Boolean {
    var lastGlasses by remember { mutableStateOf(false) }
    val showGlasses = showGlassesFeed(layers.glassesFeed, layers.glassesGhost, layers.cameraCard, open, lastGlasses)
    // Écrit après application : écrire pendant la composition planifie un
    // recompose immédiat à chaque bascule (double passe pour rien).
    SideEffect { lastGlasses = showGlasses }
    return showGlasses
}
