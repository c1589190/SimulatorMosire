# MapSimos 模块设计（M2 spec）

**日期**：2026-09-16
**状态**：待用户评审
**上游**：`docs/superpowers/specs/2026-09-16-simos-master-design.md`（总纲，已批准）
**范围**：MapSimos（`simos-map`）的全部公开类型与语义。不含 Social/Unit 的领域实现，不含 Core 的存储与时间引擎。

**本 spec 的事实基础**：四路对 GSimulator（本机 `~/DevMosire/GSimulator` @ `88d0f02`）的**只读取证**，
交付件 `m2-recon-{A,B,C,D}-*.md`（工作区内，共约 138KB，逐条带 `文件:行`）。
凡本 spec 引用 GSimulator 现状处，**均为那四份取证的实测结论**；凡属**推导**的，正文内明写"推导"。

---

## 〇 已裁决记录（本 spec 的输入）

### 〇.0 ★ 用户裁决（2026-09-16，**推翻控制器两处**）

控制器先自行裁决了五项待决，用户评审时**推翻其中两处**，并加了三条决定。**用户裁决优先**，
下表（〇.1）中与本节冲突处**以本节为准**。

| # | 议题 | 用户裁决 | 被推翻的控制器裁决 |
|---|---|---|---|
| U1 | **地形词表** | **7 项**：海洋、平原、沙漠、低矮丘陵、山地、平缓高原、高原山地。**按高度从小到大**，**各有不同特性** | 控制器原裁"以 GSimulator 落盘的 9 项为准"（water/lowland/plains/hills/mountain/forest/swamp/desert/tundra）⇒ **整个 9 项作废** |
| U2 | **边界是否入存储** | ★ **要写存储**。理由：「要不然数据持久化会出问题」 | 控制器原裁"边界降为可随时重算的派生缓存，**不进 `GameMap`、不进变更集**"⇒ **推翻** |
| U3 | **分支策略** | M1 先并入 `main`，再从 `main` 开 `feat/m2-map-simos` | （控制器原倾向即此，非推翻） |
| U4 | **工作区产物** | **全部进仓库**（含两个 `.superpowers/sdd/**` 工作区），理由：「方便从其他地方恢复工作状态」 | 控制器原拟**分类**（侦察件+台账进、评审 diff 不进）⇒ **改为全进** |

**★ U2 的落地方式（控制器给出，用户未指定细节）**：**边界作为 `Region` 的一个组件**，
而不是一个新的顶层状态字段 —— 这样它**自动随 `Region` 持久化、自动往返**，
`MapChangeSet` **不需要新增组件**（`Region` 本来就在变更集里）。
**并且**：`Region` 的规范构造器**校验** `boundary` 等于由 `hexes` 重算的值，不等即抛。
⇒ **存储满足持久化需求，校验使漂移不可能** —— 用户要的东西与控制器担心的事情同时成立。
（用户说的是"要存储"，没有说"可以不与 hexes 一致"；**校验是控制器补的**，若不想要，删掉构造器里那一行即可。）

**★ U1 的一处控制器补裁（用户未指定，需你过目）**：7 项按高度从小到大**各有自己的带**，
但**沙漠额外要求低湿度** —— 否则每张图在那个高度都会长出一圈**沙漠环**。
若你要的是"沙漠与高度无关、纯由气候决定"，改一行阈值即可。

### 〇.1 控制器的裁决记录

总纲 §十三 给 MapSimos 列了**五项待决**。逐条裁决如下。

| # | 议题（总纲 §十三） | 裁决 | 出处 |
|---|---|---|---|
| 1 | 六边形数据结构的最终形态 | **axial `(q,r)` + `HexCoord` record 作身份**；容器 `Map<HexCoord, HexCell>`；**单一 `HexDirection` 枚举（6 项）取代现存 8 份方向表**；**删 `gridSize` 与 `hexOrientation`**（前者是恒 30 的死值、不参与取格；后者恒 `false` 且无读取分支，与实际 pointy-top 公式矛盾） | 本会话裁决 |
| 2 | `Region` 如何统一"三个旧概念" | ★ **实测旧概念是 4 活 + 1 死，不是 3 个**（见 §四）。裁决：**只保留一个权威 `Region`**（`RegionId` + `name` + `Set<HexCoord>` + 元数据）；~~闭环边界降为可重算的派生缓存~~ **← 已被 U2 推翻，边界改为存储且校验**（见 §4.3）；**`CompressedRegion` 概念整体取消**（缓存不是状态）；**`ContourLayer` 归生成参数**、**`TerrainBlock` 归编辑 Command** | 本会话裁决，**边界部分经 U2 修正** |
| 3 | 连通性稳定 ID 的生成规则 | **边不需要 ID**（无序 `HexCoord` 对即身份，`EdgeRef` 一个类型取代 4 份字符串实现）；**线（`Pathway`）需要真 ID**，且 **ID 是分配并持久化的，不由内容派生**；**分支点即端点**，分支处断成多条独立线 | 本会话裁决 |
| 4 | 生成算法的参数面 | **参数对象化 `GenerationSpec`**，存量的 ~60 个方法体内魔法数字**全部提取为字段**并集中给默认值；**删两个装饰形参**（`worldId`、`coastRoughness`）；**`ridges` 的静默硬夹改为构造期校验抛异常**；**`landRatio` 改名 `baseSeaLevel`**（它实际只影响这一个数）；**seed 必须落盘**、**海拔必须落盘**；**地形词表唯一化** | 本会话裁决 |
| 5 | `MapChangeSet` 的字段清单 | **变更集与 `GameMap` 的 record 组件一一对应**，且**往返测试用反射枚举 `GameMap` 的全部组件**逐组件制造差异 —— **新增状态字段若不进变更集，测试自动红**。这是铁律 5 的机械化落地，见 §七 | 本会话裁决 |
| 附 | `TerraType` 的命名与字段 | 总纲 §5.1 写 `TerraType`（`color`/`height`/`pass`/`name`）。**实测 GSimulator 无 `TerraType` 这个名字**（全仓零命中）。裁决：**用 `TerrainType`**（`TerraType` 不是词）；**10 字段**见 §6.1，其中 **`minHeight`/`maxHeight` 就是总纲说的 `height`** —— 控制器原裁"海拔只是格子的属性"**理解窄了**，见 §八 第 1 条。**逐格的海拔值仍在 `HexCell`** | 本会话裁决，**字段部分经 U1 修正** |

**裁决之外的首次定义**集中在 §八（偏离总纲草案清单），**评审重点在那**。

---

## 一 范围与判据

### 1.1 交付物（总纲 §十一 M2）

六边形网格、`TerrainType`、`Region`、统一连通性系统（稳定 ID）、`GameMap`、地形生成
（从头生成 + 框选随机化 + 自动河流）、`MapChangeSet`、`map:` 寻址实现。

### 1.2 关账判据

| 判据（总纲原文） | 本 spec 的落点 |
|---|---|
| GSimulator 的 **L1~L9 逐条**有对应用例 | §九 测试清单逐条列出 L1~L9 的守卫用例 |
| **框选随机化**与**自动河流**各有验收 | §6.5 / §6.6，各自的验收见 §九 |
| `./mvnw verify` 绿（Spotless + Checkstyle + SpotBugs + Surefire） | §九 |
| 每条新护栏都有故意违规用例自证会响（G13） | §九，尤其是 §7.4 的"反射枚举组件"护栏 |

### 1.3 依赖与约束（总纲 §3.1 / §10.2）

- **只依赖** `simos-util`（外加 test scope 的 JUnit 5 / AssertJ）。
- **永不** import `simos-social` / `simos-unit` / `agentlib-mosire`。由 `maven-enforcer-plugin` 构建期强制。
- **不做任何存储**（用户明确要求）。存储上收到 CoreSimos。
- **不得出现其他模块的领域词汇**：`population` / `unit` 只许出现在注释里。
- ★ **enforcer 管不到"代码里出现了领域词汇"** —— 那是 review 的事，不是构建的事。

### 1.4 本 spec 必须承接的跨里程碑约束（M1 关账时补记）

**任何含 `ADD` 事件的 `TemporalSeries`，一律用模块级 `static final` 的 `addition` 构建。**
`SegmentedSeries` 是 record，`addition` 在 `equals` 里按**身份**比较（函数没有结构相等）；
各写各的 lambda 会让两个结构相同的序列**不相等**，从而让往返断言以"序列不相等"这种费解形态**假红**。
⇒ **`MapChangeSet` 若含时态序列（M2 初版不含），其测试必须照此办理。**

---

## 二 包结构

```
io.mosire.simos.map
├── hex/          HexCoord, HexDirection, HexGrid, HexDistance（六边形几何）
├── terrain/      TerrainType, TerrainCatalog, HeightField（地形与海拔）
├── region/       RegionId, Region, RegionMeta, RegionBoundary（区域）
├── pathway/      PathwayId, Pathway, PathwayGroup, EdgeRef, EdgeTags（连通性）
├── map/          GameMap, CityId, City（地图状态）
├── change/       MapChangeSet, FieldDelta（变更集）
└── generate/     GenerationSpec, MapGenerator, TerrainClassifier, RiverBuilder, RegionRandomizer
```

`map:` 寻址实现放 `io.mosire.simos.map.resolve`（实现 `simos-util` 的 `Resolver` SPI）。

---

## 三 六边形几何（待决项 1）

### 3.1 坐标系与身份

GSimulator 现状（侦察 A 实测）：axial `(q,r)` 整数；**坐标不是字段**，只作 `Map<String,HexCell>` 的键
（格式 `"q_r"`，`MapData.java:113/36`）；**不是二维数组**；cube 只作中间量（`s = -q-r`）。

**铁律 1 说"地址是定位方式，ID 是身份"。** `"q_r"` 字符串是**地址**，不该兼任**身份**。
⇒ 裁决：

```java
/** 轴向坐标。六边形格的唯一身份。 */
public record HexCoord(int q, int r) implements Comparable<HexCoord> {
  public HexCoord { /* 无归一化：每个 (q,r) 就是一个格 */ }
  /** cube 第三轴，恒等式 q + r + s == 0。 */
  public int s() { return -q - r; }
  @Override public int compareTo(HexCoord o) { /* 先 q 后 r */ }
}
```

- **容器**：`Map<HexCoord, HexCell>`。`HexCoord` 是 record ⇒ `equals`/`hashCode` 由编译器生成，无手写遗漏风险。
- **`"q_r"` 降级为纯序列化形式**：`HexCoord.toString()` 与静态 `parse(String)` 是它的**唯一**两份实现，
  且**只在 JSON 边界**使用。GSimulator 那份 `TerrainGeometry.hexKey`（`:100`）与 `MapData.hexKey`（`:113`）
  的重复实现随之消失。
- **不做二维数组**：地图是稀疏的（实测 `n0000_map.json` 4921 hex），且 `gridSize` 不参与取格（见 §3.3）。

### 3.2 单一方向常量表（**总纲 §5.1 的"单一方向常量表"**）

GSimulator 现状（侦察 A 实测）：**全仓 8 份方向表**（6 Java + 2 JS），归为**两种互逆索引序**：

| 序 | 代表 | 绕序 | 索引 0..5 |
|---|---|---|---|
| A | `TerrainGeometry.DIRS` | 顺时针 | E, SE, SW, W, NW, NE |
| B | `MapService.HEX_DIRS` | 逆时针 | E, NE, NW, W, SW, SE |

两表**集合完全相同、只是绕序相反**（脚本实测 `B[i] == A[(6-i)%6]` 对全部 i 成立）。
**精确错位集合是 `{1, 2, 4, 5}`，索引 0 与 3 一致** ——（总纲写"索引 1-4"，**应校正**，见 §八）。
**根因是"无共享入口"**：两张表**都是 package-private、都不导出公共 API**，消费方只能各自复制，
**无编译期一致性约束**。

⇒ 裁决：

```java
/** 六条边的方向。索引即边序号，全模块唯一。 */
public enum HexDirection {
  E(1, 0), SE(0, 1), SW(-1, 1), W(-1, 0), NW(0, -1), NE(1, -1);
  private final int dq, dr;
  public int dq(); public int dr();
  /** 反向边：(d + 3) % 6 —— 由枚举序保证，不是巧合。 */
  public HexDirection opposite();
  /** 相邻方向：(d + 1) % 6 / (d + 5) % 6。 */
  public HexDirection next(); public HexDirection prev();
  public static final List<HexDirection> ALL = List.of(values());
}
```

- **采用 A 序**（E, SE, SW, W, NW, NE）。理由：A 序是**位掩码兼容序** ——
  GSimulator 的 `riverMask`（`MapData.java:179`）明写 `bits 0-5 for edges E,SE,SW,W,NW,NE`，
  前端 `DIR_VECTORS`（`state.js:3`）也是 A 序，且该链**自洽**（侦察 A 逐处核过）。
  选 B 序会把这套位序和前端一起推翻，**收益为零**。
- `opposite()` / `next()` / `prev()` **由枚举序派生**，不再有第二份表可写错。
- **枚举取代了 `int[][]`** ⇒ 越界索引从"运行时读到错的偏移"变成**编译期不可能**。

### 3.3 删 `gridSize` 与 `hexOrientation`

**`gridSize`**：实测只做构造期范围校验（`MapData.java:48-49`），**不参与取格**；
且 `MapData.empty()` 与 `ContourQueryEngine.materialize` 都**写死 30** —— 而生成参数 `mapRadius` 默认是 **80**。
⇒ 它既不是网格尺寸、也不是半径，**是个恒 30 的死值**。
**裁决：删除。** 地图范围由 hex 集合导出（`HexGrid.minQ()/maxQ()/minR()/maxR()`）。

**`hexOrientation`**：实测**写死 `false`**（`MapData.empty()`、`materialize`、前端 `events.js:162/276`），
全仓**只有构造器透传、无任何读取分支**；而实际像素公式（`TerrainGeometry.java:42-46`）是 **pointy-top** 形式
⇒ **字段值与几何矛盾**。
**裁决：删除。** 若将来真要 flat-top，那是**另一个 `HexGrid` 实现**（不同的邻居与像素公式），
**不是一个 boolean 开关** —— 用一个 boolean 表达它，正是它今天既死又错的原因。

### 3.4 一份距离、一份取整

GSimulator 现状：距离函数 **4 处**（`MapService:939` 唯一 public、`LassoProcessor:177`、`GsimapEdgeListTool:87`、
前端 `hex-math.js:46`），**公式代数恒等，无口径分歧**（侦察 A 实测）；`hexRound` **3 份逐字复制**。

⇒ 裁决：`HexCoord.distanceTo(HexCoord)` 一个实现（cube 曼哈顿 `(|dq|+|dr|+|ds|)/2`）；
`HexCoord.round(double q, double r)` 一个实现。**前端不再有第二份**（前端所需的数值由 Core 侧提供，
或前端只做渲染不算几何 —— 见 §八开口项）。

### 3.5 无硬编码邻居偏移

好消息（侦察 A 实测）：GSimulator **全仓无硬编码邻居偏移**（`q+1`/`r-1` 之类只命中 URL 解析与视口外扩）。
**这条要保持**：新代码里任何邻居计算一律走 `HexDirection`。

---

## 四 Region（待决项 2）

### 4.1 ★ 现状是 4 活 + 1 死，不是 3 个

侦察 B 实测（它明写"只报实测清单，不替你裁定"）：

| # | 类型 | 定义处 | 状态 | 要害 |
|---|---|---|---|---|
| A | `MapData.Province` | `MapData.java:294-314` | 活 | ★ **无 name 字段**（名字是 `provinces` map 的键）；★ **无边界字段**（闭环边界由前端现算 `render.js:244`） |
| B | `MapData.CompressedRegion` | `MapData.java:498-537` | 活 | 渲染缓存，**可随时重算** |
| C | `ContourLayer` | `ContourLayer.java:12-59` | 活 | 地形编辑层，**无 hexKeys** |
| D | `MapData.TerrainBlock` | `MapData.java:139-158` | 活 | 旧地形块（笔刷/套索产物） |
| E | `service.CompressedRegion` | `service/CompressedRegion.java:14-23` | **死代码** | `git grep` import 零命中 |

`territory` / `Area` 概念**全仓不存在**（实测）。四者之间**只有两条单向转换**
（`hexes→CompressedRegion`、`ContourLayer→hexes`）；★ **`Province` 与其余三者之间零转换代码**。

### 4.2 裁决：一个权威 `Region`

```java
public record RegionId(String value) { /* 非空、非空白 */ }

public record RegionMeta(String color, String tag, String description, String annexedBy) {}

/** 权威区域：一组 hex 的命名集合，加它的闭环边界。 */
public record Region(RegionId id, String name, Set<HexCoord> hexes,
                     RegionBoundary boundary, RegionMeta meta) {

  /** 规范构造器：★ **校验 boundary 等于由 hexes 重算的值**，不等即抛（U2 的落地，见 §4.3）。 */
  public Region { /* … */ }

  /** ★ 正常代码走这个工厂：边界由 hexes **算出来**，不手写。 */
  public static Region of(RegionId id, String name, Set<HexCoord> hexes, RegionMeta meta) { /* … */ }
}
```

- **`id` 与 `name` 都要有。** GSimulator 的 `Province` 把名字当 map 的键 —— 那是**用地址当身份**，
  改名就要重建键，正是铁律 1 要消灭的形态。
- **`hexes` 是权威内容**，`Set<HexCoord>`（GSimulator 是 `List<String>` ⇒ 线性 `contains`，
  且这个线性扫描在 **6 处逐字重复**，见 §4.4）。
- **`boundary` **入这个类型**（U2）** —— 它是**存储的**，但**由 `hexes` 校验**，见 §4.3。
  ⇒ **`Region.of(...)` 是代码的正常入口**（边界算出来）；规范构造器留给反序列化器，
  它接受外部传入的边界**并当场验伪**。
- ★ **`withHexes` 必须重算边界** —— 这是 `boundary` 成为组件后**最容易写错的一处**，
  §九 有专门用例钉它。

### 4.3 边界**入存储，且被校验**（★ U2 推翻了控制器的原裁）

**控制器原裁**：边界降为可随时重算的派生缓存，**不进 `GameMap`、不进变更集**。
理由是 GSimulator 把渲染缓存塞进 `MapData` 的教训（整份拷贝而非增量，
侦察 C 实测 `MapDiff.java:143`），且 `TerrainBlock` 里那份 `boundary` 已被标注 `deprecated`。

**★ 用户裁决 U2 推翻它**：「**边界还是要写存储的，要不然数据持久化会出问题**」。

**⇒ 两者如何同时成立**（★ **控制器给的落地方式，用户未指定细节**）：

```java
/** 由 Region.hexes 计算出的闭环边界。**持久化，且与 hexes 的一致性被构造器强制。** */
public record RegionBoundary(List<List<HexVertex>> rings) {}
```

★ **类型校正（M2 Task 3 执行期，2026-09-17）**：本行原写作 `List<List<HexCoord>>` —— **那是错的**。
派的单里 `RegionBoundary(List<List<HexCoord>>)` 与测试名 `singleHexRingHasSixVertices`（单格边界 6 个**顶点**）
**自相矛盾**：`HexCoord` 是**格**，环的元素是**格角顶点**，两套东西。实测老仓的权威算法确证环是顶点：
`TerrainGeometry.hexSetToBoundaryWithHoles` 逐格逐边收集暴露边、取该边**两个端点**成段再串环，
注释写明「Each ring is a closed polygon」、「Use with Canvas **evenodd** fill」。
⇒ 新增 `hex.HexVertex(int u, int w)`（整数标签，全格唯一）作为环元素；详见 M2 计划 Task 3 的执行期校正。

**① 边界作为 `Region` 的组件，不是新的顶层状态字段。**
⇒ 它**自动随 `Region` 持久化、自动往返**，`MapChangeSet` **不需要新增组件**
（`Region` 本来就在变更集里，见 §七）。用户的"要存储"由此满足。

**② `Region` 的规范构造器校验 `boundary` 等于由 `hexes` 重算的值，不等即抛。**
⇒ **漂移不可能**。控制器原来担心的"缓存与权威不同步"由此被**堵死在入口**，
而不是靠"不存储"来回避。

**★ 为什么校验比"不存储"更好**（这一条是控制器在 U2 之后补的论证，不是原裁）：
用户点出的持久化问题是真的 —— **若边界只活在计算里，则任何消费存档的一方
（前端、外部工具、M6 的导入器）都必须自己再实现一遍推导**。
⇒ **把边界写进存档，等于把"推导"这件事收敛到一处**；
而**构造器校验**保证那份写下来的值不会与权威脱钩。**两个目标不冲突。**

★ **本节初稿对 GSimulator 的转述有误，实测更正**（M2 Task 3 执行期，2026-09-17）：初稿写「闭环边界由前端现算
（`render.js:244`），于是同一份几何在 Java 与 JS 里各有一份实现」——**实测不成立**。老仓有**两个不同的东西同名混用**：
- `render.js:370 computeBoundaryHexes`：返回**边界格**（有外邻居的格）、**无序**，只用来在
  `:340-344` 画**调试圆点**（`arc(x,y,5/zoom)`）。**它不是环**。
- `TerrainGeometry.java:267 hexSetToBoundaryWithHoles`：返回**顶点环**，在 **Java** 里算，
  由 `CompressionService.java:88` 存进 `CompressedRegion.boundaries()`，前端只负责 `moveTo/lineTo` 画出来。

⇒ 真正的"闭环边界"**没有**在 JS 里被重新实现；本节的结论（推导应收敛到一处）**依然成立，但理由要换成**：
病在"**边界格**与**顶点环**两个概念共用一个名字"，以及顶点身份靠 `Math.round(x*1000)+"_"+Math.round(y*1000)`
（`TerrainGeometry.java:357`）——**拿浮点舍入当身份**，同一顶点由不同格中心算出时可能对不上键而断环。
新实现用整数顶点标签（见 §4.3 的类型校正）从根上消除后者。

**★ `CompressedRegion` 概念仍然整体取消**（U2 **没有**推翻这一条）：
它是"地形 + 颜色 + 边界"的**渲染打包**，而地形在格上、颜色在地形类型上、边界在 `Region` 上
—— 三样都已有归宿，**打包本身没有信息**。用户要存的**是边界**，不是那个包。

★ **这同时消灭了一整类 bug**：GSimulator 的 `terrainBlocks`/`compressedRegions`/
`terrainTypes`/`pathwayGroups`/`edges` **五个字段全都在"权威 vs 缓存"上含混**，正是 §七 要根治的。

### 4.4 取消 L5 的线性扫描

侦察 B 实测：`Province.hexes` 是 `List<String>` ⇒ `.contains` 线性扫描，**6 处逐字重复**同一段
`for (entry : map.provinces().entrySet()) if (entry.getValue().hexes().contains(key))`：
`GsimapGetHexTool:80`、`GsimapQueryByAddressTool:146`、`:175`、`GsimapResolver:122`、`:154`、`GsimapRenderTextTool:259`。
★ **最后一处不 `break`**（为取字典序最小的名字必须全扫，每次渲染都跑）；
`GsimapResolver.resolveHex:91-144` 意味**每次 `hex:{q}_{r}` 地址解析都全表扫**。

⇒ **`RegionIndex`**：一张由 `Map<HexCoord, RegionId>` 构成的反向索引（内建、随 `GameMap` 派生），
`GameMap.regionOf(HexCoord)` 为 **O(1)**。
★ **它是派生索引，不是状态字段** —— 与 §4.3 同理，**不进变更集**。
（GSimulator 里 `computeAdjacency` 被每个区域各调一次 ⇒ **O(区域数² × 区域 hex 数 × 6)**，
同一病因：没有反向索引。）

---

## 五 连通性（待决项 3）

### 5.1 边：不需要 ID

侦察 B 实测：`edges` 是 `edgeKey → {pathwayId → {prop → value}}`，
`edgeKey` 格式 `"minQ_minR|maxQ_maxR"`（字典序规范序，**确定性**）。
★ **这份逻辑全仓有 4 份实现**：`MapData.edgeKey:390-417`、`MapService.undirectedKey:827-829`、
前端 `pathway.js:452-459`（注释自陈 `Must stay in sync with MapData.edgeKey() in Java`）。

**一条边是两格之间的关系，它的身份就是那两格。** ⇒ 裁决：

```java
/** 无向边。构造期做规范排序，故 (a,b) 与 (b,a) 恒为同一对象。 */
public record EdgeRef(HexCoord a, HexCoord b) implements Comparable<EdgeRef> {
  public EdgeRef {
    if (a.equals(b)) throw new IllegalArgumentException("边不能自环: " + a);
    if (a.compareTo(b) > 0) { var t = a; a = b; b = t; }   // 规范序
  }
}
```

- **规范序在构造期完成** ⇒ 4 份字符串实现与"必须手工保持一致"的注释**全部消失**。
- 序列化时仍可写成 `"q_r|q_r"`，但**类型是权威**，字符串只是它的一个渲染。

### 5.2 线：需要真 ID，且 ID 不由内容派生

侦察 B 实测（★ **这是全次取证里最要紧的一条**）：
- **线段（河/路）只有 groupId，无实例 ID** ⇒ **所有河流共享 `"river"` 一个身份**；
- **链的身份 = 返回列表的下标**（`MapService.java:757` Javadoc；`GsimapEdgeTraceTool:65-70` 按 `i+1` 编号打印）；
- ★ **"一条有名字的河"这个语义，在 `River`/`Road` 废弃后没有新承载结构**
  （旧的有 `name`+`path`，新的 `PathwayGroup`+`edges` 都没有）。

总纲 §5.1 要求：**单条连通性线段可寻址，分支是独立的线**。⇒ 裁决：

```java
public record PathwayId(String value) { /* 非空 */ }

/** 一条极大的简单连通线：两端是端点或分支点。 */
public record Pathway(PathwayId id, String name, String groupId,
                      List<EdgeRef> edges, Map<String, Object> props) {}
```

**★ 分支点即端点**：一条 `Pathway` 是**极大简单链**。度数 ≥3 的格是**分支点**，线在分支处断开
⇒ "分支是独立的线"落地为"分支点把线切成一串"。

**★ `PathwayId` 的生成规则（待决项 3 的核心答案）**：

> **ID 一旦分配即持久化，不由内容派生。**

理由：内容派生（如"端点哈希"）会让**改一个中间节点**变成"换了一条河"，违反铁律 1 的精神
（ID 是身份，不随内容漂移）。具体：
- **生成期**：ID 由 `(generationSeed, 序号)` **确定性派生** ⇒ 同种子同图**可复现**（这是 L7 要的）。
- **编辑期**：ID 由 `Command` 分配并**持久化**；**分裂出的新段拿新 ID**，**缩短不改 ID**。
- **闭环**（无端点）：以 **hex 规范序最小的那格**作锚参与派生。

### 5.3 删掉第二份存储（L2）与废弃类

侦察 B 实测：**第二份是 `HexCell.edgeTags`（`MapData.java:191`）+ 遗留 `riverMask`（`:190`）**，
★ **Java 侧只读不写，只有前端 `pathway.js` 写**；两份之间**没有 Java 侧转换代码**，
只有前端手写投影（`pathway.js:462-490` / `:493-516`），
★ 后者自认 `Props are empty by default — frontend doesn't edit edge properties yet`（`:510`）
⇒ **前端一存就把所有边的 props 抹平**。

⇒ 裁决：
- **`edges`（即新的 `Map<EdgeRef, EdgeTags>`）是唯一主存储。**
- **`HexCell.edgeTags` 与 `riverMask` 全删。** `HexCell` 不再有任何连通性字段。
- **`River` / `Road` 两个 `@Deprecated` record 删除** —— 其语义（`name` + `path`）由 `Pathway` 承载。

### 5.4 命名冲突的消解

`PathwayGroup` / `pathwayId` / `edges` 三者的命名在 GSimulator 里混用（"pathway"既指组又指实例）。
⇒ 新命名：**`PathwayGroup`（类型定义，如 river/road）× `Pathway`（实例）× `EdgeRef + EdgeTags`（边上的标注）**。
三层各司其职，不再有一个词指两样东西。

---

## 六 地形与生成（待决项 4）

### 6.1 `TerrainType` 与唯一词表

侦察 D 实测：**至少 9 份互不相同的地形词表副本**，其中两份在**同一批 key 上完全分叉**：

| key | 词表 A（`MapData.TerrainType.defaults()`，**8 项**） | 词表 B（`MapGenerator.defaultTerrainTypes()`，**9 项**） |
|---|---|---|
| `plains` | `平原` / `#6CC261`（绿） / food 3, gold 1 / "平原" | ★ **`山区`** / **`#B8A88A`（土黄）** / **food 2, gold 2** / "内陆高原/山区…" |

★ **串味铁证**：`ContourQueryEngine.terrainColor` 的 `default -> "#6CC261"` 用的正是**词表 A 的平原绿**。
实测**落盘数据用词表 B（9 项）**。

★ **第二个兜底色 `#5B8C3E`**（M2 Task 2 实测补记，本 spec 初稿只记了 `#6CC261`）：`CompressionService.terrainColor`
的 `default -> "#5B8C3E"` 是**词表 B 族的低地绿**。两个兜底色来自**两个不同**的词表族，故
`TerrainCatalog` 的排除用例（`noTypeRevivesAKnownFallbackColor`）**同时排除这两个值、且大小写不敏感**
（本类型颜色校验正则允许小写，`"#6cc261"` 是真实可写的同值漏路）。

⇒ 裁决（**★ 已被用户裁决 U1 推翻，以本节为准**）：

**A 与 B 同时作废。** 词表改为**用户给的 7 项**，**按高度从小到大**，各有不同特性：

| # | 名称 | key | 高度带（升序） | 特性（控制器给，待你过目） |
|---|---|---|---|---|
| 1 | 海洋 | `ocean` | 最低 | 不可通行（`moveCost` 最高/不可入）；无产出 |
| 2 | 平原 | `plains` | ↓ | 产能最高、最好走 —— **可耕作的核心地带** |
| 3 | 沙漠 | `desert` | ↓ | **★ 额外的低湿度门**（见下）；产出贫瘠、难走 |
| 4 | 低矮丘陵 | `low_hills` | ↓ | 产量中等、略难走；矿藏起点 |
| 5 | 山地 | `mountains` | ↓ | 石/矿富集、很难走 |
| 6 | 平缓高原 | `plateau` | ↓ | **高但平坦** —— 海拔高却相对好走（与山地相反的取舍） |
| 7 | 高原山地 | `plateau_mountains` | 最高 | 最险；几乎不可通行 |

```java
public record TerrainType(
    String key, String name, String color,
    double minHeight, double maxHeight,      // ★ 高度带，由 TerrainCatalog 唯一持有
    int food, int gold, int stone, int moveCost,
    String description) {}
```

- ★ **高度带进词表，不进分类器的代码**（★ **控制器补裁，用户未指定**）：
  用户说"**高度从小到大**"，最直接的落地就是**把带作为数据**。
  好处有三：① 判据可断言（带**连续、不重叠、覆盖 `[0,1]`** 是一条结构性用例，
  而不是靠人去读 `if/else`）；② `TerrainClassifier` 退化成"查带 + 变体"，
  **不再是第二个藏着阈值的词表**（GSimulator 的 L9 正是这么来的）；
  ③ 调平衡只改表，不改代码。
- ★ **沙漠的低湿度门**（★ **控制器补裁，用户未指定，需你过目**）：
  7 项**各有自己的高度带**（用户给的序）。但**沙漠若只看高度，每张图都会在那个高度长出一圈沙漠环**。
  ⇒ 沙漠带内**额外要求低湿度**，湿度高时落回**平原**。
  若你要的是"沙漠与高度无关、纯由气候决定"，改一行阈值即可。
- **`TerrainCatalog` 是唯一词表**：GSimulator 的
  `MapData.defaults()`（A）、`MapGenerator.defaultTerrainTypes()`（B）、前端 `DEFAULT_TERRAINS`、
  `TerrainTextRenderer.TERRAIN_CHAR`、`CompressionService.terrainColor`、两个 `switch` 颜色表、
  工具里的校验文本 —— **全部作废**，只此一份。
- ★ **落盘顺序**：侦察 D 实测 `MapData.java:65` 的 `Map.copyOf` **打乱迭代序**
  （同一份表在两个存档里顺序不同）⇒ 用 `LinkedHashMap` 且**不 `copyOf`**。
  **顺序必须确定**，否则"往返"在字节层面不成立。**本表的迭代序 = 高度升序**，是语义序，不是巧合。

### 6.2 地形类型必须都能产出

侦察 D 实测：`ContourQueryEngine.classify` **只产出 6 种**
（`mountain`/`hills`/`plains`/`lowland`/`swamp`/`water`），
★ **`forest` / `desert` / `tundra` 永远产生不出来** —— 而它们**在词表里**。
**一份产不出来的词表是谎话。**
⇒ 裁决：**`TerrainClassifier` 必须覆盖全部 7 项**（U1 后的词表），
且因为**高度带已在词表里**（§6.1），分类器**不再藏阈值** ——
它只做两件事：**按 `height` 查带**，以及**带内的变体判定**（目前只有沙漠的湿度门这一处）。
**"覆盖 7 项"因此从"人去读 if/else"变成一条结构性用例**：带的并集必须等于 `[0,1]`，
故每个带都非空、故每一项都产得出。

### 6.3 海拔必须落盘（L7）

侦察 A + D **互为独立佐证**的实测：`HexCell` **无 height 字段**；
`height` 只在 `ContourQueryEngine.TerrainSample`（`:62`）的**内存 LRU** 里；
`MapService.queryTerrain:860-871` **把它丢掉**（fallback 写死 `0`/`0.5`）；
★ **`.height()` 全仓零调用点**（含前端 JS 与 `docs/`）。

⇒ 裁决：**`HexCell` 加 `height` 字段**（`double`）。
理由不只是"L7 说要落盘"—— **总纲 §5.1 的"自动河流：根据地形海拔从最高到最低生成"，
在没有落盘海拔的前提下做不了**：不能每次问海拔都重新生成一遍地形。

### 6.4 `GenerationSpec`：参数面（L8）

侦察 D 实测：生成入口只暴露 **8 个形参**，其中 ★ **`worldId` 与 `coastRoughness` 两个在函数体内从未被引用**
（`MapGenerator.java:243-245`，纯装饰）；真正决定地貌的 **~60 个阈值/频率/权重全是方法体内的魔法数字**；
默认值有**三份拷贝且已分歧**（前端 `radius` 120 vs 后端 80；`roughness` 0.5 vs 0.6）。

⇒ 裁决：

```java
public record GenerationSpec(
    long seed, int mapRadius, double baseSeaLevel,
    int mainRidges, int fragments,
    NoiseBands bands, RidgeParams ridges, FragmentParams fragments_, /* … */
    int contourCacheMax) {
  public GenerationSpec { /* 全部范围校验，越界抛 IllegalArgumentException */ }
  public static GenerationSpec defaults(long seed) { /* 唯一一份默认值 */ }
}
```

- **`~60` 个魔法数字全部进字段**（分组的子 record：`NoiseBands` / `RidgeParams` / `FragmentParams` …），
  **一处默认值**（`defaults()`），前端与后端**不再各写一份**。
- ★ **删 `worldId`** —— 生成器不需要知道世界 ID。
- ★ **删 `coastRoughness`** —— 从未被读。要海岸粗糙度就用已有的 `coastFreq`（那是个**真**参数）。
- ★ **`ridges` 的静默硬夹（`Math.max(1, Math.min(mainCount, 2))`，`MapGenerator.java:61`）删除**，
  改为**构造期范围校验并抛异常**。传 `>2` 静默失效是**本项目反复付代价的"静默"模式**。
- ★ **`frags = fragmentCount - secondary` 可为负**（`MapGenerator.java:105`）—— 显式校验。
- ★ **`landRatio` 改名 `baseSeaLevel`**：实测它**只影响一个数**
  （`baseSeaLevel = 0.18 + (1-landRatio)*0.05`，`MapGenerator.java:130`）。
  **参数名必须诚实反映作用** —— 叫 `landRatio` 却只调海平面，是误导。

### 6.5 seed 必须落盘且两条入口同路（L7）

侦察 D 实测：
- 入参 `seed` → `new Random(seed)`；而**写进 `ContourContour.seed` 的是 `rng.nextLong()` 派生值**
  ⇒ **入参 seed 本身不被记录**；
- **只有 HTTP 路径调 `saveContour`**（`MapWebUIHandler.java:429`）；
  ★ **MCP 路径（`MapService.generate` → `GsimapGenerateTool`）只 `saveMap`，不写 contour**
  ⇒ **MCP 生成的地图不可复现**；
- ★ **HTTP 那个入口在同一请求里把地形算了两遍**（`:426-428` 建 contour，`:432-440` 又独立跑一遍 `generate`）。

⇒ 裁决：**`GameMap` 携带 `GenerationSpec`（含 seed）**，**HTTP 与 MCP 走同一条路径**，
**地形只算一遍**。落盘的 `GameMap` 自带复现所需的全部输入。

### 6.6 两个新模式（总纲 §5.1 的用户需求）

侦察 D 实测：**两个都不存在。**
- **框选随机化**：最接近的是 `LassoProcessor`，它只做**几何求内侧**
  （套索周长 → 桥接成墙 → 洪水填充 → 输出 `Set<String>`），
  ★ **全文零随机数调用**；地形由调用方传**单一常量**。
- **自动河流**：**全仓无任何按海拔造水系的代码**。"河"只是 `edges` 上的一个标签；
  `tracePathway` **只读不生成**（只遍历 `map.edges()`，根本不接触 contour/海拔）；
  `MapGenerator`/`ContourQueryEngine` 全文**不产生任何 `"river"` 字符串**。

⇒ 裁决（两个都是 **Command → MapChangeSet**，不是新的状态类型）：

- **`RegionRandomizer`**：输入 `(RegionId, TerrainType a, TerrainType b, double ratioA)`，
  输出一条 `MapChangeSet`，把那组 hex 按占比**概率性**重分配给两种地形。
  用**确定性**随机源（从 `(seed, RegionId)` 派生）⇒ **同种子同结果**，可复现、可往返测试。
- **`RiverBuilder`**：输入 `GameMap`，按 `HexCell.height` **从最高到最低**走一条路径，
  输出一条 `Pathway`（`groupId = "river"`），复用 §五 的连通性系统。
  单条河可寻址（`PathwayId`），分支是独立的线（§5.2）。

---

## 七 `GameMap` 与 `MapChangeSet`（待决项 5）

### 7.1 `GameMap` 的字段

综合 §三~§六 的裁决，`GameMap` 的 record 组件如下（**与 GSimulator 的 12 个逐条对照**）：

| # | 新字段 | 类型 | 来自 GSimulator 的 | 处置 |
|---|---|---|---|---|
| 1 | `hexes` | `Map<HexCoord, HexCell>` | `hexes` | 键类型化；`HexCell` **加 `height`**、**删 `edgeTags`/`riverMask`** |
| 2 | `regions` | `Map<RegionId, Region>` | `provinces` | **加 `id` 与 `name`** |
| 3 | `cities` | `Map<CityId, City>` | `cities` | 加类型化 ID |
| 4 | `terrainTypes` | `Map<String, TerrainType>` | `terrainTypes` | ★ **改为引用唯一 `TerrainCatalog`** |
| 5 | `pathways` | `Map<PathwayId, Pathway>` | **（新）** | ★ **取代废弃的 `rivers`/`roads`** |
| 6 | `pathwayGroups` | `Map<String, PathwayGroup>` | `pathwayGroups` | 保留 |
| 7 | `edges` | `Map<EdgeRef, EdgeTags>` | `edges` | 键**类型化** |
| 8 | `spec` | `GenerationSpec` | **（新）** | ★ **落盘 seed 与全部生成参数**（§6.5） |
| — | ~~`gridSize`~~ | | `gridSize` | **删**（§3.3） |
| — | ~~`hexOrientation`~~ | | `hexOrientation` | **删**（§3.3） |
| — | ~~`rivers`/`roads`~~ | | `rivers`/`roads` | **删**（§5.3） |
| — | ~~`terrainBlocks`~~ | | `terrainBlocks` | **删** → 编辑 Command 的历史 |
| — | ~~`compressedRegions`~~ | | `compressedRegions` | **删** → 派生缓存（§4.3） |

### 7.2 `MapChangeSet`

铁律 5：**变更集从完整状态类型派生**。

```java
/** 每个 GameMap 组件对应一个 FieldDelta。null 表示"未变"。 */
public sealed interface FieldDelta<T> {
  record Unchanged<T>() implements FieldDelta<T> {}
  record Upsert<T>(Map<String, T> entries) implements FieldDelta<T> {}
  record Remove<T>(Set<String> keys) implements FieldDelta<T> {}
  // map 型与 list 型各一套（list 型用全量替换，不做增量）
}

public record MapChangeSet(
    FieldDelta<HexCell> hexes, FieldDelta<Region> regions, /* …逐组件… */) {}
```

### 7.3 ★ 让漂移不可能（本 spec 对铁律 5 的核心答案）

**GSimulator 的 `MapDiff` 是逐字段手工维护的**（侦察 C 实测：`MapDiff.compute` 53 行、
**只比较 3 个领域字段**），后果是 **6 个字段漂移出去**（`terrainBlocks`/`terrainTypes`/`pathwayGroups`/`edges`
\+ ★ **`gridSize`/`hexOrientation`**，后两个是侦察 C 新查出的），
且**零守卫**（侦察 C 实测：最接近的 `MapServiceChildNodeSaveTest` **只断言"父基线被创建 + 子 diff 文件存在"，
不断言 resolve 回的内容**）。

⇒ 裁决：**往返测试用反射枚举 `GameMap` 的全部 record 组件**：

```
for each component c of GameMap.class.getRecordComponents():
    base   = GameMap.empty()
    target = base 在组件 c 上被改成一个合法的新值（其余组件不动）
    cs     = MapChangeSet.between(base, target)
    assert apply(cs, base).equals(target)          # 往返
    并断言 cs 在组件 c 上不是 Unchanged            # 证明这条差异真的进了变更集
```

★ **加一个状态字段若忘了加进变更集，`cs` 在该组件上恒为 `Unchanged` ⇒ 测试自动红。**
这是"从完整状态类型派生"的**机械化落地**，不是靠纪律。
**护栏自证（G13）**：故意把一个组件从 `MapChangeSet` 里摘掉，**该用例必须红**（§九）。

### 7.4 往返的其余约定

- `apply(cs, base)` 必须**逐字段重建**出 target（铁律 5 原文）。
- **一致性规则**：`between(x, x)` 必须返回**全 `Unchanged`** 的变更集（**不是空对象**）——
  这是"未变"与"变为空"的区分，GSimulator 的 `MapDiff.isEmpty()` 混淆了这两者。
- `GenerationSpec` 是**不可变的生成输入**，**不进变更集**（地图生成后它只是溯源信息）。
- **边界不进变更集**（§4.3）、**反向索引不进变更集**（§4.4）—— 它们是派生物。

---

## 八 偏离总纲草案清单（**评审重点**）

| # | 总纲原文 | 本 spec | 为什么 |
|---|---|---|---|
| 1 | §5.1「`TerraType`（`color`/`height`/`pass`/`name`）」 | **`TerrainType`**（**10 字段**：`key`/`name`/`color`/`minHeight`/`maxHeight`/`food`/`gold`/`stone`/`moveCost`/`description`；**海拔值本身仍归 `HexCell`**） | 实测 GSimulator **无 `TerraType` 这个名字**（全仓零命中）。★ **U1 之后这条的性质变了**：用户要求"高度从小到大"，故**高度带进了地形类型**（`minHeight`/`maxHeight`）—— **这才是总纲写 `height` 的本意**，控制器的原裁（"海拔只是格子的属性"）**理解窄了**。**逐格的海拔仍在 `HexCell`；类型携带的是它的带** |
| 1b | §5.1 未指定地形项数 | **7 项**：海洋/平原/沙漠/低矮丘陵/山地/平缓高原/高原山地，**按高度升序** | ★ **U1（用户裁决）**。GSimulator 实测落盘的是 9 项（`water/lowland/plains/hills/mountain/forest/swamp/desert/tundra`）—— **整个作废**，不是改名 |
| 2 | §L3「方向数组错位（`TerrainGeometry.DIRS` 与 `MapService.HEX_DIRS` 在索引 1-4 指向不同方向）」 | **错位集合是 `{1,2,4,5}`**；且**实际错位面比总纲窄** —— 唯一真错位处（前端 `expand.js` 的 `EXPAND_DIRS`）**的 `q`/`r` 字段全仓从未被读取**，是死数据 | 侦察 A 脚本实算 + 逐处核对消费者 |
| 3 | §L8「12 参数构造复制 12 次；`MapService` 内 12 处」 | **`MapService` 内 13 处；主源码 17 处；全仓（含测试）36 处** | 侦察 D 脚本逐括号数实参。"12 参数"**对**，"12 次"**不准**。事故文档记的"6 处"是**那一次事故的触发面**，不是全部调用点 |
| 4 | §L7「无海拔无种子落盘」 | **成立**，且比总纲更严重：**MCP 路径根本不写 contour** ⇒ **MCP 生成的地图不可复现** | 侦察 D 实测（总纲只说了"没落盘"，没说"两条入口不一样"） |
| 5 | §5.1「`Region`（**闭环 hex 边界** + 元数据）」 | ~~边界不进 `Region`~~ **← U2 已推翻，边界回归 `Region` 且被校验** | ★ **用户的裁决让这一条重新与总纲一致**：控制器原裁偏离了总纲，U2 把它掰了回来。**记录在此，是因为"偏离清单"要显示的是最终态与总纲的差，不是控制器的中途意见** |
| 6 | §十二「本轮不做」未列 | **新增开口项**：前端几何（§3.4 说"前端不算几何"）的具体形态、`annexedBy` 的最终归属（是地图数据还是未来的 Social 语义） | 见 §九"开口项" |
| 7 | §L1 的默认路径节点写 `n0007` | **记为待核** —— 侦察 B 推出应为 `n0005`，但**它自己明写"系推导、未运行代码"**。按纪律第 5 条**不当结论、不改文档** | 推导 ≠ 实测 |

---

## 九 测试清单与开口项

### 9.1 L1~L9 的守卫用例（关账判据逐条）

| 缺陷 | 守卫用例（M2 必须写） |
|---|---|
| **L1** 子节点写 `edges` 静默丢失 | 往返：改一条边 → `between` → `apply` → **断言边还在**。★ 加一条**非 root** 的用例（GSimulator 只在非 root 丢，root 走全量保存，**root 用例会掩盖它**） |
| **L2** 双份连通性存储 | 编译期：**`HexCell` 不含任何连通性字段**（反射断言组件清单） |
| **L3** 方向数组错位 | **全仓只有一个方向表**：反射断言不存在第二个 `int[][]` 方向常量；`opposite()`/`next()`/`prev()` 的代数性质（`opposite(opposite(d))==d`） |
| **L4** 三个 region 概念 | 编译期：`simos-map` 里只有**一个** `Region` 类型 |
| **L5** Province 归属 O(区域数×hex数) | `regionOf` 是 O(1)：构造 1000 hex × 100 region，断言**不随 region 数增长**（或直接断言存在反向索引） |
| **L6** 坐标表述不一致 | `HexCoord` 是唯一坐标类型；`"q_r"` 只在 JSON 边界出现（grep 断言） |
| **L7** 无海拔无种子落盘 | 往返：`GameMap` 经 JSON **逐字节**往返（含 `height` 与 `spec.seed`）；★ **同 seed 生成两次结果相同** |
| **L8** 12 参数构造复制 | `GameMap` 用 builder 或 `with*`；**断言全仓 `new GameMap(` 的调用点数量**（或干脆让构造器 package-private） |
| **L9** 地形词表分裂 | **只有一份 `TerrainCatalog`**：断言 **7 项**（U1）；★ **带的并集恰等于 `[0,1]`、两两不重叠、逐项升序** ⇒ **每一项都产得出**（由结构性断言保证，不靠人去读 `if/else`）；★ 沙漠的湿度门有专门用例（干燥→沙漠、湿润→平原） |

### 9.1b U1 / U2 的守卫（★ 用户裁决带来的新面）

| 裁决 | 守卫用例 |
|---|---|
| **U1** 7 项、高度升序 | ① `KEYS` 恰 7 项且**迭代序 = 高度升序**；② **带连续**（`catalog[i].maxHeight() == catalog[i+1].minHeight()`）；③ **覆盖 `[0,1]`**（首项 `minHeight()==0`、末项 `maxHeight()==1`）；④ 两两不重叠 |
| **U1** 沙漠的湿度门（控制器补裁） | 同一高度带内：**干燥 → 沙漠**、**湿润 → 平原**。★ 否则"每张图一圈沙漠环" |
| **U2** 边界入存储 | ① `Region` **含** `boundary` 组件（反射断言）；② `boundary` 经 JSON **往返**后逐字相同；③ **构造器校验**：给一个与 `hexes` 不符的边界 ⇒ **抛** |
| **U2** `withHexes` 重算 | `withHexes` 后 `boundary()` 等于由新 `hexes` 重算的值 |
| **U2** 边界**不进 `MapChangeSet` 的新组件** | 反射断言 `MapChangeSet` 的组件数**仍是 7** —— 边界随 `Region` 走，不另开一个 |

### 9.2 两个新模式的验收

- **框选随机化**：给定 Region + 两种地形 + 占比 `p`，**统计 1000 次抽样**的占比落在 `p ± ε`；
  ★ **同 seed 两次结果完全相同**。
- **自动河流**：给定一张有海拔的图，断言生成的 `Pathway` **从最高格出发**、**每一步不升高**
  （或按 spec 的规则）、**两端是端点**、**`PathwayId` 可寻址**；★ **分支是独立的线**（造一个带分支的输入）。

### 9.3 护栏自证（G13）

| 护栏 | 故意违规用例 |
|---|---|
| §7.3 的反射枚举往返 | **从 `MapChangeSet` 摘掉一个组件** ⇒ 该用例必须红 |
| §7.3 的**豁免集**（`Set.of("spec")`） | 往豁免集里**再加一个名字**（如 `"edges"`）⇒ 该用例必须红。**防的是"用豁免糊过组件漂移"** |
| `TerrainCatalog` 唯一 | 加第二份词表 ⇒ 断言必须红 |
| **§6.1 的高度带自洽**（U1） | 把某个带的上界改到与下一带重叠 ⇒ 断言必须红 |
| **§6.2 沙漠的湿度门**（U1 补裁） | 删掉湿度门 ⇒ "湿润时不产沙漠"的用例必须红 |
| **§4.3 边界与 `hexes` 一致**（U2） | 用一个与 `hexes` 不符的 `boundary` 构造 `Region` ⇒ **必须抛**（不是静默接受） |
| **§4.2 `withHexes` 重算边界**（U2 的连带） | 让 `withHexes` **只换 hexes 不重算 boundary** ⇒ 该用例必须红。**这是 `boundary` 成为组件后最容易写错的一处** |
| `HexCell` 无连通性字段 | 加回一个 `edgeTags` ⇒ 反射断言必须红 |
| `PathwayId` 持久 | 改成内容派生 ⇒ "改中间节点 ID 不变"的用例必须红 |
| `GenerationSpec` 校验 | 传 `mainRidges = 5` ⇒ **必须抛异常**（不是静默夹到 2） |

### 9.4 开口项（登记在案，本轮不做）

| 项 | 处置 |
|---|---|
| 前端几何（§3.4） | 前端不再有第二份 `hexRound`/方向表；**具体由 Core 侧提供还是前端只渲染，M4/M5 裁决** |
| `annexedBy` 的最终归属 | 暂留 `RegionMeta`；**若是"政治"语义，M3 起应迁往 SocialSimos**（铁律 3） |
| 地形块编辑（`TerrainBlock` 的替代） | 归 **Command**，不在 M2 状态里；具体的 Command 类型清单是 **M4 的待决项** |
| 老存档的 `boundary` 字段 | M6 导入器处理；M2 不读 |

---

**本 spec 的事实基础可复核**：§三~§六 每条断言都指向 `m2-recon-{A,B,C,D}-*.md` 里的 `文件:行`。
**凡未经实测者，正文已明写"推导"。**
