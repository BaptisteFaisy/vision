import { LIVE_MS, shareIdOf, stateOf, movementBetween, anchorAfter, narrationOf, neverPublished } from './session.mjs';
import { frameOf, pictosOf, tripOf, tripPercent, tripRemaining } from './live-view.mjs';
import { STREAM_FRESH_MS, watchStream } from './live-stream.mjs';
import { startOrb } from './orb.mjs';
import { spokenLine } from './spoken-line.mjs';
import './settings.mjs';

const shareId = shareIdOf(location.href);
const showWords = spokenLine(document.getElementById('spoken-line'));
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
let live = null, anchor = null, movement = '', movementAt = 0, polling = false, frameSrc = '', pictoSig = '';
// Direct de la caméra : dernière frame reçue par WebSocket et son instant.
let streamSrc = '', streamAt = 0;
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
  const motion = Date.now() - movementAt < LIVE_MS ? movement : '';
  showWords(narrationOf(live, status, motion, !!shareId, known), status === 'live' && live?.scene?.speaking);
  const spoken = document.querySelector('.spoken');
  spoken.hidden = status === 'offline' || !shareId;
  spoken.parentElement.classList.toggle('with-text', !spoken.hidden);
  const fromUser = status === 'live' && live?.scene?.fromUser;
  document.getElementById('speaker').textContent = fromUser ? 'YOU' : 'ISEE';
  spoken.dataset.speaker = fromUser ? 'you' : 'isee';
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
 * Ce que voit le proche : rien tant que sa caméra ne tourne pas. Une image
 * conservée après coupure resterait affichée alors que la caméra est éteinte.
 */
function renderFrame(status) {
  // Le direct passe devant la photo lente : une frame de moins de 1,5 s est la
  // caméra qui tourne maintenant, pas un état figé par le relais.
  const direct = streamSrc && Date.now() - streamAt <= STREAM_FRESH_MS;
  const view = direct ? { src: streamSrc } : frameOf(live, status);
  if (!view) {
    if (frameSrc) { frameEl.removeAttribute('src'); frameSrc = ''; }
    if (!direct) { streamSrc = ''; }
    frameEl.hidden = true;
    viewEl.classList.add('empty');
    // Pas de texte d'attente quand le lien est connu : la section se replie et
    // l'image la rouvre dès que la caméra tourne. Seul un lien absent se dit.
    if (shareId) {
      noteEl.hidden = true;
    } else {
      noteEl.hidden = false;
      noteEl.textContent = 'Ouvre le lien de suivi partagé par ton proche.';
    }
    return;
  }
  noteEl.hidden = true;
  frameEl.hidden = false;
  viewEl.classList.remove('empty');
  frameEl.alt = 'Ce que voit ton proche, en direct';
  // Une data URL identique n'est pas réassignée : l'image ne clignote pas à chaque poll.
  if (view.src !== frameSrc) { frameSrc = view.src; frameEl.src = view.src; }
}

/**
 * Une frame du direct : on ne repeint que l'image. Un `render()` complet à chaque
 * image (8 par seconde) reconstruirait le bandeau et la phrase pour rien.
 */
function paintStream() {
  if (!streamSrc) { render(); return; }
  noteEl.hidden = true;
  frameEl.hidden = false;
  viewEl.classList.remove('empty');
  frameEl.alt = 'Ce que voit ton proche, en direct';
  if (frameSrc !== streamSrc) { frameSrc = streamSrc; frameEl.src = streamSrc; }
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
  // Identifiant jamais publié (ou flux purgé) : le dire, au lieu d'attendre à vie.
  if (neverPublished(snapshot)) {
    known = false; live = null; anchor = null; movement = '';
    return;
  }
  // La position peut manquer (téléphone sans GPS) : la caméra, la phrase et les
  // repères passent quand même, seule la mesure des gestes s'interrompt.
  if (snapshot.ok && Number.isFinite(snapshot.serverAt)) {
    if (live && snapshot.serverAt < live.serverAt) return;
    known = true;
    if (stateOf(live) !== 'live') anchor = null;
    const fix = snapshot.fix || null;
    const nextMovement = movementBetween(anchor, fix);
    if (nextMovement) { movement = nextMovement; movementAt = Date.now(); }
    anchor = anchorAfter(anchor, nextMovement, fix);
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

// Direct : la caméra du proche arrive par le WebSocket de la salle. Sans salle
// (déploiement d'avant, réseau qui bloque `wss`), la page garde la photo lente.
if (shareId) {
  watchStream({
    href: location.href,
    onFrame: (src) => { streamSrc = src; streamAt = Date.now(); paintStream(); },
    onState: () => { /* coupure : la fraîcheur fait retomber sur la photo lente */ },
  });
}

let lastStatus = stateOf(live);
setInterval(() => {
  const status = stateOf(live);
  const staleStream = streamSrc && Date.now() - streamAt > STREAM_FRESH_MS;
  if (staleStream) streamSrc = '';
  if (status !== lastStatus || staleStream) { lastStatus = status; render(); }
}, 1000);
