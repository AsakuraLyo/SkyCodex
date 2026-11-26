// Read speeds of the new line and the power source (0 rpm on a source = network overstressed).
import {t} from './safe.mjs';
const out = [];
for (const [block, r] of [['create:mechanical_mixer', 8], ['create:millstone', 8], ['create:cogwheel', 8]]) {
  const m = await t('mc_machines', {radius: r, block});
  for (const l of m.production_lines ?? []) for (const x of l.machines ?? []) { const p = x.pos ?? x; if (p.z >= 494 && p.z <= 499) out.push(`${x.block.replace(/^[^:]+:/, '')}@${p.x},${p.y},${p.z}=${x.speed_rpm ?? '?'}`); }
}
console.log('line:', out.join(' '));
