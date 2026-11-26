// Withdraw exact items from a container:  node withdraw.mjs x y z item[,item] count
import {t, log, watch} from './safe.mjs';
const [x, y, z] = process.argv.slice(2, 5).map(Number), items = process.argv[5], count = Number(process.argv[6] ?? 64);
const st = await t('mc_withdraw', {x, y, z, items, count, timeout_seconds: 120});
if (st.job?.state === 'failed') { log('✘', st.job.message); process.exit(1); }
const s = await watch(150);
log(s.job.state === 'succeeded' ? '✔' : '✘', s.job.message);
