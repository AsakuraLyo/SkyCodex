// Shared helpers for driving the real game safely: stop on ANY damage (mobs or players), nearby hostiles, or lost control.
import {createClient, callTool} from 'file:///E:/MC/SkyCodex/bridge/tools.mjs';
export const GAME = 'E:\\MC\\.minecraft\\versions\\天空开拓者 SkyExplorer 0.2demo';
export const r = createClient(GAME);
export const t = async (n, a = {}) => { const x = await callTool(n, a, r); return x.content[0].type === 'image' ? x.content[0] : JSON.parse(x.content[0].text); };
export const log = (...a) => console.log(new Date().toTimeString().slice(0, 8), ...a);
/**
 * Wait for the current job. The user asked NOT to stop on damage or nearby enemies (this jar cannot fight back yet):
 * they are only logged. Abort (mc_stop) only when health falls below 35% of the highest value seen.
 */
export async function watch(maxSeconds = 300) {
  let s = await t('mc_status'); let hp = s.health, top = s.health; const end = Date.now() + maxSeconds * 1000;
  for (;;) {
    s = await t('mc_wait', {seconds: 3});
    const danger = (s.hostiles_nearby ?? []).filter(h => h.distance <= 8 && Math.abs(h.y - s.position.y) <= 3);
    if (s.health < hp - 4 || danger.length) log(`⚠ 受伤/遇敌，继续：生命 ${Math.round(hp)} -> ${Math.round(s.health)} ${JSON.stringify(danger)}`);
    top = Math.max(top, s.health);
    if (s.health < top * 0.35) { await t('mc_stop'); throw new Error(`生命过低，已停止：${Math.round(s.health)}/${Math.round(top)}`); }
    // SAFE_MAX_DROP=n: abort the job (keep control) as soon as health falls n below the best seen (e.g. near a tesla coil).
    const maxDrop = Number(process.env.SAFE_MAX_DROP ?? 0);
    if (maxDrop > 0 && s.health < top - maxDrop) {
      await t('mc_move', {x: Number(process.env.SAFE_RETREAT_X), y: Number(process.env.SAFE_RETREAT_Y), z: Number(process.env.SAFE_RETREAT_Z), radius: 2}).catch(() => {});
      throw new Error(`掉血 ${Math.round(top)} -> ${Math.round(s.health)}，已撤离到安全点`);
    }
    hp = s.health;
    if (!s.enabled) throw new Error('接管已关闭：' + s.last_stop);
    if (s.job?.state !== 'running' && s.sequence?.state !== 'running') return s;
    if (Date.now() > end) { await t('mc_stop'); throw new Error('超时'); }
  }
}
export async function moveTo(x, y, z, radius = 2) {
  const st = await t('mc_move', {x, y, z, radius, timeout_seconds: 180});
  if (st.job?.state === 'failed') throw new Error(st.job.message);
  const s = await watch(200);
  if (s.job.state !== 'succeeded') throw new Error(s.job.message);
  return s;
}
