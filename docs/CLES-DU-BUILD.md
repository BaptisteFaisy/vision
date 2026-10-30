# Clés embarquées dans l'APK

Objectif (demande du 2026-10-26) : **installer l'APK suffit**. Personne ne doit ressaisir
de clé pour que le chat réponde et que la voix parle ; les clés sont dans le binaire.

## D'où elles viennent

| Réglage | `local.properties` (gitignoré) | `BuildConfig` |
| --- | --- | --- |
| Base URL LLM | `llm.base.url` | `LLM_BASE_URL` |
| Clé LLM | `llm.api.key` | `LLM_API_KEY` |
| Modèle LLM | `llm.model` | `LLM_MODEL` |
| Clé narration Direct | `llm.live.api.key` | `LIVE_LLM_API_KEY` |
| Clé ElevenLabs | `tts.api.key` | `TTS_API_KEY` |
| Voix ElevenLabs | `tts.voice.id` | `TTS_VOICE_ID` |
| Clé dictée Meta | `asr.api.key` | `ASR_API_KEY` |
| Endpoint SAM | `sam.base.url`, `sam.us.base.url` | `SAM_BASE_URL`, `SAM_US_BASE_URL` |
| URL routeur Valhalla | `route.base.url` (défaut : backend Modal CPU) | `ROUTE_BASE_URL` |
| Clé Google Maps | `google.maps.api.key` | `GOOGLE_MAPS_API_KEY` |
| Endpoint tap SAM 3.1 | `sam31.endpoint` (vide = tap désactivé) | `SAM31_ENDPOINT` |
| Surimpression locale (défaut build) | `live.local.overlay` | `LIVE_LOCAL_OVERLAY` |
| Dev lunettes (manifest) | `mwdat.application.id`, `mwdat.client.token` (défaut `"0"`) | — (`manifestPlaceholders`) |

`app/build.gradle.kts` lit le fichier, `app/SettingsSeed.kt` remplit les champs vides au
démarrage (`seededFromBuild`, utilisé par iSee, le Direct et l'écran photo). L'intro et
les endpoints SAM par région ont déjà leur défaut en dur : sans aucune clé, l'app
s'ouvre, parle et segmente ; seuls le LLM et la voix demandent une clé.

## Priorité

1. Une valeur **enregistrée dans Réglages** sur ce téléphone gagne toujours.
2. Sinon, la valeur du build remplit le champ vide.

Donc : une installation neuve hérite de tout ; quelqu'un qui a déjà saisi sa propre clé
la garde. Pour repartir du build, vider le champ et enregistrer.

Exception : une base inutilisable (vide ou sans `https://`, LLM comme SAM, route
ou dictée) n'est pas un choix — le build (à défaut le défaut du code, le preset
de région, Maps ou Scribe selon le champ) reprend la main tout seul.

## Modèles épinglés (démo)

| Rôle | Base | Modèle |
| --- | --- | --- |
| Questions / description | `https://inference.baseten.co/v1` | `meta-models/muse-spark-open-1026` (Meta) |
| Narration du Direct | `https://inference.baseten.co/v1` | `meta-models/muse-spark-open-1026` (Meta) |

Questions et narration parlent au même fournisseur (Baseten) et au même modèle Meta :
le raisonnement interne est coupé côté client (`enable_thinking=false`), donc le budget
de 200 tokens de la narration va à la phrase. Seule la clé de narration est injectable
(`llm.live.api.key`, une clé Baseten) ; base et modèle du Direct sont figés dans `Settings`.

Gardes : un `llm.model` de build différent de l'épinglé affiche un avertissement
visible dans Réglages (« Modèles effectifs ») au lieu de s'appliquer en silence ;
`migrateLegacyLlm` ne réécrit jamais un modèle déjà épinglé ; une mise à jour depuis
l'ancien défaut (InferHub + qwen) migre base, modèle **et** clé ensemble (sinon la
clé InferHub partirait chez Baseten et répondrait 401). Les Réglages montrent les
noms exacts, les bases et le statut des clés — la clé LLM n'est « chauffée »
qu'après un « Tester le LLM » qui a réellement répondu.

## APK publié par la CI

Le runner fait un checkout neuf : il n'a pas de `local.properties`, et un APK
bâti sans l'étape « Clés du build » part donc **sans clé**. Cette étape (action
composite unique `.github/actions/cles-build`) écrit ce fichier depuis les
secrets du dépôt quand ils existent (sinon elle ne fait rien) ; elle est
présente dans les jobs qui bâtissent (`ci.yml` : tests `:core` et `:app`, `ci-native.yml` :
`apk` debug+release), donc **chaque APK téléchargé depuis GitHub
embarque les sept clés secrètes** (les autres lignes — Maps, tap, overlay,
dev lunettes — viennent du `local.properties` du poste qui bâtit, ou restent
à leur défaut).

## Garde : prouvé à chaque build

`tools/check-apk-keys.py` tourne après chaque assemblage (debug puis release,
dans le job `apk`) et fait échouer le job si, les secrets existant : le
`local.properties` ne reprend pas la clé du secret, la clé est absente du dex
de l'APK, ou un ping minimal au LLM ne répond pas 200 (secret périmé ou
mauvais fournisseur — la panne vue avec des secrets restés sur InferHub alors
que le produit parlait à Baseten). Sans secret, la garde reste inactive et
l'APK sort sans clé, comme avant. Ses tests (`tools/test_check_apk_keys.py`,
faux APK + faux LLM local, aucun réseau) tournent dans le job `ci`.

Secrets à créer — Settings → Secrets and variables → Actions → *New repository secret* :
`LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_LIVE_API_KEY`, `TTS_API_KEY`,
`TTS_VOICE_ID` (facultatif), `ASR_API_KEY` (facultatif). Aucune valeur n'est jamais
affichée par la CI.

## Avertissement

Une clé embarquée est **extractible** de l'APK par quiconque le possède : ne distribuer
l'APK qu'aux personnes à qui on accepte de prêter ces clés, et ne jamais le publier
(Play Store, dépôt public, lien ouvert). Le dépôt, lui, reste sans secret : garde CI
`git grep` + `local.properties` jamais versionné.
