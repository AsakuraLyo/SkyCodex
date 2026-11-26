// Throw cardboard blocks onto the user's upward saw (72,69,482) from the south and collect the boxes that drop out.
// If a block lands on a neighbouring depot instead, take it back and try again.  node saw-all.mjs
import {t, log, moveTo} from './safe.mjs';
const SAW = {x: 72, y: 69, z: 482};
const count = (s, id) => s.inventory.filter(i => i.item === id).reduce((a, i) => a + i.count, 0);
const contents = async () => {
  const m = await t('mc_machines', {radius: 6});
  const out = {};
  for (const l of m.production_lines ?? []) for (const x of l.machines ?? []) if ((x.contents ?? []).length) out[`${x.x},${x.y},${x.z}`] = x.contents.join('/');
  return out;
};
for (let n = 0; n < 8; n++) {
  let s = await t('mc_status');
  log(`round ${n + 1}: blocks ${count(s, 'create:cardboard_block')} boxes ${count(s, 'create_biotech:empty_cardboard_box')}`);
  if (count(s, 'create:cardboard_block') === 0) break;
  await moveTo(72, 69, 483, 0).catch(e => log('move', e.message));
  await t('mc_drop', {item: 'create:cardboard_block', count: 1, ...SAW});
  await new Promise(r => setTimeout(r, 4000));
  const e = await t('mc_entities', {radius: 16});
  const ground = (e.ground_items ?? []);
  log('ground:', ground.map(g => `${g.item.split(':')[1]}x${g.count}@${g.x},${g.y},${g.z}`).join(' ') || 'none');
  for (const g of ground) await moveTo(Math.floor(g.x), Math.floor(g.y), Math.floor(g.z), 0).catch(err => log('pick', err.message));
  const c = await contents();
  for (const [k, v] of Object.entries(c)) if (/cardboard/.test(v)) {
    log('on machine', k, v);
    const [x, y, z] = k.split(',').map(Number);
    await t('mc_equip', {item: 'empty'});
    await t('mc_use_block', {x, y, z, face: 'up'}).catch(err => log('take', err.message));
    await t('mc_wait', {seconds: 1});
  }
}
const s = await t('mc_status');
log(`done: blocks ${count(s, 'create:cardboard_block')} boxes ${count(s, 'create_biotech:empty_cardboard_box')}`);
