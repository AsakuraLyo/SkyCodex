// Read-only: list containers/machines within radius whose visible contents match a regex, and total the items.
//   node find-items.mjs <regex> [radius] [label]
import {t, log} from './safe.mjs';
import {writeFile} from 'node:fs/promises';
const re = new RegExp(process.argv[2] ?? '.', 'i'), radius = Number(process.argv[3] ?? 96), label = process.argv[4];
const s = await t('mc_status');
const m = await t('mc_machines', {radius});
if (label) await writeFile(`E:/MC/SkyCodex/surveys/${label}-machines.json`, JSON.stringify(m, null, 1));
const totals = {}; let containers = 0, withContents = 0;
const parse = c => { const mm = /^(\d+)x (.+)$/.exec(c); return mm ? [mm[2], Number(mm[1])] : [c, 1]; };
for (const l of m.production_lines ?? []) for (const x of l.machines ?? []) {
  containers++;
  if (!x.contents) continue; withContents++;
  const list = Array.isArray(x.contents) ? x.contents : [x.contents];
  const hits = list.filter(c => re.test(String(c)));
  for (const c of hits) { const [id, n] = parse(String(c)); totals[id] = (totals[id] ?? 0) + n; }
  if (hits.length) console.log(`  ${x.block} @${x.x ?? x.pos?.x},${x.y ?? x.pos?.y},${x.z ?? x.pos?.z}: ${hits.join(', ')}`);
}
log(`位置 ${JSON.stringify(s.position)}；扫描 ${containers} 个方块实体，${withContents} 个显示了内容`);
console.log('合计:', JSON.stringify(totals));
const first = (m.production_lines ?? []).flatMap(l => l.machines ?? []).find(x => x.contents);
if (first) console.log('样例字段:', JSON.stringify(first).slice(0, 300));
