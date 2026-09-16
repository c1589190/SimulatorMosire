# Task 2 报告：`terrain` 包 —— 唯一词表

**状态**：DONE
**提交**：`6d52322` `feat(map): terrain 包——唯一地形词表（7 项，高度升序，保序）`
**分支**：`feat/m2-map-simos`（**未推送**）
**日期**：2026-09-17
**需求来源**：`task-2-brief.md`（Task 2 原文）+ 控制器对 6 处歧义的裁定 (a)~(f)

## 0 交付物

| 文件 | 行数 | 说明 |
|---|---|---|
| `simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainType.java` | 50 | 10 字段 record + 构造期校验 |
| `simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainCatalog.java` | 79 | ★ 唯一词表：`KEYS` / `defaults()` / `of()` |
| `simos-map/src/test/java/io/mosire/simos/map/terrain/TerrainCatalogTest.java` | 152 | 13 条用例 |

证据目录：`.superpowers/sdd/2026-09-16-map-simos-plan/task-2-evidence/`（11 份跑测日志、10 个变异体、装置脚本、`md5-manifest.txt`、门禁原始输出 `gate-verify.txt`）。

### 0.1 与本任务无关的并发改动（**我没有碰**）

我作业期间，控制器在**同一个工作树**上又提交了 `783a096`、`c9e2723`（改的是 `CLAUDE.md`、`progress.md`、三份 plan 文档，mtime 00:29:55–00:32:06）。我的提交**只含上表 3 个文件**——`git add` 是逐个列出的，未用 `git add -A`，提交前扫过 `git diff --cached`。

---

## 1 七行地形数值表（★ 全部为**本任务新定，非来自 GSimulator**）

| # | key | name | color | minHeight | maxHeight | food | gold | stone | moveCost | description | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `ocean` | 海洋 | `#1F5FA0` | 0.00 | 0.30 | 0 | 0 | 0 | **999** | 海滨与内海的水体，不可通行、无产出 | **本任务新定** |
| 2 | `plains` | 平原 | `#9CCB5B` | 0.30 | 0.45 | 3 | 0 | 0 | **1** | 可耕作的核心地带，产能最高、最好走 | **本任务新定** |
| 3 | `desert` | 沙漠 | `#E7C86E` | 0.45 | 0.55 | 0 | 1 | 1 | 3 | 干旱带上的贫瘠地形，产出少而难走 | **本任务新定** |
| 4 | `low_hills` | 低矮丘陵 | `#A8B36A` | 0.55 | 0.65 | 2 | 1 | 1 | 2 | 低地与山地的过渡带，产量中等、略难走 | **本任务新定** |
| 5 | `mountains` | 山地 | `#7A7F85` | 0.65 | 0.78 | 0 | 2 | 3 | 6 | 石与矿富集，很难走 | **本任务新定** |
| 6 | `plateau` | 平缓高原 | `#B99B6B` | 0.78 | 0.90 | 1 | 1 | 1 | 4 | 海拔高但地势平坦，相对好走 | **本任务新定** |
| 7 | `plateau_mountains` | 高原山地 | `#68798C` | 0.90 | 1.00 | 0 | 1 | 2 | 12 | 海拔最高处，几乎不可通行 | **本任务新定** |

**「来自 GSimulator」的行：0 行（本表为空）** —— U1 已把那份 9 项表整个作废，我未从中抄任何数值。

### 1.1 三条裁定断言（控制器给定，未改）

- `plains` 的 moveCost **严格最小**：1 < {2, 3, 4, 6, 12, 999} ✓
- `ocean ≥ plateau_mountains`：999 ≥ 12 ✓（"不可通行"不弱于"几乎不可通行"）
- `plateau < mountains`：4 < 6 ✓（"高但平坦、相对好走"）
- **其余两两之间不设断言**。值本身是单调的（1 < 2 < 3 < 4 < 6 < 12 < 999），但计划没给依据，故不写成断言。

### 1.2 哨兵值与"不加第 11 个字段"（裁定 c）

- **不可通行 = `moveCost` 的哨兵值 `999`**（只出现在 `ocean` 一项上），Javadoc 里写明"不可通行以 `moveCost` 的哨兵值表达"，**没有** `passable` 之类的布尔。
- record 的组件**恰好 10 个**：`key` / `name` / `color` / `minHeight` / `maxHeight` / `food` / `gold` / `stone` / `moveCost` / `description`。**没有静态常量、没有派生字段**（`IMPASSABLE_MOVE_COST` 这类常量我**故意不建**，免得"10 个字段"这句话变成需要解释的事）。
- 哨兵值取 **999 而非旧 `water` 的 99**：刻意避开与 GSimulator 数值相同，免得报告里"新定"与"抄来"混淆。**代价**见 §7 顾虑 5。

### 1.3 颜色与 `#6CC261` 陷阱（裁定 b）

- 我**没有**选中 `#6CC261`。挑"平原绿"时按裁定 (b) 主动避开**中等饱和绿**那一族（`#6CC261` 正在其中），改取黄绿 `#9CCB5B`。
- 7 项颜色两两不同，全过 `#[0-9A-Fa-f]{6}`，**无一等于 `#6CC261`**。

### 1.4 `defaults()` 的形状（裁定 d）

按草图：方法内**新建** `LinkedHashMap` → 填 7 行 → `Collections.unmodifiableMap(m)`。**不用 `Map.copyOf`**（打乱迭代序），**不做 `static final` 缓存**（静态初始化抛异常会变成 `ExceptionInInitializerError`，而"7 项都构造得出来"这条用例要看见的是 `IllegalArgumentException`）。

---

## 2 高度带表 + 覆盖 `[0,1]` 自证

| # | key | 带（左闭右开） | 宽度 |
|---|---|---|---|
| 1 | `ocean` | `[0.00, 0.30)` | 0.30 |
| 2 | `plains` | `[0.30, 0.45)` | 0.15 |
| 3 | `desert` | `[0.45, 0.55)` | 0.10 |
| 4 | `low_hills` | `[0.55, 0.65)` | 0.10 |
| 5 | `mountains` | `[0.65, 0.78)` | 0.13 |
| 6 | `plateau` | `[0.78, 0.90)` | 0.12 |
| 7 | `plateau_mountains` | `[0.90, 1.00]` | 0.10 |

**自证**：

- 首带 `minHeight == 0.0` ✓，末带 `maxHeight == 1.0` ✓
- 相接处**写的是同一个字面量**：`0.30`/`0.45`/`0.55`/`0.65`/`0.78`/`0.90` 各出现两次（上一项的 `maxHeight`、下一项的 `minHeight`）⇒ 浮点 `==` 成立，**无需容差**
- 宽度和 `0.30+0.15+0.10+0.10+0.13+0.12+0.10 = 1.00` ✓ 无缝、无重叠、恰覆盖 `[0,1]`
- 「顺序 = 高度升序」不是注释里的承诺而是可红断言：`keysAreInAscendingHeightOrder`（变异体 **m6** 证明它有判别力）

---

## 3 Step 3：GSimulator 旧词表调查（★ **这是给 M6 的输入，不是 M2 的需求**）

**底本**：本机 `~/DevMosire/GSimulator` 存在，`git log -1` = `88d0f02`（与 spec 声明的取证底本一致）。以下每条都是**当场跑过的**。

### 3.1 三条命令的实测结果

| 命令 | 结果 |
|---|---|
| `git grep -n "defaultTerrainTypes" -- '*.java'` | **2 处**：定义在 `MapGenerator.java:178`（词表 B，9 项）；消费在 `ContourQueryEngine.java:115` |
| `git grep -n "TerrainType.defaults" -- '*.java'` | **23 处**：定义在 `MapData.java`（词表 A，8 项）；消费 2 处非测试（`MapData.java:56`、`MapData.java:100`）+ 21 处测试 |
| `git grep -c "#6CC261" -- .` | **17 个文件、28 行**。其中 main/前端 **6 行**：`MapData.java:271`（词表 A 的平原绿本尊）、`ContourQueryEngine.java:270`（★ 兜底色）、`web/js/pathway.js:108,109,475,483`（前端补默认格）；其余 22 行在测试里 |

### 3.2 我自己补测到的具名副本（带 `文件:行`）

| # | 副本 | 位置 | 形态 |
|---|---|---|---|
| 1 | 词表 A（8 项） | `gsim-map/.../map/MapData.java:271` 起 | `TerrainType.defaults()` |
| 2 | 词表 B（9 项，**实测落盘用这份**） | `gsim-map/.../service/MapGenerator.java:179` 起 | `defaultTerrainTypes()` |
| 3 | 前端词表 | `gsim-map/src/main/resources/web/js/state.js:6` | `const DEFAULT_TERRAINS = {...}` |
| 4 | 地形→字符表（9 key） | `gsim-map/.../service/TerrainTextRenderer.java:41-52` | `TERRAIN_CHAR` |
| 5 | 颜色 switch（7 分支） | `gsim-map/.../service/ContourQueryEngine.java:260` | `default -> "#6CC261"`（**词表 A 的平原绿**） |
| 6 | 颜色 switch（6 分支） | `gsim-map/.../service/CompressionService.java:162` | ★ `default -> "#5B8C3E"`（**词表 B 的低地绿**） |

★ **第 6 行是我的新发现（spec §6.1 只写了第 5 行）**：**两个** `terrainColor` switch 的兜底色**互不相同**，一个取自词表 A、一个取自词表 B。第 5 行漏了 `lowland`（6 个 case 里没有它），第 6 行漏了 `forest/desert/tundra`——**两份都漏、漏的不是同一批**。这比"有一处兜底"更硬：串味不是一处笔误，是**两张表的默认值各走各的**。

★ **"至少 9 份副本"这个数是引用**：出自 spec §6.1 引用的侦察 D，**不是我的实测**。我实测到的是上表 **6 处具名副本 + `pathway.js` 的 4 处内联默认值**；那份 9 份清单的其余项（如"工具里的校验文本"）我**没有逐一复核**。

### 3.3 ★ 旧 key（9 项）→ 新 key（7 项）映射表 —— **给 M6 老存档导入器的输入**

**明写：本表不是 M2 的实现输入。** 本任务的实现**不读取**任何旧 key，也不包含任何兼容分支。

| 旧 key（词表 B） | 旧 name / 描述 | → 新 key | 依据 / 待裁决 |
|---|---|---|---|
| `water` | 水域 / "海洋/湖泊" | **`ocean`** | 同为水体；旧 `moveCost=99` 与新哨兵同为"不可通行"，旧产出全 0 与新 `ocean` 全 0 一致 |
| `lowland` | 低地 / "沿海低地，向内陆过渡" | **无直接对应** | 新表 7 项里没有"低地"，新 `plains` 是"可耕作的核心地带"、不是"沿海过渡带"。**待 M6 裁决** |
| `hills` | 丘陵 / "低地与山区的过渡带" | `low_hills`（**弱对应**） | 依据**只有名称接近**（丘陵 vs 低矮丘陵）。旧 `hills` 的 `stone=3` 与新 `low_hills` 的 `stone=1` **不一致**，产出不构成依据。**待 M6 复核** |
| `plains` | ★**山区** / "内陆高原/山区，高山峰簇散布其间"（命名事故） | **分叉，待 M6 裁决** | **按 key 走 → `plains`；按语义走 → `plateau` 或 `plateau_mountains`**。同名 key 的语义在新表里指向高原，这是本表最需要裁决的一行 |
| `mountain` | 高山 / "高山峰簇，嵌入山区内部" | **`mountains`** | 名称与语义都对得上 |
| `forest` | 森林 / "森林 (兼容旧地图)" | **无直接对应** | 新表 7 项无林地。**待 M6 裁决** |
| `swamp` | 沼泽 / "海岸沼泽/湿地" | **无直接对应** | **待 M6 裁决** |
| `desert` | 沙漠 | **`desert`** | 名称对应。★ **但**新 `desert` 另有**低湿度门**（Task 9）：旧存档的沙漠格若湿度高，新分类器会落到 `plains` —— 导入器需注意这条**行为差异** |
| `tundra` | 冻土 | **无直接对应** | **待 M6 裁决** |

**补充（供 M6 参考，不是裁决）**：词表 A 与 B 在**同一批 key** 上分叉的实测差异是 —— `plains`（A：平原/`#6CC261`/3,1 ↔ B：山区/`#B8A88A`/2,2）、`hills` 颜色（`#BDB76B` ↔ `#A0522D`）、`water` 名（水 ↔ 水域）、`mountain` 名（山地 ↔ 高山），且 **A 没有 `lowland`**（8 项 vs 9 项）。

---

## 4 Step 4：用例清单（13 条，全部照 brief 命名）

`TerrainCatalogTest`（包级私有；类名/方法名英文，注释中文）：

| # | 用例 | 钉住什么 |
|---|---|---|
| 1 | `catalogHasExactlySevenKeys` | `KEYS.size() == 7` |
| 2 | `defaultsKeySetEqualsKeys` | keyset **迭代序** == `KEYS`（不是"集合相等"——那会放过 `copyOf`） |
| 3 | `defaultsIterationOrderIsStable` | 连调两次 `defaults()`，key 序逐项相同 |
| 4 | `defaultsIsUnmodifiable` | `put` → `UnsupportedOperationException` |
| 5 | `ofThrowsOnUnknownKey` | `of("nope")` → IAE，消息含"未知地形类型" |
| 6 | `everyTypeHasDistinctNameAndColor` | name / color 各 7 项两两不同 |
| 7 | `everyColorMatchesHexPattern` | 7 项全过 `#RRGGBB` |
| 8 | `everyTypeIsConstructible` | 7 项按自身字段**重建**并逐项相等（构造期校验不误伤） |
| 9 | `heightBandsAreContiguousAndCoverUnitInterval` | 首 0.0 / 末 1.0 / 相接处**浮点 ==** |
| 10 | `keysAreInAscendingHeightOrder` | `KEYS` 下标序 == 按 `minHeight` 升序的序 |
| 11 | `moveCostOrderMatchesCharacteristics` | §1.1 的三条断言 |
| 12 | `plainsIsPlainsNotMountains` | `plains` 的 name 不含"山" |
| 13 | `plainsGreenIsNotTheOldFallback` | **排除用例**：7 项颜色都不等于 `#6CC261` |

### 4.1 `ofNeverFallsBack` 的处理（裁定 a）

**用例类里不存在 `ofNeverFallsBack` 方法**（数：13 条，不是 14 条）。它的**判别力由 `ofThrowsOnUnknownKey` 承担**——变异体 **m1**（把 `of()` 里的 `throw` 换成兜底 `return defaults().get("plains")`）让**该用例当场红**（§5 表 m1 行）。一个断不了言的空 `@Test` 在评审里就是"断言了零个东西"，故不写。

### 4.2 高度带为什么用浮点 `==` 而非容差

带边界是**同一批字面量**（上一项的 `maxHeight` 与下一项的 `minHeight` 是同一个数），不是两次数值计算的结果。用容差会让"差 0.001 的缝"变绿——**而那正是这条用例要抓的东西**。变异体 **m5** 造了一条 0.01 的缝，红得干干净净（`expected: 0.45 but was: 0.44`）。

---

## 5 Step 5：★ 护栏自证（变异实验室）

### 5.1 装置与纪律（逐条对照 brief §5）

| 要求 | 落实 |
|---|---|
| 变异体先自证 | 每轮打印 `md5(落盘的变异体)` 与 `md5(原件参照)`，两者**必不相同**；另打印编译产物 `.class` 的 md5，证明变异**真进了字节码**（见 `task-2-evidence/md5-manifest.txt`） |
| 按白名单推到目标类名 | 变异体文件名形如 `m1.TerrainCatalog.java`，装置按**显式传入的目标类名**落盘为 `TerrainCatalog.java`，**绝不按变异文件名拷入** |
| 每轮清掉规范名之外的 `.java` | `find … ! -name TerrainType.java ! -name TerrainCatalog.java ! -name TerrainCatalogTest.java -print -delete`（本轮零命中） |
| 强制断言无编译错误 | 每轮 `grep -c "COMPILATION ERROR"` → **10/10 轮均为 0** |
| 每轮开跑前恢复干净世界 | 先 `rm -f` 两边目录的 `.java`、再从 `ref/` 还原三份原件、`rm -rf target`；轮末再跑一次**基线轮**确认回到全绿（`log-base-final.txt`：13/13 绿） |
| 变异之间无残留 | 每轮都 `rm -rf simos-map/target simos-util/target`，且每轮 `.class` md5 与基线**都不同**（基线 `a55a3393…`） |

**原件与仓库交叉核对**：`ref/` 三份源文件的 md5 与仓库 `6d52322` 里的对应文件**逐字节相同**（`md5-manifest.txt` 末段），故"原件"不是我以为的原件。

**轮次**：`base`（绿）→ `m1…m9`（各红）→ `base-final`（绿，确认世界已复原）。

### 5.2 变异表（10 轮；brief 9 行 + 我为"没红也要问为什么没红"补的 1 行）

| # | 变异（brief 行） | 改了什么（needle → repl） | 变异体 md5 / 原件 md5 | 结果 | 结论 |
|---|---|---|---|---|---|
| m1 | `of()` 加兜底 | `throw new IllegalArgumentException("未知地形类型: " + key);` → `return defaults().get("plains");` | `2608055c…` / `091e92cc…` | **红**：`ofThrowsOnUnknownKey` — `Expecting code to raise a throwable.` | 该用例吞掉了 `ofNeverFallsBack` 的全部判别力 ✓ |
| m2 | `defaults()` 改用 `Map.copyOf` | `return Collections.unmodifiableMap(m);` → `return Map.copyOf(m);`（+ 删掉随之失效的 import，见 §5.3） | `0a615694…` / `091e92cc…` | **红**：`defaultsKeySetEqualsKeys` — 实际序 `[mountains, desert, plains, plateau_mountains, plateau, ocean, low_hills]` | 保序被钉住 ✓ **但 `defaultsIterationOrderIsStable` 未红**（见 §5.4） |
| m3 | `plains` 的 name 改回"山区" | `new TerrainType("plains", "平原",` → `…"山区",` | `69b8d444…` / `091e92cc…` | **红**：`plainsIsPlainsNotMountains` — `Expecting "山区" not to contain "山"` | 命名事故的钉子有判别力 ✓ |
| m4a | 删掉一项（KEYS 里删 `plateau_mountains`） | `…"plateau", "plateau_mountains");` → `…"plateau");` | `35cc5935…` / `091e92cc…` | **红**（3 条）：`catalogHasExactlySevenKeys`（`Expected size: 7 but was: 6`）、`defaultsKeySetEqualsKeys`、`keysAreInAscendingHeightOrder` | `catalogHasExactlySevenKeys` 有判别力 ✓（**前提是被删的是 `KEYS`**，见 m4b） |
| m4b | **我补的**：删掉 `defaults()` 里的一整行（`low_hills`），**`KEYS` 不动** | 删 `m.put("low_hills", …);` 整块 | `4f0f6a63…` / `091e92cc…` | **红**（4 条）：`defaultsKeySetEqualsKeys`、`everyTypeHasDistinctNameAndColor`、`heightBandsAreContiguousAndCoverUnitInterval`、`keysAreInAscendingHeightOrder`；**`catalogHasExactlySevenKeys` 未红** | 词表少一行**抓得到**，但抓它的**不是** `catalogHasExactlySevenKeys`（它只钉 `KEYS`）——见 §5.4 |
| m5 | 把某带 `maxHeight` 缩小 0.01（造缝） | `0.30, 0.45, 3, 0, 0, 1,` → `0.30, 0.44, 3, 0, 0, 1,` | `c29536e6…` / `091e92cc…` | **红**：`heightBandsAreContiguousAndCoverUnitInterval` — `[plains 的带尾与 desert 的带头相接] expected: 0.45 but was: 0.44` | 抓得住缝 ✓ 且红的理由**正是相接处那行**（`.as()` 描述证明不是首/末带那两条先翻）⇒ **没用容差** ✓ |
| m6 | 交换 `KEYS` 里 `plateau` 与 `plateau_mountains` | `…"plateau", "plateau_mountains");` → `…"plateau_mountains", "plateau");` | `f409ffa2…` / `091e92cc…` | **红**（2 条）：`defaultsKeySetEqualsKeys`、`keysAreInAscendingHeightOrder` | "顺序 = 高度升序"是可红断言，不是注释承诺 ✓ |
| m7 | `plains` 的 `moveCost` 改成全表最大 | `0.45, 3, 0, 0, 1,` → `0.45, 3, 0, 0, 1000,` | `8a421f27…` / `091e92cc…` | **红**：`moveCostOrderMatchesCharacteristics` — `[平原必须严格最好走（与 ocean 比）] Expecting 1000 to be less than 999` | 三条裁定断言有判别力 ✓ |
| m8 | 把某项 `color` 改成 `#6CC261` | `"#9CCB5B"` → `"#6CC261"` | `e88cb4f7…` / `091e92cc…` | **红**：`plainsGreenIsNotTheOldFallback` — `[地形 plains 复活了旧的兜底色] Expecting "#6CC261" not to be equal to "#6CC261"` | 排除用例不是空转 ✓ |
| m9 | 把某带 `minHeight` 设成等于 `maxHeight` | `0.55, 0.65, 2, 1, 1, 2,` → `0.55, 0.55, 2, 1, 1, 2,` | `f19f338d…` / `091e92cc…` | **红**（1 失败 + **11 错误**）：12 条用例的失败原因**全是** `IllegalArgument 高度带非法: [0.55, 0.55]` | **构造期护栏有判别力**，且红的理由**就是那条带校验本身**（消息点名 `高度带非法`），不是别的守卫先翻 ✓ |

### 5.3 m2 为什么有两处编辑（装置诚实说明）

`m2` 除了改 `return` 语句，还**删掉了 `import java.util.Collections;`**。原因：`checkstyle:check` 绑在 `validate` 阶段、`failOnViolation=true`，留下的未用 import 会触发 `UnusedImports` 让**构建在 validate 就失败**——那时**根本没跑到测试**，红是假红。

⇒ 两处编辑，**语义只有一处**（保序 `Map` 换成 `Map.copyOf`），删 import 是同一次改动的机械后果。装置脚本对每条 needle 都断言"命中次数 == 1"（见 `make_mutants.py`）。

### 5.4 ★「没红也要问为什么没红」——两处与 brief 预期不符，如实报出

**(1) m2：`defaultsIterationOrderIsStable` **没有红**（brief 的 m2 行把两个用例名都写上了）。**

`Map.copyOf` 的迭代序在**同一 JVM 内对同一批 key 是确定的**，两次调用给同一个序，故"连调两次相同"恒成立。**当场实测**（`task-2-evidence/probe/MapCopyOfProbe.java`，同一份 7 key，跑三个 JVM）：

```
JVM #1: 插入序 = [ocean, plains, desert, low_hills, mountains, plateau, plateau_mountains]
        copyOf 第1次 = [desert, mountains, low_hills, ocean, plateau, plateau_mountains, plains]
        copyOf 第2次 = [desert, mountains, low_hills, ocean, plateau, plateau_mountains, plains]  两次相同? = true
JVM #2: copyOf = [low_hills, ocean, plateau, plateau_mountains, plains, desert, mountains]        两次相同? = true
JVM #3: copyOf = [desert, plains, plateau_mountains, plateau, ocean, low_hills, mountains]        两次相同? = true
```

⇒ **同一进程内稳定（3/3 两次相同），跨进程才变（3 个 JVM 出了 3 个不同的序）**，且**没有一次等于插入序**。

所以：`defaults()` 换成 `copyOf` 这件事**被 `defaultsKeySetEqualsKeys` 抓住**（它在**同一个进程**里把 copyOf 的序与 `KEYS` 比，必红）；而 `defaultsIterationOrderIsStable` 钉的是**更窄**的性质（"同一进程内两次调用之间不稳定"），它对 `copyOf` **本来就不该红**。spec §6.1 说的"同一份表在**两个存档**里顺序不同"正是**跨进程**（两次运行）的事，那条性质**本任务没有用例钉住**——见 §7 顾虑 1。

**(2) m4b：删掉 `defaults()` 里的一整行时，`catalogHasExactlySevenKeys` **没有红**。**

该用例只断言 `KEYS.size() == 7`，与 `defaults()` 的行数无关。brief 的 m4 行写"删掉一项（6 项）→ 红 → `catalogHasExactlySevenKeys` 有判别力"——这句话**只在被删的是 `KEYS` 时成立**（m4a ✓）。我按"词表少一项"的两种读法各跑了一遍：m4a 证明该用例**对 `KEYS`** 有判别力，m4b 证明**对 `defaults()` 的行数没有**（词表少一行由其它 4 条用例抓住）。⇒ **词表少一项这件事抓得到，但抓它的不是这条用例**。见 §7 顾虑 2。

---

## 6 门禁输出

### 6.1 关账门禁：`./mvnw -pl simos-map -am verify`

```
[INFO] --- checkstyle:3.6.0:check (checkstyle-check) @ simos-parent ---
[INFO] --- spotless:3.10.2:check (spotless-check) @ simos-parent ---
[INFO] --- checkstyle:3.6.0:check (checkstyle-check) @ simos-util ---
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0          ← UtilSimos 全量
[INFO] --- spotless:3.10.2:check (spotless-check) @ simos-util ---
[INFO] Done SpotBugs Analysis....
[INFO] BugInstance size is 0
[INFO] Error size is 0
[INFO] No errors/warnings found
[INFO] --- checkstyle:3.6.0:check (checkstyle-check) @ simos-map ---
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.map.terrain.TerrainCatalogTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.map.hex.HexCoordTest
[INFO] Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.map.hex.HexDirectionTest
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.map.hex.HexGridTest
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0          ← MapSimos 全量
[INFO] Done SpotBugs Analysis....
[INFO] BugInstance size is 0
[INFO] Error size is 0
[INFO] No errors/warnings found
[INFO] SimulatorMosire .................................... SUCCESS [  2.830 s]
[INFO] UtilSimos .......................................... SUCCESS [  2.601 s]
[INFO] MapSimos ........................................... SUCCESS [  2.211 s]
[INFO] BUILD SUCCESS
```

- **Tests run**：MapSimos **39**（其中 terrain 13）；UtilSimos **156**；Failures/Errors 全 0
- **BugInstance size is 0**（两个模块各一次），`Error size is 0`
- **BUILD SUCCESS**（exit 0）；Checkstyle 与 Spotless 在两个模块上均通过
- 迭代期单条用例：`-Dtest=TerrainCatalogTest` → `Tests run: 13, Failures: 0, Errors: 0`（**非静默**：确认过 surefire 真跑了 13 条，不是"没跑到"）

### 6.2 约束自检

| 约束 | 检查 | 结果 |
|---|---|---|
| 不做存储 | `grep -nE "java\.io\|java\.nio\|Files\|Path\("` 三份新源码 | 零命中 |
| 无其他模块领域词 | `grep -nE "\bunit\b\|\bUnit\b\|UnitSimos\|population"` 三份新源码 | 零命中（`grep "unit"` 会命中 `org.junit.jupiter`——**子串假阳性**，已用词边界复核） |
| 不越界依赖 | 未新增任何依赖；未 import `simos-social`/`simos-unit`/`agentlib-mosire` | ✓（enforcer 在 verify 中通过） |
| 中文注释 / 不手调行宽 | 三个文件写完跑过 `./mvnw -q spotless:apply`（gjf 重排过一次，重排后即当前提交内容） | ✓ |

---

## 7 未能核实的 / 顾虑（请控制器裁）

> 判成什么、为什么这么判，都写在这里。**判错不要紧，编造比判错贵得多。**

1. **`defaultsIterationOrderIsStable` 的判别力比名字看起来弱。** 它只钉"同一进程内两次调用之间稳定"，对 `Map.copyOf` **恒绿**（§5.4 有实测）。spec §6.1 真正担心的是**跨进程**（同一份表在两个存档里顺序不同）——**那条性质本任务没有用例钉住**，我也没有擅自加（加它需要跨进程比较，超出单测能表达的范围，或退化成再钉一次与 `KEYS` 同序、与 `defaultsKeySetEqualsKeys` 重复）。**若你认为需要一条跨进程的守卫**（例如落盘侧的快照比对），请裁；我判成"`defaultsKeySetEqualsKeys` 已足以在本进程内抓住 `copyOf`，故不为它造装置"。
2. **`catalogHasExactlySevenKeys` 只钉 `KEYS`，不钉 `defaults()` 的行数**（m4b 实测绿）。也就是说：**词表少一行时，这条"7 项"用例不会响**，响的是另外 4 条。若你要的是"`KEYS` 与 `defaults()` 必须同为 7 项"，那需要在用例里同时钉 `defaults().size()`。**我按 brief 的原样实现，未擅自改**；这一条我判成"brief 的原样即如此，不属于实现者该单方面改的断言"。
3. **`plainsGreenIsNotTheOldFallback` 是大小写敏感的精确比较**：`#6CC261` 的小写形态 `#6cc261` **抓不到**（校验器 `#[0-9A-Fa-f]{6}` 允许小写）。我按 brief 写的 `!= "#6CC261"` 原样实现，**没有**擅自放宽为忽略大小写。若你认为该排除应当大小写无关，改一行。
4. **7 行的数值（颜色/产出/moveCost/高度带）全是我拍的，没有任何外部依据。** spec/plan 明说"执行期由实现者定"，我只保证了两条硬约束 + 三条 moveCost 断言。**若你对具体取向有意见**（例如"海洋 0.30 太深"、"沙漠带 [0.45,0.55) 太窄"、"999 太扎眼"），改表就是改 `defaults()` 里的一行——用例会挡住越界，不会挡住你的取向。**特别地：这些数没有经过任何平衡性验证**（本任务没有模拟器可跑）。
5. **哨兵 999 是一个取舍**：好处是与 GSimulator 的 `water=99` 不同、报告里不会混淆；代价是**丢掉与旧值的连续性**，M6 导入器需要为"旧 99 → 新哨兵"写一次显式映射（§3.3 的 `water → ocean` 行已记）。若你认为"沿用 99 更省事"，改一个数即可。
6. **`TerrainType` 的构造期校验没有自己的负例用例**（brief 的 13 条里没有）。我为它**没有**写 `assertThatThrownBy(() -> new TerrainType(…minHeight==maxHeight…))` 这类直接用例，判别力**完全由变异体 m9 承担**（红 12 条，消息全是 `高度带非法: [0.55, 0.55]`）。**我判成"m9 已构成护栏自证，再加一条是与 brief 的用例清单不符的扩写"**。若你的口径是"每条构造期守卫都要一条直接负例"，这是一条**未做**的事，请裁。
7. **"至少 9 份地形词表副本"是引用、不是我的实测**（§3.2）。我实测到 6 处具名副本 + 4 处前端内联默认值；那份 9 份清单的其余项我没复核。**不要把"9"读成我数出来的数。**
8. **`hills → low_hills` 我判成"弱对应"，依据只有名称接近**，旧 `stone=3` 与新 `stone=1` 并不一致。这一条我**可能判错**——它是一个"看起来显然、其实无依据"的映射，故我在表里标了弱对应而非直接对应。
9. **旧 `plains` 的映射我给的是双向标注（按 key / 按语义），没有给单值。** 这是我认为唯一**必须由 M6 或控制器裁**的行：给单值就等于替 M6 决定"老存档里那片'山区'应该变成平原还是高原"。若你要一个默认值，请裁。
10. **`desert` 的额外低湿度门不在本任务内**（属 `TerrainClassifier`，Task 9）。本任务只保证沙漠有独立高度带；"沙漠若只看高度会长出一圈沙漠环"这件事**在 Task 9 才落地**，此刻**没有任何代码或用例**与之相关。别把本任务的绿色读成"低湿度门已验证"。
11. **我补跑了一个 brief 之外的变异体（m4b）**。理由：brief 的 m4 行只写"删掉一项 → 红 → `catalogHasExactlySevenKeys` 有判别力"，而这条断言在"删 `defaults()` 的行"这一读法下**不成立**。我把它当成**实验室的穷尽性**（"没红也要问为什么没红"）而不是设计变更——**没有改动任何源码或断言**。若你认为实验室只该跑规定的 9 个，忽略 m4b 即可（它的日志在 `task-2-evidence/log-m4b.txt`）。
12. **`CompressionService.terrainColor` 的兜底色（`#5B8C3E`）是 spec 未记录的第 2 处串味**（§3.2 第 6 行）。我把它记进了 Step 3 的记录，但**没有**为它加任何排除用例（`#5B8C3E` 不在 brief 的排除集合里）。若 M6 要一份"串味污染值"的完整清单，`#6CC261` 之外还应有 `#5B8C3E`——**这是新信息，请控制器决定是否补进 spec**。
