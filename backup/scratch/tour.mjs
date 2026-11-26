// Walk the SkyExplorer server base: visit remembered places, rescan and remember production lines, screenshot.
import {createClient, callTool} from 'file:///E:/MC/SkyCodex/bridge/tools.mjs';
import {memoryTool} from 'file:///E:/MC/SkyCodex/bridge/memory.mjs';
import {writeFile} from 'node:fs/promises';
const r = createClient(process.argv[2]);
const ROOT = 'E:/MC/SkyCodex';
const tool = async (n, a = {}) => { const x = await callTool(n, a, r); return x.content[0].type === 'image' ? x.content[0] : JSON.parse(x.content[0].text); };
const log = (...a) => console.log(new Date().toTimeString().slice(0, 8), ...a);
const stops = [
  ['链式传送机/电梯站（基地北）', {x: 88, y: 74, z: 452}],
  ['链式传送机（北，停转）', {x: 111, y: 70, z: 409}],
  ['大水车动力站', {x: 127, y: 70, z: 529}],
  ['厨房/仓库区', {x: 131, y: 72, z: 563}],
  ['猫猫引擎动力站（东）', {x: 155, y: 72, z: 536}],
  ['回到起点', {x: 59, y: 69, z: 484}],
];
let s = await tool('mc_status'); const hp0 = s.health;
const from = Number(process.argv[3] ?? 1) - 1;
// Only mobs on about the same level count: a skeleton in a cave under the floor is not a threat.
const danger = st => (st.hostiles_nearby ?? []).filter(h => h.distance <= 8 && Math.abs(h.y - st.position.y) <= 3);
if (!s.enabled) { log('接管未开启：请在游戏里按 F8'); process.exit(1); }
for (const [i, [name, at]] of stops.entries()) {
  if (i < from) continue;
  log(`▶ ${i + 1}/${stops.length} 去 ${name} ${JSON.stringify(at)}`);
  const start = await tool('mc_move', {...at, radius: 4, timeout_seconds: 150});
  if (start.job?.state === 'failed') { log('  ✘ 无法出发：', start.job.message); continue; }
  for (;;) {
    s = await tool('mc_wait', {seconds: 20});
    if (danger(s).length || s.health < hp0 * 0.6) { await tool('mc_stop'); log('  ⚠ 有危险，已停止并交还控制', JSON.stringify(danger(s)), s.health); process.exit(1); }
    if (!s.enabled) { log('  控制已关闭：', s.last_stop, '（游戏打开了界面或失去焦点暂停？）'); process.exit(1); }
    if (s.job?.state !== 'running') break;
    log(`  … 走了 ${s.job.elapsed_seconds}s，位置 ${JSON.stringify(s.position)}`);
  }
  log(`  ${s.job.state === 'succeeded' ? '✔' : '✘'} ${s.job.message}，位置 ${JSON.stringify(s.position)}，饥饿 ${s.food}`);
  if (!s.enabled) { log('  控制已关闭：', s.last_stop); process.exit(1); }
  const mem = await memoryTool({action: 'remember_lines', radius: 64}, r, ROOT);
  log(`  记忆：新增 ${mem.added}，更新 ${mem.updated}`);
  if (name.includes('停转')) {
    const m = await tool('mc_machines', {radius: 12});
    for (const l of m.production_lines.slice(0, 3)) log('  近处：', JSON.stringify(l.from), '~', JSON.stringify(l.to), l.kinetics ?? '', JSON.stringify(l.blocks), JSON.stringify(l.machines.map(x => [x.block, x.speed_rpm])));
  }
  try { const img = await tool('mc_screenshot', {max_width: 960}); await writeFile(`E:/MC/SkyCodex/surveys/tour-${i + 1}.png`, Buffer.from(img.data, 'base64')); } catch {}
}
const all = await memoryTool({action: 'recall', limit: 100}, r, ROOT);
log(`完成。记忆里共 ${all.count} 条`);
for (const e of all.entries) log(`  ${e.category.padEnd(15)} ${e.name}  ${JSON.stringify(e.center)}  ${e.kinetics ?? ''}`);
