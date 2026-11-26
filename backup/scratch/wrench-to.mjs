// Rotate a placed horizontal block with the Create wrench (top face click = 90 degrees around Y) until it faces `want`.
//   node wrench-to.mjs x y z facing
import {t, log} from './safe.mjs';
const [x, y, z] = process.argv.slice(2, 5).map(Number), want = process.argv[5];
const read = async () => (await t('mc_scan', {x, y, z, radius: 0, limit: 1})).blocks[0];
let b = await read();
log('now', b?.block, JSON.stringify(b?.state));
for (let i = 0; i < 4 && b?.state?.facing !== want; i++) {
  await t('mc_equip', {item: 'create:wrench'});
  const st = await t('mc_use_block', {x, y, z});
  for (let k = 0; k < 10; k++) { const s = await t('mc_wait', {seconds: 1}); if (s.job?.state !== 'running') { if (s.job?.state === 'failed') { log('✘', s.job.message); process.exit(1); } break; } }
  await new Promise(r => setTimeout(r, 600));
  b = await read();
  log('wrench ->', JSON.stringify(b?.state));
}
log(b?.state?.facing === want ? '✔ facing ' + want : '✘ could not reach ' + want);
process.exitCode = b?.state?.facing === want ? 0 : 1;
