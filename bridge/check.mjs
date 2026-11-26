import {readFile} from 'node:fs/promises';
import {createClient} from './tools.mjs';
try{
 const config=JSON.parse(await readFile(new URL('../game-dir.json',import.meta.url),'utf8'));
 const status=await createClient(config.gameDir)('status');
 console.log(JSON.stringify(status,null,2));
 console.log(status.in_world?'已连接游戏。'+(status.enabled?'接管已启用。':'在游戏里按 F8 启用。'):'连接已建立，请进入世界或服务器。');
}catch(e){console.error(e.message);process.exitCode=1;}
