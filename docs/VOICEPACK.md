# Pack voix — mêmes phrases ElevenLabs, zéro latence

Le guidage (traversée et navigation) parle la
**même voix ElevenLabs** que la narration, lue par le **même lecteur partagé**
(`SpeechPlayer` de l'écran iSee : une seule lecture à la fois, chaque entrée
coupe la précédente). Fini les deux moteurs qui se parlent dessus.

## Principe

1. Les phrases fixes vivent dans `tools/voicepack-catalog.json` (miroir du
   Kotlin : `CrossingText`, `VoiceNavPolicy`) — y compris les **états de
   mouvement** (`vehicle_car_moving`, `cycle_velo_moving`… : depuis le
   01/11 un mobile ne dit plus sa position, donc sa phrase ne varie plus,
   donc elle est enregistrée — § 32 de `VOIX-DANGER.md`).
2. `tools/gen-voicepack.py` les synthétise **une fois** (voix Laure par défaut,
   mêmes paramètres que l'app) en `app/src/main/res/raw/vp_<id>_en.mp3` +
   `assets/vp_voice.txt` (voix du pack). **Anglais seul** depuis le 01/11/2026 :
   les 99 `vp_*_fr.mp3` sont supprimés (produit anglophone, `SpeechLanguage`
   migre Fr vers En dans les deux stores) et le catalogue ne porte plus de `fr`.
3. Au runtime, `VoicePack` rejoue le clip quand la voix réglée est celle du
   pack ; sinon (voix changée, clip manquant, clé absente) : synthèse
   ElevenLabs bornée à 500 ms puis mise en cache, comme `AnnouncementPlayer`
   (jamais de TTS système, jamais de son en retard).

## Générer (détenteur de la clé)

```bash
ELEVENLABS_API_KEY=… python3 tools/gen-voicepack.py
git add app/src/main/res/raw/vp_*.mp3 app/src/main/assets/vp_voice.txt
```

La clé ne transite que par l'environnement (jamais affichée ni stockée).
Voix non défaut : `--voice <id>` (le pack reste ignoré tant que `vp_voice.txt`
ne colle pas à la voix réglée). `--regenerate` régénère tout.

## Garde

`python3 tools/check-voicepack.py` (CI, câblée le 01/11/2026) : chaque texte
existe dans le Kotlin, chaque MP3 attendu existe et pèse > 0, **aucun MP3
`vp_*` en surplus** sur le disque, `vp_voice.txt` = `LAURE_VOICE_ID`.
`--allow-missing` pour les branches qui codent avant de générer.

## Vérifier que le pack est bien en voix Laure (ordre user du 01/11)

Le garde CI (`check-voicepack.py`) prouve que le catalogue colle au code et que
les MP3 existent — **pas** que c'est Laure qui parle dedans. `vp_voice.txt` est
une déclaration, pas une preuve : un clip régénéré avec une autre voix, ou
ajouté à la main, garderait le bon stamp.

`tools/check-voicepack-voice.py` le vérifie **par le son** : pour chaque entrée
du catalogue il synthétise le même texte en voix du pack, puis compare les deux
sons — profil d'énergie sur 24 bandes log-espacées (80 Hz – 7 kHz), normalisé
et comparé en cosinus, plus la F0 médiane. Calibré sur ce pack le 01/11 :

| Comparaison | Similarité |
| --- | --- |
| Laure vs Laure (même texte, régénéré) | **0,987 – 0,999** |
| Laure vs Rachel (autre voix, même texte) | 0,938 – 0,953 |

Seuil par défaut 0,980 ; tout fichier dessous est listé « À REFAIRE » et le
script sort en erreur (1). Outil **manuel** (réseau + clé + numpy + ffmpeg),
hors CI — la clé ne passe que par l'environnement :

```bash
ELEVENLABS_API_KEY=… python3 tools/check-voicepack-voice.py            # pack entier
ELEVENLABS_API_KEY=… python3 tools/check-voicepack-voice.py --intro    # + intro_isee.mp3 (v3)
ELEVENLABS_API_KEY=… python3 tools/check-voicepack-voice.py --id nav_left --limit 5
```

Réparer un fichier signalé : `ELEVENLABS_API_KEY=… python3
tools/gen-voicepack.py --regenerate` (tout) ou supprimer le MP3 puis relancer
`gen-voicepack.py` sans `--regenerate` (lui seul).

Audit du 01/11 (demande user « je veux que tous les mp3 soient avec la voix de
Laure, sinon refais-les ») : **214/214 fichiers au-dessus du seuil** (le plus
bas : `vehicle_scooter_moving_en` 0,987), dont `intro_isee.mp3` (0,999, modèle
`eleven_v3` comme à sa génération). Rien à régénérer. Rapport et références :
`~/voix-audit-20261101/`. (Audit mené sur le pack bilingue ; les 99 clips FR
sont supprimés depuis — reste 114 EN + intro, tous couverts par cet audit.)

## Ce qui n'est jamais un clip

Les phrases qui portent une **mesure** ou un **compte** restent en synthèse :
arrêt et stationnement (« Voiture à l'arrêt, à 2 mètres. »), pluriel générique
(« 2 vehicles moving. »), positions (« Personne à 1 heure. »). `clipIdFor` rend
`null` pour elles — c'est la table qui décide, pas l'appelant
(`VehicleStateText.clipIdFor`, `CycleText.clipIdFor`, lues par
`recordedClipId`).

## Ajouter une phrase

1. L'écrire dans le Kotlin d'abord (seule source parlée).
2. L'ajouter au catalogue (`parts` si composée en code).
3. Régénérer le pack, `check-voicepack.py`, commiter MP3 + catalogue.

## Clips à durée contrainte (ordre user du 02/11, § 46 de VOIX-DANGER)

Certains clips ont un budget **joué** de 500 ms à vitesse de base (×1,9,
réglage `ttsSpeed`) — aujourd'hui `crosswalk` (« Crosswalk. », annonce du
passage devant), gardé par `DURATIONS` dans
`tools/check-isee-clips-en.py` (§ 6). La synthèse brute le dépasse (1,15 s),
donc le MP3 embarqué est trimmé après génération — silences tête/queue
retirés, mot intact, **sans accélération stockée** (le lecteur accélère
déjà ×1,9 ; un `atempo` cumulé rendrait le mot inintelligible) :

```bash
ffmpeg -i vp_crosswalk_en.mp3 -af "silenceremove=start_periods=1:start_duration=0:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_duration=0:start_threshold=-45dB,areverse" -codec:a libmp3lame -b:a 128k -ar 44100 vp_crosswalk_en.mp3
ffprobe -v error -show_entries format=duration -of csv=p=0 vp_crosswalk_en.mp3  # /1,9 < 0,5 (0,76 s → 0,40 s joué)
python3 tools/check-isee-clips-en.py  # dont le budget joué (§ 6)
```

Deux pièges mesurés sur ce clip : `-30dB` mange la syllabe douce « walk »
(tronqué à 0,44 s — vérifier la structure cross/silence/walk après trim) et
`stop_periods=-1` continue dans le mot. Un `--regenerate` restaure la
version lente : repasser la recette (fichier < 0,95 s) et la garde avant de
commiter.

Le compte escaliers (« 1 step. » / « N steps. », chaînes `StairChain`,
ordre user du 04/11) tient le même budget 500 ms, joué joint : les 23
atomes `c_*` sont trimmés avec la recette ci-dessus, plus
`-id3v2_version 0 -write_id3v1 0` (joints en un seul MP3 au runtime — aucun
tag au milieu du flux), puis joués en une lecture tranchée 500 ms
(`STAIR_COUNT_SLICE_MS`, vitesse adaptée jusqu'à ×3, compte à rebours
« N steps left. » exclu : trop long joint, il enchaîne comme avant).
Garde : `COUNT_CHAINS` dans `tools/check-isee-clips-en.py` — chaque paire
tient 0,5 s au plafond de tranche (paire la plus longue « seventeen
steps » : 1,46 s joints → 488 ms à ×2,93, relevé du 04/11/2026).
