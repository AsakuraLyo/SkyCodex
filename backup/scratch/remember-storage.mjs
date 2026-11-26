// Remember the storage chests found on 2026-11-24 while looking for nethersteel materials.
import {memoryTool} from 'file:///E:/MC/SkyCodex/bridge/memory.mjs';
import {r} from './safe.mjs';
const items = [
  ['storage', '露台储物·下界合金碎片箱', 58, 74, 449, '双箱 (58,74,449~450)：下界合金碎片×48、钍×44、粗铜×29、绿帘石(veridium)×155、玻璃×85 等杂物'],
  ['storage', '露台储物·铜锭/煤/大齿轮箱', 58, 74, 451, '双箱 (58,74,451~452)：铜锭×62、煤×21、大齿轮×17、齿轮×2、金锭×7、下界合金碎片×1、红石×22、面粉×43'],
  ['storage', '露台储物·创造烈焰蛋糕/铁锭箱', 55, 71, 454, '创造烈焰蛋糕×10、铁锭×40、传送带连接器×16、航空币×20、圆石台阶×132、黑石台阶×126、泥土×192'],
  ['storage', '露台储物·煤/烈焰粉箱', 56, 70, 454, '煤×20、烈焰粉×4、铁板×2、末影珍珠×8、末影之眼×5、钻石×2、深板岩圆石×98'],
  ['storage', '工厂杂物箱', 67, 69, 472, '铁粒×36、烈焰珍珠×2、毒马铃薯×43、蓝图×1 等杂物'],
  ['storage', '库存链接网络（仓库管理员猫）', 73, 69, 479, '右键座位上的猫打开总览：泥土1000+、地狱岩416、原木、金锭15 等杂物；没有铁锭/煤/机械动力材料（2026-11-24）'],
];
for (const [category, name, x, y, z, note] of items) {
  const d = await memoryTool({action: 'remember', category, name, x, y, z, note}, r, 'E:/MC/SkyCodex');
  console.log('✔', d.saved.name);
}
