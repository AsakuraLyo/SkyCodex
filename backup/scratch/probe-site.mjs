// Read-only: is the box [x0..x0+sx) x [y0+1..y0+h] x [z0..z0+sz) empty and the floor layer y0 solid?
//   node probe-site.mjs x0 y0 z0 sx sz [h]   (repeatable: pass several groups separated by ';')
import {t} from './safe.mjs';
import {writeFile} from 'node:fs/promises';
async function count(x0, y0, z0, sx, sy, sz) {
  const blocks = [];
  for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) blocks.push({x, y, z, block: 'minecraft:sponge'});
  await writeFile('E:/MC/SkyCodex/blueprints/_occupancy.json', JSON.stringify({blocks}));
  const r = await t('mc_check_blueprint', {file: '_occupancy.json', origin: {x: x0, y: y0, z: z0}});
  return {n: blocks.length, conflicts: r.conflict_count ?? (r.conflicts ?? []).length, sample: (r.conflicts ?? []).slice(0, 6).map(c => `${c.block.replace('minecraft:', '')}@${c.x},${c.y},${c.z}`)};
}
for (const g of process.argv.slice(2).join(' ').split(';')) {
  const [x0, y0, z0, sx, sz, h = 5] = g.trim().split(/\s+/).map(Number);
  const floor = await count(x0, y0, z0, sx, 1, sz), air = await count(x0, y0 + 1, z0, sx, h, sz);
  console.log(`[${x0},${y0},${z0}] ${sx}x${sz}: 地面实心 ${floor.conflicts}/${floor.n}，上方有方块 ${air.conflicts}/${air.n}  ${air.sample.join(' ')}`);
}
