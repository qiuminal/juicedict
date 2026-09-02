# 许可与致谢（Licenses & Notices）

就词典（JuiceDict）是一个纯离线的 Android StarDict 词典 App。本目录集中存放项目使用
到的第三方数据、格式规范与参考项目的许可说明。

## 本项目代码

- 就词典（JuiceDict）应用代码以 GNU General Public License v3.0（GPL-3.0）发布，
  全文见仓库根目录 [LICENSE](../LICENSE)。
- 应用内「关于 → 开源许可」可查看 GPL-3.0 全文、CC-CEDICT 许可全文与第三方致谢。

## 内置数据

| 组件 | 用途 | 许可 | 说明 |
| --- | --- | --- | --- |
| CC-CEDICT | 内置词库（中英对照，StarDict 格式） | CC BY-SA 4.0 | 来源 MDBG：<https://www.mdbg.net/chinese/dictionary?page=cc-cedict>；CC-CEDICT 为 CEDICT 项目的延续，CEDICT 由 Paul Andrew Denisowski 于 1997 年发起。详见 [CC-CEDICT-NOTICE.txt](CC-CEDICT-NOTICE.txt) 与 [CC-BY-SA-4.0.txt](CC-BY-SA-4.0.txt)。 |

## 格式与行为参考（独立 Kotlin 实现，未复制其代码）

| 项目 | 参考内容 | 许可 |
| --- | --- | --- |
| sdcv（StarDict Console Version） | 查词门面、编辑距离模糊匹配、词形还原等行为 | GPL-2.0-or-later |
| KOReader | 词典功能与 StarDict 集成方式（仅调研） | AGPL-3.0 |
| dictd / dictzip | `.dict.dz` 分块压缩格式规范 | GPL-2.0-or-later |

本项目以 GPL-3.0 发布，与上述 GPL-2.0-or-later 参考项目兼容。分发时须同时遵守
CC BY-SA 4.0 对内置词库的署名（Attribution）与相同方式共享（ShareAlike）要求。

## 开发依赖

| 组件 | 许可 |
| --- | --- |
| AndroidX（core / appcompat / activity / fragment / recyclerview / constraintlayout / documentfile / lifecycle） | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| Kotlin / kotlinx-coroutines | Apache-2.0 |
| JUnit 4（仅单元测试） | EPL-2.0 |

完整文本：仓库根目录 [LICENSE](../LICENSE)（GPL-3.0）、[CC-BY-SA-4.0.txt](CC-BY-SA-4.0.txt)。
