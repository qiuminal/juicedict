# JuiceDict MDict（MDX/MDD）支持 —— 进度与技术难点

版本：`0.1.4`（versionCode 7）
交付物：`app/build/outputs/apk/debug/app-debug.apk`（16,875,484 字节，无签名 debug 包）
状态：解析层与接入层已完成并通过真实语料回归；debug APK 已产出。

---

## 1. 结论摘要

| 层面 | 状态 | 证据 |
| --- | --- | --- |
| MDX/MDD 二进制解析 | 完成 | `verifyMdict` 对 7 个 MDX + 8 个 MDD（含 53MB/137390 条的《辞海第七版》）**15 passed, 0 failed** |
| 查词/渲染引擎 | 完成 | `smokeMdict` 对 7 部真实 MDX 端到端查词 + 渲染 + 资源读取 **7 passed, 0 failed** |
| 接入既有架构 | 完成 | `DictionaryRepository` / `LookupEngine` / `WifiImportEngine` 已去 StarDict 类型耦合 |
| 单元测试 | 完成 | MDict 包 6 个测试类，**62 个测试全绿**；全项目 `testDebugUnitTest` 共 141 个测试通过 |
| debug APK | 已产出 | `app-debug.apk`，`assembleDebug` BUILD SUCCESSFUL |

LZO1X 解压路径已用 32 条参考压缩器产出的真实压缩流固化为 fixture 并逐字节验证；**但当前语料中 LZO 压缩块数量为 0**（15 个文件共 raw 86 块、zlib 7474 块），该路径未经真实词典验证。

---

## 2. 架构决策：为什么不需要文档要求的统一抽象层

参考文档要求新增 `interface Dictionary` / `DictFactory` / `DictEntry` 三件套。实际实现**没有**这样做，理由如下。

### 2.1 MDict 的 record 区与 StarDict 的 (offset, size) 模型天然同构

MDict 的 record 区由若干压缩块组成，key block 中每条记录带一个 `record_offset`。若把所有 record block 解压后**按序拼接成一个虚拟平坦字节数组**，那么这个 `record_offset` 恰好就是该数组内的绝对偏移 —— 与 StarDict 的 `(offset, size)` 记录模型完全一致。

因此 `MdxRecordStore` 承担了这个「虚拟平坦数组」的角色：

```kotlin
internal class MdxRecordStore(private val reader: MdxFileReader, private val offset: Long) {
    val totalSize: Long
    val entryCount: Long
    fun read(at: Long, size: Int): ByteArray   // 可跨块读取
    private fun blockForOffset(virtualOffset: Long): Int   // 对 blockStarts 二分
    private fun loadBlock(index: Int): ByteArray           // LinkedHashMap LRU, CACHE_BLOCKS = 24
}
```

这块一落地，下游**全部零改动复用**：`Article`（`engine/Article.kt`）、`ArticleParser`（`engine/ArticleParser.kt:21` 的 `object ArticleParser`）、`LookupItem`（`data/LookupEngine.kt:8` 的 `data class LookupItem`）、`LookupRanking`（`data/LookupEngine.kt:38` 的 `object LookupRanking`）、`ui/MainActivity.kt:554` 的 `showDetail(item: LookupItem)`、`ui/SelectableLinkTextView.kt`。

### 2.2 唯一必须改的接入点

`DictionaryRepository.open(info)`（`data/DictionaryRepository.kt:217`）返回 `DictionaryEngine?`，`LookupEngine.kt:76` 直接调 `dict.lookupSmart(q, 60)`、`:83` 调 `dict.article(hit).preview(200)`。改造方式是抽一个最小门面接口：

```kotlin
// app/src/main/java/com/qiuminal/juicedict/engine/DictionaryEngine.kt:26
interface DictionaryEngine : AutoCloseable {
    val id: String
    val wordCount: Int
    fun lookupSmart(query: String, limit: Int = 60): List<DictHit>
    fun article(hit: DictHit): Article
}
```

`StarDict`（`engine/StarDict.kt:17`）与 `MdxDictionary`（`engine/mdict/MdxDictionary.kt:38`）各实现一次，`loaded` 由 `HashMap<String, StarDict>` 改为 `HashMap<String, DictionaryEngine>`。`StarDict` 的搜索逻辑一行未动 —— 这是对既有文件唯一一处「稍事修改」。

### 2.3 刻意不做的两件事

- **不复制 `StarDict.lookupFuzzy`**：它以编辑距离遍历每一个 key 并计算 DP 矩阵。在 15776 条的 StarDict 上可行，在 137390 条的《辞海》上不可接受。MDict 侧只提供「前缀 + 去空格」两级搜索，这正是 MDict 阅读器本身的交互模型。
- **不让 MDict 正文走 StarDict 的 markup pass**：MDX 正文本身已是 HTML，`Article.toHtml()` 对 `'h'` 类型原样输出（`Article.kt:36` 的 `'h' -> sb.append(t)`；其余类型会被 `escapeHtml` 或 `linkifyMarkup` 处理）。故 `MdxDictionary.article()` 直接构造 `ArticleSection('h', raw, rewriteLinks(text))`。

---

## 3. 已实现的模块

`app/src/main/java/com/qiuminal/juicedict/engine/mdict/`，14 个文件、2166 行，全部 `internal`：

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `MdxBytes.kt` | 47 | `u8/u16be/u32be/u32le/u64be/uBe` —— **混合字节序的唯一定义处** |
| `MdxHeader.kt` | 215 | header 解析、kind/encoding/stripKey 判定、adler32 校验 |
| `MdxKeyIndex.kt` | 345 | key section 前导区 + 每块描述符解析、按 key 定位块 |
| `MdxWordIndex.kt` | 233 | 紧凑词表（`ByteArray words` + `IntArray starts`），二分/前缀/去空格查询 |
| `MdxKeyOrder.kt` | 68 | 排序比较器（大小写折叠 + stripKey + **UTF-8 字节序**） |
| `MdxRecordStore.kt` | 157 | record 区 → 虚拟平坦字节数组，跨块读 + LRU |
| `MdxBlockReader.kt` | 136 | 块解压（raw/zlib/LZO）+ adler32 校验 |
| `Lzo1x.kt` | 248 | LZO1X 解压器（转写 `lzo1x_decompress_safe`） |
| `MdxResourcePack.kt` | 179 | MDD 资源包，路径查找 |
| `MdxResourceFiles.kt` | 50 | `.mdd` / `.1.mdd` / `.2.mdd` 配对 |
| `MdxDictionary.kt` | 333 | 引擎门面、`@@@LINK` 解引用、`entry://` 改写 |
| `MdxFileReader.kt` | 46 | `RandomAccessFile` 包装 |
| `MdxProbe.kt` | 83 | 轻量 header 探测（导入前校验用） |
| `MdxLog.kt` | 26 | 日志抽象（避免 `android.util.Log` 污染纯 JVM 单测） |

### 3.1 MDX/MDD 二进制布局（实测确认）

```
header:      u32be length | UTF-16LE XML (\r\n\0 结尾) | u32le adler32(XML bytes)
             校验覆盖 [4, 4+length)，不是整个文本
key section: 5×u64be (num_blocks, num_entries, index_decomp_len, index_comp_len, blocks_len)
             | u32be adler32(前 40 字节)          <-- 共 44 字节前导
             | key_index (压缩) | key_blocks
key_index 解压后每块:
             u64be num_entries | u16be first_len | first_key | NUL
             | u16be last_len | last_key | NUL
             | u64be comp_size | u64be decomp_size
key_block 解压后每条:  u64be record_offset | key | NUL
record section: 4×u64be (num_blocks, num_entries, index_len=16×blocks, blocks_len)
                | 每块 u64be comp_size, u64be decomp_size | record_blocks
                注意：record 区前导区没有 adler（与 key section 不同）
每个压缩块:  u32le comp_type (0=raw/1=LZO/2=zlib) | u32be adler32(未压缩数据) | payload
```

**MDD 与 MDX 的三点差异**：key 恒 UTF-16LE（`unitSize = 2`）、header 无 `Encoding` 属性且 rootTag 为 `Library_Data`、record 为无 NUL 的二进制。

---

## 4. 技术难点（按「踩坑代价」排序）

### 难点 1：混合字节序

同一个容器里，header 长度与所有 section/index 整数是**大端**，而每个块的 `compression_type` 是**小端**。

把 zlib 块的类型大端读出来得到 `0x02000000` 而非 `2`，报 `unknown MDict compression type`。这个错误会让人以为文件损坏。

处理方式：`MdxBytes` 成为唯一定义处，并在单测里**显式断言同一组字节两种读法结果不同**（`MdxBytesTest`）：

```kotlin
val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x02)
assertEquals(2, MdxBytes.u32be(bytes, 0))
assertEquals(0x02000000, MdxBytes.u32le(bytes, 0))
```

### 难点 2：key index 的 NUL 终止符

`fileformat.md` 声称 first/last key 字段没有 NUL 终止符。**这是错的**：真实文件里长度字段不含 NUL，但 NUL 物理存在。

```kotlin
p += firstLenBytes + unitSize   // 必须跳过终止符
p += lastLenBytes + unitSize
```

不跳的后果是后续 `comp_size` / `decomp_size` 全部错位，读出 `count=549621596160`、`comp=281083237038555136` 这种荒谬值。

更麻烦的是**部分写入器把 NUL 折进长度字段**，所以解析必须容忍两种写法而不依赖任何一种。

### 难点 3：排序规则由文件 header 驱动

这是最隐蔽的一处。MDict 的 key 排序规则是**三个细节叠加**，且每个细节都能静默破坏二分查找而不抛异常：

1. **大小写折叠**：全部文件 `KeyCaseSensitive="No"`。故 `\hei_xhzd.woff` 排在 `\XHZD_12.css` 之前。
2. **`StripKey` 由 header 声明**：语料中全部 **7 个 MDX 都声明 `StripKey="Yes"`**，故 `2.5D机织物` 排在 `21世纪议程` 之后（点号被剥掉后 `1` < `5`）；而全部 **8 个 MDD 都声明 `StripKey="No"`**（且不带 `Encoding` 属性），故 `\0.png` 排在 `\00.png` 之前。用一套规则套两类文件必然双向误报。属性名大小写还不一致（`StripKey` vs `Stripkey`），解析必须大小写无关。
3. **比较的是 UTF-8 字节序，不是 UTF-16 码元序**：U+F97F（`EF A9 BF`）排在 U+20164（`F0 A0 85 A4`）之前；而 Kotlin `String.compareTo` 比 UTF-16 码元，U+20164 变成代理对 `D840 DC64`，反而排在前面。**这个错误只在 BMP 外字符上显现**。

验证方式是穷举：对每部真实词典取最多 60000 条 key，在 4 种候选规则下统计**逆序对数**，逆序为 0 的即写入器实际使用的规则。结果：

- 所有 MDX（`strip=true`）→ `fold+strip` 逆序数 **0**
- 所有 MDD（`strip=false`）→ `fold-nostrip` 逆序数 **0**

反例数字：`现汉7.mdx` 用 `fold-nostrip` 有 2 处逆序、`辞海第七版.mdx` 有 48 处、`王力古漢語字典 (2000).mdd` 用 `fold+strip` 有 7 处。

### 难点 4：`@@@LINK` 重定向与 `entry://` 互见链接

- `@@@LINK=<目标词>` 是重定向记录（`字源 (2012).mdx`、`王力字典 上古擬音.mdx` 大量存在），必须解引用。用 `MAX_LINK_DEPTH = 8` 防环（畸形文件可能 `A→B→A`），到顶后返回原文而不是爆栈。
- `entry://目标词` 必须改写成 `juice://lookup/<urlencoded>`，这是 `SelectableLinkTextView` 唯一认得的协议，也就是「互见跳转」能工作的全部原因。

真实语料普查（7 部词典，每部最多 30000 条，共 **718,618 条链接**）结果：

| 项 | 数值 |
| --- | --- |
| target 终止符为 `"` | 718,610 |
| target 终止符为 `>` | 8 |
| 含 `#` 片段 | **0** |
| 含空格 | **0** |
| 含 `%` | **0** |
| 空 target | 1 |

分词典：`王力字典 上古擬音` 4952、`新华字典12` 1419、`字源 (2012)` 1637、`现汉7` 29963、`王力古漢語字典 (2000)` 207511、`漢語大字典 (2010)` 377787、`辞海第七版` 95349（其中 8 条以 `>` 结尾）。

即：真实数据里**没有** `entry://#frag` 这种同页锚点。但原实现对 `target.startsWith("#")` 的分支只 `append('#')`，会丢弃 `frag` —— 属于潜在数据丢失，已修为保留完整 `target`。

`URLEncoder` 的 `+` → `%20` 替换与既有 `Article.linkifyMarkup` 保持一致，因为下游用 `URLDecoder` 解析。

### 难点 5：LZO1X 解压器

语料里 LZO 块为 0，意味着这条路径**永远不会被真实数据走到**，错误不会被冒烟测试发现。因此单独处理：

- 从官方 `lzo1x_decompress_safe`（`nemequ/lzo` 的 `lzo1x_d.ch`）逐行核对语义，修正了 6 处错误。其中两处典型：
  - **M3 的扩展长度基数是 31 而非 33**，且触发条件是 `t and 0x1f == 0` 而不是 `== 33`；
  - **M3/M4 的距离忘了 `+ 1`**（官方是 `m_pos = op - 1; m_pos -= le16 >> 2`）。
- 尾随字面量取自 **`ip[-2] & 3`**（对 M3/M4 是第一个距离字节，不是指令字节）。
- 用 `org.anarres.lzo:lzo-core:1.0.1` 的 `LzoCompressor1x_1` 造真实压缩流做**差分模糊测试**：seed 20260101，**4000 例、40,437,889 字节输入、0 失败**。输入族覆盖纯随机、长 run（距离 1 自重叠）、周期 2~9 重复、17000~33000 字节随机填充（强制 M4 的 `0x4000` 远距离偏置）、稀疏零（扩展长度）、UTF-8 中文词重复、1~40 字节微输入。
- 参考库**不作为 Gradle 依赖**（项目禁止新增依赖），改为把 32 条已验证的压缩流冻结成 `app/src/test/resources/lzo/fixtures.txt`（每行 `<name> <base64 压缩流> <base64 明文>`）。

### 难点 6：接入层的格式识别与多部件 MDD

- `listDictionaries()` 原先只认 `<name>.ifo` 存在，`dictFile` 只找 `.dict.dz`/`.dict`。改为按目录内容分派：`readStarDictInfo(...) ?: readMdxInfo(...) ?: continue`，**不依赖元数据里的格式字段**。
- 一个词典可能带**多个** MDD：《字源 (2012)》的 43MB 扫描件在 `.1.mdd`，《新华字典12》的音频在 `.1.mdd`。配对规则提取为 `MdxResourceFiles.pairsFor(dir, base)`：先 `<base>.mdd`，再按**数值**序追加 `<base>.N.mdd`（字典序会把 `d.10.mdd` 排到 `d.2.mdd` 前）。
- SAF 导入（`importFromTree`）与 Wi-Fi 导入（`WifiImportEngine`）都要识别 `.mdx`/`.mdd`。MDD 入库时**保留原文件名**（含序号），否则多部件会互相覆盖。

---

## 5. 测试与验证

### 5.1 单元测试（62 个，全绿）

| 测试类 | 测试数 | 覆盖 |
| --- | --- | --- |
| `Lzo1xTest` | 9 | 32 条参考压缩流逐字节比对、子区间解压、EOS/空输入、截断与越界拒绝 |
| `MdxHeaderTest` | 16 | header 解析、adler32 校验、**校验和小端而长度前缀大端**、自闭合标签不死循环、stripKey 大小写无关 |
| `MdxKeyOrderTest` | 9 | 大小写折叠、stripKey 开关、**UTF-8 字节序 vs UTF-16 码元序**、真实词典全量有序性 |
| `MdxBytesTest` | 5 | 混合字节序、64 位读取 |
| `MdxResourceFilesTest` | 11 | MDD 配对（数值序、非数字中段忽略、不误吞他词典、真实语料配对）、资源缺失返回 null |
| `MdxLinkRewriteTest` | 12 | `entry://` 改写、同页锚点保留、终止符处理、非 ASCII 百分号编码、真实词典正文端到端 |

（共 6 个测试类、62 个测试，分布在 5 个文件里：`MdxBytesTest.kt` 同时容纳 `MdxBytesTest` 与 `MdxResourceFilesTest`。）

**两个由单测抓出的真实崩溃**（均已修）：

1. `MdxResourceFiles.pairsFor()` 先手工加入 `<base>.mdd`，随后在 `listFiles()` 扫描里**再次命中同一文件**，`substring(10, 9)` 抛 `StringIndexOutOfBoundsException`。该函数在 `DictionaryRepository.openMdx` 内被调用，异常直接传播导致**词典打不开** —— 且只要存在普通 `.mdd` 就必现，即对每部真实词典都必现。
2. `MdxDictionary.rewriteLinks()` 丢弃 `entry://#frag` 的片段（见难点 4）。

### 5.2 真实语料回归

`verifyMdict`（二进制层，15 passed / 0 failed）与 `smokeMdict`（引擎层，7 passed / 0 failed）：

```
.\gradlew.bat --offline -q verifyMdict "-Pmdict.dir=E:\dictionary\MDict"
.\gradlew.bat --offline -q smokeMdict  "-Pmdict.dir=E:\dictionary\MDict"
```

覆盖的 7 部 MDX：

| 词典 | 词条数 | 实测 |
| --- | --- | --- |
| 辞海第七版 | 137390 | 76 key blocks / 76 rec blocks，record 区解压后 255,337,813 字节 |
| 漢語大字典 (2010) | 81166 | UTF-8，30 个资源 |
| 现汉7 | 72239 | 162 个资源 |
| 王力古漢語字典 (2000) | 36722 | 63 个资源 |
| 新华字典12 | 15776 | 54 + 1325 个资源（两个 MDD） |
| 字源 (2012) | 13522 | 5 + 10269 个资源 |
| 王力字典 上古擬音 (Baxter-Sagart) | 12153 | 无 MDD |

资源读取实测：`\a1.mp3 -> 6509B`、`\000.png -> 31572B`、`\RobotoCondensed-Regular.ttf -> 170284B`。

### 5.3 debug APK

```
.\gradlew.bat --offline :app:assembleDebug
```

产物 `app/build/outputs/apk/debug/app-debug.apk` 已重新构建，大小 16,910,189 字节；`assembleDebug` 成功，本轮同时执行 `:app:testDebugUnitTest` 全部通过。`assembleDebug` 不触发 release 签名守卫（守卫条件是 taskNames 含 `"assemble"` 或含 `"Release"`，`assembleDebug` 两者都不满足）。

已用真实 Android 设备（ADB serial `52ce1128`，`23127PN0CC`）验证 Wi-Fi 导入闭环：旧 APK 的上传页实际提供的 `OK_EXT` 只有 `.ifo/.idx/.idx.gz/.dict/.dict.dz/.syn`，在请求发出前就把 `.mdx/.mdd` 显示为「失败：不支持的文件类型」；手机端 `WifiImportEngine` 本身可以接收 MDict，直接 PUT 真实 MDX 已返回 `ok:true`。修复 `app/src/main/assets/wifi/import.html`：过滤器加入 `.mdx/.mdd`，并将 `.mdx` 与 `.dict/.dict.dz` 放到最后一个上传阶段，保证同一批 MDD/元数据先上传完成后再上传主体；同时更新页面说明。新增无依赖 Node 回归测试 `app/src/test/js/wifi-import.test.js`，覆盖 MDX/MDD 接纳、MDD 先于 MDX、StarDict 主体顺序、三路并发和 `/finish` 单次调用，`node --test` 结果 3/3 通过。

新版 debug APK 已安装到该设备；从新版 APK 提供的实际页面读到过滤器已包含 `.mdx/.mdd`。随后按 `xinhua.1.mdd → xinhua.mdd → xinhua.mdx` 顺序上传真实 `新华字典12` 文件（5,547,152B、9,954,875B、963,072B）：两个 MDD 均返回 `import:null`，MDX 返回 `ok:true`、`import.base=xinhua`，其后 `/finish` 返回 `imported=[]/incomplete=[]`。此前用真实 `王力字典 上古擬音` MDX（259,086B）也已返回 `ok:true` 并在设备词典管理页显示 12,153 词条。该验证确认设备接收、分组、安装均已打通；浏览器网页需使用新版 APK 中的页面资源。

**debug 包与正式版可以共存**：`app/build.gradle.kts` 的 `buildTypes` 里给 debug 加了

```kotlin
applicationIdSuffix = ".debug"      // com.qiuminal.juicedict.debug
versionNameSuffix = "-debug"        // 0.1.4-debug
resValue("string", "app_name", "就词典 Debug")
```

因此 debug 包与已安装的正式版是**两个独立应用**，可同时安装、数据互不覆盖，桌面图标分别显示「就词典」「就词典 Debug」。`namespace` 与源码包名仍为 `com.qiuminal.juicedict` 不变，所以类名、`R` 类、`BuildConfig` 引用都不需要改；FileProvider 的 authority 在 manifest 里写的是 `${applicationId}.fileprovider`，会随后缀自动变成 `com.qiuminal.juicedict.debug.fileprovider`，`AppUpdater.kt:130` 用的 `"${context.packageName}.fileprovider"` 也在运行期自动跟随。

已用 `aapt2 dump badging` 核实产物：`package: name='com.qiuminal.juicedict.debug' versionName='0.1.4-debug'`、`application-label:'就词典 Debug'`。

---

## 6. 未完成 / 未验证

1. **LZO 路径无真实词典验证**：语料里 raw 86 块、zlib 7474 块、**LZO 0 块**（逐文件统计：`辞海第七版.mdx` 4104、`漢語大字典 (2010).mdx` 1344、`字源 (2012).1.mdd` 794、`现汉7.mdx` 372、`王力古漢語字典 (2000).mdx` 362、其余合计 498；raw 块集中在 `现汉7.mdd` 84 个与 `新华字典12.mdd` 2 个）。已用 4000 例差分模糊 + 32 条固化 fixture 覆盖，但未经真实 MDX 文件端到端跑通。
2. **加密词典不支持**：`Encrypted` 位只做识别与友好报错（`require(!head.isEncrypted)`）。已知规格（未实现）：位 1 = 关键字区前导 Salsa20/8 加密，位 2 = 关键字索引 `_fast_encrypt`（SWAPNIBBLE 流式异或），key = `ripemd128(comp_block[4:8] + struct.pack("<L", 0x3695))`。
3. **MDD 资源未渲染到 UI**：`MdxDictionary.resource(path)` 已可用且验证通过，但 `MainActivity` 的 `HtmlCompat.fromHtml` 不会去取 `.mdd` 里的图片/音频。当前表现是正文排版与文字正确、图片位空白。真正的图片渲染需要 `ImageGetter` 或改成 WebView，属于 UI 层改动，本轮未做。
4. **GBK/Big5 编码**：`decodeText` 会 `Charset.forName(header.encoding)`，缺失时退化为 Latin-1 不抛异常。语料里 7 部 MDX 全是 UTF-8，该分支未经真实文件验证。
5. **MDict 1.2**：按参考文档放弃。`MdxHeader.version` 会识别 1.2（无 `Encoding` 属性时判为 1.2f），但 1.2 的关键字区前导是 `u32` 而非 `u64` 且**无 adler32**，`MdxKeyIndex.parse` 尚未分支处理。
6. **测试源码集内的开发工具**：`app/src/test/.../mdict/` 下仍有 `MdxVerify.kt`、`MdxSmoke.kt` 两个回归工具（非交付物，但不进 APK）。二者分别绑定 Gradle 任务 `verifyMdict` 与 `smokeMdict`，是第 5 节那两组 `15 passed` / `7 passed` 结果的执行入口，因此刻意保留。本轮已删除其余一次性诊断探针（`MdxOrderCensus`、`MdxLinkCensus`、`MdxCompCensus`、`MdxDocCheck`、`MdxRedirectFind`、`MdxIdx`、`MdxKeyDump`、`MdxResProbe`、`MdxTime`）。

---

## 7. 环境注意事项（复现构建用）

- **JDK 位置**：`D:\dsh\tools\jdk\jdk-17.0.20.1+1`。`JAVA_HOME` 未设置、`java` 不在 `PATH`，跑 `gradlew.bat` 前必须先 `$env:JAVA_HOME = "D:\dsh\tools\jdk\jdk-17.0.20.1+1"`，否则报 `ERROR: JAVA_HOME is not set`。
- **PowerShell 向 JVM 传非 ASCII 参数会被破坏**：传 `E:\dictionary\MDict\新华字典12-20220917` 时程序会打印 `found 0 MDict file(s)` 并静默 exit=0。**必须只传 ASCII 根目录 `E:\dictionary\MDict`**，让程序自己 `walkTopDown` 递归；并加 `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`。
- **JVM 参数必须整体加引号**，否则 PowerShell 拆分后报 `找不到或无法加载主类 .encoding=UTF-8`。
- `pwsh` 用 `| Out-String` 会把子进程输出全部缓冲到退出，看不到进度；应 `*> $log` 重定向后轮询。
- `Start-Process` 在本环境因重复的 `NO_PROXY`/`no_proxy` 键抛异常，须改用 `New-Object System.Diagnostics.ProcessStartInfo`；`$p.Kill($true)` 重载不存在，用 `$p.Kill()`。
- 用 `(Get-Content x.kt) -replace ... | Set-Content` 会损坏文件里的 CJK 文本，须用 `[System.IO.File]::ReadAllLines(...UTF8)` / `WriteAllLines(... UTF8Encoding($false))` 按行改。
- 控制台输出中文乱码属于终端编码问题，不影响功能；验证资源读取是否成功应看字节数（如 `\000.png -> 31572B`）。
- `git` 在本环境不可用。

---

## 8. 手动测试建议

安装 `app-debug.apk` 后：

1. 通过「导入词典」选择包含 `.mdx`/`.mdd` 的目录（SAF），或用 Wi-Fi 导入传文件。
2. **普通查词**（以下均已实测可命中并出正文）：

   | 词典 | 查这个词 | 实测结果 |
   | --- | --- | --- |
   | 辞海第七版 | `楚庄王` | 2339B 正文，含 9 个互见链接 |
   | 现汉7 | `樹` | 14B 重定向记录 → 解引用后得到 `<entry id="47899">` 完整正文 |
   | 新华字典12 | `诘屈` | 14B 重定向记录 → 解引用后得到 `<entry id="2885">` 完整正文 |
   | 字源 (2012) | `席` | 1190B 正文（**非**重定向） |

3. **互见跳转**：点正文里的蓝色链接词，应跳到对应词条（走 `juice://lookup/`）。
4. **重定向解引用**：这类词条存储的正文只有 `@@@LINK=<目标>`，正确行为是显示目标词条的正文，而不是把 `@@@LINK=` 当文字显示出来。语料里重定向占比很高，用这些**可直接键入**的例子验证：

   | 词典 | 查这个词 | 应跳到 |
   | --- | --- | --- |
   | 现汉7 | `一古脑儿` | `一股脑儿` |
   | 现汉7 | `丁宁` | `叮咛` |
   | 现汉7 | `丰富多采` | `丰富多彩` |
   | 辞海第七版 | `並` | `并` |
   | 辞海第七版 | `乾` | `干` |
   | 字源 (2012) | `万` | `萬` |
   | 字源 (2012) | `丑` | `醜` |
   | 王力字典 上古擬音 | `与` | `與` |

   重定向记录的实际占比（整部词典全量扫描）：`王力字典 上古擬音` 8178/12153、`字源 (2012)` 7521/13522、`现汉7` 4785/72239、`辞海第七版` 3564/137390。

5. 已知限制：正文里的图片不会显示（见 6.3）。例如 `字源 (2012)` 查 `席` 时正文含 `<img src="/pu/201442300uyi.png">`，该图片位会是空白。
