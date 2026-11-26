// Place blocks one by one with mc_place (optionally oriented) and verify each with mc_scan.
//   node place-list.mjs list.json      list = [{x,y,z,item,properties?}, ...]
// Stuck handling (user request): if the character has not moved for 6 s while the job is still running, the same
// placement is issued again (re-plans the stand position and the path), at most 2 times per block.
// SAFE_MAX_DROP=n aborts when health falls n below the best value seen during a placement.
import {t, log} from './safe.mjs';
import {readFile} from 'node:fs/promises';
const list = JSON.parse((await readFile(process.argv[2], 'utf8')).replace(/^﻿/, ''));
const maxDrop = Number(process.env.SAFE_MAX_DROP ?? 0);
const same = (a, b) => a && b && a.x === b.x && a.y === b.y && a.z === b.z;
async function placeOnce(b) {
  const st = await t('mc_place', {x: b.x, y: b.y, z: b.z, item: b.item, ...(b.properties ? {properties: b.properties} : {}), timeout_seconds: Number(process.env.PLACE_TIMEOUT ?? 90)});
  if (st.job?.state === 'failed') return {state: 'failed', message: st.job.message};
  let last = st.position, still = 0, top = st.health;
  for (;;) {
    const s = await t('mc_wait', {seconds: 2});
    top = Math.max(top, s.health);
    if (maxDrop > 0 && s.health < top - maxDrop) {
      await t('mc_move', {x: Number(process.env.SAFE_RETREAT_X), y: Number(process.env.SAFE_RETREAT_Y), z: Number(process.env.SAFE_RETREAT_Z), radius: 2}).catch(() => {});
      return {state: 'danger', message: `掉血 ${Math.round(top)} -> ${Math.round(s.health)}，已撤离`};
    }
    if (s.job?.state !== 'running') return {state: s.job.state, message: s.job.message};
    if (same(s.position, last)) still += 2; else { still = 0; last = s.position; }
    if (still >= 6 && s.job.elapsed_seconds >= 6) return {state: 'stuck', message: `卡在 ${JSON.stringify(s.position)} 不动`};
  }
}
for (const b of list) {
  const before = (await t('mc_scan', {x: b.x, y: b.y, z: b.z, radius: 0, limit: 1})).blocks[0];
  if (before?.block === b.item && Object.entries(b.properties ?? {}).every(([k, v]) => before.state?.[k] === v)) { log('= already', b.item, b.x, b.y, b.z); continue; }
  if (before) { log('✘ occupied by', before.block, JSON.stringify(before.state ?? {}), 'at', b.x, b.y, b.z, '- stopping'); process.exit(1); }
  let r;
  for (let attempt = 0; attempt < 3; attempt++) {
    r = await placeOnce(b);
    if (r.state !== 'stuck') break;
    log('↻', r.message, '- 等任务超时后换站位重新寻路');
    for (;;) { const s = await t('mc_wait', {seconds: 5}); if (s.job?.state !== 'running') break; }
    const alt = (b.alt ?? [])[attempt]; if (alt) await t('mc_move', {...alt, radius: 0}).then(async () => { for (;;) { const s = await t('mc_wait', {seconds: 3}); if (s.job?.state !== 'running') break; } });
  }
  const after = (await t('mc_scan', {x: b.x, y: b.y, z: b.z, radius: 0, limit: 1})).blocks[0];
  const ok = r.state === 'succeeded' && after?.block === b.item;
  log(ok ? '✔' : '✘', b.item, `${b.x},${b.y},${b.z}`, JSON.stringify(after?.state ?? {}), ok ? '' : r.message);
  if (!ok) process.exit(1);
}
