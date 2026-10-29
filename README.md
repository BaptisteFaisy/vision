## Hackathon notice — read this first

> **Built during the Meta hackathon.** Everything in this repository — the Android app,
> the GPU backend, and the companion page — was designed and built during the Meta
> hackathon, **except** the open-source projects below, which are reused as-is
> (models, libraries, and services):
>
> - **SAM 3.1** (`facebook/sam3.1`, via the `unsloth/sam3.1` mirror) — segmentation
>   model, loaded with Hugging Face `transformers`
> - **Depth Anything V2 Small** (`depth-anything/Depth-Anything-V2-Small`, Apache-2.0) —
>   on-device depth model, via `onnx-community/depth-anything-v2-small`
> - **Valhalla** — open-source routing engine (CPU backend)
> - Standard open-source libraries: AndroidX (Compose, CameraX, Room, WorkManager),
>   OkHttp, ONNX Runtime, PyTorch, FastAPI
> - **Meta Wearable Device Access SDK** — camera and microphone access on Ray-Ban Meta
>   glasses
>
> Third-party attributions and licences: `NOTICE` (code) and
> `app/src/main/assets/NOTICE-depth.txt` (models).

# iSee — eyes that speak

iSee is an Android app that helps a **blind or low-vision person** understand
their surroundings: the phone films, one AI **segments** what it sees
(objects, people, doors), another AI **describes** the scene and answers
questions, and the phone **speaks** — distances, walking instructions,
guidance. A **companion** can follow along remotely, live, from their browser.

> This repository (`BaptisteFaisy/vision`) carries the whole product: the Android
> app, the segmenting GPU backend, and the companion page. Every claim below is
> re-checked against the code in this tree; the figures in
> [Figures](#figures) are the output of `python3 tools/count-tree.py`, replayed on
> **29/10/2026** on `master`. Figures held over time live in `docs/PERF.md` — the
> only authoritative reference.

## The problem

Seeing means perceiving, understanding, and acting — three things that require
sight:

1. **Perceive**: what's around me, where, how far, what's moving?
2. **Understand**: describe the scene, answer "what's that?", guide to a
   destination.
3. **Accompany**: let a companion follow remotely, without calling all the time.

## The solution

An ordinary phone, a remote GPU card, and voice as the only interface:

1. **Film** — the camera runs; tap = start/stop video, long-press (~300 ms) =
   hold to speak. No menu to see, no swiping, no double tap.
2. **Segment** — each frame goes to SAM 3.1 on a Modal GPU (Europe), which returns
   objects, boxes, and masks, in photo and live alike.
3. **Measure** — distances are computed **on the phone** (pinhole + on-device
   depth), with no GPU and no request.
4. **Describe and answer** — a vision LLM describes the scene and answers
   questions. No memory is carried from one question to the next: each question
   leaves alone, with no previous turns. The thread itself lives on the phone
   (Room) and is purged when the app closes.
5. **Speak and guide** — synthetic voice, distance pills, walking instructions, the
   route drawn on an in-app MapLibre map, and a hand-off to Google Maps for the last
   mile — while the companion follows on their page.

```
┌──────────────┐  images   ┌──────────────────┐  objects+masks   ┌──────────────┐
│    Phone     │ ────────► │  GPU Modal (EU)  │ ───────────────► │  LLM vision  │
│ camera+voice │           │  SAM 3.1 live    │                  │ description  │
└──────────────┘           └──────────────────┘                  └──────────────┘
       │                            │                                  │
       │  on-device distances       │  one frame in flight             │  voice (TTS)
       ▼                            ▼                                  ▼
┌──────────────┐           ┌───────────────────────────────┐   ┌──────────────┐
│ iSee speaks  │           │ Companion (browser, live)     │   │ Maps routing │
└──────────────┘           └───────────────────────────────┘   └──────────────┘
```

## What the app can do

- **iSee home** — a glass bubble on a white background, the camera blended above it,
  only the current sentence below the bubble. Tap = video, long-press = speak; the
  whole surface is the interface (`app/src/main/java/dev/sam3vision/app/isee/`,
  `docs/ISEE-DESIGN-PORTRAIT.md`).
- **Live** — **12 tracked concepts** by default (`person`, `car`, `bicycle`,
  `electric scooter`, `pole`, `sidewalk`, `crosswalk`, `wall`, `window`, `stairs`,
  `door`, `barrier`), **16 at most** (`LivePrompts.MAX`), one frame in flight over
  WebSocket `/live/ws`, pills, distances, and voice announcements
  (`docs/LIVE-SPEC.md`).
- **Photo → description → questions** — the legacy screens stay reachable from
  Settings → **"Other screens"**: Direct mode (continuous video) and Classic home
  (photo → analysis → chat) (`app/SettingsClassicScreens.kt`).
- **Tap mode** — film briefly, get a spoken summary. `POST /video` client written
  and tested; the route is implemented on no server here (`docs/MODE-TAP.md`).
- **On-device depth (beta)** — a proximity map 100% on the phone (Depth Anything V2
  Small, ONNX Runtime, the `.ort` model downloaded once), fused with the masks
  (`docs/DEPTH.md`).
- **Voice navigation** — "take me to X" routes with Valhalla (in the app and on the
  CPU backend, `/route`), draws the trip on an in-app MapLibre map
  (`docs/CARTE-OSM.md`) and hands off to Google Maps in walking mode
  (`docs/NAVIGATION-VOCALE.md`); pedestrian-light data comes from `/signals`.
- **Walking guidance** — "straight ahead" instructions, obstacles and crossings
  announced by voice (`docs/GUIDAGE-TOUT-DROIT.md`, `docs/VOIX-DANGER.md`).
- **Crossing mode (beta)** — iSee **proposes** the mode when it sees a pedestrian
  crossing and never switches by itself; once on, it reads the light on-device and
  vetoes approaching vehicles (`docs/TRAVERSEE.md`). Its green-detection recall is
  measured below: 1 true green out of 6.
- **Ray-Ban Meta glasses** — glasses camera and mic (DAT SDK 1.0.0), test bench built
  into the app (`docs/META-GLASSES.md`).
- **Companion follow-up** — a share link to a real-time page: current sentence, nearby
  objects, the user's live camera image, and a progress bar from start to destination
  while iSee is guiding (`suivi-parent/README.md`, `suivi-relais/README.md`).

## Repository contents

| Block | Role |
|---|---|
| `:core` | Android library (`dev.sam3vision.core`, minSdk 26): network clients (LLM, SAM, live WebSocket, tracker, video, Valhalla, signals), pacing, masks, distances, pure depth, gestures, crossing, voice, encrypted settings, design tokens (`iseedesign`) |
| `:app` | The APK (`dev.sam3vision.app`, minSdk 29): Compose + CameraX, iSee home, legacy screens, glasses + bench, depth (ONNX), in-app map (MapLibre), voice navigation, capture purge |
| `server/` | Modal backend (Python): live/photo (`backend/live_fast/`, app `sam3-eu-a100`), Valhalla CPU routing (`backend/valhalla_cpu.py`, app `valhalla-idf-cpu`), tracker (`backend/track_app.py`, manual deploy), photo legacy (`backend/modal_app.py`), the same live service ported to Baseten (`backend/baseten/`), GPU probes, and `scripts/` (deploy, benches, probes). The old Expo app was removed from the tree on 29/10/2026 (git history keeps it) |
| `suivi-parent/` | Companion page (static, zero npm dependencies) embedded in every APK (`caregiverAssets`, `app/build.gradle.kts:179`); its Vercel relay is abandoned since 28/10 |
| `suivi-relais/` | The relay actually used (Cloudflare Worker + D1): `POST /api/share`, `GET /api/state` |
| `tools/` | CI guards (Kotlin sizes, Portrait design, latency budget, voice navigation, APK keys), fake LLM/SAM for tests, the tree census (`count-tree.py`) |
| `scripts/` | Build, phone/emulator recipes, checks, latency budget |
| `docs/` | Architecture, contracts, recipes; `docs/DOC-ETAT.md` states each doc's status |

## Tech stack

### Android app (`gradle/libs.versions.toml`, wrapper, builds)

| Layer | Choice |
|---|---|
| Language / build | Kotlin 2.2.21, Gradle 8.14.1, AGP 8.11.1, KSP 2.2.21-2.0.5, JVM 17 bytecode (JDK 17 via `JAVA_HOME`, never pinned) |
| SDK | compileSdk / targetSdk 36 ; minSdk **29** (`:app`), 26 (`:core`) |
| UI / camera | Compose (BOM 2026.05.01, Material3), CameraX 1.6.0 (incl. `camera-video`), activity-compose 1.13.0, lifecycle 2.10.0 |
| Data | Room 2.8.5 (KSP), WorkManager 2.12.0 (hourly purge), security-crypto 1.1.0, core-ktx 1.18.0, Guava 33.3.1-android (Room transitive pin, no direct import) |
| Network | OkHttp 4.12.0 (a single client, a single pool), `org.json` (shipped with Android, zero dependencies), coroutines 1.10.2 |
| Voice / audio | ElevenLabs (TTS + STT fallback), Meta ASR (dictation), jump3r 1.0.4 (pure-Java MP3) |
| Glasses / on-device AI | Meta `mwdat-*` 1.0.0 (the only version served by the glasses), ONNX Runtime 1.18.0 (depth) |
| Map | MapLibre 13.7.1 (guidance screen, OSM tiles, `docs/CARTE-OSM.md`) |
| Version | `versionName = 1.0.<commits>-<sha>`, `versionCode` = commit date (a stale APK cannot overwrite the phone) |

### `server/` backend (Python, Modal + Baseten)

| Item | Value |
|---|---|
| Runtime | Python ≥ 3.11, FastAPI (`fastapi[standard]==0.162.0`), Modal CLI 1.6.2 |
| Model | `unsloth/sam3.1` (ungated mirror of `facebook/sam3.1`), `transformers==5.20.2`, `torch==2.14.1`, `torchvision==0.29.1` |
| Deployed | `sam3-eu-a100` (A100-40GB, `eu-west`) from `server/backend/live_fast/` — `/health`, `/segment`, `/live`, `/live/ws`, `/release`; redeployed by CI on every push that touches it, with its levers (CUDA graph, snapshot, `scaledown_window=60`) |
| Routing | `valhalla-idf-cpu` (CPU, zero GPU, Île-de-France tiles) — `/health`, `/route`, `/signals` (pedestrian lights) ; deployed by CI |
| Other | `modal_app.py` (photo legacy `/health`, `/segment`, `/analyze`, `/ask`), `track_app.py` (tracker `/health`, `/session`, `/frame`, `/session/close` — manual deploy), `baseten/` (same live service, other host), `probe_*.py` (measurement probes, never routinely deployed) |
| Golden rule | **one GPU at a time**: the live camera powers the card on; when the stream is cut, it powers off (`POST /release`); no scheduled task keeps the card on |

### Companion follow-up

Static page (HTML/CSS/JS, WebGL shader regenerated from the Android shader,
zero npm dependencies), served by `suivi-relais/` (Cloudflare Worker + D1).
No map provider: the page shows the shared camera frame and the guidance trip bar.
The old Vercel handlers in `suivi-parent/api/` are kept for local preview only.

### Voice and LLM

| Role | Provider | Contract |
|---|---|---|
| Dictation | Meta ASR (`muse-voice-transcribe-1.0`), ElevenLabs Scribe fallback | `POST {meta}/asr/transcribe` (16 kHz mono WAV) |
| Voice | ElevenLabs TTS (`eleven_v4_turbo`, Laure voice; US Jade offered) | `POST {elevenlabs}/text-to-speech/{voiceId}` → MP3 |
| Description / questions | Baseten, `meta-models/muse-spark-open-1026` (defaults `Settings.kt:168-169`) | `POST {llm}/chat/completions` (OpenAI-compatible), temperature 0.2, budgets 1,200 / 200 tokens, 400 chars (`HttpLlmClient.kt:23-26`) |

## Setup

1. **An LLM key** exposing `chat/completions` (base URL + model) — entered in
   **Settings**, stored encrypted (Keystore), or provided at build time via
   `local.properties` (gitignored). Without a key, the app says so and makes no calls.
2. **A SAM 3.1 segmentation endpoint** — *Base URL* field in Settings. The endpoint
   actually called, in this order: a manual URL (outside the presets) → a URL injected
   at build time for that region → the **region** preset (Europe `sam3-eu-a100` or
   United States) (`resolveSamEndpoint`, `core/settings/SamRegion.kt:50-55`). Without
   any reachable endpoint, the photo goes straight to the LLM. Switching regions powers
   nothing on.
3. **Optional**: ElevenLabs key (voice + dictation fallback), Meta ASR key (dictation),
   Google Maps key (opening the Maps app in walking navigation — the companion page has
   no map). Without a voice key, nothing is announced aloud and **no error is shown** —
   the documented gotcha in `docs/TESTING.md`.

## Build and test

Prerequisites: JDK 17 + Android SDK (`JAVA_HOME`, `ANDROID_HOME`), Python 3 (guards),
Node (companion page, no `npm install`).

```bash
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest   # JVM tests (2,884 @Test)
./gradlew :app:assembleDebug                               # debug APK
python3 tools/check-kotlin-limits.py                       # file < 500 lines, function < 50
python3 tools/check-isee-design.py                         # mandatory Portrait design
python3 tools/check-budget-derive.py                       # doc ↔ code latency figures
python3 tools/check-voice-nav-doc.py                       # doc ↔ code voice navigation
bash scripts/test_scripts.sh                               # 101 shell assertions
node --test suivi-parent/test/*.test.mjs suivi-relais/test/relais.test.mjs
bash scripts/build.sh                                      # assembleDebug --offline + APK check
python3 tools/count-tree.py                                # the Figure table below
```

## Figures

Measured on `master`, 29/10/2026 — `python3 tools/count-tree.py` prints exactly this
table (`--json` for scripts). The census is a script, not a habit: recopied numbers had
drifted three times in one day.

| Mesure | Valeur | Commande |
|---|---|---|
| Fichiers `.kt` | **913** | `find . -name '*.kt' -not -path './.git/*' -not -path '*/build/*' \| wc -l` |
| Lignes Kotlin | **98 370** | idem, `-exec cat {} + \| wc -l` |
| Lignes de prod (`*/src/main`) | **54 684** — `:core` 216 f. / 22 458 l. · `:app` 283 f. / 32 226 l. | idem sous `core/src/main`, `app/src/main` |
| `@Test` JVM | **2 884** — `:core` 1 835 · `:app` 1 049 (banc lunettes compris) | `grep -rho '@Test' core/src/test app/src/test --include='*.kt' \| wc -l` |
| Tests instrumentés | **9** (appareil requis) | idem sous `app/src/androidTest*` |
| `@Preview` Compose | **11** | idem, `@Preview` |
| Backend Python (`server/backend`) | **35** fichiers, **7 172** lignes | `find server/backend -name '*.py' \| wc -l` |
| Docs | **47** fichiers dans `docs/` | `ls docs/*.md \| wc -l` |
| Workflows CI | **6** | `ls .github/workflows/*.yml \| wc -l` |
| Plus gros fichier de prod | `app/.../isee/LiveProbe.kt` — **499** lignes (plafond 500) | `find core app -path '*/src/main/*' -name '*.kt' \| xargs wc -l \| sort -rn \| head` |

Details (constants, budgets, thresholds) are in `docs/PERF.md`; recipes in
`docs/TESTING.md`. The guards are green on this tree; Gradle tests are the only ones not
replayed by the census (`docs/TESTING.md` §2 gives the recipe).

## SAM 3.1 recall on pedestrian lights, and `PedLightGate` on real SAM crops (measured 29/10/2026)

The crossing mode (`docs/TRAVERSEE.md`) rests on two unmeasured assumptions: that SAM 3.1
finds a pedestrian signal at all, and that the box it returns frames the lamp. Both were
tested on 16 real photographs from Wikimedia Commons (7 countries), RTX 3060 Ti,
`unsloth/sam3.1` in `bfloat16`. Measuring code lives outside this tree; the §6 test runs the
**actual Kotlin** `PedLightGate` / `TrainedLightColor` / `PedLightColor` from this tree.
Protocol, image attribution and limits: `docs/MESURES-SAM3-FEUX.md`.

| Prompt | Detections | Images found | Recall |
|---|---|---|---|
| `pedestrian traffic light` | 41 | 14/16 | **87.5 %** |
| `pedestrian signal` | 10 | 7/16 | 43.8 % |
| `red pedestrian traffic light` | 2 | 2/16 | 12.5 % |
| `green pedestrian traffic light` | 13 | 7/16 | 43.8 % |

**The generic prompt detects, a state prompt verifies.** Naming the state drops recall to
12.5 % — but the 3 detections it did return were all correct (confidence 1.00). Treat state
prompts as a cross-check, never as the detector.

**`PedLightGate` says green 1 time out of 6 true green signals.** Feeding it crops built
exactly like `CrossingPixels.frame` (best-score box, longest edge → 48 px) gives Green 1/6,
Red 5/16, Unknown 9/16 over the 16 images — no false green on labelled images, so the
fail-safe direction holds, but a crossing mode that never announces green is dead weight.
The check that rejects is `ELONGATION_MIN = 1.5f`, and it cannot be re-tuned: the highest
elongation among rejected true greens is **1.11**, the lowest among the car discs the gate
must reject is **1.30**. The two domains are inverted, so the assumption written in the gate —
*"the SAM box hugs the lamp"* — does not hold for a text-prompted SAM 3.1, which segments the
**housing**. Fix belongs upstream (frame the lamp, or re-calibrate on the housing domain), not
in the constant.

Two defects found while running it, worth carrying into the measurement code:

- **One 384-pixel sliver flipped a whole scene.** SAM returned the real signal (516×1056 px,
  green) plus three 8-to-48-pixel slivers on the mounting pole; the scene verdict came out
  `red`. Votes must be restricted to detections within 10× of the largest one in frame.
- **A dim dotted-LED green pictogram read as "off"** (0.65 % of its crop against a 1.2 % floor).
  Lowering the floor to 0.6 % gained the case *and* improved the synthetic corpus (masked
  95.5 → 95.6 %, whole-crop 80.3 → 81.9 %).

State accuracy on the 9 photos with an unambiguous ground truth: **7/9, zero red↔green
errors**. The two misses are ground-level LED strips, not signal heads. End-to-end cost:
**0.71 s/image** for four prompts, once the vision embedding is shared across prompts.

Harness control: the same harness reproduces **9/9** of the expected verdicts in
`core/src/test/resources/feuxfr/ATTRIBUTION.md` before the SAM crops are read — that is the
check that makes the 1/6 citable.

Four attempts to lift that 1/6 to 4/6 (new features, retrained tree, another crop framing,
another prompt) **all failed without a single false green**; the numbers and the reasons are
in `docs/CALIBRAGE-FEU.md`, so nobody walks that road twice.

## YOLO11n person benchmark: base vs fine-tuned (post-training)

The urgency pipeline (YOLO11n person + ByteTrack-style tracker + voice at 3 m)
is measured on two axes. **Quality**: 100 hold-out street views (Panoramax,
day/night, eye-verified, stems disjoint from train — `yolo-eval/`, outside
this tree), `eval.py` base-vs-fine-tuned in one run. **Speed**: Samsung
SM-G780G, median of 5 (`DangerBench`, full NMS decode included), Settings
"Tester YOLO11n" button or `DangerBenchDeviceTest`.

| Metric | Base yolo11n | Fine-tuned (teacher SAM 3.1) |
|---|---|---|
| mAP50 (eval, conf 0.25) | **0.383** | pending (training v1) |
| mAP50-95 | 0.234 | pending |
| Precision / Recall | 0.737 / 0.412 | pending |
| Recall small persons | 0.29 | pending |
| False positives / 100 img | 13 (capture rig) | pending |
| Phone 640 CPU (Samsung) | **6.2 fps** (sess 130 ms + dec 37 ms) | same bench, pending |

Base = COCO weights (Ultralytics, AGPL-3.0 — `app/src/main/assets/NOTICE-detect.txt`).
Teacher = SAM 3.1 (`unsloth/sam3.1`) pseudo-labels with rig filter (boxes
`cy>0.9` dropped, else the model learns "car roof = person"). Small-person
recall is the metric to watch. Uncapped NMS measured 0.4 fps before the fix —
the 6.2 fps includes the capped decode. The fine-tuned column fills
automatically (`DELTA.md`) when training lands; this table follows.

## CI and merge queue

- **PRs**: `ci` (guards + JVM tests + backend unit tests + goldens) and `ci-budget`
  (≤ 5 min per job, same file) and `couche0` (declared `## Scope`, forbidden patterns) —
  all three **required**; a docs PR skips Gradle and gets its verdict in seconds.
- **Merge queue**: **Mergify** (`.mergify.yml`, batches of 3, rebase). GitHub's native
  queue is unavailable — it needs an organisation-owned repository, and the API answers
  `422 Invalid rule 'merge_queue'` on a user repository. `pr-auto-merge.yml` survives as
  the sweeper (conflicts, failed-run retries). `master` is protected (PR mandatory).
- On `master`: `ci-native` (debug + release APK + lint, 10 min), Modal redeploys
  (`deploy-live-fast`, `deploy-valhalla-cpu`).
- **Self-hosted** runners `[self-hosted, linux, x64, vision]` with a warm Gradle cache for
  every Gradle job; the two Modal redeploys run on `ubuntu-latest`.

Detail: `docs/CI-QUEUE.md`.

## Docs

- **Start here**: `docs/ARCHITECTURE.md` (modules, flows, HTTP contract, keys),
  `docs/PERF.md` (held figures), `docs/TESTING.md` (counts, recipes),
  `docs/DOC-ETAT.md` (each doc's status), `docs/OPTIMISATIONS.md`,
  `NOTES.md` (versions, decisions), `CHANGELOG.md` (journal since 26/10/2026).
- **Live and GPU**: `docs/LIVE-SPEC.md`, `docs/LATENCE-BUDGET.md`, `docs/TRACK-PERF.md`,
  `docs/T8-PREDICTION.md`, `docs/KEYFRAME-SCHEDULER.md`, `docs/L13-FUSION.md`.
- **Voice, narration, navigation**: `docs/ELEVENLABS-TTS.md`, `docs/META-ASR.md`,
  `docs/VOICEPACK.md`, `docs/VOIX-POSITIONS.md`, `docs/VOIX-ANGLAIS.md`,
  `docs/VOIX-DANGER.md`, `docs/MICRO-VERROUILLE.md`, `docs/TEST-LONGPRESS-DICTEE.md`,
  `docs/NAVIGATION.md`, `docs/NAVIGATION-VOCALE.md`, `docs/RECETTE-NAVIGATION-VOCALE.md`,
  `docs/VOICE_NAV_PROXY.md`, `docs/GPS_GUIDE_UTILISATEUR.md`, `docs/GUIDAGE-TOUT-DROIT.md`,
  `docs/URGENCE-EXTERNE.md`.
- **Imaging**: `docs/MASQUES-DEBUG.md`, `docs/MASK-DECODE.md`, `docs/DISTANCES.md`,
  `docs/DEPTH.md`, `docs/MESURES-SAM3-FEUX.md`, `docs/CALIBRAGE-FEU.md`,
  `docs/MESURES-u11c.txt`.
- **Glasses**: `docs/META-GLASSES.md`, `docs/META-IMAGE.md`, `docs/GLASSES-ETAT.md`,
  `docs/GLASSES-DECISION.md`, `docs/LUNETTES-LANCEMENT.md`, `docs/glasses-dat-spike.md`,
  `docs/glasses-spike-ci.md`.
- **Product and infrastructure**: `docs/MODE-TAP.md`, `docs/MODE-US.md`,
  `docs/ISEE-DESIGN-PORTRAIT.md`, `docs/TRAVERSEE.md`, `docs/CARTE-OSM.md`,
  `docs/NETTOYAGE.md`, `docs/CLES-DU-BUILD.md`, `docs/CI-QUEUE.md`.
- **Server**: `server/README.md`, `server/backend/README.md`, `server/docs/`
  (contracts, latency, deployment).
- **Companion**: `suivi-parent/README.md`, `suivi-relais/README.md`.

## License and security

MIT — see `LICENSE`. Third-party code: `NOTICE`. **Never a public issue for a
vulnerability** (`SECURITY.md`). No key is versioned: `local.properties` is gitignored,
keys live in the Android Keystore or the repo secrets (read by the CI's "build keys"
step). **By default the APK embeds the keys** so a fresh install has nothing to type
(`docs/CLES-DU-BUILD.md`): an APK is extractable — hand it only to the people you lend
those keys to, never publish it.
