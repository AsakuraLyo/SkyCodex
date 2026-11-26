# 重装系统后怎么恢复

## 一般情况：只重装了 C 盘

游戏（`E:\MC\.minecraft`）、源码（`E:\MC\dev\...`）、JDK（`E:\MC\graalvm-jdk-21.0.10+8.1`）、Node（`E:\MC\tools\node-v22.23.5-win-x64`）都在 E 盘，重装后还在。

要做的事情：

1. 装回 Claude 桌面版，登录。
2. 装 Git（`winget install Git.Git`），需要的话再装 GitHub CLI（`winget install GitHub.cli`，然后 `gh auth login`）。
3. 恢复 Claude 的记忆笔记：把本仓库 `backup\claude-memory\` 里的文件复制到
   `C:\Users\<你的用户名>\.claude\projects\E--MC\memory\`（用户名不同的话，文件夹名 `E--MC` 不变）。
4. 打开 PCL，用微软账号重新登录，启动「天空开拓者 SkyExplorer 0.2demo」。Java 运行时 PCL 会自动下载。
5. 在 Claude 里把工作目录设为 `E:\MC`，告诉它「继续 SkyCodex 项目」，它会读 `HANDOFF.md` 和记忆笔记接上。

## E 盘也格式化了：最快的办法

1. 装 PCL，用整合包「天空开拓者 SkyExplorer 0.2」重新装好游戏，进一次服务器确认能玩。
2. 打开 https://github.com/AsakuraLyo/SkyCodex/releases ，下载 `SkyCodex-0.1.0-beta.2.zip` 解压。
   - `mods\` 里两个 jar 复制进整合包的 `mods` 文件夹；
   - 运行里面的 `01-install.cmd` 装好桥接（需要先装 Node 22），`02-check.cmd` 检查。
3. 恢复 Claude 记忆（见上面第 3 步），在 Claude 里说「继续 SkyCodex 项目」。
4. 想继续改模组时再克隆源码（下一节）。

## E 盘也没了：从 GitHub 重建源码

```powershell
git clone https://github.com/<你的账号>/SkyCodex.git E:\MC\dev\SkyCodex-0.1.0-beta.2\source
```

- 源码在仓库根目录；按 `HANDOFF.md` / `Windows开发指南.md` 装 JDK 21 和 Node 22，再 `gradlew.bat build` 生成模组 jar。
- `backup\scratch\` → 复制回 `E:\MC\dev\scratch\`（调试和飞行脚本）。
- `backup\server-memory\` → 复制回 `E:\MC\SkyCodex\memory\`（服务器的产线、石碑、基地记录）。
- 整合包本身要从 PCL 重新安装；模组 jar 放进整合包的 `mods` 文件夹。
- 连接令牌 `skycodex-connection.json` 不在仓库里（故意的），装好模组首次进游戏会自动生成新的。

## 以后更新备份

```powershell
cd E:\MC\dev\SkyCodex-0.1.0-beta.2\source
powershell -ExecutionPolicy Bypass -File backup\sync.ps1
git add -A; git commit -m "Update backup"; git push
```
