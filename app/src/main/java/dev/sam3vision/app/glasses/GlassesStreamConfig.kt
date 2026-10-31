package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality

/**
 * Cadence du flux des lunettes pour l'écran iSee : 15 i/s.
 *
 * Volontairement basse : chaque image/s de plus est du bitrate pris au piqué
 * spatial sur un lien contraint (Bluetooth Classic), et 15 i/s suffit à suivre
 * une rue à pied. Le décodeur matériel avale ça sans chauffer.
 */
internal const val GLASSES_FRAME_RATE = 15

/**
 * Configuration du flux vidéo des lunettes pour l'écran iSee (production).
 *
 * - **Qualité haute** (`VideoQuality.HIGH` = 720×1280 portrait) : à qualité
 *   moyenne (504×896) la rue iSee (~1080 px de large) agrandissait chaque
 *   image ~2,1× — flou permanent. En haute, l'agrandissement tombe à ~1,5×,
 *   soit ~2× plus de pixels sources par pixel écran. Le H.265 à 720p/15 i/s
 *   resterait autour de ~1 Mbit/s — débit réel **non mesuré** (voir ci-dessous).
 * - **15 i/s** ([GLASSES_FRAME_RATE]) : le bitrate va au piqué, pas au débit.
 * - **Vidéo compressée** : le téléphone reçoit du H.265 et le rend en direct
 *   (`GlassesLiveDecoder`), sans ré-échantillonnage intermédiaire.
 *
 * ⚠️ Le SDK n'expose ni bitrate ni résolution directe : la qualité est le
 * seul levier spatial. Et l'ancien commentaire « au-delà de la qualité
 * moyenne, le lien casserait l'image » était un constat non remesuré : la
 * haute qualité est **NON VALIDÉE sur matériel** : le H.265 à 15 i/s est gardé
 * tel quel (rue nette sans freeze = le lien tient ; freezes = `MEDIUM` ici).
 *
 * Pure (le SDK s'instancie sur JVM) : prouvée par `GlassesStreamConfigTest`.
 * Le banc (`bench/GlassesDeviceSession`) garde sa propre config : outil LLM,
 * pas écran — il ne partage ni la cadence ni le besoin.
 */
internal fun glassesStreamConfiguration(audioCodec: AudioCodec?): StreamConfiguration =
    StreamConfiguration(
        audioCodec = audioCodec,
        videoQuality = VideoQuality.HIGH,
        frameRate = GLASSES_FRAME_RATE,
        compressVideo = true,
    )
