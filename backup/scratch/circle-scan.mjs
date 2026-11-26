// Fly a circle around a centre without landing, scanning for an entity type each leg; land near the first one found.
//   node circle-scan.mjs CX CZ RADIUS ALT type
import {t, log} from './safe.mjs';
const [CX, CZ, R, ALT] = process.argv.slice(2, 6).map(Number), type = process.argv[6] ?? 'minecraft:allay';
const scan = async () => { const e = await t('mc_entities', {radius: 96, type}); return e.entities ?? []; };
let found = (await scan())[0];
for (let k = 0; k < 10 && !found; k++) {
  const ang = k * Math.PI / 4;
  const x = Math.round(CX + R * Math.cos(ang)), z = Math.round(CZ + R * Math.sin(ang));
  await t('mc_fly', {x, y: ALT, z, radius: 20, cruise_height: 30, land: false, timeout_seconds: 120});
  let w; for (let i = 0; i < 10; i++) { w = await t('mc_wait', {seconds: 12}); if (w.job?.state !== 'running') break; }
  log(`leg ${k + 1} -> ${x},${z}: ${w.job?.state} ${w.job?.message ?? ''} hp ${Math.round(w.health)}`);
  if (w.job?.state !== 'succeeded') break;
  found = (await scan())[0];
}
if (!found) { log('none found; landing at centre'); }
const tgt = found ? found.pos : {x: CX, y: ALT, z: CZ};
log('landing near', JSON.stringify(tgt));
await t('mc_fly', {x: tgt.x, y: tgt.y, z: tgt.z, radius: 6, cruise_height: 20, land: true, timeout_seconds: 300});
let w; for (let i = 0; i < 26; i++) { w = await t('mc_wait', {seconds: 12}); if (w.job?.state !== 'running') break; }
log(`landed: ${w.job?.state} ${w.job?.message ?? ''} hp ${Math.round(w.health)}`);
const e = await scan();
log('allays now:', e.map(a => `${JSON.stringify(a.pos)} d=${a.distance}`).join(' ') || 'none');
