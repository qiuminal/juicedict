# JuiceDict 版本发布说明（Release Process）

本文件是仓库内的发布流程规范（随仓库同步）；机器本地的 APK、构建日志与源码切片统一保存在项目 `build-artifacts/`（不进入仓库）。

## 本机工作区与 GitHub 授权

- 当前维护工作区：`D:\dsh\juicedict\`；所有 JuiceDict 专属源码和产物均留在此目录。
- 原历史仓库（只读参考，不在原位修改）：`E:\Codex\2026-08-30\https-github-com-koreader-koreader-https\stardict-app\`。
- 旧 GitHub 工作树（只读参考）：`E:\Codex\2026-08-30\https-github-com-koreader-koreader-https\work\gh-juicedict\`。
- 远端仓库：`https://github.com/qiuminal/juicedict`。
- 本机没有 `git` / `gh` 可执行文件时，使用 GitHub Git Data API 提交、打标签和创建 Release。
- GitHub 授权保存在 Windows 凭据管理器目标 `git:https://github.com`（另有 `gh:github.com:*` 条目）；不得把令牌写入仓库、日志或命令输出。发布前应从凭据管理器读取并调用 `GET /user` 验证当前账号为 `qiuminal`，不能只检查 `GITHUB_TOKEN` / `GH_TOKEN` 环境变量。

## 更新日志双版规范

从 v0.1.0 起，每个版本同时维护两套日志：

1. **对内详细版**：写入仓库根目录 `CHANGELOG-INTERNAL.md`，用于开发追溯、维护和问题定位。应完整记录功能、技术实现、兼容性、修复、验证结果及重要工程决策，不受对外篇幅限制。
2. **对外精简版**：用于客户端「关于 → 更新日志」和 GitHub Release 正文，只保留普通用户需要了解的功能与修复，措辞必须经用户确认；同一版本在客户端与 GitHub Release 的核心日志必须一致。

两版必须使用相同的版本号和发布日期。对内版可以比对外版更详细，但不得与对外版的事实相冲突。后续发版默认先整理对内详细版，再由用户确认对外精简版。

## 核心原则

1. 发版需用户确认：任何对外发布（新建 GitHub Release、对已发布 Release 换源或替换附件）都必须先经用户确认；测试包仅供验证，不视为发布。
2. GitHub Release 正文只写本版本日志：每个 Release 页面只放该版本（相对上一版本）的变更条目，禁止把历史版本的更新日志混入当前 Release 正文。
   - 历史全量更新日志只在客户端「关于页」展示（应用内需展示整个发展历程），由客户端按版本列表渲染。
   - 反面示例：v0.0.2 Release 正文里混入 v0.0.1 日志即属错误，应只保留 v0.0.2 条目 + 安装说明 + 必要备注。
3. 更新日志文本需用户确认：版本号（versionCode / versionName）与日志条目以用户确认为准，不得擅自新增条目或改动措辞。
4. 每个 APK 必须能对应到精确源码：出包前先用 scripts/snapshot-source.ps1 生成源码切片（outputs/source-snapshots/APK名-src）。
   - 切片排除 .git、keystore/、keystore.properties、local.properties、internal-dicts/ 等敏感或非仓库文件；APK 名与切片名一一对应，可复现任意包。

## 发布流程

1. 汇总本版本完整功能、技术实现、修复和验证清单，先更新仓库内 `CHANGELOG-INTERNAL.md`。
2. 从对内详细版提炼「仅本版本」的对外精简日志，交用户确认，并同步到客户端关于页和 GitHub Release 草稿。
3. 构建正式 release APK（签名证书须与历史发布一致，可覆盖安装），生成对应源码切片。
4. 打 tag 并推送（如 v0.0.3），创建 GitHub Release：title 与 tag 为 v版本；正文仅本版本对外日志 + 安装说明（必要时附补丁/安全备注）；附件命名 JuiceDict-v版本-release.apk。
5. 发布后校验：releases/latest 已指向新 tag；附件 SHA-256 与本地构建一致；APK 签名证书 SHA-256 与历史一致；工作树干净，远端 tag 等于推送提交。
6. 补丁换源：对已发布 Release 修复时保持版本号与签名不变，仅替换同名附件，并在正文追加简短备注。
