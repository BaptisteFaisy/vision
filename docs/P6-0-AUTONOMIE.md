# P6-0 — Protocole de mesure d'autonomie (lunettes + téléphone)

> Statut : **protocole prêt à exécuter, campagne NON exécutée** (aucun
> matériel disponible à la rédaction).
> Rédigé le 2026-10-30 depuis `origin/master` (`7c11d40`).
> P6 reste **backlog** : ce protocole ne demande aucun code P6 — tous les
> scénarios tournent sur le code de master.

Parent : `docs/LUNETTES-META-CADRAGE.md` (§3 prérequis n° 2, §4 étape P6-0).

## 0. Ce que la campagne doit produire

- Des chiffres versés en doc (§7 : procès-verbal) : %/h lunettes +
  téléphone par scénario, courant moyen téléphone, températures, cadence
  utile du pont, constats de survie au verrouillage.
- Un budget P6 chiffré (§8) : cadence cible, durée max de guidage poche,
  seuils d'arrêt auto.
- Un verdict go / no-go (§6).

## 1. Setup (à réunir avant le jour J)

| Élément | Valeur attendue | Source |
|---|---|---|
| Téléphone | Redmi Note 13 Pro `2312DRA50G`, Android 15, série `2c6852ac` | `docs/LUNETTES-LANCEMENT.md:11` |
| Accès téléphone | **verrou `~/bin/phone`**, prise/libération annoncée dans `BOITE-USER.md`, **jamais d'`adb` nu** | `docs/T8-PREDICTION.md:66`, `docs/RECETTE-RUE.md:55-60` |
| Lunettes | paire `RB Meta 01TK` | `docs/META-GLASSES.md` §13 |
| App Meta AI | 295.0.0.37.164 (constat du 27/10), Developer Mode activé (5 taps sur le numéro de version), runtime DAT `1.0.0` | `docs/META-GLASSES.md` §13 |
| Build | APK debug du HEAD recetté, règle ISEE-TEST-MASTER-SEUL (`git ls-remote origin master` = SHA du build) ; **noter le SHA au PV** | `docs/RECETTE-RUE.md:55-58` |
| Clés | ElevenLabs + voix (guidage parlé), endpoint SAM (sonde) ; Valhalla **non requis** (pas de guidage route dans cette campagne) | `docs/LUNETTES-LANCEMENT.md` §3 |
| Charge | téléphone **et** lunettes à 100 % au début de chaque scénario — sinon noter le % de départ et normaliser en %/h | — |
| Réseau | Wi-Fi ou 4G/5G stable (le `/live` part au réseau) ; **noter lequel** | — |
| Ambiance | température ambiante notée (la thermique en dépend) | — |

## 2. Métriques et commandes

Toutes les commandes `adb` passent par le verrou :

```bash
~/bin/phone run --task "p6-0: <quoi>" -- adb -s 2c6852ac <commande>
```

### 2.1. Téléphone — niveau et courant

- Niveau : `adb shell dumpsys battery` → champ `level` (relevé
  début/fin → `%`/h = Δ% / Δh).
- Compteur : `adb shell dumpsys batterystats --reset` en début de
  scénario ; `adb shell dumpsys batterystats` en fin, versé aux preuves
  (`p60-<scénario>.txt`). Y lire la consommation de
  `dev.sam3vision.app` (mAh estimés) et la décharge totale.
- Courant instantané (indicatif) :
  `adb shell cat /sys/class/power_supply/battery/current_now` (µA ; le
  signe dépend du pilote — **noter le signe, ne comparer que des relevés
  du même pilote**).
- État d'écran : `adb shell dumpsys power` → `mWakefulness` (attendu
  `Dozing` écran éteint — cf. `docs/MICRO-VERROUILLE.md:80`).

### 2.2. Téléphone — température

- `adb shell dumpsys battery` → champ `temperature` (**dixièmes de °C**) :
  début, toutes les 10 min, fin.
- **Seuil d'arrêt de sécurité : 45,0 °C** : arrêter le scénario, laisser
  refroidir, le noter au PV.
- Repli si le pilote batterie est muet :
  `adb shell cat /sys/class/thermal/thermal_zone*/temp` (millidegrés).

### 2.3. Lunettes — niveau

- Pas d'`adb` sur la paire : relever le **% dans l'app Meta AI** (fiche
  de la paire) au début et à la fin de chaque scénario → %/h. Noter
  toute alerte batterie/thermique de Meta AI.
- Température paire : **pas de sonde accessible** — observation (tiédeur
  au toucher) + durée jusqu'à une éventuelle mise en pause thermique du
  flux (trou dans logcat `GlassesChain`). Marquer **NON MESURÉ** si rien
  d'observable.

### 2.4. Cadence utile et survie (logcat)

- `adb logcat -s GlassesChain` (journal de la chaîne lunettes —
  `GlassesCamera.kt`), `GlassesBridge` (1 ligne / 20 photos envoyées à
  la sonde — `GlassesProbeWiring.kt`), `GlassesVoice` (sortie audio
  réellement choisie — `docs/META-GLASSES.md` §5).
- Compter : photos envoyées à la sonde par 10 min (= **cadence utile**),
  trous (`PAUSED`, coupures), état de session au réveil (S2).

## 3. Scénarios

Ordre imposé : S0 → S1 → S2 (S3 en bonus). Recharger à 100 % entre deux
scénarios, ou normaliser. Durées **minimales** : en dessous, le %/h est
du bruit (un pas de 1 % sur 10 min vaut déjà 6 %/h).

### S0 — Veille (référence, 60 min)

But : baseline des deux batteries, sans session DAT.

1. Lunettes allumées, appairées BT au téléphone (app Meta AI), **aucune
   session DAT** (Réglages → Lunettes : `IDLE`).
2. App iSee **fermée pour de bon** (via les récents, pas home seul),
   écran éteint (power).
3. `batterystats --reset`, relevés début (% téléphone + % lunettes +
   température + heure).
4. 60 min sans toucher à rien. Ni charge, ni déplacement (vérifier a
   posteriori dans le dump qu'aucun GPS n'a tourné tout seul).
5. Relevés finaux + dump batterystats.

Attendu : chiffres de fond (téléphone en Doze typiquement < 3 %/h,
paire quasi stable). Tout le reste se lit **en écart à S0**.

### S1 — Guidage continu, écran allumé (référence haute, 30 min)

But : coût du pont actuel à plein régime — streaming 15 i/s + décodage
H.265 + affichage + sonde `/live` + voix.

1. Ouvrir iSee, Réglages → Lunettes → session (attendre `STARTED` ; le
   lien met 2 à 30 s — `docs/META-GLASSES.md` §13, `GlassesLinkGate`
   attend 30 s).
2. Vérifier le direct : la rue du Portrait montre les lunettes
   (`IseeGlassesLive`), `live` vrai (la caméra du téléphone doit alors
   se taire — `IseeVisionCard.kt`).
3. Vérifier le pont : logcat `GlassesBridge` → « N photos envoyées à la
   sonde ».
4. `batterystats --reset`, relevés début, puis 30 min de guidage
   (marcher, ou poser la paire face à une scène qui bouge — **noter
   lequel**), écran resté allumé (délai de mise en veille porté à
   30 min pour ce scénario).
5. Relevés finaux + dump. Compter les photos / 10 min (cadence utile
   mesurée).

Attendu : le **plafond** de consommation du P6 (l'écran allumé en fait
partie — le P6-3 visera moins, écran éteint).

### S2 — Verrouillage en session (le trou à mesurer, 30 min)

But : **que reste-t-il quand l'écran s'éteint ?** Aujourd'hui
`onPause` → `isee.onBackground()` → sonde arrêtée (`MainActivity.kt`) ;
la session DAT, elle, est un **fait à constater, pas à déduire**.

1. Comme S1 §1-3 (session `STARTED`, pont actif).
2. `batterystats --reset`, relevés début, puis power (écran éteint, app
   en arrière-plan — **ne pas la fermer**).
3. 30 min. Ne rien toucher.
4. Rallumer, relever immédiatement : phase affichée (`STARTED` ?
   `STARTING` ? `STOPPED` ?), logcat `GlassesChain` (le flux a-t-il
   continué ? erreurs ?), % et températures, dump batterystats.
5. Noter la reprise : le direct revient-il seul ? en combien de secondes
   ? (chronomètre, 3 essais).

Attendu : **inconnu — c'est LA mesure qui dimensionne P6-2/P6-3**. Si la
session est `STOPPED` au réveil, le P6 doit rouvrir (coût à mesurer) ;
si elle survit, le P6 n'a que la pompe headless à ajouter.

### S3 (optionnel) — Direct seul, sonde arrêtée (20 min)

But : isoler le coût de la sonde (`/live` + voix) du coût du streaming.

Comme S1, mais guidage coupé depuis l'accueil (direct affiché conservé).
Comparer le %/h à S1 : **l'écart = le prix de la sonde**.

## 4. Planning type (une demi-journée)

| Créneau | Quoi | Durée |
|---|---|---|
| S0 | veille | 60 min |
| recharge + notes | — | ~30 min |
| S1 | guidage écran allumé | 30 min |
| recharge + notes | — | ~30 min |
| S2 | verrouillage (+ 3 essais de reprise) | 45 min |
| S3 (bonus) | direct seul | 20 min |

Règle : un seul scénario à la fois, relevés notés **au moment du
relevé** (pas de mémoire).

## 5. Preuves et procès-verbal

- Tout part dans `~/preuves/lunettes/<AAAAMMJJ-HHMMSS>/` (convention
  `docs/LUNETTES-LANCEMENT.md:498`) : dumps batterystats (`p60-s0.txt`,
  …), extraits logcat horodatés, captures Meta AI (niveau paire), PV
  rempli.
- Tout écart au protocole part en **issue GitHub**, pas en commentaire
  (règle `docs/RECETTE-RUE.md:108-111`).

## 6. Seuils go / no-go (proposition P6-0 — produit tranche)

Go P6-3 (tous exigés) :

- **G1 lunettes** : streaming continu (S1) ≥ 90 min projetées (≈ 65 %/h
  paire max) — sinon le guidage poche meurt avant la fin d'un trajet.
- **G2 téléphone** : S2 (écran éteint) ≤ 20 %/h — sinon la poche vide le
  téléphone.
- **G3 thermique** : batterie téléphone < 43 °C en fin de S1 et S2 ;
  aucune pause thermique du flux lunettes.
- **G4 reprise** : après S2, direct de retour **sans redémarrage de
  l'app** (< 30 s).

No-go (un seul suffit) :

- **N1 lunettes** : < 45 min de streaming projetées.
- **N2 téléphone** : S2 > 30 %/h.
- **N3 thermique** : ≥ 45 °C ou pause thermique constatée.
- **N4 session** : DAT systématiquement `STOPPED` au verrouillage **et**
  non rouvrable sans intervention (→ P6-2 à reconcevoir, pas seulement
  à coder).

Cas entre deux : produit tranche (dégradation : cadence réduite, durée
max, arrêt auto — §8).

## 7. Tableaux à remplir (procès-verbal)

### 7.1. Relevés

| Scénario | Heure début | % tél | % lun | Temp tél | Heure fin | % tél | % lun | Temp tél | %/h tél | %/h lun |
|---|---|---|---|---|---|---|---|---|---|---|
| S0 | | | | | | | | | | |
| S1 | | | | | | | | | | |
| S2 | | | | | | | | | | |
| S3 | | | | | | | | | | |

Contexte (une fois) : SHA du build : … ; réseau : … ; ambiante : … ;
lunettes : … ; Meta AI : … ; verdict G1–G4 / N1–N4 : …

### 7.2. Constats S2 (3 essais)

| Essai | Phase au réveil | Flux vivant ? | Reprise seule ? | Délai reprise | Notes |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |

Cadence utile S1 (photos / 10 min, logcat `GlassesBridge`) : …

## 8. Budget initial proposé (à chiffrer avec les mesures, pas avant)

- Cadence utile cible : ≤ cadence S1 mesurée (le headless P6-1 ne doit
  pas coûter plus cher que le pont) ; palier dégradé à /2 si proche
  d'un seuil N.
- Durée max de guidage poche : min(80 % de la projection G1, 120 min).
- Arrêts auto : téléphone ≤ 20 %, lunettes ≤ 15 %, temp ≥ 43 °C, session
  perdue > 60 s.
- Garde-fou facturation : inchangé (`stop()` + `/release` à chaque
  sortie — `LiveProbeStop.kt`).

## 9. Non mesuré par cette campagne (à ne pas vendre comme vérifié)

- Pompe headless (n'existe pas — P6-1), service de premier plan P6
  (P6-3), repli poche (P6-5), Doze profond (> 60 min), pluie / nuit /
  optique, thermique paire (pas de sonde), courant instantané précis
  (pas d'ampèremètre USB — optionnel si le poste en a un).
