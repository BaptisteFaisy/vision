## Hackathon notice — read this first

> **Built during the Meta hackathon.** Everything in this repository — the Android app,
> the GPU backend, and the companion page — was designed and built during the Meta
> hackathon, **except** the open-source projects below, which are reused as-is
> (models, libraries, and services):
>
> - **SAM 3.1** (`facebook/sam3.1`, via the `unsloth/sam3.1` mirror) — segmentation
>   model, loaded with Hugging Face `transformers`
> - **Depth Anything V2 Small** (`depth-anything/Depth-Anything-V2-Small-hf`,
>   Apache-2.0) — depth model, run **on the GPU server** (`transformers`)
> - **Valhalla** — open-source routing engine (CPU backend)
> - Standard open-source libraries: AndroidX (Compose, CameraX, Room, WorkManager),
>   OkHttp, PyTorch, FastAPI
> - **Meta Wearable Device Access SDK** — camera and microphone access on Ray-Ban Meta
>   glasses
>
> Third-party attributions and licences: `NOTICE` (code and reused models).

# iSee — eyes that speak

[![CI](https://github.com/BaptisteFaisy/vision/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/BaptisteFaisy/vision/actions/workflows/ci.yml)

iSee is an Android app that helps a **blind or low-vision person** understand
their surroundings: the phone (or a pair of Ray-Ban Meta glasses) films, one AI
**segments** what it sees (objects, people, doors, lights), another AI
**describes** the scene and answers questions, and the phone **speaks** —
distances, walking instructions, crossing verdicts, guidance. A **companion**
can follow along remotely, live, from their browser.

> This repository (`BaptisteFaisy/vision`) carries the whole product: the Android
> app, the segmenting GPU backend, and the companion page. This page describes
> what the tree **actually ships**, re-checked on **04/11/2026** against
> `origin/master` (`995c42223`). The tree counts in [Figures](#figures) are the
> output of `python3 tools/count-tree.py`; measured figures elsewhere are dated
> and marked as measurements, not as code guarantees.

## Design rules from the field

These are not marketing principles; they are the rules the app is tested against,
written down after ten days of real walks with the phone and the glasses:

1. **Safety is fail-closed.** A doubtful pedestrian light, an unreadable box, or a
   missing box means **do not cross**. The app never trades a false green for
   convenience.
2. **Present tense only.** No announcement may describe something older than
   ~1.5 s ago. Buffered, timestamped, or queued phrases are dropped rather than
   spoken late.
3. **Priority is explicit and numeric.** On the crosswalk, a verdict that just
   changed is level 99 and cuts everything; a repeated verdict shares level 101 with
   humans, and curbs are 102. Off the crosswalk the verdict is 100. Immediate danger
   always wins.
4. **Hands-free.** Long-press to speak, tap to film, four taps to switch
   indoor/outdoor, swipes for crossing, guidance, pictograms, and glasses. No menu
   is required to use the product.
5. **The voice is the interface.** Short utterances, fixed clips when possible,
   an English fixed-clip pack; a missing key or endpoint degrades behavior, and
   that degradation is documented rather than hidden.
6. **One GPU at a time, on demand.** The camera powers the card on; leaving the
   app freezes it; `POST /release` stops it. No scheduled task keeps the A100
   billing.

## Meta AI stack

iSee is built on Meta AI, end to end:

- **Segmentation — Meta SAM 3.1** (`facebook/sam3.1`, via the `unsloth/sam3.1`
  mirror) served from `server/backend/live_fast/` on a Modal A100
  (`sam3-eu-a100`, workspace `gaiamelie`): `/health`, `/segment`, `/live`,
  `/live/ws`, `/release`. App clients:
  `core/src/main/java/dev/sam3vision/core/net/HttpSamClient.kt`,
  `HttpLiveClient.kt`, `WsLiveClient.kt`.
- **Vision LLM — Muse Spark** (`meta-models/muse-spark-open-1026`, the default)
  over an OpenAI-compatible `chat/completions` endpoint, hosted by Baseten
  (`core/src/main/java/dev/sam3vision/core/net/HttpLlmClient.kt`; defaults in
  `core/src/main/java/dev/sam3vision/core/settings/Settings.kt`). The endpoint is
  provider-agnostic — a different OpenAI-compatible base URL can be entered in
  Settings.
- **Speech-to-text — Meta AI API**: batch
  `POST https://api.meta.ai/v1/asr/transcribe` with `muse-voice-transcribe-1.0`
  (`core/src/main/java/dev/sam3vision/core/net/MetaAsrClient.kt`), plus the
  realtime WebSocket `wss://…/asr/realtime` used while the finger holds
  (`MetaRealtimeClient.kt`). ElevenLabs Scribe (`scribe_v1`) is the fallback;
  both paths are documented in `docs/META-ASR.md`.
- **Image generation — Meta Muse Image** (`muse-image-1.0`): a filmed object's
  photo becomes its black-on-yellow landmark pictogram via
  `POST {meta}/images/edits` (same base URL and key as dictation)
  (`core/src/main/java/dev/sam3vision/core/net/MetaImageClient.kt`,
  `core/src/main/java/dev/sam3vision/core/picto/PictoPrompt.kt`,
  `docs/META-IMAGE.md`).
- **Glasses — Meta Wearables SDK** (`com.meta.wearable:mwdat` 1.0.0): camera and
  microphone on Ray-Ban Meta glasses (`docs/META-GLASSES.md`).

## The problem

Seeing means perceiving, understanding, and acting — three things that require
sight:

1. **Perceive**: what's around me, where, how far, what's moving?
2. **Understand**: describe the scene, answer "what's that?", guide to a
   destination.
3. **Accompany**: let a companion follow remotely, without calling all the time.

## The solution

An ordinary phone (or a pair of glasses), a remote GPU card, and voice as the
primary interface:

1. **Film** — the camera runs; tap = start/stop video, long-press (~300 ms) =
   hold to speak. On iSee, four taps switch indoor/outdoor, swipes reach crossing
   mode, guidance, pictograms, and the glasses link. There is no menu to see.
2. **Segment** — frames go to SAM 3.1 on a Modal GPU (Europe), which returns
   objects, masks, and boxes, in photo and live alike. The live path is a
   WebSocket (`/live/ws`) with an automatic, sticky fallback to `POST /live`
   when the socket cannot open, refuses a frame, or drops.
3. **Measure** — distances are computed **on the phone** from the boxes SAM
   returns (pinhole model, Kalman smoothing, known sizes), then refined by the
   depth medians the server attaches to each object (`with_depth`, one depth map
   per 8 frames). No extra network request is made for a distance.
4. **Describe and answer** — a vision LLM describes the scene and answers
   questions. The thread stays on the phone, capped at 20 turns
   (`UseCases.MAX_HISTORY_TURNS`); stored media live in a local Room database and
   are purged when a new iSee session starts or the app is destroyed.
5. **Speak and guide** — synthetic voice, distance pills, walking instructions,
   crossing verdicts, the route drawn on an in-app MapLibre map, and a hand-off
   to Google Maps for the last mile — while the companion follows on their page.

```
┌──────────────┐  images   ┌──────────────────┐  objects+masks   ┌──────────────┐
│ Phone/glasses│ ────────► │  GPU Modal (EU)  │ ───────────────► │  LLM vision  │
│ camera+voice │           │  SAM 3.1 live    │                  │ description  │
└──────────────┘           └──────────────────┘                  └──────────────┘
       │                            │                                  │
       │  on-device distances       │  1 inference, 8 slots            │  voice (TTS)
       ▼                            ▼                                  ▼
┌──────────────┐           ┌───────────────────────────────┐   ┌──────────────┐
│ iSee speaks  │           │ Companion (browser, live)     │   │ Maps routing │
└──────────────┘           └───────────────────────────────┘   └──────────────┘
```

## What the app can do

- **iSee home** — a white screen with a rounded camera block (60 % of the screen
  when idle, 70 % while guiding), the spoken sentence below it, and a row of
  chips (place, Crossing, status). Tap = video, long-press = speak, four taps =
  indoor/outdoor, swipe up = crossing, down = guidance, right = pictograms,
  left = glasses (`app/src/main/java/dev/sam3vision/app/isee/`,
  `docs/ISEE-DESIGN-PORTRAIT.md`).
- **Live** — **20 outdoor concepts** by default (spec v4 + 03/11 restore, `child`
  removed per user order: `person`, `car`, `pedestrian traffic light`, `curb`,
  `sidewalk`, `crosswalk`, `pole`, `stairs`, `fence`, `wall`, `barrier`, `sign`,
  `street lamp`, `animal`, `trash can`, `souvenir shop`, `bus`, `van`,
  `stroller`, `dog`) plus a **10-object indoor preset**, **22 at most**
  (`LivePrompts.MAX`, `MAX_PROMPTS` server-side). The iSee probe runs **two
  frames in flight** in both Fast (210 ms budget) and Crowd (600 ms); Direct runs
  one. Transport: WebSocket `/live/ws`, with the sticky HTTP fallback above
  (`docs/LIVE-SPEC.md`, `docs/WS-RUNBOOK.md`).
- **Photo → description → questions** — the legacy screens stay reachable from
  Settings → **"Other screens"**: Direct mode (continuous video) and Classic home
  (photo → analysis → chat) (`app/src/main/java/dev/sam3vision/app/SettingsClassicScreens.kt`).
- **Tap mode** — film briefly, get a spoken summary. `POST /video` client written
  and tested; the route is implemented on no server here, so the client falls back
  to `POST /segment` on the first frame (`docs/MODE-TAP.md`).
- **Depth** — Depth Anything V2 Small runs **on the GPU server** (one map per 8
  frames, medians repeated in between, `with_depth`); the app's iSee probe sends
  every frame with `with_depth` and refines its pinhole distances with those
  medians (`docs/DEPTH.md`).
- **Voice navigation** — "take me to X" geocodes with Android's `Geocoder`, falls
  back to the French BAN (`api-adresse.data.gouv.fr`), routes with Valhalla (CPU
  backend, `/route`), draws the trip on an in-app MapLibre map
  (`docs/CARTE-OSM.md`) and hands off to Google Maps in walking mode
  (`docs/NAVIGATION-VOCALE.md`). Opening Maps is an intent: **no Google Maps API
  key is used or required**. Arrival is announced, but the arrival screen was
  removed on 04/11 — the app stays on the camera.
- **Walking guidance** — "straight ahead" instructions, obstacles and crossings
  announced by voice, stairs counted, curbs signalled
  (`docs/GUIDAGE-TOUT-DROIT.md`, `docs/VOIX-DANGER.md`).
- **Crossing mode (beta)** — **armed manually** (chip, or voice "traverser" /
  "crossing mode on"; "crossing mode off" stops it). It never arms itself. The
  **pedestrian light is the only authority**: car-light prompts are filtered out
  and the car housing is vetoed by shape, so a green pedestrian figure is not
  overridden by the red car light next to it. A green is confirmed by a 3-of-5 sliding vote; without a
  light, the app says cross only after 3 s of continuous calm with no moving car.
  Parked cars are exempt when the app can prove they are still (`docs/TRAVERSEE.md`).
- **Landmark pictograms** — film an object, Meta Muse Image (`muse-image-1.0`)
  redraws its photo as a black-on-yellow pictogram, stored directly (no caregiver
  confirmation since 03/11), then recognised on live video on-device with no
  network. Display is **XXL (54 % of the card width)** and rotated 90° clockwise
  since 04/11 (`docs/META-IMAGE.md`).
- **Ray-Ban Meta glasses** — glasses camera and mic (DAT SDK 1.0.0), test bench
  built into the app (`docs/META-GLASSES.md`), live on the phone screen at a
  requested **24 fps, MEDIUM 504×896 H.265**, with automatic fallback to 15 fps /
  LOW when the link drops more than 5 % of frames.
- **Companion follow-up** — a share link to a real-time page: current sentence,
  the landmark pictograms recognised in the scene, the user's camera (live
  WebSocket, slow photo fallback every 2 s), and a progress bar from start to
  destination while iSee is guiding (`suivi-parent/README.md`,
  `suivi-relais/README.md`).

## What changed since 02/11/2026

The tree moved 268 commits and +16,226 / −3,920 lines in two days. The changes a
README reader must know about:

- **Live transport is no longer WebSocket-only.** If `/live/ws` cannot open,
  refuses a frame, or drops, the client switches to `POST /live` and stays there
  (sticky), re-probing the socket once every 10 frames
  (`core/src/main/java/dev/sam3vision/core/net/WsHttpFallback.kt`, `WsLiveClient.kt`).
- **Network handovers no longer kill the stream.** The transport key is
  `interface#netId`, not just the interface name; backend pools are purged when a
  socket is discarded; DNS is routed through the backend route so the glasses'
  internet-less Wi-Fi cannot black-hole `modal.run`
  (`NetworkState.kt`, `BackendRoute.kt`, `Http.kt`).
- **The GPU wakes in 6–11 s instead of ~28 s.** Modal's memory snapshot is kept
  when the app leaves the foreground; the live network is warmed as soon as the
  camera link opens (`SamLifecycle.kt`, `LiveWarmup.kt`).
- **The server accepts 8 live slots**, not 4, with one inference at a time; a
  saturated card answers **429** instead of parking the request until timeout
  (`server/backend/live_fast/live_app_fast.py`, `[deploy-gpu]` 04/11).
- **iSee UI**: rounded camera block, chips on one row, white system bar, wordmark
  removed, arrival screen and route spinner removed, pictograms ×2 and rotated.
- **Crossing**: pedestrian-only prompts, car-housing veto, per-instance
  moving/parked memory, "no light → cross after 3 s without a moving car".
- **Glasses**: H.265 decoder rewritten, session recovery, 24 fps / MEDIUM,
  glasses photo sent to the LLM on the capture button.

## Repository contents

| Block | Role |
|---|---|
| `:core` | Android library (`dev.sam3vision.core`, minSdk 26): network clients (LLM, SAM photo/live/WS, tracker, video, Valhalla), transport fallback and network handover, pacing and frame gate, masks, distances, depth, gestures, crossing/traverse, navigation, voice, encrypted settings, design tokens (`iseedesign`), pictograms, stairs, curb |
| `:app` | The APK (`dev.sam3vision.app`, minSdk 29): Compose + CameraX, iSee home, legacy screens, tap mode, glasses + bench, in-app map (MapLibre), voice navigation, companion share, capture purge |
| `server/` | Deployed backend: live/photo (`backend/live_fast/`, app `sam3-eu-a100`), Valhalla CPU routing (`backend/valhalla_cpu.py`, app `valhalla-idf-cpu`), tracker (`backend/track_app.py`, manual deploy), the photo-era app (`backend/modal_app.py`, not called by the current app), Baseten host config (`backend/baseten/`), GPU probes, and `scripts/` (deploy, benches, probes, unit tests) |
| `suivi-parent/` | Companion page (static, zero npm dependencies) embedded in every APK (`caregiverAssets`, `app/build.gradle.kts`); its Vercel deployment is **not** the production path |
| `suivi-relais/` | The relay actually used (Cloudflare Worker + D1): `POST /api/share`, `GET /api/state`, `GET /health`, `GET /ws` with `role=watch` (caregiver view) or `role=push` + key (phone upload) — one Durable Object room per stream, frames never touch the database |
| `tools/` | CI guards (Kotlin sizes, Portrait design, latency budget, voice navigation, APK keys, masks, WS runbook), the pedestrian-light crop/training pipeline, voice pack generator, caregiver orb export, fake LLM/SAM for tests, the tree census (`count-tree.py`) |
| `scripts/` | Build, phone/emulator recipes, checks, latency budget, golden masks |
| `docs/` | Architecture, contracts, recipes; `docs/DOC-ETAT.md` is the documentation status table |

## Tech stack

### Android app (`gradle/libs.versions.toml`, wrapper, builds)

| Layer | Choice |
|---|---|
| Language / build | Kotlin 2.2.21, Gradle 8.14.1, AGP 8.11.1, KSP 2.2.21-2.0.5, JVM 17 bytecode (JDK 17 via `JAVA_HOME`, never pinned) |
| SDK | compileSdk / targetSdk 36 ; minSdk **29** (`:app`), 26 (`:core`) |
| UI / camera | Compose (BOM 2026.05.01, Material3), CameraX 1.6.0 (incl. `camera-video`), activity-compose 1.13.0, lifecycle 2.10.0, fragment-ktx 1.8.9 (transitive pin) |
| Data | Room 2.8.5 (KSP), WorkManager 2.12.0 (hourly capture purge), security-crypto 1.1.0, core-ktx 1.18.0, Guava 33.3.1-android (Room transitive pin) |
| Network | OkHttp 4.12.0 (shared pools: base + live, one dispatcher each), `org.json` (shipped with Android; real `org.json` only in JVM tests), coroutines 1.10.2 |
| Voice / audio | ElevenLabs (`eleven_v4_turbo` TTS, `scribe_v1` STT fallback), Meta ASR batch + realtime, jump3r 1.0.4 (pure-Java MP3 for the media cache); fixed clips are an **English-only pack** since 01/11 (`docs/VOICEPACK.md`) |
| Glasses | Meta `mwdat-*` 1.0.0 (the only version served by the glasses), 24 fps request, MEDIUM 504×896 H.265, fallback 15 fps / LOW |
| Map | MapLibre 13.7.1 (guidance screen, OSM raster tiles, `docs/CARTE-OSM.md`) |
| Version | `versionName = 1.0.<commits>-<sha>`, `versionCode` = commit date (a stale APK cannot overwrite the phone) |

### `server/` backend (Python, Modal + Baseten)

| Item | Value |
|---|---|
| Runtime | Python 3.12 in the Modal image, FastAPI (`fastapi[standard]==0.162.0`), Modal CLI 1.6.2 |
| Model | `unsloth/sam3.1` (ungated mirror of `facebook/sam3.1`), `transformers==5.20.2`, `torch==2.14.1`, `torchvision==0.29.1` ; Depth Anything V2 Small (`depth-anything/Depth-Anything-V2-Small-hf`, Apache-2.0) for `with_depth` |
| Deployed | `sam3-eu-a100` (A100-40GB, `eu-west`, workspace `gaiamelie`) from `server/backend/live_fast/` — `/health`, `/segment`, `/live`, `/live/ws`, `/release`; **8 live slots**, one inference at a time, `scaledown_window=60`, memory snapshot |
| Routing | `valhalla-idf-cpu` (CPU, zero GPU, Île-de-France tiles) — `/health`, `/route`, `/signals` (pedestrian-light points; the app does not call `/signals`) |
| Other | `modal_app.py` (photo era: `/health`, `/segment`, `/analyze`, `/ask` — kept and unit-tested, not called by the current app), `track_app.py` (tracker `/health`, `/session`, `/frame`, `/session/close` — manual deploy, used only when Direct runs on the tracker), `baseten/` (host config), `probe_*.py` (measurement probes, never routinely deployed) |
| Golden rule | **one GPU at a time**: the live camera powers the card on; leaving the app freezes it; `POST /release` stops it; no scheduled task keeps the card on |

### Companion follow-up

Static page (HTML/CSS/JS, WebGL shader regenerated from the Android shader,
zero npm dependencies), served by `suivi-relais/` (Cloudflare Worker + D1).
The live camera goes through the relay's WebSocket (`/ws`, one Durable Object
per stream); `/api/state` keeps the slow fallback (one frame every 2 s) and the
guidance trip. No map provider. The old Vercel handlers in `suivi-parent/api/`
are kept for the local preview tests only.

### Voice and LLM

| Role | Provider | Contract |
|---|---|---|
| Dictation | Meta ASR (`muse-voice-transcribe-1.0`), batch + realtime; ElevenLabs Scribe (`scribe_v1`) fallback | `POST {meta}/asr/transcribe` (16 kHz mono WAV) · `wss://{meta}/asr/realtime` |
| Voice | ElevenLabs TTS (`eleven_v4_turbo`, Laure voice by default; voice ID editable) + fixed local clips for recurring phrases | `POST {elevenlabs}/text-to-speech/{voiceId}` → MP3; clips from `assets/` |
| Description / questions | Baseten, `meta-models/muse-spark-open-1026` (defaults in `Settings.kt`) | `POST {llm}/chat/completions` (OpenAI-compatible), temperature 0.2, budgets 1,200 / 200 tokens, 400 chars (`HttpLlmClient.kt`) |
| Pictograms | Meta Muse Image (`muse-image-1.0`) | `POST {meta}/images/edits` (multipart) → PNG |

## Setup

1. **An LLM key** exposing `chat/completions` (base URL + model) — entered in
   **Settings**, stored encrypted (Keystore), or provided at build time via
   `local.properties` (gitignored). Without a key, the app says so and makes no calls.
2. **A SAM 3.1 segmentation endpoint** — *Base URL* field in Settings. The endpoint
   actually called, in this order: a manual URL (outside the presets) → a URL injected
   at build time for that region → the **region** preset (Europe `sam3-eu-a100` or
   United States) (`resolveSamEndpoint`,
   `core/src/main/java/dev/sam3vision/core/settings/SamRegion.kt`). Without
   any reachable endpoint, the photo goes straight to the LLM. Switching regions powers
   nothing on.
3. **Optional**: ElevenLabs key (voice + Scribe fallback), Meta key (dictation **and**
   pictograms — same key). Without a voice key, recurring phrases still play from the
   local English pack; dynamic answers are silent and **no error is shown** — the
   documented gotcha in `docs/TESTING.md`. The **Google Maps API key** field still
   exists in Settings, but nothing reads it: opening Google Maps is an intent and
   needs no key.

## Build and test

Prerequisites: JDK 17 + Android SDK (`JAVA_HOME`, `ANDROID_HOME`), Python 3 (guards),
Node (companion page, no `npm install`).

Android is **never compiled on a workstation**: `build-serveur <worktree> [gradle-task]`
ships the sources to the r630 build machine (default `assembleDebug`) and the APK lands
in `builds/<name>/…/app-debug.apk` there. The workstation debug keystore is authoritative
(a copy sits on the r630) — every machine signing APKs for the test phones must use the
same one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` otherwise).

```bash
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest   # JVM tests (6,130 @Test: 3,735 + 2,395 — run in CI, never locally)
./gradlew :app:assembleDebug                               # debug APK (server build only, see above)
python3 tools/check-kotlin-limits.py                       # file < 500 lines, function < 50 (Kotlin only)
python3 tools/check-isee-design.py                         # mandatory Portrait design
python3 tools/check-budget-derive.py                       # doc ↔ code latency figures
python3 tools/check-voice-nav-doc.py                       # doc ↔ code voice navigation
python3 tools/check-voicepack.py                           # voice pack catalog ↔ clips (CI gate)
python3 tools/check-masques.py                             # mask transport contract
python3 tools/check-ws-runbook.py                          # WS budgets ↔ code
bash scripts/test_scripts.sh                               # 230 shell tests
node --test suivi-parent/test/*.test.mjs suivi-relais/test/*.test.mjs  # 69 tests
python3 server/scripts/test_units.py                       # backend unit tests (960, stdlib stubs, no GPU)
bash scripts/build.sh                                      # assembleDebug --offline + APK check
python3 tools/count-tree.py                                # the Figure table below
```

Every iSee test runs on a build of `origin/master` HEAD — align, rebuild, then test
(a "regression" on a green master is a stale build until proven otherwise). The real
phone (Redmi `2c6852ac`, Samsung fallback) goes through the shared `phone` lock
(`phone status`, then `phone run --wait 600 --task "…" -- <adb…>`); anything needing
neither phone nor glasses runs on the `isee-sim` emulator with a video-file camera
(`scripts/isee-sim.sh`, no phone lock). Recipes: `scripts/install-phone-latest.sh`,
`scripts/install-emulator.sh`, `docs/TESTING.md`.

## Figures

Measured on `origin/master` (`995c42223`), 04/11/2026 — `python3 tools/count-tree.py`
prints exactly this table (`--json` for scripts). The census is a script, not a habit:
recopied numbers had drifted three times in one day.

| Mesure | Valeur | Commande |
|---|---|---|
| Fichiers `.kt` | **1448** | `find . -name '*.kt' -not -path './.git/*' -not -path '*/build/*' \| wc -l` |
| Lignes Kotlin | **189,969** | idem, `-exec cat {} + \| wc -l` |
| Lignes de prod (`*/src/main`) | **93,915** — `:core` 297 f. / 41,047 l. · `:app` 415 f. / 52,868 l. | idem sous `core/src/main`, `app/src/main` |
| `@Test` JVM | **6,130** — `:core` 3,735 · `:app` 2,395 | `grep -rho '@Test' core/src/test app/src/test --include='*.kt' \| wc -l` |
| Tests instrumentés | **10** (appareil requis) | idem sous `app/src/androidTest*` |
| `@Preview` Compose | **11** | idem, `@Preview` |
| Backend Python (`server/backend`) | **47** fichiers, **10,328** lignes | `find server/backend -name '*.py' \| wc -l` |
| Tests backend | **960** OK (`test_units.py`) | `python3 server/scripts/test_units.py` |
| Docs | **68** fichiers dans `docs/` | `ls docs/*.md \| wc -l` |
| Workflows CI | **8** | `ls .github/workflows/*.yml \| wc -l` |
| Plus gros fichier de prod | `app/src/main/java/dev/sam3vision/app/glasses/GlassesController.kt` — **499** lignes | `find core app -path '*/src/main/*' -name '*.kt' \| xargs wc -l \| sort -rn \| head` |

Details (constants, budgets, thresholds) are in `docs/PERF.md`; recipes in
`docs/TESTING.md`. The guards are green on this tree; Gradle tests are the only ones not
replayed by the census (`docs/TESTING.md` §2 gives the recipe).

## Crossing: two different measurements (02–03/11/2026)

Two test families exist and must not be mixed: **crop classification** (does the
gate read this crop as red/green?) and the **end-to-end video benchmark** (what
verdict does the whole pipeline emit over 146k consecutive frames?). Both are
reported below.

The earlier claim — *"`PedLightGate` says green 1 time out of 6 true green signals"* —
was measured on **29/10** with `ELONGATION_MIN = 1.5`. That constant is **1.0 since
31/10**, and the gate adds `WIDE_FILL_MAX = 0.35` and
`GREEN_MIN_WIDTH_FRACTION = 0.04`. The 1/6 figure is **obsolete** and must not be
quoted as current.

### A. Crop classification (`docs/MESURES-FEU-ROUGE-VERT.md`, `docs/MESURES-FEU-TEMPOREL.md`)

| Test set | Result | Date |
|---|---|---|
| Phone crops, 48 px | **20/20 green, 0 false green** | 02/11 |
| Anchor crops | **22/22** | 02/11 |
| French day-light set | **133/183 green = 72.7 %** (49 of the 50 misses are the *gate*, not the colour) | 02/11 |
| Red crops | **1/473 read green = 0.21 %** | 02/11 |
| Jittered 96 px crops | 201/240 = 83.8 %, but **22/40 red crops read green (55 %)** | 02/11 |
| Sliding vote 3-of-5 | **370/400 greens, 15 false greens = 2.6 %**, 180 ms p50 | 02/11 |

**Safety is not carried by the colour gate alone.** On the jittered 96 px set, 22 of
40 red crops can read green, so the shape veto (`CarHousingVeto`, housing h/w ≥ 3.0) and the
pedestrian-only prompt filter are part of the contract. SAM itself: generic prompt
`pedestrian traffic light` finds **87.5 % (14/16)** of signals; a state prompt drops to
12.5 %, so state prompts are a cross-check, never the detector
(`docs/MESURES-SAM3-FEUX.md`). End-to-end cost: **0.71 s/image** for four prompts.

### B. End-to-end video benchmark — AVANT (03/11/2026)

Protocol (`BENCHMARK-FEUX-AVANT-APRES.md`, on the workstation, not yet versioned):
**58 Samsung 4K60 videos** from 02/11, 15:01–16:15, **146,569 frames**, every frame
sent at 60 fps, JPEG **q85 / 640 px**, 4 light wordings, `max_objects=16`,
`work_edge=640`, dedicated Modal app (`max_containers=10`, `max_inputs=1`), code
`fb2497db`.

| Metric | AVANT baseline |
|---|---|
| Videos / frames | **58/58**, **146,569** |
| Frames with a light detected | **144,117 = 98.3 %** |
| Red / green verdicts | **126,829 (88.0 % of lit frames)** / **17,288** |
| No light | **2,452 = 1.7 % of all frames** |
| Errors | **0** |
| Server latency p50/p95 | **46 / 53 ms** |
| Client latency p50/p95 | **146.7 / 180.3 ms** |
| Flicker (R↔V flips per 1000 frames) | **25.9** |
| Run cost | ~56 min at 10 lanes, ~**$13** (+ ~$4 rerun of 7 videos) |

**APRÈS has not been run yet.** The verdict rule is fixed in advance: *better* =
flicker down **without** `avec_feu` down; *worse* = the inverse, or any error.

**This benchmark has no ground truth.** It reports descriptive rates, not precision or
recall. It is also **optimistic versus the phone**: 640/q85 instead of 320/q55,
`work_edge=640` instead of 512→320, 16 objects instead of 32, 4 prompts instead of 16
concepts, no masks and no depth. Verdicts are raw (no temporal smoothing); production
adds the 3-of-5 vote. Flicker has two proven mechanisms: `best_pick` alternating
between two lights, and the colour gate flipping at ±4 px of framing.

Known limits, stated rather than hidden: a crop that mixes both lamps is "wait"; two
fully visible lights of ambiguous shape are resolved by the stream, not by one frame;
the `1.0f` elongation is a traced debt (`docs/CALIBRAGE-FEU.md`).

## Glasses: current stream and the old measurement

The production stream is **24 fps requested, MEDIUM 504×896 H.265**, with a
`[24, 15]` ladder and a LOW fallback above 5 % frame drops
(`app/src/main/java/dev/sam3vision/app/glasses/GlassesStreamConfig.kt`). 24 fps is on the SDK's frame-rate grid, so
the request is actually honoured; at the SDK's fixed 750 kbit/s, 24 fps MEDIUM gives
**0.069 bit/pixel**, better than the old 15 fps HIGH (0.054).

The last full link→pixel measurement (**01/11/2026**, Redmi `2c6852ac`, 60 s, n=1123)
was taken in the **old HIGH / 15 fps** configuration: p50/p95/p99 =
**57/111/158 ms** (radio jitter p95 77 ms), so the B2 target (< 100 ms p95) was
**not held**. It has **not been re-measured** in the 24 fps / MEDIUM configuration —
treat the numbers as historical, not as a guarantee. Protocol:
`docs/LUNETTES-RECETTE-TEL.md` §3/§7; verdict script:
`scripts/parse_glasses_link.py`.

The "glasses cut the session instantly" failure on Xiaomi was the SDK 1.0.1 vs runtime
1.0.0 mismatch: the session started and died after ~70 ms with *"Your glasses need an
update"*. The tree pins `mwdat` **1.0.0** (`DatPinTest` guards it). The
"glasses connected → GPU unreachable" failure was DNS: the glasses' internet-less Wi-Fi
became the default route while sockets stayed on cellular, so `modal.run` resolution
failed 14 times with no retry. `RoutedDns` + a single handover retry fixes it
(`core/src/main/java/dev/sam3vision/core/net/BackendRoute.kt`, `Errors.kt`).

## Performance, as measured

| Path | Figure | Date / protocol |
|---|---|---|
| Samsung end-to-end | **229 ms mean, 379 max**, N=2166 | 31/10, `parse_isee_lat.py` |
| Redmi end-to-end | **343 ms mean, 722 max**, N=323 | 01/11, `parse_isee_lat.py` |
| Server SAM (benchmark AVANT) | p50 **46 ms**, p95 **53 ms** | 03/11, 58 videos / 146,569 frames |
| Client pipeline (benchmark AVANT) | p50 **146.7 ms**, p95 **180.3 ms** | 03/11, same campaign |
| R/V flips (benchmark AVANT) | **25.9 per 1000 frames** | 03/11, same campaign |
| GPU A100-40GB EU | **$2.415/h**, ~$0.02 per boot | tariff, not re-checked |

The product target is **300 ms mean / 500 ms max**. It is held on the Samsung
measurement and **not held** on the Redmi one; do not generalise either number.

## CI and merge queue

- **PRs**: `ci` (guards + JVM tests + backend unit tests + goldens), `ci-budget`
  (≤ 5 min per job) and `couche0` (declared `## Scope`, forbidden patterns) —
  all three **required**; a docs PR skips Gradle and gets its verdict in ~45 s wall
  time (measured 02/11 on docs-only #1002 and #955).
- **Merge queue**: **Mergify** (`.mergify.yml`, batches 1–3, rebase, 20 min check
  timeout). GitHub's native queue is unavailable — it needs an organisation-owned
  repository, and the API answers `422 Invalid rule 'merge_queue'` on a user
  repository. `pr-auto-merge.yml` is both the **queue trigger** (it comments
  `@mergifyio queue`) and the sweeper (conflicts, failed-run retries);
  `auto-approve.yml` approves the runs parked in `action_required` by bot pushes.
  `master` is protected (PR mandatory).
- On `master`: `ci-native` (debug + release APK + lint, 10 min), Modal redeploys
  (`deploy-live-fast` only on a `[deploy-gpu]` push that touches the backend;
  `deploy-valhalla-cpu` on any push touching `valhalla_cpu.py`), and
  `triage-rouge` (post-CI triage, informational).
- **Self-hosted** runners `[self-hosted, linux, x64, vision]` with a warm Gradle cache for
  every Gradle job; the two Modal redeploys run on `ubuntu-latest`.

Detail: `docs/CI-QUEUE.md`.

## Contributing

One fresh worktree per task, from a fresh `origin/master` — never edit the shared
checkout (`AGENTS.md` at the repo root is the enforceable rulebook):

```bash
SAM_REPO=/home/baptiste/vision sam-wt new <topic>   # → /home/baptiste/tmp/…
# small, frequent commits; green CI; sam-wt merge <topic> (queue merge)
# once the PR is MERGED: delete the task branch locally + remotely
```

Every Kotlin file < 500 lines, every function < 50 lines (`check-kotlin-limits.py`);
an unmeasured figure is written "not measured". PRs carry a `## Scope` section and
merge through the Mergify queue once `ci`, `ci-budget` and `couche0` are green
(`docs/CI-QUEUE.md` details the process).

## Docs

- **Start here**: `docs/ARCHITECTURE.md` (modules, flows, HTTP contract, keys),
  `docs/TESTING.md` (recipes and test inventory), `docs/DOC-ETAT.md` (documentation
  status, last full pass 29/10/2026), `docs/WS-RUNBOOK.md` (live budgets and
  invariants), `NOTES.md` (versions, decisions), `CHANGELOG.md` (journal since
  26/10/2026).
- **Vision, distances, crossing**: `docs/LIVE-SPEC.md`, `docs/LATENCE-BUDGET.md`,
  `docs/MASK-DECODE.md`, `docs/DISTANCES.md`, `docs/DEPTH.md`, `docs/POSE-IMU.md`,
  `docs/TRAVERSEE.md`, `docs/VEHICLE-BACKGROUND.md`, `docs/MESURES-SAM3-FEUX.md`,
  `docs/CALIBRAGE-FEU.md`, `docs/COLLECTE-FEUX.md`, `docs/DATASETS-FEUX.md`,
  `docs/RECETTE-FEUX-FR.md`, `docs/RECETTE-DISTANCES.md`, `docs/RECETTE-RUE.md`,
  `docs/MESURES-FEU-ROUGE-VERT.md`, `docs/MESURES-FEU-TEMPOREL.md`,
  `docs/RECETTE-FEU-EXPOSITION.md`.
- **Latency, tracking, prediction**: `docs/KEYFRAME-SCHEDULER.md`, `docs/L13-FUSION.md`,
  `docs/LATENCE-M1-HARNESS.md`, `docs/LATENCE-M1-TRANSPORT-BINAIRE.md`,
  `docs/LATENCE-M1-VECTEURS.md`, `docs/LATENCE-M2M3-SERVEUR-INFRA.md`,
  `docs/MASQUES-DEBUG.md`, `docs/MOTION-EGO.md`, `docs/T8-PREDICTION.md`,
  `docs/TRACK-PERF.md`.
- **Voice, narration, navigation**: `docs/ELEVENLABS-TTS.md`, `docs/META-ASR.md`,
  `docs/VOICEPACK.md`, `docs/VOIX-ANGLAIS.md`,
  `docs/VOIX-DANGER.md`, `docs/MICRO-VERROUILLE.md`, `docs/TEST-LONGPRESS-DICTEE.md`,
  `docs/NAVIGATION.md`, `docs/NAVIGATION-VOCALE.md`, `docs/RECETTE-NAVIGATION-VOCALE.md`,
  `docs/VOICE_NAV_PROXY.md`, `docs/GPS-FUSION.md`, `docs/GPS_GUIDE_UTILISATEUR.md`,
  `docs/GUIDAGE-TOUT-DROIT.md`.
- **Glasses and product**: `docs/META-GLASSES.md`, `docs/META-IMAGE.md`,
  `docs/GLASSES-ETAT.md`, `docs/GLASSES-DECISION.md`, `docs/LUNETTES-RECETTE-TEL.md`,
  `docs/LUNETTES-LANCEMENT.md`, `docs/LUNETTES-META-CADRAGE.md`,
  `docs/LUNETTES-VANGUARD.md`, `docs/glasses-dat-spike.md`, `docs/glasses-spike-ci.md`,
  `docs/DEMO-LUNETTES.md`, `docs/ISEE-DESIGN-PORTRAIT.md`, `docs/MODE-TAP.md`,
  `docs/MODE-US.md`, `docs/CARTE-OSM.md`, `docs/NETTOYAGE.md`, `docs/CLES-DU-BUILD.md`,
  `docs/CI-QUEUE.md`, `docs/OPTIMISATIONS.md`, `docs/WS-RUNBOOK.md`.
- **Reviews, stability**: `docs/REVUE-V4.md`, `docs/STABILITE-ISEE.md`.
- **Server and companion**: `server/README.md`, `server/backend/README.md`,
  `server/docs/`, `suivi-parent/README.md`, `suivi-relais/README.md`.

## License and security

Proprietary — all rights reserved, see `LICENSE` (copying, modifying or
distributing this project without prior written authorisation is forbidden).
Third-party code: `NOTICE`. **Never a public issue for a
vulnerability** (`SECURITY.md`). No key is versioned: `local.properties` is gitignored,
keys live in the Android Keystore or the repo secrets (read by the CI's "build keys"
step). **By default the APK embeds the keys** so a fresh install has nothing to type
(`docs/CLES-DU-BUILD.md`): an APK is extractable — hand it only to the people you lend
those keys to, never publish it.
