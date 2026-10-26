// GET /api/state?share=leo — le site parent lit le dernier état (poll toutes les 2 s).
// Réponse : {ok, live, ageMs, fix, objects, serverAt} ou {ok:true, live:false} sans état.
// live = état de moins de 12 s ; au-delà, le front affiche « signal perdu » puis la démo.
const { storeGet, validId } = require('./_store');

const FRESH_MS = 12_000;

module.exports = async (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  const shareId = (req.query && req.query.share) || 'leo';
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
    serverAt: state.serverAt,
  });
};
