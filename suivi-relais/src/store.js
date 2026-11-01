// Relay storage: one row per stream id in the D1 base.
// The adapter is the only piece knowing SQL, so the one tests
// replace with an in-memory base.

const SCHEMA = `CREATE TABLE IF NOT EXISTS streams (
  id TEXT PRIMARY KEY,
  doc TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  write_key TEXT NOT NULL
)`;

const SELECT = 'SELECT doc, updated_at, write_key FROM streams WHERE id = ?';

const UPSERT = `INSERT INTO streams (id, doc, updated_at, write_key) VALUES (?, ?, ?, ?)
ON CONFLICT(id) DO UPDATE SET doc = excluded.doc, updated_at = excluded.updated_at
WHERE streams.write_key = excluded.write_key`;

const PRUNE = 'DELETE FROM streams WHERE updated_at < ?';

/**
 * D1 adapter (Cloudflare). `read` yields the decoded row or `null`;
 * `write` yields `false` when **another key** already bound this stream; base
 * errors bubble up (the worker then answers 503, never a fake success).
 */
export function d1Store(db) {
  let ready = null;
  const ensure = () => {
    if (!ready) ready = db.prepare(SCHEMA).run().then(
      () => true,
      () => { ready = null; return false; },
    );
    return ready;
  };
  return {
    async read(id) {
      if (!(await ensure())) throw new Error('schema unavailable');
      const row = await db.prepare(SELECT).bind(id).first();
      if (!row) return null;
      let doc;
      try { doc = JSON.parse(row.doc); } catch { return null; }
      return { ...doc, serverAt: row.updated_at, key: String(row.write_key ?? '') };
    },
    async write(id, doc, key, now) {
      if (!(await ensure())) throw new Error('schema unavailable');
      const out = await db.prepare(UPSERT).bind(id, JSON.stringify(doc), now, key).run();
      if (!out || out.success === false) throw new Error('write rejected');
      const changes = out.meta ? out.meta.changes : 1;
      return changes !== 0;
    },
    async prune(before) {
      const out = await db.prepare(PRUNE).bind(before).run();
      return out && out.success !== false;
    },
  };
}
