// Open containers one by one (read only: no clicks) and list their contents.
//   node check-containers.mjs "x,y,z;x,y,z;..." [out.json]
import {t, log, moveTo} from './safe.mjs';
import {writeFile} from 'node:fs/promises';
const list = process.argv[2].split(';').map(s => s.split(',').map(Number));
const out = process.argv[3], all = {}, totals = {};
for (const [x, y, z] of list) {
  const key = `${x},${y},${z}`;
  try {
    let s = await t('mc_status');
    if (s.screen !== 'none') await t('mc_close_menu');
    const d = Math.hypot(s.position.x - x, s.position.y - y, s.position.z - z);
    if (d > 4) { try { await moveTo(x, y, z, 2); } catch (e) { log(key, '走不到:', e.message); } }
    await t('mc_use_block', {x, y, z});
    for (let i = 0; i < 10; i++) { s = await t('mc_wait', {seconds: 1}); if (s.job?.state !== 'running') break; }
    if (s.job?.state === 'failed') { log(key, '打不开:', s.job.message); continue; }
    await new Promise(r => setTimeout(r, 600));
    const m = await t('mc_menu');
    // Container slots only: the player inventory part of the menu is listed too, skip those.
    const slots = (m.slots ?? []).filter(sl => sl.item && sl.item !== 'minecraft:air' && !(sl.player_inventory || sl.inventory === 'player' || sl.container === 'player'));
    const items = {};
    for (const sl of slots) { items[sl.item] = (items[sl.item] ?? 0) + sl.count; }
    all[key] = {menu: m.menu_type ?? m.type ?? m.title, items};
    for (const [id, n] of Object.entries(items)) totals[id] = (totals[id] ?? 0) + n;
    log(key, m.title ?? m.menu_type ?? '', Object.entries(items).map(([k, n]) => `${k.replace(/^minecraft:/, '')}×${n}`).join(' ') || '(空)');
    await t('mc_close_menu');
  } catch (e) { log(key, '错误:', e.message); }
}
if (out) await writeFile(out, JSON.stringify({all, totals}, null, 1));
