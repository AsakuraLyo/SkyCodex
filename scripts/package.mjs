// Assembles the user-facing package: dist/SkyCodex-<version>/ and a .zip next to it.
// Run after the Gradle build:  node scripts/package.mjs
import {cp, mkdir, readFile, readdir, rm, writeFile, stat} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {deflateRawSync} from 'node:zlib';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const version = (await readFile(path.join(root, 'build.gradle'), 'utf8')).match(/version\s*=\s*'([^']+)'/)[1];
const modJar = `skycodex-neoforge-1.21.1-${version}.jar`, baritoneJar = 'baritone-api-neoforge-1.11.3.jar';
const name = `SkyCodex-${version}`, out = path.join(root, 'dist', name);
const builtJar = path.join(root, 'build', 'libs', modJar);
try { await stat(builtJar); } catch { throw new Error(`缺少 ${builtJar}，请先运行 Gradle build`); }

await rm(out, {recursive: true, force: true});
await mkdir(path.join(out, 'mods'), {recursive: true});
await cp(builtJar, path.join(out, 'mods', modJar));
await cp(path.join(root, 'libs', baritoneJar), path.join(out, 'mods', baritoneJar));
const checksums = {};
for (const jar of [modJar, baritoneJar]) checksums[jar] = createHash('sha256').update(await readFile(path.join(out, 'mods', jar))).digest('hex');
await writeFile(path.join(out, 'checksums.json'), JSON.stringify(checksums, null, 2));

const copy = (rel, dest = rel, opts = {}) => cp(path.join(root, rel), path.join(out, dest), {recursive: true, ...opts});
for (const f of ['AGENTS.md', 'README-中文.md', 'VALIDATION.md', 'Windows开发指南.md', 'LICENSE', '01-install.cmd', '02-check.cmd', 'install-mac.command', 'check-mac.command']) await copy(f);
await copy('bridge');
await copy('blueprints');
await copy('third_party');
// Complete development workspace (README: source/, gradlew build; Windows开发指南.md).
const SKIP = new Set(['.git', 'build', '.gradle', 'dist', 'run', 'run-modpack', 'memory', 'surveys','smoke-report.json', 'game-dir.json', 'codex-config.toml', 'smoke-test.json']);
const keep = src => !SKIP.has(path.basename(src)) && path.basename(src) !== '.DS_Store';
for (const entry of await readdir(root)) if (keep(entry)) await cp(path.join(root, entry), path.join(out, 'source', entry), {recursive: true, filter: keep});

const zip = path.join(root, 'dist', `${name}.zip`);
await rm(zip, {force: true});
const files = [];
const walk = async d => { for (const e of await readdir(d, {withFileTypes: true})) e.isDirectory() ? await walk(path.join(d, e.name)) : files.push(path.join(d, e.name)); };
await walk(out);
await writeFile(zip, await makeZip(files, path.join(root, 'dist')));
console.log(`已生成 ${out}（${files.length} 个文件）`);
console.log(`已生成 ${zip}`);
console.log(JSON.stringify(checksums, null, 2));

// Minimal zip writer with the UTF-8 filename flag, so Chinese names (README-中文.md) display correctly in Windows
// Explorer. macOS `zip` omits the flag; this avoids depending on any system archiver.
async function makeZip(paths, base) {
  const crcTable = new Uint32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc32 = buf => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const local = [], central = []; let offset = 0;
  const now = new Date(), dosTime = (now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1), dosDate = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate();
  for (const file of paths.sort()) {
    const name = Buffer.from(path.relative(base, file).split(path.sep).join('/'), 'utf8');
    const data = await readFile(file), packed = deflateRawSync(data, {level: 9}), crc = crc32(data);
    const exec = /\.(command|sh)$|gradlew$/.test(file);
    const h = Buffer.alloc(30); h.writeUInt32LE(0x04034b50, 0); h.writeUInt16LE(20, 4); h.writeUInt16LE(0x0800, 6); h.writeUInt16LE(8, 8);
    h.writeUInt16LE(dosTime, 10); h.writeUInt16LE(dosDate, 12); h.writeUInt32LE(crc, 14); h.writeUInt32LE(packed.length, 18); h.writeUInt32LE(data.length, 22); h.writeUInt16LE(name.length, 26);
    const c = Buffer.alloc(46); c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(0x0314, 4); c.writeUInt16LE(20, 6); c.writeUInt16LE(0x0800, 8); c.writeUInt16LE(8, 10);
    c.writeUInt16LE(dosTime, 12); c.writeUInt16LE(dosDate, 14); c.writeUInt32LE(crc, 16); c.writeUInt32LE(packed.length, 20); c.writeUInt32LE(data.length, 24); c.writeUInt16LE(name.length, 28);
    c.writeUInt32LE(((exec ? 0o100755 : 0o100644) << 16) >>> 0, 38); c.writeUInt32LE(offset, 42);
    local.push(h, name, packed); central.push(c, name); offset += 30 + name.length + packed.length;
  }
  const cd = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(paths.length, 8); end.writeUInt16LE(paths.length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, cd, end]);
}
