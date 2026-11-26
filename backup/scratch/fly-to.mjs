// Fly to a far target in legs with mc_fly: legs are waypoints passed in flight (land=false), only the last one lands.
//   node fly-to.mjs X Y Z [leg=150] [cruiseY]
// cruiseY = minimum altitude to keep over void (sky islands); defaults to the start height.
import {t, log, moveTo} from './safe.mjs';
const [X, Y, Z] = process.argv.slice(2, 5).map(Number), LEG = Number(process.argv[5] ?? 150);
const start = await t('mc_status');
const CRUISE_Y = Number(process.argv[6] ?? start.position.y);
const LAND_R = Number(process.argv[7] ?? 6);
let skyTries = 0;
for (let leg = 1; leg <= 40; leg++) {
  const s = await t('mc_status');
  if (!s.enabled) { log('control off:', s.last_stop); process.exit(1); }
  const p = s.position, dx = X - p.x, dz = Z - p.z, d = Math.hypot(dx, dz);
  if (d <= 10 && !s.fall_flying) { log('arrived', JSON.stringify(p), 'hp', Math.round(s.health)); break; }
  const last = d <= LEG * 1.2;
  const k = last ? 1 : LEG / d;
  const tx = Math.round(p.x + dx * k), tz = Math.round(p.z + dz * k), ty = last ? Y : CRUISE_Y;
  await t('mc_fly', {x: tx, y: ty, z: tz, radius: last ? LAND_R : 24, cruise_height: 30, land: last, timeout_seconds: last ? 400 : 150});
  let w;
  for (let i = 0; i < 28; i++) { w = await t('mc_wait', {seconds: 15}); if (w.job?.state !== 'running') break; }
  const msg = w.job?.message ?? '';
  log(`leg ${leg} -> ${tx},${ty},${tz}: ${w.job?.state} ${msg} | hp ${Math.round(w.health)} hostiles ${w.hostiles_nearby?.length ?? 0}`);
  if (w.job?.state === 'succeeded') { if (last) break; continue; }
  if (/No open sky/.test(msg) && ++skyTries <= 6) {
    const q = w.position, ex = X - q.x, ez = Z - q.z, ed = Math.hypot(ex, ez);
    try { await moveTo(Math.round(q.x + ex / ed * 10), q.y, Math.round(q.z + ez / ed * 10), 3); } catch (e) { log('walk', e.message); }
    leg--; continue;
  }
  process.exit(1);
}

