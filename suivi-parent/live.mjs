import { LIVE_MS, shareIdOf, stateOf, movementBetween, narrationOf } from './session.mjs';
import { frameOf, tripOf, tripPercent, tripRemaining } from './live-view.mjs';
import { startOrb } from './orb.mjs';
import { spokenLine } from './spoken-line.mjs';
import './settings.mjs';

const shareId = shareIdOf(location.href);
const showWords = spokenLine(document.getElementById('spoken-line'));
const frameEl = document.getElementById('view-frame');
const noteEl = document.getElementById('view-note');
const tripEl = document.getElementById('trip');
const tripDest = document.getElementById('trip-dest');
const tripRest = document.getElementById('trip-rest');
const tripTrack = document.getElementById('trip-track');
const tripLiquid = document.getElementById('trip-liquid');
const tripBall = document.getElementById('trip-ball');
let live = null, anchor = null, movement = '', movementAt = 0, polling = false, frameSrc = '';
const STATES = { live: ['LIVE', 'green'], lost: ['SIGNAL LOST', 'orange'], offline: ['OFFLINE', 'grey'] };

function render() {
  const status = stateOf(live);
  const [label, color] = STATES[status];
  const badge = document.getElementById('live-badge');
  badge.textContent = label;
  badge.className = 'pill ' + color;
  const motion = Date.now() - movementAt < LIVE_MS ? movement : '';
  showWords(narrationOf(live, status, motion, !!shareId), status === 'live' && live?.scene?.speaking);
  const spoken = document.querySelector('.spoken');
  spoken.hidden = status === 'offline' || !shareId;
  spoken.parentElement.classList.toggle('with-text', !spoken.hidden);
  const fromUser = status === 'live' && live?.scene?.fromUser;
  document.getElementById('speaker').textContent = fromUser ? 'YOU' : 'ISEE';
  spoken.dataset.speaker = fromUser ? 'you' : 'isee';
  renderFrame(status);
  renderTrip(status);
}

/** Ce que voit le proche : la dernière frame publiée, atténuée quand elle a vieilli. */
function renderFrame(status) {
  const view = frameOf(live, status);
  if (!view) {
    if (frameSrc) { frameEl.removeAttribute('src'); frameSrc = ''; }
    frameEl.hidden = true;
    noteEl.hidden = false;
    noteEl.textContent = shareId ? 'En attente de l’image de ton proche…' : 'Ouvre le lien de suivi partagé par ton proche.';
    return;
  }
  noteEl.hidden = true;
  frameEl.hidden = false;
  frameEl.classList.toggle('stale', !view.fresh);
  frameEl.alt = view.fresh ? 'Ce que voit ton proche, en direct' : 'Dernière image reçue de ton proche';
  // Une data URL identique n'est pas réassignée : l'image ne clignote pas à chaque poll.
  if (view.src !== frameSrc) { frameSrc = view.src; frameEl.src = view.src; }
}

/** Le trajet en cours : départ → arrivée, le liquide suit l'avancement du proche. */
function renderTrip(status) {
  const trip = tripOf(live, status);
  tripEl.hidden = !trip;
  if (!trip) return;
  tripEl.classList.toggle('stale', status !== 'live');
  tripDest.textContent = 'Vers ' + trip.destination;
  tripRest.textContent = tripRemaining(trip);
  const percent = tripPercent(trip);
  tripLiquid.style.width = percent + '%';
  tripBall.style.left = percent + '%';
  tripTrack.setAttribute('aria-valuenow', String(Math.round(percent)));
  tripTrack.setAttribute('aria-valuetext', tripDest.textContent + ', ' + tripRest.textContent);
}

function receive(snapshot) {
  if (snapshot.ok && snapshot.fix && Number.isFinite(snapshot.serverAt)) {
    if (live && snapshot.serverAt < live.serverAt) return;
    if (stateOf(live) !== 'live') anchor = null;
    const nextMovement = movementBetween(anchor, snapshot.fix);
    if (nextMovement) { movement = nextMovement; movementAt = Date.now(); }
    if (!anchor || nextMovement || snapshot.fix.atMs - anchor.atMs > 30_000) anchor = snapshot.fix;
    live = snapshot;
  } else if (snapshot.ok && snapshot.live === false) {
    live = null; anchor = null; movement = '';
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
let lastStatus = stateOf(live);
setInterval(() => {
  const status = stateOf(live);
  if (status !== lastStatus) { lastStatus = status; render(); }
}, 1000);
