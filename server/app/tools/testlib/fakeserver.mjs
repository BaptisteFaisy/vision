/** Serveur HTTP local factice : enregistre chaque requête, la réponse vient du handler. */
import { createServer } from 'node:http';

export async function startFakeServer(handler) {
  const requests = [];
  const server = createServer((req, res) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      const request = { method: req.method, url: req.url, headers: req.headers, body };
      requests.push(request);
      handler(request, res);
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    url: `http://127.0.0.1:${port}`,
    requests,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

/** Réponse JSON (payload sérialisé s'il n'est pas déjà une chaîne). */
export function replyJson(res, status, payload) {
  const body = typeof payload === 'string' ? payload : JSON.stringify(payload);
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(body);
}

/** Exécute fn contre un serveur dédié, puis le referme quoi qu'il arrive. */
export async function withServer(handler, fn) {
  const server = await startFakeServer(handler);
  try {
    return await fn(server);
  } finally {
    await server.close();
  }
}