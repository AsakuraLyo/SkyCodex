// Read-only survey of the world the REAL client is in (also on a multiplayer server): player status, FTB quest
// progress, machines / production lines around the player and a screenshot. No commands, no movement, no clicks;
// it does not even need F8 control. Safe on servers.
//   node scripts/server-survey.mjs "<game dir with config/skycodex-connection.json>" [radius]
// Writes survey-<time>.json and survey-<time>.png in the current folder.
import {writeFile} from 'node:fs/promises';
import {createClient, callTool} from '../bridge/tools.mjs';
import path from 'node:path';

const [gameDir, radiusArg] = process.argv.slice(2);
if (!gameDir) { console.error('用法：node scripts/server-survey.mjs "<游戏目录>" [半径，默认 96]'); process.exit(2); }
const request = createClient(path.resolve(gameDir));
const radius = Math.min(96, Math.max(8, Number(radiusArg ?? 96)));
const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
const out = {date: new Date().toISOString(), radius};
const tryRead = async (key, fn) => { try { out[key] = await fn(); } catch (e) { out[key] = {error: e.message}; } };
const tool = async (name, args = {}) => { const r = await callTool(name, args, request); return r.content[0].type === 'image' ? r.content[0] : JSON.parse(r.content[0].text); };

await tryRead('status', () => tool('mc_status'));
if (!out.status?.in_world) { console.error('游戏不在世界里，请先进入服务器。'); process.exit(1); }
await tryRead('quest_chapters', () => tool('mc_quests', {mode: 'chapters'}));
await tryRead('quests_available', () => tool('mc_quests', {mode: 'available', limit: 60}));
await tryRead('machines', () => tool('mc_machines', {radius}));
await tryRead('create_machines', () => tool('mc_machines', {radius, block: 'create:'}));
try { const shot = await tool('mc_screenshot', {max_width: 1280}); await writeFile(`survey-${stamp}.png`, Buffer.from(shot.data, 'base64')); out.screenshot = `survey-${stamp}.png`; } catch (e) { out.screenshot = {error: e.message}; }
await writeFile(`survey-${stamp}.json`, JSON.stringify(out, null, 2));

const s = out.status, ch = out.quest_chapters, m = out.machines;
console.log(`位置 ${JSON.stringify(s.position)} ${s.dimension}，生命 ${s.health}，饥饿 ${s.food}，背包 ${s.inventory.length} 格`);
if (ch?.chapters) console.log(`任务书：${ch.chapters.length} 章，${ch.quests_completed}/${ch.quests_total} 已完成，当前可做 ${out.quests_available?.matches ?? '?'} 个`);
else console.log('任务书：', ch?.error ?? '无');
if (m?.production_lines) console.log(`${radius} 格内机器 ${m.machines_found} 个，分成 ${m.production_lines.length} 组产线（显示最大的 12 组）`);
else console.log('机器：', m?.error ?? '无');
console.log(`已保存 survey-${stamp}.json`);
