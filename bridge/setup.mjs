import {access,copyFile,mkdir,readFile,writeFile,readdir} from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import readline from 'node:readline/promises';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const CREATE_JAR=/create.*1\.21\.1.*\.jar$/i;
// Look for modded 1.21.1 instances in the usual launcher folders (HMCL/PCL/official/Prism/CurseForge/Modrinth).
async function candidates(){
 const home=os.homedir(),appData=process.env.APPDATA??path.join(home,'AppData','Roaming');
 const mac=path.join(home,'Library','Application Support');
 const roots=[path.join(appData,'.minecraft'),path.join(home,'.minecraft'),path.join(mac,'minecraft'),path.join(home,'Desktop','.minecraft'),path.join(home,'Downloads','.minecraft'),path.join(home,'Documents','.minecraft')];
 const instanceRoots=[path.join(mac,'PrismLauncher','instances'),path.join(appData,'PrismLauncher','instances'),path.join(home,'curseforge','minecraft','Instances'),path.join(mac,'com.modrinth.theseus','profiles'),path.join(appData,'ModrinthApp','profiles')];
 const dirs=[];
 for(const r of roots){dirs.push(r);try{for(const v of await readdir(path.join(r,'versions')))dirs.push(path.join(r,'versions',v));}catch{}}
 for(const r of instanceRoots){try{for(const v of await readdir(r)){dirs.push(path.join(r,v),path.join(r,v,'minecraft'),path.join(r,v,'.minecraft'));}}catch{}}
 const found=[];
 for(const d of dirs){try{if((await readdir(path.join(d,'mods'))).some(n=>CREATE_JAR.test(n)))found.push(d);}catch{}}
 return [...new Set(found)];
}
// Accept paths pasted with quotes or dragged into a macOS terminal (backslash-escaped spaces).
const cleanPath=s=>{s=s.trim().replace(/^['"]|['"]$/g,'');return (process.platform==='win32'?s:s.replace(/\\([ ()&'\[\]])/g,'$1')).trim();};
if(Number(process.versions.node.split('.')[0])<22)throw new Error('请先安装 Node.js 22 或更高版本。');
const rl=readline.createInterface({input:process.stdin,output:process.stdout});
try{
 console.log('SkyCodex 安装：请先关闭 Minecraft。此程序只添加两个客户端模组和 Codex 连接配置。');
 console.log('请选择启动器“打开游戏文件夹”对应的目录，里面应当有 mods 文件夹。');
 const found=await candidates();
 if(found.length){console.log('找到以下装有 1.21.1 机械动力的游戏目录：');found.forEach((d,i)=>console.log(`  ${i+1}. ${d}`));}
 const answer=cleanPath(await rl.question(found.length?'输入编号，或粘贴／拖入其他游戏目录：':'游戏目录（可粘贴，Mac 可把文件夹拖进终端）：'));
 const raw=/^\d+$/.test(answer)&&found[Number(answer)-1]?found[Number(answer)-1]:answer;
 if(!path.isAbsolute(raw))throw new Error('请输入完整的绝对路径。');
 const game=path.resolve(raw),mods=path.join(game,'mods');await access(mods);
 const entries=await readdir(mods);
 if(!entries.some(n=>CREATE_JAR.test(n)))throw new Error('没有找到适用于 1.21.1 的 Create 模组，请检查是否选中了天空开拓者的实际游戏目录。');
 const manifest=JSON.parse(await readFile(path.join(root,'checksums.json'),'utf8'));
 const jars=(await readdir(path.join(root,'mods'))).filter(n=>n.endsWith('.jar'));
 for(const name of jars){
   const data=await readFile(path.join(root,'mods',name));
   if(createHash('sha256').update(data).digest('hex')!==manifest[name])throw new Error(`文件校验失败：${name}`);
 }
 // Refuse version duplication rather than guessing which existing jar to remove.
 for(const old of entries)if(/^(skycodex|baritone).*\.jar$/i.test(old)&&!jars.includes(old))throw new Error(`已有其他版本 ${old}。请先将它移出 mods 后再安装，避免重复加载。`);
 for(const name of jars){
   const target=path.join(mods,name);
   let existing;try{existing=await readFile(target);}catch(e){if(e.code!=='ENOENT')throw e;}
   if(existing&&createHash('sha256').update(existing).digest('hex')!==manifest[name])throw new Error(`已有同名但不同内容的文件 ${name}，请先移出后再安装。`);
 }
 for(const name of jars)await copyFile(path.join(root,'mods',name),path.join(mods,name));
 await writeFile(path.join(root,'game-dir.json'),JSON.stringify({gameDir:game},null,2));
 const toml=s=>JSON.stringify(s.replaceAll('\\','/'));
 const section=`\n[mcp_servers.skycodex]\ncommand = ${toml(process.execPath)}\nargs = [${toml(path.join(root,'bridge','server.mjs'))}, "--game-dir", ${toml(game)}]\ntool_timeout_sec = 60\n`;
 await writeFile(path.join(root,'codex-config.toml'),section);
 const dir=path.join(os.homedir(),'.codex'),file=path.join(dir,'config.toml');await mkdir(dir,{recursive:true});
 let config='';try{config=await readFile(file,'utf8');}catch(e){if(e.code!=='ENOENT')throw e;}
 if(/^\s*\[mcp_servers\.(?:skycodex|"skycodex"|'skycodex')(?:\]|\.)/m.test(config)){
   console.log('已有 skycodex 配置，已保留。请按本目录 codex-config.toml 核对路径。');
 }else{
   if(config)await copyFile(file,file+`.skycodex-backup-${Date.now()}`);
   await writeFile(file,config+section);console.log('已添加 Codex MCP 配置；其他配置已保留。');
 }
 console.log('安装完成。重启 Codex，启动游戏进入服务器；按 F8 启用，按 F9 停止。');
 console.log(`首次先运行 ${process.platform==='win32'?'02-check.cmd':'check-mac.command'}，再让 Codex 查询角色状态。请保持本安装包目录位置不变。`);
}catch(e){console.error('安装未完成：'+e.message);process.exitCode=1;}finally{rl.close();}
