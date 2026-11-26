# SkyCodex：Windows 开发与测试指南

这份指南用于在 Windows 电脑上继续开发 SkyCodex，并在**真实的 Minecraft 客户端**里完成测试。开发工具可以用 Windows 版 Claude（桌面版 Code 标签页或 Claude Code），也可以用 Codex。

## 1. 准备环境（只需一次）

| 软件 | 要求 | 检查命令 |
|---|---|---|
| Java | 21（推荐 [Eclipse Temurin 21](https://adoptium.net/) MSI，安装时勾选“设置 JAVA_HOME”） | `java -version` 显示 21 |
| Node.js | 22 或更新（[nodejs.org](https://nodejs.org/) LTS） | `node --version` |
| Claude | 桌面版或 Claude Code | — |

不需要单独安装 Gradle，项目自带 Gradle Wrapper，首次构建时会自动下载。

## 2. 放置项目

1. 把 `SkyCodex-0.1.0-beta.2.zip` 拷到 Windows 上，解压到**纯英文、无空格**的路径，例如 `D:\dev\SkyCodex-0.1.0-beta.2`（Gradle 和 Minecraft 对中文路径偶尔有兼容问题）。
2. **开发目录是其中的 `source` 文件夹**，例如 `D:\dev\SkyCodex-0.1.0-beta.2\source`。用 Claude 打开这个文件夹。
3. 压缩包根目录的其余文件是给玩家用的安装包（`01-install.cmd`、`mods\` 等），可以用来做第 6 步的真实游戏验收。

## 3. 构建与单元测试

在 `source` 目录打开 PowerShell 或终端：

```bat
gradlew.bat build
node --test bridge/test.mjs
```

- 第一次 `build` 要下载 Gradle 9.2.1、NeoForge 和 Minecraft 反编译环境（约 1 GB），通常需要 5–15 分钟。之后只要几秒钟。
- 产物：`build\libs\skycodex-neoforge-1.21.1-0.1.0-beta.2.jar`。

## 4. 自动冒烟测试（开发版客户端，单人测试世界）

需要开两个终端，都在 `source` 目录下：

**终端 1**：启动开发版客户端

```bat
gradlew.bat runClient
```

- 首次启动会下载游戏资源。游戏窗口打开后，测试工具（`DevHarness`，只在 runClient 时生效）会自动创建并进入单人世界 `SkyCodexSmoke`（生存模式、简单难度、不刷怪），然后自动启用接管，聊天栏显示“SkyCodex 已启用”。
- 开发版用离线测试账号，不需要登录正版，也不会连接任何服务器。

**终端 2**：运行测试

```bat
node scripts/smoke-test.mjs
```

- 脚本会用 `/fill` 平整玩家东侧一块空地，用 `/give` 发放材料，然后依次测试：状态读取、截图缩放、配方查询、2×2 合成、放置工作台、3×3 合成（并检查工作台会自动关闭）、挖回工作台、采集原木、寻路（包括无路时快速失败）、进食（以及饥饿时拒绝启动其他任务）、蓝图施工（楼梯朝向、栅栏和玻璃板连接、圆石不会被当成脚手架），最后关闭接管。
- 测试期间不要操作游戏窗口。窗口可以失去焦点，测试世界已关闭“失去焦点时暂停”，但不要最小化。
- 结果打印在终端里，同时写入 `smoke-report.json`。失败项会附上原因，修好后重新运行即可，测试世界会被复用。
- **不要**对正式存档或服务器运行这个脚本。它只能连接开发版客户端，正式客户端里没有 `dev_command` 接口。

### 可能遇到的问题

- 启动时报缺少 `baritoe`：说明开发环境没有把 `libs\baritone-api-neoforge-1.11.3.jar` 当作模组加载。可以检查 `build.gradle` 的依赖写法，或者把这个 JAR 复制到 `run\mods\`。
- 冒烟测试一直显示“等待开发版客户端”：确认游戏已经进入世界，`run\config\skycodex-connection.json` 文件存在。
- 采集或施工超时：用 `mc_screenshot` 截图（或直接看游戏窗口）确认 Baritone 卡在哪里，再调整测试场地或设置。

## 4b. 在整合包里测试（开发版客户端 + 天空开拓者全部模组）

不动正式游戏目录，把整合包复制一份到 `run-modpack` 再用开发版客户端加载：

```bat
powershell -ExecutionPolicy Bypass -File scripts\modpack-run.ps1 "E:\MC\.minecraft\versions\天空开拓者 SkyExplorer 0.2demo"
gradlew.bat runModpack
node scripts/smoke-test.mjs run-modpack --keep-enabled
node scripts/blueprint-trial.mjs run-modpack sea-shelter.json 0-18
```

- 开发环境里的 Minecraft 是重新编译过的，个别渲染类模组的 mixin 挂不上，正式启动器不受影响。已知需要在副本里处理的有：
  - 移走 `acceleratedrendering`；
  - 移走 Jade（开发环境下缺翻译会直接抛 AssertionError）；
  - 在 `skyexplorer-compat` 的 `META-INF/neoforge.mods.toml` 里去掉 `skyexplorer-scribes-shader.mixins.json` 这一条。
  移出的 jar 放在 `run-modpack\mods-disabled`。
- 天空开拓者是高空世界，出生点在 Y≈605。`blueprint-trial.mjs` 会先清出一块带石头地板的平地，并给缓降、抗性和饱和效果，免得试建时掉下去或饿死。
- `blueprint-trial.mjs` 会先在游戏里检查整份蓝图（哪些方块整合包里没有、哪些是机器），再按指定的层段发放材料、真实施工并验收，结果写入 `blueprints\<名称>.trial-report.json`，截图存为 `blueprints\_trial-*.png`。

## 5. 打包

```bat
node scripts/package.mjs
```

会生成 `dist\SkyCodex-<版本>\` 和 `dist\SkyCodex-<版本>.zip`。安装包包含两个模组 JAR 及其 SHA-256 校验值，以及完整的 `source\`。

## 6. 真实游戏验收（天空开拓者）

1. 在启动器（HMCL / PCL 等）中安装好天空开拓者 0.2.x 客户端，先**不装 SkyCodex** 正常启动一次，确认整合包本身没问题。
2. 关闭游戏，运行安装包根目录的 `01-install.cmd`。它会列出检测到的游戏目录，选择编号即可。
3. 重启 Codex（或任何读取 `~/.codex/config.toml` 的 MCP 客户端），进入一个**测试存档**，按 F8 启用接管，然后按 `README-中文.md` 的“第一次怎么说”逐步验收。
4. 把结果记录到 `VALIDATION.md` 的“真实游戏验收”一节。

## 7. 给 Windows 上的 Claude 的开场提示词

在 Claude 中打开 `source` 文件夹后，可以直接粘贴下面这段话：

> 这是 SkyCodex 项目：一个 MC 1.21.1 NeoForge 客户端模组加一个 Node MCP 桥，让 Codex 像真人一样操作我的角色（采集、合成、按参考图建造）。请先阅读 `VALIDATION.md`（开发进度与验证记录）、`Windows开发指南.md`、`AGENTS.md` 和 `README-中文.md`。然后按指南第 3–4 步构建，启动 `gradlew.bat runClient`（放到后台运行），再运行 `node scripts/smoke-test.mjs`。逐项修复失败的测试，直到全部通过，最后把结果更新到 `VALIDATION.md`。全程用中文和我沟通。
