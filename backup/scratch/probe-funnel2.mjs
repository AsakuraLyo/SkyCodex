// DEV ONLY: walk to each side of the chest first, then place a funnel pointing away from it.
import {createClient, callTool} from 'file:///E:/MC/dev/SkyCodex-0.1.0-beta.2/source/bridge/tools.mjs';
const r = createClient('E:\\MC\\dev\\SkyCodex-0.1.0-beta.2\\source\\run-modpack');
const t = async (n, a = {}) => JSON.parse((await callTool(n, a, r)).content[0].text);
const sleep = ms => new Promise(res => setTimeout(res, ms));
async function wait() { for (let i = 0; i < 60; i++) { const s = await t('mc_wait', {seconds: 1}); if (s.job?.state !== 'running') return s; } }
const chest = (await t('mc_scan', {block: 'minecraft:chest', radius: 10, limit: 1})).blocks[0];
console.log('chest', JSON.stringify(chest));
for (const [dx, dz, facing] of [[0, -1, 'north'], [1, 0, 'east']]) {
  const at = {x: chest.x + dx, y: chest.y, z: chest.z + dz};
  await t('mc_move', {x: chest.x + 3 * dx, y: chest.y, z: chest.z + 3 * dz, radius: 0}); let s = await wait();
  console.log('moved', s.job.state, JSON.stringify(s.position));
  await t('mc_place', {...at, item: 'create:andesite_funnel', properties: {facing}, timeout_seconds: 40});
  s = await wait();
  const b = (await t('mc_scan', {...at, radius: 0, limit: 1})).blocks[0];
  console.log(`want ${facing}: ${s.job.state} -> ${b ? JSON.stringify(b.state) : 'nothing'} ${s.job.state === 'failed' ? s.job.message.slice(0, 120) : ''}`);
}
