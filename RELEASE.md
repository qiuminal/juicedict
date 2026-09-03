# JuiceDict 版本发布说明（Release Process）

本文件是仓库内的发布流程规范（随仓库同步）；机器本地的包与源码切片对照记录见项目 outputs/test/README.md（不同步仓库）。

## 核心原则

1. 发版需用户确认：任何对外发布（新建 GitHub Release、对已发布 Release 换源或替换附件）都必须先经用户确认；测试包仅供验证，不视为发布。
2. GitHub Release 正文只写本版本日志：每个 Release 页面只放该版本（相对上一版本）的变更条目，禁止把历史版本的更新日志混入当前 Release 正文。
   - 历史全量更新日志只在客户端「关于页」展示（应用内需展示整个发展历程），由客户端按版本列表渲染。
   - 反面示例：v0.0.2 Release 正文里混入 v0.0.1 日志即属错误，应只保留 v0.0.2 条目 + 安装说明 + 必要备注。
3. 更新日志文本需用户确认：版本号（versionCode / versionName）与日志条目以用户确认为准，不得擅自新增条目或改动措辞。
4. 每个 APK 必须能对应到精确源码：出包前先用 scripts/snapshot-source.ps1 生成源码切片（outputs/source-snapshots/APK名-src）。
   - 切片排除 .git、keystore/、keystore.properties、local.properties、internal-dicts/ 等敏感或非仓库文件；APK 名与切片名一一对应，可复现任意包。

## 发布流程

1. 汇总本版本功能/修复清单，整理成「仅本版本」的更新日志，交用户确认。
2. 构建正式 release APK（签名证书须与历史发布一致，可覆盖安装），生成对应源码切片。
3. 打 tag 并推送（如 v0.0.3），创建 GitHub Release：title 与 tag 为 v版本；正文仅本版本日志 + 安装说明（必要时附补丁/安全备注）；附件命名 JuiceDict-v版本-release.apk。
4. 发布后校验：releases/latest 已指向新 tag；附件 SHA-256 与本地构建一致；APK 签名证书 SHA-256 与历史一致；工作树干净，远端 tag 等于推送提交。
5. 补丁换源：对已发布 Release 修复时保持版本号与签名不变，仅替换同名附件，并在正文追加简短备注。
