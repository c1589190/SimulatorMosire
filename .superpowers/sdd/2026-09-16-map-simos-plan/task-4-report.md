# Task 4 报告：`pathway` 包（M2 / simos-map）

**状态**：DONE_WITH_CONCERNS（顾虑见 §6；无阻塞项）
**提交**：代码 `18d755c`（9 文件 / +900 行，按显式路径 `git add`，提交前扫过 `git diff --cached --stat`）；本报告与证据在同指令下的第二条 `docs(sdd)` 提交里入库。**未推送**（推送由控制器做）
**测试汇总**：`./mvnw -pl simos-map -am clean verify` 全绿 —— simos-map **102** 用例 0 失败（本任务前为 71，+31）、simos-util 156 用例 0 失败；Checkstyle `0 violations`、SpotBugs `BugInstance size is 0`（两模块）；6 轮变异**全部红**，每轮 `COMPILATION ERROR count = 0`，且 6 轮的红**全部是断言红（Failures），Errors 恒为 0**。

---

## 1. 交付物（行数为最终状态）

| 文件 | 行 | 要点 |
|---|---|---|
| `simos-map/.../map/pathway/EdgeRef.java` | 65 | 无向边，**规范序在紧凑构造器里完成**（老仓 4 份手写 `edgeKey` 合成一份，由类型系统保证而非注释）；`toString()` = `a\|b` + `parse()` 往返（R-48-f 三件套之二） |
| `simos-map/.../map/pathway/EdgeTags.java` | 35 | `byPathway`（`edgeKey → {pathwayId → {prop → value}}`）；**双层**冻结副本、`LinkedHashMap` 保插入序；老仓那条"前端写着写着就丢数据"的第二写入口被消掉 |
| `simos-map/.../map/pathway/PathwayId.java` | 41 | 身份与名字分离；空白即抛；**R-48-f**：手写 `toString()` 返回裸值 + `parse()`（形状照 `RegionId`） |
| `simos-map/.../map/pathway/Pathway.java` | 115 | `id/name/groupId/edges/props`；`edges = List.copyOf`、`props` 保序不可变；`start()/end()/isClosed()/anchor()/length()`、`freeEnd` 对"两条都共享/都不共享"抛 |
| `simos-map/.../map/pathway/PathwayGroup.java` | 72 | 字段**照老仓** `MapData.java:451`（`id/name/color/description/visible/properties`）；含**嵌套** `public record PropertyDef(String type, Object defaultValue, String description)` |
| `simos-map/.../map/pathway/EdgeRefTest.java` | 149 | 8 条：规范序（7×7 穷举两个方向）、声明序、自环、null 端点、`toStringIsStable`、**冻结串** `"-1_0\|3_4"` + 坐标序 vs 串序的分叉钉子、往返 + 9 种非法串、全序 |
| `simos-map/.../map/pathway/PathwayTest.java` | 218 | 14 条：构造守卫、两个不可变、`length`、链两端（3 格链 + 单边）、**`idsArePersistedNotDerived`（两条断言）**、逐组件相等、闭环锚点、空链/断链、R-48-f 三件套 |
| `simos-map/.../map/pathway/EdgeTagsTest.java` | 93 | 4 条：**保序（对冻结字面量比，不是自己跟自己比）**、不可变（外层 + 内层）、构造期取样、null 拒绝 |
| `simos-map/.../map/pathway/PathwayGroupTest.java` | 112 | 5 条：**★ 不在派单书 Files 段里**（见 §6.1）；字段即老仓集合、id/name/color 抛、properties 保序不可变、null 拒绝、`PropertyDef` 是嵌套 record 且三组件 |

依赖纪律：本包只 import `java.*` 与 `map.hex`；无 social/unit/agentlib、无存储（enforcer 未响）。**未触碰 `hex/**` 与 `region/**`**。

用例数对账（实测）：`pathway` 4 个测试类合计 14 + 8 + 4 + 5 = 31；本任务前 simos-map 为 71（门禁日志逐类相加：13+4+16+10+2+13+8+5），71 + 31 = 102，与模块汇总行一致。

## 2. 门禁原始输出摘要

`./mvnw -pl simos-map -am clean verify`（**clean**，全量重建；原始输出全文入库 `task-4-evidence/gate-clean-verify.txt`）：

```
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0        # simos-util
[INFO] BugInstance size is 0                                     # simos-util
[INFO] You have 0 Checkstyle violations.
[INFO] Tests run: 102, Failures: 0, Errors: 0, Skipped: 0        # simos-map（pathway 4 类：14/8/4/5）
[INFO] BugInstance size is 0                                     # simos-map
[INFO] BUILD SUCCESS
[INFO] Total time:  8.404 s
```

中文 Javadoc 的折行按纪律交给 `./mvnw -q spotless:apply` 决定，未手工调行宽。

## 3. 变异实验室（6 轮，全部红）

装置**照 Task 3 的样子改**（换 `TARGET` 表与 `mutate.py`），无新装置：`run.sh` + `mutate.py` 随报告入库 `task-4-evidence/`，各轮日志 `log-m4v-1..6.txt`，清单与结果 `md5-manifest.txt`。
每轮顺序（`run.sh` 内强制）：恢复 pristine 镜像 → **逐文件**比 md5 + 文件数校验（有清单外的 `.java` 即作废）→ 清 `target/{classes,test-classes}` → 变异**按目标类名原地写入** → 前后 md5 自证字节不同（相同即作废）→ 断言**除目标外无文件被改动** → `./mvnw -pl simos-map -am test` → 断言 `grep -c "COMPILATION ERROR"` = 0（不为 0 即作废）。
**六轮全部满足**上述每一项：干净世界 OK、md5 字节不同 OK、只有目标文件变、`COMPILATION ERROR count = 0`。测试文件六轮**从未**被变异。

下表每一格都是**实测**（`md5-manifest.txt` + 各轮 `log-m4v-N.txt`），无推导值。

| 轮 | 目标 | md5 原件 → 变异体 | 结果 | 红的用例（为什么红） |
|---|---|---|---|---|
| m4v-1 | EdgeRef：删构造期规范序交换 | `b689c01d…` → `788f849d…` | **Failures 6 / Errors 0** | `orderIsCanonical:22`、`canonicalFormTouchesASortedFields:53`、`toStringIsStable:81`、`toStringMatchesFrozenLiteral:90`、`parseRoundTripsFrozenLiteral:101`、`compareToIsTotalOrder:147`。红的是**被删那行本身**：`(3,4)/(-1,0)` 两向不再收敛，`expected: -1_0\|3_4 but was: 3_4\|-1_0`；穷举那条给 `[同一无向边的两个方向 -3_-3 / -3_-2] expected: -3_-2\|-3_-3 but was: -3_-3\|-3_-2` |
| m4v-2 | EdgeRef：删自环校验 | `b689c01d…` → `8ff831d1…` | **Failures 2 / Errors 0** | `selfLoopIsRejected:62`、`parseRoundTripsFrozenLiteral:119`，两条都是 `java.lang.AssertionError: Expecting code to raise a throwable.` —— 即 `assertThatThrownBy` 落空，**不是**抛错（Errors 0）。往返那条一起红，正因为构造器与 `parse` 共用同一批守卫 |
| m4v-3 | Pathway：构造器忽略传入 id（改用 `"derived-" + edges.size()`） | `5082b8d6…` → `3ac5ab82…` | **Failures 2 / Errors 0** | `idsArePersistedNotDerived:156`、`equalityIsComponentwise:174`。前者是**第一条**断言（内容全同、id 不同 ⇒ 不相等）先响：两条 Pathway 都变成 `id=derived-2` ⇒ 反而相等。**第二条断言（161 行）这轮没轮到**，见 m4v-6 |
| m4v-4 | EdgeTags：`Collections.unmodifiableMap` → `Map.copyOf` | `353d5fb2…` → `e38f8898…` | **Failures 1 / Errors 0** | `preservesInsertionOrder:42`。实测拿到 `["name","flow","depth","locked","width","color"]`，期望（插入序）`["width","name","locked","flow","depth","color"]` —— 保序这一项确实只由测试文件里那份冻结字面量钉住 |
| m4v-5 | PathwayId：删手写 `toString()`（退回 record 默认） | `578b8aa8…` → `5c3f2742…` | **Failures 2 / Errors 0** | `pathwayIdToStringIsBareValue:190`（`expected: "p1" but was: "PathwayId[value=p1]"`）+ `pathwayIdParseRoundTripsFrozenLiteral:197`（`expected: PathwayId[value=p1] but was: PathwayId[value=PathwayId[value=p1]]`）。**往返真的断了**，不是"两个实例比一比"的自证循环 |
| m4v-6 | Pathway：id 混内容哈希（`id.value() + "-" + edges.hashCode()`） | `5082b8d6…` → `2ff2fec7…` | **Failures 1 / Errors 0** | `idsArePersistedNotDerived:**161**`（`expected: p1-2945 but was: p1-3043`）—— 补的是 m4v-3 盖不到的那半 |

**关于 m4v-6（本轮是补做的，说明为什么）**：派单书 Step 5 的 `idsArePersistedNotDerived` 要求**两条断言合起来才算数**，但 m4v-3 的变异让**第一条先响**、JUnit 到不了 161 行 ⇒ "改内容不换身份"这半条在证据上是**空的**。补救办法不是删断言、也不是改测试去迎合，而是**加一轮能精确打到那半条的变异**（id 前缀保留、内容一变就换身份）：m4v-6 红在 161 行，证明该断言独立可证伪。两条变异（m4v-3/m4v-6）合起来才覆盖 `idsArePersistedNotDerived` 的两半。

**"为什么没红"也问过**：六轮里没有任何一轮出现"预期红却没红"的条目 —— 每轮变异的**目标用例**都红了（上表）。非目标用例保持绿也有解释（逐轮实测）：m4v-1 只影响 EdgeRef 的规范序，`PathwayTest/EdgeTagsTest/PathwayGroupTest` 里没有任何断言依赖"两个方向是同一对象"；m4v-4 的 `Map.copyOf` 只动顺序、不停改内容，`isImmutable` 那类断言本就与顺序无关。

**装置自身踩过的坑（已修，记录以免下轮重踩）**：首跑 `python3: can't open file '/tmp/m4lab/mutate.py'` —— `init()` 没把 `mutate.py` 拷进实验室目录，该轮被装置**正确作废**（世界已恢复）；已在 `init()` 里补 `cp -f`。另：模块汇总行一度用 `grep … | tail -3` 抓错（抓到的是逐类行），已换成锚定整行的正则（模块合计行没有 ` -- in <类>` 后缀），并补了 surefire 报告抽取，使每轮 kept 日志自带断言消息。`init` 会重建 `/tmp/m4lab/logs`，故**六轮是重跑一遍的整组**，入库日志彼此一致。

## 4. 与裁定的关系（无反对意见）

- **R-48-f**：`EdgeRef` 与 `PathwayId` 各补齐三件套（裸值 `toString()` + `static parse` + **冻结字面量往返用例**）。原稿 `EdgeRef` 只有手写 `toString()`、无 `parse`、无冻结用例 —— 已补 `parseRoundTripsFrozenLiteral`（含乱序输入收敛 + 9 种非法串 + `null`）与 `toStringMatchesFrozenLiteral`（字面量 `"-1_0|3_4"`）。`toStringIsStable` 只比两个方向、**不算冻结**，这一点写进了该用例上方的 Javadoc，m4v-1 那轮实测两条一起红。
- **R-48-c**：`PathwayGroup` 字段取自老仓 `MapData.java:451`（`id/name/color/description/visible/properties`，`PathwayGroupTest.fieldsAreTheOldRepoSet` 按**声明序**钉住）；老仓那五行 `if (x == null) x = …` 的**静默填空一律改成抛**（id/name 空白抛、properties 键值 null 抛、`PropertyDef.type` 空白抛）。`PropertyDef` 是**嵌套 record**，未为省事删除（`propertyDefIsNestedAndKeepsThreeComponents` 用 `getEnclosingClass()` 钉住）。老仓的颜色静默填 `"#808080"` 同样改成抛（口径 `#[0-9A-Fa-f]{6}`）。
- **R-48-b**：`EdgeTagsTest.java` 已建并入库（4 条）。
- 派单书 Step 4 大纲里既有 `PathwayTest` 段又单列了一段 `PathwayIdTest`，而 Files 段明确写"`PathwayId` 的三件套用例**并进这里，不另开文件**"（与 Task 3 把 `RegionId` 放进 `RegionTest` 同形制）—— 按 Files 段执行，四件套用例在 `PathwayTest` 里（第 185 行起的分区注释）。

## 5. 我没能验证的事（诚实清单）

1. **未与老仓 GSimulator 对拍**（**推导**）：`EdgeRef` 的规范序取**坐标序**（`HexCoord.compareTo` 先 q 后 r），老仓 `MapData.edgeKey` 走的是**串序**。这条分叉是读老仓代码 + 读裁决得出的，**没有**跑老仓逐边比对；`toStringMatchesFrozenLiteral` 里 `(10,0)/(2,0) → "2_0|10_0"` 那条断言是这条分叉的**实测钉子**（值本身实测，但"老仓确实会给出 `10_0|2_0`"是推导）。
2. **m4v-4 的残余风险**（**推导**）：`Map.copyOf` 的迭代序按 JVM 加盐、**跨运行可变**（项目里 `TerrainCatalogTest` 的注释已记过这一类）。本轮它**实测**落在了非插入序上（上面引的 6 键顺序），但理论上存在"恰好落回插入序 ⇒ 该轮假绿"的可能；6 键下这个概率很低，我**没有**测它的概率。真正的护栏是那份冻结字面量，不是"永远不同"。
3. **`Pathway.start()/end()` 的边界口径没被变异覆盖**（**推导**）：空链抛 `IllegalStateException`、闭环两头都返回最小 hex 锚点、断链抛 —— 这三条只有正向用例（`emptyChainHasNoEndpoint`、`closedLoopAnchorsAtSmallestHex`、`brokenChainIsRejectedAtEndpointAccess`），**没有**对应的删守卫变异轮。
4. **`PathwayGroup` / `EdgeTags` 的护栏只自证了部分**：`PathwayGroupTest` 的 5 条与 `EdgeTagsTest` 的其余 3 条**没有**配变异轮（派单书 Step 5 的 5 行表格里没有它们；我按"照它的样子改"只补了 m4v-6 那一行）。它们的判别力是**从写法推的**，不是测出来的。
5. **`EdgeTags` 的第二层（内层 props 的保序）没有独立变异**：m4v-4 只改外层。内层保序靠 `preservesInsertionOrder` 里的内层断言与 `isImmutable` 的 `isUnmodifiable` 正向钉住，**未**变异自证。
6. **没有测性能/规模**：`isClosed()` 是 O(n) 且会被 `anchor()` 间接调用，边界规模下无所谓，但我**没测**。

## 6. 顾虑 / 建议控制器处置

1. **`PathwayGroupTest.java` 不在派单书 Files 段里**（新增文件，请控制器追认或驳回）。理由写在文件头注释：R-48-c 要求把老仓的**静默填空改成抛**，而"护栏必须自证"要求每条守卫有一个故意违规的用例；`PathwayGroup` 的守卫若无归属文件就等于装饰。5 条用例全部是正向区分度（抛/不抛、声明序、保序不可变），**未配变异轮**（见 §5.4）。
2. **给 Task 6 的硬提醒**（与 Task 3 §6.1 同源，本任务实测到同一形态）：`EdgeTags.byPathway` 与 `Pathway.props` 都是 `LinkedHashMap` 包裹的**保序**映射，`PathwayId.toString()` / `EdgeRef.toString()` 是变更集 String key。任何拿这些 key 做**集合/哈希/序列化**的地方，都不要改成 `Map.copyOf`/`HashMap`（m4v-4 实测顺序会变），否则变更集内容会跨运行漂移。`Pathway.props` 用 `Object` 装值（老仓即如此），Task 6 的 `FieldDelta` 落到它上面时需要一个"值相等"口径。
3. **`PathwayGroup.color` 的 `#RRGGBB` 校验是本任务加的**（老仓无校验、静默填 `#808080`）。若后续有"允许 `#RGB` 简写/alpha"的输入源，这条会先响 —— 请确认口径，别当噪音改掉。
4. **`PropertyDef.type` 只拒空白、不做白名单**（`int/float/bool/string` 是老仓的**隐含约定**，代码里从未判定过）。我**没有**造白名单（造了就是编设计）；若 M2 后续要按类型校验 `defaultValue`，需先裁一个词表。
5. **`Pathway.name` 有意不校验、`description` 同样不校验**（与 `TerrainType.description` 同口径）；`Pathway.props` 允许 null 值（只拒 null 的 Map 本身）。若 Task 6 的往返要求"值不得为 null"，现在就该说，改起来是一行。
6. **`start()/end()` 的语义是我定的**（派单书只给了用例名）：空链抛 `IllegalStateException`、闭环两端都返回**最小 hex 锚点**（闭环没有自由端，与其编一个 `null` 不如给规范锚）、断链（同一顶点被 3 条以上边共享，或重复边）在访问端点时抛。请确认这与 spec §5 的"分支点即端点"口径一致。

## 7. R-48-f

`EdgeRef`（`toString()` 裸 `a|b` + `parse` + 冻结字面量 `"-1_0|3_4"`）与 `PathwayId`（`toString()` 裸值 + `parse` + 冻结字面量 `"p1"`）各三件齐全；两条 `toString` 的 Javadoc 写明"它是变更集的 String key / JSON 边界，**不是**给人看的调试输出"。变异 m4v-5 证明 PathwayId 的手写 `toString()` 删掉即红（这正是"往返会不会断"的早期报警），m4v-1 证明 `EdgeRef` 的冻结串与规范序绑在一起。最终状态以 **clean** `verify` 复核（§2）。

---

## 提交

- 代码提交：`18d755c` `feat(map): pathway 包——规范序无向边与稳定 ID 的线` —— `simos-map/src/main/java/io/mosire/simos/map/pathway/`（5 文件）+ `simos-map/src/test/java/io/mosire/simos/map/pathway/`（4 文件），9 文件 / +900 行。
- 文档提交（同一条指令、与 Task 3 的 `d0338a1` 同形制）：`docs(sdd): Task 4 报告 + 变异实验室证据入库` —— 本文件 + `task-4-evidence/`（`run.sh`、`mutate.py`、`md5-manifest.txt`、`log-m4v-1..6.txt`、`gate-clean-verify.txt`）；`.superpowers/sdd/` 被 `.gitignore` 忽略，故这两处走 `git add -f` 显式路径。
- 两次提交均**未 `git add -A`**，提交前扫过 `git diff --cached --stat`；**未推送**。
