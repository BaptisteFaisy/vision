package dev.sam3vision.core.settings

import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Réglages de l'app. [samBaseUrl] vide = preset de la région ([samRegion]) :
 * l'endpoint se résout toujours ([resolveSamEndpoint]), la segmentation n'est
 * jamais « désactivée » faute d'URL. Aucune valeur de clé n'est jamais écrite en dur.
 */
data class Settings(
    val llmBaseUrl: String,
    val llmApiKey: String,
    val llmModel: String,
    val samBaseUrl: String,
    val ttsApiKey: String = "",
    val ttsVoiceId: String = DEFAULT_TTS_VOICE_ID,
    /**
     * Vitesse de lecture des voix, de [MIN_TTS_SPEED] à [MAX_TTS_SPEED]
     * ([DEFAULT_TTS_SPEED] par défaut). Appliquée **en lecture locale**
     * (`MediaPlayer` pour ElevenLabs) : le modèle runtime (`eleven_v4_turbo`)
     * ignore le réglage `speed` du cloud, donc l'envoyer ne changerait rien —
     * ici tout l'audio, frais ou en cache, parle à la vitesse choisie, même hors ligne.
     */
    val ttsSpeed: Float = DEFAULT_TTS_SPEED,
    /** Lecture automatique des réponses du chat (désactivable dans Réglages). */
    val ttsAutoplay: Boolean = true,
    /** Dictée : base URL / clé / modèle du moteur principal (Meta Muse). */
    val asrBaseUrl: String = DEFAULT_ASR_BASE_URL,
    val asrApiKey: String = "",
    val asrModel: String = DEFAULT_ASR_MODEL,
    /**
     * Narration continue du **Direct** : même modèle Meta que les questions
     * ([DEFAULT_LIVE_LLM_MODEL]), mais avec sa **propre clé** — la narration ne part
     * **que** si cette clé est renseignée (vide = Direct muet, aucune narration).
     * Le raisonnement interne est coupé côté client (`enable_thinking=false`), donc le
     * budget de 200 tokens va à la phrase, pas aux pensées.
     */
    val liveLlmApiKey: String = "",
    /**
     * Annonce vocale des **positions** dès qu'un objet est détecté (mode Direct et
     * analyse photo) : « Personne à 9 heures, chien à 3 heures. » Via ElevenLabs, jamais
     * le TTS système. `true` par défaut : c'est la fonction d'assistance attendue.
     */
    val ttsAnnouncePositions: Boolean = true,
    /**
     * Guidage « tout droit » de la page caméra iSee : la voix dit un mot
     * (« Tout droit. », « Gauche. », « Droite. », « Stop. ») au lieu de la
     * position des objets. `true` par défaut : c'est l'intention de marche attendue.
     * Clips pré-synthétisés (< 100 ms), jamais le TTS système, sans aucun appel LLM.
     */
    val ttsGuidanceStraight: Boolean = true,
    /** Intro vocale embarquée autorisée (bascule des Réglages, `true` par défaut). */
    val introEnabled: Boolean = true,
    /** Marqueur historique « intro déjà entendue », conservé pour le format de fichier
     * (l'intro ne s'appuie plus dessus : elle est rejouée à chaque ouverture complète). */
    val introPlayed: Boolean = false,
    /** Distances affichées sur le direct (hauteurs connues + sténopé, sans GPU). */
    val distanceOverlayEnabled: Boolean = true,
    /**
     * Direct avec détecteur : les frames partent à SAM `/live` sur Modal pendant que la
     * caméra tourne (masques, objets, distances, journal), comme demandé par le user le
     * 2026-10-26. Les **questions** partent quand même directement au LLM, sans attendre
     * SAM. `false` = vision LLM seule, aucun appel Modal, aucun GPU.
     */
    val liveSamEnabled: Boolean = true,
    /**
     * Prédiction locale des boîtes (`core/predict`, T8) : entre deux réponses serveur,
     * la boîte extrapolée prend le relais quand le masque a vieilli, au lieu d'attendre
     * la réponse suivante. **`false` par défaut** : le branchement n'est allumé
     * qu'après la mesure de la latence perçue — sans elle, une boîte qui bouge peut
     * être pire qu'une boîte qui saute. Le câblage vit côté app (`live/LivePrediction`).
     */
    val boxPredictionEnabled: Boolean = false,
    /**
     * Calque local (lane L13) : entre deux keyframes du serveur, le masque du dernier
     * keyframe est propagé sur le téléphone (30 ips visés, budget de peinture 4 ms).
     * `true` par défaut ; sans estimateur de mouvement branché, le calque reste **inerte**
     * et l'écran se comporte exactement comme avant.
     */
    val localOverlayEnabled: Boolean = true,
    /**
     * Carte de debug : tracé schématique de l'itinéraire guidé + position GPS,
     * en fond de l'écran iSee (derrière la boule et les textes). `true` par
     * défaut ; sans guidage actif, rien n'est dessiné et l'écran est inchangé.
     */
    val debugRouteMapEnabled: Boolean = true,
    /**
     * Guidage GPS : la destination confirmée (« emmène-moi à X ») part en guidage
     * Google Maps (`google.navigation:` puis repli URL universelle). Éteint : seule
     * la fiche du lieu s'affiche, sans lancer de guidage. `true` par défaut.
     */
    val navigationGuidanceEnabled: Boolean = true,
    /**
     * Champ de vision horizontal imposé à la caméra, en degrés : 0 = lu sur le
     * capteur. Sert quand le driver ne répond pas, ou pour affiner l'étalonnage.
     */
    val cameraFovDeg: Float = 0f,
    /**
     * Rôle de ce téléphone : [CaregiverRole.Blind] (défaut, iSee) ou
     * [CaregiverRole.Caregiver] (page de suivi du proche). Persisté : l'app
     * rouvre sur la page du rôle choisi.
     */
    val caregiverRole: CaregiverRole = CaregiverRole.Blind,
    /**
     * Base du relais de suivi (Worker `suivi-relais/`) : c'est l'origine de la page
     * caregiver embarquée dans l'APK et de ses appels `/api/state`. Une base par
     * flux est construite à partir de [shareId] ; vider le champ retombe sur le
     * relais par défaut.
     */
    val parentSiteUrl: String = DEFAULT_PARENT_SITE_URL,
    /** Clé du fond de carte Google, préremplie par le build ; modifiable dans Réglages. */
    val googleMapsApiKey: String = "",
    /** Envoi de la position + objets au relais pendant le Direct (site parent). */
    val shareEnabled: Boolean = true,
    /**
     * Invitation collée par le proche (rôle Caregiver) : le lien ou le code que la
     * personne aveugle lui a envoyé. Acceptée telle quelle (« léo », « ?share=léo »
     * ou le lien complet) — voir `parseCaregiverInvite`. Vide = pas d'invitation.
     */
    val caregiverInvite: String = "",
    /**
     * Identifiant du flux de partage (relais `suivi-<shareId>`), **propre à ce
     * téléphone** : attribué au chargement des réglages par `seedShareRelay`, vide
     * tant qu'il ne l'a pas encore été. Jamais saisi à la main.
     */
    val shareId: String = "",
    /** Clé d'écriture vérifiée par le relais (jamais exposée côté site). */
    val shareWriteKey: String = "",
    /** Plafond d'objets affichés à l'écran, commun au direct et à la photo. */
    val maxObjects: Int = ObjectLimits.DEFAULT_ON_SCREEN,
    /**
     * Région de la GPU SAM 3.1 : Europe (défaut) ou **mode US** (carte aux États-Unis).
     * Choisit l'endpoint appelé, n'allume aucune carte. Voir [resolveSamEndpoint].
     */
    val samRegion: SamRegion = SamRegion.Eu,
    /**
     * Langue de la **voix** (annonces de scène et réponses du chat lues à voix haute,
     * consignes données au LLM). Anglais par défaut : l'intro embarquée est déjà en
     * anglais. L'interface, elle, reste en français.
     */
    val speechLanguage: SpeechLanguage = SpeechLanguage.DEFAULT,
    /**
     * Base de l'itinéraire Valhalla (`POST /route`, mode piéton) : URL publique
     * du backend Modal CPU (jamais un secret). Vide = pas d'itinéraire in-app,
     * le guidage reste celui de Google Maps. Remplie au build (`route.base.url`,
     * défaut Modal) ou dans Réglages.
     */
    val routeBaseUrl: String = "",
    /**
     * Moteur de profondeur embarqué (100 % sur le téléphone, 0 GPU Modal).
     * Actif par défaut (DA2, seuil < 200 ms tenu sur Redmi) ; OFF le coupe.
     * Réglages pré-29/10 (AUTO, MIDAS) migrés vers DA2 par fromName.
     */
    val depthBackend: DepthBackend = DepthBackend.DA2,
) {
    companion object {
        /**
         * Défaut LLM : **Baseten, muse-spark-open-1026** — le seul modèle vision que la clé
         * du workspace sert (les autres Model APIs répondent « please check the api-key »).
         * Ces deux valeurs sont indépendantes de la clé : elle seule change d'un poste à
         * l'autre, l'endpoint et le modèle restent les mêmes par défaut.
         *
         * Modèle à raisonnement : il remplit `reasoning_content` avant `content`, donc le
         * budget de sortie compte — voir `HttpLlmClient.MAX_TOKENS`.
         */
        const val DEFAULT_LLM_BASE_URL = "https://inference.baseten.co/v1"
        const val DEFAULT_LLM_MODEL = "meta-models/muse-spark-open-1026"

        /** Laure, voix par défaut de l'app (compte ElevenLabs du projet). */
        const val LAURE_VOICE_ID = "3b8fXc91YHS1i2DYAlBQ"

        /**
         * Voix ElevenLabs de départ : **Laure**, la voix de l'app. Les installations
         * d'avant migraient sur Sarah (voir [SARAH_VOICE_ID]) : la migration vit dans
         * `seeded()` côté `:app`, jamais ici.
         */
        const val DEFAULT_TTS_VOICE_ID = LAURE_VOICE_ID

        /**
         * Sarah, voix « premade » utilisable en compte free. Ancien défaut de l'app
         * (les voix de bibliothèque comme Rachel `21m00Tcm4TlvDq8ikWAM` répondent
         * 402 sans crédits payants) : conservée comme repli documenté dans les
         * Réglages et comme marqueur de migration.
         */
        const val SARAH_VOICE_ID = "EXAVITQu4vr4xnSDxMaL"

        /** Vitesse de lecture de départ : ×1,9, proche du plafond ElevenLabs (×2). */
        const val DEFAULT_TTS_SPEED = 1.9f

        /** Plancher de la vitesse (celui d'ElevenLabs) : en dessous, on n'articule plus. */
        const val MIN_TTS_SPEED = 0.5f

        /** Plafond de la vitesse (celui d'ElevenLabs). */
        const val MAX_TTS_SPEED = 2.0f

        /** Vitesse ramenée dans [MIN_TTS_SPEED]..[MAX_TTS_SPEED] (réglage hors bornes). */
        fun clampTtsSpeed(value: Float): Float = value.coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED)

        /**
         * Voix « Jade US » du compte ElevenLabs (l'intro embarquée parlait cette voix
         * jusqu'au 2026-10-28, voix Laure depuis). Proposée dans les Réglages, mais pas
         * par défaut : voix de bibliothèque, elle répond 402 sur un compte free, là où
         * Sarah est premade.
         */
        const val JADE_US_VOICE_ID = "TOXPlnYC7z5SQSV2Zgc5"

        /**
         * Narration du Direct : **même endpoint et même modèle Meta** que les questions
         * (Baseten, `muse-spark-open-1026`) — tout le LLM de l'app parle à un seul
         * fournisseur. Seule la clé diffère (`liveLlmApiKey`, Réglages / build) : sans
         * elle, le Direct se tait. Alias du LLM photo : le jour où le direct aura son
         * propre modèle, dédoubler ici.
         */
        const val DEFAULT_LIVE_LLM_BASE_URL = DEFAULT_LLM_BASE_URL
        const val DEFAULT_LIVE_LLM_MODEL = DEFAULT_LLM_MODEL

        /** API Meta (non compatible OpenAI) : la route est `{base}/asr/transcribe`. */
        const val DEFAULT_ASR_BASE_URL = "https://api.meta.ai/v1"
        const val DEFAULT_ASR_MODEL = "muse-voice-transcribe-1.0"

        /**
         * Relais de suivi de l'app (`suivi-relais/`, Worker + D1) : un état par
         * identifiant de flux. C'est lui qui reçoit la position et que lit la page
         * caregiver — plus aucun site web à maintenir.
         */
        const val DEFAULT_PARENT_SITE_URL =
            "https://site-1607fe95049a481989380f019f9067f3.freebuff.page/"

        /**
         * Ancien relais du site Vercel, abandonné (déploiement figé, écritures
         * refusées). Reconnu à la lecture pour que les installations d'avant la
         * bascule repointent sur [DEFAULT_PARENT_SITE_URL] sans rien saisir.
         */
        const val LEGACY_PARENT_SITE_URL = "https://suivi-parent.vercel.app/"

        /**
         * Ancien identifiant d'usine, commun à **toutes** les installations (« un
         * seul proche suivi ») : il n'est plus attribué, seulement reconnu pour
         * qu'une installation d'avant reçoive enfin son identifiant unique.
         */
        const val LEGACY_SHARE_ID = "leo"

        /**
         * Réglages d'usine : les 4 champs sans défaut du constructeur, le reste
         * suit les défauts déclarés plus haut (une seule source de vérité).
         */
        val Default = Settings(
            llmBaseUrl = DEFAULT_LLM_BASE_URL,
            llmApiKey = "",
            llmModel = DEFAULT_LLM_MODEL,
            samBaseUrl = "",
        )
    }

    /**
     * Intro attendue à chaque ouverture complète de l'app (relance après fermeture,
     * pas au simple retour d'arrière-plan) : seule la coupure par la voix
     * (« Disable iSee intro », [introEnabled] = false) la fait taire définitivement.
     */
    val introPending: Boolean get() = introEnabled

    /** Plafond d'objets réellement appliqué (le réglage peut être hors bornes). */
    val objectsOnScreen: Int get() = ObjectLimits.clamp(maxObjects)

    /**
     * Vrai si un endpoint SAM est disponible : une URL manuelle, ou le preset de la
     * région choisie ([resolveSamEndpoint] — donc toujours vrai, sauf preset vide).
     */
    val segmentationEnabled: Boolean get() = resolveSamEndpoint().isNotBlank()

    /** Vrai si la narration du Direct peut partir (clé dédiée renseignée). */
    val liveLlmConfigured: Boolean get() = liveLlmApiKey.isNotBlank()

    /** Vrai si l'app peut appeler le LLM. */
    val llmConfigured: Boolean get() = llmBaseUrl.isNotBlank() && llmApiKey.isNotBlank()

    /** Vrai si l'app peut appeler ElevenLabs (clé + voix renseignées). */
    val ttsConfigured: Boolean get() = ttsApiKey.isNotBlank() && ttsVoiceId.isNotBlank()

    /** Vrai si une réponse du chat doit être lue dès qu'elle arrive. */
    val ttsAutoRead: Boolean get() = ttsAutoplay && ttsConfigured

    /** Vrai si les positions détectées doivent être dites à voix haute. */
    val announcePositions: Boolean get() = ttsAnnouncePositions && ttsConfigured

    /** Vrai si la page caméra iSee doit guider la marche au lieu de décrire les positions. */
    val guideStraight: Boolean get() = ttsGuidanceStraight && ttsConfigured

    /** Vrai si la dictée Meta est utilisable (endpoint + clé renseignés). */
    val asrConfigured: Boolean get() = asrBaseUrl.isNotBlank() && asrApiKey.isNotBlank()

    /** Vrai si la dictée a un moteur : Meta, à défaut le repli ElevenLabs (clé TTS). */
    val sttConfigured: Boolean get() = asrConfigured || ttsApiKey.isNotBlank()

    /** Vrai si un champ de vision de repli a été saisi (sinon : lecture capteur). */
    val cameraFovForced: Boolean get() = cameraFovDeg > 0f

    /** Vrai si le guidage vocal Valhalla peut partir (endpoint renseigné). */
    val routeConfigured: Boolean get() = routeBaseUrl.isNotBlank()

    /**
     * Affichage **sûr** : les trois clés d'API sont masquées, jamais imprimées. Un
     * `Settings` part dans un `Log`, dans le message d'une exception, dans une trace
     * de test — une clé qui y entre une seule fois reste lisible dans logcat autant
     * que le téléphone vit. Le reste des champs reste visible (urls, modèles, drapeaux) :
     * c'est ce qui sert au diagnostic.
     */
    override fun toString(): String = "Settings(" +
        "llmBaseUrl=$llmBaseUrl, llmApiKey=${maskSecret(llmApiKey)}, llmModel=$llmModel, " +
        "samBaseUrl=$samBaseUrl, samRegion=$samRegion, speechLanguage=$speechLanguage, " +
        "ttsApiKey=${maskSecret(ttsApiKey)}, ttsVoiceId=$ttsVoiceId, ttsSpeed=$ttsSpeed, " +
        "ttsAutoplay=$ttsAutoplay, ttsAnnouncePositions=$ttsAnnouncePositions, " +
        "ttsGuidanceStraight=$ttsGuidanceStraight, " +
        "asrBaseUrl=$asrBaseUrl, asrApiKey=${maskSecret(asrApiKey)}, asrModel=$asrModel, " +
        "introEnabled=$introEnabled, introPlayed=$introPlayed, " +
        "distanceOverlayEnabled=$distanceOverlayEnabled, liveSamEnabled=$liveSamEnabled, " +
        "boxPredictionEnabled=$boxPredictionEnabled, " +
        "localOverlayEnabled=$localOverlayEnabled, " +
        "debugRouteMapEnabled=$debugRouteMapEnabled, " +
        "navigationGuidanceEnabled=$navigationGuidanceEnabled, " +
        "cameraFovDeg=$cameraFovDeg, caregiverRole=$caregiverRole, " +
        "caregiverInvite=$caregiverInvite, maxObjects=$maxObjects, " +
        "routeBaseUrl=$routeBaseUrl)"
}

/** Marqueur de substitution d'une secret masqué (voir [maskSecret]). */
internal const val MASKED_SECRET = "•••"

/**
 * Masque une clé d'API pour tout ce qui peut être imprimé. Une clé vide le reste
 * (le vide est une information : « rien n'a encore été saisi ») ; une clé posée
 * devient [MASKED_SECRET], sans longueur ni fraction — même les 4 derniers
 * caractères appartiennent à la clé.
 */
internal fun maskSecret(value: String): String = if (value.isEmpty()) "" else MASKED_SECRET

interface SettingsStore {
    suspend fun load(): Settings
    suspend fun save(s: Settings)
}

/** Implémentation en mémoire (tests, previews Compose). */
class MemorySettingsStore(initial: Settings = Settings.Default) : SettingsStore {
    private var current: Settings = initial

    override suspend fun load(): Settings = current

    override suspend fun save(s: Settings) {
        current = s
    }
}
