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
>   OkHttp, ONNX Runtime, TensorFlow Lite, PyTorch, FastAPI
> - **Meta Wearable Device Access SDK** — camera and microphone access on Ray-Ban Meta
>   glasses
>
> Full model attributions and licences: `app/src/main/assets/NOTICE-depth.txt`.

# iSee — eyes that speak

iSee is an Android app that helps a **blind or low-vision person** understand
their surroundings: the phone films, one AI **segments** what it sees
(objects, people, doors), another AI **describes** the scene and answers
questions, and the phone **speaks** — distances, walking instructions,
guidance. A **companion** can follow along remotely, live, from their browser.

> This repository (`BaptisteFaisy/vision`) carries the whole product: the Android
> app, the segmenting GPU backend, and the companion page. Every claim below is
> re-checked against the code in this tree; every figure was measured on
> **29/10/2026** (command given) on `master`. Figures held over time live in
> `docs/PERF.md` — the only authoritative reference.

## The problem

Seeing means perceiving, understanding, and acting — three things that require
sight:

1. **Perceive**: what's around me, where, how far, what's moving?
2. **Understand**: describe the scene, answer "what's that?", guide to a
   destination.
3. **Accompany**: let a companion follow remotely, without calling all the time.

## The solution

An ordinary phone, a remote GPU card, and voice as the only interface:

1. **Film** — the camera runs; tap = start/stop video, long-press = ask a spoken
   question. No menu to see, no swiping.
2. **Segment** — each frame goes to SAM 3.1 on a Modal GPU (Europe), which returns
   objects, boxes, and masks, in photo and live alike.
3. **Measure** — distances are computed **on the phone** (pinhole + on-device
   depth), with no GPU and no request.
4. **Describe and answer** — a vision LLM describes the scene and answers questions,
   with no history carried over from one launch to the next.
5. **Speak and guide** — synthetic voice, distance pills, walking instructions,
   navigation to Google Maps — while the companion follows on their page.

```
┌──────────────┐  images   ┌──────────────────┐  objects+masks   ┌──────────────┐
│    Phone     │ ────────► │  GPU Modal (EU)  │ ───────────────► │  LLM vision  │
│ camera+voice │           │  SAM 3.1 live    │                  │ description  │
└──────────────┘           └──────────────────┘                  └──────────────┘
       │                            │                                  │
       │  on-device distances       │  one frame in flight             │  voice (TTS)
       ▼                            ▼                                  ▼
┌──────────────┐           ┌───────────────────────────────────────────┐                  ┌──────────────┐
│ iSee speaks  │           │  Companion (browser, real-time tracking)  │                  │ Maps routing │
└──────────────┘           └───────────────────────────────────────────┘                  └──────────────┘
```

## What the app can do

- **iSee home** — a glass bubble on a white background, the camera blended above it,
  only the current sentence below the bubble. Tap = video, long-press (300 ms) =
  dictation then a spoken answer; no swiping, no stored history
  (`app/isee/`, `docs/ISEE-DESIGN-PORTRAIT.md`).
- **Live** — 6 tracked concepts (`person`, `door`, `bicycle`, `car`, `truck`,
  `electric scooter`), one frame in flight over WebSocket `/live/ws`, pills,
  distances, and voice announcements (`docs/LIVE-SPEC.md`).
- **Photo → description → questions** — the original screens (Capture, Analyse,
  Chat, Direct, Tap) stay reachable from Settings ("Other screens").
- **Tap mode** — film briefly, get a spoken summary. `POST /video` client written
  and tested; the server route is implemented nowhere here (`docs/MODE-TAP.md`).
- **On-device depth (beta)** — a proximity map 100% on the phone (Depth Anything V2),
  model downloaded once, fused with the masks (`docs/DEPTH.md`).
- **Voice navigation** — "emmène-moi à X" opens Google Maps in walking mode; Valhalla
  router built into the app and CPU backend (`docs/NAVIGATION-VOCALE.md`).
- **Walking guidance** — "tout droit" instructions, obstacles and crossings announced
  by voice (`docs/GUIDAGE-TOUT-DROIT.md`).
- **Crossing mode** — reads the pedestrian light on-device, vetoes approaching
  vehicles, and speaks when to cross (`docs/TRAVERSEE.md`).
- **Ray-Ban Meta glasses** — glasses camera and mic (DAT SDK 1.0.0), test bench built
  into the app (`docs/META-GLASSES.md`).
- **Companion follow-up** — a share link to a real-time page: current sentence, nearby
  objects, map (`suivi-parent/README.md`).

## Repository contents

| Block | Role |
|---|---|
| `:core` | Android library (`dev.sam3vision.core`, minSdk 26): network clients (LLM, SAM, live WebSocket, tracker, video), pacing, masks, distances, pure depth, gestures, voice, encrypted settings |
| `:app` | The APK (`dev.sam3vision.app`, minSdk 29): Compose + CameraX, iSee home, legacy screens, glasses + bench, depth (ONNX), segmentation (LiteRT), capture purge |
| `server/` | Modal backend (Python): live/photo (`live_fast`, app `sam3-eu-a100`), Valhalla CPU routing (`valhalla-idf-cpu`), tracker (`track_app.py`, manual deploy), photo legacy (`modal_app.py`), GPU probes — plus `legacy-expo-app/`, the archived old Expo app |
| `suivi-parent/` | Companion page (static, zero npm dependencies) + Vercel relay; embedded in every APK (`caregiverAssets`, `app/build.gradle.kts:179`) |
| `tools/` | CI guards (Kotlin sizes, Portrait design, latency budget, voice navigation), fake LLM/SAM for tests, utilities |
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
| Glasses / on-device AI | Meta `mwdat-*` 1.0.0 (the only version served by the glasses), ONNX Runtime 1.18.0, LiteRT 2.2.0 (`litert-edge`, TwinLite segmentation) |
| Version | `versionName = 1.0.<commits>-<sha>`, `versionCode` = commit date (a stale APK cannot overwrite the phone) |

### `server/` backend (Python, Modal)

| Item | Value |
|---|---|
| Runtime | Python ≥ 3.11, FastAPI (`fastapi[standard]==0.162.0`), Modal CLI 1.6.2 |
| Model | `unsloth/sam3.1` (ungated mirror of `facebook/sam3.1`), `transformers==5.20.2`, `torch==2.14.1`, `torchvision==0.29.1` |
| Deployed | `sam3-eu-a100` (A100-40GB, `eu-west`) from `server/backend/live_fast/` — `/health`, `/segment`, `/live`, `/live/ws`, `/release`; redeployed by CI on every push that touches it, with its levers (CUDA graph, snapshot, `scaledown_window=60`) |
| Routing | `valhalla-idf-cpu` (CPU, zero GPU, Île-de-France tiles) — `/health`, `/route`; deployed by CI |
| Other | `modal_app.py` (photo legacy `/analyze`, `/ask`), `track_app.py` (tracker `/session`, `/frame` — manual deploy), `probe_*.py` (measurement probes, never routinely deployed) |
| Golden rule | **one GPU at a time**: the live camera powers the card on; when the stream is cut, it powers off (`POST /release`); no scheduled task keeps the card on |

### Companion follow-up (`suivi-parent/`)

Static page (HTML/CSS/JS, WebGL shader regenerated from the Android shader),
dependency-free Node Vercel relay, Upstash Redis storage (in-memory fallback in
dev), Google Maps if a key is provided (OpenFreeMap otherwise).

### Voice and LLM

| Role | Provider | Contract |
|---|---|---|
| Dictation | Meta ASR (`muse-voice-transcribe-1.0`), ElevenLabs Scribe fallback | `POST {meta}/asr/transcribe` (16 kHz mono WAV) |
| Voice | ElevenLabs TTS (`eleven_v4_turbo`, Laure voice; US Jade offered) | `POST {elevenlabs}/text-to-speech/{voiceId}` → MP3 |
| Description / questions | Baseten, `meta-models/muse-spark-open-1026` (defaults `Settings.kt:167-168`) | `POST {llm}/chat/completions` (OpenAI-compatible), temperature 0.2, budgets 1,200 / 200 tokens, 400 chars (`HttpLlmClient.kt:21-24`) |

## Setup

1. **An LLM key** exposing `chat/completions` (base URL + model) — entered in
   **Settings**, stored encrypted (Keystore), or provided at build time via
   `local.properties` (gitignored). Without a key, the app says so and makes no calls.
2. **A SAM 3.1 segmentation endpoint** — *Base URL* field in Settings (empty = the
   **region** preset below, Europe `sam3-eu-a100` or United States, provides the URL;
   a build-injected URL wins over the preset — `SamRegion.kt:50-56`). Without any
   reachable endpoint, the photo goes straight to the LLM. Switching regions powers
   nothing on.
3. **Optional**: ElevenLabs key (voice + dictation fallback), Meta ASR key (dictation),
   Google Maps key (companion map). Without a voice key, nothing is announced aloud
   and **no error is shown** — the documented gotcha in `docs/TESTING.md`.

## Build and test

Prerequisites: JDK 17 + Android SDK (`JAVA_HOME`, `ANDROID_HOME`), Python 3 (guards),
Node (companion page, no `npm install`).

```bash
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest   # JVM tests (2,709 @Test)
./gradlew :app:assembleDebug                               # debug APK
python3 tools/check-kotlin-limits.py                       # file < 500 lines, function < 50
python3 tools/check-isee-design.py                         # mandatory Portrait design
bash scripts/test_scripts.sh                               # 101 shell assertions
node --test suivi-parent/test/session.test.mjs suivi-parent/test/relay.test.mjs
bash scripts/build.sh                                      # assembleDebug --offline + APK check
```

## Figures (measured 29/10/2026 on `master`)

| Metric | Value | Command |
|---|---|---|
| `.kt` files | **876** | `find . -name '*.kt' -not -path './.git/*' -not -path '*/build/*' \| wc -l` |
| Kotlin lines | **93,871** | same `find`, `-exec cat {} + \| wc -l` |
| Prod lines (`*/src/main`) | **52,815** — `:core` 211 files / 21,749 lines · `:app` 277 files / 31,066 lines | same under `core/src/main`, `app/src/main` |
| JVM `@Test` | **2,709** — `:core` 1,765 · `:app` 944 (incl. 95 glasses bench) | `grep -rho '@Test' <dir> --include='*.kt' \| wc -l` |
| Instrumented tests | **10** (device required) | same on `app/src/androidTest*` |
| Python backend | **34** files, **6,689** lines | `find server/backend -name '*.py' …` |
| Guards | all green (sizes, Portrait, budget, voice, ci-budget, scripts, goldens, relay) | see `docs/TESTING.md` §2 |

Details (constants, budgets, thresholds) are in `docs/PERF.md`; recipes in
`docs/TESTING.md`.

## CI and merge queue

- **PRs**: a single `ci` job (guards + JVM tests) + `ci-budget` (≤ 5 min) + `couche0`
  (declared `## Scope`, forbidden patterns) — all three **required**, verdict in
  minutes, a docs PR in seconds (Gradle skipped).
- Native `pr-auto-merge` queue: rebase, conflict-resolution agent, merge as soon as
  the 3 checks are green. `master` is protected (PR mandatory).
- On `master`: `ci-native` (debug + release APK + lint), Modal redeploys
  (`deploy-live-fast`, `deploy-valhalla-cpu`).
- **Self-hosted** runners `[self-hosted, linux, x64, vision]`, warm Gradle cache.

Detail: `docs/CI-QUEUE.md`.

## Docs

- **Start here**: `docs/ARCHITECTURE.md` (modules, flows, HTTP contract, keys),
  `docs/PERF.md` (held figures), `docs/TESTING.md` (counts, recipes),
  `docs/DOC-ETAT.md` (each doc's status), `NOTES.md` (versions, decisions),
  `CHANGELOG.md` (journal since 26/10/2026).
- **Live**: `docs/LIVE-SPEC.md`, `docs/LATENCE-BUDGET.md`, `docs/TRACK-PERF.md`,
  `docs/T8-PREDICTION.md`, `docs/KEYFRAME-SCHEDULER.md`, `docs/L13-FUSION.md`.
- **Voice and navigation**: `docs/ELEVENLABS-TTS.md`, `docs/META-ASR.md`,
  `docs/NAVIGATION-VOCALE.md`, `docs/NAVIGATION.md`, `docs/VOIX-POSITIONS.md`,
  `docs/VOIX-ANGLAIS.md`, `docs/TEST-LONGPRESS-DICTEE.md`.
- **Imaging**: `docs/MASQUES-DEBUG.md`, `docs/MASK-DECODE.md`, `docs/DISTANCES.md`,
  `docs/DEPTH.md`.
- **Glasses**: `docs/META-GLASSES.md`, `docs/GLASSES-ETAT.md`,
  `docs/GLASSES-DECISION.md`, `docs/LUNETTES-LANCEMENT.md`.
- **Product**: `docs/MODE-TAP.md`, `docs/MODE-US.md`, `docs/ISEE-DESIGN-PORTRAIT.md`,
  `docs/GUIDAGE-TOUT-DROIT.md`, `docs/TRAVERSEE.md`, `docs/MICRO-VERROUILLE.md`,
  `docs/NETTOYAGE.md`, `docs/OPTIMISATIONS.md`, `docs/CLES-DU-BUILD.md`,
  `docs/CI-QUEUE.md`.
- **Server**: `server/README.md`, `server/backend/README.md`, `server/docs/`
  (contracts, latency, deployment).
- **Companion**: `suivi-parent/README.md`.

## License and security

MIT — see `LICENSE`. **Never a public issue for a vulnerability** (`SECURITY.md`).
No key is versioned: `local.properties` is gitignored, keys live in the
Android Keystore or the repo secrets (read by the CI's "build keys" step).
