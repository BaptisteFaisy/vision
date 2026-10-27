# Module de nettoyage — purge horaire des captures de l'app

Purge **silencieuse** (aucune boîte de dialogue, aucun son, aucune vibration) de toutes les
images et vidéos créées par l'app, **toutes les heures**, et rien d'autre.

État vérifié le **2026-10-26** sur cet arbre : 12 fichiers dans
`app/src/main/java/dev/sam3vision/app/cleanup/` (599 lignes au total, `wc -l`, aucun à ≥ 500 lignes).
Les lignes citées sont celles de cet arbre ; les verdicts d'exécution des tests instrumentés sont
marqués **non mesurés ici** (aucun appareil branché, aucun Gradle lancé sur cette machine partagée).

## Périmètre : le stockage privé de l'app, uniquement

`CapturePaths` (`Android/data/<package>/…`) est le seul endroit qui décide où l'app écrit :

- `getExternalFilesDir(DIRECTORY_PICTURES)` — photos et images importées de la galerie ;
- `getExternalFilesDir(DIRECTORY_MOVIES)` — vidéos du mode tap ;
- `filesDir/captures` — repli si le stockage externe privé est absent.

Chaque dossier porte un `.nomedia`, posé une seule fois (`CapturePaths.kt:41-45`). Le stockage
partagé, les répertoires d'autres apps et les fichiers média d'autres applications ne sont
**jamais** ni lus ni supprimés : le job ne parcourt que `CapturePaths.privateRoots(context)`
(`CapturePaths.kt:31`), récursivement, **sans suivre les liens symboliques** —
`java.nio.file.Files.isSymbolicLink` (`CaptureScanner.kt:80`) : un lien vers `/sdcard/…` ne
devient pas une porte de sortie.

Preuves attendues (`app/src/androidTest/…/cleanup/CaptureCleanupTest.kt`) : **6 méthodes
`@Test` instrumentées** — capture de l'app supprimée, fichier déposé hors périmètre intact,
dé-sync MediaStore gérée silencieusement, capture en cours d'écriture reportée, périmètre privé
seulement, job unique sans réseau. **Exécution non mesurée ici** : ces tests demandent un
émulateur ou un téléphone (`./gradlew :app:connectedDebugAndroidTest`), et le document
précédent affirmait « 6/6 vertes » sans renvoi à une exécution récente.

## Traces MediaStore : liste blanche, jamais une plage de dates

Par défaut `CleanupConfig.ADD_TO_GALLERY = false` (`CleanupConfig.kt:32`) : les captures restent
privées, invisibles dans la galerie. Si l'app publie dans MediaStore (`MediaStoreGallery.publish`,
`MediaStoreGallery.kt:28`, Android 10+), l'`_ID` rendu est écrit **dans la table Room à la
création** — `traced_captures`, schéma exporté v1
(`app/schemas/dev.sam3vision.app.cleanup.CleanupDatabase/1.json`) :

| Colonne | Rôle |
|---|---|
| `mediaStoreId` | `_ID` renvoyé par l'insertion |
| `uri`, `relativePath` | retrouver l'entrée même si l'`_ID` est recyclé |
| `kind` | collection à interroger (`Images` ou `Video`) |
| `createdAtMs` | horodatage de la capture |

Index unique `(mediaStoreId, kind)` (`TracedCapture.kt:22`). À la purge
(`RoomTracedCaptureStore.delete`, `TracedCaptureStore.kt:40`), la sélection est **toujours**
`_ID = ?` plus, sur Android 10+, `OWNER_PACKAGE_NAME = ?` du paquet (`TracedCaptureStore.kt:71-77`) :
jamais de `MediaStore.Files` filtré par `date < X`, qui emporterait les photos de l'appareil
photo ou de WhatsApp. Un `_ID`/URL invalide (entrée déjà partie) est avalé comme « disparu »
(`TracedCaptureStore.kt:47`).

Idempotence : l'entrée a disparu (suppression manuelle, MediaStore dé-synchronisé) → la trace est
oubliée sans erreur (`dao.deleteById`, `TracedCaptureStore.kt:61`) ; le fichier déjà absent est
compté comme supprimé.

## Planification

`CleanupScheduler.schedule` appelé dans `SamApp.onCreate` (`SamApp.kt:14` ; classe `Application`
déclarée au manifeste, `AndroidManifest.xml:23`) :

- `PeriodicWorkRequestBuilder<CaptureCleanupWorker>(CleanupConfig.intervalMinutes(periodHours), MINUTES)`
  (`CleanupScheduler.kt:19-22`) ;
- **l'intervalle est borné au plancher WorkManager de 15 minutes**
  (`CleanupConfig.kt:40` : `(hours × 60).coerceAtLeast(15)`) — `PERIOD_HOURS = 1` donne bien
  60 minutes, mais descendre de 15 min ne sert à rien ;
- `enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, …)` — nom unique
  `samvision-capture-cleanup` (`CleanupConfig.kt:35`, `CleanupScheduler.kt:27-31`) : jamais de
  doublon après un redémarrage ;
- `Constraints.Builder().build()` : **aucune contrainte** (ni réseau, ni batterie) — la purge
  tourne hors connexion (`CleanupScheduler.kt:23`) ;
- repli d'échec : `BackoffPolicy.LINEAR`, 15 minutes, et le tag du job = `WORK_NAME`
  (`CleanupScheduler.kt:24-25`) ;
- `CaptureCleanupWorker.doWork()` renvoie `Result.success()` **dans tous les cas**
  (`CaptureCleanupWorker.kt:23`) : un échec est journalisé, repris à la passe suivante, sans
  faire grossir une file de retries ; `CleanupRunner.run` enveloppe la passe en `runCatching` et
  renvoie un bilan vide plutôt que de lever (`CleanupRunner.kt:20-21`).

Capture en cours : un fichier modifié il y a moins de `IN_PROGRESS_GRACE_SECONDS = 60` s est reporté à
la passe suivante (`CleanupConfig.kt:29`, `CaptureScanner.kt:52`).

Journal : une ligne par passe dans `filesDir/cleanup.log`, borné aux **200 dernières lignes**
(`CleanupLog.kt:16-17,25`) + logcat (`TAG = "CaptureCleanup"`, `CleanupLog.kt:15`), avec les
comptes tenus/supprimés/reportés/échoués (`CleanupReport`).

## Changer le comportement — un seul fichier

`CleanupConfig.kt` (companion, lignes 25-40) :

```kotlin
PERIOD_HOURS = 1              // périodicité : un seul endroit (plancher réel : 15 min)
DELETE_IMAGES = true          // couper les images sans couper les vidéos
DELETE_VIDEOS = true
MAX_AGE_MINUTES = 0L          // 0 = tout supprimer à chaque passe
IN_PROGRESS_GRACE_SECONDS = 60L
ADD_TO_GALLERY = false        // true = publie + trace dans Room, et purge via _ID
```

Les deux lectures possibles de « toutes les 1 h » sont déjà câblées :
- **par défaut** : chaque heure, on purge tout ce que l'app a capté (rétention ≈ 1 h) ;
- **« supprimer ce qui a plus d'une heure »** : `MAX_AGE_MINUTES = 60` — le mécanisme
  (`CaptureScanner.minAgeMs`, `CaptureScanner.kt:59`) est prévu, rien d'autre à changer.

Le texte de la bascule et des colonnes est en français ; le nom du job WorkManager est un
identifiant technique, il ne se traduit pas.

## Ce que ce module NE fait PAS — le cache iSee est ailleurs

Ne pas confondre la purge des **captures** (ce document) avec la purge du **cache multimédia**
d'iSee, qui a ses propres règles et ses propres fichiers :

- `app/media/MediaCacheStore.kt:14-18` — tout fichier lourd s'écrit dans `cacheDir/isee` :
  `user-<ts>.mp3` (micro maintenu), `reply-<ts>.mp3` (voix ElevenLabs), `env-<ts>.mp4` (vidéo
  d'environnement), chacun lié à une ligne Room (`MediaDatabase`, schéma v1) ;
- `app/media/CacheCleanerManager.kt` — trois règles : purge totale (`purgeAll`, table +
  `cacheDir.deleteRecursively()`), timer d'inactivité programmé à `onPause` et annulé à
  `onResume`, et **sécurité FIFO** après chaque écriture (au-delà d'un plafond d'octets, on
  détruit le message le plus ancien) ;
- la purge totale part de **deux endroits** : `onDestroy` (`IseeSession.onClosed`) et le
  **démarrage du ViewModel**. Un force-stop ou un balayage depuis les apps récentes ne passe pas
  par `onDestroy` : sans la purge au démarrage, les messages survivaient à une réouverture ;
- assemblage dans `app/isee/IseeViewModel.kt:44-45` (`MediaCacheStore(app, dao)` puis `CacheCleanerManager(dao, media.dir())`).

Ce cache n'est **pas** planifié par WorkManager et n'est **pas** concerné par
`CleanupConfig` : changer la périodicité horaire ne purge pas le cache, et vice versa.

## Garde-fous

Aucun `chmod`/`bash`/`rm` système, aucun `MediaStore.Files` global, aucun dialogue, aucun son :
le module ne parle qu'à `java.io.File` dans le périmètre privé et qu'aux `_ID` tracés.
Le garde-fou de taille passe (`python3 tools/check-kotlin-limits.py` : OK le 2026-10-26, 373 fichiers inspectés).
