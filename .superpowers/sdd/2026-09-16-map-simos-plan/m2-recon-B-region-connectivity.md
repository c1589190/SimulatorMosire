# M2 取证 B — Region 概念群 / 连通性 / L1·L2·L5

> 取证对象：`/home/cna/DevMosire/GSimulator`（只读）
> HEAD：`88d0f02 fix(worldinfo): friendly error for non-existent world in node tools`
> 分支：`refactor/gsim-module-split`
> 取证方式：`git grep` + 逐文件 Read + 直接读取 `worlds/` 下 JSON。**未执行任何构建/测试**。

---

## 第一部分：Region 概念群

### 1. 到底有几个"区域"概念

在 `gsim-map` 主源码中，**以"一组 hex / 一块地图面积"为单位的结构共有 5 个**（其中 4 个是活代码，1 个是死代码）：

| # | 类型 | 定义处 | 语义 |
|---|---|---|---|
| A | `MapData.Province` | `MapData.java:294-314` | **玩法区域**：命名的 hex 列表 + 颜色/tag/描述/被吞并标记。**没有边界字段** |
| B | `MapData.CompressedRegion` | `MapData.java:498-537` | **渲染缓存**：连续同地形大块的简化多边形 + 其 hexKeys。文档明说"Pure rendering optimization，可随时重跑 compress 重建" |
| C | `ContourLayer` | `ContourLayer.java:12-81` | **编辑层**：程序生成地形时手绘的闭合多边形 + 地形 + 内部种子 hex。不持久化在 MapData 里，存在 `ContinentContour.editorLayers` |
| D | `MapData.TerrainBlock` | `MapData.java:139-158` | **地形块**：地形 + 多边形 boundary + seedKey + hexKeys。旧的笔刷/套索地形编辑产物 |
| E | `com.gsim.map.service.CompressedRegion` | `CompressedRegion.java:14-81` | **死代码**：B 的可变 POJO 版本。全仓无任何 `import com.gsim.map.service.CompressedRegion`（`git grep` 实测为空） |

另外 `ContinentContour`（`ContinentContour.java`）本身是"世界级地形生成参数 + 编辑层容器"，不是区域概念。

**未找到**任何 `territory` / `Area` 命名的概念（`git grep -ni "territory"` 与 `git grep -nE "\bArea\b"` 在 `*.java`/`*.js` 中均为空）。

#### A. `MapData.Province` 字段清单（`MapData.java:284-314`，原文）

```java
    /**
     * A province — a named region with a color and list of hexes.
     *
     * @param hexes       hex keys belonging to this province
     * @param color       display color
     * @param tag         short identifier tag
     * @param description optional description
     * @param annexedBy   if non-empty, this province has been annexed by another (not rendered)
     */
    @JsonDeserialize
    public record Province(
            @JsonProperty("hexes") List<String> hexes,
            @JsonProperty("color") String color,
            @JsonProperty("tag") String tag,
            @JsonProperty("description") String description,
            @JsonProperty("annexedBy") String annexedBy) {
        public Province {
            if (hexes == null) hexes = List.of();
            if (color == null) color = "#ff0000";
            if (tag == null) tag = "";
            if (description == null) description = "";
            if (annexedBy == null) annexedBy = "";
            // Defensive copy + freeze (SpotBugs EI_EXPOSE_REP)
            hexes = List.copyOf(hexes);
        }

        /** Backward-compatible constructor without annexedBy. */
        public Province(List<String> hexes, String color, String tag, String description) {
            this(hexes, color, tag, description, "");
        }
    }
```

注意：**名字不是字段**，是 `MapData.provinces()` 这个 `Map<String, Province>` 的键（`RegionSearchSource.java:16-18` 的注释明确写了这一点："Province 无 name 字段，名称即 provinces map 的键"）。

#### B. `MapData.CompressedRegion` 字段清单（`MapData.java:484-537`，原文）

```java
    /**
     * A compressed representation of a large contiguous same-terrain region.
     * Pure rendering optimization — does not replace hexes in {@link #hexes()}.
     * Can be regenerated at any time by re-running {@code compress()}.
     *
     * @param id         unique identifier
     * @param terrain    the terrain type identifier
     * @param color      fill color
     * @param boundary   deprecated single outer ring boundary
     * @param boundaries list of boundary rings (outer + holes)
     * @param isWater    whether this is a water region
     * @param hexKeys    hex keys belonging to this region
     */
    @JsonDeserialize
    public record CompressedRegion(
            @JsonProperty("id") String id,
            @JsonProperty("terrain") String terrain,
            @JsonProperty("color") String color,
            // 已废弃：single-ring boundary，请使用 support-hole 的 boundaries (List<List<Pt>>)
            @JsonProperty("boundary") List<Pt> boundary,
            @JsonProperty("boundaries") List<List<Pt>> boundaries,
            @JsonProperty("isWater") boolean isWater,
            @JsonProperty("hexKeys") Set<String> hexKeys) {
```

`size()` 是派生方法（`MapData.java:534-536`）：`return hexKeys.size();`，不序列化。

#### C. `ContourLayer` 字段清单（`ContourLayer.java:12-59`，原文）

```java
public class ContourLayer {
    /** Terrain type to assign (e.g. "mountain", "forest", "plains", "water") */
    private String terrain;

    /** Polygon boundary points (closed — first == last implicit) */
    private List<ContinentContour.Pt> boundary;

    /** Interior seed hex key for flood fill reconstruction */
    private String seedKey;
```

#### D. `MapData.TerrainBlock` 字段清单（`MapData.java:130-158`，原文）

```java
    /**
     * A terrain block — a contiguous area painted with a single terrain type.
     *
     * @param terrain  the terrain type identifier
     * @param boundary the polygon boundary points (outer ring)
     * @param seedKey  the seed hex key used during generation
     * @param hexKeys  the set of hex keys belonging to this block
     */
    @JsonDeserialize
    public record TerrainBlock(
            @JsonProperty("terrain") String terrain,
            @JsonProperty("boundary") List<Pt> boundary,
            @JsonProperty("seedKey") String seedKey,
            @JsonProperty("hexKeys") Set<String> hexKeys) {
```

#### E. `service.CompressedRegion` 字段清单（`CompressedRegion.java:14-23`，原文）

```java
public class CompressedRegion {
    private String id;
    private String terrain;
    private String color;
    private List<MapData.Pt> boundary; // RDP-simplified polygon for rendering
    private int size; // number of hexes in this region
    private boolean isWater;

    // Hex keys in this region (serialized for frontend rendering)
    private Set<String> hexKeys;
```

### 2. 谁写、谁读

#### A. `Province`
**写**（全部经 `MapService.saveMap` → 对 root 写 full、对子节点写 `MapDiff`）：
- `MapService.createRegion` `MapService.java:1446-1471` ← `GsimapCreateRegionTool.java:63`（WRITE）
- `MapService.deleteRegion` `:1477-1488` ← `GsimapDeleteRegionTool.java:48`（WRITE）
- `MapService.updateRegion` `:1365-1389` ← `GsimapUpdateRegionTool.java:66`（WRITE）
- `MapService.addHexToRegion` `:1395-1416` ← `GsimapAddHexToRegionTool.java:63`（WRITE）
- `MapService.removeHexFromRegion` `:1422-1440` ← `GsimapRemoveHexFromRegionTool.java:63`（WRITE）
- `MapService.mergeRegions` `:1502-1565` ← `GsimapMergeRegionsTool.java:56`（WRITE）
- `MapService.renameRegion` `:887-927` ← `GsimapRenameRegionTool.java:75`（WRITE）
- `MapService.initNation` `:1646-1740`
- `MapWebUIHandler` `POST /merge-regions` `:600-618`、`POST /rename-region` `:619-639`
- 前端 `province.js:20`、`:290`（创建）、`:167-183`（删除）、`:336-...`（合并）直接改 `State.mapData.provinces` 后 PUT

**读**：
- `GsimapGetProvinceTool.java:63` 单区域详情 + `:80-84` 邻接
- `GsimapListRegionsTool.java:70-87` 全区域列表 + 邻接
- `GsimapResolver.java:76`（`gsimap:region:{name}` 地址解析）
- `GsimapGetHexTool.java:79`、`GsimapQueryByAddressTool.java:145、174`（hex/city → 归属区域）
- `GsimapGetDistanceTool.java:69-79`（区域中心距）
- `GsimapRenderTextTool.java:256`（区域文本渲染地址行）
- `TerrainTextRenderer.java:195`（区域字符映射）、`:256`（hex→区域 反查）、`:366`
- `RegionSearchSource.java:49`（搜索语料）
- `CompressionValidator.java:229`、`MapResolver.java:224-230`、`MapDiff.java:111-121`
- 前端 `render.js:228`、`events.js:68`、`tags.js:5`、`province.js`

#### B. `CompressedRegion`
**写**：只有一条 —— `CompressionService.compress(MapData, int)` `CompressionService.java:44-106`（BFS 找同地形连通块，`≥minRegionSize` 才建）→ `MapService.compress` `:1137-1180` → `saveMap`。**由 `CompressionValidator.repair` 修复** `:181-207`。
**读**：`MapService.decompress` `:1192-1223`、`decompressAt` `:1225-1248`；`CompressionValidator.validate` `:103-110`；前端 `render.js:55、152-154、200、259`、`events.js:257-261`。

#### C. `ContourLayer`
**写**：唯一入口 `MapWebUIHandler.handleContour` `:472-493`（`PUT /api/map/{worldId}/contour/editor-layers`）→ `ContourLayer(terrain, pts, seed)` → `contour.setEditorLayers(layers)` → `mapService.saveContour`（`MapService.java:838-841`，落 `n0000` 的 `contour` attachment）→ 该 contour 转成 `TerrainBlock` 由 `populateTerrainBlocks` `MapWebUIHandler.java:740-...`（调用点 `:444`）。
**读**：`ContourQueryEngine.compute` `:127-133`（**倒序遍历，最后一个 layer 优先级最高**）；`MapWebUIHandler.java:480-489`（回读）。

#### D. `TerrainBlock`（参考）
**写**：`MapService.persistBlocks` `:282-305` → `saveMap`；`TerrainCanvas.getBlocks()` `:294-...`。
**读**：`TerrainBlockProcessor.process/queryTerrain` `:40-...`、`:95-...`；`MapWebUIHandler` `GET /blocks` `:512-517`。

### 3. 同一语义被两套结构各存一份

**确认存在 4 组：**

**(a) hex 边连通性 —— 两套并存（= L2）**
- `MapData.edges`：`MapData.java:46`，`Map<String, Map<String, Map<String, Object>>>`（edgeKey → pathwayGroupId → props），**MCP 工具写**
- `HexCell.edgeTags`：`MapData.java:191`，`Map<Integer, List<String>>`（方向 0-5 → tag 列表）+ `HexCell.riverMask`（`MapData.java:190`，6-bit 位掩码），**前端 `pathway.js` 写**
- 两套之间**没有 Java 侧转换代码**，只有前端 `pathway.js:462-490`（edges→edgeTags）与 `:493-516`（edgeTags→edges）两个方向的手写投影
- Java 侧存在一次单向前端遗留迁移：`MapData.java:200-207`（`edgeTags` 为空且 `riverMask>0` 时按位填 `"river"`）

**(b) 河流/道路实体 —— 两套并存（旧的已废弃但仍在序列化面上）**
- 旧：`MapData.rivers` / `MapData.roads`（`MapData.java:41-42`），元素类型 `River`/`Road`（`MapData.java:350-388`），**每个有 `name` 和 `path`（有序 hex 键列表）**
- 新：`MapData.pathwayGroups` + `MapData.edges`，**没有 name，没有 path，只有 groupId**
- 桥接处全部写死 `List.of()`：`MapDiff.compute` `MapDiff.java:141-142`、`MapResolver.applyDiff` `MapResolver.java:242-243`、`MapService` 的 6 处重建（`:296-297、`:335-336、`:652-653、`:914-915、`:1335-1336、`:1353-1354`）
- `MapDiff` **仍然声明** `rivers_added`/`roads_added` 两个字段（`MapDiff.java:42-43`）用于 JSON 向后兼容

**(c) `CompressedRegion` 两个同名类**
`MapData.CompressedRegion`（record，活） vs `com.gsim.map.service.CompressedRegion`（POJO，死）。字段几乎一一对应（含 `size`）。

**(d) "一组 hex + 一个语义标签" 四种表达**
`Province`（hexes List，无 boundary）、`TerrainBlock`（hexKeys Set + boundary）、`CompressedRegion`（hexKeys Set + boundaries rings + terrain）、`ContourLayer`（boundary + seedKey，**无 hexKeys**）。`CompressedRegion.hexKeys` 与 `MapData.hexes` 的成员关系重复，但被文档显式定义为缓存（`MapData.java:486-487`、`CompressionService.java:20-22`）。

**转换/映射代码（存在于概念之间）：**
- `MapData.hexes`（地形）→ `CompressedRegion`：`CompressionService.compress` `:44-106` + `TerrainGeometry.hexSetToBoundaryWithHoles` `:267-...`
- `ContourLayer` → `MapData.hexes`：`ContourQueryEngine.compute` `:125-...` + `materialize` `:93-118`
- `ContinentContour`（ridges） → `TerrainBlock`：`MapWebUIHandler.populateTerrainBlocks` `:740-...`
- **`Province` ↔ `CompressedRegion` / `ContourLayer` / `TerrainBlock` 之间没有任何转换代码**。`Province` 要边界只能靠前端现算（`render.js:244` `computeBoundaryHexes`）。

---

## 第二部分：连通性

### 4. `MapData.edges` 的存储形态

字段声明与 Javadoc（`MapData.java:27-30` + `:46`，原文）：

```java
 * @param pathwayGroups     pathway group definitions (river, road, etc.)
 * @param edges             sparse edge map: edgeKey → {pathwayId → {prop → value}}.
 *                          edgeKey format: "minQ_minR|maxQ_maxR" (deterministic).
 *                          Only edges with pathway tags are stored; default
 *                          property values from PathwayGroup are omitted.
 */
```

```java
        @JsonProperty("edges") Map<String, Map<String, Map<String, Object>>> edges) {
```

- 键类型：`String`，形如 `"minQ_minR|maxQ_maxR"`（字典序小的在前）
- 值类型：`Map<String, Map<String, Object>>` = pathwayGroupId → 属性名 → 值（`width` 等；默认值不落盘）
- 构造期只做浅冻结（`MapData.java:59、68`：`Map.copyOf(...)`），内层两层 Map 未冻结
- `MapData.empty()` 与 `worlds/default/nodes/n0000_map.json` 实测：**root 全量地图 JSON 里 `edges` 键缺失**（该文件 `edges` 计数 = 0；keys 为 `cities, compressedRegions, gridSize, hexOrientation, hexes, pathwayGroups, provinces, rivers, roads, terrainBlocks, terrainTypes`）

**是主存储还是索引？** 是**唯一的主存储**——`traceChains`、`GsimapEdgeListTool`、`GsimapEdgeGetTool`、`TerrainTextRenderer.renderPathwayPresence` 都直接从它建图/查询（`MapService.java:766-774`、`GsimapEdgeListTool.java:56`、`GsimapEdgeGetTool.java:56`、`TerrainTextRenderer.java:410-415`）。它不是从 `HexCell.edgeTags` 派生的索引；反过来 `HexCell.edgeTags` 才是前端视图。

### 5. ★ L2「双份连通性存储」是哪两份

**第一份：`MapData.edges`（服务端权威）**
- 声明：`MapData.java:46`
- 写入口：`MapService.setEdgeTag` `:340-373`、`MapService.removeEdgeTag` `:381-421`
- 工具：`GsimapEdgeSetTool`（WRITE，`:85` 调 `setEdgeTag`）、`GsimapEdgeRemoveTool`（WRITE）、`GsimapEdgeGetTool`（READ）、`GsimapEdgeListTool`（READ）、`GsimapEdgeTraceTool`（READ，`:49`）
- 另有一处直接落盘旁路：`MapWebUIHandler.java:714-717`，客户端若自带 `parentNodeId` 字段则原样 `MapStore.saveDiff`（**不经过 `MapDiff.compute`**）
- 形态：`edgeKey → pathwayGroupId → props`

**第二份：`HexCell.edgeTags`（+ 遗留 `riverMask`）**
- 声明：`MapData.java:190-192`
- 写入口：**只有前端**。`gsim-map/src/main/resources/web/js/pathway.js:9-53` 是完整 CRUD（`ensureEdgeTags`/`getEdgeTags`/`addEdgeTagToCell`/`removeEdgeTagFromCell`），`:427-434` 按组删除
- Java 侧对 `edgeTags` **只读不写**：`MapService.java:547、:590` 两处 `mergeCell` 原样透传；`MapData.java:200-213` 做 `riverMask → edgeTags` 的单向迁移
- 形态：`方向(0-5) → List<String> tag`（外加 `riverMask` 6-bit 冗余位掩码）
- 两份之间的同步：`pathway.js:461-490`（load 后 `syncEdgesToHexTags`）、`:492-516`（save 前 `syncHexTagsToEdges`）。`syncHexTagsToEdges` 注释自认 "Props are empty by default — frontend doesn't edit edge properties yet"（`pathway.js:510`），即**前端一存就把所有边的 props 抹平为 `{}`**

### 6. ★ 连通性线段（chain / path / 河流 / 道路）的标识

**没有任何稳定 ID。** 现在的身份是"边的集合 + 组 ID"，链是每次查询现算的临时产物。

**(a) 边的身份 = 坐标对派生的确定性字符串**（`MapData.java:390-417`，原文）：

```java
    /**
     * Deterministic edge key for two hex coordinates.
     * Sorts q1_r1 and q2_r2 lexicographically so the key is independent of order.
     * Format: {@code "minQ_minR|maxQ_maxR"}.
     */
    public static String edgeKey(int q1, int r1, int q2, int r2) {
        String a = q1 + "_" + r1;
        String b = q2 + "_" + r2;
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * Parse an edge key back to its two hex coordinate pairs.
     *
     * @return a 4-element array: {@code [q1, r1, q2, r2]}
     */
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

**(b) 线段的身份 = `pathwayGroupId`（"river"/"road"），不是实例 ID。**
`edges` 的中间层键就是 group id（`MapData.java:46` 的类型 + `PathwayGroup.id`，`MapData.java:451-457`）。默认只有两组（`MapData.java:469-480`）：

```java
    /** Default pathway groups: river and road. */
    public static Map<String, PathwayGroup> defaultPathwayGroups() {
        var g = new LinkedHashMap<String, PathwayGroup>();
        g.put(
                "river",
                new PathwayGroup(
                        "river", "河流", "#3295D2", "天然水系", true, Map.of("width", new PropertyDef("int", 2, "河宽"))));
        g.put(
                "road",
                new PathwayGroup(
                        "road", "道路", "#8B7355", "陆路通道", true, Map.of("width", new PropertyDef("int", 1, "路宽"))));
        return g;
    }
```

也就是说：**所有河流共享 `"river"` 这一个身份**，一条河无法与另一条河区分。`PathwayGroup` 的字段里只有 `id/name/color/description/visible/properties`，没有实例集合。

**(c) 链的身份 = 返回列表的下标。**
`traceChains` 返回 `List<List<String>>`，chain 本身没有任何字段（`MapService.java:757` 的 Javadoc：`@return list of chains; each chain is an ordered list of {@code q_r} hex keys`）。对外只有 `GsimapEdgeTraceTool` 按 `i+1` 编号打印（`GsimapEdgeTraceTool.java:65-70`）。

**(d) 唯一的例外是已废弃的 `River`/`Road`**，它们**有** `name` + `path`（`MapData.java:348-363`）：

```java
    /**
     * A deprecated river definition.
     *
     * @param name  river name
     * @param path  ordered hex keys along the river
     * @param width river width in pixels
     * @param color display color
     */
    @JsonDeserialize
    @Deprecated
    public record River(
            @JsonProperty("name") String name,
            @JsonProperty("path") List<String> path,
            @JsonProperty("width") int width,
            @JsonProperty("color") String color) {
```

即：**"一条有名字的河"这个语义，在废弃 `River` 之后就没有新的承载结构了**。

**(e) 前端另有第三份边键实现**（必须与 Java 手工保持一致，注释自认）：`pathway.js:452-459`

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

Java 侧对应的去重键是 `MapService.undirectedKey`（`MapService.java:827-829`），**第三份同样逻辑的拷贝**。

### 7. `MapService.java:759-829` `traceChains` 全文解析

**输入**：`MapData map`、`String type`（pathway group id，如 `"river"`）
**输出**：`List<List<String>>`，每个 chain 是**有序的 `q_r` hex 键列表**
**算法**：不是纯"找连通分量"，而是**连通分量 + 度 2 内点收缩成简单链**

完整原文（`MapService.java:765-829`）：

```java
    static List<List<String>> traceChains(MapData map, String type) {
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        for (var entry : map.edges().entrySet()) {
            if (!entry.getValue().containsKey(type)) continue;
            int[] c = MapData.parseEdgeKey(entry.getKey());
            String a = MapData.hexKey(c[0], c[1]);
            String b = MapData.hexKey(c[2], c[3]);
            adj.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
            adj.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);
        }
        if (adj.isEmpty()) return List.of();

        Set<String> usedEdges = new HashSet<>();
        List<List<String>> chains = new ArrayList<>();

        for (String node : adj.keySet()) {
            int deg = adj.get(node).size();
            if (deg == 2) continue;
            for (String nb : adj.get(node)) {
                if (usedEdges.add(undirectedKey(node, nb))) {
                    chains.add(traceChain(adj, usedEdges, node, nb));
                }
            }
        }

        for (String node : adj.keySet()) {
            for (String nb : adj.get(node)) {
                if (usedEdges.add(undirectedKey(node, nb))) {
                    chains.add(traceChain(adj, usedEdges, node, nb));
                }
            }
        }
        return chains;
    }

    private static List<String> traceChain(
            Map<String, Set<String>> adj, Set<String> usedEdges, String start, String through) {
        List<String> path = new ArrayList<>();
        path.add(start);
        path.add(through);
        String prev = start;
        String cur = through;
        while (!cur.equals(start)) {
            int deg = adj.getOrDefault(cur, Set.of()).size();
            if (deg != 2) break;
            String next = null;
            for (String cand : adj.get(cur)) {
                if (cand.equals(prev)) continue;
                if (!usedEdges.contains(undirectedKey(cur, cand))) {
                    next = cand;
                    break;
                }
            }
            if (next == null) break;
            usedEdges.add(undirectedKey(cur, next));
            path.add(next);
            prev = cur;
            cur = next;
        }
        return path;
    }

    private static String undirectedKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
```

要点：
1. **建图**：遍历 `map.edges()`，只保留值里含 `type` 的边（**不含 `defaultPathwayGroups` 里 visible=false 的过滤，也不校验 hex 是否真的在 `map.hexes()` 里**）。无向邻接表 `adj: hex → Set<邻居>`。
2. **第一趟**：跳过所有度 2 的节点，只从**端点（度 1）或分支点（度 ≥3）**出发，每条还没用过的边都启一条链。
3. **第二趟**：扫所有尚未使用的边——这趟捞的是**整条链全是度 2 的闭环**（第一趟的 `if (deg == 2) continue` 会把它们全跳过）。
4. `traceChain`：从 `start` 经 `through` 往一个方向走，只要 `cur` 的度 == 2 且还有未用的、不回头的边就继续；`cur` 一旦回到 `start`（闭环）或度 != 2（撞上端点/分支点）就停。
5. **无环检测上限**：`while (!cur.equals(start))` + `usedEdges` 保证边只用一次，`visited` 集合只在 hex 层起隐式保护。
6. **chain 的表示**：`List<String>`，元素是 `MapData.hexKey(q,r)`（即 `"q_r"`），**有序**；相邻两元素之间隐含一条边，**边本身及其 props 全部丢失**。
7. 调用链：`GsimapEdgeTraceTool.java:49` → `MapService.tracePathway` `:759-763`（对 `resolveActive(worldId)` 的结果调 `traceChains`）。

### 8. ★ L1「子节点写 `edges` 静默丢失」的成因

**成因是两处环环相扣的缺陷：MapDiff 根本没有 `edges` 这个字段（写不进），MapResolver 又永远用 `base.edges()` 重建（读不掉）。**

**第一处：`MapService.saveMap` —— 唯一的写入口，对非 root 一律走 diff**（`MapService.java:309-330`，原文）

```java
    /**
     * Save a map — automatically chooses full (root) or diff (child).
     * This is the ONLY save entry point. Business methods MUST NOT call
     * MapStore.saveFull/saveDiff directly.
     */
    public void saveMap(String worldId, String nodeId, MapData updated) {
        if (isRootNode(worldId, nodeId)) {
            MapStore.saveFull(worldsDir, worldId, nodeId, updated);
        } else {
            String parentId = readParentId(worldId, nodeId);
            MapData parent = resolve(worldId, parentId);
            if (parent == null) {
                // 父节点尚无地图：自动创建空地图基线并落盘，后续 diff 相对空基线记录
                parent = MapData.empty();
                MapStore.saveFull(worldsDir, worldId, parentId, parent);
                evict(worldId, parentId);
            }
            MapDiff diff = MapDiff.compute(parentId, parent, updated);
            MapStore.saveDiff(worldsDir, worldId, nodeId, diff);
        }
        evict(worldId, nodeId);
    }
```

**第二处：`MapDiff` 的 10 个字段里没有 `edges`**（`MapDiff.java:34-44`，原文）

```java
@JsonDeserialize
public record MapDiff(
        @JsonProperty("parentNodeId") String parentNodeId,
        @JsonProperty("changed") Map<String, MapData.HexCell> changed,
        @JsonProperty("removed") List<String> removed,
        @JsonProperty("provinces_changed") Map<String, MapData.Province> provincesChanged,
        @JsonProperty("provinces_removed") List<String> provincesRemoved,
        @JsonProperty("cities_added") Map<String, MapData.City> citiesAdded,
        @JsonProperty("cities_removed") List<String> citiesRemoved,
        @JsonProperty("rivers_added") List<MapData.River> riversAdded,
        @JsonProperty("roads_added") List<MapData.Road> roadsAdded,
        @JsonProperty("compressedRegions") List<MapData.CompressedRegion> compressedRegions) {
```

`MapData` 有 **12** 个分量（`MapData.java:33-46`），`MapDiff` 只有 **10** 个。**缺的正是 `terrainBlocks` / `terrainTypes` / `pathwayGroups` / `edges` 这四个**（与 CLAUDE.md 记载的"漂移出去的四个字段"完全一致）。

**第三处：`MapDiff.compute` 的返回语句里压根没有 edges 参数**（`MapDiff.java:133-143`，原文）

```java
        return new MapDiff(
                parentNodeId,
                changed,
                removed,
                provChanged,
                provRemoved,
                citiesAdded,
                citiesRemoved,
                List.of(), // rivers: 已废弃，将由 PathwayGroup 连通性系统替代
                List.of(), // roads:  已废弃，将由 PathwayGroup 连通性系统替代
                child.compressedRegions());
```

它只 diff 了 hex / province / city 三类，`compressedRegions` 是**整份替换**，其余四个字段连提都没提。且 `isEmpty()`（`:70-80`）也不含 edges —— 于是"只改了一条边"的编辑会算出 `isEmpty() == true` 的空 diff。

**第四处（读取侧）：`MapResolver.applyDiff` 永远传父节点的 `base.edges()`**（`MapResolver.java:255-267`，原文）

```java
        return new MapData(
                base.gridSize(),
                base.hexOrientation(),
                hexes,
                base.terrainBlocks(),
                provinces,
                cities,
                rivers,
                roads,
                base.terrainTypes(),
                crs,
                base.pathwayGroups(),
                base.edges());
```

注意 `hexes` / `provinces` / `cities` / `crs` 都是**刚刚按 diff 算出来的局部变量**，而 `terrainBlocks` / `terrainTypes` / `pathwayGroups` / `edges` 是**直接读 base 的**。

**第五处（读取侧的短路）**：`MapResolver.resolve` `:76-82` 只在 `!diff.isEmpty()` 时才调 `applyDiff`：

```java
        for (int i = 1; i < chain.size(); i++) {
            MapDiff diff = MapStore.loadDiff(worldsDir, worldId, chain.get(i));
            if (diff != null && !diff.isEmpty()) {
                chainDiffs.put(chain.get(i), diff);
                resolved = applyDiff(resolved, diff, chain, baseCrs, chainDiffs);
            }
        }
```

**完整丢失链路（以 `GsimapEdgeSetTool` 为目标）**：
`GsimapEdgeSetTool.java:85` → `MapService.setEdgeTag` `:340-373`（在内存里真的把 `edges` 改了，`:353-370`，返回的 `updated` 也含新边）→ `saveMap` `:371` → 因为目标节点不是 root，走 `:326-327` `MapDiff.compute` → **`compute` 里没有一处读 `child.edges()`**，落盘的 JSON 只有那 10 个键（实测 `worlds/default/nodes/n0007_map_diff.json` 的顶层键正是这 10 个，无 `edges`）→ 下次 `resolve` 时 `MapResolver.applyDiff` `:267` 用 `base.edges()` 重建 → **写的东西在位图里再也不出现，且没有任何日志或异常**。

**同源的三个字段**（同样静默丢失，只是不属本次取证重点）：`terrainBlocks`（写入口 `persistBlocks` `:282-305`）、`terrainTypes`（`updateTerrainType` `:1571-...`）、`pathwayGroups`（`updatePathwayGroups` `:639-659`）。

**"默认路径"的实测校正**：`readActiveNodeId`（`MapService.java:1754-1756`）委托 `WorldManager.activeNodeIdOr`，后者取**turn 最大、同 turn 取 nodeId 最大**（`WorldManager.java:30-31、:90-105`）。逐个读 `worlds/default/nodes/*.json` 得到 turn：`n0000=0, n0001=1, n0002=1, n0003=2, n0004=2, n0005=3, n0006=1, n0007=2` → **当前工作树里 `worlds/default` 的活跃节点是 `n0005`，不是总纲写的 `n0007`**（`worlds/default/active.json` 里写的是 `n0000`，`world.json` 的 `currentNodeId` 也是 `n0000`，但两者都不是 `activeNodeId` 的判据）。**结论方向不变**：活跃节点不是 root，因此"不显式指定 nodeId 的边写入"默认走 `MapDiff` 路径 → 丢。
（说明：此条由读 JSON + 读比较器推导，**未运行代码验证**。）

**旁路一（已核实）：** `MapWebUIHandler.handleSave` `:714-717` 若请求体自带 `parentNodeId`，会**直接** `MapStore.saveDiff`（不经 `compute`），把客户端 JSON 原样反序列化成 `MapDiff`。但这条旁路**同样救不了 edges**：`MapWebUIHandler.java:52` 的 `MAPPER = JsonUtils.MAPPER`，而 `gsim-docslib/.../JsonUtils.java:18` 显式 `.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` —— 客户端 JSON 里即使带了 `edges`，也只会被**静默丢弃**，不报错。所以无论走哪条写路径，非 root 节点上的 `edges` 都进不了 `MapDiff`。

---

## 第三部分：性能

### 9. ★ L5「Province 归属查询 O(区域数 × hex 数)」

**成因**：`Province.hexes` 是 `List<String>`（`MapData.java:295`），`List.contains` 是线性扫描；外层又遍历全部 province。总复杂度 = 各 province 的 hex 数之和（最坏情况），不存在反向索引 `hexKey → provinceName`。

**共 6 处逐字重复的同一段代码：**

`GsimapGetHexTool.java:77-84`（原文）：
```java
        // Find owning province
        String province = null;
        for (var entry : map.provinces().entrySet()) {
            if (entry.getValue().hexes().contains(key)) {
                province = entry.getKey();
                break;
            }
        }
```

`GsimapQueryByAddressTool.java:143-150`（原文）：
```java
        // Find which province owns this hex
        String owner = null;
        for (var entry : map.provinces().entrySet()) {
            if (entry.getValue().hexes().contains(cellKey)) {
                owner = entry.getKey();
                break;
            }
        }
```

`GsimapQueryByAddressTool.java:171-179`（原文，city 版本）：
```java
        // Find which province owns the city's hex
        String hexKey = MapData.hexKey(city.q(), city.r());
        String owner = null;
        for (var entry : map.provinces().entrySet()) {
            if (entry.getValue().hexes().contains(hexKey)) {
                owner = entry.getKey();
                break;
            }
        }
```

`GsimapResolver.java:120-126`（原文）：
```java
        String owner = null;
        for (var entry : map.provinces().entrySet()) {
            if (entry.getValue().hexes().contains(cellKey)) {
                owner = entry.getKey();
                break;
            }
        }
```

`GsimapResolver.java:152-158`（city 版本，同上）。

`GsimapRenderTextTool.java:253-264`（**这段更贵：它不 `break`**，为了取名字字典序最小的那个必须全扫）：
```java
    private static String regionAddress(MapData map, int cq, int cr, Map<String, String> regionCharMap) {
        String hexKey = MapData.hexKey(cq, cr);
        String best = null;
        for (var entry : map.provinces().entrySet()) {
            String name = entry.getKey();
            if (!regionCharMap.containsKey(name)) continue;
            if (entry.getValue().hexes().contains(hexKey)) {
                if (best == null || name.compareTo(best) < 0) best = name;
            }
        }
        return best == null ? null : "address: gsimap:region:" + best;
    }
```

**调用频次**：`GsimapResolver.resolveHex` 在**每一次 `gsimap:hex:{q}_{r}` 地址解析**时都跑一遍（`GsimapResolver.java:91-144`，`:122` 是那个循环）；`GsimapGetHexTool` 每次查格；`GsimapQueryByAddressTool` 每次按地址查；`GsimapRenderTextTool` 每次渲染。

**量级参照（实测 `worlds/default`）**：`n0000_map.json` 共 4921 hex、2 个 province；`n0007_map_diff.json` 的 `provinces_changed` 里 `区域4` 有 137 hex、`区域3` 有 106 hex、`测试国A/B` —— 即单次解析要扫数百个字符串才可能命中。**未做基准测试**。

**另一处同源 O(区域数 × hex 数)**（不是归属查询，但同族）：`MapService.computeAdjacency` `:967-984`——对每个其他区域，遍历自己的每个 hex、每 hex 查 6 个邻居：

```java
    public static List<Map<String, Object>> computeAdjacency(
            Set<String> ownHexes, Map<String, Set<String>> allRegionHexes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : allRegionHexes.entrySet()) {
            String otherName = entry.getKey();
            Set<String> otherHexes = entry.getValue();
            if (otherHexes == ownHexes) continue;
            int sharedEdges = 0;
            for (String hk : ownHexes) {
                int[] qr = MapData.parseHexKey(hk);
                for (int[] d : HEX_DIRS) {
                    if (otherHexes.contains(MapData.hexKey(qr[0] + d[0], qr[1] + d[1]))) sharedEdges++;
                }
            }
            if (sharedEdges > 0) result.add(Map.of("name", otherName, "sharedEdges", sharedEdges));
        }
        return result;
    }
```

调用方 `GsimapListRegionsTool.java:70-87` 会**对每个区域各调一次** `computeAdjacency` → 整体 O(区域数² × 区域 hex 数 × 6)。

---

## 附：未能核实 / 存疑项

1. **`worlds/default` 活跃节点 = `n0005`** 系由"读 `world.json`/`nodes/*.json` 的 turn 值"+"读 `WorldManager.java:30-31、:90-105` 的比较器"推导，**未运行代码**。总纲 §L1 写的 `n0007` 与本次实测不符。
2. ~~`MapWebUIHandler.handleSave` 直通 `MapStore.saveDiff` 时 Jackson 对未知 `edges` 字段的处理策略~~ —— **已补核**：`JsonUtils.java:18` 关闭了 `FAIL_ON_UNKNOWN_PROPERTIES`，未知字段静默丢弃。见第二部 Q8 的"旁路一"。
3. `service.CompressedRegion` 判定为死代码，依据是 `git grep -n "import com.gsim.map.service.CompressedRegion"` 返回空 + `git grep -n "CompressedRegion"` 全仓无该类的调用点；**未跑编译期 `unused` 检查**。
4. L4 的"三个 region 概念"若按总纲口径是 `Province` / `CompressedRegion` / `ContourLayer`；本次取证在活代码里**另外找到 `TerrainBlock` 与死类 `service.CompressedRegion`**，即实际是 4 个活概念 + 1 个死类。总数取决于"概念"的判据，本报告只报实测清单，不替你裁定。
5. `traceChains` 的第二趟循环（`MapService.java:790-796`）在什么输入下会真正被触发（必须整条链全是度 2 的闭环），**未构造用例实测**，只按代码路径陈述。
