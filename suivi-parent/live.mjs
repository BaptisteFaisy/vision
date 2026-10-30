import { NO_STREAM_TEXT, shareIdOf, stateOf, neverPublished } from './session.mjs';
import { frameOf, pictosOf, tripOf, tripPercent, tripRemaining } from './live-view.mjs';
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
// Direct de la caméra : dernière frame reçue par WebSocket et son instant.
let streamSrc = '', streamAt = 0;
// La salle a dit que la caméra est éteinte : le direct comme la photo lente
// restent cachés jusqu'à l'avis contraire, ou une frame qui prouve le contraire.
let cameraOff = false;
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
 * Ce que voit le proche : rien tant que sa caméra ne tourne pas. Une image
 * conservée après coupure resterait affichée alors que la caméra est éteinte.
 */
function renderFrame(status) {
  // Le direct passe devant la photo lente : une frame de moins de 1,5 s est la
  // caméra qui tourne maintenant, pas un état figé par le relais.
  const direct = streamSrc && Date.now() - streamAt <= STREAM_FRESH_MS;
  const view = direct ? { src: streamSrc } : frameOf(live, status, Date.now(), cameraOff);
  if (!view) {
    if (frameSrc) { frameEl.removeAttribute('src'); frameSrc = ''; }
    if (!direct) { streamSrc = ''; }
    frameEl.hidden = true;
    viewEl.classList.add('empty');
    // Pas de texte d'attente : sans image, la section se replie. Seuls un lien
    // absent et un lien mort s'écrivent dans la zone caméra (plus de phrase).
    if (!shareId || status === 'unknown') {
      noteEl.textContent = !shareId ? 'Ouvre le lien de suivi partagé par ton proche.' : NO_STREAM_TEXT;
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
  frameEl.alt = 'Ce que voit ton proche, en direct';
  // Une data URL identique n'est pas réassignée : l'image ne clignote pas à chaque poll.
  if (view.src !== frameSrc) { frameSrc = view.src; frameEl.src = view.src; }
}

/**
 * Une frame du direct : on ne repeint que l'image. Un `render()` complet à chaque
 * image (8 par seconde) reconstruirait le bandeau, le trajet et le badge pour rien.
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
    onFrame: (src) => { cameraOff = false; streamSrc = src; streamAt = Date.now(); paintStream(); },
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

let lastStatus = stateOf(live);
setInterval(() => {
  const status = stateOf(live);
  const staleStream = streamSrc && Date.now() - streamAt > STREAM_FRESH_MS;
  if (staleStream) streamSrc = '';
  if (status !== lastStatus || staleStream) { lastStatus = status; render(); }
}, 1000);
