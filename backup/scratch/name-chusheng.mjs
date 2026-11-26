// Give the 出生岛 lines readable names (from the 2026-11-24 survey).
import {memoryTool} from 'file:///E:/MC/SkyCodex/bridge/memory.mjs';
import {r} from './safe.mjs';
const names = [
  ['ac23bd28', 'production_line', '出生岛·综合加工线（东南）', '机械手×6、辊压机×3、搅拌器、注液器、锯、鼓风机、物品排液器、烈焰人燃烧室×2、轧机；7 猫猫引擎 + 2 交流发电机供能，转速控制器 256rpm，在转；16 木桶 + 3 流体储罐'],
  ['20480b75', 'power', '出生岛·储能站（蓄电池 + 特斯拉线圈）', '8 模块化蓄电池、4 大型接线器、2 特斯拉线圈（充电）、2 置物台'],
  ['3ffe5e43', 'production_line', '出生岛·新生魔艺附魔台', '附魔装置 + 奥术核心 + 11 奥术基座、4 灌注室、2 魔源罐'],
  ['9694b93f', 'production_line', '出生岛·熔炉排', '6 熔炉 + 2 高炉 + 6 木桶 + 2 樱桃木箱'],
  ['ecb66e65', 'kitchen', '出生岛·厨房（东）', '炉灶×2、烹饪锅、砧板、营火×2、铁圆石生成器、农艺魔源连接器；5 木桶 + 6 箱子'],
  ['035c99a8', 'kitchen', '出生岛·厨房（苹果榨汁机/烤箱，停转）', '2 苹果榨汁机、烤箱、炖锅、炉灶、烟熏炉；连着的传动轴扫描时没在转'],
  ['7bf314d8', 'production_line', '出生岛·压榨桶（酒馆）', '2 压榨桶 + 农艺魔源连接器 + 樱桃木箱'],
  ['9c4821c0', 'farm', '出生岛·作物增幅器', '2 作物增幅器 + 接线器（农田增产）'],
  ['d484ff5e', 'production_line', '出生岛·枪械工作台', '2 个 TaCZ 枪械工作台 + 2 木桶'],
  ['68049ed7', 'place', '出生岛·神龛/女神像', '车万女仆神龛 + 2 女神像 + 箱子'],
  ['2e5bd5cc', 'storage', '出生岛·箱子（东南角）', '4 橡木箱'],
  ['5bbc0338', 'portal', '出生岛·下界传送门', '(19,607,8)'],
];
for (const [id, category, name, note] of names) {
  try { const d = await memoryTool({action: 'remember', id, category, name, note}, r, 'E:/MC/SkyCodex'); console.log('✔', d.saved.name); }
  catch (e) { console.log('✘', id, e.message); }
}
