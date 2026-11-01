import { NO_STREAM_TEXT, shareIdOf, stateOf, neverPublished } from './session.mjs';
import { frameOf, freezeFrameOf, pictosOf, tripOf, tripPercent, tripRemaining } from './live-view.mjs';
import { STREAM_FRESH_MS, watchStream } from './live-stream.mjs';
import { startOrb } from './orb.mjs';
import './settings.mjs';

const shareId = shareIdOf(location.href);
const viewEl = document.getElementById('view');
const frameEl = document.getElementById('view-frame');
const noteEl = document.getElementById('view-note');
const tripEl = document.getElementById('trip');
const tripDest = document.getElementById('trip-dest');
const tripRest = document.getElementById('trip-rest');
const tripTrack = document.getElementById('trip-track');
const tripLiquid = document.getElementById('trip-liquid');
const tripBall = document.getElementById('trip-ball');
const pictoEl = document.getElementById('pictos');
const pictoStrip = document.getElementById('picto-strip');
let live = null, polling = false, frameSrc = '', pictoSig = '';
// Last paint: when, and through which pipe (live or slow photo). When the
// live stream drops, the painted frame stays frozen during the grace instead
// of being torn away — every micro-gap used to flicker otherwise.
let shownAt = 0, showingDirect = false;
// Camera live stream: last frame received over WebSocket, and when.
let streamSrc = '', streamAt = 0;
// The room said the camera is off: live and slow photo stay hidden until
// told otherwise, or a frame proving otherwise.
let cameraOff = false;
/** Live resume: a frame newer than this relights the picture (hysteresis). */
const STREAM_RESUME_MS = 200;
let streamLive = false;
// Latest-only painting: a single frame in flight (offscreen pre-decode), the
// surplus between two painted frames is dropped instead of aborting a decode.
const preload = new Image();
let paintQueued = false, decoding = null;
let paintedCount = 0, droppedPaints = 0, lastPaintAt = 0;
const paintGaps = [];
// No relay answer yet: never announce a dead link without proof.
let known = true;
const STATES = {
  live: ['LIVE', 'green'], lost: ['SIGNAL LOST', 'orange'],
  offline: ['OFFLINE', 'grey'], unknown: ['NO STREAM', 'grey'],
};

function render() {
  const status = known ? stateOf(live) : 'unknown';
  const [label, color] = STATES[status];
  const badge = document.getElementById('live-badge');
  badge.textContent = label;
  badge.className = 'pill ' + color;
  renderFrame(status);
  renderPictos(status);
  renderTrip(status);
}

/**
 * Recognized objects: their drawings in a strip, nothing without a fresh detection.
 * Same landmarks: the strip is not rebuilt, pictures never flicker.
 */
function renderPictos(status) {
  const items = pictosOf(live, status);
  if (!items) {
    pictoEl.hidden = true;
    if (pictoSig) { pictoSig = ''; pictoStrip.replaceChildren(); }
    return;
  }
  pictoEl.hidden = false;
  const signature = JSON.stringify(items.map((item) => item.label));
  if (signature === pictoSig) return;
  pictoSig = signature;
  pictoStrip.replaceChildren(...items.map((item) => {
    const figure = document.createElement('figure');
    figure.setAttribute('role', 'listitem');
    const img = document.createElement('img');
    img.src = item.src;
    img.alt = item.label;
    const caption = document.createElement('figcaption');
    caption.textContent = item.label;
    figure.append(img, caption);
    return figure;
  }));
}

/**
 * Is the live stream showing? Hysteresis: lost after [STREAM_FRESH_MS] with no
 * frame, resumed only on a frame under [STREAM_RESUME_MS] — in between, the
 * shown state is kept instead of flapping live/slow photo.
 */
function streamIsLive() {
  if (!streamSrc) return false;
  const age = Date.now() - streamAt;
  return streamLive ? age <= STREAM_FRESH_MS : age <= STREAM_RESUME_MS;
}

/**
 * What the loved one sees: nothing until their camera runs. A picture kept
 * after a drop would stay on screen while the camera is off.
 */
function renderFrame(status) {
  // Live wins over the slow photo: a fresh frame is the camera running
  // now, not a state frozen by the relay.
  const direct = streamIsLive();
  if (direct) {
    // The live picture is painted by paintStream (offscreen pre-decode,
    // latest-only): render() never touches it — overwriting img.src here would
    // force an out-of-preload decode on every poll and steal one paint.
    noteEl.hidden = true;
    frameEl.hidden = false;
    viewEl.classList.remove('empty');
    frameEl.alt = 'What your loved one sees, live';
    return;
  }
  const view = frameOf(live, status, Date.now(), cameraOff);
  // Live micro-drop: the painted frame stays frozen during the grace,
  // instead of being torn away (whole-page reflow) or swapped with the slow
  // photo (flash + re-swap on return).
  if (freezeFrameOf(frameSrc, shownAt, showingDirect, view)) {
    noteEl.hidden = true;
    frameEl.hidden = false;
    viewEl.classList.remove('empty');
    return;
  }
  if (!view) {
    if (frameSrc) { frameEl.removeAttribute('src'); frameSrc = ''; }
    streamSrc = '';
    frameEl.hidden = true;
    viewEl.classList.add('empty');
    // No waiting text: without a picture, the section folds. Only a missing
    // link and a dead link are written in the camera area (no sentence left).
    if (!shareId || status === 'unknown') {
      noteEl.textContent = !shareId ? 'Open the tracking link shared by your loved one.' : NO_STREAM_TEXT;
      noteEl.hidden = false;
      viewEl.classList.remove('empty');
    } else {
      noteEl.hidden = true;
    }
    return;
  }
  noteEl.hidden = true;
  frameEl.hidden = false;
  viewEl.classList.remove('empty');
  frameEl.alt = 'What your loved one sees, live';
  // An identical data URL is never reassigned: the picture never flickers on each poll.
  if (view.src !== frameSrc) { frameSrc = view.src; frameEl.src = view.src; shownAt = Date.now(); showingDirect = false; }
}

/**
 * One live frame: only the picture repaints. A full `render()` per frame
 * (10 a second) would rebuild the strip, the trip and the badge for nothing.
 *
 * Latest-only: frames coalesce on `requestAnimationFrame` (a single paint
 * per turn), and the picture decodes **offscreen** before the swap — crushing
 * `img.src` on every message aborted the previous decode and stuttered at 10/s.
 */
function paintStream() {
  if (!streamSrc) { render(); return; }
  if (document.hidden) return;
  if (paintQueued) { droppedPaints += 1; return; }
  paintQueued = true;
  requestAnimationFrame(() => {
    paintQueued = false;
    const src = streamSrc;
    if (!src || src === frameSrc) return;
    if (decoding) { droppedPaints += 1; return; }
    decoding = src;
    preload.onload = () => {
      if (decoding !== src) return;
      decoding = null;
      noteEl.hidden = true;
      frameEl.hidden = false;
      viewEl.classList.remove('empty');
      frameEl.alt = 'What your loved one sees, live';
      frameSrc = src;
      frameEl.src = src;
      shownAt = Date.now();
      showingDirect = true;
      notePaint();
    };
    preload.onerror = () => { if (decoding === src) decoding = null; };
    preload.src = src;
  });
}

/**
 * **Displayed** live rate (median and p95 of paint gaps, window of 60):
 * `window.__streamFps` is the page-side proof of 10+ fps.
 */
function notePaint() {
  const now = performance.now();
  if (lastPaintAt > 0) {
    paintGaps.push(now - lastPaintAt);
    if (paintGaps.length > 60) paintGaps.shift();
  }
  lastPaintAt = now;
  paintedCount += 1;
  const sorted = [...paintGaps].sort((a, b) => a - b);
  const median = sorted.length ? sorted[sorted.length >> 1] : 0;
  const p95 = sorted.length ? sorted[Math.min(sorted.length - 1, (sorted.length * 95 / 100) | 0)] : 0;
  window.__streamFps = {
    paints: paintedCount,
    dropped: droppedPaints,
    medianMs: Math.round(median * 10) / 10,
    p95Ms: Math.round(p95 * 10) / 10,
    ips: median > 0 ? Math.round(10000 / median) / 10 : 0,
  };
}

/** The trip in progress: start → destination, the liquid follows the loved one's progress. */
function renderTrip(status) {
  const trip = tripOf(live, status);
  tripEl.hidden = !trip;
  if (!trip) return;
  tripEl.classList.toggle('stale', status !== 'live');
  tripDest.textContent = 'To ' + trip.destination;
  tripRest.textContent = tripRemaining(trip);
  const percent = tripPercent(trip);
  tripLiquid.style.width = percent + '%';
  tripBall.style.left = percent + '%';
  tripTrack.setAttribute('aria-valuenow', String(Math.round(percent)));
  tripTrack.setAttribute('aria-valuetext', tripDest.textContent + ', ' + tripRest.textContent);
}

function receive(snapshot) {
  // Id never published (or stream pruned): say so, instead of waiting forever.
  if (neverPublished(snapshot)) {
    known = false; live = null;
    return;
  }
  // The position may be missing (phone without GPS): camera, sentence and
  // landmarks still pass, only move measurement pauses.
  if (snapshot.ok && Number.isFinite(snapshot.serverAt)) {
    if (live && snapshot.serverAt < live.serverAt) return;
    known = true;
    live = snapshot;
  } else if (snapshot.ok && snapshot.live === false) {
    live = null;
  }
}

async function poll() {
  if (!shareId || polling) return;
  polling = true;
  try {
    const response = await fetch('/api/state?share=' + encodeURIComponent(shareId), {
      cache: 'no-store', signal: AbortSignal.timeout(8000),
    });
    if (response.ok) receive(await response.json());
  } catch { /* The last packet keeps aging: no fake return to live. */ }
  finally { polling = false; render(); }
}

startOrb(document.getElementById('orb'));
render(); poll();
setInterval(poll, 2000);

// Live: the loved one's camera arrives over the room WebSocket. With no room
// (older deploy, network blocking `wss`), the page keeps the slow photo.
if (shareId) {
  watchStream({
    href: location.href,
    onFrame: (src) => {
      cameraOff = false;
      streamSrc = src;
      streamAt = Date.now();
      streamLive = streamIsLive();
      paintStream();
    },
    onState: () => { /* drop: freshness falls back to the slow photo */ },
    // Camera off: the picture goes away at once, without waiting for the
    // last frame to expire. Duplicates never repaint.
    onCamera: (on) => {
      if (cameraOff === !on) return;
      cameraOff = !on;
      if (cameraOff) streamSrc = '';
      render();
    },
  });
}

// Hidden tab: paints are suspended (`paintStream` does nothing), on return
// the last frame repaints when it is still live.
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && streamIsLive()) paintStream();
});

let lastStatus = stateOf(live);
setInterval(() => {
  const status = stateOf(live);
  const now = streamIsLive();
  const flipped = now !== streamLive;
  streamLive = now;
  if (!now && streamSrc && Date.now() - streamAt > STREAM_FRESH_MS) streamSrc = '';
  if (status !== lastStatus || flipped) { lastStatus = status; render(); }
}, 500);
