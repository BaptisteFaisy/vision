// POST /api/share — le téléphone pousse son état toutes les 2 s pendant le Direct.
// Corps : {shareId, writeKey, fix:{lat,lon,accM,headingDeg,atMs,indoor}, objects:[{label,distM,angleDeg}],
//         scene, photo:{jpeg,atMs}, trip:{destination,totalM,remainingM,remainingS,progress}}.
// Si SHARE_WRITE_KEY est défini côté Vercel, la clé doit correspondre (sinon 403).
const { storeSet, validId } = require('./_store');

function num(v, min, max) {
  return typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
}

// Optionnel : les anciennes versions de l'app continuent d'envoyer GPS + objets seuls.
function sceneOf(scene) {
  if (!scene || typeof scene.text !== 'string') return null;
  return {
    text: scene.text.slice(0, 2000),
    fromUser: scene.fromUser === true,
    speaking: scene.speaking === true,
  };
}

/** Image courante de la caméra (JPEG base64) ; null = absente, false = illisible. */
function photoOf(photo) {
  if (photo === undefined || photo === null) return null;
  if (typeof photo !== 'object' || typeof photo.jpeg !== 'string') return false;
  const jpeg = photo.jpeg.trim();
  if (!jpeg || jpeg.length > 120_000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(jpeg)) return false;
  return { jpeg, atMs: num(photo.atMs, 0, 9e15) ? photo.atMs : Date.now() };
}

/** Trajet guidé en cours ; null = aucun, false = illisible. */
function tripOf(trip) {
  if (trip === undefined || trip === null) return null;
  if (typeof trip !== 'object') return false;
  const destination = typeof trip.destination === 'string' ? trip.destination.trim() : '';
  if (!destination) return false;
  if (!num(trip.totalM, 0, 5e6) || !num(trip.remainingM, 0, 5e6)) return false;
  if (!num(trip.remainingS, 0, 1e6) || !num(trip.progress, 0, 1)) return false;
  return {
    destination: destination.slice(0, 80),
    totalM: trip.totalM, remainingM: trip.remainingM, remainingS: trip.remainingS, progress: trip.progress,
  };
}

function fixOf(f) {
  return {
    lat: f.lat, lon: f.lon, accM: f.accM,
    headingDeg: num(f.headingDeg, 0, 360) ? f.headingDeg : null,
    atMs: num(f.atMs, 0, 9e15) ? f.atMs : Date.now(),
    indoor: f.indoor === true,
  };
}

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'POST') return res.status(405).json({ ok: false, error: 'POST attendu' });
  const b = req.body && typeof req.body === 'object' ? req.body : {};
  if (!validId(b.shareId)) return res.status(400).json({ ok: false, error: 'shareId invalide' });
  const f = b.fix || {};
  if (!num(f.lat, -90, 90) || !num(f.lon, -180, 180) || !num(f.accM, 0, 500)) {
    return res.status(400).json({ ok: false, error: 'fix invalide' });
  }
  const expected = process.env.SHARE_WRITE_KEY;
  if (expected && b.writeKey !== expected) {
    return res.status(403).json({ ok: false, error: 'clé invalide' });
  }
  const objects = Array.isArray(b.objects) ? b.objects.slice(0, 8) : [];
  for (const o of objects) {
    if (typeof o.label !== 'string' || !num(o.distM, 0, 60) || !num(o.angleDeg, -180, 180)) {
      return res.status(400).json({ ok: false, error: 'objet invalide' });
    }
  }
  const photo = photoOf(b.photo);
  if (photo === false) return res.status(400).json({ ok: false, error: 'photo invalide' });
  const trip = tripOf(b.trip);
  if (trip === false) return res.status(400).json({ ok: false, error: 'trip invalide' });
  const stored = await storeSet('suivi:' + b.shareId, {
    fix: fixOf(f),
    objects: objects.map((o) => ({ label: String(o.label).slice(0, 40), distM: o.distM, angleDeg: o.angleDeg })),
    scene: sceneOf(b.scene),
    photo,
    trip,
    serverAt: Date.now(),
  });
  if (!stored) return res.status(503).json({ ok: false, error: 'relais indisponible' });
  return res.status(200).json({ ok: true });
};
