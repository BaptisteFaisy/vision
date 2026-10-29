import { audienceNotice, frameOk, frameTargets, keyOf, roleOf, shareOf } from './room.js';
import { validId } from './contrat.js';
import { d1Store } from './store.js';

/**
 * Salle d'un flux (`/ws?share=<id>`), une instance de Durable Object par
 * identifiant. Le téléphone accompagne se connecte en `role=push`, chaque aidant
 * en `role=watch` ; les frames binaires passent de l'un aux autres sans être
 * stockées ni relues.
 *
 * L'API d'**hibernation** est utilisée exprès : une salle avec un spectateur
 * immobile ne coûte alors aucune durée, et les frames réveillent l'objet le temps
 * de les relayer. Les onglets sont étiquetés par rôle (`acceptWebSocket(ws,
 * [role])`), ce qui rend `getWebSockets('watch')` suffisant pour joindre
 * l'audience sans tenir de table en mémoire (perdue à chaque hibernation).
 */
export class RelayRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (request.headers.get('Upgrade') !== 'websocket') {
      return new Response('websocket attendu', { status: 426 });
    }
    const share = shareOf(url);
    const role = roleOf(url);
    if (!validId(share) || !role) return new Response('flux ou rôle invalide', { status: 400 });
    if (role === 'push' && !(await this.pushAllowed(share, keyOf(url)))) {
      return new Response('clé invalide', { status: 403 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.state.acceptWebSocket(server, [role]);
    if (role === 'watch') this.notifyAudience();
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Frame binaire du téléphone : relayée aux aidants, rien d'autre. */
  async webSocketMessage(socket, message) {
    if (typeof message === 'string') return;
    const bytes = message.byteLength ?? message.length;
    if (!frameOk(bytes)) return;
    if (!this.state.getWebSockets('watch').length) return;
    for (const target of frameTargets(this.state.getWebSockets('watch'), socket)) {
      try { target.send(message); } catch { /* un onglet fermé n'arrête pas les autres */ }
    }
  }

  async webSocketClose(socket) {
    this.closeQuietly(socket);
    this.notifyAudience(this.watchersExcept(socket));
  }

  async webSocketError(socket) {
    this.closeQuietly(socket);
    this.notifyAudience(this.watchersExcept(socket));
  }

  closeQuietly(socket) {
    try { socket.close(1000, 'fin'); } catch { /* déjà fermé */ }
  }

  /**
   * Aidants encore là. À la fermeture, le runtime compte **encore** l'onglet qui
   * s'en va : sans ce retrait, le téléphone apprendrait `watch:1` au départ du
   * dernier aidant et continuerait d'émettre dans le vide.
   */
  watchersExcept(socket) {
    return this.state.getWebSockets('watch').filter((s) => s !== socket).length;
  }

  /** Dit aux téléphones combien d'aidants regardent : ils n'émettent que si > 0. */
  notifyAudience(watch = this.state.getWebSockets('watch').length) {
    const notice = audienceNotice(watch);
    for (const socket of this.state.getWebSockets('push')) {
      try { socket.send(notice); } catch { /* téléphone parti */ }
    }
  }

  /**
   * Même règle que `/api/share` : la clé est liée au flux au premier envoi. Un
   * flux encore jamais écrit n'a rien à protéger, donc on l'accepte ; dès qu'une
   * clé existe, une autre clé est refusée — sinon un identifiant deviné suffirait
   * à diffuser une fausse caméra chez l'aidant.
   */
  async pushAllowed(share, key) {
    let row = null;
    try { row = await d1Store(this.env.DB).read(share); } catch { row = null; }
    if (!row) return true;
    return !row.key || row.key === key;
  }
}
