# Stabilité d'iSee — ne plus perdre la connexion, ne plus mourir

Relevé et correctifs du 02/11/2026. Objet : les deux symptômes rapportés par l'utilisateur,
« l'app crash parfois » et « elle perd la connexion ». Une seule preuve de crash existait dans
le journal du téléphone (`dumpsys dropbox`, 48 h) ; le reste a été établi par lecture de code.

## 1. Le crash constaté

```
2026-11-01 17:00:54 data_app_crash  dev.sam3vision.app
Crash-Tag: window_resize
android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException:
  Context.startForegroundService() did not then call Service.startForeground():
  ServiceRecord{… dev.sam3vision.app/.voicenav.GuidanceTrackingService}
```

Deux causes, corrigées :

1. **Rotations.** Le service de guidage était démarré depuis `onPause`, donc à chaque
   changement de configuration ; l'obligation des 5 s courait pendant que le thread principal
   reconstruisait l'écran. `MainActivity` ne démarre plus le fond pendant
   `isChangingConfigurations`, et `GuidanceLifecycle` le refuse aussi (défense en profondeur).
2. **Le service lui-même.** Le premier plan n'était pris qu'à la fin d'`onStartCommand`, la
   branche `ACTION_STOP` sortait **sans** `startForeground`, `createChannel()` n'était pas
   gardé et rien n'empêchait un second `startForeground` sur une notification déjà posée.
   Désormais : premier plan tenu dès `onCreate`, séquence dans `GuidanceServiceRunner`
   (gardée, idempotente, sortie propre si le système refuse), canal de secours, arrêt par
   `stopService`.

## 2. Ce qui pouvait tuer le process sans laisser de trace

Le journal ne montre **aucun** ANR ni tombstone : les autres causes sont latentes, pas
constatées. Elles sont quand même fermées, parce qu'une seule suffit à perdre l'app en pleine rue.

| Cause | Correctif |
|---|---|
| 119 des 125 `launch` n'avaient aucune frontière d'exception ; 3 `Thread` nus exécutaient du code qui lève | `CrashGuard` : handler global **et** `CoroutineExceptionHandler`, journal borné (fichier, rotation) — toute exception laisse une trace avant de tuer |
| `BusSpeaker.say()` lançait sa coroutine sans `try/catch` : une exception de réglages/pack tuait l'app depuis la parole | garde + libération du bus dans tous les cas |
| Caméra : `.get()` du provider et la « porte frame inutile » hors de tout `try`, exception d'analyseur non capturée, `release()` qui ne délie pas | `CameraBindPlan` (décisions pures) + `IseeCameraBinder` (exécution gardée) : plus d'exception qui sort, re-liaison bornée, échec affiché |
| `GlassesController` : scope sans handler, le SDK (preview) lève | handler ajouté |
| Démarrage : coroutine iSee sans garde, `SamApp.onCreate` sans filet | `SamApp` pose le filet avant tout, et chaque étape de démarrage est gardée |

## 3. La perte de connexion

Constat de départ : **aucune** occurrence de `ConnectivityManager` dans l'arbre, et
`ACCESS_NETWORK_STATE` n'était pas déclarée. L'app ne savait donc pas qu'elle changeait de
réseau : un passage Wi-Fi → 4G laissait le WebSocket `/live/ws` accroché à l'interface morte,
découvert seulement au délai expiré de la frame suivante (10 s, **60 s** avant la première
frame réussie).

Ce qui change :

- **État réseau poussé au client** (`app/net/AndroidNetworkState.kt` + `NetMonitor`) :
  `registerDefaultNetworkCallback`, deux champs volatils, zéro réveil périodique. Hors ligne
  connu ⇒ la frame échoue **localement** (ni DNS, ni TCP, ni handshake) ; changement
  d'interface ⇒ le socket de l'ancienne est jeté et la frame courante repart sur la nouvelle.
- **État de lien observable** (`core/net/WsLink.kt`) : `Closed / Connecting / Open / Degraded`,
  backoff exponentiel plafonné à 8 s avec jitter, compteur de tentatives, reprise immédiate au
  retour du réseau. Plus de rafale de handshakes sur une radio cellulaire.
- **Watchdog de silence** : plus aucune réponse ni preuve de vie au-delà du budget ⇒ le socket
  est déclaré mort, les frames en vol sont réveillées en échec, la reprise est armée.
- **Session froide payée une seule fois** (`ProbeLink`) : le budget de 60 s n'est plus réarmé
  par un `configure()`, un réglage ou une bascule de mode ; la sonde dit « pas de réponse
  depuis N s » au lieu de « GPU en cours de réveil », et le log d'échec est limité à une
  ligne par seconde.

## 4. La panne se dit maintenant

Avant, une coupure après la première frame était **muette** : masques figés à l'écran,
aucune phrase, aucune ligne. `OutageWatch` (pur, `:core`) escalade une seule fois par palier
et par épisode :

| Temps de panne | Écran | Voix (clip déjà embarqué) |
|---|---|---|
| 6 s | `No connection — retrying…` | « Guidance unavailable. » (`gps_unavailable`) |
| 15 s | `Vision off — GPS guidance only` / `Vision off — no guidance` | — |
| 60 s | `Offline — stop and try again` | « Guidance stopped. » (`nav_stopped`) |
| retour | `Back online` (3 s) | — |

Anti-bavardage : un seul mot par palier, premier mot tu si l'épisode précédent vient de parler
(30 s), et seuils décalés au démarrage à froid (60 s / 120 s) pour ne pas crier pendant le
réveil normal du conteneur. La ligne s'affiche **aussi en guidage**, seul mode où l'on marche.

## 5. Serveur (`server/`)

Une inférence abandonnée (client parti, annulation, timeout) ne garde plus le verrou GPU ni le
slot : libération dans tous les chemins. `/health` expose l'état réel du WS, une réponse trop
grosse dégrade (`max_objects`) au lieu de fermer en 1009. Le contrat de reprise est écrit dans
`docs/LIVE-SPEC.md` § 8.

## 6. Vérifier sur le téléphone

```bash
# aucune ligne fatale depuis l'installation
adb logcat -b crash -d | grep -A5 sam3vision
# état du lien pendant une séance
adb logcat -s sam3vision:* | grep -E "live KO|bus-jet|extinction GPU"
```

Recette rue : marcher avec iSee, couper le Wi-Fi (ou passer en mode avion) 20 s, le remettre.
Attendu : la ligne `No connection — retrying…` apparaît sous 6 s, la phrase
« Guidance unavailable. » une seule fois, puis `Back online` et la voix d'obstacles revient
sans toucher à l'écran.

## 7. Ce qui reste ouvert

- **Recette téléphone complète** (l'appareil s'est déconnecté pendant la session) : la
  vérification faite ici est la recette émulateur (`scripts/isee-sim.sh`, 0 crash, caméra
  reliée, 30 s de flux).
- Deux tests hérités sont sensibles au temps et échouent par intermittence sous charge
  (`GlassesFrameCollectorTest`, `AnnouncementPlayerTest`) : antérieur à ce lot, à rendre
  déterministes.
- Le mode `PAUSED` des lunettes Meta (session gelée en « Session starting… ») reste à traiter :
  hors du symptôme rapporté, mais c'est la même classe de panne silencieuse.
