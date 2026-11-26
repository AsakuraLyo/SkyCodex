// Stand still and wait for an entity of a type to come within reach, then right-click it with the held item.
//   node wait-catch.mjs minecraft:allay create_biotech:empty_cardboard_box [seconds=240]
import {t, log} from './safe.mjs';
const [type, item] = process.argv.slice(2, 4), secs = Number(process.argv[4] ?? 240);
const has = s => s.inventory.filter(i => i.item === item).reduce((a, i) => a + i.count, 0);
let s = await t('mc_status'); const start = has(s);
await t('mc_equip', {item});
const end = Date.now() + secs * 1000; let best = 999, tries = 0;
while (Date.now() < end) {
  const e = await t('mc_entities', {radius: 32, type});
  const near = e.entities?.[0];
  if (near) best = Math.min(best, near.distance);
  if (near && near.distance <= 3.2) {
    tries++;
    try { const r = await t('mc_use_entity', {type}); log('used on', r.used_on, r.result); } catch (err) { log('use:', err.message); }
    await new Promise(r => setTimeout(r, 700));
    s = await t('mc_status');
    if (has(s) < start) { log('captured! box count', start, '->', has(s)); process.exit(0); }
    await t('mc_equip', {item});
  }
  await new Promise(r => setTimeout(r, 500));
}
log(`not captured; closest ${best} blocks, ${tries} tries`); process.exit(1);
