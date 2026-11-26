// DEV ONLY: place shafts with each axis around the player on a fresh stone floor and report what happens.
import {createClient, callTool} from 'file:///E:/MC/dev/SkyCodex-0.1.0-beta.2/source/bridge/tools.mjs';
const r = createClient('E:\\MC\\dev\\SkyCodex-0.1.0-beta.2\\source\\run-modpack');
const t = async (n, a = {}) => JSON.parse((await callTool(n, a, r)).content[0].text);
const cmd = c => r('dev_command', {command: c});
const sleep = ms => new Promise(res => setTimeout(res, ms));
async function wait() { for (let i = 0; i < 60; i++) { const s = await t('mc_wait', {seconds: 1}); if (s.job?.state !== 'running') return s; } }
let s = await t('mc_status'); const p = s.position;
await cmd(`fill ${p.x - 4} ${p.y} ${p.z - 4} ${p.x + 4} ${p.y + 2} ${p.z + 4} minecraft:air`);
await cmd(`fill ${p.x - 4} ${p.y - 1} ${p.z - 4} ${p.x + 4} ${p.y - 1} ${p.z + 4} minecraft:stone`);
await cmd('give @s create:shaft 16'); await sleep(800);
for (const [dx, dz, axis] of [[2, 0, 'x'], [2, 0, 'z'], [0, 2, 'x'], [0, 2, 'z'], [2, 2, 'y']]) {
  const at = {x: p.x + dx, y: p.y, z: p.z + dz};
  await cmd(`setblock ${at.x} ${at.y} ${at.z} minecraft:air`); await sleep(300);
  s = await t('mc_status'); const from = JSON.stringify(s.position);
  await t('mc_place', {...at, item: 'create:shaft', properties: {axis}, timeout_seconds: 40});
  s = await wait();
  const b = (await t('mc_scan', {...at, radius: 0, limit: 1})).blocks[0];
  console.log(`cell +${dx},+${dz} axis=${axis} from ${from}: ${s.job.state} now ${JSON.stringify(s.position)} -> ${b ? JSON.stringify(b.state) : 'nothing'} ${s.job.state === 'failed' ? s.job.message.slice(60, 160) : ''}`);
}
