// Read-only occupancy map: check an all-air blueprint over a box; every conflict is a non-air block.
//   node occupancy.mjs x0 y0 z0 sx sy sz out.json
import {t} from './safe.mjs';
import {writeFile, mkdir} from 'node:fs/promises';
const [x0, y0, z0, sx, sy, sz] = process.argv.slice(2, 8).map(Number), out = process.argv[8];
const blocks = [];
for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) blocks.push({x, y, z, block: 'minecraft:sponge'});
await mkdir('E:/MC/SkyCodex/blueprints', {recursive: true});
await writeFile('E:/MC/SkyCodex/blueprints/_occupancy.json', JSON.stringify({blocks}));
const r = await t('mc_check_blueprint', {file: '_occupancy.json', origin: {x: x0, y: y0, z: z0}});
await writeFile(out, JSON.stringify(r, null, 1));
console.log(Object.keys(r).join(','), 'conflicts:', (r.conflicts ?? []).length, 'already_correct:', r.already_correct);
console.log(JSON.stringify((r.conflicts ?? []).slice(0, 3)));

