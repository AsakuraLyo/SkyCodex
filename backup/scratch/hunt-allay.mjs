// Walk toward the nearest allay (as close as pathing allows) and grab it with a box when within reach.
//   node hunt-allay.mjs <boxItem> [minutes=6]
import {t, log} from './safe.mjs';
const item = process.argv[2], mins = Number(process.argv[3] ?? 6);
const has = s => s.inventory.filter(i => i.item === item).reduce((a, i) => a + i.count, 0);
let s = await t('mc_status'); const start = has(s);
const end = Date.now() + mins * 60000;
const tryUse = async () => {
  await t('mc_equip', {item});
  try { const r = await t('mc_use_entity', {type: 'minecraft:allay'}); log('used on', r.used_on, r.result); } catch { return false; }
  await new Promise(r => setTimeout(r, 700));
  s = await t('mc_status');
  return has(s) < start;
};
while (Date.now() < end) {
  const e = await t('mc_entities', {radius: 96, type: 'minecraft:allay'});
  const a = e.entities?.[0];
  if (!a) { await new Promise(r => setTimeout(r, 3000)); continue; }
  if (a.distance <= 3.2) { if (await tryUse()) { log('captured!'); process.exit(0); } continue; }
  // walk toward it; Baritone gets as close as it can within 4 blocks, else try a point below it on the ground
  for (const [dy, r] of [[0, 3], [-1, 3], [-2, 4], [-4, 4]]) {
    await t('mc_move', {x: a.pos.x, y: a.pos.y + dy, z: a.pos.z, radius: r, timeout_seconds: 40});
    let w; for (let i = 0; i < 4; i++) { w = await t('mc_wait', {seconds: 10}); if (w.job?.state !== 'running') break; }
    if (w.job?.state === 'succeeded') break;
  }
  const e2 = await t('mc_entities', {radius: 8, type: 'minecraft:allay'});
  if (e2.entities?.[0] && e2.entities[0].distance <= 3.2 && await tryUse()) { log('captured!'); process.exit(0); }
  log('nearest allay now', e2.entities?.[0]?.distance ?? '>8');
}
log('not captured'); process.exit(1);
