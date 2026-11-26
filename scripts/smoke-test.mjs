// End-to-end smoke test against the DEV client (gradlew runClient), which auto-creates the world "SkyCodexSmoke",
// enables control and allows setup commands. It drives the same MCP tool layer Codex uses (bridge/tools.mjs).
//   Terminal 1:  gradlew.bat runClient        (Windows)   |  ./gradlew runClient
//   Terminal 2:  node scripts/smoke-test.mjs
// Writes smoke-report.json. Never run this against a real server or a world you care about: it uses /fill and /give.
import {readFile, writeFile, access} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createClient, callTool} from '../bridge/tools.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
// Usage: node scripts/smoke-test.mjs [runDir] [--keep-enabled]   (runDir e.g. run-modpack for the modpack client)
const positional = process.argv.slice(2).filter(a => !a.startsWith('--'));
const keepEnabled = process.argv.includes('--keep-enabled');
const runDir = path.resolve(positional[0] ?? path.join(root, 'run'));
const request = createClient(runDir);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const results = [];
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

async function tool(name, args = {}) {
  const r = await callTool(name, args, request);
  if (r.content[0].type === 'image') return {image: r.content[0]};
  const data = JSON.parse(r.content[0].text);
  return {...data, isError: r.isError};
}
const cmd = command => request('dev_command', {command});
async function waitJob(maxSeconds = 200) {
  const end = Date.now() + maxSeconds * 1000;
  let s;
  do { s = await tool('mc_wait', {seconds: 20}); if (s.job?.state !== 'running') return s; } while (Date.now() < end);
  await tool('mc_stop'); throw new Error('job did not finish in time');
}
const count = (s, id) => (s.inventory ?? []).filter(i => i.item === id).reduce((n, i) => n + i.count, 0);
async function step(name, fn) {
  log('▶', name);
  try {
    const detail = await fn();
    results.push({name, ok: true, detail}); log('  ✔', detail ?? '');
  } catch (e) {
    results.push({name, ok: false, error: e.message}); log('  ✘', e.message);
    try { const s = await tool('mc_status'); if (!s.enabled) log('  (control was disabled: ' + s.last_stop + ')'); } catch {}
  }
}
const expect = (cond, msg) => { if (!cond) throw new Error(msg); };

// ---- wait for the dev client ---------------------------------------------------------------------------------
log('等待开发版客户端：', runDir);
for (let i = 0; ; i++) {
  try {
    await access(path.join(runDir, 'config', 'skycodex-connection.json'));
    const s = await request('status');
    if (s.in_world && s.enabled && s.screen === 'none') break;
  } catch {}
  if (i > 300) throw new Error('5 分钟内未进入世界。请确认 gradlew runClient 正在运行。');
  await sleep(1000);
}
log('已进入世界，开始测试');
await sleep(3000);
let base = (await tool('mc_status')).position;
const at = (dx, dy, dz) => ({x: base.x + dx, y: base.y + dy, z: base.z + dz});

// ---- prepare a flat, empty test area east of the player -------------------------------------------------------
for (const c of ['time set day', 'weather clear', 'gamerule doMobSpawning false', 'kill @e[type=!player]', 'clear @s',
  'effect clear @s', 'fill ~-3 ~-1 ~-3 ~20 ~-1 ~14 minecraft:stone', 'fill ~-3 ~ ~-3 ~20 ~12 ~14 minecraft:air',
  'fill ~-3 ~-2 ~-3 ~20 ~-2 ~14 minecraft:stone']) { await cmd(c); await sleep(150); }
await sleep(1500);
base = (await tool('mc_status')).position;

await step('mc_status 字段完整', async () => {
  const s = await tool('mc_status');
  for (const k of ['position', 'health', 'food', 'inventory', 'hostiles_nearby', 'time_of_day', 'selected_hotbar']) expect(k in s, `missing ${k}`);
  return `version ${s.version} at ${JSON.stringify(s.position)}`;
});

await step('mc_screenshot 缩放', async () => {
  const r = await tool('mc_screenshot', {max_width: 640});
  const png = Buffer.from(r.image.data, 'base64');
  const width = png.readUInt32BE(16);
  expect(width <= 640, `width ${width} > 640`);
  return `${width}px, ${png.length} bytes`;
});

await step('mc_recipes 查询', async () => {
  const r = await tool('mc_recipes', {query: 'crafting_table', limit: 5});
  expect(r.recipes.some(x => x.id === 'minecraft:crafting_table'), 'crafting_table recipe not found');
  return `${r.recipes.length} recipes`;
});

await step('2×2 合成：橡木原木 → 木板', async () => {
  // 12 planks: crafting table 4 + sticks 2 + wooden pickaxe 3, with 3 to spare.
  await cmd('give @s minecraft:oak_log 8'); await sleep(500);
  const before = count(await tool('mc_status'), 'minecraft:oak_planks');
  await tool('mc_craft', {recipe: 'minecraft:oak_planks', times: 3});
  const s = await waitJob(60);
  expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'minecraft:oak_planks') === before + 12, `planks ${count(s, 'minecraft:oak_planks')}`);
  return s.job.message;
});

await step('2×2 合成：工作台、木棍', async () => {
  await tool('mc_craft', {recipe: 'minecraft:crafting_table', times: 1}); let s = await waitJob(60);
  expect(s.job.state === 'succeeded', s.job.message);
  await tool('mc_craft', {recipe: 'minecraft:stick', times: 1}); s = await waitJob(60);
  expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'minecraft:crafting_table') >= 1 && count(s, 'minecraft:stick') >= 4, 'missing table or sticks');
  return 'ok';
});

const tablePos = at(2, 0, 0);
await step('mc_place 放置工作台', async () => {
  await tool('mc_place', {...tablePos, item: 'minecraft:crafting_table'});
  const s = await waitJob(60);
  expect(s.job.state === 'succeeded', s.job.message);
  const scan = await tool('mc_scan', {block: 'crafting_table', radius: 6});
  expect(scan.blocks.some(b => b.x === tablePos.x && b.y === tablePos.y && b.z === tablePos.z), 'table not at target');
  return s.job.message;
});

await step('3×3 合成：木镐（自动开关工作台）', async () => {
  await tool('mc_craft', {recipe: 'minecraft:wooden_pickaxe', times: 1});
  const s = await waitJob(60);
  expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'minecraft:wooden_pickaxe') === 1, 'no pickaxe');
  expect(s.screen === 'none', `crafting screen still open: ${s.screen}`);
  return 'pickaxe crafted, table closed';
});

await step('mc_break 挖回工作台并拾取', async () => {
  const before = count(await tool('mc_status'), 'minecraft:crafting_table');
  await tool('mc_break', {...tablePos});
  const s = await waitJob(60);
  expect(s.job.state === 'succeeded', s.job.message);
  await sleep(1000);
  const after = count(await tool('mc_status'), 'minecraft:crafting_table');
  expect(after === before + 1, `table count ${before} -> ${after}`);
  return 'picked up';
});

await step('mc_collect 采集暴露的原木（多种 ID）', async () => {
  // A 4-high "tree trunk" plus a birch trunk, reachable from the ground.
  await cmd(`fill ${base.x + 5} ${base.y} ${base.z + 3} ${base.x + 5} ${base.y + 3} ${base.z + 3} minecraft:oak_log`);
  await cmd(`fill ${base.x + 7} ${base.y} ${base.z + 3} ${base.x + 7} ${base.y + 1} ${base.z + 3} minecraft:birch_log`);
  await cmd('give @s minecraft:dirt 16'); await sleep(800);
  const ids = 'minecraft:oak_log,minecraft:birch_log';
  const before = count(await tool('mc_status'), 'minecraft:oak_log') + count(await tool('mc_status'), 'minecraft:birch_log');
  await tool('mc_collect', {block: ids, drop_item: ids, count: 5, timeout_seconds: 150});
  const s = await waitJob(170);
  expect(s.job.state === 'succeeded', s.job.message);
  const after = count(s, 'minecraft:oak_log') + count(s, 'minecraft:birch_log');
  expect(after >= before + 5, `logs ${before} -> ${after}`);
  return `logs ${before} -> ${after}`;
});

await step('mc_move 寻路 + 无路时快速失败', async () => {
  await tool('mc_move', {...at(10, 0, 6), radius: 1});
  let s = await waitJob(60);
  expect(s.job.state === 'succeeded', s.job.message);
  // A target sealed in stone: must fail quickly, not wait for the 180 s timeout.
  await cmd(`fill ${base.x - 40} ${base.y - 8} ${base.z - 40} ${base.x - 36} ${base.y - 4} ${base.z - 36} minecraft:stone`);
  const t0 = Date.now();
  await tool('mc_move', {x: base.x - 38, y: base.y - 6, z: base.z - 38, radius: 0, timeout_seconds: 120});
  s = await waitJob(130);
  expect(s.job.state === 'failed', `expected failure, got ${s.job.state}`);
  const secs = Math.round((Date.now() - t0) / 1000);
  expect(secs < 60, `took ${secs}s to fail`);
  return `unreachable target failed after ${secs}s: ${s.job.message}`;
});

await step('mc_eat 进食（饥饿时任务拒绝启动）', async () => {
  await cmd('give @s minecraft:bread 4');
  await cmd('give @s minecraft:rotten_flesh 4');
  await cmd('effect give @s minecraft:hunger 30 255 true');
  for (let i = 0; i < 40; i++) { const s = await tool('mc_status'); if (s.food <= 6) break; await sleep(1000); }
  await cmd('effect clear @s'); await sleep(500);
  let s = await tool('mc_status');
  expect(s.food <= 6, `food did not drop (${s.food})`);
  // begin() throws for low vitals, which the bridge surfaces as a rejected tool call.
  let refused;
  try { const r = await tool('mc_move', {...at(9, 0, 6)}); refused = r.isError || r.job?.state === 'failed'; }
  catch (e) { refused = /food <= 6/.test(e.message); }
  expect(refused, 'move should be refused while hungry');
  const breadBefore = count(s, 'minecraft:bread'), flesh = count(s, 'minecraft:rotten_flesh'), food0 = s.food;
  await tool('mc_eat');
  s = await waitJob(30);
  expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'minecraft:bread') === breadBefore - 1 && count(s, 'minecraft:rotten_flesh') === flesh, 'should eat bread, not rotten flesh');
  expect(s.food > food0, `food ${food0} -> ${s.food}`);
  await cmd('effect give @s minecraft:saturation 3 10 true');
  return `food ${food0} -> ${s.food}`;
});

await step('蓝图施工：楼梯朝向 + 玻璃板/栅栏连接 + 圆石不被当脚手架', async () => {
  const bp = {name: 'smoke', blocks: []};
  for (let x = 0; x < 3; x++) for (let z = 0; z < 3; z++) bp.blocks.push({x, y: 0, z, block: 'minecraft:cobblestone'});
  bp.blocks.push({x: 0, y: 1, z: 0, block: 'minecraft:oak_fence'}, {x: 1, y: 1, z: 0, block: 'minecraft:oak_fence'});
  bp.blocks.push({x: 0, y: 1, z: 2, block: 'minecraft:glass_pane'}, {x: 1, y: 1, z: 2, block: 'minecraft:glass_pane'});
  bp.blocks.push({x: 2, y: 1, z: 1, block: 'minecraft:oak_stairs', properties: {facing: 'west'}});
  bp.blocks.push({x: 2, y: 1, z: 0, block: 'minecraft:oak_stairs', properties: {facing: 'west'}});
  await writeFile(path.join(root, 'blueprints', 'smoke-test.json'), JSON.stringify(bp));
  await cmd('clear @s minecraft:dirt');
  for (const g of ['cobblestone 9', 'oak_fence 2', 'glass_pane 2', 'oak_stairs 2', 'dirt 32']) await cmd('give @s minecraft:' + g);
  await sleep(800);
  base = (await tool('mc_status')).position;
  const origin = {x: base.x + 2, y: base.y, z: base.z - 6};
  await cmd(`fill ${origin.x - 2} ${origin.y} ${origin.z - 2} ${origin.x + 5} ${origin.y + 4} ${origin.z + 5} minecraft:air`);
  await cmd(`fill ${origin.x - 2} ${origin.y - 1} ${origin.z - 2} ${origin.x + 5} ${origin.y - 1} ${origin.z + 5} minecraft:stone`);
  await sleep(500);
  const check = await tool('mc_check_blueprint', {file: 'smoke-test.json', origin});
  expect(check.missing.length === 0 && check.conflict_count === 0, JSON.stringify({missing: check.missing, conflicts: check.conflicts}));
  await tool('mc_build_blueprint', {file: 'smoke-test.json', origin, timeout_seconds: 240});
  const s = await waitJob(260);
  expect(s.job.state === 'succeeded', `${s.job.message} (progress ${s.job.progress}/${bp.blocks.length})`);
  return `${s.job.message}; cobblestone left ${count(s, 'minecraft:cobblestone')}`;
});

if (!keepEnabled) await step('mc_stop 关闭接管', async () => {
  const s = await tool('mc_stop');
  expect(s.enabled === false, 'still enabled');
  return s.last_stop;
});

const report = {date: new Date().toISOString(), platform: process.platform, passed: results.filter(r => r.ok).length, total: results.length, results};
await writeFile(path.join(root, 'smoke-report.json'), JSON.stringify(report, null, 2));
console.log(`\n冒烟测试：${report.passed}/${report.total} 通过。报告：smoke-report.json`);
for (const r of results) console.log(`${r.ok ? '✔' : '✘'} ${r.name}${r.ok ? '' : ' — ' + r.error}`);
process.exitCode = report.passed === report.total ? 0 : 1;
