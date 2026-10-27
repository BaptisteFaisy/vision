# Mode US — la GPU SAM 3.1 aux États-Unis

Réglages → section **« GPU SAM 3.1 — région »** (`GpuRegionSettings.kt:24`) : un bouton **pleine
largeur** par région, la région active en couleur pleine (`GpuRegionSettings.kt:51-63`). Libellés
exacts, lus dans l'enum : « GPU en Europe (eu) » et « GPU aux États-Unis (us) »
(`SamRegion.kt:19`, `:24`). Le choix est persisté et s'applique au Direct, à la sonde iSee et à
l'analyse photo (tableau plus bas).

## Ce que fait le réglage (et ce qu'il ne fait pas)

Il **élit l'endpoint** appelé, il **n'allume aucune carte** (`SamRegion.kt:6-8`).

| Région | App Modal | Conteneur | Preset d'endpoint (`SamRegion.kt`) |
|---|---|---|---|
| EU (défaut) | `sam3-eu-a100` | `region=eu`, `routing_region=eu-west` | `https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run` (ligne 21) |
| US | `sam3-us-a100` | `region=us`, `routing_region=us-east` | `https://baptiste-faisy--sam3-us-a100-sam3live-web.us-east.modal.run` (ligne 26) |

## Ordre de résolution — corrigé, et revérifié dans cet arbre

Le code (`core/settings/SamRegion.kt:50-55`) fait, dans cet ordre :

1. **Base URL saisie à la main**, si elle n'est pas un preset (`:51-52`) ;
2. **URL injectée au build pour la région choisie** — `sam.base.url` pour EU,
   `sam.us.base.url` pour US (`:53`, injectées en `BuildConfig.SAM_BASE_URL` /
   `SAM_US_BASE_URL`, `app/build.gradle.kts:70`, `:72`) ;
3. **preset de la région** — seulement si l'URL du build est vide (`:54`,
   `injected.trim().ifBlank { samRegion.presetBaseUrl }`).

⚠️ **Ce que ce document disait avant était faux**, et le commentaire du code l'est aussi : le KDoc
de `resolveSamEndpoint` (`SamRegion.kt:46-49`) comme celui de `Settings.samEndpoint()`
(`SamEndpoint.kt:7-8`) annoncent « manuel > preset de la région > URL du build ». L'implémentation
donne **la priorité à l'URL du build sur le preset**. Conséquences concrètes :

- `sam.base.url` renseigné ⇒ en région **EU** c'est cette URL qui répond, jamais le preset ;
  idem `sam.us.base.url` en région **US**. Le preset ne sert que de repli quand la clé de la
  région visée est vide.
- Si les deux clés portent **la même URL** (cas d'un ancien endpoint commun), la bascule EU/US
  ne change plus rien à l'appel — et ça ne se voit pas dans les Réglages, puisque « Endpoint
  actif » affiche justement cette URL.
- `dropPresetUrl` n'y change rien : il nettoie la valeur **persistée** (`samBaseUrl`), pas les
  `BuildConfig`.

Pour que les boutons de région pilotent réellement l'appel, ces deux clés doivent donc rester
**vides**. (Le KDoc est à corriger ; il n'est pas modifié ici : cette mission n'écrit que dans
les fiches de `docs/`, aucun fichier `.kt`.)

## Le piège `dropPresetUrl`

Une URL qui n'est **que** le preset d'une région (persistée par une version antérieure) ne doit
pas compter comme une saisie manuelle : au chargement, `seededFromBuild` l'efface
(`SettingsSeed.kt:46-47`, `dropPresetUrl` : `if (SamRegion.isPreset(samBaseUrl)) copy(samBaseUrl =
"")`). Sans ça, une installation existante resterait figée sur l'EU et la bascule US semblerait
sans effet. La comparaison ignore les espaces et la barre finale (`SamRegion.isPreset`,
`SamRegion.kt:39-42`).

Le même nettoyage est refait **au moment du clic** : `pickRegion()` efface une `samBaseUrl` qui
n'est qu'un preset et conserve une URL tapée à la main (`GpuRegionSettings.kt:70-73`). Une saisie
manuelle gagne donc toujours — c'est voulu, et c'est le seul moyen de viser un autre profil Modal.

## L'UI des Réglages, telle qu'elle est

- Section « GPU SAM 3.1 — région », placée avec le reste des réglages SAM
  (`SettingsScreen.kt:75`) ; sous les boutons, le rappel : une seule GPU à la fois, allumer la
  bonne app (`GpuRegionSettings.kt:30-36`).
- **« Endpoint actif : … »** sous les boutons, calculé avec la vraie résolution
  (`GpuRegionSettings.kt:37-41`).
- Champ **« Base URL SAM 3.1 (vide = région choisie ci-dessous) »** (`SettingsScreen.kt:123-124`).
- **« Tester SAM »** : actif si `samConfigured()` (`SettingsScreen.kt:140`), affiche la région
  testée — « Test SAM en cours… (EU|US) » (`AppStatusText.kt:31`) — puis « SAM OK — endpoint
  joignable. » / « SAM KO — … » (`AppStatusText.kt:33-36`, `AppDiagnostics.kt:36-37`).
- Persistance : `sam_region` dans le store chiffré (`EncryptedSettingsStore.kt:37`, `:71`, `:95`) et
  `samRegion` dans le store fichier (`PrivateFileSettingsStore.kt:96`, `:117`, `:145`) ; valeur
  inconnue ou absente ⇒ **EU** (`SamRegion.fromName`, `SamRegion.kt:31-32`).

## Où la résolution s'applique… et où elle ne s'applique pas

| Chemin | Preuve |
|---|---|
| Direct (vidéo en continu) : `liveBaseUrl()` = `samEndpoint()` | `LiveState.kt:160`, `LiveViewModel.kt:151` |
| Sonde SAM d'iSee (page caméra, WebSocket) | `ProbeWiring.kt:24` |
| Analyse photo | `AppViewModel.kt:122` (`HttpSamClient(settings.samEndpoint())`) |
| « Tester SAM » | `AppDiagnostics.kt:36-37`, `AppViewModel.kt:260` |
| `segmentationEnabled` = `resolveSamEndpoint().isNotBlank()` | `Settings.kt:119` — appelé **sans** argument de build, donc c'est le preset qui décide ici |
| ❌ **Mode tap** : `BuildConfig.SAM31_ENDPOINT` en direct, aucune région | `TapConfig.kt:35`, `TapCaptureScreen.kt:171` (voir `docs/MODE-TAP.md`) |
| ✅ **Réveil à l'ouverture** : `warmGpu()` = `GET /health` silencieux, 3 essais, arrêt immédiat si backend stoppé | `MainActivity.kt:174` (`onResume`) → `AppViewModel.kt:240` → `AppDiagnostics.kt:99` ; la carte endormie se réveille, la carte chaude répond quasi gratis. Un trou de réseau n'éteint plus le réveil (2e et 3e essais) ; un backend stoppé (`BackendStopped`) ne boucle pas dans le vide — il faut redéployer (`deploy-live-fast.sh`) ; passer en fond périme les essais en cours (`warmGeneration`, pas de rallumage derrière `releaseGpu`). La **première frame** du Direct ou de la sonde iSee reste un réveil de secours. En sens inverse, quitter le plein écran éteint la carte : `onPause` (`MainActivity.kt:164`), split-screen (`MainActivity.kt:185-187`), fermeture (`MainActivity.kt:198`) |
| ❌ **Banc d'essai lunettes** (écran `Route.Bench` d'iSee, plus d'app séparée) : `Settings(...)` construit depuis `BuildConfig`, SAM = `SAM31_ENDPOINT` en direct, aucune région | `BenchScreen.kt:309-322` (`samBaseUrl = BuildConfig.SAM31_ENDPOINT`, `:315`) |

## Transport depuis iSee : WebSocket uniquement

Le Direct et la sonde iSee passent par **WebSocket `/live/ws`** ; le client HTTP ne sert plus
qu'à `GET /health` (une requête par session) : `LiveClientFactory.kt:3-18`,
`WsLiveClient.kt:63`, chemin `LIVE_WS_PATH = "/live/ws"` (`WsTransport.kt:35`). La pastille de
transport affiche « WebSocket » (vert) ou « WebSocket KO » (rouge), sans repli
(`LiveMessages.kt:21`, `:24`, `LiveBars.kt:84`, `LiveViewModel.kt:399`). Le mode US ne change rien à ce transport : il ne change que la base.

## Allumer la carte américaine (outil hors dépôt)

`~/bin/sam3-gpu-us` vit sur le r630, **pas dans ce dépôt** : relu le 2026-10-26 par son `--help`,
le reste (bail, watchdog) n'est pas vérifié ici. Commandes :

```bash
~/bin/sam3-gpu-us status                     # app US déployée ? conteneurs ? bail ?
~/bin/sam3-gpu-us on --reason "mode US"      # déploie si besoin + bail 30 min
~/bin/sam3-gpu-us off --reason "fini"        # extinction (app laissée déployée)
```

C'est un enrobage `SAM3_APP=sam3-us-a100 SAM3_REGION=us SAM3_ROUTING=us-east` autour de
`~/bin/sam3-gpu` : même bail extinctif, même journal, même watchdog.

- **Une seule GPU, jamais deux** : EU et US ne tournent jamais en même temps, on éteint l'une
  avant d'allumer l'autre (`sam3-gpu busy` → exit 0 = une carte tourne déjà, on la réutilise au
  lieu d'en allumer une seconde). Côté machine, `modal-one-gpu-watch` (r630, toutes les 15 s)
  ramène à une carte et arrête l'app qui revient.
- **Déploiement manuel, réveil auto** : le réglage EU/US des Réglages **n'allume rien**
  (`SamRegion.kt:6-8`), et un agent ne lance pas `sam3-gpu on` / `sam3-gpu-us on` sans demande
  explicite de l'utilisateur — le bail est de 30 min, à 2,415 $/h. En revanche, **l'app
  réveille la carte déployée à chaque ouverture** (`warmGpu`, `/health` silencieux) et
  l'éteint dès qu'elle quitte le plein écran (`onPause`, split-screen, fermeture). Si
  l'app Modal n'est pas déployée, le réveil ne rallume rien (`BackendStopped`) : il faut
  un `on` manuel, une fois.
- Coût et cold start (constat hors dépôt, **non remesuré ici**) : A100-40GB 2,415 $/h ;
  `region=us` est une région large (×1,15 ; `us-west` serait ×1,75) ; premier `on` = déploiement
  puis réveil, cold start ~50 s (poids 20,9 Go + build du predictor 22,3 s), `--warm` attendant
  que `/health` réponde.

## ⚠️ L'image déployée n'est pas reconstructible — ne pas redéployer

État **rapporté par l'outil, le 2026-10-26** (`~/bin/sam3-gpu status` → `app : sam3-eu-a100 —
état=absent`, 0 conteneur, aucun bail ; idem `sam3-gpu-us` pour l'app US) : rien dans ce dépôt ne
vérifie ce que sert réellement le backend. Ce qui est établi côté backend : le conteneur
déployé sur `sam3-eu-a100` tournait une image `sam3.1-fast` (avec `batch_prompts`, edge appliqué
518) **dont le source n'existe nulle part**. Un redéploiement
depuis le dépôt écraserait cette image : perte d'environ **235 ms de temps serveur**, plus le
WebSocket. Donc : **ne pas redéployer l'app EU**. Et toujours une seule GPU allumée, toutes apps
et tous agents confondus.

## Vérifier

```bash
~/bin/sam3-gpu-us status     # lecture seule, ne réveille rien (exécuté le 2026-10-26 : app absente)
curl -s https://baptiste-faisy--sam3-us-a100-sam3live-web.us-east.modal.run/health
# → {"ok":true,"gpu":"NVIDIA A100-…","region":"us",…}
# ⚠️ ce curl RÉVEILLE la carte (facturation) : ne pas le lancer sans demande explicite ; non lancé ici.
```

Dans l'app : « Endpoint actif : … » sous les deux boutons, et « Tester SAM » avec la région
affichée (voir plus haut).

## Latence : les chiffres de référence ne sont pas ici

Les mesures de latence du mode EU **et** du mode US ne sont **pas reprises** dans ce document :
elles viennent du dépôt **backend**, `docs/LATENCE.md` (absent de ce dépôt : `ls docs/LATENCE.md`
→ absent) — il est
cité dans `docs/TESTING.md:69`, qui rappelle les ordres de grandeur : serveur 82-95 ms, A/R PC
p50 240,1 ms, téléphone 600-710 ms dont ~80 ms non comptés. Ce sont des chiffres **du backend**,
pas des mesures faites ici, et ils n'ont pas été refaits.

Ce qui reste vrai sans mesure : depuis la France, l'EU garde l'avantage du routage (Dublin) ; le
mode US sert quand l'Europe est à sec ou indisponible, **pas** pour gagner en latence.

## Pièges

- **`routing_region` ne se change pas** après le premier déploiement d'une fonction : d'où une
  **app distincte** (`sam3-us-a100`) plutôt qu'un redéploiement de `sam3-eu-a100` avec d'autres
  variables.
- App US non déployée ou arrêtée ⇒ le proxy Modal répond `404 invalid function call` sur toutes
  les routes ; l'app le traduit en `BackendStopped` (« GPU SAM éteint — demande à b de l'allumer »)
  — `core/…/net/Errors.kt:35-37`, mapping `Http.kt:198-204`. Un 404 sans ce corps tombe dans
  `ApiError.Http(404, …)` (`Http.kt:203`).
- Le preset suppose le profil Modal `baptiste-faisy`. Un autre profil ⇒ mettre l'URL dans
  « Base URL SAM 3.1 » (elle gagne sur tout le reste).
- Une URL du build (`sam.base.url` / `sam.us.base.url`) gagne sur le preset : voir le premier
  piège de ce document.
