# Task 3 报告：`region` 包（M2 / simos-map）

**状态**：DONE（顾虑清单见文末 §6；无阻塞项）
**提交**：`6d0de35`（10 文件 / +934 行；按显式路径 `git add`，提交前扫过 `git diff --cached --stat`；**未推送**）
**测试汇总**：`./mvnw -pl simos-map -am clean verify` 全绿 —— simos-map 69 用例 0 失败、simos-util 156 用例 0 失败；Checkstyle 0 violations、SpotBugs `BugInstance size is 0`；13 轮变异全红且每轮 `COMPILATION ERROR count = 0`。

---

## 1. 交付物（行数为最终状态）

| 文件 | 行 | 要点 |
|---|---|---|
| `simos-map/.../map/hex/HexVertex.java` | 45 | 整数格角标签 `(u,w)`（R-3a/R-3b）；`U/W` 偏移表取代老仓 `Math.round(x*1000)` 的浮点身份 |
| `simos-map/.../map/region/RegionId.java` | 37 | 身份与名字分离；空白即抛；**R-48-f**：手写 `toString()` 返回裸值 + `parse()` |
| `simos-map/.../map/region/RegionMeta.java` | 14 | `color/tag/description/annexedBy`，`empty()` |
| `simos-map/.../map/region/Region.java` | 69 | 五元组 `id/name/hexes/boundary/meta`；边界是**组件**（U2）且构造期重算比对；`withHexes` 走 `of` 重算、`withName` 复用边界引用 |
| `simos-map/.../map/region/RegionBoundary.java` | 166 | `List<List<HexVertex>> rings`（R-3a）；规范化（起点/绕向/环表序）全在紧凑构造器（R-3d）；度 ≠ 2 抛（R-3e）；不沿用 `size<3` 短路（R-3c） |
| `simos-map/.../map/region/RegionIndex.java` | 55 | `regionOf` 一次 `Map.get`（O(1)，解 L5）；重叠按 id 字典序先到者胜（R-3g） |
| `simos-map/.../map/hex/HexVertexTest.java` | 62 | 六顶点互异 + 每方向恰共享 2 顶点（R-3b） |
| `simos-map/.../map/region/RegionTest.java` | 201 | 16 条，含 `boundaryIsAStoredComponent`（声明序 + 组件类型）、`withNameKeepsBoundary`（引用复用）、R-48-f 两条冻结字面量 |
| `simos-map/.../map/region/RegionBoundaryTest.java` | 179 | 8 条，含单格规范环字面量、`storedBoundaryEqualsRecomputed`（比**存储的那份**）、三条规范化、人造度 3/度 1 图压守卫 |
| `simos-map/.../map/region/RegionIndexTest.java` | 106 | 4 条，含 `CountingMap` 计数注入钉"只 `get` 一次" |

依赖纪律：本包只 import `java.*` 与 `map.hex`，无 social/unit/agentlib、无存储（enforcer 未响）。

## 2. 门禁原始输出摘要

`./mvnw -pl simos-map -am clean verify`（**clean**，非增量 —— R-48-f 之后的最终状态因此被完整复核）：

```
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0        # simos-util
[INFO] BugInstance size is 0                                     # simos-util
[INFO] Tests run: 69,  Failures: 0, Errors: 0, Skipped: 0        # simos-map
[INFO] BugInstance size is 0                                     # simos-map
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
```

## 3. 变异实验室（13 轮，全部红）

装置：`/tmp/m3lab/`（`mutate.py` 每条形变是**唯一匹配**的精确替换，匹配数 ≠ 1 直接抛；`run.sh` 每轮先 `cp -rf pristine/map` + 删 `target/{classes,test-classes}` 造干净世界，变异体**原地**写入目标文件，跑前跑后比 md5，再断言 `grep -c "COMPILATION ERROR"` 为 0）。**装置与各轮日志已随本报告入库**：`task-3-evidence/`（`mutate.py`、`run.sh`、`md5-manifest.txt`、各轮日志、`gate-clean-verify.txt`、`probe/Probe2.java`；m3v-7/m3v-8 的整环转储日志过大未入，可按同目录脚本重放）。
**每轮都满足**：md5 字节不同（相等即作废本轮）、文件名即目标类名、编译错误数为 0、干净世界。下表 md5 已用同一脚本对同一原件**逐条重放核对**，与当轮记录逐字节一致。

| 轮 | 目标 | md5 原件 → 变异体 | 红的用例（为什么红） |
|---|---|---|---|
| m3v-1 | Region（删 U2 的"重算+比对"） | `d8b4f780…` → `7a9a1b94…` | `constructorRejectsBoundaryThatDisagreesWithHexes` —— 只此一条：守卫被删 ⇒ 不再抛 IAE ⇒ `assertThatThrownBy` 落空。其余用例传入的边界都由 `of` 算出，删守卫不影响 |
| m3v-2 | Region（`withHexes` 内容取旧的） | `d8b4f780…` → `50f1ca5e…` | `withHexesKeepsIdAndName`、`withHexesRecomputesBoundary` —— 红的是**断言本身**（新旧内容各自与边界自洽，构造器校验照过）；`hexes` 断言先响，`boundary` 断言没轮到 |
| m3v-3 | Region（`withHexes` 不重算边界） | `d8b4f780…` → `f3d222ef…` | 两条 **Errors**（非 Failures）：`… » IllegalArgument boundary 与 hexes 不一致` —— 构造器 U2 校验抛，正是派单书预测的形态 |
| m3v-4 | RegionBoundary（删"度 ≠ 2 则抛"） | `5e7becae…` → `2f01e8aa…` | `walkRingsRejectsVertexDegreeOtherThanTwo` —— 人造度 3 图不抛了；合法输入造不出度 ≠ 2（R-3e），故只有人造图能压 |
| m3v-5 | HexVertex（`W[1]`: 1 → −1） | `813b671b…` → `b1566126…` | 24 条（hex/region 全包）：顶点身份错 ⇒ 相邻格共享关系错 + 环上出现度 4/1 ⇒ `of` 时守卫抛。唯一一轮"面状红" |
| m3v-6 | RegionBoundary（删环表排序） | `5e7becae…` → `de3d207b…` | `boundaryIsIndependentOfInputSetIterationOrder`、`compactConstructorNormalizesStartDirectionAndRingOrder [环表换序]` |
| m3v-7 | RegionBoundary（不做方向规范化） | `5e7becae…` → `930fe09c…` | `boundaryIsIndependentOfInputSetIterationOrder`、`[绕向相反]`、`regionOfIsConstantTime`、`equalityIsComponentwise` —— 内容相同而边界不相等 |
| m3v-8 | RegionBoundary（不做起点规范化） | `5e7becae…` → `4e3bcf40…` | `singleHexRingHasSixVertices`、`[环起点不同]`。**未红的**：`boundaryIsIndependentOfInputSetIterationOrder`（见 §5.3）；另有**非确定性**附带红 `RegionIndexTest.regionOfIsConstantTime`（见 §3.1） |
| m3v-9 | RegionIndex（`regionOf` 退化成遍历） | `44dea904…` → `fa3d1b54…` | `regionOfIsConstantTime` —— 值仍全对，红的**纯粹是"只 `get` 一次"的计数断言**（最强形态） |
| m3v-10 | Region（`withHexes` 拿 name 当 id） | `d8b4f780…` → `09f61430…` | `withHexesKeepsIdAndName`（id 断言）—— 铁律 1 的钉子 |
| m3v-11 | Region（复合 m3v-1 + m3v-3） | `d8b4f780…` → `6eacd655…` | `constructorRejectsBoundaryThatDisagreesWithHexes` **+ `withHexesRecomputesBoundary` 的 boundary 断言** —— 拆掉守卫后这条断言终于轮到自己响，证明它独立可证伪（前两轮的红都被更早的断言抢先） |
| m3v-12 | RegionIndex（删按 id 字典序排序） | `44dea904…` → `d9d73fca…` | `overlappingRegionsResolveDeterministically` —— 裁决退化成人参顺序，倒序那份给出 `b` |
| m3v-13 | RegionId（删手写 `toString`，R-48-f 加分项） | `cc724cc2…` → `54124d68…` | `regionIdToStringIsBareValue`（拿到 `RegionId[value=r1]`）+ `regionIdParseRoundTripsFrozenLiteral`（`parse(toString())` 收下非空白串、返回**另一个身份**） |

装置自身的状态：`pristine` 镜像我一度**漏了 `RegionMeta.java`**（`cp -rf` 不删多余文件，故从未影响任何一轮 —— 没有变异针对它、它在每轮都原样保留）；本次已补齐并复核 md5。

### 3.1 m3v-8 的附带红：一条实测出来的真问题（不是噪音）

m3v-8 那轮 `RegionIndexTest.regionOfIsConstantTime:68`（构造 1000 格一行的 Region）抛了 U2 的"不一致"。**实测它跨运行漂移**：当轮日志里两侧环首顶点是 `u=32240` 与 `u=33297`（i≈16），今天复跑则是 `149308` 与 `148251`（i≈74）。

原因（读代码 + 两次探针实测）：`Region.of` 会**算两次**边界 —— 工厂拿调用方的 `HashSet`，紧凑构造器拿它的 `Set.copyOf` 副本；两份的迭代序不同，而 `SetN` 的探测序列**每个 JVM 运行都不同**（实测同一内容、同一 JVM 内稳定，跨运行变：一行 1000 格，两次运行 `Set.copyOf` 副本的首元素分别是 `923_0` 与 `330_0`）。迭代序差 ⇒ 邻接表里的邻点顺序差 ⇒ `walkOneRing` 选的方向差；起点规范化被删时，环的起点就跟着漂。

对照（同一探针，跨 100 个 offset）：**纯净实现 0 处分叉**；变异体两次运行分别 6 处、15 处（且是哪几处每次不同）。即：规范化确实是内容纯函数的保证，而"跨运行"这件事本身把这条保证的必要性抬到了比原以为更高的位置。

## 4. 与裁定的关系（无反对意见）

- **R-3a**：采纳 —— 派单书的 `RegionBoundary(List<List<HexCoord>> rings)` 与 `singleHexRingHasSixVertices` 自相矛盾，环按**格角顶点**实现，单格 6 顶点得证。
- R-3b/R-3c/R-3d/R-3e/R-3g/R-48-f 均按裁定实现，无冲突；R-3e 的护栏走包内可见的 `walkRings` 缝（合法输入无法触发）。
- 需说明的**映射偏离**：派单书变异行的"`RegionBoundary.of` 不排序、顺着入参迭代序走"在本架构下**不能**写成对 `of` 一处的变异 —— 规范化按 R-3d 住在紧凑构造器里，`of` 自身不含排序。它被拆成 m3v-6（环表序）+ m3v-8（起点），绕向那半由 m3v-7 覆盖。

## 5. 我没能验证的事（诚实清单）

1. **没与老仓 GSimulator 对拍几何**。R-3b 的偏移表靠**解析推导 + 自洽性测试**（六顶点互异、每方向恰共享 2 顶点、单格规范环写成显式字面量）钉住，不是与老仓逐点比对的产物。若老仓实际几何与推导相反，我的字面量会一起错。
2. `new RegionBoundary(List.of(List.of()))`（**空环**）没写用例：按实现会从 `List.getFirst()` 抛 `NoSuchElementException`。这是**推导**，没实测。
3. `boundaryIsIndependentOfInputSetIterationOrder` 这条用例的**判别力比名字弱**：m3v-8 下它保持绿。我一度补了一条"大集合"断言想补上，实测它在变异体下**时红时绿**（即上面的跨运行加盐），于是**撤掉**了 —— 宁可留一条判别力有限但结论稳定的用例，也不要一条会因 JVM 运行而闪的护栏。起点规范化真正靠 `singleHexRingHasSixVertices` 与 `[环起点不同]` 钉（这两条在 m3v-8 下稳定红）。
4. 拓扑只覆盖到"两个不连通单格"（多环），**没覆盖带洞的环**（洞会让"环表排序 + 逐环规范化"更吃紧）。
5. 性能没实测：`regionOf` 的 O(1) 用**调用次数**钉（不测时间，时间不稳）。
6. m3v-11 是复合轮，`mutate.py` 里没有对应条目（由 m3v-1、m3v-3 依次施加得到，两种施加顺序得到同一 md5）；其"当轮行号"指的是加 R-48-f 两条用例**之前**的测试文件修订，其余各轮的行号与最终修订一致。

## 6. 顾虑 / 建议控制器处置

1. **给 Task 6 的硬提醒（本次实测得出）**：`Set.copyOf` 的迭代序**跨 JVM 运行会变**，而 `Region.hexes` 正是 `Set.copyOf`、`Region.equals` 逐组件。任何从 `hexes` 迭代序派生的**存储/序列化/哈希**（`MapChangeSet` 的 hexes 字段、变更集 key、content hash）都必须排序或用无序形式，否则变更集内容会跨运行漂移。建议在 Task 6 派单时把这条写成硬约束（本任务里正是 R-3d 的规范化在挡它）。
2. 一条**冻结字面量**式的"单格规范环"（`RegionBoundaryTest`）同时承担着"R-3b 偏移表正确"的证据；若 Task 4 及以后改动 `HexVertex`，它是第一道会响的护栏 —— 要知道它会响，不要当噪音改掉。
3. 建议 spec/计划把"空环输入"的口径写明（当前实现是 `NoSuchElementException`，我**没**测，也没在 Javadoc 里承诺）。

## 7. R-48-f

已按 R-48-f 补三件套（`toString()` 裸值 + `parse()` + `RegionTest` 内两条冻结字面量用例；`toString()` 的 Javadoc 写明它是变更集的 String key、不是调试输出），并按加分项加了 m3v-13 一轮变异（红，见 §3）；最终状态以 **clean** `verify` 复核（§2）。
