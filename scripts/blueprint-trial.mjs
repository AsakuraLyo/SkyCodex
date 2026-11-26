// DEV ONLY: trial-build slices of a large blueprint in the dev client test world (gradlew runClient / runModpack).
// Uses /fill, /give and /effect through dev_command, so never point it at a real world.
//   node scripts/blueprint-trial.mjs <runDir> <blueprint.json> <yFrom-yTo>[,<yFrom-yTo>...] [--check-only]
// For each slice: clears a flat test area, gives exactly the slice's materials plus dirt scaffolding, builds it with
// mc_build_blueprint (skip_unsupported) and verifies. Also runs mc_check_blueprint on the whole blueprint first.
// Writes <blueprint>.trial-report.json next to the blueprint and screenshots to blueprints/_trial-*.png.
import {readFile, writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createClient, callTool} from '../bridge/tools.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const [runArg, bpArg, slicesArg] = process.argv.slice(2).filter(a => !a.startsWith('--'));
if (!runArg || !bpArg) { console.error('usage: node scripts/blueprint-trial.mjs <runDir> <blueprint.json> <y0-y1,...> [--check-only]'); process.exit(2); }
const request = createClient(path.resolve(runArg));
const bpFile = path.basename(bpArg);
const full = JSON.parse(await readFile(path.join(root, 'blueprints', bpFile), 'utf8'));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const cmd = async c => { await request('dev_command', {command: c}); await sleep(120); };
async function tool(name, args = {}) {
  const r = await callTool(name, args, request);
  if (r.content[0].type === 'image') return r.content[0];
  return JSON.parse(r.content[0].text);
}
async function waitJob(maxSeconds) {
  const end = Date.now() + maxSeconds * 1000; let s;
  do { s = await tool('mc_wait', {seconds: 20}); if (s.job?.state !== 'running') return s;
    log(`  … ${s.job.progress} verified, ${s.job.elapsed_seconds}s`); } while (Date.now() < end);
  await tool('mc_stop'); throw new Error('job did not finish in time');
}
const size = bp => ['x', 'y', 'z'].map(k => Math.max(...bp.blocks.map(b => b[k])) + 1);
// /fill is limited to 32768 blocks per command; split along y.
async function fillBox(x0, y0, z0, x1, y1, z1, block) {
  const per = Math.max(1, Math.floor(32768 / ((x1 - x0 + 1) * (z1 - z0 + 1))));
  for (let y = y0; y <= y1; y += per) await cmd(`fill ${x0} ${y} ${z0} ${x1} ${Math.min(y1, y + per - 1)} ${z1} ${block}`);
}

const report = {date: new Date().toISOString(), blueprint: bpFile, blocks: full.blocks.length, size: size(full), slices: []};
let s = await tool('mc_status');
if (!s.in_world || !s.enabled) throw new Error('dev client must be in a world with control enabled');
for (const c of ['time set day', 'weather clear', 'gamerule doMobSpawning false', 'kill @e[type=!player]',
  'effect give @s minecraft:saturation infinite 0 true', 'effect give @s minecraft:slow_falling infinite 0 true',
  'effect give @s minecraft:resistance infinite 4 true']) await cmd(c);

// ---- 1. whole-blueprint check in the real registry -------------------------------------------------------------
const base = s.position;
const [W, H, L] = size(full);
const fullOrigin = {x: base.x + 4, y: base.y, z: base.z + 4};
const check = await tool('mc_check_blueprint', {file: bpFile, origin: fullOrigin, skip_unsupported: true});
report.full_check = {total_supported: check.total, skipped_count: check.skipped_count ?? 0, skipped: check.skipped_unsupported ?? [],
  materials: check.materials?.map(m => ({item: m.item, required: m.required}))};
if (check.error) report.full_check.error = check.error;
log(`whole blueprint: ${check.total} supported, ${check.skipped_count ?? 0} skipped, ${check.materials?.length} material types`);
if (process.argv.includes('--check-only') || !slicesArg) {
  await writeFile(path.join(root, 'blueprints', bpFile.replace(/\.json$/, '.trial-report.json')), JSON.stringify(report, null, 2));
  process.exit(0);
}

// ---- 2. build slices ------------------------------------------------------------------------------------------
for (const range of slicesArg.split(',')) {
  const [y0, y1] = range.split('-').map(Number);
  const name = `_trial-${bpFile.replace(/\.json$/, '')}-y${y0}-${y1}`;
  const slice = {name, blocks: full.blocks.filter(b => b.y >= y0 && b.y <= y1).map(b => ({...b, y: b.y - y0}))};
  await writeFile(path.join(root, 'blueprints', name + '.json'), JSON.stringify(slice));
  const entry = {slice: `y ${y0}..${y1}`, file: name + '.json', blocks: slice.blocks.length};
  report.slices.push(entry);
  log(`▶ slice y${y0}-${y1}: ${slice.blocks.length} blocks`);
  try {
    s = await tool('mc_status');
    const p = s.position, [w, h, l] = size(slice), m = 6;
    const origin = {x: p.x + 3, y: p.y, z: p.z + 3};
    // Flat empty area with a margin, so the player can walk around and scaffold; floor of stone.
    await fillBox(origin.x - m, origin.y, origin.z - m, origin.x + w + m, origin.y + h + 6, origin.z + l + m, 'minecraft:air');
    await fillBox(origin.x - m, origin.y - 1, origin.z - m, origin.x + w + m, origin.y - 1, origin.z + l + m, 'minecraft:stone');
    await cmd('clear @s');
    await sleep(500);
    const pre = await tool('mc_check_blueprint', {file: name + '.json', origin, skip_unsupported: true});
    if (pre.error) throw new Error(pre.error);
    entry.supported = pre.total; entry.skipped = pre.skipped_unsupported ?? []; entry.skipped_count = pre.skipped_count ?? 0;
    entry.materials = pre.materials.map(x => ({item: x.item, required: x.required}));
    for (const x of pre.materials) for (let n = x.required; n > 0; n -= 64) await cmd(`give @s ${x.item} ${Math.min(64, n)}`);
    for (let i = 0; i < 3; i++) await cmd('give @s minecraft:dirt 64');
    await sleep(800);
    s = await tool('mc_status');
    const short = pre.materials.filter(x => (s.inventory.filter(i => i.item === x.item).reduce((a, i) => a + i.count, 0)) < x.required);
    if (short.length) throw new Error('inventory could not hold all materials: ' + short.map(x => x.item).join(', '));
    const t0 = Date.now();
    let last = -1, result, unplaced = [];
    entry.origin = origin;
    for (let round = 0; round < 8; round++) {
      await tool('mc_build_blueprint', {file: name + '.json', origin, skip_unsupported: true, timeout_seconds: 600});
      result = await waitJob(620);
      if (result.job.detail?.unplaced) unplaced = result.job.detail.unplaced;
      log(`  round ${round + 1}: ${result.job.state} — ${result.job.message} (${result.job.progress}/${pre.total})`);
      if (result.job.state === 'succeeded' || result.job.progress <= last) break;
      last = result.job.progress;
      if (/Health|food/.test(result.job.message)) { await tool('mc_eat'); await waitJob(30); }
    }
    const after = await tool('mc_check_blueprint', {file: name + '.json', origin, skip_unsupported: true});
    entry.state = result.job.state; entry.message = result.job.message; entry.unplaced = unplaced; entry.wrong_blocks = after.conflicts; entry.seconds = Math.round((Date.now() - t0) / 1000);
    entry.verified = after.already_correct; entry.remaining = after.total - after.already_correct;
    entry.remaining_by_block = Object.entries(after.materials.filter(x => x.required > 0).reduce((a, x) => (a[x.item] = x.required, a), {}));
    entry.dirt_left = (await tool('mc_status')).inventory.filter(i => i.item === 'minecraft:dirt').reduce((a, i) => a + i.count, 0);
    const shot = await tool('mc_screenshot', {max_width: 1280});
    await writeFile(path.join(root, 'blueprints', name + '.png'), Buffer.from(shot.data, 'base64'));
    log(`  ✔ ${entry.verified}/${pre.total} verified in ${entry.seconds}s`);
  } catch (e) { entry.error = e.message; log('  ✘', e.message); }
}
const out = path.join(root, 'blueprints', bpFile.replace(/\.json$/, '.trial-report.json'));
await writeFile(out, JSON.stringify(report, null, 2));
console.log('report:', out);
