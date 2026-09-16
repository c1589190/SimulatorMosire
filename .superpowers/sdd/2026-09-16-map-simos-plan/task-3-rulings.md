# Task 3 控制器裁定（2026-09-17）—— `region` 包

派单书（`task-3-brief.md`）里有一处**自相矛盾**，必须先裁：`RegionBoundary(List<List<HexCoord>> rings)`
的环元素是 **`HexCoord`（格）**，但 Step 4 又要求 `singleHexRingHasSixVertices`（单格边界 **6 个顶点**）。
格与顶点是两套东西，不可能都对。以下 R-3a~R-3h 是裁定，**优先于派单书原文**。

## 判据来源（实测，不是推测）

老仓的权威算法已找到并读过：`TerrainGeometry.hexSetToBoundaryWithHoles`（`~/DevMosire/GSimulator`，
`gsim-map/src/main/java/com/gsim/map/service/TerrainGeometry.java:267`）。实测确认：

- **环是格角顶点，不是格**。注释原文：「Reconstruct ALL boundary loops from a hex set — outer ring + hole rings」，
  且注释写着「Each ring is a closed polygon (first point == last point)」、「Use with Canvas **evenodd** fill」。
- 算法：逐格逐边，**邻居不在集合里 ⇒ 该边暴露**，取该边的两个端点（`c1 = d`、`c2 = (d+1)%6`）成段；
  再按顶点建邻接图、走环。`CompressionService.java:88` 调它，存进 `CompressedRegion.boundaries()`（`List<List<Pt>>`）。
- `DIRS = {{1,0},{0,1},{-1,1},{-1,0},{0,-1},{1,-1}}`（`:33`）与**本仓 `HexDirection` 的枚举序逐项相同**；
  `hexToPixel(q,r) = (SIZE*(√3 q + √3/2 r), SIZE*(3/2 r))`（`:42`）与本仓口径一致。
  源码注释亦写明「Order must match corner angles (60*i - 30°)」。
- ★ 老代码用 `cornerKey(x,y) = Math.round(x*1000) + "_" + Math.round(y*1000)`（`:357`）当顶点身份 ——
  **拿浮点舍入当身份**。同一个顶点由不同格中心算出时可能落在 `.5` 两侧而**对不上键**，环就断了。
  **这是本项目最忌讳的形态，新实现不得沿用。**

⇒ 派单书里 `singleHexRingHasSixVertices`、`twoAdjacentHexesShareOneRing`、`nonContiguousHexesGiveMultipleRings`
三个名字**全部与"环是顶点"一致**，只有那个 `List<List<HexCoord>>` 类型是错的。故按下裁定。

---

## R-3a｜环的元素是**格角顶点**，新增 `HexVertex`

新建 `simos-map/src/main/java/io/mosire/simos/map/hex/HexVertex.java`（**放 `hex` 包**，不是 `region` ——
它是格几何原语，与 `HexCoord`/`HexDirection` 同族；将来的等高线渲染也要用）。

```java
/** 格角顶点。**整数标签，全格唯一** —— 同一个顶点由相邻三格中任一格算得的值必须完全相同。 */
public record HexVertex(int u, int w) implements Comparable<HexVertex> {}
```

**不新增 `HexCorner`/`Pt`/重用 `HexCoord`**：`Pt` 是像素点（老仓的渲染类型），`HexCoord` 是格。
**一个词指两样东西是本项目明文禁止的**（见 spec §五 `PathwayGroup` 那条）。

## R-3b｜`(u,w)` 的整数标签定义（★ 这是我的**推导**，不是实测 —— 见 R-3f 的验证要求）

把像素坐标**各向异性地**缩放成整数：`u = x / (size·√3/2)`，`w = y / (size/2)`。于是

```
格 (q,r) 的中心 → (2q + r, 3r)
格 (q,r) 的第 i 个顶点 → (2q + r + U[i], 3r + W[i])
```

`U/W` 取自 `60i-30` 度的角偏移（`cos` 乘 2/√3、`sin` 乘 2），**六项常量表**：

| i | 角度 | U[i] | W[i] |
|---|---|---|---|
| 0 | −30° | 1 | −1 |
| 1 | 30° | 1 | 1 |
| 2 | 90° | 0 | 2 |
| 3 | 150° | −1 | 1 |
| 4 | 210° | −1 | −1 |
| 5 | 270° | 0 | −2 |

★ **`(u,w)` 是标签，不是坐标**：x 与 y 的缩放系数不同（1 与 1/√3 之比），故它**不是相似变换**，
**不要拿它算距离或角度**。它只承诺两件事：① 同一顶点的标签唯一且与算法路径无关；② 有全序，可用于规范化。
（这正是替掉老代码 `Math.round(x*1000)` 的理由。）

★ **边的方向序号与顶点序号的对应**：`HexDirection` 的第 `d` 条边，两端点是第 `d` 与第 `(d+1)%6` 个顶点。
已对表验证：`HexDirection` 枚举序 = 老仓 `DIRS` 序；`E` 即 0° 方向，其边的两端在 −30° 与 +30° 即顶点 0 与 1。

## R-3c｜**不做** size < 3 短路

老代码 `if (hexSet == null || hexSet.size() < 3) return List.of();`（`:268`）是**渲染期的多边形下限**，
不是几何事实：单格的边界本来就是正的六边形。**新实现不沿用**：

- 空集 → `rings = []`
- 单格 → **1 条环、6 个顶点**（`singleHexRingHasSixVertices` 成立）
- 相邻两格 → **1 条环、10 个顶点**（`twoAdjacentHexesShareOneRing` 成立）
- N 个互不相邻的单格 → N 条环、各 6 个顶点

★ 这是一处**有意的行为偏离**，要写进报告与 Javadoc，免得后人拿 GSimulator 对表时以为丢了东西。

## R-3d｜规范化在 `RegionBoundary` 的**紧凑构造器**里做，`of()` 只管造

`RegionBoundary` 是 `Region` 的组件、是**存储的**，所以它必须是**值类型**：内容相同 ⇒ `equals` 为真。
把规范化放进紧凑构造器（幂等），`of()` 就只需老实收集，不必操心顺序：

1. 每条环：**旋到字典序最小的顶点开头**
2. 每条环：与其反向序列比，**取字典序较小者**（⇒ 绕行方向被钉死；反序列化进来的非规范环会被就地归一）
3. 环表：**按各自的首顶点字典序排序**

★ 第 2 步让"绕行方向"变成规范的，但它**没有几何含义** —— 消费端用 `evenodd` 填充（老仓如此），
绕向不影响结果。**不要**在 Javadoc 里把它说成"顺时针"。

★ 规范化**不移动任何顶点、不改变环的内容**，只改表示。故它是内容保持的。

## R-3e｜`of(Set<HexCoord>)` 必须是**纯函数**，且顶点度为 2

算法：逐格逐边扫出暴露边 → 按 `HexVertex` 建邻接 → 走环。**入参 Set 的迭代序不得影响结果**
（`hexes` 是 `Set.copyOf`，不保序；而 `Region.equals` 依赖 `boundary`）。

★ **顶点度恒为 2**（每个边界顶点恰有 2 条暴露边）——**这是我的推导**：六角格每个顶点恰有 3 格、3 条边；
设这 3 格中有 k 格属于本区域，则暴露边数 = k(3−k)，k=1 或 2 时都得 **2**，k=0 或 3 时得 0。
**故不存在"岔路口"，走环无需转向规则。**

★ **这条推导必须变成代码里的护栏**：走环时若发现任何顶点的度 ≠ 2，**抛异常**（宁抛不静默），
不许"取第一个未访问邻居"糊过去。这条护栏**要自证**（见变异表 m3v-4）。
★ 若它真的抛了，说明我的推导错了 —— **如实报告，不要绕着改**。

## R-3f｜实现者必须先验证 R-3b 的偏移表

偏移表是控制器推导的。**落码前先用一条用例把它钉住**：相邻两格（如 `(0,0)` 与 `(1,0)`）的顶点集合
**交集恰为 2 个**，且 `(0,0)` 的 6 个顶点**互不相同**。这两条不过 ⇒ 表错了 ⇒ **报告，别自己凑数**。

## R-3g｜`RegionIndex`

- `of(Collection<Region>)`：按 `RegionId` **字典序**遍历，`putIfAbsent` ⇒ 重叠时**先到者胜**、且确定性
- `regionOf` 无归属返回 `null`；`hasRegion` 独立给出
- ★ `regionOfIsConstantTime` 的**承重断言是"只触发一次 `get`"**（用一个计数 `Map` 包装层）。
  派单书里"1000 hex 索引与 1 hex 索引结果相同"只是冒烟检查，**不算护栏**，别拿它充当。

## R-3h｜不写的东西

- **不写** `boundaryIsDerivedNotStored` 或任何"边界不进存储"的断言 —— U2 已推翻，现在**恰好相反**。
- **不写** "GameMap 不含 RegionIndex" —— `GameMap` 在 Task 5 才存在，该断言归 Task 5，**只写一处**。
- 不新增 `MapChangeSet` 组件（边界随 `Region` 走）。

---

## 变异表（在派单书 Step 5 基础上增补）

派单书原表 7 行**全部保留**，另加：

| 变异 | 期望 | 证明什么 |
|---|---|---|
| **m3v-4** 走环时删掉"度 ≠ 2 则抛"那条护栏 | **红** | R-3e 的护栏有判别力（用一个度≠2 的人造顶点集触发；若造不出来，**报告 R-3e 的推导可能错了**） |
| **m3v-5** `HexVertex` 的 `W[i]` 表里把 `i=1` 的 `1` 改成 `-1` | **红** | 偏移表被钉住了（相邻两格共享顶点数不再是 2） |
| **m3v-6** 紧凑构造器里删掉"环表排序"那一行 | **红** | 环表规范序有判别力（构造两个内容相同、环的收集顺序不同的边界） |
| **m3v-7** 紧凑构造器里删掉"取字典序较小方向"那一行 | **红** | 绕向规范化有判别力（用同一批顶点、正反两种输入的环构造） |

★ 每条变异体都要**先自证**（编原件比 md5，确认字节不同再看结果），**按目标类名**推入，
每轮**强制断言 `grep -c "COMPILATION ERROR"` 为 0**；**每轮开跑前把 `target/classes` 恢复成干净世界**
（旧 `.class` 会活到下一轮 —— CLAUDE.md 形态 1 第六例）。
★ **红了的理由必须是被保护的那一行本身**：派单书 Step 5 第 2、3 行明确写了"红来自断言"与"红来自异常"
两种不同理由，**报告里要分开写**。
