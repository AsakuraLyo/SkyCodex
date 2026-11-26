# SkyCodex 交接文档（2026-11-24，Windows）

给接手的 Claude：先读本文件，再读 `VALIDATION.md`、`AGENTS.md`、`README-中文.md`、`Windows开发指南.md`。全程用中文和用户沟通。

## 1. 项目是什么

SkyCodex：Minecraft 1.21.1 NeoForge 客户端模组 + Node MCP 桥接。Codex（或任何 MCP 客户端）通过它操作用户自己的角色，像真人一样走路、采集、合成、建造等。目标整合包是“天空开拓者 SkyExplorer 0.2demo”（NeoForge 21.1.249，258 个模组，Create 6.0.10，FTB Quests，Waystones 等）。模组只装在客户端，服务器不用装。

## 2. 这台电脑上的路径

| 用途 | 路径 |
|---|---|
| **开发源码（以此为准）** | `E:\MC\dev\SkyCodex-0.1.0-beta.2\source` |
| JDK 21（GraalVM，未加入 PATH） | `E:\MC\graalvm-jdk-21.0.10+8.1` |
| Node 22（免安装，未加入 PATH） | `E:\MC\tools\node-v22.23.5-win-x64` |
| 已安装的 SkyCodex 安装包（Codex 配置指向这里，不要移动） | `E:\MC\SkyCodex` |
| 用户的游戏实例（PCL 版本隔离） | `E:\MC\.minecraft\versions\天空开拓者 SkyExplorer 0.2demo` |
| Codex 配置（已添加 `[mcp_servers.skycodex]`，有备份） | `C:\Users\20480\.codex\config.toml` |
| 服务器记忆 | `E:\MC\SkyCodex\memory\default.json` |
| 服务器调查结果和截图 | `E:\MC\SkyCodex\surveys\` |
| 临时脚本和日志 | `E:\MC\dev\scratch\` |
| 整合包开发测试副本（约 1.3 GB，可由脚本重建） | `source\run-modpack\` |

每个 PowerShell 命令前先设置环境变量（PowerShell 5.1，不支持 `&&`）：

```powershell
$env:JAVA_HOME='E:\MC\graalvm-jdk-21.0.10+8.1'; $env:Path="$env:JAVA_HOME\bin;E:\MC\tools\node-v22.23.5-win-x64;$env:Path"
```

用户的服务器：`p39.ytonidc.com:44800`（正版验证，开发版离线账号进不去，只能用用户自己的 PCL 客户端）。

## 3. 当前状态

- 源码能完整构建：`gradlew.bat clean build` 通过，`node --test bridge/test.mjs` 7/7 通过。
- **用户实例里的 jar 与源码一致**（18:09 替换，SHA-256 已核对），包含 `status.world`（服务器地址或单机存档名）。下次进服务器第一次调用 `mc_memory` 时，记忆会自动从 `default.json` 迁移到 `p39.ytonidc.com_44800.json`（见 `bridge/memory.mjs`）。以后更新 jar 都要等用户关游戏：
  ```powershell
  cd E:\MC\dev\SkyCodex-0.1.0-beta.2\source; .\gradlew.bat build --no-daemon -q
  Copy-Item build\libs\skycodex-neoforge-1.21.1-0.1.0-beta.2.jar "E:\MC\.minecraft\versions\天空开拓者 SkyExplorer 0.2demo\mods\" -Force
  ```
- 桥接层（`bridge/*.mjs`）已经同步到 `E:\MC\SkyCodex\bridge` 和 `E:\MC\SkyCodex\source\bridge`。**改了桥接文件后，用户那边的 Codex 要重启才能用上。**
- 用户的游戏选项已设为 `pauseOnLostFocus:false`。失去焦点会弹出暂停菜单，任何界面打开都会让 SkyCodex 交还控制。
- 已在服务器上做过只读调查和一次基地巡视（6 个站点）。记忆里有 25 条：产线、动力、电梯、地铁（施工中）、储物、厨房、蓝图桌、传送石碑等，用户已认可这些名称。已知 (111,67~72,409) 的链式传送机停转。

## 4. 工具一览（33 个 MCP 工具，含新增的 `mc_waystone`，定义在 `bridge/tools.mjs`）

状态/画面/物品/配方/扫描，`mc_move`、`mc_collect`、`mc_craft`、`mc_eat`、`mc_place`、`mc_break`、`mc_use_block`、菜单点击，蓝图检查/施工（含 `skip_unsupported`）、`mc_wait`、`mc_stop`。今天新增：
- 觅食：`mc_harvest`（先尝试右键采摘，失败再破坏+补种）、`mc_smelt`、`mc_hunt`（不打有名字/被拴/已驯服/幼年的动物）；长任务在饥饿 ≤14 时自动进食。
- `mc_deposit` / `mc_withdraw`（精确数量）。
- FTB 任务：`mc_quests`、`mc_quest_submit`、`mc_quest_claim`（反射调用，数据包经 Architectury 发送）。
- `mc_machines`：产线扫描，`for_item` 按产品查加工机器。
- `mc_cannon`：机械动力蓝图大炮的状态和启动/暂停/停止（Catnip 数据包，需要打开大炮菜单）；`bridge/nbt.mjs --materials` 输出大炮材料清单。
- `mc_sequence`：在客户端内批量执行 1–20 步，每步核验，失败即停。
- `mc_memory`：按服务器的持久记忆（recall/remember/remember_lines/forget）。只在桥接侧实现，不需要更新 jar。

实现文件：`Controller.java`（核心、任务调度、自动进食、序列、兜底放置）、`Blueprint.java`、`CraftTask.java`、`HarvestTask.java`、`HuntTask.java`、`SmeltTask.java`、`ContainerTask.java`、`CannonTask.java`、`QuestBridge.java`、`MachineScanner.java`、`JobTask.java`、`LocalServer.java`、`DevHarness.java`；桥接 `bridge/tools.mjs`、`memory.mjs`、`nbt.mjs`、`design.mjs`、`render.mjs`、`server.mjs`、`setup.mjs`。

## 5. 测试方法

- 纯净开发版：`gradlew.bat runClient`（后台），然后运行 `node scripts/smoke-test.mjs`（13 项）。
- 整合包开发版：`powershell -File scripts\modpack-run.ps1 "<实例目录>"` 复制整合包到 `run-modpack`，再用 `E:\MC\dev\scratch\modpack-launch-loop.ps1` 启动（会等到进入世界）。启动后可运行：
  - `node scripts/smoke-test.mjs run-modpack --keep-enabled`（12 项）；
  - `node scripts/features-test.mjs run-modpack [--only=步骤名片段]`（11 项，需要环境变量 `SKYCODEX_TEST_SCHEMATIC` 指向一个小 .nbt）；
  - `node scripts/blueprint-trial.mjs run-modpack <蓝图.json> 0-3`。
- 副本里为了开发环境能启动做过的处理：移走 Accelerated Rendering 和 Jade；去掉 `skyexplorer-compat` 的 `skyexplorer-scribes-shader` mixin。正式启动器不需要这些。
- 用户要求：**已经通过的测试不要重复跑**，修好失败项后用 `--only=` 只重跑失败的。
- 服务器上只读调查：`node E:\MC\SkyCodex\source\scripts\server-survey.mjs "<实例目录>" 96`。巡视脚本示例见 `E:\MC\dev\scratch\tour.mjs`。

## 6. 踩过的坑（务必注意）

1. 不要用 `Minecraft.execute()` 处理请求：Baritone 首次计算掉落物时会死锁。请求放在 `Controller.pending` 队列里，在 ClientTickEvent 中执行。
2. FTB Quests 的数据包必须经 `dev.architectury.networking.NetworkManager.sendToServer` 发送；直接用 NeoForge `PacketDistributor` 会编码失败并掉线。Create 的数据包经 `CatnipServices.NETWORK`（`NetworkHelper` 接口）发送。
3. Baritone：
   - 只会在当前位置尝试朝向，放不了时会报 “Missing materials” 并暂停；角色站在目标格上会死循环。所以有兜底放置（`startManualPlace`）、30 秒卡住检测和 `skipFailedLayers`。
   - 施工时脚手架只用泥土/地狱岩，并且只允许破坏这两种（`allowBreakAnyway`）。
   - 行走时 Baritone 强制设定的按键会打断进食，进食前要 `clearAllKeys`。
4. 进食：吃完一个的那个 tick 原版会马上开始吃下一个，要在 `ClientTickEvent.Pre` 里松开使用键。
5. 模组自定义的连接属性（Create 梁的 top/bottom/x/z 等）：所有布尔属性都视为派生属性，只有 `hanging` 例外。
6. PowerShell 5.1：
   - 脚本里写中文要存成带 BOM 的 UTF-8，否则按 ANSI 解析；
   - 游戏日志是 GBK 编码；
   - `Select-String` 要加 `-Encoding utf8`；
   - 通过管道给 Node 的 readline 喂输入会提前结束，用 `E:\MC\dev\scratch\run-setup.mjs` 这种包装脚本。
7. 天空开拓者是高空世界，出生点在 Y≈605，测试时离开平台就是虚空。
8. 可重复的 FTB 任务领奖后会显示“未领取”，要以背包里的物品为准（工具会标 `repeatable`）。
9. 用户的服务器有敌对生物：巡视时只把高度差 ≤3、距离 ≤8 格的当作危险。

## 7. 下一步（用户已同意或提出过）

1. **传送石碑工具 `mc_waystone`：代码已完成，未实测，jar 未安装**（2026-11-24 第二个会话）。
   - `WaystoneTask.java`（list / teleport），已接入 `Controller`（`JOB_KINDS`、`GUI_KINDS`、`startJob`），以及 `bridge/tools.mjs`（也可用在 `mc_sequence` 里）。传送任务进行中允许维度变化，不会因此关闭接管。
   - 接口已用 javap 核对：`SelectWaystoneMessage(UUID)`，发送方式 `Balm.getNetworking().sendToServer`，与游戏里传送按钮的字节码一致。反射只调用公开接口上的方法，因为 API 的实现类不是 public。
   - `gradlew build` 通过，`node --test bridge/test.mjs` 7/7 通过（工具数改为 33）。
   - 没有实测的原因：用户的游戏开着，空闲内存不够再开一个 10 GB 的开发客户端，用户选择晚点再测。
   - 剩下的工作：
     1. 等用户关游戏后，按第 5 节启动整合包开发版，运行 `node scripts/features-test.mjs run-modpack --only=传送石碑`；
     2. 通过后把 jar 装进用户实例；
     3. 把 `bridge/*.mjs` 和 `AGENTS.md` 同步到 `E:\MC\SkyCodex`（为了不出现“工具有了、jar 不支持”，故意还没同步）；
     4. 提醒用户重启 Codex。
   - 跨维度传送仍需在服务器上验证。基地传送石碑在 (47,69,462)，记忆里已有 10 个目的地（category `waystone_destination`）。
   - 注意：用户的服务器上有玩家 PvP，角色在 (110,74,496) 附近被玩家打死过一次。`E:\MC\dev\scratch\safe.mjs` 会在角色受伤时立即停止任务。
2. 参考 Numen/DeepClef 的改进（已给用户列过，“批量步骤”已完成）：
   - **安全反射层**：防溺水、低血量或附近有敌对生物时暂停、避开岩浆；
   - 只返回可见方块的扫描；
   - `action_id` 防止重复提交；
   - 新增一个“取消当前任务但保持接管”的操作，现在 `mc_stop` 会关闭接管。
3. 在服务器上做 README 里的基础验收，并补充到 VALIDATION.md。
4. 海上庇护所蓝图（`blueprints/sea-shelter.json`）：Create Deco 方块用户决定**留空**。可以用蓝图大炮打印（材料清单：`node bridge/nbt.mjs <nbt> --materials`）。

## 8. 给下一个 Claude 的开场提示词

> 这是 SkyCodex 项目（MC 1.21.1 NeoForge 客户端模组 + Node MCP 桥，让 Codex 操作我在天空开拓者服务器上的角色）。开发源码在 `E:\MC\dev\SkyCodex-0.1.0-beta.2\source`。请先读 `HANDOFF.md`、`VALIDATION.md`、`AGENTS.md`，按交接文档设置 JAVA_HOME 和 Node 路径，确认 `gradlew.bat build` 和 `node --test bridge/test.mjs` 都通过。然后从交接文档“下一步”的第 1 项（传送石碑工具）开始。已经测过的不要重复测。全程用中文。
