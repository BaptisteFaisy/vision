import test from 'node:test';
import assert from 'node:assert/strict';
import { ROLES, MAX_FRAME_BYTES, roleOf, keyOf, shareOf, audienceNotice, frameTargets, frameOk } from '../src/room.js';
import { RelayRoom } from '../src/durable.js';

/**
 * Node refuse `status: 101` (hors 200-599) alors que le runtime de Cloudflare
 * l'exige pour une bascule WebSocket. La doublure garde l'API, seule la valeur
 * interne change : `response.status` rend bien 101.
 */
const VraieReponse = globalThis.Response;
globalThis.Response = class Reponse extends VraieReponse {
  constructor(body, init) {
    const bascule = init && init.status === 101;
    super(body, init ? { ...init, status: bascule ? 200 : init.status } : init);
    this.bascule = bascule ? 101 : 0;
    this.socket = init ? init.webSocket : null;
  }
  get status() { return this.bascule || super.status; }
  get webSocket() { return this.socket; }
};

const url = (q) => new URL('https://relais.example/ws?' + q);

/** Requête d'ouverture : la salle n'accepte qu'une vraie demande de WebSocket. */
const ouvre = (q) => new Request('https://relais.example/ws?' + q, { headers: { Upgrade: 'websocket' } });

test('le rôle d’un onglet est celui de l’URL, jamais autre chose', () => {
  assert.deepEqual(ROLES, ['push', 'watch']);
  assert.equal(roleOf(url('share=leo&role=watch')), 'watch');
  assert.equal(roleOf(url('share=leo&role=push')), 'push');
  assert.equal(roleOf(url('share=leo&role=admin')), null);
  assert.equal(roleOf(url('share=leo')), null);
  assert.equal(shareOf(url('share=leo&role=push')), 'leo');
  assert.equal(keyOf(url('share=leo&key=abc')), 'abc');
  assert.equal(keyOf(url('share=leo')), '');
});

test('le téléphone n’émet que s’il est regardé', () => {
  assert.equal(audienceNotice(0), '{"type":"audience","watch":0}');
  assert.equal(JSON.parse(audienceNotice(2)).watch, 2);
});

test('une frame va aux spectateurs et pas à son émetteur', () => {
  const a = { role: 'watch' }, b = { role: 'watch' }, phone = { role: 'push' };
  assert.deepEqual(frameTargets([a, b, phone], phone), [a, b]);
});

test('une frame vide ou démesurée est refusée', () => {
  assert.equal(frameOk(0), false);
  assert.equal(frameOk(-1), false);
  assert.equal(frameOk(MAX_FRAME_BYTES + 1), false);
  assert.equal(frameOk(24_000), true);
});

/** Base D1 en mémoire : seulement ce que `store.read` demande. */
function baseFactice(key) {
  return {
    prepare(sql) {
      const stmt = {
        args: [],
        bind(...args) { stmt.args = args; return stmt; },
        async first() {
          if (!key) return null;
          return { doc: JSON.stringify({ objects: [] }), updated_at: 1_700_000_000_000, write_key: key };
        },
        async run() { return { success: true, meta: { changes: 0 } }; },
      };
      return stmt;
    },
  };
}

/** Salle d'un flux, avec de faux onglets : ce que le Durable Object reçoit en vrai. */
function salle(key = '') {
  const tags = new Map();
  const sent = [];
  const state = {
    acceptWebSocket(socket, names) { tags.set(socket, names[0]); },
    // Le runtime de Cloudflare retire un onglet fermé : la doublure aussi.
    getWebSockets(tag) {
      return [...tags.keys()].filter((s) => !s.ferme && (!tag || tags.get(s) === tag));
    },
  };
  const room = new RelayRoom(state, { DB: baseFactice(key) });
  return { room, state, sent, tags };
}

/** Onglet factice : `send` note le trafic, `close` le marque fermé. */
function onglet(nom) {
  return {
    nom,
    recu: [],
    ferme: false,
    send(data) { this.recu.push(data); },
    close() { this.ferme = true; },
  };
}

test('la salle refuse un téléphone dont la clé n’est pas celle du flux', async () => {
  const { room } = salle('cle-du-flux');
  globalThis.WebSocketPair = class { constructor() { this[0] = onglet('client'); this[1] = onglet('serveur'); } };
  const refus = await room.fetch(ouvre('share=leo&role=push&key=autre'));
  assert.equal(refus.status, 403);
});

test('un flux jamais écrit n’a rien à protéger : la salle accepte le premier téléphone', async () => {
  const { room } = salle('');
  globalThis.WebSocketPair = class { constructor() { this[0] = onglet('client'); this[1] = onglet('serveur'); } };
  const ok = await room.fetch(ouvre('share=leo&role=push&key=quelconque'));
  assert.equal(ok.status, 101);
});

test('sans `Upgrade: websocket`, la salle ne prend pas la connexion', async () => {
  const { room } = salle('');
  const refus = await room.fetch(new Request('https://relais.example/ws?share=leo&role=watch'));
  assert.equal(refus.status, 426);
});

test('la frame du téléphone arrive aux aidants, et à personne d’autre', async () => {
  const { room, state } = salle('');
  const phone = onglet('phone');
  const aidant = onglet('aidant');
  state.acceptWebSocket(phone, ['push']);
  state.acceptWebSocket(aidant, ['watch']);
  const frame = new Uint8Array([1, 2, 3]);
  await room.webSocketMessage(phone, frame);
  assert.equal(aidant.recu.length, 1);
  assert.equal(phone.recu.length, 0);
});

test('sans aidant connecté, le téléphone ne reçoit qu’un avis d’audience à zéro', async () => {
  const { room, state } = salle('');
  const phone = onglet('phone');
  state.acceptWebSocket(phone, ['push']);
  await room.webSocketMessage(phone, new Uint8Array([1, 2, 3]));
  assert.deepEqual(phone.recu, []);
});

test('un aidant qui arrive prévient le téléphone, un qui part aussi', async () => {
  const { room, state } = salle('');
  const phone = onglet('phone');
  const aidant = onglet('aidant');
  state.acceptWebSocket(phone, ['push']);
  state.acceptWebSocket(aidant, ['watch']);
  room.notifyAudience();
  assert.deepEqual(phone.recu, ['{"type":"audience","watch":1}']);
  phone.recu.length = 0;
  await room.webSocketClose(aidant);
  assert.deepEqual(phone.recu, ['{"type":"audience","watch":0}']);
});
