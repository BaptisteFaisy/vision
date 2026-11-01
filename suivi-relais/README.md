# Tracking relay — Worker + D1

The relay the app uses **by default** (`Settings.DEFAULT_PARENT_SITE_URL`).
It replaces the abandoned Vercel-site API in `suivi-parent/`: that deploy was
frozen on a 27/10 commit and refused every write without a key the
phones do not have (403 "invalid key"), so the loved one's map stayed empty
without a word.

## Contract (unchanged for the page embedded in the APK)

```text
POST /api/share   {shareId, writeKey, fix?:{lat,lon,accM,headingDeg,atMs,indoor},
                   objects:[{label,distM,angleDeg}], scene:{text,fromUser,speaking},
                   photo:{jpeg,atMs}, trip:{destination,totalM,remainingM,remainingS,progress}}
GET  /api/state?share=<id>            → {ok, live, ageMs, fix?, objects, scene, photo, trip, serverAt}
GET  /health                          → {ok:true}
GET  /ws?share=<id>&role=watch        → WebSocket: binary JPEG frames of the loved one
GET  /ws?share=<id>&role=push&key=<k> → WebSocket: the loved one pushes their frames
```

## Live: `/ws`, one room per stream (`src/durable.js`)

The slow state (position, sentence, trip) goes through D1; **the live picture
never touches the base** — a Durable Object acts as the room, and frames flow
from the phone to the connected caregivers without being written anywhere.
It is the only way to hold a true live stream: at 6 fps, D1 and its write
quota would never keep up (100,000 writes/day, i.e. **2.5 h** of live, and
345,000 requests for the same duration Worker-side).

```text
phone ──binary frames──▶ RelayRoom (DO, one per id) ──▶ caregivers

phone ──{"type":"camera","on":…}──▶ RelayRoom ──{"type":"camera","on":…}──▶ caregivers
                                      │
                                      └─▶ {"type":"audience","watch":n}  (to the phone)
```

- **The camera tells when it turns off**: the phone announces
  `{"type":"camera","on":…}` on every change and the room forwards it to
  caregivers; when the last phone leaves (close, crash, network), the
  room itself emits `{"type":"camera","on":false}`. The page clears the
  picture at once — live and slow photo alike — instead of keeping the last
  picture up to 8 s. A caregiver arriving with no phone learns at once that
  the camera is off; only a `push` speaks for the camera (a caregiver
  cannot turn off the others' picture).
- **The phone only emits while watched**: on every arrival or departure
  (caregiver or phone), the room republishes `{"type":"audience","watch":n}`
  to the connected phones; at `n = 0`, the camera goes quiet (plan,
  battery). A phone joining an already watched room thus learns at once
  that it is watched, instead of staying mute.
- **A frame never returns to its sender** and is never re-read from base;
  past 512 KB (`MAX_FRAME_BYTES`), it is ignored.
- **Writes stay protected**: `role=push` requires the stream key when the
  stream has one (same rule as `/api/share`, bound on first send) — a guessed
  id is not enough to broadcast a fake camera to the caregiver.
- **Hibernation**: the room uses the hibernation API (`acceptWebSocket` with
  a role tag), so a still caregiver costs no duration. On the free tier
  (100,000 requests/day, WebSocket messages counted at 1/20), one hour
  of live costs ~1,900 requests: far below the cap.
- **Fallback**: the page keeps the slow photo (`/api/state`, one frame every
  2 s) when the WebSocket is unavailable. A deploy without a `RELAY_ROOM`
  binding answers `503 {"ok":false,"error":"streaming not deployed"}` and the
  page keeps working.

- `fix` (position), `photo` (base64 JPEG ≤ 120,000 chars) and `trip`
  (0..1 progress) are optional: a phone without GPS still publishes
  its camera, sentence and landmarks, and an older device keeps
  sending position, objects and sentence. A write **without** a picture
  (screen-off tracking service) keeps the last photo and its `atMs`: the
  page hides it instead of passing it off as live. A missing `trip` removes
  the progress bar.

- **One state per stream id**: two tracked loved ones = two `?share=`, two
  separate rows. Several caregivers may read the same id, none
  may write instead of another (`caregiverRole` blocks it app-side).
- `live` lasts **12 s**; past that the page moves to SIGNAL LOST then OFFLINE,
  keeping the last position (`ageMs`, `serverAt`).
- **No key to type**: the write key binds to the stream on first send
  (`write_key` in base). A different key later → 403, which stops a
  guessed id from injecting a fake position. The app generates this key at
  install (`defaultShareKey`); it is never shown nor shared.
- `SHARE_WRITE_KEY` (Worker secret) stays available as a global lock: when
  set, every write must carry that key.

## Storage

D1, table `streams (id, doc, updated_at, write_key)`, schema created on first
request. A stream is kept 24 h then pruned (one write in 200).

## Check

```bash
node --test suivi-relais/test/relais.test.mjs
```

Tests cover: two-stream isolation, keyless write, stream-bound key
(403), optional global lock, invalid bodies, keyless read, state
age, keyless-position publishing and text capping.

## Deploy

The Worker ships through the `deploy.json` manifest (ESM modules + `DB`
binding). The production URL is copied into `Settings.DEFAULT_PARENT_SITE_URL`
on the `:core` side; changing it requires a fresh APK.

**Deploying the room**: `npx wrangler login`, then `npx wrangler d1 list`
(note the base id and paste it into `wrangler.toml`), then `npx wrangler
deploy` from this folder. This machine's wrangler token expired on
6/10/2026 — no deploy is possible before logging in again.

**Production URL (29/10): `https://isee-suivi.duello.workers.dev/`** — the
Worker itself: `/health` → `{"ok":true}`, `/api/state?share=…` → the stream
state, and it also serves the WebView page (`/`, `live.mjs`, `styles.css`).
The prettier-looking domain picked earlier (`…freebuff.page`) returned **502
"Site unavailable"** on the evening of 29/10 while this Worker still
answered: the caregiver page stayed on NO STREAM and the loved one's camera
no longer showed. A relay serving people points at infrastructure one
deploys, not at a redirect. Check after every deploy:

```bash
curl -s https://isee-suivi.duello.workers.dev/health      # {"ok":true}
curl -s "https://isee-suivi.duello.workers.dev/api/state?share=verif"   # {"ok":true,"live":false}
```
