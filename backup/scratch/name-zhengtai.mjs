// Give the 正太岛 lines readable names (from the 2026-11-24 survey).
import {memoryTool} from 'file:///E:/MC/SkyCodex/bridge/memory.mjs';
import {r} from './safe.mjs';
const names = [
  ['295525e9', 'production_line', '正太岛·综合加工线（西）', '辊压机×8、粉碎轮×6、搅拌器×2、注液器×2、烈焰人燃烧室×2、轧机；44 物品保险库 + 18 流体储罐；水车/风车/猫猫引擎供能；铁圆石生成器。部分在转（压机 256rpm）。盆里有铁锭、甜菜种子'],
  ['93c712f7', 'production_line', '正太岛·粉碎轮产线（南，黏土）', '4 对粉碎轮 + 93 物品保险库，256rpm 在转；置物台上有黏土球'],
  ['9f8eb3eb', 'production_line', '正太岛·圆石粉碎/搅拌线（西南）', '铁圆石生成器 → 粉碎轮 → 搅拌器，产煤渣粉（cinder_flour），20 物品保险库，在转'],
  ['39dbea15', 'power', '正太岛·猫猫引擎动力站', '14 猫猫引擎 + 泵 + 交流发电机，96rpm 在转'],
  ['5d906d9e', 'production_line', '正太岛·风扇加工（营火/灵魂营火）', '4 鼓风机对着营火和灵魂营火（烟熏/洗涤类批量加工），猫猫引擎供能，在转'],
  ['ebd920f2', 'production_line', '正太岛·风扇组（西，停转）', '2 鼓风机 + 链传动，扫描时没在转：无动力或过载'],
  ['b1e6fdcb', 'storage', '正太岛·保险库仓储 + 弹射器', '24 物品保险库、可移动存储接口、置物弹射器，在转；溜槽里有胡萝卜'],
  ['f0047351', 'waystone', '正太岛·传送石碑 + 箱子', '8 箱子 + 传送石碑（2434,758,-1669 附近）'],
  ['73a656bb', 'kitchen', '正太岛·主屋（厨房/仓库）', '149 木桶、炉灶、烹饪锅、砧板、苹果榨汁机、酿造台、高炉、烟熏炉；含传送石碑（到达点附近）'],
  ['9204fa5e', 'storage', '正太岛·二楼仓库（木桶 + 蜂箱）', '47 木桶、12 箱子、2 蜂箱'],
  ['3406ea46', 'storage', '正太岛·顶楼储物', '4 木桶 + 4 箱子'],
  ['bb52ae0d', 'portal', '正太岛·下界传送门', '(2427,758,-1651)'],
];
for (const [id, category, name, note] of names) {
  try { const d = await memoryTool({action: 'remember', id, category, name, note}, r, 'E:/MC/SkyCodex'); console.log('✔', d.saved.name); }
  catch (e) { console.log('✘', id, e.message); }
}
