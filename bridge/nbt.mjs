// Convert a structure .nbt (vanilla structure block / Create schematic, as saved in the game's `schematics` folder)
// into a SkyCodex sparse JSON blueprint, and report which blocks the beta builder cannot place.
//   node bridge/nbt.mjs <file.nbt> [out.json] [--keep-air]
// Pure Node, no dependencies.
import {readFile, writeFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

/** Minimal NBT reader (big-endian, Java edition). Returns plain JS values; longs become BigInt. */
export function parseNbt(buffer) {
  let buf = buffer;
  if (buf[0] === 0x1f && buf[1] === 0x8b) buf = gunzipSync(buf);
  let o = 0;
  const u8 = () => buf.readUInt8(o++);
  const i16 = () => { const v = buf.readInt16BE(o); o += 2; return v; };
  const u16 = () => { const v = buf.readUInt16BE(o); o += 2; return v; };
  const i32 = () => { const v = buf.readInt32BE(o); o += 4; return v; };
  const str = () => { const n = u16(); const s = buf.toString('utf8', o, o + n); o += n; return s; };
  const payload = type => {
    switch (type) {
      case 1: return buf.readInt8(o++);
      case 2: return i16();
      case 3: return i32();
      case 4: { const v = buf.readBigInt64BE(o); o += 8; return v; }
      case 5: { const v = buf.readFloatBE(o); o += 4; return v; }
      case 6: { const v = buf.readDoubleBE(o); o += 8; return v; }
      case 7: { const n = i32(); const v = [...buf.subarray(o, o + n)].map(b => (b << 24) >> 24); o += n; return v; }
      case 8: return str();
      case 9: { const t = u8(), n = i32(); const v = []; for (let i = 0; i < n; i++) v.push(payload(t)); return v; }
      case 10: { const v = {}; for (let t; (t = u8()) !== 0;) { const k = str(); v[k] = payload(t); } return v; }
      case 11: { const n = i32(); const v = []; for (let i = 0; i < n; i++) v.push(i32()); return v; }
      case 12: { const n = i32(); const v = []; for (let i = 0; i < n; i++) { v.push(buf.readBigInt64BE(o)); o += 8; } return v; }
      default: throw new Error(`Unknown NBT tag type ${type} at byte ${o}`);
    }
  };
  const type = u8();
  if (type !== 10) throw new Error('Not an NBT compound file');
  str();
  return payload(10);
}

// Mirrors Blueprint.java: what the beta builder refuses or cannot place.
const BLOCK_ENTITY_HINT = /chest|barrel|furnace|smoker|hopper|dispenser|dropper|shulker|sign|banner|bed$|_bed|lectern|campfire|brewing|beacon|spawner|skull|head|bell|jukebox|decorated_pot|crafter|chiseled_bookshelf|enchanting|ender_chest|command_block|structure_block|jigsaw|conduit|beehive|bee_nest|comparator|daylight_detector|piston_head|moving_piston|vault|trial_spawner|creaking/;
const FLUIDS = new Set(['minecraft:water', 'minecraft:lava', 'minecraft:bubble_column']);
const AIR = new Set(['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:structure_void']);

export function convertStructure(root, {name = 'converted', keepAir = false} = {}) {
  if (!Array.isArray(root.size) || !Array.isArray(root.blocks)) throw new Error('Not a structure file (missing size/blocks)');
  const palette = root.palette ?? root.palettes?.[0];
  if (!Array.isArray(palette)) throw new Error('Structure has no palette');
  const [sx, sy, sz] = root.size;
  const blocks = [], issues = new Map(), materials = new Map();
  const note = (kind, id) => { const k = `${kind}|${id}`; issues.set(k, (issues.get(k) ?? 0) + 1); };
  let air = 0;
  for (const b of root.blocks) {
    const entry = palette[b.state];
    const id = entry.Name;
    const [x, y, z] = b.pos;
    if (AIR.has(id)) { air++; if (!keepAir) continue; }
    const properties = {...(entry.Properties ?? {})};
    // A structure can store a waterlogged stair etc.; beta blueprints place them dry.
    if (properties.waterlogged === 'true') { note('waterlogged (will be placed dry)', id); properties.waterlogged = 'false'; }
    if (FLUIDS.has(id)) { note('fluid (skipped)', id); continue; }
    if (b.nbt || BLOCK_ENTITY_HINT.test(id.split(':')[1])) { note('block entity (skipped; place with mc_place)', id); continue; }
    if (/_door$/.test(id)) { note('door (skipped)', id); continue; }
    if (properties.type === 'double' && /slab/.test(id)) { note('double slab (skipped)', id); continue; }
    if (!id.startsWith('minecraft:')) note('modded block (check in modpack)', id);
    if (x > 63 || y > 63 || z > 63) { note('outside 0..63 (split the blueprint)', id); continue; }
    const cell = {x, y, z, block: id};
    if (Object.keys(properties).length) cell.properties = properties;
    blocks.push(cell);
    materials.set(id, (materials.get(id) ?? 0) + 1);
  }
  blocks.sort((a, b) => a.y - b.y || a.z - b.z || a.x - b.x);
  return {
    blueprint: {name, blocks},
    report: {
      size: {x: sx, y: sy, z: sz}, total_cells: root.blocks.length, air_cells: air, blueprint_blocks: blocks.length,
      entities: (root.entities ?? []).map(e => e.nbt?.id ?? 'unknown'),
      materials: [...materials].sort((a, b) => b[1] - a[1]).map(([item, count]) => ({block: item, count})),
      issues: [...issues].map(([k, count]) => { const [kind, block] = k.split('|'); return {kind, block, count}; }),
    },
  };
}

/**
 * Items a Create schematicannon needs to print the whole structure, machines included (approximate: one item per
 * block with the usual exceptions). Fluids cannot be printed. Unknown ids mean a mod the pack does not have.
 */
export function cannonMaterials(root) {
  const palette = root.palette ?? root.palettes?.[0];
  const need = new Map(), notPrintable = new Map();
  const add = (m, id, n = 1) => m.set(id, (m.get(id) ?? 0) + n);
  const wallMap = [[/^(.*)_wall_hanging_sign$/, '$1_hanging_sign'], [/^(.*)_wall_sign$/, '$1_sign'], [/^(.*)_wall_banner$/, '$1_banner'],
    [/^(.*)_wall_(head|skull)$/, '$1_$2'], [/^(redstone|soul)_wall_torch$/, '$1_torch'], [/^wall_torch$/, 'torch'], [/^(.*)_wall_fan$/, '$1_fan']];
  const special = {'minecraft:wheat': 'minecraft:wheat_seeds', 'minecraft:carrots': 'minecraft:carrot', 'minecraft:potatoes': 'minecraft:potato',
    'minecraft:beetroots': 'minecraft:beetroot_seeds', 'minecraft:cave_vines': 'minecraft:glow_berries', 'minecraft:cave_vines_plant': 'minecraft:glow_berries',
    'minecraft:sweet_berry_bush': 'minecraft:sweet_berries', 'minecraft:redstone_wire': 'minecraft:redstone', 'minecraft:tripwire': 'minecraft:string',
    'minecraft:water_cauldron': 'minecraft:cauldron', 'minecraft:lava_cauldron': 'minecraft:cauldron', 'minecraft:powder_snow_cauldron': 'minecraft:cauldron',
    'minecraft:tall_seagrass': 'minecraft:seagrass', 'minecraft:pumpkin_stem': 'minecraft:pumpkin_seeds', 'minecraft:melon_stem': 'minecraft:melon_seeds',
    'minecraft:attached_pumpkin_stem': 'minecraft:pumpkin_seeds', 'minecraft:attached_melon_stem': 'minecraft:melon_seeds', 'minecraft:cocoa': 'minecraft:cocoa_beans',
    'minecraft:kelp_plant': 'minecraft:kelp', 'minecraft:twisting_vines_plant': 'minecraft:twisting_vines', 'minecraft:weeping_vines_plant': 'minecraft:weeping_vines',
    'minecraft:bamboo_sapling': 'minecraft:bamboo', 'minecraft:big_dripleaf_stem': 'minecraft:big_dripleaf'};
  for (const b of root.blocks) {
    const e = palette[b.state], id = e.Name, p = e.Properties ?? {};
    if (AIR.has(id)) continue;
    if (FLUIDS.has(id) || /^minecraft:(fire|soul_fire|nether_portal|end_portal|end_gateway|moving_piston|piston_head|frosted_ice)$/.test(id)) { add(notPrintable, id); continue; }
    if (id === 'minecraft:barrier' || /command_block|structure_block|jigsaw/.test(id)) { add(notPrintable, id + ' (creative only)'); continue; }
    if (p.part === 'head' || p.half === 'upper' && /door|tall|sunflower|lilac|rose_bush|peony|large_fern|pitcher|small_dripleaf/.test(id)) continue; // other half of a 2-block item
    const [ns, name] = id.split(':');
    let item = special[id] ?? id;
    if (id.startsWith('minecraft:potted_')) { add(need, 'minecraft:flower_pot'); item = 'minecraft:' + name.slice('potted_'.length); }
    else for (const [re, rep] of wallMap) if (re.test(name)) { item = ns + ':' + name.replace(re, rep); break; }
    let n = 1;
    if (p.type === 'double' && /slab/.test(id)) n = 2;
    for (const k of ['pickles', 'candles', 'eggs', 'layers', 'flower_amount', 'segment_amount']) if (p[k]) n = Number(p[k]);
    if (p.waterlogged === 'true') add(notPrintable, 'water (waterlogging)');
    if (/cauldron$/.test(id) && id !== 'minecraft:cauldron') add(notPrintable, id + ' contents');
    add(need, item, n);
  }
  const sorted = m => [...m].sort((a, b) => b[1] - a[1]).map(([item, count]) => ({item, count}));
  return {items: sorted(need), total: [...need.values()].reduce((a, b) => a + b, 0), not_printable: sorted(notPrintable),
    note: 'Approximate list for a Create schematicannon (it computes the exact one itself). Verify ids with mc_items: unknown ids are mods this pack does not have. Contents of chests/machines are not included.'};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2).filter(a => !a.startsWith('--'));
  if (!args[0]) { console.error('用法：node bridge/nbt.mjs <蓝图.nbt> [输出.json] [--keep-air] | node bridge/nbt.mjs <蓝图.nbt> --materials'); process.exit(2); }
  if (process.argv.includes('--materials')) {
    console.log(JSON.stringify(cannonMaterials(parseNbt(await readFile(path.resolve(args[0])))), null, 2));
    process.exit(0);
  }
  const input = path.resolve(args[0]);
  const name = path.basename(input, '.nbt');
  const out = path.resolve(args[1] ?? path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'blueprints', `${name}.json`));
  const {blueprint, report} = convertStructure(parseNbt(await readFile(input)), {name, keepAir: process.argv.includes('--keep-air')});
  if (blueprint.blocks.length > 20000) console.warn(`警告：${blueprint.blocks.length} 块超过单份蓝图上限 20000，需要拆分。`);
  await writeFile(out, JSON.stringify(blueprint));
  console.log(JSON.stringify({output: out, ...report}, null, 2));
}
