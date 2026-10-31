#!/usr/bin/env python3
"""Prepare l'etiquetage main des crops collectes (stdlib uniquement).

Entree : `lightcrops-*.jsonl` (voir LightCropLogger : une ligne = box +
verdict + PNG base64). Sortie dans OUT : `crops/NNNN.png` + `review.html`
(grille R/G/BAD, clavier r/g/b, bouton Export -> labels.csv a enregistrer
a cote). Usage : `python3 tools/review_light_crops.py IN.jsonl OUT/`
puis ouvrir OUT/review.html dans Chrome.
"""

import base64
import json
import sys
from pathlib import Path

PAGE = """<!DOCTYPE html>
<html lang="fr"><head><meta charset="utf-8"><title>Feux pietons - etiquetage</title>
<style>
body{font-family:sans-serif;background:#111;color:#eee;margin:0;padding:12px}
#bar{position:sticky;top:0;background:#111;padding:8px 0;display:flex;gap:12px;align-items:center}
button{font-size:16px;padding:6px 14px;cursor:pointer}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:10px}
.cell{background:#222;border:3px solid #555;border-radius:8px;padding:6px;text-align:center}
.cell img{width:100%;image-rendering:pixelated;background:#000;border-radius:4px}
.cell.R{border-color:#e74c3c}.cell.G{border-color:#2ecc71}.cell.BAD{border-color:#888;opacity:.55}
.cell .id{font-size:11px;color:#aaa}
.cell .v{font-size:11px;color:#f1c40f}
.keys{display:flex;gap:6px}
.keys button{flex:1}
</style></head><body>
<div id="bar"><b id="count"></b><span>r = rouge, g = vert, b = rebus (flou/coupé/pas un feu)</span>
<button id="exp">Exporter labels.csv</button></div>
<div class="grid" id="grid"></div>
<script>
const crops = CROPS_JSON;
const labels = {};
function render(){
  const grid = document.getElementById('grid');
  grid.innerHTML = '';
  let done = 0;
  crops.forEach((c, i) => {
    const lab = labels[c.id];
    if (lab) done++;
    const d = document.createElement('div');
    d.className = 'cell' + (lab ? ' ' + lab : '');
    d.innerHTML = `<img src="crops/${c.id}.png"><div class="id">${c.id}</div><div class="v"> machine: ${c.verdict}</div>
      <div class="keys"><button data-l="R">R</button><button data-l="G">G</button><button data-l="BAD">B</button></div>`;
    d.querySelectorAll('button').forEach(b => b.onclick = () => { labels[c.id] = b.dataset.l; render(); });
    grid.appendChild(d);
  });
  document.getElementById('count').textContent = `${done}/${crops.length} etiquetes`;
}
document.addEventListener('keydown', e => {
  const k = e.key.toLowerCase();
  const map = {r:'R', g:'G', b:'BAD'};
  if (!(k in map)) return;
  const next = crops.find(c => !(c.id in labels));
  if (next) { labels[next.id] = map[k]; render(); }
});
document.getElementById('exp').onclick = () => {
  let csv = 'name,label\\n';
  crops.forEach(c => { csv += `${c.id}.png,${labels[c.id] || ''}\\n`; });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([csv], {type: 'text/csv'}));
  a.download = 'labels.csv';
  a.click();
};
render();
</script></body></html>
"""


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, outdir = Path(sys.argv[1]), Path(sys.argv[2])
    crops_dir = outdir / "crops"
    crops_dir.mkdir(parents=True, exist_ok=True)
    crops = []
    for i, line in enumerate(src.read_text().splitlines()):
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        cid = f"{i:04d}"
        (crops_dir / f"{cid}.png").write_bytes(base64.b64decode(rec["png"]))
        crops.append({"id": cid, "verdict": rec.get("verdict", "?")})
    page = PAGE.replace("CROPS_JSON", json.dumps(crops))  # ids chiffres : rien a echapper
    (outdir / "review.html").write_text(page)
    print(f"{len(crops)} crops -> {crops_dir}/ + review.html")
    print("Ouvrez review.html, etiquetez (r/g/b), Exportez labels.csv dans " + str(outdir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
