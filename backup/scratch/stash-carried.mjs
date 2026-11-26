// Put whatever the mouse carries into the first empty player-inventory slot of the open menu.
import {createClient, callTool} from 'file:///E:/MC/SkyCodex/bridge/tools.mjs';
const r = createClient('E:\\MC\\.minecraft\\versions\\天空开拓者 SkyExplorer 0.2demo');
const t = async (n, a = {}) => JSON.parse((await callTool(n, a, r)).content[0].text);
const m = await t('mc_menu');
if (m.carried.item === 'minecraft:air') { console.log('nothing carried'); process.exit(0); }
// Prefer stacking onto the same item, otherwise an empty slot.
const same = m.slots.find(s => s.player_inventory && s.item === m.carried.item && s.count < 64);
const empty = m.slots.find(s => s.player_inventory && s.item === 'minecraft:air');
const slot = (same ?? empty)?.slot;
if (slot == null) { console.log('no room for', m.carried.item); process.exit(1); }
await t('mc_click_slot', {container_id: m.container_id, slot, mode: 'pickup', button: 0});
const after = await t('mc_menu');
console.log(`stored ${m.carried.item} x${m.carried.count} in slot ${slot}; carried now ${after.carried.item}`);
