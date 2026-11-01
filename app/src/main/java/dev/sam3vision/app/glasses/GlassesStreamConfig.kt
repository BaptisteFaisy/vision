package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality

/**
 * Cadence demandée au flux des lunettes pour l'écran iSee : 60 i/s.
 *
 * Le maximum, pas le confort : à 15 i/s le direct saccade et la rue semble
 * floue en mouvement. Les cadences citées par la doc Meta (2 / 7 / 15 / 24 / 30,
 * `GLASSES-DECISION` §7, non revérifiée) ne promettent que 30 — 60 est donc un
 * essai, et [GLASSES_FRAME_RATE_LADDER] dégrade (30, 24, puis 15) si le lien
 * refuse, plutôt que de priver l'app de caméra. La cadence tenue est mesurée,
 * pas supposée : recette `LUNETTES-RECETTE-TEL` §3 (`parse_glasses_link.py`).
 */
internal const val GLASSES_FRAME_RATE = 60

/**
 * Essais de cadence dans l'ordre, du maximum au dernier recours (miroir du
 * plan audio [GlassesAudioPlan.ladder]) : un refus du lien ne doit ni priver
 * l'app de caméra, ni la laisser muette — `GlassesCamera` annonce le repli
 * tenu. 15 = borne basse du ladder SDK, jamais en dessous.
 */
internal val GLASSES_FRAME_RATE_LADDER = listOf(60, 30, 24, 15)

/**
 * Configuration du flux vidéo des lunettes pour l'écran iSee (production).
 *
 * - **Qualité haute** (`VideoQuality.HIGH` = 720×1280 portrait) : à qualité
 *   moyenne (504×896) la rue iSee (~1080 px de large) agrandissait chaque
 *   image ~2,1× — flou permanent. En haute, l'agrandissement tombe à ~1,5×,
 *   soit ~2× plus de pixels sources par pixel écran. Le H.265 à 720p/60 i/s
 *   demanderait ~4 Mbit/s — débit réel **non mesuré** (voir ci-dessous).
 * - **60 i/s** ([GLASSES_FRAME_RATE], [frameRate]) : le maximum demandé ; repli
 *   [GLASSES_FRAME_RATE_LADDER] si le lien refuse.
 * - **Vidéo compressée** : le téléphone reçoit du H.265 et le rend en direct
 *   (`GlassesLiveDecoder`), sans ré-échantillonnage intermédiaire.
 *
 * ⚠️ Le SDK n'expose ni bitrate ni résolution directe : la qualité est le
 * seul levier spatial. Et l'ancien commentaire « au-delà de la qualité
 * moyenne, le lien casserait l'image » était un constat non remesuré : la
 * haute qualité est **NON VALIDÉE sur matériel** : le H.265 à 60 i/s est gardé
 * tel quel (rue nette sans freeze = le lien tient ; freezes = `MEDIUM` ici,
 * ou cadence tenue plus basse — le repli est annoncé, pas muet).
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
