// Find columns near X,Z whose highest block is near ground level Y (open sky down to the ground).
//   node open-ground.mjs X Y Z [range=12] [step=3]
import {t} from './safe.mjs';
const [X, Y, Z] = process.argv.slice(2, 5).map(Number), R = Number(process.argv[5] ?? 12), S = Number(process.argv[6] ?? 3);
const out = [];
for (let dx = -R; dx <= R; dx += S) for (let dz = -R; dz <= R; dz += S) {
  let top = null;
  for (let y = Y + 40; y >= Y - 6; y--) {
    const s = await t('mc_scan', {x: X + dx, y, z: Z + dz, radius: 0, limit: 1});
    const b = s.blocks?.[0]?.block;
    if (b && b !== 'minecraft:air') { top = {y, b}; break; }
  }
  if (top && top.y <= Y + 4) out.push(`${X + dx},${top.y},${Z + dz} ${top.b}`);
}
console.log(out.join('\n') || 'none');
