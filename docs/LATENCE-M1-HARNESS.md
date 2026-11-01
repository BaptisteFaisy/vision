# LATENCE-M1-HARNESS — protocole A/B binaire-vs-base64 sur isee-sim

Compare le transport WS M1 (JPEG brut, `docs/LATENCE-M1-TRANSPORT-BINAIRE.md`)
au JSON texte historique (base64), sur l'émulateur `isee-sim` — jamais sur le
téléphone. Deux jambes, même SHA, même vidéo, même serveur : la différence
mesurée est celle du transport, pas du code ni de la scène.

## 1. Ce que le A/B mesure (et ne mesure pas)

Les deux jambes passent par `LiveSession.runFrameWith(jpegB64)` : l'appli
construit le base64 dans les deux cas (`runFrameBytes`, sans base64 client,
n'est appelé par aucun écran). Deltas attendus (§ 6 du doc M1) :

- `up` (jpeg → send) : −0,8 ms de fil (27 Ko → 20,7 Ko) **moins** le redécodage
  base64 de la jambe binaire — le signe sur ce poste n'est pas acquis ;
- `srv` : −~1 ms (le serveur saute le décodage base64) ;
- `rtt`, `latency` : la somme des deux ;
- `capt`, `mask`, `pixel` : **contrôles** — le transport ne les touche pas, une
  dérive > 10 ms de moyenne invalide la comparaison (voir § 5).

Le gain CPU client complet (plus de base64 construit) viendra du câblage de
`runFrameBytes`, hors scope : ce harnais mesure le fil + le serveur.

## 2. Sélection de la jambe (build, pas d'option cachée)

- `live.ws.text` dans `local.properties` (gitignoré, jamais commité) → `WS_TEXT`
  (`app/build.gradle.kts`, même motif que `live.local.overlay`) ;
- vide (défaut) = binaire M1 ; `true` = JSON texte même si le serveur lit le
  binaire (`LiveMaskTransport.clientFor`, parseur `buildFlagOn` testé en JVM) ;
- deux APK construits du même SHA par `build-serveur` (§ 4), un par jambe ;
- chaque session annonce sa jambe en logcat sous le tag `ISEE_LAT`
  (`TimingsText.leg` : `leg binary` / `leg text`, invisible à
  `parse_isee_lat.py`), une fois à la première frame puis au repli texte.

## 3. Campagne

```bash
# 1. Deux APK du même SHA (r630, jamais de Gradle local).
build-serveur <worktree> assembleDebug
# 2. Une jambe = un appel (même --video pour les deux) :
bash scripts/latence-ab.sh --leg text --apk <apk-text> --video <balade> \
  --frames 520 --skip 20 --out /tmp/latence-m1-harness/text-<date>
bash scripts/latence-ab.sh --leg binary --apk <apk-binary> --video <balade> \
  --frames 520 --skip 20 --out /tmp/latence-m1-harness/binary-<date>
# 3. Comparaison (mêmes centiles que parse_isee_lat.py, refus si jambe mixte) :
python3 scripts/latence-ab-compare.py --skip 20 \
  /tmp/latence-m1-harness/text-<date>/isee-lat-text.txt \
  /tmp/latence-m1-harness/binary-<date>/isee-lat-binary.txt
```

`latence-ab.sh` refuse si le port 5554 est pris, boote via `isee-sim.sh`
(`--keep` interne), attend 520 frames `ISEE_LAT` (timeout 900 s), rapatrie le
dump + `meta.txt` + verdicts, vérifie la jambe, puis **éteint** l'émulateur
(pas de `--keep` : une campagne ne laisse rien d'allumé).

## 4. Builds : texte puis binaire, même SHA

`build-serveur` transfère le `local.properties` du worktree (le filtre serveur
ne purge que les préfixes `asr|llm|mwdat|sam31|sam|tts`, `live.*` survit) :

```bash
printf 'live.ws.text=true\n' >> local.properties   # local, gitignoré
build-serveur <worktree> assembleDebug             # → APK jambe text
scp <r630>:builds/<nom>/app/build/outputs/apk/debug/app-debug.apk /tmp/latence-m1-harness/apks/app-text.apk
sed -i '/^live\.ws\.text=/d' local.properties
build-serveur <worktree> assembleDebug             # → APK jambe binary (défaut)
scp <r630>:builds/<nom>/app/build/outputs/apk/debug/app-debug.apk /tmp/latence-m1-harness/apks/app-binary.apk
```

Noter les deux `sha256sum` (le script les écrit dans `meta.txt`).

## 5. Validité d'une comparaison

1. **Une jambe par dump** : `latence-ab.sh` refuse un dump mixte ; sans aucun
   marqueur (vieil APK), la jambe est attribuée par l'APK, avec avertissement.
2. **N ≥ 500 mesurées par jambe** après warmup (`--frames 520 --skip 20`) ;
   `latence-ab-compare.py` refuse en dessous (`--min-frames 500`).
3. **Même vidéo** (même `sha256sum`, donc même boucle caméra), même serveur
   déployé (noter le SHA du deploy `sam3-eu-a100`), GPU chaude (le warmup
   couvre le réveil ; en doute, rejouer la jambe).
4. **Contrôles stables** : `capt`, `mask`, `pixel` à ±10 ms de moyenne entre
   jambes, sinon campagnes non comparables (le script le signale, la synthèse
   tranche).
5. **Un seul émulateur à la fois** (port 5554 libre avant chaque jambe),
   annonces prise/libération en `BOITE-USER.md`, GPU tenue par
   `/tmp/sam3-gpu.keep` pendant les runs (voir le contrat de campagne).

## 6. Contenu d'un dossier de jambe

`isee-lat-<jambe>.txt` (dump brut, la preuve), `meta.txt` (date UTC, jambe,
SHA des APK/vidéo, N, warmup, série, AVD), `verdict.txt` / `servers.txt` /
`capsplit.txt` (`parse_isee_lat.py`, mêmes `--skip`), `isee-sim.log`,
`devices-before/after.txt`, `crash.txt` (vide sauf plantage).
