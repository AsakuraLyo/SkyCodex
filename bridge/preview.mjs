// Usage: node bridge/preview.mjs blueprints/NAME.json
// Writes NAME.svg (isometric volume) and NAME.preview.png (front/right/back/left/top elevations with a 5-block grid).
// Open the PNG and compare it with the user's reference image before building.
import {readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {colorOf,shade,renderPng} from './render.mjs';
const input=process.argv[2];
if(!input){console.error('Usage: node bridge/preview.mjs blueprints/NAME.json');process.exit(1);}
const blueprint=JSON.parse(await readFile(input,'utf8'));
const blocks=blueprint.blocks;
if(!Array.isArray(blocks)||!blocks.length||blocks.length>20000)throw new Error('Invalid blueprint');
for(const b of blocks)if(!['x','y','z'].every(k=>Number.isInteger(b[k])&&b[k]>=0&&b[k]<64))throw new Error('Coordinates outside 0..63');
const escape=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
const project=(x,y,z)=>[(x-z)*14,(x+z)*7-y*16];
const occupied=new Set(blocks.map(b=>`${b.x},${b.y},${b.z}`));
const points=[];const polys=[];const material=new Map();
const faces=[[[0,0,1],[1,0,1],[1,1,1],[0,1,1]],[[1,0,0],[1,0,1],[1,1,1],[1,1,0]],[[0,1,0],[1,1,0],[1,1,1],[0,1,1]]];
const neighbour=[[0,0,1],[1,0,0],[0,1,0]];
for(const b of [...blocks].sort((a,b)=>(a.x+a.z+a.y*2)-(b.x+b.z+b.y*2))){
 material.set(b.block,(material.get(b.block)??0)+1);
 for(let i=0;i<3;i++){
  const [dx,dy,dz]=neighbour[i];if(occupied.has(`${b.x+dx},${b.y+dy},${b.z+dz}`))continue; // hidden face
  const p=faces[i].map(([fx,fy,fz])=>project(b.x+fx,b.y+fy,b.z+fz));points.push(...p);
  polys.push(`<polygon points="${p.map(p=>p.join(',')).join(' ')}" fill="${shade(colorOf(b.block),[0.7,0.85,1.12][i])}" stroke="#192129" stroke-opacity=".18" stroke-width=".5"/>`);
 }
}
const minX=Math.min(...points.map(p=>p[0]))-40,minY=Math.min(...points.map(p=>p[1]))-70;
const w=Math.max(...points.map(p=>p[0]))-minX+40,h=Math.max(...points.map(p=>p[1]))-minY+60;
const title=blueprint.name??path.basename(input);
const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="${Math.max(w,640)}" height="${Math.max(h,480)}" viewBox="${minX} ${minY} ${w} ${h}"><rect x="${minX}" y="${minY}" width="${w}" height="${h}" fill="#f3f4f5"/><text x="${minX+16}" y="${minY+26}" font-family="sans-serif" font-size="16" fill="#18252c">${escape(title)} · ${blocks.length} blocks</text>${polys.join('')}<text x="${minX+16}" y="${minY+h-16}" font-family="sans-serif" font-size="10" fill="#53636e">Block volume preview; textures and special shapes are approximate.</text></svg>`;
const base=input.replace(/\.json$/i,'');
await writeFile(base+'.svg',svg);
await writeFile(base+'.preview.png',renderPng(blueprint,title));
console.log(base+'.svg');console.log(base+'.preview.png');
console.log(JSON.stringify(Object.fromEntries([...material].sort((a,b)=>b[1]-a[1])),null,2));
