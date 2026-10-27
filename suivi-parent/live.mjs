import { LIVE_MS, shareIdOf, stateOf, movementBetween, narrationOf } from './session.mjs';
import { bootMap, updateMap } from './maps.mjs';
import { startOrb } from './orb.mjs';
import { spokenLine } from './spoken-line.mjs';

const shareId = shareIdOf(location.href);
const showWords = spokenLine(document.getElementById('spoken-line'));
let live = null, anchor = null, movement = '', movementAt = 0, polling = false;
const STATES = { live: ['LIVE', 'green'], lost: ['SIGNAL LOST', 'orange'], offline: ['OFFLINE', 'grey'] };

function render() {
  const status = stateOf(live);
  const [label, color] = STATES[status];
  const badge = document.getElementById('live-badge');
  badge.textContent = shareId ? label : 'LIEN REQUIS';
  badge.className = 'pill ' + color;
  document.getElementById('dot').className = 'live-dot ' + status;
  document.getElementById('recenter').disabled = status === 'offline';
  const indoor = document.getElementById('indoor');
  indoor.textContent = status !== 'offline' && live?.fix.indoor ? `Position approximative · ±${Math.round(live.fix.accM)} m` : '';
  const motion = Date.now() - movementAt < LIVE_MS ? movement : '';
  showWords(narrationOf(live, status, motion, !!shareId), status === 'live' && live?.scene?.speaking);
  document.getElementById('speaker').textContent = status === 'live' && live?.scene?.fromUser ? 'YOU' : 'ISEE';
  document.getElementById('session-note').textContent = !shareId ? 'Le lien reçu ouvre la session de ton proche.' :
    status === 'lost' ? 'Dernières détections figées · en attente de reconnexion' :
    status === 'offline' ? 'La carte se remplira quand le téléphone enverra sa position.' : 'Position et objets du téléphone partagé';
  updateMap(live, status);
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

document.getElementById('shared-link-form').onsubmit = event => {
  event.preventDefault();
  const input = document.getElementById('shared-link');
  const id = shareIdOf(input.value.trim());
  input.setCustomValidity(id ? '' : 'Colle le lien complet reçu depuis l’app de ton proche.');
  if (!input.reportValidity()) return;
  // Le lien détermine aussi l'origine du relais : aucune dépendance au même téléphone.
  location.assign(input.value.trim());
};
document.getElementById('shared-link').oninput = event => event.target.setCustomValidity('');
document.getElementById('shared-link').value = shareId ? location.href : '';
document.getElementById('link-settings').open = !shareId;
startOrb(document.getElementById('orb'));
render(); poll();
const initializeMap = () => bootMap().catch(() => {
  document.getElementById('session-note').textContent = 'Carte indisponible. Le suivi textuel reste actif.';
});
if (document.readyState === 'complete') initializeMap();
else window.addEventListener('load', initializeMap, { once: true });
setInterval(poll, 2000);
let lastStatus = stateOf(live);
setInterval(() => {
  const status = stateOf(live);
  if (status !== lastStatus) { lastStatus = status; render(); }
}, 1000);
