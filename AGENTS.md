# AGENTS.md — Mika Android 9 兼容 Fork

给在本仓库里干活的 AI/协作者。**读完再动手。**

## Boundaries

- ✅ **Always**：改完构建 + 真机验证；每处兼容改动写中文注释说明原因（API 从哪版才有、低版本走什么替代）
- ⚠️ **Ask first**：rebase 上游（`android9-compat-v2` 已同步上游，`android9-compat` 仍停在旧版当退路）、调 `minSdk`/`compileSdk`、动 `.gitattributes`
- 🚫 **Never**：`git add -A`（`.toolchain/` 1.1G 会被卷进去）、改 `core.autocrlf`、提交构建产物

## 详细流程

构建命令、装机步骤（vivo 会拦 `adb install`，必须走 UI）、真机验收流程、踩过的坑、已知永久限制 —— **全在技能里，不要在这里重写**：

> **`.hermes/skills/android9-backport/SKILL.md`**

