// Compiles a compact building "design" (boxes, walls, roofs, ASCII facades) into a sparse SkyCodex blueprint.
// Usage: node bridge/design.mjs blueprints/src/NAME.design.json   ->  writes blueprints/NAME.json (+ preview)
import {readFile, writeFile} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const LIMIT = 64, MAX_BLOCKS = 20000;
const STAIR_FACING = {north: 'north', south: 'south', east: 'east', west: 'west'};

export function compileDesign(design) {
  if (!design || !Array.isArray(design.ops)) throw new Error('Design needs an "ops" array');
  const palette = design.palette ?? {};
  const cells = new Map(); // "x,y,z" -> {block, properties}
  const warnings = [];
  const resolve = (name, where) => {
    if (typeof name !== 'string' || !name) throw new Error(`${where}: block is required`);
    const id = palette[name] ?? name;
    if (typeof id === 'object') return {block: id.block, properties: id.properties};
    if (!id.includes(':')) throw new Error(`${where}: "${name}" is neither a palette key nor a namespaced block id`);
    return {block: id};
  };
  const put = (x, y, z, spec, extra) => {
    if (![x, y, z].every(n => Number.isInteger(n))) throw new Error(`Non-integer coordinate ${x},${y},${z}`);
    if (x < 0 || y < 0 || z < 0 || x >= LIMIT || y >= LIMIT || z >= LIMIT) throw new Error(`Coordinate ${x},${y},${z} outside 0..63; move the design or split it`);
    const properties = {...(spec.properties ?? {}), ...(extra ?? {})};
    cells.set(`${x},${y},${z}`, Object.keys(properties).length ? {block: spec.block, properties} : {block: spec.block});
  };
  const del = (x, y, z) => cells.delete(`${x},${y},${z}`);
  const box = (op, where) => {
    const a = op.from, b = op.to ?? op.from;
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== 3 || b.length !== 3) throw new Error(`${where}: from/to must be [x,y,z]`);
    return [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])];
  };
  const each = ([x0, y0, z0, x1, y1, z1], fn) => { for (let y = y0; y <= y1; y++) for (let z = z0; z <= z1; z++) for (let x = x0; x <= x1; x++) fn(x, y, z); };

  design.ops.forEach((op, i) => {
    const where = `ops[${i}] (${op?.op})`;
    switch (op?.op) {
      case 'fill': { const s = resolve(op.block, where); each(box(op, where), (x, y, z) => put(x, y, z, s, op.properties)); break; }
      case 'walls': { // four vertical sides, open top and bottom
        const s = resolve(op.block, where), b = box(op, where);
        each(b, (x, y, z) => { if (x === b[0] || x === b[3] || z === b[2] || z === b[5]) put(x, y, z, s, op.properties); }); break;
      }
      case 'box': { // hollow shell
        const s = resolve(op.block, where), b = box(op, where);
        each(b, (x, y, z) => { if (x === b[0] || x === b[3] || y === b[1] || y === b[4] || z === b[2] || z === b[5]) put(x, y, z, s, op.properties); }); break;
      }
      case 'frame': { // edges only: corner posts and beams (timber framing)
        const s = resolve(op.block, where), b = box(op, where);
        each(b, (x, y, z) => {
          const onX = x === b[0] || x === b[3], onY = y === b[1] || y === b[4], onZ = z === b[2] || z === b[5];
          if ((onX && onY) || (onX && onZ) || (onY && onZ)) {
            const axis = onX && onZ ? 'y' : onY && onZ ? 'x' : 'z';
            const isLog = /(_log|_wood|_stem|_hyphae|pillar|basalt)$/.test(s.block);
            put(x, y, z, s, isLog ? {axis, ...(op.properties ?? {})} : op.properties);
          }
        }); break;
      }
      case 'clear': each(box(op, where), del); break; // openings: those cells are left untouched in the world
      case 'set': { const [x, y, z] = op.at ?? []; put(x, y, z, resolve(op.block, where), op.properties); break; }
      case 'replace': {
        const from = resolve(op.match, where).block, s = resolve(op.block, where);
        each(box(op, where), (x, y, z) => { const c = cells.get(`${x},${y},${z}`); if (c && c.block === from) put(x, y, z, s, op.properties ?? c.properties); }); break;
      }
      case 'facade': facade(op, where); break;
      case 'roof': roof(op, where); break;
      default: throw new Error(`${where}: unknown op. Use fill, walls, box, frame, clear, set, replace, facade, roof`);
    }
  });

  function facade(op, where) {
    // ASCII picture of one plane. rows[0] is the TOP row (xy/zy planes) or the north row z=at.z (xz plane).
    // "." or " " = leave as is, "-" = clear cell, other characters map through op.key to palette/block ids.
    const plane = op.plane ?? 'xy', [ax, ay, az] = op.at ?? [];
    if (!Array.isArray(op.rows) || !op.rows.length) throw new Error(`${where}: rows required`);
    const key = op.key ?? {};
    op.rows.forEach((row, r) => [...row].forEach((ch, c) => {
      if (ch === '.' || ch === ' ') return;
      let x, y, z;
      if (plane === 'xy') { x = ax + c; y = ay + (op.rows.length - 1 - r); z = az; }
      else if (plane === 'zy') { x = ax; y = ay + (op.rows.length - 1 - r); z = az + c; }
      else if (plane === 'xz') { x = ax + c; y = ay; z = az + r; }
      else throw new Error(`${where}: plane must be xy, zy or xz`);
      if (ch === '-') { del(x, y, z); return; }
      const entry = key[ch];
      if (entry === undefined) throw new Error(`${where}: character "${ch}" has no entry in key`);
      if (typeof entry === 'object') put(x, y, z, resolve(entry.block, where), entry.properties);
      else put(x, y, z, resolve(entry, where));
    }));
  }

  function roof(op, where) {
    // Stair roof over the footprint from..to (y of "from" is the eave layer). Corner stair shapes are computed by the game.
    const b = box(op, where), y0 = op.from[1];
    const stairs = resolve(op.block, where), cap = op.cap ? resolve(op.cap, where) : null, gable = op.gable ? resolve(op.gable, where) : null;
    const half = op.upside_down ? 'top' : 'bottom';
    const st = (x, y, z, facing) => put(x, y, z, stairs, {facing: STAIR_FACING[facing], half});
    const style = op.style ?? 'gable';
    if (style === 'flat') { each([b[0], y0, b[2], b[3], y0, b[5]], (x, y, z) => put(x, y, z, cap ?? stairs)); return; }
    if (style === 'gable') {
      const ridgeX = (op.ridge ?? (b[3] - b[0] >= b[5] - b[2] ? 'x' : 'z')) === 'x';
      const lo = ridgeX ? b[2] : b[0], hi = ridgeX ? b[5] : b[3];
      for (let k = 0; lo + k <= hi - k; k++) {
        const y = y0 + k, a = lo + k, c = hi - k;
        const along = ridgeX ? [b[0], b[3]] : [b[2], b[5]];
        for (let t = along[0]; t <= along[1]; t++) {
          if (a === c) { const [x, z] = ridgeX ? [t, a] : [a, t]; put(x, y, z, cap ?? stairs); continue; }
          if (ridgeX) { st(t, y, a, 'south'); st(t, y, c, 'north'); } else { st(a, y, t, 'east'); st(c, y, t, 'west'); }
        }
        if (gable) for (let m = a + 1; m < c; m++) for (const t of along) { const inset = t === along[0] ? 1 : -1; const [x, z] = ridgeX ? [t + inset * (op.gable_inset ?? 0), m] : [m, t + inset * (op.gable_inset ?? 0)]; put(x, y, z, gable); }
      }
      return;
    }
    if (style === 'hip') {
      for (let k = 0; ; k++) {
        const x0 = b[0] + k, x1 = b[3] - k, z0 = b[2] + k, z1 = b[5] - k, y = y0 + k;
        if (x0 > x1 || z0 > z1) break;
        if (x0 === x1 || z0 === z1) { each([x0, y, z0, x1, y, z1], (x, yy, z) => put(x, yy, z, cap ?? stairs)); break; }
        for (let x = x0; x <= x1; x++) { st(x, y, z0, 'south'); st(x, y, z1, 'north'); }
        for (let z = z0 + 1; z < z1; z++) { st(x0, y, z, 'east'); st(x1, y, z, 'west'); }
      }
      return;
    }
    throw new Error(`${where}: roof style must be gable, hip or flat`);
  }

  const blocks = [...cells].map(([k, v]) => { const [x, y, z] = k.split(',').map(Number); return {x, y, z, ...v}; })
    .sort((p, q) => p.y - q.y || p.z - q.z || p.x - q.x);
  if (!blocks.length) throw new Error('Design produced no blocks');
  if (blocks.length > MAX_BLOCKS) throw new Error(`Design has ${blocks.length} blocks; maximum is ${MAX_BLOCKS}. Split it into several blueprints`);
  const materials = {};
  for (const b of blocks) materials[b.block] = (materials[b.block] ?? 0) + 1;
  const size = ['x', 'y', 'z'].map(k => Math.max(...blocks.map(b => b[k])) + 1);
  return {blueprint: {name: design.name ?? 'Unnamed design', ...(design.notes ? {notes: design.notes} : {}), blocks}, materials, size, warnings};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const input = process.argv[2];
  if (!input) { console.error('Usage: node bridge/design.mjs blueprints/src/NAME.design.json [output.json]'); process.exit(1); }
  const design = JSON.parse(await readFile(input, 'utf8'));
  const {blueprint, materials, size} = compileDesign(design);
  const out = process.argv[3] ?? path.join(path.dirname(path.dirname(path.resolve(input))), path.basename(input).replace(/\.design\.json$|\.json$/i, '') + '.json');
  await writeFile(out, JSON.stringify(blueprint, null, 1));
  console.log(`已生成 ${out}`);
  console.log(`尺寸 X×Y×Z = ${size.join('×')}，共 ${blueprint.blocks.length} 个方块`);
  console.log('材料清单（不含脚手架）：');
  for (const [id, n] of Object.entries(materials).sort((a, b) => b[1] - a[1])) console.log(`  ${id}: ${n}（${Math.floor(n / 64)} 组 + ${n % 64}）`);
  console.log(`下一步：node bridge/preview.mjs ${path.relative(process.cwd(), out)}`);
}
