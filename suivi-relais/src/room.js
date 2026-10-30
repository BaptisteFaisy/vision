// Salle temps réel d'un flux : le téléphone **pousse** des frames, les aidants
// **regardent**. Rien n'est stocké ici — D1 garde l'état lent (position, phrase,
// trajet), la salle ne fait que relayer des octets. Une image qui traîne en base
// serait de toute façon refusée par la page (fraîcheur), donc la garder ne
// servirait qu'à payer des écritures.

/** Rôles acceptés sur `/ws`. */
export const ROLES = ['push', 'watch'];

/** Une frame au-delà de 512 Ko n'est pas une frame de 480 px : on la refuse. */
export const MAX_FRAME_BYTES = 512 * 1024;

/** Rôle porté par l'URL (`?role=watch`), `null` si absent ou inconnu. */
export function roleOf(url) {
  const role = url.searchParams.get('role');
  return ROLES.includes(role) ? role : null;
}

/** Clé d'écriture du flux, comme sur `/api/share` (liée au flux au premier envoi). */
export function keyOf(url) {
  const key = url.searchParams.get('key');
  return typeof key === 'string' ? key : '';
}

/** Identifiant de flux porté par l'URL ; le worker l'a déjà validé. */
export function shareOf(url) {
  return url.searchParams.get('share') || '';
}

/**
 * Ce que le téléphone reçoit quand l'audience change : il n'émet **que s'il est
 * regardé**. Sans ça, la caméra du proche pousserait des frames dans le vide à
 * chaque promenade, sur son forfait mobile et sa batterie.
 */
export function audienceNotice(count) {
  return JSON.stringify({ type: 'audience', watch: count });
}

/**
 * Ce que les aidants reçoivent quand la caméra du proche s'allume ou s'éteint :
 * sans cet avis, une page connectée garderait la dernière image jusqu'à 8 s
 * après la coupure (photo lente encore « fraîche »), alors que la caméra est
 * déjà éteinte. Émis par la salle (départ du téléphone) ou relayé depuis lui
 * (caméra fermée, tuyau encore ouvert).
 */
export function cameraNotice(on) {
  return JSON.stringify({ type: 'camera', on: on === true });
}

/**
 * État caméra porté par un message texte, `null` si ce n'en est pas un. Seul
 * un booléen strict compte : une frame binaire décodée en texte ne doit jamais
 * éteindre l'image de tous les aidants.
 */
export function cameraOnOf(text) {
  if (typeof text !== 'string') return null;
  let parsed = null;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  if (!parsed || parsed.type !== 'camera') return null;
  if (parsed.on === true) return true;
  if (parsed.on === false) return false;
  return null;
}

/**
 * Une frame va aux spectateurs, **jamais** à son émetteur : le téléphone n'a rien
 * à se renvoyer à lui-même, et une boucle coûterait des données pour rien.
 */
export function frameTargets(sockets, sender) {
  return sockets.filter((socket) => socket !== sender);
}

/** Frame relaisable : non vide et bornée. */
export function frameOk(bytes) {
  return Number.isFinite(bytes) && bytes > 0 && bytes <= MAX_FRAME_BYTES;
}
