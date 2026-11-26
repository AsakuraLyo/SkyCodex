import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,symlink,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {spawn} from 'node:child_process';
import readline from 'node:readline';
import {fileURLToPath} from 'node:url';
import {createClient,callTool,readBlueprint,TOOLS,validate} from './tools.mjs';

test('Reject invalid coordinates, fractional counts and unknown action fields',()=>{
 const move=TOOLS.find(t=>t.name==='mc_move').inputSchema;
 assert.throws(()=>validate(move,{x:0,y:64,z:0.1}));
 assert.throws(()=>validate(move,{x:0,y:64,z:0,command:'op @p'}));
 assert.throws(()=>validate(move,{x:0,y:99999,z:0}));
 assert.doesNotThrow(()=>validate(move,{x:-3,y:64,z:1}));
 const collect=TOOLS.find(t=>t.name==='mc_collect').inputSchema;
 assert.doesNotThrow(()=>validate(collect,{block:'minecraft:oak_log,minecraft:birch_log',drop_item:'minecraft:oak_log,minecraft:birch_log',count:8,mode:'exposed'}));
 assert.throws(()=>validate(collect,{block:'minecraft:oak_log',drop_item:'minecraft:oak_log',count:8,mode:'xray'}));
 const brk=TOOLS.find(t=>t.name==='mc_break').inputSchema;
 assert.throws(()=>validate(brk,{x:0,y:64,z:0,allow_container:'yes'}));
 assert.doesNotThrow(()=>validate(brk,{x:0,y:64,z:0,allow_container:true}));
});
test('Blueprint rejects traversal, symlink escape and duplicates',async t=>{
 const root=await mkdtemp(path.join(tmpdir(),'skyc-bp-'));await mkdir(path.join(root,'blueprints'));
 try{
  const valid={blocks:[{x:0,y:0,z:0,block:'minecraft:stone'}]};
  await writeFile(path.join(root,'outside.json'),JSON.stringify(valid));
  await writeFile(path.join(root,'blueprints','good.json'),JSON.stringify(valid));
  // Windows needs admin rights or Developer Mode to create file symlinks; skip only that assertion there.
  let linked=true;
  try{await symlink(path.join(root,'outside.json'),path.join(root,'blueprints','link.json'));}
  catch(e){if(e.code!=='EPERM')throw e;linked=false;t.diagnostic('symlink not permitted on this system; symlink escape check skipped');}
  assert.equal((await readBlueprint(path.join(root,'blueprints'),'good.json')).blocks.length,1);
  await assert.rejects(readBlueprint(path.join(root,'blueprints'),'../outside.json'));
  if(linked)await assert.rejects(readBlueprint(path.join(root,'blueprints'),'link.json'));
  await writeFile(path.join(root,'blueprints','dup.json'),JSON.stringify({blocks:[...valid.blocks,...valid.blocks]}));
  await assert.rejects(readBlueprint(path.join(root,'blueprints'),'dup.json'),/Duplicate/);
 }finally{await rm(root,{recursive:true,force:true});}
});
test('Local client authentication, token rotation, image and failed job reporting',async()=>{
 const root=await mkdtemp(path.join(tmpdir(),'skyc-http-'));await mkdir(path.join(root,'config'));
 let expectedToken='a'.repeat(43),seen=[];
 const server=http.createServer((req,res)=>{
  assert.equal(req.headers.authorization,`Bearer ${expectedToken}`);seen.push(req.url);
  res.setHeader('Content-Type','application/json');
  if(req.url==='/v1/screenshot')res.end(JSON.stringify({mimeType:'image/png',data:'iVBORw=='}));
  else if(req.url==='/v1/craft')res.end(JSON.stringify({job:{state:'failed',message:'Missing crafting ingredients'}}));
  else res.end(JSON.stringify({in_world:true,job:{state:'succeeded'}}));
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const writeConnection=()=>writeFile(path.join(root,'config','skycodex-connection.json'),JSON.stringify({port:server.address().port,token:expectedToken}));
 try{
  await writeConnection();const client=createClient(root);
  assert.equal((await client('status')).in_world,true);
  expectedToken='b'.repeat(43);await writeConnection();await client('status');
  const image=await callTool('mc_screenshot',{},client);assert.equal(image.content[0].type,'image');
  const fail=await callTool('mc_craft',{recipe:'minecraft:stick',times:1},client);assert.equal(fail.isError,true);
  await assert.rejects(callTool('mc_shell',{command:'anything'},client),/Unknown tool/);
  assert.deepEqual(seen,['/v1/status','/v1/status','/v1/screenshot','/v1/craft']);
 }finally{await new Promise(r=>server.close(r));await rm(root,{recursive:true,force:true});}
});
test('MCP stdio initializes and lists tools without game installed',async()=>{
 const child=spawn(process.execPath,[fileURLToPath(new URL('./server.mjs',import.meta.url)),'--game-dir',path.join(tmpdir(),'skyc-no-game')],{stdio:['pipe','pipe','pipe']});
 const stream=readline.createInterface({input:child.stdout});const waiting=new Map();
 stream.on('line',line=>{const m=JSON.parse(line);waiting.get(m.id)?.(m);});
 const ask=(id,method,params)=>new Promise(resolve=>{waiting.set(id,resolve);child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});
 try{
  const init=await ask(1,'initialize',{protocolVersion:'2025-06-18',capabilities:{},clientInfo:{name:'test',version:'1'}});
  assert.equal(init.result.protocolVersion,'2025-06-18');
  const list=await ask(2,'tools/list',{});assert.equal(list.result.tools.length,39);assert.ok(list.result.tools.some(t=>t.name==='mc_waystone'));
  const status=await ask(3,'tools/call',{name:'mc_status',arguments:{}});assert.equal(status.result.isError,true);assert.match(status.result.content[0].text,/Start Minecraft/);
 }finally{child.stdin.end();child.kill();stream.close();}
});
test('Structure .nbt conversion keeps ordinary blocks and reports unsupported ones',async()=>{
 const {parseNbt,convertStructure}=await import('./nbt.mjs');
 const {gzipSync}=await import('node:zlib');
 // Minimal NBT writer for the fixture: plain objects -> compound, arrays of numbers -> int list, arrays of objects -> compound list.
 const parts=[];const u8=v=>parts.push(Buffer.from([v]));const i32=v=>{const b=Buffer.alloc(4);b.writeInt32BE(v);parts.push(b);};
 const str=s=>{const d=Buffer.from(s,'utf8');const b=Buffer.alloc(2);b.writeUInt16BE(d.length);parts.push(b,d);};
 const typeOf=v=>typeof v==='string'?8:typeof v==='number'?3:Array.isArray(v)?9:10;
 const payload=v=>{const t=typeOf(v);
  if(t===8)str(v);else if(t===3)i32(v);
  else if(t===9){const et=v.length?typeOf(v[0]):10;u8(et);i32(v.length);v.forEach(payload);}
  else{for(const[k,x]of Object.entries(v)){u8(typeOf(x));str(k);payload(x);}u8(0);}};
 const root={size:[3,2,1],palette:[{Name:'minecraft:stone'},{Name:'minecraft:oak_stairs',Properties:{facing:'west',half:'bottom',waterlogged:'true'}},{Name:'minecraft:water'},{Name:'minecraft:chest'},{Name:'minecraft:air'},{Name:'create:andesite_casing'}],
  blocks:[{pos:[0,0,0],state:0},{pos:[1,0,0],state:1},{pos:[2,0,0],state:2},{pos:[0,1,0],state:3,nbt:{id:'minecraft:chest'}},{pos:[1,1,0],state:4},{pos:[2,1,0],state:5}]};
 u8(10);str('');payload(root);
 const parsed=parseNbt(gzipSync(Buffer.concat(parts)));
 assert.deepEqual(parsed.size,[3,2,1]);
 const {blueprint,report}=convertStructure(parsed,{name:'t'});
 assert.deepEqual(blueprint.blocks.map(b=>b.block),['minecraft:stone','minecraft:oak_stairs','create:andesite_casing']);
 assert.equal(blueprint.blocks[1].properties.waterlogged,'false');
 assert.equal(report.air_cells,1);
 const kinds=report.issues.map(i=>i.kind);
 assert.ok(kinds.some(k=>k.startsWith('fluid'))&&kinds.some(k=>k.startsWith('block entity'))&&kinds.some(k=>k.startsWith('modded')));
});
test('mc_sequence validates every step with its own tool schema and maps tools to game operations',async()=>{
 const sent=[];const fake=async(op,args)=>{sent.push([op,args]);return op==='sequence'?{sequence:{state:'running'}}:{};};
 await callTool('mc_sequence',{steps:[
  {tool:'mc_collect',args:{block:'minecraft:oak_log',drop_item:'minecraft:oak_log',count:4},expect:{item:'minecraft:oak_log',at_least:4}},
  {tool:'mc_craft',args:{recipe:'minecraft:oak_planks',times:1}}]},fake);
 assert.equal(sent[0][0],'sequence');
 assert.deepEqual(sent[0][1].steps.map(s=>s.op),['collect','craft']);
 assert.deepEqual(sent[0][1].steps[0].expect,{item:'minecraft:oak_log',at_least:4});
 await assert.rejects(callTool('mc_sequence',{steps:[{tool:'mc_quest_submit',args:{id:'1'}}]},fake),/Invalid/);
 await assert.rejects(callTool('mc_sequence',{steps:[{tool:'mc_move',args:{x:1,y:2}}]},fake),/z is required/);
 await assert.rejects(callTool('mc_sequence',{steps:[]},fake),/array/);
 const busy=await callTool('mc_wait',{seconds:1},async()=>({job:{state:'succeeded'},sequence:{state:'running'}}));
 assert.equal(busy.isError,false);
});
test('Design compiler: shapes, roofs, facades and limits',async()=>{
 const {compileDesign}=await import('./design.mjs');
 const {renderPng}=await import('./render.mjs');
 const roof=compileDesign({ops:[{op:'roof',style:'gable',ridge:'x',from:[0,0,0],to:[3,0,4],block:'minecraft:oak_stairs',cap:'minecraft:oak_slab'}]});
 const at=(bp,x,y,z)=>bp.blocks.find(b=>b.x===x&&b.y===y&&b.z===z);
 assert.equal(at(roof.blueprint,0,0,0).properties.facing,'south');
 assert.equal(at(roof.blueprint,0,0,4).properties.facing,'north');
 assert.equal(at(roof.blueprint,2,2,2).block,'minecraft:oak_slab');
 assert.equal(roof.blueprint.blocks.length,4*5);
 const walls=compileDesign({palette:{w:'minecraft:stone'},ops:[{op:'walls',from:[0,0,0],to:[4,2,4],block:'w'},{op:'clear',from:[2,0,0],to:[2,1,0]}]});
 assert.equal(walls.blueprint.blocks.length,16*3-2);
 const fac=compileDesign({ops:[{op:'facade',plane:'xy',at:[0,0,0],key:{G:'minecraft:glass'},rows:['G.','GG']}]});
 assert.ok(at(fac.blueprint,0,1,0)&&at(fac.blueprint,1,0,0)&&!at(fac.blueprint,1,1,0));
 assert.throws(()=>compileDesign({ops:[{op:'fill',from:[60,0,0],to:[64,0,0],block:'minecraft:stone'}]}),/outside 0..63/);
 assert.throws(()=>compileDesign({ops:[{op:'facade',at:[0,0,0],key:{},rows:['Q']}]}),/no entry/);
 assert.throws(()=>compileDesign({ops:[{op:'fill',from:[0,0,0],to:[0,0,0],block:'stone'}]}),/namespaced/);
 const png=renderPng(walls.blueprint,'t');assert.deepEqual([...png.subarray(1,4)],[80,78,71]);
});
