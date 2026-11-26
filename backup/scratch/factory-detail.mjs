// Read-only: detailed machine list for lines overlapping a box.  node factory-detail.mjs x1 z1 x2 z2 [radius] [regex]
import {t} from './safe.mjs';
import {writeFile} from 'node:fs/promises';
const [x1, z1, x2, z2] = process.argv.slice(2, 6).map(Number), radius = Number(process.argv[6] ?? 48);
const re = process.argv[7] ? new RegExp(process.argv[7]) : null;
const m = await t('mc_machines', {radius});
await writeFile('E:/MC/SkyCodex/surveys/base-factory-machines.json', JSON.stringify(m, null, 1));
for (const l of m.production_lines ?? []) {
  if (l.to.x < x1 || l.from.x > x2 || l.to.z < z1 || l.from.z > z2) continue;
  console.log(`== line ${JSON.stringify(l.from)}~${JSON.stringify(l.to)} ${l.kinetics ?? ''} machines=${(l.machines ?? []).length}`);
  for (const x of l.machines ?? []) {
    if (re && !re.test(x.block)) continue;
    const p = x.pos ?? {x: x.x, y: x.y, z: x.z};
    console.log(`  ${x.block} @${p.x},${p.y},${p.z}${x.speed_rpm != null ? ' ' + x.speed_rpm + 'rpm' : ''}${x.stress ? ' stress ' + JSON.stringify(x.stress) : ''}${x.contents ? ' :: ' + JSON.stringify(x.contents).slice(0, 120) : ''}`);
  }
}
const one = (m.production_lines ?? []).flatMap(l => l.machines ?? [])[0];
console.log('fields:', JSON.stringify(one));
