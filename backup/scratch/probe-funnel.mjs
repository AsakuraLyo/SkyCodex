// DEV ONLY: which funnel facings can be placed on each side of a chest.
import {createClient, callTool} from 'file:///E:/MC/dev/SkyCodex-0.1.0-beta.2/source/bridge/tools.mjs';
const r = createClient('E:\\MC\\dev\\SkyCodex-0.1.0-beta.2\\source\\run-modpack');
const t = async (n, a = {}) => JSON.parse((await callTool(n, a, r)).content[0].text);
const cmd = c => r('dev_command', {command: c});
const sleep = ms => new Promise(res => setTimeout(res, ms));
async function wait() { for (let i = 0; i < 60; i++) { const s = await t('mc_wait', {seconds: 1}); if (s.job?.state !== 'running') return s; } }
let s = await t('mc_status'); const p = s.position;
const chest = {x: p.x + 3, y: p.y, z: p.z};
await cmd(`fill ${p.x + 1} ${p.y} ${p.z - 3} ${p.x + 6} ${p.y + 2} ${p.z + 3} minecraft:air`);
await cmd(`setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest`);
await cmd('give @s create:andesite_funnel 16');
await sleep(800);
for (const [dx, dz, side] of [[0, 1, 'south'], [0, -1, 'north'], [1, 0, 'east']]) {
  const at = {x: chest.x + dx, y: chest.y, z: chest.z + dz};
  for (const facing of [side, {south: 'north', north: 'south', east: 'west', west: 'east'}[side]]) {
    await cmd(`setblock ${at.x} ${at.y} ${at.z} minecraft:air`); await sleep(300);
    await t('mc_place', {...at, item: 'create:andesite_funnel', properties: {facing}, timeout_seconds: 40});
    s = await wait();
    const b = (await t('mc_scan', {...at, radius: 0, limit: 1})).blocks[0];
    console.log(`${side} side, want facing=${facing}: ${s.job.state} screen=${s.screen} -> ${b ? JSON.stringify(b.state) : 'nothing'}  ${s.job.state === 'failed' ? s.job.message.slice(0, 90) : ''}`);
  }
}
