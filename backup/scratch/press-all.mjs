// Press all pulp into cardboard on the depot under the user's press (70,69,482): drop one pulp, wait, take it back
// from the depot top with an empty hand. Picks up anything that missed.  node press-all.mjs
import {t, log, moveTo} from './safe.mjs';
const D = {x: 70, y: 69, z: 482};
const count = (s, id) => s.inventory.filter(i => i.item === id).reduce((a, i) => a + i.count, 0);
await moveTo(70, 69, 480, 0).catch(e => log('move', e.message));
for (let n = 0; n < 12; n++) {
  let s = await t('mc_status');
  if (count(s, 'create:pulp') === 0) break;
  await t('mc_drop', {item: 'create:pulp', count: 1, ...D});
  await new Promise(r => setTimeout(r, 3500));
  await t('mc_equip', {item: 'empty'});
  await t('mc_use_block', {...D, face: 'up'}).catch(e => log('take', e.message));
  await t('mc_wait', {seconds: 1});
  s = await t('mc_status');
  const e = await t('mc_entities', {radius: 12});
  log(`cycle ${n + 1}: pulp ${count(s, 'create:pulp')} cardboard ${count(s, 'create:cardboard')} ground ${(e.ground_items ?? []).map(g => g.item.split(':')[1] + 'x' + g.count + '@' + g.x + ',' + g.z).join(' ')}`);
}
