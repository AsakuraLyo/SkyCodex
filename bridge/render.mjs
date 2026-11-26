// Dependency-free PNG renderer for blueprint elevations (front/right/back/left/top) with grid and labels.
import {deflateSync} from 'node:zlib';

const DYES = {white:'#e9ecec',orange:'#f07613',magenta:'#bd44b3',light_blue:'#3aafd9',yellow:'#f8c527',lime:'#70b919',pink:'#ed8dac',gray:'#3e4447',light_gray:'#8e8e86',cyan:'#158991',purple:'#792aac',blue:'#35399d',brown:'#724728',green:'#546d1b',red:'#a12722',black:'#141519'};
const WOOD = [['dark_oak','#4f3a22','#3c2b17'],['pale_oak','#e6dcd2','#5a5450'],['oak','#b8945f','#6b5236'],['spruce','#7a5a3a','#3d2b1a'],['birch','#d7c98c','#d8d7d2'],['jungle','#b07c55','#55451c'],['acacia','#b5603a','#676157'],['mangrove','#773632','#584a2d'],['cherry','#e3b3ad','#3a2028'],['bamboo','#d0c060','#7f9a3a'],['crimson','#7b3a52','#5c1d1e'],['warped','#2b7a73','#3a3a4d']];
const MATERIALS = [['glass','#c0dce4'],['deepslate','#4d4d50'],['blackstone','#2e2a30'],['mossy','#6f7f5a'],['cobblestone','#7a7a7a'],['stone_brick','#7c7c7c'],['smooth_stone','#a0a0a0'],['andesite','#888c8c'],['diorite','#c8c8c8'],['granite','#9a6c5a'],['red_sandstone','#b5622a'],['sandstone','#d8cb94'],['mud_brick','#8a6a50'],['nether_brick','#2c1619'],['brick','#966253'],['quartz','#ebe5de'],['prismarine','#5fa596'],['calcite','#dfe0dc'],['tuff','#6c6d66'],['copper','#c06a4f'],['iron','#d8d8d8'],['gold','#f5d33f'],['leaves','#4a7a2e'],['grass_block','#6b9a3c'],['dirt','#86603e'],['terracotta','#985e43'],['basalt','#4a4a4f'],['obsidian','#1a1028'],['snow','#f2f6f6'],['clay','#a0a6b4'],['hay','#c9a62c'],['bookshelf','#7a5a3a'],['lantern','#e7b45a'],['torch','#f2c94c'],['stone','#8a8a8a']];

export function colorOf(id) {
  const name = id.split(':').pop();
  for (const [dye, hex] of Object.entries(DYES).sort((a, b) => b[0].length - a[0].length)) if (name.startsWith(dye + '_')) {
    return name.includes('terracotta') ? shade(hex, 0.72) : hex;
  }
  for (const [wood, planks, log] of WOOD) if (name.includes(wood)) return /(_log|_wood|_stem|_hyphae|bamboo_block)$/.test(name) ? log : planks;
  for (const [key, hex] of MATERIALS) if (name.includes(key)) return hex;
  return '#a2aaa0';
}
export function shade(hex, m) {
  return '#' + hex.slice(1).match(/../g).map(c => Math.max(0, Math.min(255, Math.round(parseInt(c, 16) * m))).toString(16).padStart(2, '0')).join('');
}
const rgb = hex => hex.slice(1).match(/../g).map(c => parseInt(c, 16));

// 3x5 bitmap font, rows of 3 pixels ('#' = ink): uppercase, digits and a few symbols.
const FONT = {A:'.#.#.#####.##.#',B:'##.#.###.#.###.',C:'.###..#..#...##',D:'##.#.##.##.###.',E:'####..##.#..###',F:'####..##.#..#..',G:'.###..#.##.#.##',H:'#.##.#####.##.#',I:'###.#..#..#.###',J:'..#..#..##.#.#.',K:'#.##.###.#.##.#',L:'#..#..#..#..###',M:'#.########.##.#',N:'##.#.##.##.##.#',O:'.#.#.##.##.#.#.',P:'##.#.###.#..#..',Q:'.#.#.##.###..##',R:'##.#.###.#.##.#',S:'.###...#...###.',T:'###.#..#..#..#.',U:'#.##.##.##.####',V:'#.##.##.##.#.#.',W:'#.##.########.#',X:'#.##.#.#.#.##.#',Y:'#.##.#.#..#..#.',Z:'###..#.#.#..###',0:'####.##.##.####',1:'.#.##..#..#.###',2:'##...#.#.#..###',3:'##...#.#...###.',4:'#.##.####..#..#',5:'####..##...###.',6:'.###..####.####',7:'###..#.#..#..#.',8:'####.#####.####',9:'####.####..###.','=':'...###...###...','+':'....#.###.#....','-':'......###......','(':'.#.#..#..#...#.',')':'.#...#..#..#.#.',',':'..........#.#..','.':'.............#.','/':'..#..#.#.#..#..',':':'....#.....#....',' ':'...............','>':'#...#...#.#.#..','<':'..#.#.#...#...#','#':'#.#####.#####.#','?':'##...#.#.....#.'};
const GLYPH_ROWS = c => { const s = FONT[c] ?? FONT['?']; return [0, 1, 2, 3, 4].map(r => [0, 1, 2].reduce((bits, col) => bits | (s[r * 3 + col] === '#' ? 1 << col : 0), 0)); };

class Canvas {
  constructor(w, h, bg) { this.w = w; this.h = h; this.px = Buffer.alloc(w * h * 3); const [r, g, b] = rgb(bg); for (let i = 0; i < w * h; i++) { this.px[i * 3] = r; this.px[i * 3 + 1] = g; this.px[i * 3 + 2] = b; } }
  rect(x, y, w, h, hex) { const [r, g, b] = rgb(hex); for (let yy = Math.max(0, y); yy < Math.min(this.h, y + h); yy++) for (let xx = Math.max(0, x); xx < Math.min(this.w, x + w); xx++) { const i = (yy * this.w + xx) * 3; this.px[i] = r; this.px[i + 1] = g; this.px[i + 2] = b; } }
  text(x, y, str, hex, scale = 2) {
    let cx = x;
    for (const ch of str.toUpperCase()) { GLYPH_ROWS(ch).forEach((bits, row) => { for (let col = 0; col < 3; col++) if (bits & (1 << col)) this.rect(cx + col * scale, y + row * scale, scale, scale, hex); }); cx += 4 * scale; }
  }
  png() {
    const raw = Buffer.alloc((this.w * 3 + 1) * this.h);
    for (let y = 0; y < this.h; y++) { raw[y * (this.w * 3 + 1)] = 0; this.px.copy(raw, y * (this.w * 3 + 1) + 1, y * this.w * 3, (y + 1) * this.w * 3); }
    const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td)); return Buffer.concat([len, td, crc]); };
    const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(this.w, 0); ihdr.writeUInt32BE(this.h, 4); ihdr[8] = 8; ihdr[9] = 2;
    return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
  }
}
const CRC = new Uint32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
function crc32(buf) { let c = 0xffffffff; for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }

/** Orthographic views. Each view maps a block to (u, v, depth); the nearest block per (u, v) wins. */
export function views(blocks) {
  const W = Math.max(...blocks.map(b => b.x)) + 1, H = Math.max(...blocks.map(b => b.y)) + 1, D = Math.max(...blocks.map(b => b.z)) + 1;
  const defs = [
    {label: 'FRONT (FROM Z=0 SIDE, X ->)', w: W, h: H, map: b => [b.x, H - 1 - b.y, b.z], depth: D},
    {label: 'RIGHT (FROM MAX X, Z ->)', w: D, h: H, map: b => [b.z, H - 1 - b.y, W - 1 - b.x], depth: W},
    {label: 'BACK (FROM MAX Z, X <-)', w: W, h: H, map: b => [W - 1 - b.x, H - 1 - b.y, D - 1 - b.z], depth: D},
    {label: 'LEFT (FROM X=0, Z <-)', w: D, h: H, map: b => [D - 1 - b.z, H - 1 - b.y, b.x], depth: W},
    {label: 'TOP (X ->, Z DOWN)', w: W, h: D, map: b => [b.x, b.z, H - 1 - b.y], depth: H},
  ];
  return defs.map(v => {
    const grid = new Map();
    for (const b of blocks) { const [u, w, d] = v.map(b); const k = u + ',' + w; const prev = grid.get(k); if (!prev || d < prev.d) grid.set(k, {d, b}); }
    return {...v, grid};
  });
}

export function renderPng(blueprint, title) {
  const blocks = blueprint.blocks;
  const vs = views(blocks);
  const maxDim = Math.max(...vs.map(v => Math.max(v.w, v.h)));
  const cell = Math.max(4, Math.min(18, Math.floor(420 / maxDim)));
  const pad = 24, labelH = 22;
  const panelW = v => v.w * cell, panelH = v => v.h * cell + labelH;
  const row1 = vs.slice(0, 3), row2 = vs.slice(3);
  const rowW = r => r.reduce((s, v) => s + Math.max(panelW(v), v.label.length * 8) + pad, pad);
  const width = Math.max(rowW(row1), rowW(row2), 360);
  const h1 = Math.max(...row1.map(panelH)), h2 = Math.max(...row2.map(panelH));
  const height = 40 + h1 + pad + h2 + pad + 26;
  const c = new Canvas(width, height, '#f3f4f5');
  c.text(pad, 12, (title ?? 'BLUEPRINT').replace(/[^\x20-\x7e]/g, '?').slice(0, 60) + `  ${blocks.length} BLOCKS`, '#18252c', 2);
  const draw = (v, ox, oy) => {
    c.text(ox, oy, v.label, '#33424c', 2);
    const top = oy + labelH;
    c.rect(ox, top, v.w * cell, v.h * cell, '#e2e5e8');
    for (const [k, {d, b}] of v.grid) {
      const [u, w] = k.split(',').map(Number);
      const depthShade = 1 - 0.45 * (d / Math.max(1, v.depth - 1));
      let hex = shade(colorOf(b.block), depthShade);
      const x = ox + u * cell, y = top + w * cell, name = b.block;
      if (name.endsWith('_slab') && v.label[0] !== 'T') { const topHalf = b.properties?.type === 'top'; c.rect(x, y + (topHalf ? 0 : cell / 2 | 0), cell, cell / 2 | 0 || 1, hex); continue; }
      c.rect(x, y, cell, cell, hex);
      if (name.endsWith('_stairs') && cell >= 6) c.rect(x, y, cell / 2 | 0, cell / 2 | 0, shade(hex, 1.18));
      if (/glass|pane/.test(name) && cell >= 6) c.rect(x + 2, y + 2, cell - 4, cell - 4, shade(hex, 1.12));
    }
    // Grid: thin line per block when large enough, darker every 5 blocks for counting against the reference image.
    for (let u = 0; u <= v.w; u++) if (cell >= 8 || u % 5 === 0) c.rect(ox + u * cell, top, 1, v.h * cell, u % 5 === 0 ? '#5d6b75' : '#c3c9ce');
    for (let w = 0; w <= v.h; w++) if (cell >= 8 || w % 5 === 0) c.rect(ox, top + w * cell, v.w * cell, 1, w % 5 === 0 ? '#5d6b75' : '#c3c9ce');
    c.text(ox, top + v.h * cell + 4, `${v.w}X${v.h}`, '#53636e', 1);
  };
  let x = pad; for (const v of row1) { draw(v, x, 40); x += Math.max(panelW(v), v.label.length * 8) + pad; }
  x = pad; for (const v of row2) { draw(v, x, 40 + h1 + pad); x += Math.max(panelW(v), v.label.length * 8) + pad; }
  c.text(pad, height - 18, 'APPROXIMATE COLOURS. DARKER = FARTHER FROM VIEWER. GRID LINE EVERY 5 BLOCKS.', '#53636e', 1);
  return c.png();
}
