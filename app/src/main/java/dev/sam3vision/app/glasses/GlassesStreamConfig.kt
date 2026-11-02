package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality

/**
 * Cadence demandée au flux des lunettes pour l'écran iSee : 24 i/s.
 *
 * Le SDK Meta DAT (mwdat-camera 1.0.0, bytecode `StreamImpl.createVideoFormat`
 * et `VideoFormat.defaultFormat`) fixe `bitRate = 750000` bps pour toutes les
 * qualités, borne `frameRate` à `coerceIn(2..30)` puis l'accroche à
 * {30, 24, 15, 7, 2}. Demander 60 revient donc à demander 30, sans erreur ni
 * avertissement. À débit constant, chaque image de plus retire des bits aux
 * autres : 750 kbit/s à 30 i/s sur 720×1280 = 0,027 bit/pixel, et les
 * macroblocs apparaissent dès que la tête tourne. À 24 i/s : 0,034 bit/pixel ;
 * à 15 i/s : 0,054. 24 est la dernière cadence où le débit fixe du SDK laisse
 * assez de bits par image ; au-delà, on vole des bits à chaque image sans rien
 * gagner en fluidité réelle.
 *
 * La cadence tenue est mesurée, pas supposée : recette `LUNETTES-RECETTE-TEL`
 * §3 (`parse_glasses_link.py`).
 */
internal const val GLASSES_FRAME_RATE = 24

/**
 * Essais de cadence dans l'ordre, du maximum au dernier recours (miroir du
 * plan audio [GlassesAudioPlan.ladder]) : un refus du lien ne doit pas priver
 * l'app de caméra — `GlassesCamera` annonce le repli tenu. 15 = borne basse
 * du ladder SDK pour la vidéo, jamais en dessous.
 */
internal val GLASSES_FRAME_RATE_LADDER = listOf(24, 15)

/**
 * Configuration du flux vidéo des lunettes pour l'écran iSee (production).
 *
 * - **Qualité haute** (`VideoQuality.HIGH` = 720×1280 portrait) : à qualité
 *   moyenne (504×896) la rue iSee (~1080 px de large) agrandissait chaque
 *   image ~2,1× — flou permanent. En haute, l'agrandissement tombe à ~1,5×,
 *   soit ~2× plus de pixels sources par pixel écran.
 * - **24 i/s** ([GLASSES_FRAME_RATE], [frameRate]) : le maximum utile à
 *   750 kbit/s (KDoc de [GLASSES_FRAME_RATE]) ; repli
 *   [GLASSES_FRAME_RATE_LADDER] (15 i/s) si le lien refuse.
 * - **Vidéo compressée** : le téléphone reçoit du H.265 et le rend en direct
 *   (`GlassesLiveDecoder`), sans ré-échantillonnage intermédiaire.
 *
 * ⚠️ Le SDK n'expose ni bitrate ni résolution directe : la qualité est le
 * seul levier spatial, le débit restant fixé en interne à 750 kbit/s quel que
 * soit le réglage demandé. L'ancien commentaire « au-delà de la qualité
 * moyenne, le lien casserait l'image » était un constat non remesuré : la
 * haute qualité est **NON VALIDÉE sur matériel** : le H.265 à 720p/24 i/s est
 * gardé tel quel (rue nette sans freeze = le lien tient ; freezes = `MEDIUM`
 * ici, ou cadence tenue plus basse — le repli est annoncé, pas muet).
 *
 * Pure (le SDK s'instancie sur JVM) : prouvée par `GlassesStreamConfigTest`.
 * Le banc (`bench/GlassesDeviceSession`) garde sa propre config : outil LLM,
 * pas écran — il ne partage ni la cadence ni le besoin.
 */
internal fun glassesStreamConfiguration(
    audioCodec: AudioCodec?,
    frameRate: Int = GLASSES_FRAME_RATE,
): StreamConfiguration =
    StreamConfiguration(
        audioCodec = audioCodec,
        videoQuality = VideoQuality.HIGH,
        frameRate = frameRate,
        compressVideo = true,
    )
