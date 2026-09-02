# 就词典 (JuiceDict)

一个纯离线、无广告的 Android 词典 App，Kotlin 编写，支持导入 StarDict（星际译王）格式词典
（`.ifo` / `.idx` / `.dict`，以及 `.idx.gz` / `.dict.dz` 压缩变体）。

- 中文名：就词典
- 英文名：JuiceDict
- 包名：`com.qiuminal.juicedict`

## 许可与致谢

- **应用代码**：就词典（JuiceDict）以 GNU General Public License v3.0（GPL-3.0）发布，
  全文见根目录 [LICENSE](LICENSE)。
- **内置词库 CC-CEDICT**：数据遵循 Creative Commons Attribution-ShareAlike 4.0
  International（CC BY-SA 4.0）。来源：<https://www.mdbg.net/chinese/dictionary?page=cc-cedict>
  （CC-CEDICT 为 CEDICT 的延续项目，CEDICT 由 Paul Andrew Denisowski 于 1997 年发起）；
  对数据所做的整理 / 同义词合并 / 格式转换亦以 CC BY-SA 4.0 提供。许可全文与数据致谢见
  [licenses/](licenses/)，应用内「关于 → 开源许可」亦可查看。
- **格式与行为参考**（独立 Kotlin 实现，未复制其代码）：sdcv（GPL-2.0-or-later）的查词
  与模糊匹配行为、KOReader（AGPL-3.0）的词典功能调研、dictzip（.dict.dz）格式规范，
  详见 [licenses/README.md](licenses/README.md)。

词典解析与搜索行为参考了 KOReader 的词典功能——KOReader 内部通过 `sdcv`（StarDict Console
Version）实现查词，本项目在 Kotlin 中复刻了 sdcv 的核心行为：

- `.ifo` 元信息解析（wordcount / idxfilesize / sametypesequence / synwordcount / idxoffsetbits 等）
- `.idx` 索引加载：`词条\0 + 偏移(4B BE) + 长度(4B BE)`，64 位偏移（idxoffsetbits=64）与 `.idx.gz` 也支持
- `.syn` 同义词别名加载：`别名\0 + 词条序号(4B BE)`，正式词头无命中时自动回退（如简体“三军”命中
  繁体词头“三軍”），与 sdcv / ColorDict 等 StarDict 软件行为一致
- `.dict` 数据读取：按 sametypesequence 规则切分段落（小写类型 = 以 \0 结尾的字符串，
  大写类型 = 前置 4 字节长度，最后一段取剩余字节），`h`(HTML)/`x`(XDXF)/`g`(Pango)/`k`/`w` 等类型
- `.dict.dz`（dictzip）随机访问：解析 gzip 扩展头 "RA" 分块表，按块独立解压，支持大词典不整包解压
- 排序比较采用 sdcv 的 `stardict_strcmp`（ASCII 大小写不敏感 + 字节序决胜）

## 智能查询

不提供模式 Tab，用户无感：**前缀查询优先**（前缀命中天然包含精确命中，且更短的精确词排最前），
前缀无结果时依次回退到 **`.syn` 同义词别名** 与 **模糊查询**。候选列表不显示“精确 / 相似”标签。

模糊层**中英文统一一套规则**（参考 sdcv 的 `CalEditDistance`，含相邻换位 `COVER_TRANSPOSITION`）：
插入、删除、替换、相邻换位都算 1 次操作，距离 ≤ 2 即命中。

- 英文少打字母：`helo` → `hello`
- 英文相邻字母互换：`recieve` → `receive`
- 成语少打一个字：`一望际` → `一望无际`
- 汉字相邻乱序：`一望际无` → `一望无际`
- 中英混排乱序：`制AA` → `AA制`
- 错字（含同音字）：`AA智` → `AA制`（与普通错字同权，不做拼音/模糊音特判）

候选**跨词典全局排序**：精确（含大小写变体与 `.syn` 别名命中）> 前缀 > 模糊，同等级保持
词典顺序。**只要有任何词典给出精确/前缀命中，就丢弃所有模糊候选**——例如查“韭菜盒子”时
CC-CEDICT 精确命中，chibigenc 因无此词头落到模糊层返回“韭菜 / 八音盒子”，这些噪音不再显示；
只有当所有词典都没有精确/前缀命中时（如错字、缺字）才展示模糊结果。

排序：等级 → （模糊层内）距离 → 公共前缀长度 → 公共后缀长度 → stardict 字典序。

## 预建索引缓存（.jidx）

首次解析 `.idx`/`.syn` 后会把索引序列化到 `filesDir/dicts/<词库>/*.jidx`（词条以 UTF-16 连续
存放、已排序数组与预处理过的 syn 别名直接落盘），冷启动直接从缓存重建，避免每次启动重解析、
重排序（JVM 实测 chibigenc：解析 ~160ms → 缓存加载 ~80ms）。业务逻辑：

- **内置词库**：首次启动复制 assets 完成后，后台自动建索引并写入缓存；之后每次启动直接载入缓存。
- **导入词库**：导入完成后立即在后台建索引（`prewarm`），用户去查询时已就绪。
- **兜底**：若查询时缓存尚未就绪/失效，则同步解析并写缓存（只慢一次）。
- **失效**：缓存头记录 wordcount / idxfilesize / synwordcount，与当前 `.ifo` 不一致即视为失效
  自动重建（词典升级或重新导入时生效）。

## 版本历史

- v0.0.1（2026-09-02）首个Release版本（此前 1.3.1 ~ 1.4.0 内测改动并入本条）：
  内置 CC-CEDICT（2025-11-02 版，525,037 词条）；支持导入 StarDict 词典、多词典并行查询与
  `.jidx` 预建索引缓存（冷启动更快）；智能查询（输入即查、自动模糊匹配）；查询结果实时
  预览、点击展开完整释义详情、支持复制/分享；词典管理（启用/停用/删除，SAF 导入第三方
  StarDict 词典）；首页左上角侧边菜单（首页/关于）与关于页内置更新器（发现新版本显示红色
  徽标，点击一键下载安装）；书本 + 八等分橙子图标与空态广告语「没别的，就词典」；补齐开源
  许可（应用代码 GPL-3.0，内置 CC-CEDICT 词库 CC BY-SA 4.0，详见 LICENSE 与 licenses/）。

## 主要功能

- 智能查询：输入即查（300ms 防抖），前缀优先、自动 fallback 模糊，无模式选择
- 结果列表：词条 + 词典名 + 释义预览；点击候选词收起列表、原地展开完整释义详情页
  （此时只保留搜索框 + 详情），支持复制 / 分享；点搜索框的 × 清除输入并收起详情，回到初始状态
- 词典管理：查看已安装词典、启用 / 停用、删除；通过系统文件选择器（SAF）选择文件夹导入
  第三方 StarDict 词典（需同时包含 `.ifo`、`.idx` 或 `.idx.gz`、`.dict` 或 `.dict.dz`，可选 `.syn`）
- 首页左上角☰ 侧边菜单：首页 / 关于；关于页自动检查更新，发现新版本可一键下载安装
- Material 3（Material Components 1.12）+ 动态取色（Android 12+），edge-to-edge，明暗主题自适应
- 查词全程离线，导入使用 SAF 文件选择器无需存储权限；INTERNET 权限仅用于「检查更新」访问 GitHub Releases

## 工程结构

```
app/src/main/java/com/qiuminal/juicedict/
  engine/   Kotlin 版 StarDict 引擎，纯 JVM 可测（不依赖 Android）
    Ifo.kt               .ifo 解析
    StarDictIndex.kt     .idx / .syn 加载 + 二分查找（精确/前缀）+ .jidx 预建缓存读写
    DictDataReader.kt    .dict 普通文件读取（LRU 缓存）
    DictZipReader.kt     .dict.dz dictzip 分块随机解压
    ArticleParser.kt     sametypesequence 段落解析
    Article.kt           释义渲染与文本预览 / HTML
    StarDict.kt          查询门面：lookupSmart（前缀→syn→模糊）、lookupExact/Prefix/Fuzzy/SynExact
    Morphology.kt        词形还原（参考 sdcv LookupSimilarWord）
    EditDistance.kt      统一 Damerau-Levenshtein（工作区复用，零分配）
  data/     词典仓库：内置资源复制、SAF 导入、启用状态持久化、多词典并发查询、
            跨词典排序/模糊过滤（MatchRank / LookupRanking）、后台 prewarm
  ui/       Material3 界面：MainActivity（侧边菜单 + 搜索 + 原地详情）、词典管理、关于页
  App.kt    应用入口：启动时后台复制内置词库并 prewarm 建索引
AppUpdater.kt  GitHub Releases 更新检查 / 下载安装（参考虎助手）
app/src/main/assets/dict/   内置词典（CC-CEDICT）
```

分层设计使 `engine` 不依赖 Android 代码：后续如需为其他 App 提供跨应用查询，只需在
`data/` 之上加一层 AIDL 绑定服务（接口签名、Parcelable 等），把
`StarDict.lookupSmart` / `lookupExact` / `lookupPrefix` / `lookupFuzzy` 暴露为原子接口。

## 构建

环境：JDK 17、Android SDK（compileSdk 35 / build-tools 35 / platform-tools）。
