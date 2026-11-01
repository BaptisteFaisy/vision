// GET /api/state?share=leo — the parent site reads the latest state (poll every 2 s).
// Answer: {ok, live, ageMs, fix, objects, scene, photo, trip, serverAt} or {ok:true, live:false}.
// live = state under 12 s old; then signal lost and offline, with no simulation.
const { storeGet, validId } = require('./_store');

const FRESH_MS = 12_000;

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const shareId = req.query && req.query.share;
  if (!validId(shareId)) return res.status(400).json({ ok: false, error: 'invalid share' });
  const state = await storeGet('suivi:' + shareId);
  if (!state) return res.status(200).json({ ok: true, live: false });
  const ageMs = Date.now() - state.serverAt;
  return res.status(200).json({
    ok: true,
    live: ageMs < FRESH_MS,
    ageMs,
    fix: state.fix,
    objects: state.objects,
    scene: state.scene || null,
    photo: state.photo || null,
    trip: state.trip || null,
    serverAt: state.serverAt,
  });
};
