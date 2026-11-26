// DEV ONLY: shaft wall with a gap; walk across and trace position / jumps / replans every second.
import {createClient, callTool} from 'file:///E:/MC/dev/SkyCodex-0.1.0-beta.2/source/bridge/tools.mjs';
import {writeFile} from 'node:fs/promises';
const r = createClient('E:\\MC\\dev\\SkyCodex-0.1.0-beta.2\\source\\run-modpack');
const t = async (n, a = {}) => { const x = await callTool(n, a, r); return x.content[0].type === 'image' ? x.content[0] : JSON.parse(x.content[0].text); };
const cmd = c => r('dev_command', {command: c});
const sleep = ms => new Promise(res => setTimeout(res, ms));
let s = await t('mc_status'); const p = s.position;
await cmd(`fill ${p.x - 2} ${p.y} ${p.z - 6} ${p.x + 9} ${p.y + 2} ${p.z + 6} minecraft:air`);
await cmd(`fill ${p.x - 2} ${p.y - 1} ${p.z - 6} ${p.x + 9} ${p.y - 1} ${p.z + 6} minecraft:stone`);
await cmd(`fill ${p.x + 3} ${p.y} ${p.z - 6} ${p.x + 3} ${p.y + 1} ${p.z + 3} create:shaft[axis=z]`);
await sleep(800);
console.log('start', JSON.stringify(p), 'goal', JSON.stringify({x: p.x + 7, y: p.y, z: p.z}), 'pathing', JSON.stringify(s.pathing));
await t('mc_move', {x: p.x + 7, y: p.y, z: p.z, radius: 0, timeout_seconds: 40});
for (let i = 0; i < 45; i++) {
  await sleep(1000);
  s = await t('mc_status');
  console.log(`t${i} ${s.job.state} pos=${JSON.stringify(s.position)} jumps=${s.job.auto_jumps ?? 0} replans=${s.job.replans ?? 0} escapes=${s.job.escapes ?? 0} ${s.job.detail?.stuck_on ? 'stuck_on=' + JSON.stringify(s.job.detail.stuck_on) : ''} ${s.job.state !== 'running' ? s.job.message + ' learned=' + JSON.stringify(s.pathing?.learned_obstacles) : ''}`);
  if (s.job.state !== 'running') break;
  if (i === 6) { const img = await t('mc_screenshot', {max_width: 960}); await writeFile('E:/MC/dev/scratch/path-probe.png', Buffer.from(img.data, 'base64')); }
}
