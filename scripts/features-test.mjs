// DEV ONLY: end-to-end test of foraging (harvest / smelt / hunt / auto-eat), FTB Quests and production-line scanning
// in the dev client test world (gradlew runClient / runModpack). Uses /fill, /setblock, /give, /summon.
//   node scripts/features-test.mjs [runDir]          e.g. node scripts/features-test.mjs run-modpack
// Writes features-report.json. Never run against a real world.
import {writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createClient, callTool} from '../bridge/tools.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const runDir = path.resolve(process.argv.slice(2).find(a => !a.startsWith('--')) ?? path.join(root, 'run'));
const request = createClient(runDir);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const results = [];
const cmd = async c => { await request('dev_command', {command: c}); await sleep(120); };
async function tool(name, args = {}) {
  const r = await callTool(name, args, request);
  if (r.content[0].type === 'image') return r.content[0];
  return {...JSON.parse(r.content[0].text), isError: r.isError};
}
async function waitJob(maxSeconds = 200) {
  const end = Date.now() + maxSeconds * 1000; let s;
  do { s = await tool('mc_wait', {seconds: 20}); if (s.job?.state !== 'running' && s.sequence?.state !== 'running') return s; } while (Date.now() < end);
  await tool('mc_stop'); throw new Error('job did not finish in time');
}
const count = (s, id) => (s.inventory ?? []).filter(i => i.item === id).reduce((n, i) => n + i.count, 0);
const expect = (c, m) => { if (!c) throw new Error(m); };
// --only=自动进食,领奖,蓝图大炮 runs just the steps whose names contain one of these texts.
const only = (process.argv.find(a => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
async function step(name, fn) {
  if (only.length && !only.some(o => name.includes(o))) return;
  log('▶', name);
  try { const detail = await fn(); results.push({name, ok: true, detail}); log('  ✔', detail ?? ''); }
  catch (e) { results.push({name, ok: false, error: e.message}); log('  ✘', e.message); }
}

let s = await tool('mc_status');
if (!s.in_world || !s.enabled) throw new Error('dev client must be in a world with control enabled');
for (const c of ['time set day', 'weather clear', 'gamerule doMobSpawning false', 'kill @e[type=!player]', 'clear @s', 'effect clear @s',
  'effect give @s minecraft:resistance infinite 4 true']) await cmd(c);
let b = (await tool('mc_status')).position;
// 30 x 30 flat grass test area around the player.
for (let y = 0; y <= 6; y++) await cmd(`fill ${b.x - 15} ${b.y + y} ${b.z - 15} ${b.x + 15} ${b.y + y} ${b.z + 15} minecraft:air`);
await cmd(`fill ${b.x - 15} ${b.y - 1} ${b.z - 15} ${b.x + 15} ${b.y - 1} ${b.z + 15} minecraft:grass_block`);
await cmd(`fill ${b.x - 15} ${b.y - 2} ${b.z - 15} ${b.x + 15} ${b.y - 2} ${b.z + 15} minecraft:stone`);
await sleep(1000);

await step('mc_harvest 收获成熟作物并补种', async () => {
  const f = {x: b.x + 4, y: b.y, z: b.z};
  await cmd(`fill ${f.x} ${f.y - 1} ${f.z} ${f.x + 3} ${f.y - 1} ${f.z + 1} minecraft:farmland[moisture=7]`);
  await cmd(`fill ${f.x} ${f.y} ${f.z} ${f.x + 3} ${f.y} ${f.z} minecraft:wheat[age=7]`);
  await cmd(`fill ${f.x} ${f.y} ${f.z + 1} ${f.x + 1} ${f.y} ${f.z + 1} minecraft:carrots[age=7]`);
  await cmd(`setblock ${f.x + 2} ${f.y} ${f.z + 1} minecraft:wheat[age=3]`); // unripe: must stay
  await cmd(`setblock ${f.x + 5} ${f.y} ${f.z} minecraft:sweet_berry_bush[age=3]`);
  await cmd(`setblock ${f.x + 6} ${f.y} ${f.z} minecraft:sweet_berry_bush[age=3]`);
  await sleep(500);
  const before = await tool('mc_status');
  await tool('mc_harvest', {radius: 12, timeout_seconds: 150});
  s = await waitJob(170);
  expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'minecraft:wheat') >= count(before, 'minecraft:wheat') + 4, 'wheat not gained');
  expect(count(s, 'minecraft:sweet_berries') > count(before, 'minecraft:sweet_berries'), 'no berries');
  const unripe = await tool('mc_scan', {block: 'wheat', radius: 12, limit: 20});
  expect(unripe.blocks.length >= 5, `wheat blocks after harvest: ${unripe.blocks.length} (replant + unripe expected)`);
  return s.job.message;
});

await step('mc_smelt 熔炉烤牛肉', async () => {
  s = await tool('mc_status'); const p = s.position;
  const fpos = {x: p.x - 3, y: p.y, z: p.z};
  await cmd(`setblock ${fpos.x} ${fpos.y} ${fpos.z} minecraft:furnace[facing=east]`);
  await cmd('give @s minecraft:beef 3'); await cmd('give @s minecraft:coal 2'); await sleep(500);
  await tool('mc_smelt', {...fpos, item: 'minecraft:beef', count: 3, timeout_seconds: 90});
  s = await waitJob(100);
  expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'minecraft:cooked_beef') >= 3, `cooked beef ${count(s, 'minecraft:cooked_beef')}`);
  expect(s.screen === 'none', 'furnace menu left open');
  return s.job.message;
});

await step('mc_hunt 打猎（不碰有名字的动物）', async () => {
  s = await tool('mc_status'); const p = s.position;
  await cmd('give @s minecraft:iron_sword 1');
  await cmd(`summon minecraft:cow ${p.x + 6} ${p.y} ${p.z + 6}`);
  await cmd(`summon minecraft:cow ${p.x + 7} ${p.y} ${p.z + 5}`);
  await cmd(`summon minecraft:cow ${p.x - 6} ${p.y} ${p.z - 6} {CustomName:'"Bessie"'}`);
  await sleep(800);
  const before = await tool('mc_status');
  await tool('mc_hunt', {animals: 'minecraft:cow', count: 2, radius: 16, timeout_seconds: 120});
  s = await waitJob(130);
  expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'minecraft:beef') + count(s, 'minecraft:leather') > count(before, 'minecraft:beef') + count(before, 'minecraft:leather'), 'no drops picked up');
  // Only the named cow is left: another hunt must refuse instead of attacking it.
  const again = await tool('mc_hunt', {animals: 'minecraft:cow', count: 1, radius: 16});
  expect(again.job?.state === 'failed' && /No huntable/.test(again.job.message), 'named cow was treated as huntable: ' + JSON.stringify(again.job));
  return s.job.message + '; named cow left alone';
});

await step('自动进食：长任务中饿了会自己吃', async () => {
  await cmd('give @s minecraft:bread 3');
  await cmd('effect give @s minecraft:hunger 60 120 true');
  for (let i = 0; i < 40; i++) { s = await tool('mc_status'); if (s.food <= 13) break; await sleep(700); }
  await cmd('effect clear @s'); await cmd('effect give @s minecraft:resistance infinite 4 true');
  s = await tool('mc_status');
  expect(s.food <= 14 && s.food > 6, `food ${s.food} not in 7..14`);
  const bread0 = count(s, 'minecraft:bread'), food0 = s.food;
  // A target on the flat test platform (the pack is a sky world: off the platform there is only void).
  await tool('mc_move', {x: b.x - 10, y: b.y, z: b.z + 10, radius: 1, timeout_seconds: 90});
  s = await waitJob(100);
  expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'minecraft:bread') < bread0, 'did not eat on the way: ' + s.job.message);
  return `food ${food0} -> ${s.food}, bread ${bread0} -> ${count(s, 'minecraft:bread')}`;
});

await step('FTB 任务：读取任务书', async () => {
  const ch = await tool('mc_quests', {mode: 'chapters'});
  expect(!ch.error, ch.error);
  expect(ch.chapters?.length > 0, 'no chapters');
  const av = await tool('mc_quests', {mode: 'available', limit: 60});
  expect(av.quests?.length > 0, 'no available quests');
  return `${ch.chapters.length} chapters, ${ch.quests_total} quests (${ch.quests_completed} done), ${av.matches} available now`;
});

await step('FTB 任务：提交物品任务并领奖', async () => {
  const av = await tool('mc_quests', {mode: 'available', limit: 60});
  // An open quest whose only unfinished tasks are item tasks for items that exist (we /give them in the test world).
  const pick = av.quests.find(q => !q.completed && q.tasks.length > 0 && q.tasks.every(t => t.done || (t.type === 'ftbquests:item' && t.item && t.max <= 64)));
  expect(pick, 'no available quest with only simple item tasks');
  for (const t of pick.tasks.filter(t => !t.done)) await cmd(`give @s ${t.item} ${t.max - t.progress}`);
  await sleep(1500);
  const sub = await tool('mc_quest_submit', {id: pick.id});
  expect(!sub.error, sub.error);
  let q;
  for (let i = 0; i < 10; i++) { await sleep(700); q = (await tool('mc_quests', {mode: 'quest', query: pick.id})).quest; if (q.completed) break; }
  expect(q.completed, `quest "${pick.title}" not completed: ${JSON.stringify(q.tasks.map(t => [t.title, t.progress, t.max]))}`);
  let claimText = 'no rewards';
  if (q.rewards.some(r => !r.claimed)) {
    const inv0 = await tool('mc_status');
    const cl = await tool('mc_quest_claim', {id: pick.id});
    expect(!cl.error, cl.error);
    let left = [];
    for (let i = 0; i < 8; i++) {
      await sleep(750);
      const q2 = (await tool('mc_quests', {mode: 'quest', query: pick.id})).quest;
      left = q2.rewards.filter(r => !r.claimed && !/choice/.test(r.type));
      if (!left.length) break;
    }
    const inv1 = await tool('mc_status');
    const gained = inv1.inventory.filter(i => count(inv1, i.item) > count(inv0, i.item)).map(i => `${i.item} (${i.name}) +${count(inv1, i.item) - count(inv0, i.item)}`);
    expect(left.length === 0, `rewards still unclaimed after 6 s: ${JSON.stringify(left)}; claim reply ${JSON.stringify(cl)}; inventory gained ${JSON.stringify(gained)}`);
    claimText = `claimed ${cl.claimed.length}, skipped ${cl.skipped.length}; got ${gained.join(', ') || 'nothing visible'}`;
  }
  return `"${pick.title}" (${pick.chapter}) completed; ${claimText}`;
});

await step('mc_machines 找产线 + 按产品查机器', async () => {
  s = await tool('mc_status'); const p = s.position;
  const m = {x: p.x + 3, y: p.y, z: p.z + 3};
  await cmd(`setblock ${m.x} ${m.y} ${m.z} create:depot`);
  await cmd(`setblock ${m.x} ${m.y + 2} ${m.z} create:mechanical_press`);
  await cmd(`setblock ${m.x + 1} ${m.y + 2} ${m.z} create:shaft[axis=x]`);
  await cmd(`setblock ${m.x + 3} ${m.y} ${m.z} create:basin`);
  await cmd(`setblock ${m.x + 3} ${m.y + 2} ${m.z} create:mechanical_mixer`);
  await sleep(800);
  const r = await tool('mc_machines', {radius: 16, for_item: 'create:iron_sheet'});
  expect(!r.error, r.error);
  const line = r.production_lines.find(l => l.blocks['create:mechanical_press']);
  expect(line, 'press not found in any production line');
  const pressing = r.for_item.by_recipe_type.find(t => t.recipe_type === 'create:pressing');
  expect(pressing && pressing.machines_nearby.length > 0, 'iron_sheet -> create:pressing -> nearby press not reported');
  return `${r.machines_found} machines, ${r.production_lines.length} lines; iron_sheet via ${r.for_item.by_recipe_type.map(t => t.recipe_type).join(', ')}`;
});

await step('mc_deposit / mc_withdraw 箱子存取', async () => {
  s = await tool('mc_status'); const p = s.position;
  const chest = {x: p.x + 2, y: p.y, z: p.z - 2};
  await cmd(`setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest`);
  await cmd('clear @s minecraft:cobblestone'); await cmd('give @s minecraft:cobblestone 70'); await sleep(500);
  await tool('mc_deposit', {...chest, items: 'minecraft:cobblestone', count: 40});
  s = await waitJob(60);
  expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'minecraft:cobblestone') === 30, `cobblestone left ${count(s, 'minecraft:cobblestone')} (expected 30)`);
  await tool('mc_withdraw', {...chest, items: 'minecraft:cobblestone', count: 15});
  s = await waitJob(60);
  expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'minecraft:cobblestone') === 45, `cobblestone ${count(s, 'minecraft:cobblestone')} (expected 45)`);
  expect(s.screen === 'none', 'chest left open');
  return 'deposit 40, withdraw 15 exact';
});

await step('蓝图大炮：补料 + 启动 + 打印（45 块沙子）', async () => {
  const {copyFile, mkdir} = await import('node:fs/promises');
  const src = process.env.SKYCODEX_TEST_SCHEMATIC;
  expect(src, 'set SKYCODEX_TEST_SCHEMATIC to a small .nbt (e.g. the pack\'s schematics/测试.nbt)');
  const up = path.join(runDir, 'schematics', 'uploaded', 'Dev');
  await mkdir(up, {recursive: true}); await copyFile(src, path.join(up, 'skycodex_test.nbt'));
  s = await tool('mc_status'); const p = s.position;
  const cannon = {x: p.x - 2, y: p.y, z: p.z + 2}, chest = {x: cannon.x - 1, y: cannon.y, z: cannon.z};
  const anchor = {x: b.x + 6, y: b.y, z: b.z + 6};
  await cmd(`fill ${anchor.x} ${anchor.y} ${anchor.z} ${anchor.x + 8} ${anchor.y + 6} ${anchor.z + 8} minecraft:air`);
  await cmd(`setblock ${cannon.x} ${cannon.y} ${cannon.z} create:schematicannon`);
  await cmd(`setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest`);
  await cmd('clear @s');
  await cmd(`give @s create:schematic[create:schematic_file="skycodex_test.nbt",create:schematic_owner="Dev",create:schematic_deployed=true,create:schematic_anchor=[I;${anchor.x},${anchor.y},${anchor.z}]] 1`);
  await cmd('give @s minecraft:sand 64'); await cmd('give @s minecraft:gunpowder 4');
  await sleep(800);
  s = await tool('mc_status');
  expect(count(s, 'create:schematic') === 1, 'could not create the schematic item (component syntax?)');
  const st0 = await tool('mc_cannon', {...cannon});
  expect(!st0.error, st0.error);
  await tool('mc_deposit', {...chest, items: 'minecraft:sand'}); s = await waitJob(60); expect(s.job.state === 'succeeded', s.job.message);
  await tool('mc_deposit', {...cannon, items: 'create:schematic,minecraft:gunpowder'}); s = await waitJob(60); expect(s.job.state === 'succeeded', s.job.message);
  expect(count(s, 'create:schematic') === 0, 'schematic not put into the cannon: ' + s.job.message);
  await tool('mc_cannon', {...cannon, action: 'start'}); s = await waitJob(60); expect(s.job.state === 'succeeded', s.job.message);
  let st;
  for (let i = 0; i < 40; i++) {
    await sleep(3000); st = await tool('mc_cannon', {...cannon});
    if (st.blocks_to_place > 0 && st.blocks_placed >= st.blocks_to_place || /finished|done|complete/i.test(st.status ?? '')) break;
    if (st.missing_item) log('   cannon missing', st.missing_item);
  }
  // Verify the printed area against the same schematic, converted to a SkyCodex blueprint.
  const {parseNbt, convertStructure} = await import('../bridge/nbt.mjs');
  const {readFile} = await import('node:fs/promises');
  const {blueprint} = convertStructure(parseNbt(await readFile(src)), {name: '_cannon-test'});
  await writeFile(path.join(root, 'blueprints', '_cannon-test.json'), JSON.stringify(blueprint));
  const chk = await tool('mc_check_blueprint', {file: '_cannon-test.json', origin: anchor});
  const placed = chk.already_correct;
  expect(placed === blueprint.blocks.length, `only ${placed}/${blueprint.blocks.length} printed; cannon ${JSON.stringify(st)}`);
  return `printed ${placed} blocks; cannon ${st.state} "${st.status ?? ''}" ${st.blocks_placed}/${st.blocks_to_place}`;
});

await step('mc_sequence 批量步骤（一次调用 4 步 + 失败时停止）', async () => {
  await cmd('clear @s'); await cmd('give @s minecraft:oak_log 4'); await sleep(600);
  s = await tool('mc_status'); const p = s.position;
  const tablePos = {x: p.x + 2, y: p.y, z: p.z + 1};
  await cmd(`setblock ${tablePos.x} ${tablePos.y} ${tablePos.z} minecraft:air`);
  const r = await tool('mc_sequence', {steps: [
    {tool: 'mc_craft', args: {recipe: 'minecraft:oak_planks', times: 2}, expect: {item: 'minecraft:oak_planks', at_least: 8}},
    {tool: 'mc_craft', args: {recipe: 'minecraft:crafting_table', times: 1}, expect: {item: 'minecraft:crafting_table', at_least: 1}},
    {tool: 'mc_place', args: {...tablePos, item: 'minecraft:crafting_table'}},
    {tool: 'mc_break', args: {...tablePos}, expect: {item: 'minecraft:crafting_table', at_least: 1}}]});
  expect(!r.error, r.error);
  s = await waitJob(180);
  expect(s.sequence?.state === 'succeeded', JSON.stringify(s.sequence));
  expect(s.sequence.log.length === 4 && s.sequence.log.every(l => l.state === 'succeeded'), JSON.stringify(s.sequence.log));
  // Failure stops the sequence: step 2 expects an item we do not have, step 3 must not run.
  await tool('mc_sequence', {steps: [
    {tool: 'mc_craft', args: {recipe: 'minecraft:stick', times: 1}},
    {tool: 'mc_craft', args: {recipe: 'minecraft:stick', times: 1}, expect: {item: 'minecraft:diamond', at_least: 1}},
    {tool: 'mc_craft', args: {recipe: 'minecraft:stick', times: 1}}]});
  const f = await waitJob(60);
  expect(f.sequence?.state === 'failed' && f.sequence.log.length === 2 && /Step 2\/3/.test(f.sequence.message), JSON.stringify(f.sequence));
  return `4 steps in one call in ${s.sequence.elapsed_seconds}s; failure stopped at: ${f.sequence.message}`;
});

await step('mc_waystone 传送石碑：列出目的地 + 按名字传送', async () => {
  s = await tool('mc_status'); const p = s.position;
  const A = {x: p.x + 3, y: p.y, z: p.z - 6}, B = {x: p.x + 60, y: p.y, z: p.z + 4};
  // B stands outside the flat test area: give it a small platform.
  await cmd(`fill ${B.x - 3} ${B.y - 1} ${B.z - 3} ${B.x + 3} ${B.y - 1} ${B.z + 3} minecraft:stone`);
  await cmd(`fill ${B.x - 3} ${B.y} ${B.z - 3} ${B.x + 3} ${B.y + 3} ${B.z + 3} minecraft:air`);
  for (const w of [A, B]) {
    await cmd(`setblock ${w.x} ${w.y} ${w.z} waystones:waystone[half=lower]`);
    await cmd(`setblock ${w.x} ${w.y + 1} ${w.z} waystones:waystone[half=upper]`);
  }
  await cmd('xp add @s 30 levels'); await sleep(800);
  // A player knows a waystone after right-clicking it once; activate B there, then A here.
  for (const [w, back] of [[B, false], [A, true]]) {
    await cmd(`tp @s ${w.x + 2} ${w.y} ${w.z}`); await sleep(1500);
    await tool('mc_use_block', w); s = await waitJob(40);
    expect(s.job.state === 'succeeded', 'activate: ' + s.job.message);
    await sleep(800); if ((await tool('mc_menu')).container_id) await tool('mc_close_menu');
    if (back) await cmd(`tp @s ${p.x} ${p.y} ${p.z}`);
  }
  await sleep(1500);
  await tool('mc_waystone', {action: 'list'}); s = await waitJob(60);
  expect(s.job.state === 'succeeded', 'list: ' + s.job.message);
  const list = s.job.detail?.waystones ?? [];
  const here = list.find(w => w.current), there = list.find(w => !w.current && w.pos?.x === B.x && w.pos?.z === B.z);
  expect(here && there, 'list misses A (current) or B: ' + JSON.stringify(list));
  // Refusals: the current stone and an unknown name fail without moving.
  // Waystones placed with /setblock have no name: select them by position.
  await tool('mc_waystone', {action: 'teleport', ...here.pos}); s = await waitJob(60);
  expect(s.job.state === 'failed' && /standing at/.test(s.job.message), 'current stone not refused: ' + s.job.message);
  await tool('mc_waystone', {action: 'teleport', name: '不存在的石碑xyz'}); s = await waitJob(60);
  expect(s.job.state === 'failed' && /No known waystone/.test(s.job.message), 'unknown name not refused: ' + s.job.message);
  await tool('mc_waystone', {action: 'teleport', ...there.pos}); s = await waitJob(60);
  expect(s.job.state === 'succeeded', 'teleport: ' + s.job.message);
  expect(Math.abs(s.position.x - B.x) <= 4 && Math.abs(s.position.z - B.z) <= 4, 'not near B: ' + JSON.stringify(s.position));
  expect(s.enabled, 'control was dropped after teleport');
  return `${list.length} waystones listed (${list.map(w => w.name).join(', ')}); teleported to "${there.name}", xp spent ${s.job.detail?.xp_levels_spent ?? '?'}`;
});

await step('mc_place 指定朝向放置（传动轴/齿轮/箱子侧面的漏斗）', async () => {
  await cmd('clear @s');
  for (const it of ['create:shaft 4', 'create:cogwheel 2', 'minecraft:chest 1', 'create:andesite_funnel 2', 'minecraft:stone 8']) await cmd(`give @s ${it}`);
  await sleep(600);
  s = await tool('mc_status'); const p = s.position;
  const at = (dx, dy, dz) => ({x: p.x + dx, y: p.y + dy, z: p.z + dz});
  await cmd(`fill ${p.x - 1} ${p.y} ${p.z - 4} ${p.x + 7} ${p.y + 2} ${p.z + 4} minecraft:air`);
  await cmd(`fill ${p.x - 1} ${p.y - 1} ${p.z - 4} ${p.x + 7} ${p.y - 1} ${p.z + 4} minecraft:stone`);
  await sleep(500);
  const want = [
    [at(3, 0, -2), 'create:shaft', {axis: 'x'}],
    [at(3, 0, 0), 'create:shaft', {axis: 'z'}],
    [at(3, 0, 2), 'create:cogwheel', {axis: 'y'}],
    [at(5, 0, 0), 'minecraft:chest', null],
    [at(5, 0, 1), 'create:andesite_funnel', {facing: 'south'}],
  ];
  const out = [];
  for (const [pos, item, properties] of want) {
    await tool('mc_place', {...pos, item, ...(properties ? {properties} : {}), timeout_seconds: 60});
    const r = await waitJob(70);
    expect(r.job.state === 'succeeded', `${item} ${JSON.stringify(properties)}: ${r.job.message}`);
    const scan = await tool('mc_scan', {...pos, radius: 0, limit: 1});
    const b = scan.blocks?.[0];
    expect(b?.block === item, `${item} not there: ${JSON.stringify(b)}`);
    for (const [k, v] of Object.entries(properties ?? {})) expect(b.state?.[k] === v, `${item} ${k}=${b.state?.[k]}, wanted ${v}`);
    out.push(`${item.split(':')[1]} ${JSON.stringify(properties ?? {})}`);
  }
  s = await tool('mc_status');
  expect(s.screen === 'none', 'a GUI was opened (chest clicked without sneaking?): ' + s.screen);
  return out.join(', ');
});

await step('自动反击：走路途中被僵尸打，反击后继续任务', async () => {
  await cmd('clear @s'); await cmd('give @s minecraft:iron_sword 1'); await cmd('give @s minecraft:bread 16'); await cmd('give @s minecraft:diamond_sword 1');
  await cmd('effect give @s minecraft:resistance infinite 4 true'); await cmd('difficulty easy');
  await sleep(600);
  s = await tool('mc_status'); const p = s.position;
  await cmd(`fill ${p.x - 3} ${p.y} ${p.z - 3} ${p.x + 14} ${p.y + 2} ${p.z + 3} minecraft:air`);
  await cmd(`fill ${p.x - 3} ${p.y - 1} ${p.z - 3} ${p.x + 14} ${p.y - 1} ${p.z + 3} minecraft:grass_block`);
  const before = s.defense?.kills ?? 0;
  await tool('mc_move', {x: p.x + 12, y: p.y, z: p.z, radius: 1, timeout_seconds: 120});
  await sleep(700);
  await cmd(`summon minecraft:zombie ${p.x + 3} ${p.y} ${p.z} {IsBaby:0b}`);
  let fought = false, sMid;
  for (let i = 0; i < 40; i++) {
    sMid = await tool('mc_wait', {seconds: 1});
    if (sMid.defense?.active) fought = true;
    if (sMid.job?.state !== 'running') break;
  }
  s = await waitJob(120);
  expect(fought || (s.defense?.engagements ?? 0) > 0, 'never fought: ' + JSON.stringify(s.defense));
  expect((s.defense?.kills ?? 0) > before, 'zombie not killed: ' + JSON.stringify(s.defense));
  expect(/diamond_sword/.test(s.defense?.weapon ?? ''), 'did not pick the highest-damage weapon: ' + s.defense?.weapon);
  expect(s.enabled, 'control was dropped');
  expect(s.job.state === 'succeeded', 'move did not resume/finish: ' + s.job.message);
  return `defense ${JSON.stringify(s.defense)}; move: ${s.job.message}`;
});

await step('寻路：把传动轴等挡路零件当障碍，绕过去而不是卡住', async () => {
  s = await tool('mc_status'); const p = s.position;
  await cmd(`fill ${p.x - 2} ${p.y} ${p.z - 6} ${p.x + 9} ${p.y + 2} ${p.z + 6} minecraft:air`);
  await cmd(`fill ${p.x - 2} ${p.y - 1} ${p.z - 6} ${p.x + 9} ${p.y - 1} ${p.z + 6} minecraft:stone`);
  // A wall of shafts (two high, so it cannot be jumped) across the way, with a gap only at the south end.
  await cmd(`fill ${p.x + 3} ${p.y} ${p.z - 6} ${p.x + 3} ${p.y + 1} ${p.z + 3} create:shaft[axis=z]`);
  await sleep(600);
  await tool('mc_move', {x: p.x + 7, y: p.y, z: p.z, radius: 0, timeout_seconds: 60});
  s = await waitJob(70);
  expect(s.job.state === 'succeeded', 'did not get around the shaft wall: ' + s.job.message + ' at ' + JSON.stringify(s.position));
  return `reached ${JSON.stringify(s.position)} in ${s.job.elapsed_seconds}s, auto_jumps=${s.job.auto_jumps ?? 0}, replans=${s.job.replans ?? 0}`;
});

await step('mc_cancel 取消任务但保留接管', async () => {
  s = await tool('mc_status'); const p = s.position;
  await tool('mc_move', {x: p.x - 40, y: p.y, z: p.z, radius: 1, timeout_seconds: 120});
  await sleep(1500);
  const c = await tool('mc_cancel');
  expect(c.job?.state === 'cancelled', 'job not cancelled: ' + JSON.stringify(c.job));
  expect(c.enabled === true, 'control was turned off');
  return c.job.message;
});

await step('管子上放猫咪引擎（Shift 点击 + 南北/东西两种朝向）', async () => {
  await cmd('give @s create:fluid_pipe 2'); await cmd('give @s laowu:cat_engine 2'); await sleep(600);
  s = await tool('mc_status'); const p = s.position;
  await cmd(`fill ${p.x - 3} ${p.y} ${p.z - 4} ${p.x + 4} ${p.y + 2} ${p.z + 4} minecraft:air`);
  await cmd(`fill ${p.x - 3} ${p.y - 1} ${p.z - 4} ${p.x + 4} ${p.y - 1} ${p.z + 4} minecraft:stone`);
  const pipe = {x: p.x + 2, y: p.y, z: p.z}, eng = {x: p.x + 2, y: p.y + 1, z: p.z};
  await tool('mc_place', {...pipe, item: 'create:fluid_pipe'}); s = await waitJob(60);
  expect(s.job.state === 'succeeded', 'pipe: ' + s.job.message);
  const out = [];
  // The cat engine only distinguishes the north-south and the east-west axis.
  for (const facing of ['south', 'east']) {
    await cmd(`setblock ${eng.x} ${eng.y} ${eng.z} minecraft:air`); await sleep(400);
    await tool('mc_place', {...eng, item: 'laowu:cat_engine', properties: {facing}, timeout_seconds: 60}); s = await waitJob(70);
    const b = (await tool('mc_scan', {...eng, radius: 0, limit: 1})).blocks[0];
    expect(s.job.state === 'succeeded' && b?.state?.facing === facing, `engine facing ${facing}: ${s.job.message} -> ${JSON.stringify(b?.state)}`);
    out.push(facing);
  }
  s = await tool('mc_status');
  expect(s.screen === 'none', 'a GUI opened: ' + s.screen);
  return 'engine placed on a pipe facing ' + out.join(' and ');
});

await step('自动反击：打得中会飞的女仆妖精', async () => {
  await cmd('clear @s'); await cmd('give @s minecraft:diamond_sword 1'); await cmd('give @s minecraft:bread 16');
  await cmd('effect give @s minecraft:resistance infinite 4 true'); await cmd('difficulty normal'); await cmd('time set midnight');
  await sleep(600);
  s = await tool('mc_status'); const p = s.position; const before = s.defense ?? {};
  await cmd(`fill ${p.x - 6} ${p.y} ${p.z - 6} ${p.x + 6} ${p.y + 4} ${p.z + 6} minecraft:air`);
  await cmd(`fill ${p.x - 6} ${p.y - 1} ${p.z - 6} ${p.x + 6} ${p.y - 1} ${p.z + 6} minecraft:stone`);
  await cmd(`summon touhou_little_maid:fairy ${p.x + 2} ${p.y + 2} ${p.z + 1}`);
  for (let i = 0; i < 40; i++) { s = await tool('mc_wait', {seconds: 1}); if ((s.defense?.kills ?? 0) > (before.kills ?? 0)) break; }
  await cmd('kill @e[type=touhou_little_maid:fairy]'); await cmd('time set day');
  expect((s.defense?.hits ?? 0) > (before.hits ?? 0), 'never hit the fairy: ' + JSON.stringify(s.defense));
  expect((s.defense?.kills ?? 0) > (before.kills ?? 0), 'fairy not killed: ' + JSON.stringify(s.defense));
  return JSON.stringify(s.defense);
});

await step('mc_sleep 晚上找床睡到天亮', async () => {
  s = await tool('mc_status'); const p = s.position;
  await cmd(`fill ${p.x - 4} ${p.y} ${p.z - 4} ${p.x + 6} ${p.y + 2} ${p.z + 4} minecraft:air`);
  await cmd(`fill ${p.x - 4} ${p.y - 1} ${p.z - 4} ${p.x + 6} ${p.y - 1} ${p.z + 4} minecraft:stone`);
  await cmd('kill @e[type=!player]');
  await cmd(`setblock ${p.x + 5} ${p.y} ${p.z} minecraft:red_bed[facing=east,part=foot]`);
  await cmd(`setblock ${p.x + 6} ${p.y} ${p.z} minecraft:red_bed[facing=east,part=head]`);
  const refused = await tool('mc_sleep');
  expect(refused.job?.state === 'failed' && /day/.test(refused.job.message), 'slept during the day? ' + JSON.stringify(refused.job));
  await cmd('time set 18000'); await sleep(800);
  await tool('mc_sleep', {timeout_seconds: 60});
  s = await waitJob(70);
  expect(s.job.state === 'succeeded' && /morning/.test(s.job.message), 'sleep: ' + s.job.message);
  expect(!s.is_night, 'still night after sleeping');
  return s.job.message;
});

const report = {date: new Date().toISOString(), runDir: path.basename(runDir), passed: results.filter(r => r.ok).length, total: results.length, results};
await writeFile(path.join(root, 'features-report.json'), JSON.stringify(report, null, 2));
console.log(`\n功能测试：${report.passed}/${report.total} 通过。报告：features-report.json`);
for (const r of results) console.log(`${r.ok ? '✔' : '✘'} ${r.name}${r.ok ? ' — ' + (r.detail ?? '') : ' — ' + r.error}`);
process.exitCode = report.passed === report.total ? 0 : 1;
