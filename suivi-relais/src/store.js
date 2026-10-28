// Stockage du relais : une ligne par identifiant de flux dans la base D1.
// L'adaptateur est la seule pièce qui connaît le SQL, donc celle que les tests
// remplacent par une base en mémoire.

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
 * Adaptateur D1 (Cloudflare). `read` rend la ligne décodée ou `null` ;
 * `write` rend `false` quand une **autre clé** a déjà lié ce flux ; les erreurs
 * de base remontent (le worker répond alors 503, jamais un faux succès).
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
      if (!(await ensure())) throw new Error('schéma indisponible');
      const row = await db.prepare(SELECT).bind(id).first();
      if (!row) return null;
      let doc;
      try { doc = JSON.parse(row.doc); } catch { return null; }
      return { ...doc, serverAt: row.updated_at, key: String(row.write_key ?? '') };
    },
    async write(id, doc, key, now) {
      if (!(await ensure())) throw new Error('schéma indisponible');
      const out = await db.prepare(UPSERT).bind(id, JSON.stringify(doc), now, key).run();
      if (!out || out.success === false) throw new Error('écriture refusée');
      const changes = out.meta ? out.meta.changes : 1;
      return changes !== 0;
    },
    async prune(before) {
      const out = await db.prepare(PRUNE).bind(before).run();
      return out && out.success !== false;
    },
  };
}
