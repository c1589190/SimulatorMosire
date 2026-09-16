# M2 取证 A：六边形几何 / 坐标系 / 方向常量表 / 距离

目标仓库：`/home/cna/DevMosire/GSimulator`（只读取证，未修改任何文件）
HEAD：`88d0f02`，分支 `refactor/gsim-module-split`
取证时间：2026-09-16

> 纪律声明：本文所有代码原文均以 `git -C /home/cna/DevMosire/GSimulator grep` / Read 实读得到，
> 行号为实读行号。凡"未核实"项在文末单列。

---

## 结论一段话

GSimulator 的六边形用 **axial（q, r）整数坐标**，承载类是 `MapData.HexCell` 的 **键**（`String`，格式
`"q_r"`），容器是 `Map<String, HexCell>`（`MapData.hexes()`），不是二维数组——`MapData` 里根本没有
"按 (q,r) 下标取格"的数组；地图尺寸只由 `gridSize`/半径做**约束**用。方向常量表全仓共 **6 份 Java 副本
+ 2 份 JS 副本**，归为**两种互逆的索引序**：`TerrainGeometry` 序（E,SE,SW,W,NW,NE，即顺时针）
与 `MapService` 序（E,NE,NW,W,SW,SE，即逆时针）。两表**集合相同、索引序互为反向**，
**索引 0 与 3 一致，索引 1/2/4/5 全部错位**（不是"1-4"）。像素换算有 **两种标度**（`SIZE=30` 与
`q+r*0.5 / r*0.866` 的无标度系）——前者是 `TerrainGeometry`/前端，后者是 `ContourQueryEngine`/
`MapGenerator` 的轮廓空间；`TerrainBlockProcessor`（**全仓无调用者**的 deprecated 类）在同一个类里
**同时用了这两种标度**。距离函数有 **4 处实现，公式数值等价**，无口径分歧。海拔（height）**不是
`HexCell` 字段，不落盘**，只活在 `ContourQueryEngine.TerrainSample` 的内存 LRU 里。

---

## Q1 坐标系

### 1.1 用什么坐标：axial (q, r)

`gsim-map/src/main/java/com/gsim/map/map/MapData.java:16-18`

```java
 * @param gridSize          width/height of the hex grid (1-1000)
 * @param hexOrientation    true for pointy-top, false for flat-top
 * @param hexes             all hex cells keyed by "q_r"
```

`MapData.java:34-36`

```java
        @JsonProperty("gridSize") int gridSize,
        @JsonProperty("hexOrientation") boolean hexOrientation,
        @JsonProperty("hexes") Map<String, HexCell> hexes,
```

**承载坐标的类/字段**：
- 单格：`MapData.HexCell`（`MapData.java:184-192`）——**HexCell 本身不存 q/r**，坐标只存在于 map 的键里。
- 键格式：`MapData.hexKey(int q, int r)` → `MapData.java:113-115`：
  ```java
    public static String hexKey(int q, int r) {
        return q + "_" + r;
    }
  ```
- 解析：`MapData.parseHexKey(String)` → `MapData.java:123-126`：
  ```java
    public static int[] parseHexKey(String key) {
        String[] parts = key.split("_");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }
  ```

**容器**：`Map<String, MapData.HexCell>`，**不是二维数组**。索引方式 = `hexes().get("q_r")`。
`gridSize` 只在构造时做范围校验（`MapData.java:48-49`：`1-1000`），**不参与取格**。

**特例**：城市是**唯一把坐标存成独立字段**的实体——
`MapData.java:327-332`：

```java
    public record City(
            @JsonProperty("q") int q,
            @JsonProperty("r") int r,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description) {
```

`MapData.HexCell` 完整字段（`MapData.java:184-192`）：`color, terrain, symbol, symbolColor,
description, riverMask, edgeTags, tags` — 无坐标、无高度。

### 1.2 `hexOrientation` 是死字段

`MapData.java:35` 声明，`MapData.empty()`（`MapData.java:90-104`）写死 `false`，
`ContourQueryEngine.materialize` 也写死 `false`（`ContourQueryEngine.java:106-108`）。
全仓 grep `hexOrientation` 的命中**只有构造器透传**（`MapResolver.java:257`、`MapService.java` 共 14 处
`map.hexOrientation(),`、`CompressionValidator.java:226`）与前端赋值——**没有任何读取分支**。
前端 `gsim-map/src/main/resources/web/js/events.js:162` 与 `:276` 也写死 `hexOrientation:false`。

而实际像素公式（Q5）是 **pointy-top** 形式（`y` 随 `r` 线性、`x` 含 `√3/2·r`），
`TerrainTextRenderer` 的注释却自称 flat-top（见 Q5.6）。**字段值与实际几何不一致，且该字段无人读。**

### 1.3 像素换算（唯一的"标准"版）

`gsim-map/src/main/java/com/gsim/map/service/TerrainGeometry.java:25-59`

```java
    /** Hex size in pixels — must match the frontend GRID constant. */
    public static final double SIZE = 30.0;
...
    public static double[] hexToPixel(int q, int r) {
        double x = SIZE * (Math.sqrt(3) * q + Math.sqrt(3) / 2.0 * r);
        double y = SIZE * (3.0 / 2.0 * r);
        return new double[] {x, y};
    }
...
    public static double[] pixelToHexFrac(double px, double py) {
        double fq = (Math.sqrt(3) / 3.0 * px - 1.0 / 3.0 * py) / SIZE;
        double fr = (2.0 / 3.0 * py) / SIZE;
        return new double[] {fq, fr};
    }
```

`TerrainGeometry.java:80-91` 的 `hexRound` 用 **cube 取整**（`fs = -fq - fr`，最大偏差分量回代）。

前端 `gsim-map/src/main/resources/web/js/hex-math.js:1-28` 与 `state.js:2` 是**同一套**：

```js
const GRID = 30;
```
```js
function hexToPixel(q, r) {
  return {
    x: GRID * (Math.sqrt(3) * q + Math.sqrt(3)/2 * r),
    y: GRID * (3/2 * r)
  };
}
function pixelToHex(x, y) {
  const s = GRID;
  return hexRound((Math.sqrt(3)/3*x - 1/3*y)/s, (2/3*y)/s);
}
```

**cube 坐标只作中间量**（`hexRound`、`hexDist`、`hexLine` 内部用 `s = -q - r`），**不作为存储或接口格式**。

---

## Q2 方向常量表：**共 6 份 Java + 2 份 JS = 8 份**

### 2.0 全量清单（`git grep` 实得，无其他文件含方向数组）

| # | 出处 | 常量名 | 索引序 |
|---|---|---|---|
| 1 | `gsim-map/.../service/TerrainGeometry.java:33` | `DIRS`（package-private `static final`） | **A 序** |
| 2 | `gsim-map/.../service/TerrainBlockProcessor.java:24` | `DIRS`（`private static final`） | **A 序** |
| 3 | `gsim-map/.../service/MapService.java:932` | `HEX_DIRS`（`static final`） | **B 序** |
| 4 | `gsim-map/.../service/CompressionService.java:32` | `DIRS`（`private static final`） | **B 序** |
| 5 | `gsim-map/.../service/LassoProcessor.java:23` | `DIRS`（`private static final`） | **B 序** |
| 6 | `gsim-map/.../tools/map/GsimapGetNeighborsTool.java:20` | `HEX_DIRS`（`private static final`） | **B 序** |
| 7 | `gsim-map/src/main/resources/web/js/state.js:3` | `DIR_VECTORS`（`const`） | **A 序** |
| 8 | `gsim-map/src/main/resources/web/js/expand.js:2-8` | `EXPAND_DIRS`（含 `q`/`r` 字段，**死字段**） | 见 2.4 |

`gsim-core` / `gsim-app` / `gsim-agentsmanager` **无任何方向数组**（已用 `git grep -n "int\[\]\[\]"` 全仓确认，
命中全部落在 `gsim-map`）。

### 2.1 原文（两份表的定义行，逐字）

**A 序** —— `gsim-map/src/main/java/com/gsim/map/service/TerrainGeometry.java:28-33`：

```java
    /** The 6 axial direction vectors. */
    // Hex neighbor directions indexed by edge. Order must match corner angles (60*i - 30°):
    // Edge 0 (right) → E(1,0), Edge 1 (bottom-right) → SE(0,1),
    // Edge 2 (bottom-left) → SW(-1,1), Edge 3 (left) → W(-1,0),
    // Edge 4 (top-left) → NW(0,-1), Edge 5 (top-right) → NE(1,-1)
    static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};
```

`gsim-map/src/main/java/com/gsim/map/service/TerrainBlockProcessor.java:23-24`：

```java
    // Hex direction vectors (flat-top axial)
    private static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};
```

`gsim-map/src/main/resources/web/js/state.js:3`：

```js
const DIR_VECTORS = [[1,0],[0,1],[-1,1],[-1,0],[0,-1],[1,-1]];
```

**B 序** —— `gsim-map/src/main/java/com/gsim/map/service/MapService.java:931-934`：

```java
    /** Hex neighbor directions in axial coordinates (q, r). */
    static final int[][] HEX_DIRS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};

    private static final String[] EXPAND_NAMES = {"E", "NE", "NW", "W", "SW", "SE"};
```

`gsim-map/src/main/java/com/gsim/map/service/CompressionService.java:32`：

```java
    private static final int[][] DIRS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
```

`gsim-map/src/main/java/com/gsim/map/service/LassoProcessor.java:23`：

```java
    private static final int[][] DIRS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
```

`gsim-map/src/main/java/com/gsim/map/tools/map/GsimapGetNeighborsTool.java:20-21`：

```java
    private static final int[][] HEX_DIRS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
    private static final String[] DIR_NAMES = {"E", "NE", "NW", "W", "SW", "SE"};
```

### 2.2 ★ 并排逐索引对照（A 序 vs B 序）——本报告核心

像素角度按 `x = √3(q + r/2), y = 1.5r`（pointy-top，y 向下）实算，
`angle = atan2(Δy, Δx)`，脚本实测输出：

| 索引 | A 序向量 `TerrainGeometry.DIRS[i]` | A 序方向名（源自 `TerrainGeometry.java:30-32` 注释） | A 序像素角 | B 序向量 `MapService.HEX_DIRS[i]` | B 序方向名（源自 `MapService.java:934` `EXPAND_NAMES`） | B 序像素角 | 是否同一向量 |
|---|---|---|---|---|---|---|---|
| **0** | `(1, 0)` | E | 0.0° | `(1, 0)` | E | 0.0° | **相同** |
| **1** | `(0, 1)` | SE | 60.0° | `(1, -1)` | NE | −60.0° | **不同** |
| **2** | `(-1, 1)` | SW | 120.0° | `(0, -1)` | NW | −120.0° | **不同** |
| **3** | `(-1, 0)` | W | 180.0° | `(-1, 0)` | W | 180.0° | **相同** |
| **4** | `(0, -1)` | NW | −120.0° | `(-1, 1)` | SW | 120.0° | **不同** |
| **5** | `(1, -1)` | NE | −60.0° | `(0, 1)` | SE | 60.0° | **不同** |

**错位集合 = {1, 2, 4, 5}，共 4 个索引错位；索引 0 与 3 一致。**
（任务书里"索引 1-4 指向不同方向"的措辞**不精确**：正确的错位集合是 1/2/4/5，而 3 是相同的。）

**两表的精确关系（脚本实测为 True）**：

```
A[1:] == reversed(B[1:])            → True
B[i] == A[(6 - i) % 6]  for all i   → True
```

即：**两表是同一组 6 个向量的相反绕序**。B 序 = 从 `(1,0)` 出发逆时针（E→NE→NW→W→SW→SE），
A 序 = 从 `(1,0)` 出发顺时针（E→SE→SW→W→NW→NE）。
**没有一个向量在两表中缺席，也没有一个向量被换成别的**——差别**纯粹是索引位置**。

**各自的自洽性**：
- A 序自洽：`TerrainGeometry.java:280-297` 用 `angle = 60*i - 30` 生成角点，
  取 `c1 = d`、`c2 = (d+1) % 6` 作为边 d 的两端 → `DIRS[d]` 恰好是角点 d 与 d+1 之间的那条边。
  `TerrainGeometry.java:29` 的注释（"Order must match corner angles (60*i - 30°)"）与代码实算一致。
- B 序自洽：`MapService.java:934` 的 `EXPAND_NAMES` 与 `:932` 的 `HEX_DIRS` 逐索引配对，
  按上表角度实算，**6 个名字全部正确**（E=正东、NE=右上、NW=左上、W=正西、SW=左下、SE=右下）。

**⚠️ 错位的实际后果（已核实的消费者）**：任何"用 A 表的索引去查 B 表的名字/语义"或反之的地方都会错。
**逐处核对结果**：
- `MapData.HexCell.edgeTags` 的键语义 = **A 序**，见 `MapData.java:179`：
  ```java
     * @param riverMask   6-bit edge mask (bits 0-5 for edges E,SE,SW,W,NW,NE)
  ```
  与 `MapData.java:201-207` 的 legacy 迁移：
  ```java
            if (edgeTags.isEmpty() && riverMask > 0) {
                for (int d = 0; d < 6; d++) {
                    if ((riverMask & (1 << d)) != 0) {
                        edgeTags.put(d, new ArrayList<>(List.of("river")));
  ```
  前端 `DIR_VECTORS`（A 序）与之**一致**——`pathway.js:132/180/233/253/501` 全部 `DIR_VECTORS[d]` 配
  `edgeTags[d]`，`hex-math.js:65-71` 的 `direction()` 也遍历 `DIR_VECTORS`。**这一条链是自洽的。**
- `TerrainCanvas` / `CompressionValidator` / `TerrainGeometry` 内部 → 全走 A 序，自洽。
- `MapService:977`（`computeAdjacency`）/:1034-1035（`expand`）/:1695（`init_nation` flood-fill）
  → 全走 B 序，且**这些用途（BFS/邻接计数/120° 旋转）对索引序不敏感或与名字配套**，自洽。
- `CompressionService:69`、`LassoProcessor:99/126/127`、`TerrainBlockProcessor:152/191`
  → 纯 BFS/flood，**索引序无影响**。
- **唯一实际错位**：前端 `expand.js:2-8` 的 `EXPAND_DIRS` 的 `q`/`r` 字段——见 2.4。

### 2.3 缺失的统一入口

没有任何一处**共享**的方向表：6 份 Java 副本各自 `private`/package-private，
`TerrainGeometry.DIRS` 是 package-private（`TerrainGeometry.java:33`），
`MapService.HEX_DIRS` 也是 package-private（`MapService.java:932`），
**两者都不导出为公共 API**，故消费方只能各自复制，无编译期约束保证一致。

### 2.4 前端 `EXPAND_DIRS`：q/r 字段与后端表**整体错开一位**（但当前是死字段）

`gsim-map/src/main/resources/web/js/expand.js:2-8`：

```js
const EXPAND_DIRS = [
  {key:'NW', label:'↖ 西北', q:-1, r:0},
  {key:'NE', label:'↗ 东北', q:0, r:-1},
  {key:'W',  label:'← 西',  q:-1, r:1},
  {key:'E',  label:'→ 东',  q:1, r:-1},
  {key:'SW', label:'↙ 西南', q:0, r:1},
  {key:'SE', label:'↘ 东南', q:1, r:0},
];
```

对照 2.2 的实算角度：

| key | 前端 `(q,r)` | 该向量的真实方位 | 前端 label | 后端 `EXPAND_NAMES`→`HEX_DIRS` |
|---|---|---|---|---|
| `NW` | `(-1, 0)` | **W**（180°） | 西北 | `NW` → `(0,-1)` |
| `NE` | `(0, -1)` | **NW**（−120°） | 东北 | `NE` → `(1,-1)` |
| `W` | `(-1, 1)` | **SW**（120°） | 西 | `W` → `(-1,0)` |
| `E` | `(1, -1)` | **NE**（−60°） | 东 | `E` → `(1,0)` |
| `SW` | `(0, 1)` | **SE**（60°） | 西南 | `SW` → `(-1,1)` |
| `SE` | `(1, 0)` | **E**（0°） | 东南 | `SE` → `(0,1)` |

即：**前端 6 个 key 的 `q`/`r` 全部等于后端"下一个"方向的向量**（整体错开一位）。

**但这条目前不构成行为差异**——`q`/`r` 字段全仓**从未被读取**：
`git grep -n "EXPAND_DIRS"` 只有两处命中，`expand.js:2`（定义）与 `expand.js:64`
（`EXPAND_DIRS.find(d => d.key === cell.key)`，只读 `d.key`；`:65` 只读 `d.label`）。
实际请求只发 key：`expand.js:33`：

```js
    const url = `/api/map/${MapAPI.worldId}/expand?direction=${State.expandDirection}&radius=${radius}${nodeParam}`;
```

后端 `MapWebUIHandler.java:320` 取 key 后交 `MapService.expand`，由
`MapService.java:1004-1011` 用 `EXPAND_NAMES` 反查索引 → 用的是**后端 B 序表**，自洽。
**故：前端 `q`/`r` 是"值与名不符的死数据"**，报告为事实，不做定性。

### 2.5 `OPPOSITE_DIR`（前端）

`gsim-map/src/main/resources/web/js/state.js:4`：

```js
const OPPOSITE_DIR = [3,4,5,0,1,2];
```

配 A 序 `DIR_VECTORS`，`(i+3)%6` 正确（A 序下 `DIRS[i] = -DIRS[i+3]` 逐索引成立）。

---

## Q3 邻居计算：实现处 / 用哪张表 / 硬编码偏移

**全仓无任何硬编码邻居偏移。** `git grep -n "q + 1\|q - 1\|r + 1\|r - 1"` 在 `*.java`（非 test）
只命中 URL query 解析（`CliWebSocketServer.java:425`、`HandlerUtils.java:37,108`），与几何无关；
`*.js` 只命中 `render.js:104`：

```js
    for (let q = tl.q - 1; q <= br.q + 1; q++) {
```

——这是**视口包围盒外扩一格**，不是邻居偏移。

**按方向表逐处列出（消费者清单）**：

| 文件:行 | 用的表 | 用途 |
|---|---|---|
| `TerrainGeometry.java:201` | 自身 `DIRS`（A） | `hexSetFromPolygon` flood-fill |
| `TerrainGeometry.java:217` | 自身 `DIRS`（A） | 边界点邻居探测 |
| `TerrainGeometry.java:288-289` | 自身 `DIRS`（A） | `hexSetToBoundaryWithHoles` 判"边是否暴露" |
| `TerrainGeometry.java:455-456` | 自身 `DIRS`（A） | `isAdjacent` |
| `TerrainCanvas.java:321` | `TerrainGeometry.DIRS`（A） | `splitComponents` 连通分量 |
| `CompressionValidator.java:147-148` | `TerrainGeometry.DIRS`（A） | 邻格侵入检查 |
| `TerrainBlockProcessor.java:152` | 自身 `DIRS`（A） | flood-fill |
| `TerrainBlockProcessor.java:191` | 自身 `DIRS`（A） | `hexSetToBoundary` 判边 |
| `MapService.java:977` | `HEX_DIRS`（B） | `computeAdjacency` 共享边计数 |
| `MapService.java:1034-1035` | `HEX_DIRS`（B） | `expand`，含 `HEX_DIRS[(dirIdx + 2) % 6]` 取"垂直"方向 |
| `MapService.java:1695` | `HEX_DIRS`（B） | `init_nation` 领地 flood-fill |
| `CompressionService.java:69` | 自身 `DIRS`（B） | 同地形连通分量 BFS |
| `LassoProcessor.java:99` | 自身 `DIRS`（B） | 套索内部 flood-fill |
| `LassoProcessor.java:126-127` | 自身 `DIRS`（B） | `findInsideSeed` 双层邻居试探 |
| `GsimapGetNeighborsTool.java:70-72` | 自身 `HEX_DIRS`（B） | 对外工具 `gsimap_get_neighbors` |
| `hex-math.js:68` | `DIR_VECTORS`（A） | `direction()` 由位移反查边索引 |
| `paint.js:39` | `DIR_VECTORS`（A） | 套索断点续接 |
| `pathway.js:132/180/233/253/504` | `DIR_VECTORS`（A） | 通路命中的边索引 ↔ 邻居 / 画线 |
| `province.js:99/257-260/268` | `DIR_VECTORS`（A） | 省界 flood-fill、环状种子搜索 |
| `render.js:376` | `DIR_VECTORS`（A） | 压缩区边界判定 |

**对外工具 `gsimap_get_neighbors` 的输出**（`GsimapGetNeighborsTool.java:76-93`）
按 `DIR_NAMES[i]`（B 序）逐条输出 `### E (1,0)` 形式，**正确**。

**`MapService.expand` 的"垂直"取值**（`MapService.java:1034-1035`）：

```java
        int[] dir = HEX_DIRS[dirIdx];
        int[] perp = HEX_DIRS[(dirIdx + 2) % 6]; // 60°×2 ≈ perpendicular
```

B 序下 `+2` = 旋转 120°（E→NW、NE→W…），注释自陈"≈ perpendicular"。**记录为现状，不评价。**

---

## Q4 距离函数：**4 处实现，公式数值等价，无口径分歧**

### 4.1 实现清单

| # | 文件:行 | 可见性 | 公式原文 |
|---|---|---|---|
| 1 | `gsim-map/.../service/MapService.java:939-941` | `public static` | 见下 |
| 2 | `gsim-map/.../service/LassoProcessor.java:177-179` | `private static` | 见下 |
| 3 | `gsim-map/.../tools/map/GsimapEdgeListTool.java:87-91` | `private static` | 见下 |
| 4 | `gsim-map/src/main/resources/web/js/hex-math.js:46` | JS `const` | 见下 |

**原文 1** `MapService.java:938-941`：

```java
    /** Hex distance in axial coordinates (standard hex grid metric). */
    public static int hexDistance(int q1, int r1, int q2, int r2) {
        return (Math.abs(q1 - q2) + Math.abs(r1 - r2) + Math.abs((-q1 - r1) - (-q2 - r2))) / 2;
    }
```

**原文 2** `LassoProcessor.java:177-179`：

```java
    private static int hexDist(int aq, int ar, int bq, int br) {
        return (Math.abs(aq - bq) + Math.abs(ar - br) + Math.abs(-aq - ar + bq + br)) / 2;
    }
```

**原文 3** `GsimapEdgeListTool.java:87-91`：

```java
    private static int hexDist(int q1, int r1, int q2, int r2) {
        int s1 = -(q1 + r1);
        int s2 = -(q2 + r2);
        return (Math.abs(q1 - q2) + Math.abs(r1 - r2) + Math.abs(s1 - s2)) / 2;
    }
```

**原文 4** `hex-math.js:45-47`（嵌在 `hexLine` 内）：

```js
function hexLine(aq, ar, bq, br) {
  const dist = (Math.abs(aq - bq) + Math.abs(ar - br) + Math.abs(-aq-ar + bq+br)) / 2;
```

**四处都是同一个 cube 曼哈顿式 `(|dq| + |dr| + |ds|)/2`，`s = -q - r`。代数恒等，无口径分歧。**
差异仅在：`MapService.hexDistance` 是唯一的 `public` 版本，被 `GsimapGetDistanceTool.java:101` 调用；
另两处是各自类内的 `private` 复制。

### 4.2 无 BFS / A* 寻路

`git grep -n "bfs\|BFS\|aStar\|AStar\|dijkstra\|shortestPath\|findPath\|heuristic"` 全仓：
**BFS 只用于"同地形连通分量"与"套索内部填充"，没有以距离为代价的寻路**。
唯一的 `heuristic` 类搜索是文本搜索（`GsimSearchTool`），与几何无关。

### 4.3 有"半径"口径，但不是距离函数

`TerrainTextRenderer.java:103-107`、`GsimapQueryRadiusTool.java:82-83`、`ContourQueryEngine.java:97-98`、
`MapService.java:1016-1022`、`TerrainGeometry.java:182` 各自展开 `radius` 菱形/六边形范围，
**都用同一 cube 判据** `|dq| + |dr| + |ds| <= 2*radius`（或等价写法），口径一致。

### 4.4 `hexRound` 也有两份

- `TerrainGeometry.java:80-91`（public，cube 取整）
- `TerrainBlockProcessor.java:226-238`（private，逐字复制）
- `hex-math.js:13-20`（JS，逐字复制）

三份内容一致（`if (dq > dr && dq > ds) q = -r - s; else if (dr > ds) r = -q - s;`）。
另 `LassoProcessor.java:162-171` 的 `cubeRound` 是**变体**（直接对 x/y/z 取整，注释自陈
`// rz is not reassigned — the else branch is a no-op for the return value`）。

---

## Q5 坐标表述：**逐处清单**

### 5.1 `int[]` 双元素（`{q, r}`）——最普遍的内部形态

`TerrainGeometry.hexToPixel/parseHexKey/pixelToHex/hexRound`（`TerrainGeometry.java:42,68,80,110`）、
`MapData.parseHexKey`（`MapData.java:123`）、`MapService.computeCenter`（`MapService.java:944`）
返回并用 `int[]`；解引用一律是 `qr[0]` / `qr[1]`。
**`MapData.parseEdgeKey` 返回 4 元素 `[q1,r1,q2,r2]`**（`MapData.java:405-414`）：

```java
    public static int[] parseEdgeKey(String key) {
        String[] parts = key.split("\\|");
        if (parts.length != 2) throw new IllegalArgumentException("Invalid edge key: " + key);
        String[] a = parts[0].split("_");
        String[] b = parts[1].split("_");
        return new int[] {
            Integer.parseInt(a[0]), Integer.parseInt(a[1]),
            Integer.parseInt(b[0]), Integer.parseInt(b[1])
        };
    }
```

### 5.2 `String "q_r"`（hex key）

- `MapData.hexKey`（`MapData.java:113`）/ `TerrainGeometry.hexKey`（`TerrainGeometry.java:100`）
  ——**两份独立实现，字符串完全相同**。
- **实体地址**：`gsimap:hex:{q}_{r}`，及带 tag 的 `gsimap:hex:{q}_{r}:tag:{tag_key}`。
  - `GsimapResolver.java:107/117`、`GsimapGetHexTool.java:117`、`GsimapGetNeighborsTool.java:96`、
    `GsimapQueryRadiusTool.java:128`、`GsimapGetDistanceTool.java:111,116`、
    `GsimapQueryHexByTagsTool.java:107`、`GsimapSetHexTool.java:104,137`、
    `GsimapRemoveHexTagTool.java:70`、`GsimapRenderTextTool.java:102`、
    `GsimapQueryByAddressTool.java:130,140`、`HexSearchSource.java:78`、`GsimapSearchHexTool.java:110`
  - **跨模块**：`gsim-core/src/main/java/com/gsim/core/tools/ref/ResolveRefTool.java:69`：
    ```
                  gsimap:hex:<q>_<r>            — 读取地图单格
    ```
    ——**这是 `gsim-core` 里唯一的 hex 坐标表述，是给 LLM 看的文档字符串。**
- **边键**：`"minQ_minR|maxQ_maxR"`（字典序，与顺序无关），`MapData.java:390-399`：
  ```java
    public static String edgeKey(int q1, int r1, int q2, int r2) {
        String a = q1 + "_" + r1;
        String b = q2 + "_" + r2;
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
  ```
  前端**逐字重写一份**，`pathway.js:450-458`：
  ```js
  /**
   * edgeKey: "minQ_minR|maxQ_maxR" (lexicographic).
   * Must stay in sync with MapData.edgeKey() in Java.
   */
  function buildEdgeKey(q1, r1, q2, r2) {
    const a = `${q1}_${r1}`, b = `${q2}_${r2}`;
    return a <= b ? `${a}|${b}` : `${b}|${a}`;
  }
  ```
  ——注释自陈"Must stay in sync"，**靠人肉同步，无跨语言护栏**。

### 5.3 两个独立整数参数（`q` + `r`）——MCP 工具层

所有 `gsimap_*` 工具的 JSON Schema 都是**分开的 `q` / `r` 两个 integer 属性**：

| 工具 | 位置 | 参数名 |
|---|---|---|
| `GsimapGetHexTool` | `:130-131` | `q`, `r` |
| `GsimapGetNeighborsTool` | `:112-113` | `q`, `r` |
| `GsimapQueryRadiusTool` | `:144-145` | `q`, `r`（"of center"） |
| `GsimapRemoveHexTagTool` | `:43-44` | `q`, `r` |
| `GsimapRenderTextTool` | `:271-272` | `q`, `r`（"of center"） |
| `GsimapSetHexTool` | `:58-59` | `q`, `r`（"single-hex mode"） |
| `GsimapEdgeListTool` | `:38-39` | `q`, `r`（"Optional center q for radius filter"） |
| `GsimapGetDistanceTool` | `:144-149` | **`fromQ`/`fromR`/`toQ`/`toR`** ← 命名不同 |

**`GsimapGetDistanceTool` 是唯一用 `fromQ/fromR/toQ/toR` 的**（`GsimapGetDistanceTool.java:144-149`），
其余全是裸 `q`/`r`。同文件也允许 `fromRegion`/`toRegion` 二选一。

### 5.4 `record`（Java 类型化坐标）

- `MapData.City`（`MapData.java:327-332`）：`int q, int r` —— **唯一存坐标的实体**。
- `MapData.Pt`（`MapData.java:167`）：`record Pt(double x, double y)` —— **像素点**，不是 hex 坐标。
- `ContourQueryEngine.TerrainSample`（`ContourQueryEngine.java:62`）：`(double height, String terrain, String color)`
  —— **不含坐标**。
- `MapGenerator.Ridge/Pt`（`MapGenerator.java:26-28`）：`record Pt(double x, double y)` ——
  **轮廓空间点**，见 5.6。
- `ContinentContour.Pt`（`ContinentContour.java` 内嵌类，getter 风格 `getX()/getY()`）—— 同上。

**注意**：仓库里**有两个不同的 `Pt` record**（`MapData.Pt` 用 `x()/y()` 访问器，
`MapGenerator.Pt` 也是 `x()/y()`，`ContinentContour.Pt` 是 `getX()/getY()`）。

### 5.5 输出侧的表述（混用）

- 字符串（"q,r" 风格）：`GsimapGetNeighborsTool.java:68`：`"## Neighbors of (" + q + "," + r + ")"`；
  `GsimapGetDistanceTool.java:97-98,106,108`：`"(" + fromQ + "," + fromR + ")"`。
- JSON 对象：`MapWebUIHandler.java:647`：
  ```java
        sendJson(exchange, 200, Map.of("q", q, "r", r, "terrain", terrain != null ? terrain : "empty"));
  ```
- JSON 数字字段：`GsimapResolver.java:163` / `GsimapQueryByAddressTool.java:184`：
  ```java
        result.put("q", city.q());
  ```
- MCP 结果的 `Item.path()`：`worldId + ":" + nodeId + ":" + hexKey`（`GsimapGetNeighborsTool.java:102`），
  READ 类工具则给 `"gsimap:hex:..."`。**同一工具族里 path 前缀有两种形态。**

### 5.6 ★ 像素空间有两种**标度**（跨层不一致）

**(a) `SIZE=30` + √3 标度**（"前端像素系"）—— `TerrainGeometry`、`TerrainBlockProcessor.hexToPixel`、
前端 `hex-math.js`、`MapWebUIHandler.populateTerrainBlocks` 产出的 `MapData.Pt`。
`TerrainBlockProcessor.java:221-224`：

```java
    private static final double GRID = 30.0;
...
    private double[] hexToPixel(int q, int r) {
        return new double[] {GRID * (Math.sqrt(3) * q + Math.sqrt(3) / 2 * r), GRID * (3.0 / 2 * r)};
    }
```

**(b) 无标度轮廓系** `px = q + r*0.5, py = r*0.8660254`（"轮廓系"）——
`ContourQueryEngine.java:135-136`（`compute`）与 `:277-278`（`isHexInPolygon`）：

```java
        double px = q + r * 0.5;
        double py = r * 0.8660254;
```

同样两行也出现在 `TerrainBlockProcessor.java:96-97`（`queryTerrain`）与 `:147-149`
（`hexSetFromPolygon` 的 flood-fill 接受判据）：

```java
            double px = qr[0] + qr[1] * 0.5;
            double py = qr[1] * 0.8660254;
```

**两系的换算关系（代数推导，非实测）**：`√3·px = X/SIZE`、`√3·py = Y/SIZE`
（因 `√3·(q + 0.5r) = √3 q + (√3/2) r`、`√3·0.8660254 = 1.5`）。即**同轴同向，仅差常数倍 `SIZE/√3 = 17.32`**。
**但 `TerrainBlockProcessor` 在同一个类里同时用两系**：
`:135` 的 `pixelToHex(cx, cy)` 用 `GRID=30`（a 系），
`:147-149` 的 flood-fill 接受判据却用 b 系无标度值，**两者相差 `√3` 倍**。
该类**全仓无构造者、无调用者**（`git grep TerrainBlockProcessor` 除自身文件外零命中），
类注释 `TerrainBlockProcessor.java:16`（`@deprecated`）与 `:18`（`@Deprecated`）自陈：

```java
 * @deprecated Replaced by {@link TerrainCanvas}. Retained for reference.
```

**另一处跨系混用**：`MapWebUIHandler.populateTerrainBlocks`（`MapWebUIHandler.java:740-801`）。
`ContinentContour.Ridge` 的点是 `MapGenerator.placeRidges` 用 `radius * …` 生成的**轮廓系**值
（`MapGenerator.java:55-118`，例如 `radius * (1.3 + rng.nextDouble() * 0.4)`）。
而 `MapWebUIHandler.java:764-766`：

```java
                // Convert axial → pixel (with GRID scaling for TerrainGeometry)
                double px = (p.getX() + p.getY() * 0.5) * grid;
                double py = p.getY() * 0.8660254 * grid;
```
—— 注释自称"axial → pixel (with GRID scaling for TerrainGeometry)"，但把**轮廓系的 (x, y) 当作 axial (q, r)** 代入，
且 `TerrainGeometry` 的正确式子是 `30*(√3 q + √3/2 r)`（含 √3），此处为 `30*(q + 0.5r)`（不含）。
同一函数 `:791-798` 又把**未乘 `grid` 的轮廓系原点**直接喂给 `TerrainGeometry.pixelToHex`（内部除 `SIZE=30`）：

```java
            String seedKey = TerrainGeometry.hexKey(
                    TerrainGeometry.pixelToHex(
                            ridge.getPoints().get(0).getX(),
                            ridge.getPoints().get(0).getY())[0],
                    TerrainGeometry.pixelToHex(
                            ridge.getPoints().get(0).getX(),
                            ridge.getPoints().get(0).getY())[1]);
```

**记录为事实：同一函数内三种标度混用。**（未跑运行时验证，见"未核实"。）

### 5.7 `TerrainTextRenderer` 自称 flat-top，公式是 pointy-top

`TerrainTextRenderer.java:14-16`：

```java
 * <p>Each terrain type is mapped to a single visible character. Flat-top hex
 * orientation is rendered with offset rows — odd-r rows are indented one
 * space to simulate the staggered hex grid layout.
```

`TerrainTextRenderer.java:140-141` 与 `:475-476`（两处逐字重复）：

```java
            // Flat-top hex: odd-r rows are indented one space
            if ((r & 1) != 0) sb.append(' ');
```

即文本渲染走 **offset 行缩进（odd-r）**，与 `hexToPixel` 的 axial 直投**不是同一套布局**。
`MapData.hexOrientation` 在此**未被读取**。

### 5.8 前端 `edges` ↔ `edgeTags` 的双向转换（跨层格式转换点）

`pathway.js:460-515` 的 `syncEdgesToHexTags()` / `syncHexTagsToEdges()` 在
`"minQ_minR|maxQ_maxR"` 与 `edgeTags[0..5]` 之间互转，**用 `direction()`（A 序）**建立索引。
后端 `MapData.edgeKey`/`parseEdgeKey` 只处理字符串，**不涉及方向索引**，故此处无错位。

---

## Q6 海拔 / 高度：**不落盘、不对外**

**`MapData.HexCell` 没有 height/elevation 字段。** 完整字段清单见 `MapData.java:184-192`：
`color, terrain, symbol, symbolColor, description, riverMask, edgeTags, tags`。
`git grep -n "height\|elevation" -- '*.java'` 在 `gsim-map` 的命中**全部**指向：

**写方（唯一）**：`ContourQueryEngine.compute`（`ContourQueryEngine.java:125-175`）算出 `double height`：

```java
        // Height assembly — ridge weight raised for wider mountain spines
        double height = ridgeH * 0.68 + shelf * 0.35 + multi * 0.45 - valley;
        height = Math.max(0, height);
        height = Math.pow(height, 0.92); // 0.85→0.92 让山脉更宽更长条
...
        if (height < seaLevel) {
            return new TerrainSample(height, "water", "#3295D2");
        }
...
        return new TerrainSample(height, terrain, color);
```

**承载**：`ContourQueryEngine.java:62`：

```java
    public record TerrainSample(double height, String terrain, String color) {
```

**读方**：`git grep -n "\.height()\|TerrainSample"` 全部命中如下——
- `ContourQueryEngine` 自身（LRU 缓存 `Map<String, TerrainSample>`，`:21`；`query`/`compute` 返回）。
- `MapService.queryTerrain`（`MapService.java:860-871`）：**把 height 直接丢掉**——
  ```java
            if (cell == null) return new ContourQueryEngine.TerrainSample(0, "water", "#3295D2");
            return new ContourQueryEngine.TerrainSample(0.5, cell.terrain(), cell.color());
  ```
  （fallback 分支写死 `0` 或 `0.5`）。
- `MapService.expand`（`MapService.java:1063-1067`）只取 `sample.terrain()` 与 `sample.color()`，
  **不用 height**：
  ```java
                    var sample = engine.query(q, r);
                    terrain = sample.terrain();
                    color = sample.color();
  ```

**`.height()` 这个访问器在全仓（`gsim-map/**`、`gsim-core/**`、`gsim-app/**`、前端 JS、`docs/`）
没有任何调用点。** 前端 `state.js:9-18` 的 `DEFAULT_TERRAINS` 是 `food/gold/stone/moveCost`，
**无海拔**。`docs/DATA-MODEL.md` 中 `HexCell` 字段清单亦无 height。

**结论**：height 是 `ContourQueryEngine` 的**内部中间量**，只用于 `classify(height, …)` 分档与
`height < seaLevel` 判水，**不进 `MapData`、不进 JSON、不进 MCP、不进前端**。

---

## 未核实 / 卡在哪

1. **`MapWebUIHandler.populateTerrainBlocks` 的标度混用是否产生实际可见的错误**——
   我只做了公式的代数比对（5.6），**没有运行生成流程去看产出的 block 落在哪里**。
   卡点：需要起 HTTP 服务并调 `POST /api/map/{world}/generate`，属于运行时验证，本次只读取证未做。
   **我只报"两个公式不同"，不报"结果一定错"。**
2. **`TerrainBlockProcessor` 的 `√3` 标度差是否真会导致行为差异**——
   同上，只做代数比对。且该类**全仓无调用者**（已用 `git grep` 确认），是否仍被反射/配置引用未查。
3. **两处 `hexRound`（`TerrainGeometry` vs `TerrainBlockProcessor`）是否在边界输入上分叉**——
   我逐行比对了文本，**未跑差分测试**。
4. **前端渲染的实际视觉效果**——我没有打开浏览器看 `render.js` 画出来的六边形朝向，
   A 序"顺时针"的判定是从 `hexCorners` 的 `60*i - 30` 公式 + `y` 向下的屏幕坐标系推导出来的，
   **不是肉眼确认的**。
5. **历史**：我**没有**查 git log/blame 去判断两份方向表谁先谁后、是否曾有意为之。
6. **`~/.m2` / 构建**：本次未跑任何 Maven 命令，所有结论来自源码阅读与 `git grep`。
7. **`MapService.java` 1771 行未逐行通读**——我按 `HEX_DIRS`/`hexDistance`/`hexKey`/`parseHexKey`
   四个锚点做了定位式阅读（932-1100、1670-1731、855-895、186-265 等段），
   **可能存在我未触及的方向/距离相关代码**。缓解：Q2 的"共 6 份"结论是用
   `git grep -n "int\[\]\[\]"`（全仓、不限目录）与 `git grep -n "HEX_DIRS\|DIRS\b\|DIRECTIONS\|NEIGHBOR"` 双重确认的，
   **方向表清单本身是完备的**；但"谁在用"的消费者清单以同一组 grep 的命中为界。
