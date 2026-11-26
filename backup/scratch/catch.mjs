// Walk to the nearest entity of a type and right-click it with a held item (e.g. capture an allay in a cardboard box).
//   node catch.mjs minecraft:allay create_biotech:empty_cardboard_box [tries=8]
import {t, log} from './safe.mjs';
const [type, item] = process.argv.slice(2, 4), tries = Number(process.argv[4] ?? 8);
const has = s => s.inventory.filter(i => i.item === item).reduce((a, i) => a + i.count, 0);
let s = await t('mc_status'); const start = has(s);
for (let n = 1; n <= tries; n++) {
  const e = await t('mc_entities', {radius: 64, type});
  const near = e.entities?.[0];
  if (!near) { log('no', type, 'nearby'); process.exit(1); }
  log(`try ${n}: ${type} at ${JSON.stringify(near.pos)} d=${near.distance}`);
  if (near.distance > 2.8) {
    await t('mc_move', {x: near.pos.x, y: near.pos.y, z: near.pos.z, radius: 1, timeout_seconds: 60});
    for (let i = 0; i < 5; i++) { const w = await t('mc_wait', {seconds: 12}); if (w.job?.state !== 'running') { log('move:', w.job?.state, w.job?.message ?? ''); break; } }
  }
  await t('mc_equip', {item});
  try {
    const r = await t('mc_use_entity', {type});
    log('used on', r.used_on, r.result);
  } catch (err) { log('use:', err.message); continue; }
  await t('mc_wait', {seconds: 2});
  s = await t('mc_status');
  if (has(s) < start) { log('captured! box count', start, '->', has(s)); process.exit(0); }
}
log('not captured'); process.exit(1);
