// DEV ONLY: from the east side, ask for the north-side funnel (needs the automatic walk to a stand position).
import {createClient, callTool} from 'file:///E:/MC/dev/SkyCodex-0.1.0-beta.2/source/bridge/tools.mjs';
const r = createClient('E:\\MC\\dev\\SkyCodex-0.1.0-beta.2\\source\\run-modpack');
const t = async (n, a = {}) => JSON.parse((await callTool(n, a, r)).content[0].text);
const sleep = ms => new Promise(res => setTimeout(res, ms));
const chest = (await t('mc_scan', {block: 'minecraft:chest', radius: 10, limit: 1})).blocks[0];
const at = {x: chest.x, y: chest.y, z: chest.z - 1};
await r('dev_command', {command: `setblock ${at.x} ${at.y} ${at.z} minecraft:air`}); await sleep(400);
let s = await t('mc_status'); console.log('player', JSON.stringify(s.position), 'cell', JSON.stringify(at));
await t('mc_place', {...at, item: 'create:andesite_funnel', properties: {facing: 'north'}, timeout_seconds: 40});
for (let i = 0; i < 60; i++) {
  s = await t('mc_status');
  console.log(`t${i} ${s.job.state} pos=${JSON.stringify(s.position)} facing=${s.facing} shift? ${s.job.message.slice(0, 100)}`);
  if (s.job.state !== 'running') break;
  await sleep(400);
}
console.log(JSON.stringify((await t('mc_scan', {...at, radius: 0, limit: 1})).blocks[0]));
