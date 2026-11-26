// One press cycle helper: make hotbar slot 2 (index 1) empty, optionally drop one item aimed at the depot top.
//   node press-cycle.mjs [dropItem]
import {t, log} from './safe.mjs';
const drop = process.argv[2];
let s = await t('mc_status');
const inSlot1 = s.inventory.find(i => i.inventory_index === 1);
if (inSlot1) { await t('mc_click_slot', {container_id: 0, slot: 37, mode: 'quick_move', button: 0}); }
if (drop) {
  const have = s.inventory.filter(i => i.item === drop).reduce((a, i) => a + i.count, 0);
  if (have > 0) await t('mc_drop', {item: drop, count: 1, x: 70, y: 69, z: 482});
  else await t('mc_drop', {item: 'minecraft:rotten_flesh', count: 1, x: 70, y: 69, z: 482}).catch(() => {});
}
s = await t('mc_status');
log('slot1', s.inventory.find(i => i.inventory_index === 1)?.item ?? 'empty',
    '| pulp', s.inventory.filter(i => i.item === 'create:pulp').reduce((a, i) => a + i.count, 0),
    '| cardboard', s.inventory.filter(i => i.item === 'create:cardboard').reduce((a, i) => a + i.count, 0));
