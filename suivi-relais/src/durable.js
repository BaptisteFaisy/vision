import { audienceNotice, cameraNotice, cameraOnOf, frameOk, frameTargets, keyOf, roleOf, shareOf } from './room.js';
import { validId } from './contrat.js';
import { d1Store } from './store.js';

/**
 * Room of a stream (`/ws?share=<id>`), one Durable Object instance per id.
 * The accompanied phone connects as `role=push`, each caregiver as
 * `role=watch`; binary frames pass from one to the others, never stored
 * nor re-read.
 *
 * The **hibernation** API is used on purpose: a room with a still watcher
 * then costs no duration, and frames wake the object just long enough to
 * relay them. Tabs are tagged by role (`acceptWebSocket(ws, [role])`), so
 * `getWebSockets('watch')` alone reaches the audience with no in-memory
 * table (lost at every hibernation).
 */
export class RelayRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (request.headers.get('Upgrade') !== 'websocket') {
      return new Response('websocket expected', { status: 426 });
    }
    const share = shareOf(url);
    const role = roleOf(url);
    if (!validId(share) || !role) return new Response('invalid share or role', { status: 400 });
    if (role === 'push' && !(await this.pushAllowed(share, keyOf(url)))) {
      return new Response('invalid key', { status: 403 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.state.acceptWebSocket(server, [role]);
    // Every arrival republishes the audience: a phone joining an already
    // watched room must know at once, or it stays mute waiting for a
    // caregiver already there.
    this.notifyAudience();
    // A caregiver arriving while the phone is away learns at once that the
    // camera is off, instead of showing a slow photo still "fresh". Phone
    // present: it tells its real state itself (camera on or not), and the
    // frames speak for themselves.
    if (role === 'watch' && !this.state.getWebSockets('push').length) {
      try { server.send(cameraNotice(false)); } catch { /* tab already gone */ }
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  /** The phone talks to caregivers: binary frames and its camera state. */
  async webSocketMessage(socket, message) {
    // The phone announces its camera (on or closed): forwarded to caregivers
    // after validation. Only a `push` speaks for the loved one's camera —
    // a caregiver cannot turn off the others' picture.
    if (typeof message === 'string') {
      const on = cameraOnOf(message);
      if (on !== null && this.state.getWebSockets('push').includes(socket)) this.notifyCamera(on);
      return;
    }
    const bytes = message.byteLength ?? message.length;
    if (!frameOk(bytes)) return;
    if (!this.state.getWebSockets('watch').length) return;
    for (const target of frameTargets(this.state.getWebSockets('watch'), socket)) {
      try { target.send(message); } catch { /* one closed tab never stops the others */ }
    }
  }

  async webSocketClose(socket) {
    this.closeQuietly(socket);
    this.notifyAudience(this.watchersExcept(socket));
    this.cameraOffIfAlone(socket);
  }

  async webSocketError(socket) {
    this.closeQuietly(socket);
    this.notifyAudience(this.watchersExcept(socket));
    this.cameraOffIfAlone(socket);
  }

  closeQuietly(socket) {
    try { socket.close(1000, 'done'); } catch { /* already closed */ }
  }

  /**
   * Caregivers still there. At close time, the runtime **still** counts the
   * leaving tab: without this removal, the phone would learn `watch:1` when
   * the last caregiver leaves and would keep emitting into the void.
   */
  watchersExcept(socket) {
    return this.state.getWebSockets('watch').filter((s) => s !== socket).length;
  }

  /**
   * Phones still there, minus the leaving one: same reading as
   * `watchersExcept`, the runtime still counting the closing tab.
   */
  pushesExcept(socket) {
    return this.state.getWebSockets('push').filter((s) => s !== socket);
  }

  /** Tells caregivers whether the camera runs: they clear it as soon as it stops. */
  notifyCamera(on) {
    const notice = cameraNotice(on);
    for (const socket of this.state.getWebSockets('watch')) {
      try { socket.send(notice); } catch { /* closed tab */ }
    }
  }

  /**
   * The last phone leaving turns the caregivers' picture off right away:
   * without this notice, the slow photo still "fresh" would stay on screen
   * up to 8 s after the drop. A leaving caregiver or spare phone changes
   * nothing.
   */
  cameraOffIfAlone(socket) {
    if (!this.state.getWebSockets('push').includes(socket)) return;
    if (this.pushesExcept(socket).length) return;
    this.notifyCamera(false);
  }

  /** Tells phones how many caregivers watch: they only emit when > 0. */
  notifyAudience(watch = this.state.getWebSockets('watch').length) {
    const notice = audienceNotice(watch);
    for (const socket of this.state.getWebSockets('push')) {
      try { socket.send(notice); } catch { /* phone gone */ }
    }
  }

  /**
   * Same rule as `/api/share`: the key binds to the stream on first send. A
   * never-written stream has nothing to protect, so accept it; once a key
   * exists, another key is refused — otherwise a guessed id would be enough
   * to broadcast a fake camera to the caregiver.
   */
  async pushAllowed(share, key) {
    let row = null;
    try { row = await d1Store(this.env.DB).read(share); } catch { row = null; }
    if (!row) return true;
    return !row.key || row.key === key;
  }
}
