// Fill the 5x5 crafter block at x76-80, y70-74, z=475 (fronts face south) with the crushing wheel recipe.
// Verifies each insert by the held item count dropping (mc_machines does not show crafter contents).
//   node fill-crafters.mjs [skip=x,y;x,y]
import {t, log, moveTo} from './safe.mjs';
const A = 'create:andesite_alloy', P = 'minecraft:acacia_planks', S = 'minecraft:stone';
const rows = [            // top (y74) to bottom (y70), x76..x80
  [null, A, A, A, null],
  [A, A, P, A, A],
  [A, P, S, P, A],
  [A, A, P, A, A],
  [null, A, A, A, null],
];
const skip = new Set((process.argv[2] ?? '').replace(/^skip=/, '').split(';').filter(Boolean));
const cells = [];
rows.forEach((row, r) => row.forEach((item, c) => { if (item) cells.push({x: 76 + c, y: 74 - r, z: 475, item}); }));
const count = (s, item) => s.inventory.filter(i => i.item === item).reduce((a, i) => a + i.count, 0);
let done = 0; const failed = [];
for (const cell of cells) {
  if (skip.has(`${cell.x},${cell.y}`)) continue;
  const stand = {x: cell.x <= 77 ? 77 : cell.x >= 79 ? 79 : 78, y: 69, z: 477};
  let s = await t('mc_status');
  if (Math.abs(s.position.x - stand.x) > 1 || Math.abs(s.position.z - stand.z) > 1) { try { await moveTo(stand.x, stand.y, stand.z, 0); } catch (e) { log('move', e.message); } }
  await t('mc_equip', {item: cell.item});
  s = await t('mc_status');
  const before = count(s, cell.item);
  await t('mc_use_block', {x: cell.x, y: cell.y, z: cell.z});
  for (let i = 0; i < 5; i++) { const w = await t('mc_wait', {seconds: 1}); if (w.job?.state !== 'running') { s = w; break; } }
  s = await t('mc_status');
  const ok = count(s, cell.item) < before;
  log(`${cell.x},${cell.y} <- ${cell.item.split(':')[1]} : ${ok ? 'ok' : 'NOT INSERTED ' + (s.job?.message ?? '')}`);
  if (ok) done++; else failed.push(`${cell.x},${cell.y}`);
}
log(`filled ${done}; failed: ${failed.join(';') || 'none'}`);
