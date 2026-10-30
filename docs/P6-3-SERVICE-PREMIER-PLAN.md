# P6-3 — Note de décision : type de service de premier plan

> Statut : **instruction, pas une décision produit**. P6 reste **backlog** :
> aucun code, aucun service créé par cette note.
> Rédigée le 2026-10-30 depuis `origin/master` (`892b9fb`), par lecture du
> code et du manifeste. Le choix proposé doit être **démontré sur le Redmi**
> avant P6-3 (protocole §6).

Parent : `docs/LUNETTES-META-CADRAGE.md` (§2 point 3, §5 point ouvert n° 2).

## 1. Ce que le service P6 ferait, et ne ferait pas

En poche / écran éteint, guidage obstacles depuis les lunettes :

- **Fait** : maintenir la session DAT (flux Bluetooth Classic 15 i/s,
  `GlassesCamera.kt:89-96`), décoder le H.265 sans surface (P6-1),
  alimenter `LiveProbe` (P6-2), envoyer `/live` au réseau, parler
  (voix vers les lunettes en session — `GlassesVoiceRoute` — sinon
  haut-parleur).
- **Ne fait pas** : la caméra Android (jamais `CameraService`), le GPS
  (c'est `GuidanceTrackingService`, type `location`, déjà en place),
  l'enregistrement micro téléphonique (c'est `LockedMicService`, type
  `microphone`, déjà en place).

Données de cadrage : `targetSdk = 36`, `minSdk = 29`
(`app/build.gradle.kts:83-88`) ; téléphone de recette sous Android 15
(API 35 — `docs/LUNETTES-LANCEMENT.md:11`). Les règles Android 14+
s'appliquent donc de plein droit : type déclaré au manifeste **et**
demandé à l'exécution, permission `FOREGROUND_SERVICE_<TYPE>` par type,
démarrage depuis l'arrière-plan interdit (Android 12+).

## 2. Options

| Type FGS | Couvre (doc Android) | Adéquation P6 | Permission à ajouter |
|---|---|---|---|
| `connectedDevice` | interaction continue avec un appareil externe (Bluetooth, USB, NFC) | **forte** : le service existe pour la session DAT, qui est un flux BT Classic continu (vidéo + PCM) | `FOREGROUND_SERVICE_CONNECTED_DEVICE` (+ `BLUETOOTH_CONNECT`, déjà déclarée) |
| `microphone` | capture audio via le micro | faible : le micro DAT n'est pas un `AudioRecord`, il ride le flux caméra (`GlassesAudioTap`) ; la dictée poche a déjà son service (`LockedMicService`) | déjà déclarée |
| `camera` | usage de la caméra Android | **nulle, mensongère** : le flux DAT n'est pas `CameraService` ; déclarer `camera` sans caméra, c'est un refus Play en puissance | `FOREGROUND_SERVICE_CAMERA` |
| `dataSync` | transfert réseau initié par l'utilisateur | moyenne : le `/live` est du réseau, mais le cœur du service est le flux BT + le décodage, pas un transfert ; Android 15 borne les `dataSync` (fenêtre de 6 h) | `FOREGROUND_SERVICE_DATA_SYNC` |
| `mediaPlayback` | lecture multimédia | faible : des annonces vocales ne sont pas un lecteur multimédia (pas de `MediaSession`) | `FOREGROUND_SERVICE_MEDIA_PLAYBACK` |
| `location` | position | nulle ici : aucun GPS dans le P6 ; le guidage route a déjà son service | déjà déclarée |
| `specialUse` | tout le reste, avec motif déclaré au manifeste | repli honnête si le système ou Play refuse `connectedDevice` | `FOREGROUND_SERVICE_SPECIAL_USE` + motif |

## 3. Recommandation : `connectedDevice` seul

Le service P6 est un compagnon d'objet connecté : il maintient une
session Bluetooth continue et traite son flux. C'est exactement le cas
documenté de `connectedDevice`, et le précédent de la maison pour les
compagnons d'objets Bluetooth. Un seul type, pas de cumul :

- **pas de `microphone` en plus** : la capture micro poche reste dans
  `LockedMicService` (arbitrage `MicBus` en P6-4) ; cumuler un type
  « au cas où » affaiblit le dossier Play sans rien apporter ;
- **pas de `camera`** : ce serait faux, et le faux se paie au contrôle ;
- **`specialUse` en repli documenté** : si l'essai appareil (§6) montre un
  refus système du type `connectedDevice`, on bascule sur `specialUse`
  avec motif « guidage vocal d'obstacles depuis lunettes Bluetooth ».

Modèle d'armement : celui de `LockedMicService`, qui a fait ses preuves
sur ce téléphone (`docs/MICRO-VERROUILLE.md`) — armé depuis l'app
**visible** (`LockedMicBridge.arm` dans `MainActivity.onResume`), jamais
depuis l'arrière-plan ; réveil partiel pendant le travail seulement ;
notification permanente avec action d'arrêt (précédent
`GuidanceNotification`).

## 4. Ce qui bloque la tranche P6-3

1. **P6-0, scénario S2** (`docs/P6-0-AUTONOMIE.md`) : la session DAT
   survit-elle au verrouillage ? Le réseau `/live` survit-il (Doze) ?
   Sans ces constats, le service est dessiné à l'aveugle.
2. **Preuve appareil du type** : protocole §6 exécuté sur le Redmi
   (Android 15 + HyperOS, qui tue le processus dès que l'app n'est plus
   visible — constaté dans `LockedMicService.kt:217-225`).
3. **Propriété de la session DAT** (décision P6-2, amont) : le
   `GlassesController` est lié à l'activité (`MainActivity.onCreate` /
   `onDestroy`) ; le service doit posséder la session ou la relayer.
   Le type FGS ne tranche pas ce point, mais le service ne se code pas
   sans lui.
4. **Politique batterie/Doze** (décision produit) : un service de premier
   plan n'exempte **pas** des restrictions réseau de Doze. En marche,
   le mouvement réveille Doze (pas de Doze profond en marchant — à
   confirmer en S2) ; à l'arrêt prolongé, le `/live` peut caller aux
   fenêtres de maintenance. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   est exclu (politique Play) : le choix restant est un guidage
   utilisateur vers les réglages (autostart HyperOS, économiseur),
   comme pour les services existants.
5. **Contenu de la notification** (produit) : texte permanent, action
   d'arrêt, comportement au tap — sur le modèle `GuidanceNotification`.

## 5. Critères de décision (le type est tranché quand)

- [ ] `startForeground` avec `connectedDevice` accepté sur le Redmi,
      service listé avec le bon type (`dumpsys activity services`,
      champ `foregroundServiceType`) — cf. §6.
- [ ] 30 min écran verrouillé, session DAT ouverte : pas de
      `SecurityException`, pas de mise à mort, `/live` vivant (S2).
- [ ] Reprise après déverrouillage sans redémarrage de l'app (< 30 s,
      seuil G4 du P6-0).
- [ ] Notification permanente affichée, action d'arrêt fonctionnelle.
- [ ] Réglages HyperOS (autostart, batterie) documentés dans le
      protocole de recette, pas dans la tête d'un seul testeur.
- [ ] Si refus système : essai `specialUse` avec motif, même grille.

## 6. Protocole d'essai du type (à exécuter, pas à coder)

Prérequis : build du HEAD recetté (règle ISEE-TEST-MASTER-SEUL),
téléphone `2c6852ac` via le verrou `~/bin/phone`, jamais d'`adb` nu.

1. Installer, ouvrir iSee (service armé depuis le visible).
2. Ouvrir la session DAT, verrouiller (power), attendre 30 min.
3. Relevés : `dumpsys activity services | grep -A5 iSee` (type du
   service), `dumpsys battery` (%, température), `logcat -s GlassesChain`
   (flux vivant ?), `dumpsys power` (`mWakefulness`).
4. Déverrouiller : phase affichée, délai de reprise (chronomètre).
5. Tout écart part en **issue GitHub**, pas en commentaire
   (`docs/RECETTE-RUE.md:108-111`).

## 7. Non tranché par cette note

La propriété de la session (P6-2), l'arbitrage micro (P6-4, `MicBus`),
le repli poche (`P6-5-REPLI-POCHE.md`), le budget chiffré (P6-0 §8).
