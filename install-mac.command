#!/bin/bash
# SkyCodex 安装（macOS）。若双击被拦截：右键 → 打开，或在终端运行 bash install-mac.command
cd "$(dirname "$0")" || exit 1
if ! command -v node >/dev/null 2>&1; then
  echo "请先安装 Node.js 22 或更新版本：brew install node 或从 https://nodejs.org/ 下载。"
  read -r -p "按回车关闭" _; exit 1
fi
node bridge/setup.mjs
read -r -p "按回车关闭" _
