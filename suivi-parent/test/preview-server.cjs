// Local review server: same handlers as Vercel, memory storage only.
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const ROOT = path.resolve(__dirname, '..');
const TYPES = { '.html': 'text/html', '.mjs': 'text/javascript', '.css': 'text/css', '.png': 'image/png' };

async function relay(req, res, url) {
  const handler = require('../api/' + (url.pathname === '/api/share' ? 'share.js' : 'state.js'));
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  req.body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {};
  req.query = Object.fromEntries(url.searchParams);
  res.status = code => { res.statusCode = code; return res; };
  res.json = body => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(body)); };
  await handler(req, res);
}

async function respond(req, res) {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (['/api/share', '/api/state'].includes(url.pathname)) return await relay(req, res, url);
    const file = path.resolve(ROOT, '.' + (url.pathname === '/' ? '/index.html' : url.pathname));
    if (!file.startsWith(ROOT + path.sep)) { res.writeHead(403); res.end(); return; }
    const content = await fs.readFile(file);
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
    res.end(content);
  } catch { res.writeHead(404); res.end('Not found'); }
}

if (require.main === module) {
  for (const key of ['UPSTASH_REDIS_REST_URL', 'KV_REST_API_URL', 'SHARE_WRITE_KEY']) delete process.env[key];
  http.createServer(respond).listen(8836, '127.0.0.1', () => console.log('Caregiver preview: http://127.0.0.1:8836'));
}

module.exports = { respond };
