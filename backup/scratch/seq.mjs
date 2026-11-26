// Run an mc_sequence from a JSON file and watch it:  node seq.mjs steps.json
import {t, log} from './safe.mjs';
import {readFile} from 'node:fs/promises';
const steps = JSON.parse(await readFile(process.argv[2], 'utf8'));
const r = await t('mc_sequence', {steps});
if (r.sequence?.state === 'failed') { log('✘', r.sequence.message); process.exit(1); }
for (;;) {
  const s = await t('mc_wait', {seconds: 20});
  if (s.defense?.active) log('⚔', JSON.stringify(s.defense));
  if (s.sequence?.state !== 'running') {
    log(s.sequence?.state === 'succeeded' ? '✔' : '✘', s.sequence?.message);
    for (const l of s.sequence?.log ?? []) log('  ', l.state, l.tool ?? l.op ?? '', l.message);
    break;
  }
}
