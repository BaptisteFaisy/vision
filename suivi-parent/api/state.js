// GET /api/state?share=leo — le site parent lit le dernier état (poll toutes les 2 s).
// Réponse : {ok, live, ageMs, fix, objects, scene, photo, trip, serverAt} ou {ok:true, live:false}.
// live = état de moins de 12 s ; puis signal perdu et hors ligne, sans simulation.
const { storeGet, validId } = require('./_store');

const FRESH_MS = 12_000;

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const shareId = req.query && req.query.share;
  if (!validId(shareId)) return res.status(400).json({ ok: false, error: 'share invalide' });
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
