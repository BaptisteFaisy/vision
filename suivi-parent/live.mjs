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
// Dernière peinture : son instant et son tuyau (direct ou photo lente). À la
// perte du direct, l'image posée reste figée pendant la grâce au lieu
// d'être arrachée — chaque micro-trou clignotait sinon.
let shownAt = 0, showingDirect = false;
// Direct de la caméra : dernière frame reçue par WebSocket et son instant.
let streamSrc = '', streamAt = 0;
// La salle a dit que la caméra est éteinte : le direct comme la photo lente
// restent cachés jusqu'à l'avis contraire, ou une frame qui prouve le contraire.
let cameraOff = false;
/** Reprise du direct : une frame de moins que ça rallume l'image (hystérésis). */
const STREAM_RESUME_MS = 200;
let streamLive = false;
// Peinture latest-only : une seule image en vol (pré-décodage hors écran), le
// surplus entre deux frames peintes est jeté au lieu d'avorter un décodage.
const preload = new Image();
let paintQueued = false, decoding = null;
let paintedCount = 0, droppedPaints = 0, lastPaintAt = 0;
const paintGaps = [];
// Aucune réponse du relais encore : ne pas annoncer un lien mort sans preuve.
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
 * Objets reconnus : leurs dessins en bandeau, rien sans détection fraîche.
 * Mêmes repères : le bandeau n'est pas reconstruit, les images ne clignotent pas.
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
 * Le direct est-il affiché ? Hystérésis : perdu après [STREAM_FRESH_MS] sans
 * frame, repris seulement sur une frame de moins de [STREAM_RESUME_MS] — entre
 * les deux, on garde l'état affiché au lieu de flapper direct/photo lente.
 */
function streamIsLive() {
  if (!streamSrc) return false;
  const age = Date.now() - streamAt;
  return streamLive ? age <= STREAM_FRESH_MS : age <= STREAM_RESUME_MS;
}

/**
 * Ce que voit le proche : rien tant que sa caméra ne tourne pas. Une image
 * conservée après coupure resterait affichée alors que la caméra est éteinte.
 */
function renderFrame(status) {
  // Le direct passe devant la photo lente : une frame fraîche est la caméra qui
  // tourne maintenant, pas un état figé par le relais.
  const direct = streamIsLive();
  if (direct) {
    // L'image du direct est peinte par paintStream (pré-décodage hors écran,
    // latest-only) : render() n'y touche pas — écraser img.src ici forcerait
    // un décodage hors pré-chargement à chaque poll et volerait une peinture.
    noteEl.hidden = true;
    frameEl.hidden = false;
    viewEl.classList.remove('empty');
    frameEl.alt = 'What your loved one sees, live';
    return;
  }
  const view = frameOf(live, status, Date.now(), cameraOff);
  // Micro-coupure du direct : l'image posée reste figée pendant la grâce, au
  // lieu d'être arrachée (reflow de toute la page) ou permutée avec la photo
  // lente (flash + re-swap au retour).
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
    // Pas de texte d'attente : sans image, la section se replie. Seuls un lien
    // absent et un lien mort s'écrivent dans la zone caméra (plus de phrase).
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
  // Une data URL identique n'est pas réassignée : l'image ne clignote pas à chaque poll.
  if (view.src !== frameSrc) { frameSrc = view.src; frameEl.src = view.src; shownAt = Date.now(); showingDirect = false; }
}

/**
 * Une frame du direct : on ne repeint que l'image. Un `render()` complet à chaque
 * image (10 par seconde) reconstruirait le bandeau, le trajet et le badge pour rien.
 *
 * Latest-only : les frames sont coalescées sur `requestAnimationFrame` (une seule
 * peinture par tour), et l'image est décodée **hors écran** avant le swap — écraser
 * `img.src` à chaque message avortait le décodage précédent et saccadait à 10/s.
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
 * Rythme **affiché** du direct (médiane et p95 des intervalles de peinture,
 * fenêtre de 60) : `window.__streamFps` est la preuve côté page des 10+ ips.
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

/** Le trajet en cours : départ → arrivée, le liquide suit l'avancement du proche. */
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
  // Identifiant jamais publié (ou flux purgé) : le dire, au lieu d'attendre à vie.
  if (neverPublished(snapshot)) {
    known = false; live = null;
    return;
  }
  // La position peut manquer (téléphone sans GPS) : la caméra, la phrase et les
  // repères passent quand même, seule la mesure des gestes s'interrompt.
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
  } catch { /* L'âge du dernier paquet continue de croître : aucune fausse remise en direct. */ }
  finally { polling = false; render(); }
}

startOrb(document.getElementById('orb'));
render(); poll();
setInterval(poll, 2000);

// Direct : la caméra du proche arrive par le WebSocket de la salle. Sans salle
// (déploiement d'avant, réseau qui bloque `wss`), la page garde la photo lente.
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
    onState: () => { /* coupure : la fraîcheur fait retomber sur la photo lente */ },
    // Caméra éteinte : l'image disparaît tout de suite, sans attendre que la
    // dernière frame périme. Les doublons ne repeignent pas.
    onCamera: (on) => {
      if (cameraOff === !on) return;
      cameraOff = !on;
      if (cameraOff) streamSrc = '';
      render();
    },
  });
}

// Onglet masqué : les peintures sont suspendues (`paintStream` ne fait rien),
// au retour on repeint la dernière frame si elle est encore du direct.
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
