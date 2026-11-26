// Runs SkyCodex bridge/setup.mjs and answers its game-directory prompt once it appears.
import {spawn} from 'node:child_process';
const [setup, gameDir] = process.argv.slice(2);
const child = spawn(process.execPath, [setup], {cwd: process.cwd(), stdio: ['pipe', 'pipe', 'inherit']});
let answered = false;
child.stdout.on('data', d => {
  const s = d.toString('utf8'); process.stdout.write(s);
  if (!answered && /目录/.test(s) && /[：:]\s*$/.test(s.trimEnd() + ' ') ) { answered = true; child.stdin.write(gameDir + '\n'); }
});
setTimeout(() => { if (!answered) { answered = true; child.stdin.write(gameDir + '\n'); } }, 8000);
child.on('exit', code => process.exit(code ?? 1));
