// One-off: give the remembered SkyExplorer server lines readable names (based on their machines).
import {memoryTool} from 'file:///E:/MC/SkyCodex/bridge/memory.mjs';
import {createClient} from 'file:///E:/MC/SkyCodex/bridge/tools.mjs';
const r = createClient(process.argv[2]);
const root = 'E:/MC/SkyCodex';
const names = [
  ['e78c8c46', 'production_line', '主基地综合工厂', '81 物品保险库仓储 + 51 机械合成器阵列；粉碎轮/辊轧机/搅拌/辊压/锯/注液/烈焰人燃烧室加工；交流发电机+储能+特斯拉线圈发电；打包机/青蛙港/库存链接物流。部分在转'],
  ['a5a393a5', 'power', '大水车动力站', '5 个大水车 + 鼓风机，在转'],
  ['e8de208d', 'power', '猫猫引擎动力站（东）', '2 个猫猫引擎 + 流体管 + 链式传送机，在转'],
  ['cb2c258f', 'logistics', '链式传送机/电梯站（基地北）', '链式传送机 + 电梯滑轮，在转'],
  ['9bf2b37e', 'logistics', '链式传送机/电梯站（南）', '链式传送机 + 电梯滑轮，在转'],
  ['70385b66', 'logistics', '链式传送机（北，停转）', '扫描时没在转：无动力或过载，需要检查'],
  ['4160fc86', 'railway', '列车轨道段（地下 y58）', '16 段轨道'],
  ['bb9f2da1', 'kitchen', '厨房/仓库区', '12 云杉木箱、营火、熔炉、平底锅、炉灶'],
  ['7067b049', 'kitchen', '厨房区·营火烹饪', '10 营火 + 木桶，紧邻厨房/仓库区'],
  ['737582e4', 'kitchen', '厨房区·木桶仓', '5 木桶 + 营火，紧邻厨房/仓库区'],
  ['a2250156', 'kitchen', '营火区（西）', '8 营火（烹饪或装饰）'],
  ['46556f85', 'storage', '储物点（深色橡木/丛林木箱+熔炉）', ''],
  ['93459391', 'storage', '储物点（金合欢木箱+铁背包）', '6 金合欢木箱 + 铁背包'],
  ['e23c1e11', 'storage', '储物点（橡木箱+熔炉）', '4 橡木箱 + 2 熔炉 + 铜背包'],
  ['49c11781', 'loot', '地下战利品点（y37，Lootr）', 'Lootr 战利品箱/桶 + 熔炉，可能是遗迹结构，不一定是自己的'],
];
for (const [id, category, name, note] of names) {
  try { const d = await memoryTool({action: 'remember', id, category, name, note}, r, root); console.log('✔', id, d.saved.name, JSON.stringify(d.saved.from)); }
  catch (e) { console.log('✘', id, e.message); }
}
