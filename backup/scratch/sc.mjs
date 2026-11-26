// One SkyCodex tool call against the user's running game:  node sc.mjs <tool> ['{"json":"args"}'] [out.png]
// Uses the INSTALLED bridge (E:\MC\SkyCodex), i.e. the same tools Codex sees. Screenshots are written to a file.
import {createClient, callTool} from 'file:///E:/MC/SkyCodex/bridge/tools.mjs';
import {memoryTool} from 'file:///E:/MC/SkyCodex/bridge/memory.mjs';
import {writeFile} from 'node:fs/promises';
const GAME = 'E:\\MC\\.minecraft\\versions\\天空开拓者 SkyExplorer 0.2demo';
const r = createClient(GAME);
// Arguments as key=value (PowerShell 5.1 strips JSON quotes); numbers/true/false are converted. out=FILE for screenshots.
const [name, ...rest] = process.argv.slice(2);
const args = {}; let out;
for (const kv of rest) { const i = kv.indexOf('='); const k = kv.slice(0, i), v = kv.slice(i + 1);
  if (k === 'out') out = v; else if (k.startsWith('prop.')) { (args.properties ??= {})[k.slice(5)] = v; } else args[k] = /^-?\d+$/.test(v) ? Number(v) : v === 'true' ? true : v === 'false' ? false : v; }
try {
  if (name === 'memory') { console.log(JSON.stringify(await memoryTool(args, r, 'E:/MC/SkyCodex'), null, 1)); process.exit(0); }
  const x = await callTool(name, args, r);
  const c = x.content[0];
  if (c.type === 'image') { const f = out ?? 'E:/MC/SkyCodex/surveys/now.png'; await writeFile(f, Buffer.from(c.data, 'base64')); console.log('saved', f); }
  else console.log(c.text);
} catch (e) { console.log('ERROR', e.message); process.exitCode = 1; }

