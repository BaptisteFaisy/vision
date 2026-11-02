package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality

/**
 * Cadence demandée au flux des lunettes pour l'écran iSee : 15 i/s.
 *
 * Le SDK Meta DAT (mwdat-camera 1.0.0, bytecode `StreamImpl.createVideoFormat`
 * et `VideoFormat.defaultFormat`) fixe `bitRate = 750000` bps pour toutes les
 * qualités, borne `frameRate` à `coerceIn(2..30)` puis l'accroche à
 * {30, 24, 15, 7, 2}. Demander 60 revient donc à demander 30, sans erreur ni
 * avertissement. À débit constant, chaque image de plus retire des bits aux
 * autres : 750 kbit/s à 30 i/s sur 720×1280 = 0,027 bit/pixel, et les
 * macroblocs apparaissent dès que la tête tourne. À 24 i/s : 0,034 bit/pixel ;
 * à 15 i/s : 0,054 — le maximum de bits par image sur la borne basse du
 * ladder, là où les déchirures d'écran reculent le plus.
 *
 * La cadence tenue est mesurée, pas supposée : recette `LUNETTES-RECETTE-TEL`
 * §3 (`parse_glasses_link.py`).
 */
internal const val GLASSES_FRAME_RATE = 15

/**
 * Essais de cadence dans l'ordre : 15 i/s seul — demande et borne basse du
 * ladder confondues, pas de repli plus bas possible. `GlassesCamera` garde sa
 * boucle d'essais (et l'annonce `frameRateFallback`) pour un futur repli,
 * jamais muet.
 */
internal val GLASSES_FRAME_RATE_LADDER = listOf(15)

/**
 * Taux d'abandon au-delà duquel le flux des lunettes passe en qualité moyenne :
 * 5 % — à ce niveau le lien radio sature (jitter, freezes) et la rue plein
 * format n'arrive de toute façon plus à temps.
 */
internal const val GLASSES_MEDIUM_DROP_RATE = 0.05

/**
 * Qualité vidéo demandée au flux : haute par défaut, moyenne quand le lien
 * sature ([dropRate] > 5 %) — ~55 % de pixels en moins par frame sur un lien
 * saturé, là où chaque octet compte. Pur, testé en JVM.
 */
internal fun glassesVideoQuality(dropRate: Double): VideoQuality =
    if (dropRate > GLASSES_MEDIUM_DROP_RATE) VideoQuality.MEDIUM else VideoQuality.HIGH

/**
 * Configuration du flux vidéo des lunettes pour l'écran iSee (production).
 *
 * - **Qualité haute** (`VideoQuality.HIGH` = 720×1280 portrait) : à qualité
 *   moyenne (504×896) la rue iSee (~1080 px de large) agrandissait chaque
 *   image ~2,1× — flou permanent. En haute, l'agrandissement tombe à ~1,5×,
 *   soit ~2× plus de pixels sources par pixel écran.
 * - **Repli moyen automatique** : [dropRate] > 5 % ([GLASSES_MEDIUM_DROP_RATE])
 *   demande `MEDIUM` ([glassesVideoQuality]) — un lien saturé ne porte plus le
 *   plein format, et la rue réduite à temps vaut mieux que la rue pleine en
 *   retard. Le repli s'applique à l'attache suivante (jamais de reconfiguration
 *   en cours de session : le SDK ne le permet pas sans couper le flux).
 * - **15 i/s** ([GLASSES_FRAME_RATE], [frameRate]) : le maximum de bits par
 *   image à 750 kbit/s fixe (0,054 bit/pixel, KDoc de [GLASSES_FRAME_RATE]) ;
 *   [GLASSES_FRAME_RATE_LADDER] réduit à [15], borne basse du ladder.
 * - **Vidéo compressée** : le téléphone reçoit du H.265 et le rend en direct
 *   (`GlassesLiveDecoder`), sans ré-échantillonnage intermédiaire.
 *
 * ⚠️ Le SDK n'expose ni bitrate ni résolution directe : la qualité est le
 * seul levier spatial, le débit restant fixé en interne à 750 kbit/s quel que
 * soit le réglage demandé — il n'y a pas de levier MTU/GATT côté app, le
 * transport radio est 100 % SDK Meta DAT (aucun socket ni `BluetoothGatt` dans
 * `app/` ni `core/`, vérifié par grep). Tout correctif lien est donc applicatif
 * (bits/frame, copies, verrous), jamais radio. L'ancien commentaire « au-delà
 * de la qualité moyenne, le lien casserait l'image » était un constat non
 * remesuré : la haute qualité est **NON VALIDÉE sur matériel**, et le repli
 * `MEDIUM` ci-dessus est le seul levier quand elle freeze.
 *
 * Pure (le SDK s'instancie sur JVM) : prouvée par `GlassesStreamConfigTest`.
 * Le banc (`bench/GlassesDeviceSession`) garde sa propre config : outil LLM,
 * pas écran — il ne partage ni la cadence ni le besoin.
 */
internal fun glassesStreamConfiguration(
    audioCodec: AudioCodec?,
    frameRate: Int = GLASSES_FRAME_RATE,
    dropRate: Double = 0.0,
): StreamConfiguration =
    StreamConfiguration(
        audioCodec = audioCodec,
        videoQuality = glassesVideoQuality(dropRate),
        frameRate = frameRate,
        compressVideo = true,
    )
