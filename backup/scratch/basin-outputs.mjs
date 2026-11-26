// List basins near the main factory and what sits on their output side.
import {t} from './safe.mjs';
const seen = new Set();
const D = {north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0]};
for (const [x, y, z] of [[70, 70, 480], [60, 70, 480], [80, 70, 470]]) {
  const s = await t('mc_scan', {block: 'create:basin', x, y, z, radius: 16, limit: 20});
  for (const b of s.blocks ?? []) {
    const k = `${b.x},${b.y},${b.z}`; if (seen.has(k) || b.z >= 494) continue; seen.add(k);
    let line = `basin ${k} facing=${b.state?.facing}`;
    if (b.state?.facing && b.state.facing !== 'down') {
      const [dx, dz] = D[b.state.facing];
      for (const dy of [0, -1]) {
        const n = await t('mc_scan', {x: b.x + dx, y: b.y + dy, z: b.z + dz, radius: 0, limit: 1});
        const nb = n.blocks?.[0];
        line += ` | ${b.x + dx},${b.y + dy},${b.z + dz} ${nb?.block ?? 'air'} ${JSON.stringify(nb?.state ?? {})}`;
      }
    }
    console.log(line);
  }
}
