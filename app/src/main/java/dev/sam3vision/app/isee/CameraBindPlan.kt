package dev.sam3vision.app.isee

/**
 * Les décisions de la liaison caméra, **pures** : quels cas d'usage demander, quoi
 * faire d'une tentative ratée, faut-il (re)lier, et le mot affiché. Aucun type
 * Android ici — la partie CameraX vit dans `IseeCameraBinder`, ces règles-là sont
 * verrouillées en JVM (`CameraBindPlanTest`).
 *
 * Pourquoi ce fichier : un `bindToLifecycle` réussi suivi d'une étape qui lève
 * laissait la caméra **liée** sous un état « échec » (aucun `recorder.attach`,
 * aucun re-lien), et rien ne reprenait la caméra qu'une autre app avait prise.
 * Ces deux décisions sont désormais des fonctions.
 */

/** Cas d'usage qu'une tentative peut demander (miroir de CameraX, sans l'importer). */
enum class BindUseCase { PREVIEW, ANALYSIS, CAPTURE, VIDEO }

/** Une tentative de liaison : les cas demandés, et celui qu'elle sacrifie. */
data class BindAttempt(val useCases: Set<BindUseCase>, val dropped: BindUseCase?)

/**
 * Les tentatives, de la plus complète à la plus nue. On retire d'abord la vidéo,
 * puis la photo : ce sont les deux qui réclament des surfaces supplémentaires,
 * souvent la ressource qui manque à un HAL. L'analyse part **en dernier** — sans
 * elle iSee est aveugle, c'est le flux vital — et l'aperçu juste avant : un écran
 * noir reste préférable à plus aucune frame.
 */
fun bindPlans(): List<BindAttempt> = listOf(
    BindAttempt(setOf(BindUseCase.PREVIEW, BindUseCase.ANALYSIS, BindUseCase.CAPTURE, BindUseCase.VIDEO), null),
    BindAttempt(setOf(BindUseCase.PREVIEW, BindUseCase.ANALYSIS, BindUseCase.CAPTURE), BindUseCase.VIDEO),
    BindAttempt(setOf(BindUseCase.PREVIEW, BindUseCase.ANALYSIS), BindUseCase.CAPTURE),
    BindAttempt(setOf(BindUseCase.ANALYSIS), BindUseCase.PREVIEW),
)

/** Délais de reprise après échec (ms), croissants : au-delà, on cesse de retenter. */
val CAMERA_RETRY_DELAYS_MS: List<Long> = listOf(250L, 1_000L, 3_000L, 10_000L, 30_000L)

/**
 * Faut-il retenter, et dans combien de temps ? `null` = non : un MP4 s'écrit (un
 * `unbindAll` tuerait le fichier) ou le plafond d'essais est atteint — la reprise
 * reste alors au retour au premier plan, pas dans une boucle. Les [CAMERA_RETRY_DELAYS_MS]
 * sont les délais des reprises 1 à N, tous utilisés : on renonce à N+1.
 */
fun nextRetryDelayMs(failures: Int, recording: Boolean): Long? =
    if (recording || failures <= 0) null else CAMERA_RETRY_DELAYS_MS.getOrNull(failures - 1)

/** Ce qu'il y a à faire de la caméra maintenant. */
enum class CameraGate {
    /** Ne rien toucher : pas de permission, caméra déjà liée et saine, ou MP4 en cours. */
    HOLD,

    /** Rien n'est lié : lier. */
    BIND,

    /** Une caméra a été liée puis fermée par le système (autre app, veille) : relier. */
    REBIND,

    /** Attendre le délai de reprise, puis retenter. */
    RETRY,

    /** Plafond d'essais atteint : on cesse ; le retour au premier plan relancera. */
    GIVE_UP,
}

/**
 * La décision, à partir de faits observables seulement :
 *
 * - `granted` : la permission CAMERA est réellement accordée à cet instant ;
 * - `bound` : une caméra est liée et utilisable ;
 * - `closed` : CameraX a rapporté `CLOSED` (autre app, veille, restriction) ;
 * - `recording` : un MP4 s'écrit — on ne délie jamais sous lui ;
 * - `failures` : échecs consécutifs depuis la dernière liaison réussie.
 */
fun cameraGate(
    granted: Boolean,
    bound: Boolean,
    closed: Boolean,
    recording: Boolean,
    failures: Int,
): CameraGate = when {
    !granted -> CameraGate.HOLD
    recording -> CameraGate.HOLD
    failures > CAMERA_RETRY_DELAYS_MS.size -> CameraGate.GIVE_UP
    failures > 0 -> CameraGate.RETRY
    closed -> CameraGate.REBIND
    bound -> CameraGate.HOLD
    else -> CameraGate.BIND
}

/** Verdict d'une tentative : la caméra est prête, ou il faut la délier. */
enum class BindVerdict { READY, RELEASE }

/**
 * Une caméra n'est « prête » que si la liaison **et** le recorder sont posés, et
 * qu'aucune étape vitale n'a levé. Tout le reste — liée sans recorder, ou liée
 * avec un échec avoué — doit être délié puis retenté : jamais une caméra allumée
 * que l'état dit en panne, jamais un tap qui répond « pas prête » sans raison.
 */
fun bindVerdict(bound: Boolean, recorderAttached: Boolean, failure: String?): BindVerdict =
    if (bound && recorderAttached && failure == null) BindVerdict.READY else BindVerdict.RELEASE

/** Le mot affiché sur la carte quand la caméra manque (l'utilisateur doit le savoir). */
const val CAMERA_MISSING_TEXT = "Camera unavailable — retrying…"

/** Longueur maximale du détail technique ajouté au mot de la carte (caractères). */
const val CAMERA_DETAIL_MAX = 40

/**
 * Le texte que lit l'utilisateur pour un échec de liaison : le mot de la carte,
 * suivi du détail technique tronqué (une exception complète déborderait de la
 * ligne). `null` ou vide : le mot seul. Une caméra morte ne doit plus ressembler
 * à une perte de connexion (l'échec était écrit et lu par personne).
 */
fun cameraFailureText(reason: String?): String {
    val detail = reason?.trim().orEmpty()
    if (detail.isEmpty()) return CAMERA_MISSING_TEXT
    val short = if (detail.length > CAMERA_DETAIL_MAX) detail.take(CAMERA_DETAIL_MAX) + "…" else detail
    return "$CAMERA_MISSING_TEXT ($short)"
}
