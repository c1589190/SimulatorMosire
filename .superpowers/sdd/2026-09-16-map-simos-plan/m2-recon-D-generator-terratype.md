# M2 取证 D — 生成算法参数面 / L7 / L8 / L9 / 地形词表

取证对象：`/home/cna/DevMosire/GSimulator` @ `88d0f022a246e85bb33fcaa48b9dd0e533e3e859`（分支见仓库）
取证方式：只读。全程用 `git grep`（本机 grep 为 ugrep，会尊重 .gitignore 并跳过隐藏目录，已规避）。
取证人：只读取证者（不做设计建议）。

---

## 0. 一段话结论

GSimulator 的地形生成是**单条硬编码流水线**：`MapGenerator.placeRidges` 造山脊线 → `generateContour` 打包成 `ContinentContour`（10 参数构造）→ `ContourQueryEngine.compute` 按需求高度并**当场分类成地形字符串** → `materialize` 落成 `MapData`。参数面**只暴露了 5 个**（seed/radius/ridges/fragments/landRatio），其中 `worldId` 与 `coastRoughness` 两个形参在 `MapGenerator.generate` 内**从未被引用**（纯装饰）；真正决定地貌的 ~40 个阈值/频率/权重**全部是方法体内的魔法数字**，不可配置、不可落盘、无任何测试覆盖（全仓**没有** `MapGeneratorTest`/`ContourQueryEngineTest`）。种子只活在 `MapGenerator` 的实例字段与 `ContinentContour.seed` 里，而 `ContinentContour` **只有 HTTP 路径**（`MapWebUIHandler.handleGenerate`）会 `saveContour`，**MCP 路径**（`MapService.generate` → `GsimapGenerateTool`）不写 contour——落盘的 `MapData` 12 个字段里**既无 seed 也无 height**，海拔在 `materialize` 那一步被直接丢弃（`TerrainSample.height` 是唯一的载体，且只存在于内存）。地形词表**至少 6 份互不相同的副本**（2 份 Java 默认表 + 1 份前端 JS + 1 份 ASCII 字符表 + 1 份工具校验清单 + 1 份文档），其中 `MapData.defaults()`（8 项，无 lowland）与 `MapGenerator.defaultTerrainTypes()`（9 项，有 lowland）在 **`plains` 的颜色/产出/语义**上完全分叉——`MapData.defaults()` 的 `plains` 是绿色平原 `#6CC261`，`MapGenerator` 的 `plains` 是"山区" `#B8A88A`；而 `ContourQueryEngine.terrainColor` 的 `default -> "#6CC261"` 用的正是**另一份词表**的平原绿。实测落盘数据（`worlds/*/nodes/n0000_map.json`）用的是**生成器版 9 项表**。用户要求新增的**框选随机化**（按两种地形理想占比随机重填充）与**自动河流**（按海拔从高到低生成）在 GSimulator 中**都不存在**：`LassoProcessor` 只做"闭合套索 + 洪水填充"，`tracePathway` 只做**读**已存在的 edges 链，没有任何一处按海拔生成水系；`height` 甚至无法在落盘后取回。

---

## 1. 生成算法完整参数面（含魔法数字）

### 1.1 真正的"参数"（有名字、可传的）

| 名字 | 类型 | 默认值 | 含义 | 写在哪 |
|---|---|---|---|---|
| `seed` | `long` | 无（必传） | `new Random(seed)` 的种子；**`ContinentContour` 的 seed 是 `rng.nextLong()` 派生值，不是它** | `MapGenerator.java:34/44` 构造器 |
| `mapRadius` / `radius` | `int` | 80 | 六角格半径，同时是**所有频率的分母**（`1.8/radius` 等） | `MapGenerator.java:44`；默认值在 `GsimapGenerateTool.java:60`、`MapConfig.java:36` |
| `contourCacheMax` | `int` | 5000 = `ContourQueryEngine.MAX_CACHE` | 查询引擎 LRU 上限 | `MapGenerator.java:44`；`ContourQueryEngine.java:22` |
| `mainCount` / `ridges` | `int` | 2 | 主山脉链数；**被硬夹到 1–2**，传 >2 静默失效 | `MapGenerator.java:56`；夹取在 `:61` |
| `fragmentCount` / `fragments` | `int` | 5 | 碎片总数；实际是"次级 ≥2 + 剩余为碎片"，`frags = fragmentCount - secondary` 可为负 | `MapGenerator.java:56`；派生在 `:85`、`:105` |
| `landRatio` | `double` | 0.55 | **唯一真正进入地形的比率**——只用于 `baseSeaLevel = 0.18 + (1-landRatio)*0.05` | `MapGenerator.java:129/164` |
| `worldId` | `String` | — | javadoc 说"for terrain types"，**函数体内从未引用** | `MapGenerator.java:204`（7 参）、`:235`（8 参） |
| `coastRoughness` | `double` | 0.6 | javadoc 说"coastline roughness factor"，**函数体内从未引用**——纯装饰参数 | `MapGenerator.java:210`、`:241` |

**方法签名原文（`MapGenerator.java:234-246`）**——注意两个形参完全没出现在体里：

```java
    public static MapData generate(
            String worldId,
            long seed,
            int mapRadius,
            int mainRidges,
            int fragments,
            double landRatio,
            double coastRoughness,
            int contourCacheMax) {
        var gen = new MapGenerator(seed, mapRadius, contourCacheMax);
        gen.placeRidges(mainRidges, fragments);
        return gen.generate(landRatio);
    }
```

### 1.2 `MapConfig` —— 与生成算法**无关**的 7 个限流参数

`gsim-map/src/main/java/com/gsim/map/config/MapConfig.java:26-41`，`DEFAULT = new MapConfig(80, 32, 5000, 200, 30000, 100, 200)`：

| 字段 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `defaultMapRadius` | `int` | 80 | 曾是 `TerrainCanvas.DEFAULT_MAP_RADIUS` |
| `cacheMaxEntries` | `int` | 32 | `MapService` MapData LRU |
| `contourCacheMax` | `int` | 5000 | `ContourQueryEngine` LRU |
| `lassoMaxRadius` | `int` | 200 | 套索洪水填充坐标界 |
| `lassoMaxFill` | `int` | 30000 | 套索填充格数上限 |
| `minRegionSize` | `int` | 100 | 压缩最小区域 |
| `maxChainDepth` | `int` | 200 | 引用链深度 |

⚠️ 这 7 个数在仓库里有**三份拷贝**：`MapConfig.java:36`（`DEFAULT`）、`MapConfig.java:40`（无参构造器，重复 7 个字面量）、`gsim-app/src/main/java/com/gsim/app/AppConfig.java:261-267`（从 `gsim.properties` 读，默认值第三次写死）。`gsim.properties:137-149` 里 7 个 key **全部被注释掉**。

### 1.3 默认参数值的多份拷贝（**已实测出分歧**）

| 位置 | radius | ridges | fragments | landRatio | roughness | seed |
|---|---|---|---|---|---|---|
| `GsimapGenerateTool.java:60-64` | 80 | 2 | 5 | 0.55 | 0.6 | `System.currentTimeMillis()` |
| `MapWebUIHandler.java:418-423` | `mapConfig.defaultMapRadius()`=80 | 2 | 5 | 0.55 | 0.6 | `System.currentTimeMillis()` |
| `web/index.html:55-60`（前端滑杆） | **120** | 2（1–6） | 5（1–15） | 0.55（0.25–0.80） | **0.5**（0.1–1.0） | 空 |

★ **`radius` 前端默认 120 vs 后端默认 80**；**`roughness` 前端默认 0.5 vs 后端 0.6**（后者还是死参数）。

### 1.4 ★ 魔法数字全表（散落在方法体内，**未提取成任何参数**）

**A. `MapGenerator.placeRidges`（`MapGenerator.java:56-118`）**

| 行 | 表达式 | 含义 |
|---|---|---|
| 58 | `rng.nextDouble() * Math.PI` | 主造山方向 0–180° |
| 61 | `Math.max(1, Math.min(mainCount, 2))` | 主脊链数**硬夹 1–2** |
| 63 | `+(rng.nextDouble()*0.5 + 0.3) * ±1` | 第 2 条主脊偏角 0.3–0.8 rad |
| 64 | `radius * (1.3 + rng.nextDouble()*0.4)` | 主脊长度 1.3–1.7 × radius |
| 67 | `radius * (0.20 + rng.nextDouble()*0.30)` | 离中心偏移 0.20–0.50 × radius |
| 68,69 | `rng.nextGaussian() * radius * 0.03` | 起点抖动 3% |
| 71 | `rng.nextDouble() * radius * 0.18 * ±1` | 贝塞尔控制点 0–18% radius |
| 75,79 | `len * 0.48` / `len * 0.52` | 控制点半长 |
| 75,76,79,80 | `radius * 0.02` / `radius * 0.03` | 端点抖动 2% / 3% |
| 81 | `0.75 + rng.nextDouble()*0.25` | 主脊权重 0.75–1.00 |
| 85 | `Math.max(2, fragmentCount / 2)` | **次级脊数派生**（最少 2） |
| 87 | `(rng.nextDouble()*0.4 + 0.12) * ±1` | 次级偏角 0.12–0.52 rad |
| 88 | `radius * (0.10 + rng.nextDouble()*0.28) * ±1` | 次级垂直距离 0.10–0.38 × radius |
| 89 | `radius * (0.50 + rng.nextDouble()*0.45)` | 次级长度 0.50–0.95 × radius |
| 90,92 | `radius * (0.05 + rng.nextDouble()*0.22)` | 次级沿主方向起点 0.05–0.27 × radius |
| 96–100 | `len*0.5` + `radius*0.02` 抖动 | 次级端点 |
| 101 | `0.25 + rng.nextDouble()*0.30` | 次级权重 0.25–0.55 |
| 105 | `fragmentCount - secondary` | 碎片数派生（**可为负 → 0 个碎片**） |
| 107 | `rng.nextDouble() * 2 * Math.PI` | 碎片方向全向 |
| 108 | `radius * (0.35 + rng.nextDouble()*0.50)` | 碎片距心 0.35–0.85 × radius |
| 111 | `radius * (0.04 + rng.nextDouble()*0.08)` | 碎片长 0.04–0.12 × radius |
| 112 | `angle + rng.nextGaussian() * 0.5` | 碎片角度抖动 σ=0.5 |
| 116 | `0.10 + rng.nextDouble()*0.15` | 碎片权重 0.10–0.25 |
| 66 | `angle + Math.PI / 2` | 垂直主轴 |

**B. `MapGenerator.generateContour`（`MapGenerator.java:129-157`）**

| 行 | 表达式 | 含义 |
|---|---|---|
| 130 | `0.18 + (1.0 - landRatio) * 0.05` | **唯一由 landRatio 决定的量**：landRatio 0.25→0.2175，0.80→0.1900 |
| 131 | `1.8 / radius` | shelfFreq |
| 132 | `3.5 / radius` | lowFreq |
| 133 | `8.0 / radius` | midFreq |
| 134 | `20.0 / radius` | highFreq |
| 135 | `3.5 / radius` | coastFreq |
| 147 | `rng.nextLong()` | **contour 的 seed 是派生的**，非入参 seed |
| 167 | `-radius, radius, -radius, radius` | materialize 的 q/r 包围盒 |

**C. `ContourQueryEngine.compute`（`ContourQueryEngine.java:125-175`）**

| 行 | 表达式 | 含义 |
|---|---|---|
| 131 | `new TerrainSample(0.5, ...)` | 命中编辑器图层时**硬编码高度 0.5** |
| 135,136 | `px = q + r*0.5`，`py = r*0.8660254` | 轴向→像素（无 grid 缩放） |
| 139,140 | `noise2(px*0.018, py*0.018) * 10` | **域扭曲**：频率 0.018、幅度 10 |
| 140 | `+ 70` | 域扭曲第二通道偏移 |
| 147 | `Math.max(0, shelf*0.35 + 0.15)` | shelf 偏移 0.15、增益 0.35 |
| 150–152 | `+100` / `+300` / `+500` | 三个噪声带偏移 |
| 153 | `n1*0.40 + n2*0.25 + n3*0.12` | **多带权重 0.40 / 0.25 / 0.12** |
| 159 | `ridgeH*0.68 + shelf*0.35 + multi*0.45 - valley` | **高度合成权重** |
| 160 | `Math.max(0, height)` | 下限截断 |
| 161 | `Math.pow(height, 0.92)` | **gamma（注释自述 0.85→0.92）** |
| 164 | `+ 77` | 海岸噪声偏移 |
| 165 | `baseSeaLevel + coastNoise*0.35` | 海平面抖动幅度 0.35 |
| 185 | `k = 5.5 + weight * 2.0` | 山脊衰减率 |
| 186 | `exp(-d * k / radius)` | 山脊核 |
| 193 | `ridges.size() < 2 → 0` | 峡谷惩罚需要 ≥2 条脊 |
| 203 | `sigma = radius * 0.10` | 峡谷高斯 σ |
| 204 | `exp(...)*exp(...)*0.30` | 峡谷强度 0.30 |
| 215 | `len2 < 0.001` | 退化线段阈值 |

**D. `ContourQueryEngine.classify`（`ContourQueryEngine.java:229-258`）—— 分类阈值全表**

| 行 | 条件 | 结果 |
|---|---|---|
| 230 | `noise2(px*0.02 + 500, py*0.02 + 500)` | 湿度场（频率 0.02，偏移 +500） |
| 233 | `height > 0.68` | `mountain` |
| 237 | `noise2(px*(2.0/radius) + 900, ...)` | hillsNoise（偏移 +900） |
| 239 | `noise2(px*(1.5/radius) + 800, ...)` | plainsNoise（偏移 +800） |
| 242 | `height > 0.48` | `moisture > 0.10 ? hills : plains` |
| 243 | `height > 0.36` | `moisture > 0.0 ? hills : plains` |
| 245 | `height > 0.14 && hillsNoise > 0.50` | `hills` |
| 248 | `height > 0.22 && plainsNoise > 0.28` | `plains` |
| 249 | `height > 0.14 && plainsNoise > 0.42` | `plains` |
| 253,254 | `height > 0.12` 且 `noise2(px*0.05 + 600, ...) > 0.50` | `plains` |
| 255 | `height > 0.12` 否则 | `lowland` |
| 257 | 兜底 | `moisture > 0.15 ? swamp : lowland` |

**E. 其余**

| 文件:行 | 值 | 含义 |
|---|---|---|
| `ContourQueryEngine.java:22` | `MAX_CACHE = 5000` | 默认 LRU |
| `ContourQueryEngine.java:98` | `|q|+|r|+|s| > 2*radius` | 六角盘裁剪 |
| `LassoProcessor.java:23` | `DIRS` 六向 | 填充邻接 |
| `LassoProcessor.java:24,25` | `MAX_RADIUS=200`、`MAX_FILL=30000` | 遗留常量（`MapConfig` 未接管无参重载 `fill(keys)`） |
| `LassoProcessor.java:66` | `hexDist <= 1 → 不桥接` | 相邻判据 |
| `TerrainCanvas.java:36` | `DEFAULT_MAP_RADIUS = 80` | 第三份 radius 默认值 |
| `TerrainCanvas.java:89` | `hs.size() <= 500` | 超此不落 boundary |
| `MapWebUIHandler.java:748` | `radius * (weight > 0.8 ? 0.12 : 0.06)` | 山脊块宽度 |
| `MapWebUIHandler.java:749` | `grid = 30.0` | 像素栅格 |
| `MapWebUIHandler.java:791` | `weight > 0.8 ? "hills" : "plains"` | terrain block 地形二选一 |

---

## 2. ★ L8：「12 参数构造」的真相

**结论：那个 12 参数构造器是 `MapData` 的 record 规范构造器**（`gsim-map/src/main/java/com/gsim/map/map/MapData.java:33-46`）。仓库里**没有** `MapData` 的其它构造器（只有一个 compact 构造器 `MapData.java:47-69` 做归一化/冻结），所以每一处 `new MapData(...)` 都**必须**手写 12 个实参。

### 2.1 原文签名（`MapData.java:32-46`）

```java
@JsonDeserialize
public record MapData(
        @JsonProperty("gridSize") int gridSize,
        @JsonProperty("hexOrientation") boolean hexOrientation,
        @JsonProperty("hexes") Map<String, HexCell> hexes,
        @JsonProperty("terrainBlocks") List<TerrainBlock> terrainBlocks,
        @JsonProperty("provinces") Map<String, Province> provinces,
        @JsonProperty("cities") Map<String, City> cities,
        // ══ 已废弃：rivers/roads 将在分门别类的地块连通性系统（PathwayGroup）中重建 ══
        @JsonProperty("rivers") List<River> rivers,
        @JsonProperty("roads") List<Road> roads,
        @JsonProperty("terrainTypes") Map<String, TerrainType> terrainTypes,
        @JsonProperty("compressedRegions") List<CompressedRegion> compressedRegions,
        @JsonProperty("pathwayGroups") Map<String, PathwayGroup> pathwayGroups,
        @JsonProperty("edges") Map<String, Map<String, Map<String, Object>>> edges) {
```

### 2.2 12 个参数逐个列出

| # | 名字 | 类型 | 含义 |
|---|---|---|---|
| 1 | `gridSize` | `int` | 网格宽/高（1–1000，越界抛 `IllegalArgumentException`，`MapData.java:48-49`） |
| 2 | `hexOrientation` | `boolean` | true=pointy-top，false=flat-top |
| 3 | `hexes` | `Map<String, HexCell>` | 全部六角格，key `"q_r"` |
| 4 | `terrainBlocks` | `List<TerrainBlock>` | 有序多边形地形块（末位=最上层） |
| 5 | `provinces` | `Map<String, Province>` | 区域定义，keyed by id |
| 6 | `cities` | `Map<String, City>` | 城市定义，keyed by id |
| 7 | `rivers` | `List<River>` | **已废弃**（`MapData.java:40` 注释） |
| 8 | `roads` | `List<Road>` | **已废弃**（同上） |
| 9 | `terrainTypes` | `Map<String, TerrainType>` | 地形类型定义，keyed by name |
| 10 | `compressedRegions` | `List<CompressedRegion>` | 渲染用轮廓凸包缓存 |
| 11 | `pathwayGroups` | `Map<String, PathwayGroup>` | 通路组定义（river/road） |
| 12 | `edges` | `Map<String, Map<String, Map<String, Object>>>` | 稀疏边映射 `edgeKey → {pathwayId → {prop → value}}` |

### 2.3 复制处逐处（脚本逐括号数实参，全部 = 12）

**总 36 处。** 主源码 **17 处**、测试 **19 处**。

**主源码 17 处：**

| 文件:行 |
|---|
| `gsim-map/src/main/java/com/gsim/map/map/MapData.java:91`（`empty()`） |
| `gsim-map/src/main/java/com/gsim/map/map/MapResolver.java:255` |
| `gsim-map/src/main/java/com/gsim/map/service/CompressionValidator.java:224` |
| `gsim-map/src/main/java/com/gsim/map/service/ContourQueryEngine.java:106`（**全限定名 `new com.gsim.map.map.MapData(`**） |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:289` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:358` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:406` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:515` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:596` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:645` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:907` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:1087` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:1145` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:1200` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:1235` |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:1328`（`withProvinces`） |
| `gsim-map/src/main/java/com/gsim/map/service/MapService.java:1346`（`withTerrainTypes`） |

**测试 19 处：** `HexCellTagsTest.java:23`、`MapServiceChildNodeSaveTest.java:48`、`MapServiceHexTagsTest.java:45`、`MapServiceTracePathwayTest.java:16`、`TerrainTextRendererRegionTest.java:42/123/153`、`GsimapQueryByAddressToolTest.java:42`、`GsimapQueryHexByTagsToolTest.java:41`、`GsimapRemoveHexTagToolTest.java:38`、`GsimapRenameRegionToolTest.java:58`、`GsimapRenderTextToolTest.java:38`、`GsimapResolverTest.java:37`、`GsimapSetHexToolTest.java:45`、`GsimSearchToolTest.java:70`、`GsimapSearchHexToolTest.java:59/86/230`、`GsimapSearchRegionToolTest.java:46`。

### 2.4 核实"复制了几次"——**与总纲陈述有出入**

| 陈述 | 实测 |
|---|---|
| 总纲 spec `:57`「`new MapData(...)` 12 参数构造复制 **12 次**；`MapService` 内 **12 处**」 | **`MapService.java` 内是 13 处**（上表 13 行），主源码 17 处，全仓 36 处 |

- "12 参数"对：构造器确为 12 个分量。
- "12 次/12 处"**不准确**：`MapService` 是 13 处；若把整个主源码算上是 17 处；全仓（含测试）36 处。
- 附带事实：该事故根因文档 `bugs/2026-08-01-logic-edges-wiped-on-rebuild.md:30-34` 记录的是**6 处**重建路径把 `edges` 硬编码成 `Map.of()`（persistBlocks、renameRegion、expand、compress、decompress、decompressAt），现已修复为透传 `map.edges()`。**"6 处"是那一次事故的触发面，不是 `new MapData(` 的全部调用点。**

---

## 3. ★ L7：种子与海拔落盘实况

### 3.1 有种子，但**分两层，且层间靠派生**

- 入参 `seed`（`long`）→ `MapGenerator.java:45` `this.rng = new Random(seed)`。
- `MapGenerator.java:147` 在 `generateContour` 里用 **`rng.nextLong()`** 造 contour 的 seed，写进 `ContinentContour.seed`（`ContinentContour.java:14`、`:57`）。**它是派生值，不是入参 seed**；同一入参 seed 下由于 `placeRidges` 的取数次数固定，派生值可复现，但**入参 seed 本身不被记录**。
- `ContourQueryEngine.java:41` `this.noise = new SimplexNoise(contour.getSeed())` —— 噪声只认 contour 的派生 seed。

**种子有没有写进 `MapData`/存档？**
- **`MapData` 的 12 个字段里没有 seed**（见 §2.2）。
- 唯一的种子落盘载体是 `ContinentContour`（`MapService.saveContour` → `n0000_contour.json`）。
- **但只有 HTTP 路径会调它**：`MapWebUIHandler.java:429` `mapService.saveContour(worldId, contour);`。
- **MCP 路径不调**：`MapService.generate(...)`（`MapService.java:1281-1309`）只做 `MapGenerator.generate(...)` + `saveMap(...)`，**没有 `saveContour`**。
- 实测印证：`find worlds -name '*contour*'` 只命中 **2 个**（`worlds/api-test/nodes/n0000_contour.json`、`worlds/demo/nodes/contour.json`），而 `*_map.json` 有 **9 个**。`worlds/default`（terrainTypes 是 9 项生成器版）**没有 contour 文件**。
- 实测 contour 文件内容（`worlds/api-test/nodes/n0000_contour.json`）：`seed: -2894201620038283915`、`landRatio: 0.55`、`baseSeaLevel: 0.2025`、`shelfFreq: 0.045`、`lowFreq: 0.0875`、`midFreq: 0.2`、`highFreq: 0.5`、`coastFreq: 0.0875`、`ridges: 7 条`、`editorLayers: []`。

### 3.2 海拔算得出来，**算出来就丢**

- 高度确实在生成时算出：`ContourQueryEngine.java:159` `double height = ridgeH*0.68 + shelf*0.35 + multi*0.45 - valley;`，`:160-161` 截断 + gamma。
- 载体是**内存局部量 + 瞬态 record**：`ContourQueryEngine.java:62` `public record TerrainSample(double height, String terrain, String color)`。
- **丢弃点**：`ContourQueryEngine.java:100-103` ——
  ```java
                TerrainSample ts = query(q, r);
                hexes.put(
                        q + "_" + r,
                        new com.gsim.map.map.MapData.HexCell(
                                ts.color, ts.terrain, null, null, "", 0, Map.of(), Map.of()));
  ```
  `ts.height` **没有被读**，`HexCell` 的 8 个字段（color/terrain/symbol/symbolColor/description/riverMask/edgeTags/tags）**没有 height 位**。
- 全仓 `git grep -n "height" -- gsim-map/src/main/java` 的命中**全部落在 `ContourQueryEngine` 内部**，`MapData` 一处都没有（`MapData.java:16` 那个 `height` 是"网格宽/高"的注释）。
- 实测落盘 JSON：任一 hex 的字段是 `['color','terrain','symbol','symbolColor','description','riverMask','edgeTags','tags']`，**无 height、无 seed**（脚本对全部 hex 值扫过 `'height' in v or 'seed' in v` → `False`）。
- 旁证：`MapService.decompressAt`（`MapService.java:1064-1068`）在无 contour 时**硬编码 `terrain="lowland"`、`color="#5B8C3E"`** —— 没有海拔可用，只能猜默认地形。

### 3.3 `MapStore` 实际写了哪些字段

`MapStore`（`gsim-map/src/main/java/com/gsim/map/map/MapStore.java`）本身**不决定字段**，它只是把 `MapData`/`MapDiff` 交给 `NodeLoader.saveAttachmentFile`（`MapStore.java:54-58`、`:72-76`）：

- 路径：`NodeLoader.attachmentFilePath(worldsDir, worldId, nodeId, "map")` → `worlds/{worldId}/nodes/{nodeId}_map.json`（`MapStore.java:31-33`）。
- 写：`NodeLoader.java:115-123` `Files.writeString(attachFile, JsonUtils.toJson(data))`，并在 `nXXXX.json` 的 `attachments` 里记一条 `{"_file":"nXXXX_map.json","_type":"external"}` 轻引用（`NodeLoader.java:124-140`）。
- 序列化：`JsonUtils.MAPPER`（`gsim-docslib/src/main/java/com/gsim/docslib/util/JsonUtils.java:13-17`）**没有设置 inclusion**，故落盘字段 = `MapData` 的 12 个 `@JsonProperty` 名，即 `gridSize, hexOrientation, hexes, terrainBlocks, provinces, cities, rivers, roads, terrainTypes, compressedRegions, pathwayGroups, edges`。
- 实测 12 字段全在（`worlds/api-test`、`mcp_smoke_test`、`f3_qa`、`f3qa`、`mcp_baseline_test`）；较老的 4 个文件（`default`、`demo`、`north-china-1980s`、`test_integration`）**缺 `edges`**，是 `edges` 字段加入之前的存量存档。
- **没有 height，没有 seed。** `rivers`/`roads` 恒为 `[]`（已废弃）。

---

## 4. ★ L9：地形词表全清单（逐份原文，穷尽）

### 词表 A — `MapData.TerrainType.defaults()`，**8 项**
`gsim-map/src/main/java/com/gsim/map/map/MapData.java:268-279`（原文）
```java
            var tt = new LinkedHashMap<String, TerrainType>();
            tt.put("water", new TerrainType("水", "#3295D2", 1, 0, 0, 99, "水域"));
            tt.put("plains", new TerrainType("平原", "#6CC261", 3, 1, 1, 1, "平原"));
            tt.put("forest", new TerrainType("森林", "#228B22", 2, 1, 2, 2, "森林"));
            tt.put("mountain", new TerrainType("山地", "#808080", 0, 2, 5, 3, "山地"));
            tt.put("desert", new TerrainType("沙漠", "#DDC88D", 1, 2, 1, 2, "沙漠"));
            tt.put("swamp", new TerrainType("沼泽", "#556B2F", 2, 0, 1, 2, "沼泽"));
            tt.put("tundra", new TerrainType("冻土", "#A8C4D8", 1, 1, 1, 2, "冻土"));
            tt.put("hills", new TerrainType("丘陵", "#BDB76B", 2, 1, 3, 2, "丘陵"));
```
**谁在用**：`MapData.java:56`（`terrainTypes` 为 null/空时的兜底）、`MapData.java:100`（`MapData.empty()`）。**它被 `MapData` 的 compact 构造器当默认值，理论上任何"缺 terrainTypes"的反序列化都会拿到它。**

### 词表 B — `MapGenerator.defaultTerrainTypes()`，**9 项**
`gsim-map/src/main/java/com/gsim/map/service/MapGenerator.java:178-190`（原文，注意方法签名是 `static LinkedHashMap<String, MapData.TerrainType>`，包级可见）
```java
        var tt = new LinkedHashMap<String, MapData.TerrainType>();
        tt.put("water", new MapData.TerrainType("水域", "#3295D2", 1, 0, 0, 99, "海洋/湖泊"));
        tt.put("lowland", new MapData.TerrainType("低地", "#5B8C3E", 3, 1, 1, 1, "沿海低地，向内陆过渡"));
        tt.put("hills", new MapData.TerrainType("丘陵", "#A0522D", 2, 1, 3, 2, "低地与山区的过渡带"));
        tt.put("plains", new MapData.TerrainType("山区", "#B8A88A", 2, 2, 1, 1, "内陆高原/山区，高山峰簇散布其间"));
        tt.put("mountain", new MapData.TerrainType("高山", "#6B6B6B", 0, 2, 5, 3, "高山峰簇，嵌入山区内部"));
        tt.put("forest", new MapData.TerrainType("森林", "#228B22", 2, 1, 2, 2, "森林 (兼容旧地图)"));
        tt.put("swamp", new MapData.TerrainType("沼泽", "#556B2F", 2, 0, 1, 2, "海岸沼泽/湿地"));
        tt.put("desert", new MapData.TerrainType("沙漠", "#DDC88D", 1, 2, 1, 2, "沙漠"));
        tt.put("tundra", new MapData.TerrainType("冻土", "#A8C4D8", 1, 1, 1, 2, "冻土"));
```
**谁在用**：`ContourQueryEngine.java:115`（`materialize` 的唯一调用点）。★ 注意 `defaultTerrainTypes()` **只有这一个调用者**——即"生成的地图"确实用 B。

### 词表 C — 前端 `DEFAULT_TERRAINS`，**9 项**
`gsim-map/src/main/resources/web/js/state.js:6-16`（原文）
```javascript
const DEFAULT_TERRAINS = {
  water:    {name:"water",    color:"#3295D2", food:1, gold:0, stone:0, moveCost:99, description:"水域"},
  lowland:  {name:"lowland",  color:"#5B8C3E", food:3, gold:1, stone:1, moveCost:1,  description:"沿海低地"},
  hills:    {name:"hills",    color:"#A0522D", food:2, gold:1, stone:3, moveCost:2,  description:"丘陵过渡带"},
  plains:   {name:"plains",   color:"#B8A88A", food:2, gold:2, stone:1, moveCost:1,  description:"内陆山区高原"},
  mountain: {name:"mountain", color:"#6B6B6B", food:0, gold:2, stone:5, moveCost:3,  description:"高山峰簇"},
  forest:   {name:"forest",   color:"#228B22", food:2, gold:1, stone:3, moveCost:2,  description:"森林"},
  desert:   {name:"desert",   color:"#DDC88D", food:0, gold:1, stone:2, moveCost:2,  description:"沙漠"},
  swamp:    {name:"swamp",    color:"#556B2F", food:2, gold:0, stone:1, moveCost:3,  description:"海岸沼泽"},
  tundra:   {name:"tundra",   color:"#B0C4DE", food:1, gold:0, stone:1, moveCost:2,  description:"冻土"}
};
```
**谁在用**：`state.js:22`（`State.terrainTypes` 初值）、`events.js:158/162/246/276/278/287`（无地图数据时的兜底与重置）、`terrain.js:4`。

### 词表 D — `TerrainTextRenderer.TERRAIN_CHAR`，**9 项**（ASCII 字符）
`gsim-map/src/main/java/com/gsim/map/service/TerrainTextRenderer.java:43-53`（原文）
```java
        TERRAIN_CHAR.put("water", "~");
        TERRAIN_CHAR.put("lowland", ",");
        TERRAIN_CHAR.put("plains", ".");
        TERRAIN_CHAR.put("hills", "^");
        TERRAIN_CHAR.put("mountain", "@");
        TERRAIN_CHAR.put("forest", "F");
        TERRAIN_CHAR.put("swamp", "S");
        TERRAIN_CHAR.put("desert", "D");
        TERRAIN_CHAR.put("tundra", "T");
```
未知地形 → `"?"`（`TerrainTextRenderer.java:59-62`）。同类清单在 javadoc 表格里**再抄一遍**（`TerrainTextRenderer.java:20-31`）。**谁在用**：`GsimapRenderTextTool.java:84`。

### 词表 E — `ContourQueryEngine` 的分类产物 + 颜色 switch，**6 产出 / 9 分支**
产出（`classify`）：`mountain`(233) `hills`(242/243/245) `plains`(242/243/248/249/254) `lowland`(255/257) `swamp`(257) `water`(168)。**`forest`/`desert`/`tundra` 永远产生不出来。**
颜色（`ContourQueryEngine.java:260-272`）：
```java
        return switch (t) {
            case "mountain" -> "#6B6B6B";
            case "hills" -> "#A0522D";
            case "lowland" -> "#5B8C3E";
            case "plains" -> "#B8A88A";
            case "swamp" -> "#556B2F";
            case "desert" -> "#DDC88D";
            case "tundra" -> "#A8C4D8";
            case "water" -> "#3295D2";
            default -> "#6CC261";
        };
```
★ `default -> "#6CC261"` 是**词表 A 里 `plains` 的绿色**——两套词表的串味铁证。

### 词表 F — `CompressionService.terrainColor`，**6 命名 + default**
`gsim-map/src/main/java/com/gsim/map/service/CompressionService.java:163-172`
```java
        return switch (t) {
            case "mountain" -> "#6B6B6B";
            case "hills" -> "#A0522D";
            case "lowland" -> "#5B8C3E";
            case "plains" -> "#B8A88A";
            case "swamp" -> "#556B2F";
            case "water" -> "#3295D2";
            default -> "#5B8C3E";
        };
```
（比 E 少了 desert/tundra，default 也不同：#5B8C3E 是 lowland 绿。）

### 词表 G — `GsimapUpdateTerrainTypeTool` 校验清单，**9 项**
`gsim-map/src/main/java/com/gsim/map/tools/map/GsimapUpdateTerrainTypeTool.java:47`（错误消息）与 `:93`（JSON schema 描述）：
```
water/lowland/hills/plains/mountain/swamp/desert/tundra/forest
```
**注意：这是"允许的 key"的白名单文本，不是数据结构——没有任何代码拿它做校验**（`MapService.updateTerrainType` 只查 `map.terrainTypes().containsKey(key)`，`MapService.java:1585`）。若地图来自词表 A，则 `lowland` 会被拒。

### 词表 H — 文档 `docs/DATA-MODEL.md:71-74`，**8 项**
```
├── terrainTypes: Map<String, TerrainType> — 8 个内置类型
│     TerrainType { name, color, food, gold, stone, moveCost, description }
│     内置: water(水), plains(平原), forest(森林), mountain(山地),
│           desert(沙漠), swamp(沼泽), tundra(冻土), hills(丘陵)
```
即**照抄词表 A**（8 项、无 lowland、"8 个内置类型"这个数都写死了）。`docs/ARCHITECTURE.md:221` 只写"`terrainTypes`: 地形类型定义"，不含清单。

### 词表 I — 零散单点（非完整表，但属于词表分裂面）
| 文件:行 | 内容 |
|---|---|
| `MapData.java:145` | `TerrainBlock` terrain 为 null → `"plains"` |
| `MapData.java:195`、`:226`、`:509` | `HexCell`/`CompressedRegion` 默认 terrain = **`"unknown"`**（**不在任何一份词表里**） |
| `TerrainBlockProcessor.java:103` | 无块覆盖 → `"water"` |
| `MapWebUIHandler.java:791` | 生成 terrain block 时只有 `"hills"` / `"plains"` 两种 |
| `MapService.java:1072` | 无 contour 时硬编码 `"lowland"` |
| `ContourLayer.java:13`、`:51` | javadoc 举例 `"mountain"/"forest"/"plains"/"water"` |
| `worlds/*/nodes/n0000_map.json` | 实测落盘的 terrainTypes 为 **9 项（词表 B）** |

### 4.1 ★ 词表 A ↔ 词表 B 的逐项差异（**"具体哪些项不同"**）

共有 8 个 key（`water/plains/forest/mountain/desert/swamp/tundra/hills`）；**B 多出 `lowland`；A 没有 `lowland`**。共有 key 中：

| key | 字段 | 词表 A（`MapData.defaults`） | 词表 B（`MapGenerator.defaultTerrainTypes`） | 差异 |
|---|---|---|---|---|
| `water` | name | `水` | `水域` | 名字不同 |
| | description | `水域` | `海洋/湖泊` | 描述不同 |
| `plains` | name | `平原` | **`山区`** | ★ 语义完全不同 |
| | color | `#6CC261`（绿） | **`#B8A88A`（土黄）** | ★ 颜色不同 |
| | food / gold | 3 / 1 | **2 / 2** | ★ 产出不同 |
| | description | `平原` | `内陆高原/山区，高山峰簇散布其间` | ★ 描述不同 |
| `forest` | description | `森林` | `森林 (兼容旧地图)` | 描述不同 |
| | stone | 2 | 2 | 同 |
| `mountain` | name | `山地` | `高山` | 名字不同 |
| | color | `#808080` | **`#6B6B6B`** | 颜色不同 |
| `desert` | name/color/yields | `沙漠`/`#DDC88D`/1,2,1,2 | `沙漠`/`#DDC88D`/1,2,1,2 | **完全相同**（仅 description 文本同） |
| `swamp` | description | `沼泽` | `海岸沼泽/湿地` | 描述不同 |
| `tundra` | 全部 | `冻土`/`#A8C4D8`/1,1,1,2 | 同 | **完全相同** |
| `hills` | color | `#BDB76B` | **`#A0522D`** | 颜色不同 |

### 4.2 ★ 词表 B ↔ 词表 C（前端）的逐项差异

key 集合**完全一致**（同 9 项）。字段差异：

| key | 字段 | B（Java 生成器） | C（前端 JS） | 差异 |
|---|---|---|---|---|
| 全部 9 项 | `name` | **中文**（水域/低地/丘陵/山区/高山/森林/沼泽/沙漠/冻土） | **英文 key 原值**（water/lowland/hills/plains/mountain/forest/desert/swamp/tundra） | ★ 前端默认表根本不显示中文名 |
| `water` | description | `海洋/湖泊` | `水域` | 不同 |
| `lowland` | description | `沿海低地，向内陆过渡` | `沿海低地` | 不同 |
| `hills` | description | `低地与山区的过渡带` | `丘陵过渡带` | 不同 |
| `plains` | name | `山区` | `plains` | 不同 |
| | description | `内陆高原/山区，高山峰簇散布其间` | `内陆山区高原` | 不同 |
| `mountain` | description | `高山峰簇，嵌入山区内部` | `高山峰簇` | 不同 |
| `forest` | **stone** | **2** | **3** | ★ 数值不同 |
| | description | `森林 (兼容旧地图)` | `森林` | 不同 |
| `desert` | **food** | **1** | **0** | ★ 数值不同 |
| | **gold** | **2** | **1** | ★ 数值不同 |
| | **stone** | **1** | **2** | ★ 数值不同 |
| `swamp` | **moveCost** | **2** | **3** | ★ 数值不同 |
| | description | `海岸沼泽/湿地` | `海岸沼泽` | 不同 |
| `tundra` | **color** | **`#A8C4D8`** | **`#B0C4DE`** | ★ 颜色不同 |

### 4.3 附带实测：落盘时顺序也被打乱

`MapData.java:65` `terrainTypes = Map.copyOf(terrainTypes);` —— `Map.copyOf` 的迭代序**未指定**。实测同一份 9 项词表在不同存档里顺序都不同：`worlds/api-test` 落盘顺序 `['hills','tundra','desert','forest','lowland','water','mountain','swamp','plains']`，`worlds/mcp_smoke_test` 为 `['tundra','desert','water','lowland','forest','mountain','hills','plains','swamp']`。→ **`defaultTerrainTypes()` 的 `LinkedHashMap` 顺序保证在落盘那一步就失效了。**

---

## 5. `TerraType` / `TerrainType` 的字段

- **仓库里没有 `TerraType` 这个名字**（`git grep -n "TerraType" -- .` 返回空）。等价类叫 **`MapData.TerrainType`**，是 `MapData` 的**嵌套 record**：`gsim-map/src/main/java/com/gsim/map/map/MapData.java:254-262`。
- 字段 **7 个**（注释原文照抄，`MapData.java:243-262`）：

| # | 名 | 类型 | `@JsonProperty` | 注释原文 |
|---|---|---|---|---|
| 1 | `name` | `String` | `"name"` | `display name` |
| 2 | `color` | `String` | `"color"` | `representative color` |
| 3 | `food` | `int` | `"food"` | `food yield` |
| 4 | `gold` | `int` | `"gold"` | `gold yield` |
| 5 | `stone` | `int` | `"stone"` | `stone yield` |
| 6 | `moveCost` | `int` | `"moveCost"` | `movement cost modifier` |
| 7 | `description` | `String` | `"description"` | `optional description` |

**对总纲的核实**：总纲说它有 `color`/`height`/`pass`/`name` —— **`color` 和 `name` 对；`height` 与 `pass` 在 GSimulator 中都不存在**。实际多出来的是 `food`/`gold`/`stone`/`moveCost`/`description` 五个，**没有任何海拔或通行性字段**。（通行成本只有 `moveCost` 这个数值修饰符，单位是"倍率/代价"，不是通行布尔。）

**引用它的位置（消费方）**：`MapData.java:56/65/100/268`、`MapResolver.java:264`、`CompressionValidator.java:233`、`ContourQueryEngine.java:115`、`MapService.java:298/367/415/524/605/654/916/1096/1154/1209/1244/1337/1345/1571/1585/1587/1596/1597/1598`、`TerrainTextRenderer.java:66/73`、`GsimapGetHexTool.java:102-103`、`GsimapQueryByAddressTool.java:193`、`GsimapResolver.java:171`、`GsimapSearchHexTool.java:134`、`HexSearchSource.java:88`、`MapGenerator.java:180-188`、`GsimapUpdateTerrainTypeTool.java`。

---

## 6. 用户要的两个新模式：GSimulator **现在有没有**

### 6.1 框选随机化（传入区域 + 两种地形 + 理想占比 → 按占比随机重填充）

**没有。** 现有最接近的能力是 `LassoProcessor`（`gsim-map/src/main/java/com/gsim/map/service/LassoProcessor.java`），它**实际做的事**是纯粹的**几何求内侧**：

1. `fill(List<String> rawKeys, int maxRadius, int maxFill)`（`:45-109`）——把前端传来的**套索周长 hex keys（按绘制顺序）**解析、按 `maxRadius` 过滤越界（`:50-53`，少于 3 点即返回空集）。
2. 建"墙"：套索点 + 用 **Bresenham 六角线**把相邻点桥接起来（`:57-70`，`hexDist <= 1` 则跳过桥接 `:66`）。
3. 取套索**质心**作为种子（`:72-81`）；若种子落在墙上，用 `findInsideSeed` 往外试探两圈（`:82-86`、`:125-133`）。
4. 洪水填充内部，带上限 `maxFill`（`:88-103`）；若填出的数量 ≥ `maxFill` 判定为"漏了"→ **返回空集**（`:106`）。

**输出是一个 `Set<String>`（区域内 hex keys）**。它**不做任何地形分配、不掷随机、不读占比、不读海拔**——`LassoProcessor` 全文**没有任何随机数调用**（`git grep -n "随机\|Random\|random" -- gsim-map/src/main/java` 的命中里，`LassoProcessor` 一条都没有）。地形是靠调用方把 `terrain` 作为**单一常量**传给 `mapService.addBlockFromHexSet(worldId, terrain, hexSet, seed)`（`MapWebUIHandler.java:535/552`）。

另一条相关路径 `TerrainBlockProcessor`（`gsim-map/src/main/java/com/gsim/map/service/TerrainBlockProcessor.java`）**已被 `@Deprecated`**（`:16-18` "Replaced by `TerrainCanvas`. Retained for reference."），做的也只是"自动闭合成多边形 → 求 hex 集合 → 同地形合并 / 异地形挖洞"（`:40-86`），同样是**单一 terrain 常量**。

全仓**没有**"按两种地形各自理想占比随机重填充"的任何函数、工具或端点。

### 6.2 自动河流（按海拔从高到低生成一条河）

**没有。** 逐点核实：

- **"河"这个概念存在，但只是标签**：`MapData.edges` 里可以挂 `"river"` 通路标签（`MapData.java:27-30`），默认通路组 `defaultPathwayGroups()` 注册了 `river`（`MapData.java:469-478`：`new PathwayGroup("river","河流","#3295D2","天然水系",true, Map.of("width", new PropertyDef("int", 2, "河宽")))`）；前端也有一份同名拷贝（`events.js:162`）。
- **写入靠手**：`gsimap_edge_set`（`GsimapEdgeSetTool`）／旧的 `HexCell.riverMask` 6 位掩码（`MapData.java:179`，已迁到 `edgeTags`，`MapData.java:200-207`）。
- **读取靠 `tracePathway`**（`MapService.java:760-815` + `GsimapEdgeTraceTool`）：它把**已存在的** edges 按度数拆成链——"链在分支点（度数 ≥3）断开、在端点（度数 ≠2）结束"（`MapService.java:750-756` 注释）。**纯读，不生成。**
- **`riverMask` 的唯一"生成"处是迁移**：`MapData.java:201-207` 把旧 `riverMask` 位展开成 `edgeTags` 的 `"river"`。
- **没有任何代码读海拔来造水**：`height` 只存在于 `ContourQueryEngine` 内部（§3.2），而 `tracePathway` 在 `MapService`，**根本不接触 contour / 海拔**（`MapService.java:761-771` 只遍历 `map.edges()`）。
- 实测落盘里真有 river 边：`worlds/mcp_smoke_test/nodes/n0000_map.json` 的 `edges` = `{"0_0|1_0": {"river": {}}}` —— 这是**手工 `edge_set` 的产物**（该文件是 MCP smoke test 世界，无 contour 文件），不是生成出来的。
- `MapGenerator` / `ContourQueryEngine` 全文**不产生任何 `"river"` 字符串**（`git grep` 已核）。
- 顺带：`MapData.rivers()` 与 `MapData.roads()` 两个字段**已废弃**（`MapData.java:22-23`、`:40-42`、`:73-83` 打 `@Deprecated`），所有重建路径一律写 `List.of()`。

---

## 7. 生成算法整体流程（入口 → 一张图）

两条入口，**共用同一个内核**：

### 入口 1（MCP，主用）：`gsimap_generate`
1. `GsimapGenerateTool.execute`（`GsimapGenerateTool.java:35-70`）解析 `worldId`（优先取请求上下文）、`nodeId`（必填）、`seed`（缺省 `System.currentTimeMillis()`）、`radius`=80、`ridges`=2、`fragments`=5、`landRatio`=0.55、`coastRoughness`=0.6 —— **每步都用到"默认参数表"（§1.3），其中 `coastRoughness` 是死参数**。
2. `MapService.generate`（`MapService.java:1281-1309`）→ 直接转交 `MapGenerator.generate`。
3. `MapGenerator.generate(8 参)`（`MapGenerator.java:234-246`）→ `new MapGenerator(seed, radius, contourCacheMax)`（用 `seed`）→ `placeRidges(mainRidges, fragments)`（用 `mainRidges`/`fragments` + §1.4A 全部魔法数字）→ `gen.generate(landRatio)`。
   **`worldId` 与 `coastRoughness` 在此处被丢弃。**
4. `MapGenerator.generate(double)`（`:164-168`）→ `generateContour(landRatio)`（`:129-157`，用 `landRatio` 定 `baseSeaLevel`，用 §1.4B 的 5 个频率，产 `ContinentContour`，**seed 为 `rng.nextLong()`**）。
5. `new ContourQueryEngine(contour, contourCacheMax)`（`:166`，用 `contourCacheMax`）→ `engine.materialize(-radius, radius, -radius, radius)`（`:167`，用 `radius` 定包围盒）。
6. `materialize`（`ContourQueryEngine.java:93-119`）：对每个候选 (q,r) 做六角盘裁剪（`:98`）→ `query(q,r)`（LRU 查/算，`:75-83`）→ `compute(q,r)`。
7. `compute`（`:125-175`）：① 先查编辑器图层（`ContourLayer` 命中即返回**硬编码 height 0.5** + 图层地形）；② 轴向→像素；③ 域扭曲；④ 山脊高度 `computeRidgeHeight`；⑤ shelf 噪声；⑥ 三带噪声加权；⑦ 峡谷惩罚 `computeValleyPenalty`；⑧ 合成 height + gamma；⑨ 海岸噪声定 `seaLevel`；⑩ `height < seaLevel` → `water`，否则 `classify` 出地形；⑪ `terrainColor` 定颜色。**全部阈值来自 §1.4C/D。**
8. 回 `materialize`：**丢掉 height**，只取 `color`+`terrain` 造 `HexCell`（`:100-103`）。
9. 组装 `MapData`（`:106-118`）：`gridSize=30`（写死）、`hexOrientation=false`（写死）、`terrainTypes = MapGenerator.defaultTerrainTypes()`（**词表 B**）、`rivers/roads/compressedRegions = List.of()`、`pathwayGroups/edges = 空 Map`。
10. 回 `MapService.generate`：`saveMap(worldId, nodeId, map)` 落 `nXXXX_map.json`；统计 `landHexes`；返回 `{ok, worldId, nodeId, hexCount, landHexes, seed}`（`MapService.java:1292-1308`）。**注意：返回体里有 `seed`，但落盘里没有；也没有 `saveContour`。**

### 入口 2（HTTP/Web UI，`POST /api/map/{worldId}/generate`）
`MapWebUIHandler.handleGenerate`（`MapWebUIHandler.java:416-465`）——**多两步、且多落一份产品**：
1. 解析 query 参数（`:418-423`，默认值见 §1.3）。
2. `new MapGenerator(seed, radius, mapConfig.contourCacheMax())` + `placeRidges(mainCount, fragmentCount)` + `gen.generateContour(landRatio)`（`:426-428`）。
3. ★ **`mapService.saveContour(worldId, contour)`（`:429`）→ 落 `n0000_contour.json`（seed + 10 个参数 + ridges + editorLayers 全在）。这是"能复现"的唯一路径。**
4. 再**独立跑一遍** `MapGenerator.generate(8 参)`（`:432-440`）→ `mapService.saveMap(worldId, "n0000", map)`（`:441`）。
   ⚠️ 这一段**重新 new 了一个 `MapGenerator`、重新跑了一遍 rng**；虽然同 seed 同参数下结果一致，但**同一个请求里地形被算了两遍**。
5. `populateTerrainBlocks(worldId, contour, radius)`（`:444` → `:740-801`）：把 contour 的每条 ridge 拓成一条**带状多边形**（宽度 `radius * (weight > 0.8 ? 0.12 : 0.06)`，`grid = 30.0`），地形**只有 `hills`/`plains` 两种**（`:791`），`canvas.addBlock(...)` 逐个入 `TerrainCanvas`。
6. `mapService.evictCanvas(worldId, "n0000")`（`:445`）。
7. 返回 `{ok, worldId, nodeId, seed, hexCount, landHexes}`（`:447-464`）。

**用到的参数对照**：`seed`→step 3；`radius`→step 3/4（包围盒、全部频率的分母）+ step 5（带宽）；`ridges`/`fragments`→step 3（`placeRidges`，其中 ridges 被夹到 1–2）；`landRatio`→step 3（只影响 `baseSeaLevel`）；`roughness`→**解析了、传了、从未被读**；`contourCacheMax`→step 3。

---

## 8. 我**未能核实**的 / 卡在哪

| 项 | 状态 | 卡在哪 |
|---|---|---|
| `MapGenerator`/`ContourQueryEngine` 的实际运行行为（跑一遍看输出的地形分布） | **未实测** | 只读授权，未编译或运行任何代码；我只报"代码怎么写"，不报"跑出来是什么"。全仓 `find . -name '*Generator*Test*.java' -o -name '*Contour*Test*.java'` **返回空**，故也没有现成测试输出可引用。 |
| "总纲说 `TerraType` 有 `color`/`height`/`pass`" 的**出处原文** | **未读** | 该句在 `/home/cna/SimulatorMosire/docs/superpowers/specs/` 下，不在本次取证范围（我只核 GSimulator 侧事实：这两个字段不存在）。 |
| L8 "12 次"里"12"这个数**当初是怎么数出来的** | **未核实** | 那是 SimulatorMosire 侧文档的陈述；我只给出 GSimulator 侧的实测计数（`MapService` 13 / 主源码 17 / 全仓 36）。两者对不上，但我不去猜对方的数法。 |
| `worlds/default` 等 4 个存档**缺 `edges`** 的确切成因 | **推断，未证实** | 我观察到该 4 文件的 12 字段里少 `edges`，推测为 `edges` 加入前的存量存档；**没有**去读 git 历史证实（超出取证范围）。**这一条请当作未证实。** |
| `Map.copyOf` 导致 `terrainTypes` 顺序打乱 | **实测到现象** | 我在两份存档里读到顺序不同；"原因是 `Map.copyOf`"是**基于 `MapData.java:65` 的推导**，未做对照实验。 |
| 前端 `index.html` 滑杆默认值与后端不一致**是否有意** | **未核实** | 只报数值对不上（radius 120 vs 80；roughness 0.5 vs 0.6），不猜意图。 |
| `GsimapUpdateTerrainTypeTool` 白名单文本**是否被别处真正校验** | **已核主路径** | 我读了 `MapService.updateTerrainType`（`:1585`）确认它只查 `containsKey`；但未逐行扫全部 25 个工具的校验逻辑。 |
