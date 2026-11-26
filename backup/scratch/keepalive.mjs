// Keeps the SkyCodex heartbeat alive while Claude is actively working (mc_status every 20 s).
// Stops by itself after MAX minutes, when control is turned off (F9 / anything else), or when keepalive.stop exists.
import {createClient} from 'file:///E:/MC/SkyCodex/bridge/tools.mjs';
import {existsSync, unlinkSync} from 'node:fs';
const r = createClient('E:\\MC\\.minecraft\\versions\\天空开拓者 SkyExplorer 0.2demo');
const STOP = 'E:/MC/dev/scratch/keepalive.stop', MAX = Number(process.argv[2] ?? 90);
if (existsSync(STOP)) unlinkSync(STOP);
const end = Date.now() + MAX * 60000; let seenEnabled = false;
while (Date.now() < end && !existsSync(STOP)) {
  try {
    const s = await r('status');
    if (s.enabled) seenEnabled = true;
    else if (seenEnabled) { console.log(new Date().toTimeString().slice(0, 8), 'control turned off:', s.last_stop, '- keepalive ends'); break; }
  } catch (e) { console.log('status failed:', e.message); }
  await new Promise(res => setTimeout(res, 20000));
}
console.log(new Date().toTimeString().slice(0, 8), 'keepalive stopped');
