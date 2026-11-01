import test from 'node:test';
import assert from 'node:assert/strict';
import { ROLES, MAX_FRAME_BYTES, roleOf, keyOf, shareOf, audienceNotice, cameraNotice, cameraOnOf, frameTargets, frameOk } from '../src/room.js';
import { RelayRoom } from '../src/durable.js';

/**
 * Node refuses `status: 101` (outside 200-599) while the Cloudflare runtime
 * requires it for a WebSocket upgrade. The double keeps the API, only the
 * inner value changes: `response.status` still yields 101.
 */
const TrueResponse = globalThis.Response;
globalThis.Response = class FakeResponse extends TrueResponse {
  constructor(body, init) {
    const upgraded = init && init.status === 101;
    super(body, init ? { ...init, status: upgraded ? 200 : init.status } : init);
    this.upgraded = upgraded ? 101 : 0;
    this.socket = init ? init.webSocket : null;
  }
  get status() { return this.upgraded || super.status; }
  get webSocket() { return this.socket; }
};

const url = (q) => new URL('https://relais.example/ws?' + q);

/** Open request: the room only takes a real WebSocket ask. */
const openReq = (q) => new Request('https://relais.example/ws?' + q, { headers: { Upgrade: 'websocket' } });

test('a tab role is the URL one, never anything else', () => {
  assert.deepEqual(ROLES, ['push', 'watch']);
  assert.equal(roleOf(url('share=leo&role=watch')), 'watch');
  assert.equal(roleOf(url('share=leo&role=push')), 'push');
  assert.equal(roleOf(url('share=leo&role=admin')), null);
  assert.equal(roleOf(url('share=leo')), null);
  assert.equal(shareOf(url('share=leo&role=push')), 'leo');
  assert.equal(keyOf(url('share=leo&key=abc')), 'abc');
  assert.equal(keyOf(url('share=leo')), '');
});

test('the phone only emits while watched', () => {
  assert.equal(audienceNotice(0), '{"type":"audience","watch":0}');
  assert.equal(JSON.parse(audienceNotice(2)).watch, 2);
});

test('a frame goes to watchers, not back to its sender', () => {
  const a = { role: 'watch' }, b = { role: 'watch' }, phone = { role: 'push' };
  assert.deepEqual(frameTargets([a, b, phone], phone), [a, b]);
});

test('an empty or oversized frame is refused', () => {
  assert.equal(frameOk(0), false);
  assert.equal(frameOk(-1), false);
  assert.equal(frameOk(MAX_FRAME_BYTES + 1), false);
  assert.equal(frameOk(24_000), true);
});

/** In-memory D1 base: only what `store.read` asks for. */
function fakeDb(key) {
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

/** Room of a stream, with fake tabs: what the Durable Object really gets. */
function makeRoom(key = '') {
  const tags = new Map();
  const sent = [];
  const state = {
    acceptWebSocket(socket, names) { tags.set(socket, names[0]); },
    // Like the runtime: at `close` time, the tab is **still** in the list.
    getWebSockets(tag) {
      return [...tags.keys()].filter((s) => !tag || tags.get(s) === tag);
    },
  };
  const room = new RelayRoom(state, { DB: fakeDb(key) });
  return { room, state, sent, tags };
}

/** Fake tab: `send` logs traffic, `close` marks it closed. */
function tab(name) {
  return {
    name,
    received: [],
    closed: false,
    send(data) { this.received.push(data); },
    close() { this.closed = true; },
  };
}

test('the room refuses a phone whose key is not the stream one', async () => {
  const { room } = makeRoom('stream-key');
  globalThis.WebSocketPair = class { constructor() { this[0] = tab('client'); this[1] = tab('server'); } };
  const denied = await room.fetch(openReq('share=leo&role=push&key=other'));
  assert.equal(denied.status, 403);
});

test('a never-written stream has nothing to protect: the room takes the first phone', async () => {
  const { room } = makeRoom('');
  globalThis.WebSocketPair = class { constructor() { this[0] = tab('client'); this[1] = tab('server'); } };
  const ok = await room.fetch(openReq('share=leo&role=push&key=whatever'));
  assert.equal(ok.status, 101);
});

test('without `Upgrade: websocket`, the room never takes the connection', async () => {
  const { room } = makeRoom('');
  const denied = await room.fetch(new Request('https://relais.example/ws?share=leo&role=watch'));
  assert.equal(denied.status, 426);
});

test('the phone frame reaches caregivers, and nobody else', async () => {
  const { room, state } = makeRoom('');
  const phone = tab('phone');
  const caregiver = tab('caregiver');
  state.acceptWebSocket(phone, ['push']);
  state.acceptWebSocket(caregiver, ['watch']);
  const frame = new Uint8Array([1, 2, 3]);
  await room.webSocketMessage(phone, frame);
  assert.equal(caregiver.received.length, 1);
  assert.equal(phone.received.length, 0);
});

test('with no caregiver connected, the phone only gets a zero audience notice', async () => {
  const { room, state } = makeRoom('');
  const phone = tab('phone');
  state.acceptWebSocket(phone, ['push']);
  await room.webSocketMessage(phone, new Uint8Array([1, 2, 3]));
  assert.deepEqual(phone.received, []);
});

test('a phone joining an already watched room emits at once', async () => {
  const { room, state } = makeRoom('');
  const caregiver = tab('caregiver');
  state.acceptWebSocket(caregiver, ['watch']);
  let server = null;
  globalThis.WebSocketPair = class {
    constructor() { this[0] = tab('client'); this[1] = tab('server'); server = this[1]; }
  };
  const ok = await room.fetch(openReq('share=leo&role=push&key=k'));
  assert.equal(ok.status, 101);
  assert.deepEqual(server.received, ['{"type":"audience","watch":1}']);
});

test('the camera notice reads strictly: a boolean, nothing else', () => {
  assert.equal(cameraNotice(true), '{"type":"camera","on":true}');
  assert.equal(cameraNotice(false), '{"type":"camera","on":false}');
  assert.equal(cameraOnOf('{"type":"camera","on":true}'), true);
  assert.equal(cameraOnOf('{"type":"camera","on":false}'), false);
  assert.equal(cameraOnOf('{"type":"audience","watch":1}'), null);
  assert.equal(cameraOnOf('{"type":"camera","on":1}'), null);
  assert.equal(cameraOnOf('{"type":"camera"}'), null);
  assert.equal(cameraOnOf('not json'), null);
  assert.equal(cameraOnOf(''), null);
});

test('the last phone leaving turns the caregivers picture off', async () => {
  const { room, state } = makeRoom('');
  const phone = tab('phone');
  const caregiver = tab('caregiver');
  state.acceptWebSocket(phone, ['push']);
  state.acceptWebSocket(caregiver, ['watch']);
  await room.webSocketClose(phone);
  assert.deepEqual(caregiver.received, ['{"type":"camera","on":false}']);
});

test('a phone-pipe error turns the picture off like a close', async () => {
  const { room, state } = makeRoom('');
  const phone = tab('phone');
  const caregiver = tab('caregiver');
  state.acceptWebSocket(phone, ['push']);
  state.acceptWebSocket(caregiver, ['watch']);
  await room.webSocketError(phone);
  assert.deepEqual(caregiver.received, ['{"type":"camera","on":false}']);
});

test('a leaving caregiver or spare phone never touches the picture', async () => {
  const { room, state } = makeRoom('');
  const phone = tab('phone');
  const duplicate = tab('duplicate');
  const caregiver = tab('caregiver');
  state.acceptWebSocket(phone, ['push']);
  state.acceptWebSocket(duplicate, ['push']);
  state.acceptWebSocket(caregiver, ['watch']);
  await room.webSocketClose(duplicate);
  await room.webSocketClose(caregiver);
  assert.deepEqual(caregiver.received, []);
  assert.deepEqual(phone.received.filter((m) => m.includes('camera')), []);
});

test('the phone announces its camera, the room forwards it to caregivers', async () => {
  const { room, state } = makeRoom('');
  const phone = tab('phone');
  const caregiver = tab('caregiver');
  state.acceptWebSocket(phone, ['push']);
  state.acceptWebSocket(caregiver, ['watch']);
  await room.webSocketMessage(phone, '{"type":"camera","on":false}');
  await room.webSocketMessage(phone, '{"type":"camera","on":true}');
  await room.webSocketMessage(phone, 'hello');
  assert.deepEqual(caregiver.received, ['{"type":"camera","on":false}', '{"type":"camera","on":true}']);
});

test('a caregiver cannot turn off the others picture', async () => {
  const { room, state } = makeRoom('');
  const phone = tab('phone');
  const caregiver = tab('caregiver');
  const other = tab('other');
  state.acceptWebSocket(phone, ['push']);
  state.acceptWebSocket(caregiver, ['watch']);
  state.acceptWebSocket(other, ['watch']);
  await room.webSocketMessage(caregiver, '{"type":"camera","on":false}');
  assert.deepEqual(other.received, []);
  assert.deepEqual(phone.received, []);
});

test('a joining phone never claims its camera runs', async () => {
  const { room, state } = makeRoom('');
  const caregiver = tab('caregiver');
  state.acceptWebSocket(caregiver, ['watch']);
  globalThis.WebSocketPair = class { constructor() { this[0] = tab('client'); this[1] = tab('server'); } };
  const ok = await room.fetch(openReq('share=leo&role=push&key=k'));
  assert.equal(ok.status, 101);
  assert.deepEqual(caregiver.received, []);
});

test('a caregiver arriving with no phone learns the camera is off', async () => {
  const { room } = makeRoom('');
  let server = null;
  globalThis.WebSocketPair = class {
    constructor() { this[0] = tab('client'); this[1] = tab('server'); server = this[1]; }
  };
  const ok = await room.fetch(openReq('share=leo&role=watch'));
  assert.equal(ok.status, 101);
  assert.deepEqual(server.received, ['{"type":"camera","on":false}']);
});

test('a caregiver arriving with the phone present learns nothing from the room', async () => {
  const { room, state } = makeRoom('');
  const phone = tab('phone');
  state.acceptWebSocket(phone, ['push']);
  let server = null;
  globalThis.WebSocketPair = class {
    constructor() { this[0] = tab('client'); this[1] = tab('server'); server = this[1]; }
  };
  const ok = await room.fetch(openReq('share=leo&role=watch'));
  assert.equal(ok.status, 101);
  assert.deepEqual(server.received, []);
  // … but the phone learns it is being watched.
  assert.deepEqual(phone.received, ['{"type":"audience","watch":1}']);
});

test('an arriving caregiver tells the phone, a leaving one too', async () => {
  const { room, state } = makeRoom('');
  const phone = tab('phone');
  const caregiver = tab('caregiver');
  state.acceptWebSocket(phone, ['push']);
  state.acceptWebSocket(caregiver, ['watch']);
  room.notifyAudience();
  assert.deepEqual(phone.received, ['{"type":"audience","watch":1}']);
  phone.received.length = 0;
  await room.webSocketClose(caregiver);
  assert.deepEqual(phone.received, ['{"type":"audience","watch":0}']);
});
