# SkyCodex 开发进度与验证记录

## 当前版本：0.1.0-beta.2（2026-11-24）

### 新功能整合包实测（天空开拓者开发版客户端，2026-11-24）

用 `scripts/features-test.mjs run-modpack` 测试，失败项修复后用 `--only=` 只重跑失败的步骤，已通过的不再重复测。

| 功能 | 结果 |
|---|---|
| `mc_harvest` 收获 | ✔ 8 株：6 株右键采摘（整合包支持，植株保留），2 株破坏后补种；未成熟的小麦没碰 |
| `mc_smelt` 熔炉 | ✔ 3 生牛肉 → 3 熟牛肉，自动加燃料，结束后关闭界面 |
| `mc_hunt` 打猎 | ✔ 打了 2 头牛并捡起掉落物；有名字的牛没碰，再次打猎会直接拒绝 |
| 长任务自动进食 | ✔ 行走途中饥饿 12 → 17，吃掉 1 个面包后继续走到终点 |
| `mc_quests` 任务书 | ✔ 62 章、3072 个任务、2157 个可做 |
| `mc_quest_submit` / `mc_quest_claim` | ✔ 物品任务提交后完成，领奖后背包 +5 航空币（`kubejs:aerocoin`）。该任务可重复，领奖后显示“未领取”属正常，工具现在会标注 `repeatable` |
| `mc_machines` 产线 | ✔ 找到辊压机所在的产线；查询铁板时列出 create:pressing → 附近的辊压机 |
| `mc_deposit` / `mc_withdraw` | ✔ 存 40、取 15，数量精确，结束后关闭箱子 |
| 蓝图大炮 `mc_cannon` + 补料 | ✔ 存材料进箱子、把蓝图和火药放进大炮、启动后打印 45/45 块，与蓝图逐块比对一致 |

| `mc_sequence` 批量步骤 | ✔ 一次调用连续完成“做木板→做工作台→放下→挖回”4 步，用时 20 秒，每步都核验；第 2 步核验失败时停在 2/3，第 3 步没有执行 |

**已安装到用户实例**（2026-11-24）：`E:\MC\.minecraft\versions\天空开拓者 SkyExplorer 0.2demo\mods` 中放入 SkyCodex（含以上全部功能）和 Baritone 1.11.3，并已核对 SHA-256。安装包固定在 `E:\MC\SkyCodex`，Codex 配置 `~/.codex/config.toml` 已添加 `skycodex` 一节，原文件已备份。尚未用 PCL 正式启动验证。

测试中修复的问题：
- FTB 任务数据包必须经 Architectury 发送，直接走 NeoForge 发送会导致掉线；
- 浆果丛掉落物：改为站到旁边拾取，不再走进浆果丛；
- 自动进食：Baritone 行走时强制的按键状态会打断“按住使用键吃东西”，现在进食前清除这些按键。

### 已验证（macOS，2026-11-24）

| 项目 | 结果 |
|---|---|
| `gradle clean build`（JDK 21.0.13，Gradle 9.2.1，NeoForge 21.1.249） | 通过，模组 JAR 已生成 |
| JUnit：`IngredientAllocatorTest`（4 项） | 通过 |
| `node --test bridge/test.mjs`（5 组：参数校验、蓝图路径安全、本地鉴权与令牌轮换、MCP stdio 初始化与 20 个工具、设计编译器与 PNG） | 全部通过 |
| `bridge/design.mjs` 编译示例 `cottage.design.json`（338 块）与 `preview.mjs` 生成五视图 PNG | 通过，已人工检查图像 |

### 已验证（Windows 11，2026-11-24）

环境：Oracle GraalVM JDK 21.0.10、Node 22.23.5（免安装版）、Gradle 9.2.1、NeoForge 21.1.249，开发版客户端（纯净 NeoForge + Baritone，**不含**天空开拓者的其他模组）。

| 项目 | 结果 |
|---|---|
| `gradlew.bat clean build` + JUnit | 通过 |
| `node --test bridge/test.mjs` | 5/5 通过。Windows 普通用户不能建文件符号链接，“符号链接逃逸”那一条断言自动跳过，其余照常检查 |
| `gradlew.bat runClient` 加载 Baritone（`baritoe`） | 通过，DevHarness 自动进入 `SkyCodexSmoke` 世界并启用接管 |
| `node scripts/smoke-test.mjs`（13 项） | **13/13 通过，连续两次**（每次重启客户端） |
| `node scripts/package.mjs` | 通过，生成 `dist\SkyCodex-0.1.0-beta.2.zip` |

冒烟测试摘要（第二次运行）：状态字段、截图缩放（640px）、配方查询、2×2 合成（木板、工作台、木棍）、`mc_place` 放工作台、3×3 合成木镐且工作台自动关闭、`mc_break` 挖回工作台并确认进背包、`mc_collect` 采集橡木+白桦原木（+5）、`mc_move` 到达目标且无路时 17 秒报失败、饥饿时拒绝任务 → `mc_eat` 只吃 1 个面包（不吃腐肉，饥饿值 5→10）、蓝图 15 块全部验收通过（楼梯朝西、栅栏/玻璃板、圆石没有被当脚手架用掉）、`mc_stop`。

### 冒烟测试中发现并修复的问题（Windows，2026-11-24）

1. **请求导致游戏卡死**（严重）：第一次 `mc_collect` 时整个游戏“未响应”。原因：`LocalServer` 用 `Minecraft.execute()` 把请求投递到主线程，执行时事件循环处于重入状态；Baritone 第一次计算方块掉落物（`BlockOptionalMeta` → `ServerLevelStub.registryAccess().join()`）时，需要的加载任务只能排队等主线程，于是死锁。现在请求放进 `Controller.pending` 队列，在 `ClientTickEvent` 里执行，和 Baritone 聊天命令处于同一上下文。
2. **合成失败后工作台不关闭**：原来只在成功时关闭模组自己打开的工作台，失败后所有任务都报“Close the current GUI first”。现在 `finish()` 在任何结束状态下都会关闭。
3. **`mc_break` 没有捡到掉落物却报成功**：原来走到旁边约 0.75 秒就结束。现在会走到掉落物实体上，确认背包数量增加，或者附近已经没有掉落物，才算成功；10 秒内捡不到就如实报告失败。
4. **`mc_eat` 一次吃到饱**：按住“使用”键吃东西时，吃完一个的那个 tick 里原版会立刻开始吃下一个。现在在 `ClientTickEvent.Pre`（原版处理按键之前）松开按键。
5. **带朝向的方块无法施工**：Baritone 只在当前位置尝试转向，转不到需要的朝向时报 `Missing materials ... oak_stairs[facing=west]` 并暂停。现在 Baritone 停止或暂停后，模组接手剩余方块：在附近 7×7 范围内找可站立的位置和可见的点击面，用原版 `getStateForPlacement` 模拟放置结果，确认与蓝图一致后走过去、看向该面、右键放置，再核实结果。每个方块最多尝试 4 次。
6. 测试脚本：原来只合成了 8 块木板，不够做木镐（工作台 4 + 木棍 2 + 木镐 3），改为 12 块。

### 整合包内验证（天空开拓者 0.2demo，开发版客户端，2026-11-24）

做法：`scripts/modpack-run.ps1` 把整合包实例复制到 `run-modpack`（只读原目录），`gradlew runModpack` 用离线测试账号加载全部模组，进入本地测试世界（高空世界，出生点 Y≈605）。副本里为了能在开发环境启动，做了三处改动（正式启动器不需要）：
- 移走 Accelerated Rendering（mixin 与开发环境重编译的 MC 不兼容）；
- 移走 Jade（开发环境下缺翻译时直接抛 AssertionError）；
- 去掉 `skyexplorer-compat` 里 `skyexplorer-scribes-shader` 这一条渲染 mixin。

| 项目 | 结果 |
|---|---|
| 整合包 257 个模组 + SkyCodex + Baritone 一起加载、进入世界 | 通过 |
| 冒烟测试（整合包内，`--keep-enabled`，12 项） | **12/12 通过**，修复后连续 3 次 |
| `mc_recipes` 在整合包里查到工作台配方 | 通过（2 条，含整合包配方） |
| 海上庇护所蓝图 `.nbt` → JSON（`bridge/nbt.mjs`） | 7766 格：3541 个普通方块、2964 格水、15 格岩浆、约 1260 个机器/方块实体、9 扇门、16 张床，另有 17 个强力胶实体 |
| 整份蓝图在整合包注册表里检查（`skip_unsupported`） | 3058 块可施工，483 块被跳过（见下） |
| 试建支腿 0–3 层（182 块，原位） | **180/182 验收通过**（141 秒）。没放上的 2 块：1 个横向安山岩梁、1 个要贴墙的按钮（墙不在这一段） |
| 试建 0–18 层（原位，897 块可施工） | 10 分钟一轮能建约 300–340 块，支腿逐层往上长，Baritone 自己搭泥土脚手架。按用户要求没有建完 |

**海上庇护所蓝图和当前整合包不匹配**：蓝图里 458 个 Create Deco 方块（`createdeco:industrial_iron_catwalk` / `_railing` / `_stairs`、`industrial_iron_sheet_metal`、`andesite_catwalk_stairs`）和 `brewinandchewin:heating_cask`，在天空开拓者 0.2demo 里**不存在**（没装这个模组）。屏障方块是创造模式专用，花盆植物、洞穴藤蔓等没有对应物品。这些需要换材料，或者改用装了 Create Deco 的整合包。

整合包测试中又发现并修复的问题：
7. **站在目标格上导致 Baritone 原地死循环**：Baritone 不暂停也不放弃，直到超时。现在 30 秒内已验收方块数没有增加，就转交兜底放置。
8. **模组方块的连接属性永远对不上**：例如 Create 柴油发电机安山岩梁的 `top/bottom/x/z`。现在所有布尔属性都视为派生属性，只有 `hanging` 例外；作物 `age`、`stage`，蛋糕 `bites` 也不比对。Baritone 的忽略名单按每份蓝图实际出现的属性名生成。
9. **兜底放置遇到一个放不了的块就让整个任务失败**：现在跳过该块继续，最后在 `job.detail.unplaced` 列出原因。地面够不着的高处方块交回 Baritone（它能搭脚手架），循环到没有新进展为止。
10. **一层里有一个放不了的块，Baritone 就停在这一层**：开启了 `skipFailedLayers`。
11. **Baritone 把泥土脚手架搭进还没施工的蓝图格子**：施工任务原本禁止破坏方块，它没法自己再拿走。现在施工时脚手架只用泥土/地狱岩，并且只允许破坏这两种（Baritone `allowBreakAnyway`）。本次会话里自己搭进蓝图格子的脚手架会被记录下来，续建时不算冲突。
12. DevHarness：新游戏目录第一次启动时的“旁白/无障碍”引导界面会自动跳过。
13. 新增 `bridge/nbt.mjs`（`.nbt` 结构/机械动力蓝图 → SkyCodex 蓝图）、`mc_check_blueprint` / `mc_build_blueprint` 的 `skip_unsupported` 参数（一次列出全部不支持的方块，而不是遇到第一个就报错）、`scripts/blueprint-trial.mjs`（开发版分段试建）、`gradlew runModpack`。

已知限制：施工中如果游戏打开任何界面（包括窗口失去焦点后弹出的暂停菜单），出于安全会立即交还控制并停止任务。0–18 层试建的最后一轮就是这样在 300 块时被暂停菜单打断的。长时间施工时，请保持游戏窗口在前台，或关闭“失去焦点时暂停”。

### 尚未验证

- [ ] `01-install.cmd` 在真实天空开拓者目录上的安装，以及用 PCL 正式启动（不是开发版）。
- [ ] 一次完整建完大型蓝图（按用户要求只验证了“能跑”）。
- [ ] KubeJS 改过的配方能否用 `mc_craft` 合成（冒烟测试只合成了原版配方）。
- [ ] Baritone 计算掉落物时只读原版数据包，模组方块的掉落物它可能不认识。`mc_collect` 以背包数量为准，不受影响，但 Baritone 可能不会主动去捡这些掉落物。
- [ ] 带朝向方块的手动放置会让角色转头看向点击面（与真人一致），但还没在有反作弊的正式服务器上测试。
- [ ] 在正式服务器上的表现，包括反作弊、延迟和区块加载。
- [x] **整合包开发版实测（2026-11-24 19:42，`features-test.mjs --only=指定朝向,自动反击,传送石碑`，3/3 通过）**：
  - `mc_waystone`：列出 3 个石碑，拒绝「当前石碑」和不存在的名字，按坐标传送成功（setblock 放的石碑没有名字，所以加了按 x,y,z 选择）。
  - `mc_place` + `properties`：传动轴 X/Z/Y 向在 4 个方位连续两轮 10/10 正确（`mc_scan` 读的是服务器确认后的状态）；漏斗贴在箱子北/南/东侧、朝外，3/3 正确，箱子界面没有被打开。修过的问题：只瞄面中心导致轴向临界（改为 3×3 取点 + ±3° 稳健性检查）、站位偏离中心（站位要求偏 0.25 格也能放，失败的站位拉黑）、非满格方块（箱子）射线打不到、客户端预测造成的「假成功」（点击前发送转头包，放下后等 8 tick 再核对服务器状态）。
  - 自动反击：走路途中召唤僵尸，角色换上伤害最高的钻石剑（+6）将其击杀，然后继续走到终点，接管未关闭。
  - 未验证：在正式服务器上对玩家的反击、远程攻击者、跨维度传送；蓝图施工的手动放置也用到同一套放置逻辑，这次没有重跑蓝图试建。
- [x] **寻路与放置改进（2026-11-25，整合包开发版，`--only=寻路,管子上放,指定朝向` 3/3、`mc_cancel` 1/1 通过）**：
  - 整合包里所有带碰撞箱的模组方块（5155 种）都加入 Baritone 的 `blocksToAvoid`（只禁止穿过，不影响站在上面）。
  - 卡住处理：只按水平位移判断；障碍不高于 1 格才跳；2 秒无进展就记下撞到的方块类型、退到离障碍最远的相邻空格、重新寻路（重新寻路后 2 秒内不判失败）。实测：两格高的传动轴墙，卡 1 次、脱身 1 次，6 秒绕过。
  - `mc_cancel`：取消任务但保留接管。
  - 放置：用方块真实碰撞箱判断会不会压到角色；Shift 点击改为直接发送按下/松开潜行数据包（Baritone 控制输入时按键不生效）。猫咪引擎放在管子上，南北向、东西向都一次成功。
  - 未验证：在用户服务器的工厂里实际走一遍；被录入为障碍的模组方块是否会让某些本该能走的路线（例如穿过模组门以外的可通行方块）变得不可走。
- [x] **`mc_waystone` 传送石碑（2026-11-24 新增）**。已确认的部分：
  - `javap` 核对过 Waystones 21.1.41 和 Balm 21.0.65 的接口：`SelectWaystoneMessage(UUID)`、`WaystoneSelectionMenu.getWaystones()/getWaystoneFrom()`，发送方式 `Balm.getNetworking().sendToServer(...)` 与游戏里传送按钮的字节码一致；
  - `gradlew build` 通过，`node --test bridge/test.mjs` 7/7 通过（工具数 33）。

  还没验证：整合包内实测（已写好 `features-test.mjs` 的“传送石碑”一步，用 `--only=传送石碑` 运行）；跨维度传送（去下界的石碑）后不掉接管；服务器上的 XP 消耗和冷却提示。jar 还没装进用户实例。

### 冒烟测试结果

Windows 11，2026-11-24：13/13 通过（连续两次），详见上文和 `smoke-report.json`。

### 真实游戏验收

（按 `README-中文.md` 的“第一次怎么说”操作后填写。）

---

## beta.2 改动清单（由 Claude 在 Codex 额度充值期间完成）

改动前，Codex 原版已备份在 Mac 的 `~/开发/aimc（claude）/backup/sky-codex-codex原版/`。

### 修复的问题
1. **建筑永远验收不过**：原来要求方块状态完全一致，但楼梯 `shape`、栅栏/玻璃板/墙的连接方向、树叶 `distance` 等属性由游戏根据相邻方块自动计算，蓝图无法指定。现在验收和 Baritone 都忽略这些派生属性（`Blueprint.DERIVED_PROPERTIES`），只比对方块种类和可控属性（朝向、上下半、轴向、台阶类型等）。
2. **施工消耗建材**：Baritone 默认会把圆石、泥土、石头当脚手架消耗掉。现在所有任务都会把当前蓝图的材料（或采集目标、放置物品）从脚手架列表中排除。
3. **采集可能乱挖隧道**：原来用 `legitMine`，在看不到目标时 Baritone 会自动下挖到 Y=11 并横向开矿道，可能挖穿建筑；同时又禁止搭方块和向下挖，高处的原木够不着。现在默认 `mode=exposed`（`allowOnlyExposedOres`），只挖暴露在空气中的方块，找不到就直接失败，绝不自己开矿道；允许搭泥土和向下挖。`mode=any` 才允许挖隐藏的方块。
4. **寻路失败要干等**：`mc_move` 找不到路时原来要等到超时（默认 180 秒），现在 Baritone 放弃后约 2 秒即报告失败（通常几秒内）。
5. **合成后工作台不关闭**：原来下一条任务会报“Close the current GUI first”。现在如果工作台是合成任务自己打开的，结束后会自动关闭。
6. **饥饿直接关掉接管**：原来饥饿值 ≤6 时直接解除接管，玩家必须重新按 F8，而且没有进食工具。现在只停止当前任务、拒绝启动新任务，Codex 可以调用 `mc_eat` 后继续。
7. **截图过大**：Retina/4K 原图有好几 MB。现在默认缩放到宽 1280（可通过 `max_width` 调整）。
8. **安装包装不上**：`setup.mjs` 需要的 `mods/` 和 `checksums.json` 原来没人生成。新增 `scripts/package.mjs` 负责生成。README 里提到的 `source/`、`VALIDATION.md` 现在也都有了。
9. Windows 批处理改为 CRLF 换行，避免 `.cmd` 中的中文和标签解析出错。

### 新功能
- `mc_eat`：自动挑选营养最高且无负面效果的食物（不吃腐肉、蜘蛛眼），也可以指定食物。
- `mc_place`：走到可触及范围内，在指定空位放一个方块（工作台、熔炉、箱子、火把），朝向自动选择，并核实结果。
- `mc_break`：挖掉指定位置的一个方块并走过去拾取掉落物。容器需要先清空，并显式传 `allow_container=true`。
- `mc_collect`：`block` 和 `drop_item` 支持用逗号分隔多个 ID，例如所有原木。
- `mc_status`：新增朝向、时间、是否夜晚、当前快捷栏、附近敌对生物（16 格内）。
- `mc_check_blueprint`：冲突列表最多显示 40 条（另附总数），并给出脚手架提示。
- **蓝图设计生成器** `bridge/design.mjs`：用 `fill / walls / box / frame / clear / set / replace / facade（字符画立面）/ roof（gable/hip/flat，楼梯朝向自动生成）` 描述建筑，自动展开成逐块蓝图，并输出材料清单。
- **五视图 PNG 预览** `bridge/render.mjs`：正面、右侧、背面、左侧、俯视，每 5 格一条粗网格线，便于和参考图数格子、比较比例。纯 Node 实现，没有依赖。
- macOS 安装与检查脚本：`install-mac.command`、`check-mac.command`。安装程序会自动列出常见启动器里装有 1.21.1 机械动力的游戏目录。
- 开发专用测试工具 `DevHarness`（只在 `-Dskycodex.dev=true`，即 `runClient` 时生效）和端到端冒烟测试 `scripts/smoke-test.mjs`。

---

## 路线图（建议顺序）

1. **在 Windows 上跑通冒烟测试**，修复游戏内暴露出的问题（最优先）。
2. 在天空开拓者测试存档中完成 README 的基础验收：采集 8 个原木、做工作台、吃东西、放置和挖回。
3. 熔炉辅助工具 `mc_smelt`：放入燃料和原料，等待并取出成品，免得 Codex 逐个点击槽位。
4. 箱子存取 `mc_deposit` / `mc_withdraw`：按物品 ID 批量存取。
5. 长任务中自动进食，遇到危险自动撤退（目前只能停止任务）。
6. 蓝图支持门、床、台阶双层、方块实体（箱子、灯笼等）。
7. 超过 64×64×64 或 20000 块的建筑自动拆分成多份蓝图，并按顺序施工。
8. 机械动力（Create）机器的配方查询和半自动操作（需要逐台验证槽位布局）。
