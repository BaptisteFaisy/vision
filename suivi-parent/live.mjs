import { LIVE_MS, shareIdOf, stateOf, movementBetween, narrationOf } from './session.mjs';
import { bootMap, updateMap } from './maps.mjs';
import { startOrb } from './orb.mjs';
import { spokenLine } from './spoken-line.mjs';
import './settings.mjs';

const shareId = shareIdOf(location.href);
const showWords = spokenLine(document.getElementById('spoken-line'));
let live = null, anchor = null, movement = '', movementAt = 0, polling = false;
const STATES = { live: ['LIVE', 'green'], lost: ['SIGNAL LOST', 'orange'], offline: ['OFFLINE', 'grey'] };

function render() {
  const status = stateOf(live);
  const [label, color] = STATES[status];
  const badge = document.getElementById('live-badge');
  badge.textContent = label;
  badge.className = 'pill ' + color;
  document.getElementById('dot').className = 'live-dot ' + status;
  document.getElementById('recenter').disabled = status === 'offline';
  const motion = Date.now() - movementAt < LIVE_MS ? movement : '';
  showWords(narrationOf(live, status, motion, !!shareId), status === 'live' && live?.scene?.speaking);
  document.querySelector('.spoken').hidden = status === 'offline' || !shareId;
  const fromUser = status === 'live' && live?.scene?.fromUser;
  document.getElementById('speaker').textContent = fromUser ? 'YOU' : 'ISEE';
  document.querySelector('.spoken').dataset.speaker = fromUser ? 'you' : 'isee';
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

startOrb(document.getElementById('orb'));
render(); poll();
const initializeMap = () => bootMap().catch(() => {
  document.getElementById('map-loading').textContent = 'Carte indisponible';
});
if (document.readyState === 'complete') initializeMap();
else window.addEventListener('load', initializeMap, { once: true });
setInterval(poll, 2000);
let lastStatus = stateOf(live);
setInterval(() => {
  const status = stateOf(live);
  if (status !== lastStatus) { lastStatus = status; render(); }
}, 1000);
