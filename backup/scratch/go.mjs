// Walk safely to x y z [radius]: stops on damage or nearby hostiles (see safe.mjs). radius 0 = exactly that block.
import {moveTo, log} from './safe.mjs';
const [x, y, z, radius] = process.argv.slice(2).map(Number);
try { const s = await moveTo(x, y, z, Number.isNaN(radius) || radius === undefined ? 2 : radius); log('到达', JSON.stringify(s.position), '生命', s.health, '饥饿', s.food); }
catch (e) { log('✘', e.message); process.exitCode = 1; }
