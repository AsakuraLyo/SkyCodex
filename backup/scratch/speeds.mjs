// Read-only: kinetic speeds of Create blocks inside a box.  node speeds.mjs x1 z1 x2 z2 [radius]
import {t} from './safe.mjs';
const [x1, z1, x2, z2] = process.argv.slice(2, 6).map(Number), radius = Number(process.argv[6] ?? 24);
const m = await t('mc_machines', {radius, block: 'create:'});
const seen = new Set();
for (const l of m.production_lines ?? []) for (const x of l.machines ?? []) {
  const p = x.pos ?? x;
  if (p.x < x1 || p.x > x2 || p.z < z1 || p.z > z2) continue;
  const k = `${p.x},${p.y},${p.z}`; if (seen.has(k)) continue; seen.add(k);
  const extra = Object.entries(x).filter(([k]) => !['x', 'y', 'z', 'pos', 'block', 'contents'].includes(k)).map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(' ');
  console.log(`${x.block.replace('create:', '')} @${k} ${extra}`);
}
