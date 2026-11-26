#!/bin/bash
cd "$(dirname "$0")" || exit 1
node bridge/check.mjs
read -r -p "按回车关闭" _
