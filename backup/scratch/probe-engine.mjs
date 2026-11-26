// DEV ONLY: trace an oriented cat-engine placement on top of a pipe.
import {createClient, callTool} from 'file:///E:/MC/dev/SkyCodex-0.1.0-beta.2/source/bridge/tools.mjs';
import {writeFile} from 'node:fs/promises';
const r = createClient('E:\\MC\\dev\\SkyCodex-0.1.0-beta.2\\source\\run-modpack');
const t = async (n, a = {}) => { const x = await callTool(n, a, r); return x.content[0].type === 'image' ? x.content[0] : JSON.parse(x.content[0].text); };
const cmd = c => r('dev_command', {command: c});
const sleep = ms => new Promise(res => setTimeout(res, ms));
const facing = process.argv[2] ?? 'north';
await t('mc_cancel').catch(() => {});
let s = await t('mc_status'); const p = s.position;
await cmd(`fill ${p.x - 4} ${p.y} ${p.z - 5} ${p.x + 5} ${p.y + 2} ${p.z + 5} minecraft:air`);
await cmd(`fill ${p.x - 4} ${p.y - 1} ${p.z - 5} ${p.x + 5} ${p.y - 1} ${p.z + 5} minecraft:stone`);
const pipe = {x: p.x + 2, y: p.y, z: p.z}, eng = {x: p.x + 2, y: p.y + 1, z: p.z};
await cmd(`setblock ${pipe.x} ${pipe.y} ${pipe.z} create:fluid_pipe`);
await cmd('give @s laowu:cat_engine 4'); await sleep(800);
s = await t('mc_status');
const before = (s.inventory.find(i => i.item === 'laowu:cat_engine') ?? {count: 0}).count;
console.log('player', JSON.stringify(s.position), 'cell', JSON.stringify(eng), 'want', facing, 'engines', before);
await t('mc_place', {...eng, item: 'laowu:cat_engine', properties: {facing}, timeout_seconds: 40});
let shot = 0;
for (let i = 0; i < 120; i++) {
  await sleep(300);
  s = await t('mc_status');
  const b = (await t('mc_scan', {...eng, radius: 0, limit: 1})).blocks[0];
  const n = (s.inventory.find(it => it.item === 'laowu:cat_engine') ?? {count: 0}).count;
  console.log(`${i} ${s.job.state} pos=${JSON.stringify(s.position)} facing=${s.facing} shift? cell=${b ? b.block + JSON.stringify(b.state) : 'air'} engines=${n}`);
  if (shot < 2 && i % 6 === 3) { const img = await t('mc_screenshot', {max_width: 900}); await writeFile(`E:/MC/dev/scratch/engine-${shot++}.png`, Buffer.from(img.data, 'base64')); }
  if (s.job.state !== 'running') { console.log(s.job.message); break; }
}
