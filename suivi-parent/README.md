# Caregiver tracking — shared iSee session

> **Abandoned site (2026-10-28).** The relay the app uses by default is
> `../suivi-relais/` (Worker + D1); this Vercel deployment stayed frozen at
> 27/10 9:03 PM and refused writes from fresh installs (403). This
> folder now only serves as the source of the page embedded in the APK (Gradle
> task `caregiverAssets`) and as a reference for the old contract.

Mobile page used by the link sent to the loved one and by the Android Caregiver
WebView: white background, cocoa-and-caramel Portrait orb on top, the
**accompanied phone's picture** at the bottom (latest camera frame, 480 px, full
width fading into white like the street on the accompanied side), **drawings of
the recognized objects** and, during a guided trip, a **liquid progress bar**
from start to destination. No sentence under the orb.

The Android UI ships in every APK from this folder (Gradle task
`caregiverAssets`). The WebView only intercepts these local files under
`/.isee-caregiver/`; `/api/state` calls stay on the shared link's origin. An
older version of the site can therefore never replace the installed app's
design.

The screen fits the phone height, with no scrolling, no back bar and no
Caregiver title: the orb on top, then objects and trip, then the loved one's
picture filling the bottom to the edge, with no frame or outline, fading into
white upwards (mirroring the street on the accompanied side, which sits on
top). The LIVE / SIGNAL LOST / OFFLINE state lives in a rounded pill at the
top right, sized like the Portrait badge. The black cog opens the Android
settings (received link), with no map-background setting of any kind: the
map was removed.

## Opening the right session on another phone

1. On the accompanied phone: set one stream id per loved one in
   Settings → Share tracking, then share it.
2. The caregiver opens the received link (`https://…/?share=<id>`) in their
   browser, or pastes it into **Settings → THE USER → Invite link or code**
   in their app in the Caregiver role.
3. The page reads only that stream. Without a valid link, no implicit session
   is picked. No position from the caregiver phone is ever requested.

The Caregiver role also forbids Android publishing: neither the Direct screen
nor an old tracking service can send its position instead of the loved one.

## Rendering and data

- The orb reuses the Android Portrait shader, with a cocoa-and-caramel liquid
  (proposal 32 of the color board), a 24%-of-width radius and a 100 CSS px cap.
  From the root, `python tools/export-caregiver-orb.py` regenerates its WebGL
  port from the AGSL source, which stays the single truth: the glass top
  answers in two liquid hues (the palette's deep blue and its gold; here two
  cocoas), with no pink or green, and without washing the orb in color. Cocoa-
  and-caramel glass fallback when WebGL is unavailable (same `--orb-color-*`
  variables).
- No sentence shown: the narration (`session.mjs`, still tested) no longer
  reaches the screen; only the state badge and the camera-area note speak
  (missing link, dead link).
- GPS moves tell forward, back and sideways apart against the previous
  heading. A move must exceed the summed GPS uncertainties and fit a walk. No
  small indoor step or detour is ever claimed from a mere instruction. With no
  position (packet without GPS), no move is claimed. Accuracy stays limited by
  the accompanied phone's GPS.
- Picture: the loved one's camera shows **only while they run it** — LIVE
  stream and frame under 8 s, in `object-fit: cover`, full width to the bottom
  edge, with no frame or outline, fading into white upwards. Anywhere else, no
  frame, picture or text: the section folds and the picture reopens it as soon
  as the camera runs. Only a missing link (« Open the tracking link shared by
  your loved one. ») and a dead link (`NO_STREAM_TEXT`) are written in the
  camera area. The room's camera notice (`{"type":"camera","on":false}`) clears
  the picture at once, live and slow photo alike, without waiting out the 8 s.
- **Live**: when the relay exposes the WebSocket room (`/ws`), the page
  connects (`live-stream.mjs`) and shows frames **as they flow** — 15 fps, a
  picture under 600 ms old counts as "now". The `/api/state` photo (one every
  2 s) stays the fallback when the WebSocket fails or does not exist: the page
  depends on no deployment to work. The room also tells when the camera turns
  on or off (`{"type":"camera","on":…}`, emitted or forwarded from the phone):
  the page clears the picture on `on:false` and resumes on `on:true` or the
  next frame.
- Landmarks: the objects the phone recognized (voice-created pictograms) show
  in a strip, **as drawings** with their name — LIVE stream and detection
  under 8 s, like the picture. No detection, no strip.
- Trip: during iSee guidance, a bar from start to destination. The liquid
  (the orb's liquid gradient) and the ball advance with `progress`; the rest
  reads « 300 m to go · 4 min ». The bar goes away as soon as guidance stops
  and freezes (dimmed) when the stream is no longer LIVE.
- LIVE before 12 s; SIGNAL LOST up to 2 min (objects and position frozen in
  grey); OFFLINE after (no landmarks). No simulation in production.
- NO STREAM when the relay does not know the id (`{ok:true, live:false}`
  without `serverAt`, or stream pruned after 24 h): the camera area says so
  and offers to ask for the link again, instead of endlessly waiting for a
  phone that no longer exists — a stale link (reinstalled app, new id) no
  longer looks exactly like a loved one not yet live.

## Flow

The accompanied phone publishes every two seconds during iSee or Direct:

```text
POST /api/share
{shareId, writeKey, fix?:{lat,lon,accM,headingDeg,atMs,indoor},
 objects:[{label,distM,angleDeg}], scene:{text,fromUser,speaking},
 photo:{jpeg,atMs}, trip:{destination,totalM,remainingM,remainingS,progress}}
GET /api/state?share=<id>
```

`fix`, `scene`, `photo` and `trip` are optional: a phone without GPS
(indoors, starting up, refused permission) still publishes its camera, its
sentence and its landmarks — otherwise the page stayed OFFLINE while the
camera ran; a packet without a position invents no move. Text is capped at
2,000 characters and the base64 picture at 120,000. The
service resumes position alone in the background, without inventing the
objects of a closed camera; the iSee relay then keeps the last picture with
its age, and the page never shows it: a camera shows because it runs,
not because a picture lingers. The relay
uses the site's origin, even when the settings hold a full link with
`?share=`.

## Local check

From the root, with no npm dependency:

```bash
node --test suivi-parent/test/session.test.mjs suivi-parent/test/relay.test.mjs suivi-parent/test/live-view.test.mjs
node suivi-parent/test/preview-server.cjs
```

Preview: `http://127.0.0.1:8836/?share=your-stream`. The local server
uses the Vercel handlers and memory storage isolated from production.
Tests: two independent streams, backward compatibility, object cap,
disconnect, GPS noise, received picture and trip, word highlight.

## Hosting

Vercel root: `suivi-parent/`, « Other » framework, empty build. The
`.mjs` modules, CSS and generated shader are static; `api/` has no npm
dependency.

- `SHARE_WRITE_KEY`: when set, same key in the sender's settings.
- `UPSTASH_REDIS_REST_URL` + `UPSTASH_REDIS_REST_TOKEN`, or the Vercel KV names
  `KV_REST_API_URL` + `KV_REST_API_TOKEN`: storage shared across instances.
  The memory fallback is for development only.

The page no longer depends on any map provider: no MapLibre, no Google
key, no third-party network call. The shown picture is the one the phone
published in `photo` (base64 JPEG frame) and the bar reads `trip`; both
fields are optional, so an older device keeps sharing
position, objects and sentence.
