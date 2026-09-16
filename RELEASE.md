# JuiceDict 版本发布说明（Release Process）

本文件是仓库内的发布流程规范（随仓库同步）；机器本地的 APK、构建日志与排查脚本统一保存在项目 `build-artifacts/`（不进入仓库）。

## 本机工作区与 GitHub 授权

- 当前维护工作区：`D:\dsh\juicedict\`。
- 原历史仓库（只读参考，不在原位修改）：`E:\Codex\2026-08-30\https-github-com-koreader-koreader-https\stardict-app\`。
- 旧 GitHub 工作树（只读参考）：`E:\Codex\2026-08-30\https-github-com-koreader-koreader-https\work\gh-juicedict\`。
- 远端仓库：`https://github.com/qiuminal/juicedict`。
- 本机没有 `git` / `gh` 可执行文件，提交与发布一律使用 GitHub Git Data API 和 REST API 完成（提交、更新 ref、触发 workflow_dispatch、创建 Release）。
- GitHub 授权保存在 Windows 凭据管理器目标 `git:https://github.com`；不得把令牌写入仓库、日志或命令输出。发布前必须读取该凭据并调用 `GET /user` 验证账号为 `qiuminal`，不能只检查 `GITHUB_TOKEN` / `GH_TOKEN` 环境变量。

## 更新日志双版规范

每个版本同时维护两套日志：

1. **对内详细版**：仓库根目录 `CHANGELOG-INTERNAL.md`，用于开发追溯、维护和问题定位。完整记录功能、技术实现、根因分析、兼容性与验证结果，不受篇幅限制。
2. **对外精简版**：仓库 `release-notes/vX.Y.Z.md`，用于客户端「关于 → 更新日志」和 GitHub Release 正文，只保留普通用户需要了解的功能与修复；措辞必须经用户确认。

两版必须使用相同的版本号和发布日期；对内版可以更详细，但不得与对外版的事实冲突。发版默认先整理对内详细版，再由用户确认对外精简版。

## GitHub Actions 发布方式

发布采用 GitHub Actions 云端构建，**不使用本机 APK 作为 Release 附件**——本机包仅用于真机验证。

### 工作流单一化原则

**仓库只保留当前开发版本的一个发布工作流**，文件名为 `release-vX.Y.Z.yml`。版本发布完成后，该工作流继续作为下一个版本的载体沿用（改名 + 更新其中的版本号），不要为每个版本新增文件。

历史遗留的 `release-v0.1.1.yml`（内容为 v0.1.2）与 `release-v0.1.3.yml` 已在 v0.1.4 发布后删除：它们已完成使命，且仍在引用已失效的第三方 Action。保留它们只会带来"某个旧工作流被误触发后失败"的风险。

### 禁止使用的 Action

**不得使用 `android-actions/setup-android`。** 该 Action 会尝试通过 `sdkmanager` 安装已被 Android 官方仓库移除的 `tools` 包，运行时报 `Failed to find package 'tools'` 并以 exit 1 失败，导致构建在 `Set up Android SDK` 步骤就中断（v0.1.3 定时任务与 v0.1.4 首次发布均因此失败）。

`ubuntu-latest` runner 已预装 Android SDK（含 `build-tools/35.0.0` 与 `platforms/android-35`），正确做法是直接导出路径并断言存在：

```yaml
- name: Set up Android SDK
  run: |
    set -euo pipefail
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
    test -d "$SDK"
    echo "ANDROID_HOME=$SDK" >> "$GITHUB_ENV"
    echo "ANDROID_SDK_ROOT=$SDK" >> "$GITHUB_ENV"
    test -d "$SDK/build-tools/35.0.0"
    test -d "$SDK/platforms/android-35"
```

若将来 `build-tools` 或 `platforms` 版本随 SDK 升级而变化，同步更新上面的断言路径，不要退回引入 setup Action。

### 禁止保留定时触发器

发布工作流只保留 `workflow_dispatch`，**不得添加 `schedule`/cron**。

定时触发只适用于「版本已提交、等待指定时刻自动发布」的短暂窗口；版本一旦发布完成，残留的 cron 会让仓库每天空跑 CI，并在 Release 已存在时以失败结束（v0.1.2 与 v0.1.3 都发生过：仓库数日无提交，却每天出现失败运行）。需要发布时手动触发即可。

### 附件命名

Release 附件统一命名为 `JuiceDict-vX.Y.Z-release.apk`（与 v0.1.0 / v0.0.x 历史命名一致），不使用 Gradle 产物默认名 `app-release.apk`。工作流在发布前把构建产物 `cp` 为该名称后再上传。

### Secrets

GitHub Actions Secrets 应配置：

- `JUICEDICT_KEYSTORE_BASE64`：release.keystore 的 Base64 内容
- `JUICEDICT_STORE_PASSWORD`
- `JUICEDICT_KEY_ALIAS`
- `JUICEDICT_KEY_PASSWORD`

签名密钥和密码不得写入仓库、日志或 Release 正文。工作流只在 runner 临时目录还原 keystore，结束时由 runner 清理。

### 历史签名证书

所有 Release APK 必须使用同一历史正式证书签名，工作流内置校验：

```
00173116DD7CDC1F6BC192BC876335F379EA387CDCD59222FE758F9F1D2986D0
```

签名不一致时构建必须失败，禁止发布换签名的包（旧版本无法覆盖安装）。

## 核心原则

1. **发版需用户确认**：任何对外发布（新建 GitHub Release、对已发布 Release 换源或替换附件）都必须先经用户确认；测试包仅供验证，不视为发布。
2. **Release 正文只写本版本日志**：每个 Release 页面只放该版本（相对上一版本）的变更条目，禁止混入历史版本日志。
   - 历史全量日志只在客户端「关于页」展示，由客户端按版本列表渲染。
   - 反面示例：v0.0.2 Release 正文里混入 v0.0.1 日志即属错误。
3. **日志与版本号需用户确认**：版本号（versionCode / versionName）与对外日志条目以用户确认为准，不得擅自新增条目或改动措辞。
4. **交付必须给出完整路径**：提交产物、报告结果时写出可点击的完整文件路径，不能让用户自己去找。
5. **不虚构验证结果**：没有真机/实测证据时明确说明"未验证"，禁止用"构建通过"冒充"功能验证通过"。

## 发布流程

1. **整理对内日志**：汇总本版本的功能、技术实现、根因与验证清单，写入 `CHANGELOG-INTERNAL.md`。
2. **起草对外日志**：从对内版提炼「仅本版本」的精简条目，写入 `release-notes/vX.Y.Z.md`，交用户确认。
3. **落版本号**：更新 `app/build.gradle.kts` 的 `versionCode`（递增）与 `versionName`；同步 `app/src/main/res/values/strings.xml` 的 `changelog_content`（新版本置顶，保留全部历史）。注意 XML 中必须转义为 `\n`。
4. **准备发布工作流**：确认 `release-vX.Y.Z.yml` 中的版本号断言、tag、附件名与本版本一致；确认没有 `schedule`、没有 `android-actions/setup-android`。
5. **本地预检**：运行 `testDebugUnitTest` 与 `assembleRelease`，确认通过并校验 APK 版本号与签名证书。
6. **提交到 main**：经 Git Data API 提交上述文件并更新 `refs/heads/main`。
7. **触发发布**：`POST /actions/workflows/release-vX.Y.Z.yml/dispatches`，body `{"ref":"main"}`；等待运行结束并确认 `conclusion=success`。若失败，先读日志定位，不要在未修复的情况下反复重试。
8. **发布后校验**（逐项确认，缺一不可）：
   - Actions 运行 `conclusion=success`；
   - `GET /releases/tags/vX.Y.Z` 存在，且 `target_commitish` 等于本次构建提交；
   - `GET /releases/latest` 指向新 tag；
   - 附件名为 `JuiceDict-vX.Y.Z-release.apk`；
   - 下载附件实测：`aapt2 dump badging` 的 versionCode/versionName 正确，`apksigner verify --print-certs` 证书等于历史证书，文件 SHA-256 等于 Release 记录的 digest；
   - Release 正文等于用户确认的文案。
9. **补丁换源**：对已发布 Release 修复时保持版本号与签名不变，仅替换同名附件，并在正文追加简短备注。

## 本机排查脚本约定

定位引擎/格式问题时临时编写的验证脚本放在 `build-artifacts/probe/`，仅作本地证据留档，不进入仓库。这类脚本用于复现真实词典的解析行为，是"不虚构验证结果"原则的支撑材料。
