# Task 4~8 派单前扫描 —— 控制器裁定（2026-09-17）

派单 Task 4 之前，用一个**只读**子代理扫了计划 Task 4~8 有无 Task 3 那种"类型与断言互斥"的缺陷。
12 条发现，**控制器逐条回读原文核实过**（子代理是模型输出，不直接采信）。**无"高"severity**
—— 没有"无论怎么写都违反一半"的条目。以下逐条裁定，**优先于计划原文**。

## R-48-a｜`mainRidges` 要有**上界**（★ 必须就地改，否则新用例在干净基线上就是红的）

`:1571` 只写了 `if (mainRidges < 1) throw …`，**无上界**；而 `:1599` 的 `mainRidgesFiveThrows`
要求 `mainRidges = 5` 抛，spec `:669` 同（「传 `mainRidges = 5` ⇒ **必须抛异常**（不是静默夹到 2）」）。
同一任务的 `mainRidgesTwoIsAccepted`（`:1600`）**反证合法上界就是 2**。

⇒ **合法区间 `[1, 2]`，越界抛**。校验写成 `if (mainRidges < 1 || mainRidges > 2)`。
**不夹取**（宁抛不静默，与 `TerrainType` 同族口径）。

## R-48-b｜Task 4 的 Test 段漏了 `EdgeTagsTest`（★ 就地改）

Step 4 的用例清单里有 `preservesInsertionOrder`（`:1123`），Step 5 的变异表拿它当判据（`:1134`），
但 Files 的 Test 段只有 `EdgeRefTest` 与 `PathwayTest`（`:1004`）。⇒ **Files 补
`pathway/EdgeTagsTest.java`**。

## R-48-c｜`PathwayGroup` 的形状：**照老仓，不编造**（★ 就地改）

Step 2 标题点名要写 `PathwayGroup`（`:1049`），但正文只给了 `EdgeTags` 与 `PathwayId` 两份定义；
Task 5（`:1227` `Map<String, PathwayGroup>`）、Task 6（`:1360` `FieldDelta<PathwayGroup>`）、
Task 7（按名分派要为它造合法新值）都当它已存在 ⇒ **必须补形状**。

spec §5.4 只说它是「类型定义，如 river/road」，**没给字段**。故**照老仓**（`MapData.java:451` 实测）：

```java
public record PathwayGroup(String id, String name, String color, String description,
                           boolean visible, Map<String, PropertyDef> properties) {}
// 老仓默认两组（MapData.java:469 defaultPathwayGroups）：river 河流 #3295D2 / road 道路 #8B7355
```

★ **两处必须改口径**（老仓是静默兜底，本项目禁）：
1. 老仓构造器 `if (id == null) id = "";` 等**静默填空**⇒ 新实现**抛**（同 `TerrainType`）。
2. `properties` 的值类型 `PropertyDef` 是**二层嵌套 record**。**本任务先按老仓建**（`PropertyDef(String type, Object defaultValue, String description)`），
   若 Task 7 的反射枚举因此变复杂，**如实报告**，不许为省事偷偷把 `properties` 删掉。

## R-48-d｜Task 5 的 `Modify HexGrid.java（补内容访问）` 是**残留**（★ 就地删）

`:1152` 列了它，但 Task 5 六个 Step **一处也没提 HexGrid**，用例清单里也没有"内容访问"的用例。
`:277` 的解释是"Task 1 只交付纯几何、`HexCell` 在 Task 5 才补"——而实测 `GameMap`（`:1222`）
**自己持有 `Map<HexCoord, HexCell> hexes`**，根本不经 `HexGrid`。

⇒ **删掉那一行**；`HexGrid` **保持纯几何**（内容归 `GameMap` 自己的 map）。
同时更正自审表 `:2076`（它声称"已写进 Task 1 Step 3"，实测 Task 1 的 Step 3 只写了"不引入 `HexCell`"）。

## R-48-e｜Task 8 的"收紧 spec"要写清**由谁保证**（★ 就地改）

`:1528-1530` 说 `GameMap.empty()` 与 `MapChangeSet.apply` 里"都可能写着 `null` spec"，要求逐处改掉，
并加 `specIsNeverNullAfterTask8`；但 **Step 3 的用例清单（`:1597-1609`）里没有这条**，五个 Step 也没承接。

⇒ 裁定：
1. **Task 5 的 `empty()` 起就写非 null**（`GenerationSpec.defaults(<seed>)`），不是 Task 8 才收紧。
2. **Task 6 的 `apply` 逐组件从 target 重建**（铁律 5 本就要求），故 spec 天然非 null。
3. **Task 8 仍加 `specIsNeverNullAfterTask8`** —— 它是**守卫**（钉住"spec 从不 null"这条不变量），
   不是"收紧动作"本身。写进 Step 3 清单。
4. Task 8 的 Files **补 `Modify: …/change/MapChangeSet.java`**（Task 6 若真写了 null 兜底才要改；
   实现者按实际情况决定改不改，但**必须先去看一眼**）。

## R-48-f｜★ `keyOf = toString()` 与 ID record 的**往返**（最重要的一条）

`:1379` 定："`keyOf` **必须是 `toString()`**，且 `apply` 侧用对应的 `parse` 还原"。
但实测 `PathwayId`（`:1066`）、`CityId`（`:1191`）、`RegionId`（Task 3）都是
`record X(String value)`，**既不覆写 `toString`、也没有 `parse`** ⇒ Java 默认输出是
`PathwayId[value=abc]`，apply 侧**无 parse 可用** ⇒ **往返断掉，`applyRebuildsTargetExactly` 会红**。

⇒ 裁定（**每个 ID 类型各自负责**，与全局约束 `:64-67`「没有冻结用例的手写 `toString` 算违规」一致）：

| 类型 | 归属 | 要求 |
|---|---|---|
| `HexCoord` | Task 1（已完成） | 已有 `toString()`=`"q_r"` + `parse`，**已有冻结串用例** ✓ |
| `RegionId` | Task 3（**在跑**） | ★ 补 `toString()`=裸 `value` + `static parse(String)` + **冻结字面量往返用例** |
| `PathwayId` | Task 4 | 同上 |
| `CityId` | Task 5 | 同上 |
| `EdgeRef` | Task 4 | 已有手写 `toString`（`a + "|" + b`）⇒ **补 `parse` + 冻结字面量用例**（现在只有 `toStringIsStable`，比较两个构造方向，**无冻结串**，按全局约束算违规） |

★ `toString()` 一律**裸值**（`RegionId` → `"r1"`，不是 `RegionId[value=r1]`）——它是**地址**，不是调试输出。
★ `EdgeRef` 的 Javadoc `:1035` 写着"**只在 JSON 边界使用**"，与 `:1380`「`"q_r"` 唯一被允许出现的地方」
（变更集 key）**对不上** ⇒ 改成"**作为变更集 key 与 JSON 边界两种用途**"。

## R-48-g｜Task 7 补一条组件数断言（spec `:647` 明文要求）

spec §9.1b 的 U2 守卫写「反射断言 `MapChangeSet` 的组件数**仍是 7**」，Task 7 只有正/反两个方向的
逐组件对应 + 豁免集钉死，**没有组件数断言**。（子代理标"待核"，控制器核实：确实没有。）

⇒ **补一条**：反射断言 `MapChangeSet` 的 record 组件数 **== 7**。这与反方向的逐组件对应**不重复**：
反方向只保证"变更集的组件在 `GameMap` 里有同名者"，挡不住"两边**同时**多一个同名的第 8 个组件"。

## R-48-h｜Task 7 的 V1~V5 应为 **V1~V6**（★ 就地改）

`:1506` 写 `V1~V5 的"改前"必须真的跑在当前 HEAD 上`，而 `:1489-1496` 的表是 **V1~V6** 六条。
⇒ 六条都要跑"改前"基线（V6 是"往豁免集里加 `edges`"—— **豁免口不是洞**，同样要证改前是绿的）。

## R-48-i｜Task 6 的 `terrainTypes` 变异要**配对**（待核转确证）

`:1402` 的变异（`between` 里删掉 `terrainTypes` 的比较）声称"该组件漂移出去时测试会响"，
但 Step 3 清单（`:1384-1395`）的三条 `betweenDetects*` **全是 hexes** —— 删掉 `terrainTypes` 比较，
这三条**照样绿**，红的是 Task 7 的反射枚举。

⇒ **不许**把它当成"Step 3 用例的判别力"。要么让 `betweenDetects*` 里**真的有一条动 `terrainTypes`**
（推荐：加 `betweenDetectsChangedTerrainType`），要么在报告里**明写红的来自 Task 7 的反射枚举**。

## R-48-j｜`GenerationSpec.defaults` 的签名：`defaults(long seed)`，改 spec

spec `:487` 写 `defaults()`（无参），计划 `:1577` 写 `defaults(long seed)`。**seed 无法有默认值**
⇒ **以计划为准**（`defaults(long seed)`），**spec 就地更正**。

---

## 给派单的用法

Task 4~8 的**每一份派单**都要：
1. 附上本文件路径，声明**优先于计划原文**；
2. 只把**与该任务相关**的那几条裁定逐字抄进派单（不要整份塞）。

★ 本文件与 `task-3-rulings.md` 是同一批工作：**派单前扫出缺陷**比**派单后返工**便宜一个数量级。
这不违反用户"评审体量不得压过代码"的红线 —— 它是**计划期的静态检查**，不是**代码期的评审装置**。
