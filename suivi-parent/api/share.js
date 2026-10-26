// POST /api/share — le téléphone pousse son état toutes les 2 s pendant le Direct.
// Corps : {shareId, writeKey, fix:{lat,lon,accM,headingDeg,atMs,indoor}, objects:[{label,distM,angleDeg}]}.
// Si SHARE_WRITE_KEY est défini côté Vercel, la clé doit correspondre (sinon 403).
const { storeSet, validId } = require('./_store');

function num(v, min, max) {
  return typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
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
  await storeSet('suivi:' + b.shareId, {
    fix: {
      lat: f.lat,
      lon: f.lon,
      accM: f.accM,
      headingDeg: num(f.headingDeg, 0, 360) ? f.headingDeg : null,
      atMs: num(f.atMs, 0, 9e15) ? f.atMs : Date.now(),
      indoor: f.indoor === true,
    },
    objects: objects.map((o) => ({ label: String(o.label).slice(0, 40), distM: o.distM, angleDeg: o.angleDeg })),
    serverAt: Date.now(),
  });
  return res.status(200).json({ ok: true });
};
