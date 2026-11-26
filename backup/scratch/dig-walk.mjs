// Dig a 2-high tunnel along a list of cells and walk into each one.  node dig-walk.mjs "x,y,z;x,y,z;..."
import {t, log} from './safe.mjs';
const cells = process.argv[2].split(';').map(s => s.split(',').map(Number));
for (const [x, y, z] of cells) {
  for (const yy of [y, y + 1]) {
    const s = await t('mc_scan', {x, y: yy, z, radius: 0, limit: 1});
    const b = s.blocks?.[0]?.block;
    if (b && b !== 'minecraft:air') {
      await t('mc_break', {x, y: yy, z, allow_container: false, timeout_seconds: 40});
      let w; for (let i = 0; i < 5; i++) { w = await t('mc_wait', {seconds: 8}); if (w.job?.state !== 'running') break; }
      log(`break ${x},${yy},${z} ${b.split(':')[1]}: ${w.job?.state}`);
    }
  }
  await t('mc_move', {x, y, z, radius: 0, timeout_seconds: 20});
  let w; for (let i = 0; i < 3; i++) { w = await t('mc_wait', {seconds: 8}); if (w.job?.state !== 'running') break; }
  const s = await t('mc_status');
  log(`at ${JSON.stringify(s.position)} (move ${w.job?.state})`);
}
