// Read-only layered map of a box using mc_scan (radius-2 cubes), with block states.
//   node map.mjs x1 y1 z1 x2 y2 z2 [out.json]
import {t} from './safe.mjs';
import {writeFile} from 'node:fs/promises';
const [x1, y1, z1, x2, y2, z2] = process.argv.slice(2, 8).map(Number), out = process.argv[8];
const cells = new Map();
for (let cx = x1 + 2; cx <= x2 + 2; cx += 5) for (let cy = y1 + 2; cy <= y2 + 2; cy += 5) for (let cz = z1 + 2; cz <= z2 + 2; cz += 5) {
  const r = await t('mc_scan', {x: cx, y: cy, z: cz, radius: 2, limit: 128});
  for (const b of r.blocks ?? []) if (b.x >= x1 && b.x <= x2 && b.y >= y1 && b.y <= y2 && b.z >= z1 && b.z <= z2) cells.set(`${b.x},${b.y},${b.z}`, b);
}
if (out) await writeFile(out, JSON.stringify([...cells.values()], null, 1));
// Legend: one letter per distinct block id.
const ids = [...new Set([...cells.values()].map(b => b.block))].sort();
const letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
const key = Object.fromEntries(ids.map((id, i) => [id, letters[i] ?? '?']));
console.log('legend: ' + ids.map(id => `${key[id]}=${id.replace(/^minecraft:/, '')}`).join('  '));
for (let y = y2; y >= y1; y--) {
  console.log(`--- y=${y}   (x ${x1}..${x2} left->right, z ${z1}..${z2} top->bottom)`);
  console.log('      ' + Array.from({length: x2 - x1 + 1}, (_, i) => String((x1 + i) % 10)).join(''));
  for (let z = z1; z <= z2; z++) {
    let row = '';
    for (let x = x1; x <= x2; x++) { const b = cells.get(`${x},${y},${z}`); row += b ? key[b.block] : '.'; }
    console.log(String(z).padStart(5) + ' ' + row);
  }
}
