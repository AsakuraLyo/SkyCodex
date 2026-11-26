// Read-only survey at the current position: scan production lines (radius), save them to memory, print a summary,
// save a screenshot.   node survey-here.mjs <label> [radius]
import {t, r, log} from './safe.mjs';
import {memoryTool} from 'file:///E:/MC/SkyCodex/bridge/memory.mjs';
import {writeFile} from 'node:fs/promises';
const label = process.argv[2] ?? 'here', radius = Number(process.argv[3] ?? 96);
const s = await t('mc_status');
log(`位置 ${JSON.stringify(s.position)} 生命 ${Math.round(s.health)} 饥饿 ${s.food} 敌人 ${JSON.stringify(s.hostiles_nearby ?? [])}`);
const m = await t('mc_machines', {radius});
await writeFile(`E:/MC/SkyCodex/surveys/${label}-machines.json`, JSON.stringify(m, null, 1));
const lines = m.production_lines ?? [];
log(`扫描半径 ${radius}：${lines.length} 组`);
for (const l of lines) {
  const top = Object.entries(l.blocks).sort((a, b) => b[1] - a[1]).slice(0, 7).map(([k, n]) => `${k.replace(/^create:/, '')}×${n}`).join(' ');
  const c = {x: Math.round((l.from.x + l.to.x) / 2), y: Math.round((l.from.y + l.to.y) / 2), z: Math.round((l.from.z + l.to.z) / 2)};
  console.log(`  [${c.x},${c.y},${c.z}] ${l.to.x - l.from.x + 1}x${l.to.y - l.from.y + 1}x${l.to.z - l.from.z + 1} ${l.kinetics ?? ''} | ${top}`);
}
const mem = await memoryTool({action: 'remember_lines', radius}, r, 'E:/MC/SkyCodex');
log(`记忆：新增 ${mem.added}，更新 ${mem.updated}，忽略 ${mem.skipped_noise}`);
try { const img = await t('mc_screenshot', {max_width: 960}); await writeFile(`E:/MC/SkyCodex/surveys/${label}.png`, Buffer.from(img.data, 'base64')); } catch {}
