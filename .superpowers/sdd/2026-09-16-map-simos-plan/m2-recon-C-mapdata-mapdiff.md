# M2 取证 C：`MapData` 完整字段清单 与 `MapDiff` 漂移对照

取证对象：`/home/cna/DevMosire/GSimulator`（**只读**，未修改任何文件）
取证手段：整文件 Read（非片段） + `git grep`（规避本机 ugrep 的静默空返回）
取证时间：2026-09-16

---

## 0. 三份必读文件的读取情况

| 文件 | 行数（Read 实测） | 是否整文件读完 |
|---|---|---|
| `gsim-map/src/main/java/com/gsim/map/map/MapData.java` | 538 | 是（含全部嵌套 record） |
| `gsim-map/src/main/java/com/gsim/map/map/MapDiff.java` | 145 | 是 |
| `gsim-map/src/main/java/com/gsim/map/map/MapResolver.java` | 269 | 是 |

---

## 1. ★ `MapData` 的完整字段清单（12 个）

`MapData` 是 **record**（`MapData.java:33` `public record MapData(`），
12 个 record component 即全部字段。声明原文（`MapData.java:33-46`）：

```java
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

逐字段（序号 / 名字 / 类型 / 注释说明语义）：

| # | 字段名 | 类型 | 行号 | 是否有注释说明语义（原文） |
|---|---|---|---|---|
| 1 | `gridSize` | `int` | 34 | 有（类级 Javadoc `@param`）：`width/height of the hex grid (1-1000)` |
| 2 | `hexOrientation` | `boolean` | 35 | 有：`true for pointy-top, false for flat-top` |
| 3 | `hexes` | `Map<String, HexCell>` | 36 | 有：`all hex cells keyed by "q_r"` |
| 4 | `terrainBlocks` | `List<TerrainBlock>` | 37 | 有：`ordered polygon blocks (last = topmost)` |
| 5 | `provinces` | `Map<String, Province>` | 38 | 有：`province definitions keyed by id` |
| 6 | `cities` | `Map<String, City>` | 39 | 有：`city definitions keyed by id` |
| 7 | `rivers` | `List<River>` | 41 | 有：`deprecated river definitions`（另有行内中文注释，见下） |
| 8 | `roads` | `List<Road>` | 42 | 有：`deprecated road definitions`（同上） |
| 9 | `terrainTypes` | `Map<String, TerrainType>` | 43 | 有：`terrain type definitions keyed by name` |
| 10 | `compressedRegions` | `List<CompressedRegion>` | 44 | 有：`cached contour hulls for rendering` |
| 11 | `pathwayGroups` | `Map<String, PathwayGroup>` | 45 | 有：`pathway group definitions (river, road, etc.)` |
| 12 | `edges` | `Map<String, Map<String, Map<String, Object>>>` | 46 | 有（类级 Javadoc 四行长文）：见下 |

`edges` 的语义原文（`MapData.java:27-30`）：

```
 * @param edges             sparse edge map: edgeKey → {pathwayId → {prop → value}}.
 *                          edgeKey format: "minQ_minR|maxQ_maxR" (deterministic).
 *                          Only edges with pathway tags are stored; default
 *                          property values from PathwayGroup are omitted.
```

`rivers`/`roads` 的行内注释原文（`MapData.java:40`）：

```java
        // ══ 已废弃：rivers/roads 将在分门别类的地块连通性系统（PathwayGroup）中重建 ══
```

### 类形态 / 构造方式

- **形态**：`record`，`public record MapData(...)`（`MapData.java:33`）。**没有 builder、没有 `with*` 方法、没有 `copy`/`clone`、没有 `diff(other)`**。
  验证：`git grep -n -E "equals\(|hashCode\(|MapData diff|public MapData with|Builder|clone\(|copy\(" -- gsim-map/src/main/java/com/gsim/map/map/MapData.java` → **exit=1，零匹配**。
  （注意：文件里出现的 `List.copyOf` / `Map.copyOf` 是紧凑构造器内的防御性拷贝，不是 `copy`/`clone` 方法。）
- **构造器**：只有**规范构造器**（canonical，12 参，顺序与上表一致），外加一个紧凑构造器做校验/默认值/冻结（`MapData.java:47-69`）。
  紧凑构造器行为逐条：
  - `gridSize < 1 || > 1000` → 抛 `IllegalArgumentException`（48-49）
  - **默认值填充**（50-59）：`hexes`/`provinces`/`cities`/`edges` → `new LinkedHashMap<>()`；`terrainBlocks` → `new ArrayList<>()`；`rivers`/`roads`/`compressedRegions` → `List.of()`；`terrainTypes` 若 null 或**空** → `TerrainType.defaults()`；`pathwayGroups` 若 null 或**空** → `defaultPathwayGroups()`
  - **冻结**（61-68）：`hexes`/`provinces`/`cities`/`terrainTypes`/`pathwayGroups`/`edges` → `Map.copyOf`；`terrainBlocks`/`compressedRegions` → `List.copyOf`。**注意：`rivers`/`roads` 没有进冻结列表**（61-68 只列了 8 个字段，见下文 Q7）
  - **`Map.copyOf` 会丢插入顺序**（返回的是不可变 Map，非 `LinkedHashMap`）——`edges` 是嵌套 `Map<String, Map<String, Map<String,Object>>>`，**只有最外层被 `Map.copyOf` 冻结，内两层未冻结**
- **静态工厂**：`MapData.empty()`（`MapData.java:90-104`），硬编码 `30, false, Map.of(), List.of(), ...`——**唯一一处用字面量 `gridSize` 的 main 代码构造点**
- **辅助静态方法**（非字段）：`hexKey(int,int)`（113）、`parseHexKey(String)`（123）、`edgeKey(int,int,int,int)`（397）、`parseEdgeKey(String)`（408）、`defaultPathwayGroups()`（469）
- **嵌套 record**（同文件内，均为 `MapData` 的组成部分，**不是** `MapData` 自身字段）：
  `TerrainBlock`(139, 4 组件)、`Pt`(167, 2)、`HexCell`(184, 8)、`TerrainType`(255, 7)、`Province`(294, 5)、`City`(327, 4)、`River`(350, 4, `@Deprecated`)、`Road`(375, 4, `@Deprecated`)、`PropertyDef`(429, 3)、`PathwayGroup`(451, 6)、`CompressedRegion`(498, 7)
  **注意 `City` 是 4 组件 (`q`,`r`,`name`,`description`)——`MapDiff.compute` 只按 key 判增删，不判内容变化**

---

## 2. ★ `MapDiff` 的完整字段清单（10 个）

`MapDiff` 是 **record**（`MapDiff.java:34` `public record MapDiff(`）。声明原文（`MapDiff.java:33-44`）：

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

（类上方另有 `@SuppressWarnings("deprecation")` 与其两行中文说明，见 `MapDiff.java:30-32`）

逐字段（名字 / 类型 / 行号 / 类级 Javadoc 语义原文）：

| # | 字段名 | 类型 | 行号 | 语义（类级 Javadoc `@param` 原文） |
|---|---|---|---|---|
| 1 | `parentNodeId` | `String` | 35 | `parent node identifier`（**非** `MapData` 字段） |
| 2 | `changed` | `Map<String, MapData.HexCell>` | 36 | `hex cells that changed (keyed by "q_r")` |
| 3 | `removed` | `List<String>` | 37 | `hex keys that were removed` |
| 4 | `provincesChanged` | `Map<String, MapData.Province>` | 38 | `province definitions that changed` |
| 5 | `provincesRemoved` | `List<String>` | 39 | `province ids that were removed` |
| 6 | `citiesAdded` | `Map<String, MapData.City>` | 40 | `city definitions that were added` |
| 7 | `citiesRemoved` | `List<String>` | 41 | `city ids that were removed` |
| 8 | `riversAdded` | `List<MapData.River>` | 42 | `river definitions that were added` |
| 9 | `roadsAdded` | `List<MapData.Road>` | 43 | `road definitions that were added` |
| 10 | `compressedRegions` | `List<MapData.CompressedRegion>` | 44 | `compressed region data` |

紧凑构造器（`MapDiff.java:45-66`）：`parentNodeId` 空/空白 → 抛 `IllegalArgumentException("parentNodeId required")`；其余 9 个 null → 分别填 `Map.of()` / `List.of()`；10 个全部 `Map.copyOf` / `List.copyOf` 冻结。

---

## 3. ★ 漂移对照：`MapData` 有、`MapDiff` 没有的字段

**核实手段（贴证据）**：

```
$ git -C /home/cna/DevMosire/GSimulator grep -n -E "terrainBlocks|terrainTypes|pathwayGroups|edges" \
      -- gsim-map/src/main/java/com/gsim/map/map/MapDiff.java
（无输出）
exit=1
```

即：**这四个串在 `MapDiff.java` 全文里一次都没出现**（含 Javadoc、注释、字段名）。同时我通读了 `MapDiff.java` 全部 145 行，10 个 component 里确实没有它们。

**对照表**：

| `MapData` 字段（12） | `MapDiff` 中对应的字段 | 状态 |
|---|---|---|
| `gridSize` | 无 | **★ 漂移（第五个）** |
| `hexOrientation` | 无 | **★ 漂移（第六个）** |
| `hexes` | `changed` + `removed` | 有（拆两条） |
| `terrainBlocks` | 无 | **★ 已知漂移，已核实** |
| `provinces` | `provincesChanged` + `provincesRemoved` | 有（拆两条） |
| `cities` | `citiesAdded` + `citiesRemoved` | 有（**只有增/删，无改**，见下） |
| `rivers` | `riversAdded` | 有（**恒为空**，见下） |
| `roads` | `roadsAdded` | 有（**恒为空**，见下） |
| `terrainTypes` | 无 | **★ 已知漂移，已核实** |
| `compressedRegions` | `compressedRegions` | 有，但**语义不是 diff**（见下） |
| `pathwayGroups` | 无 | **★ 已知漂移，已核实** |
| `edges` | 无 | **★ 已知漂移，已核实** |

**结论：漂移的不是四个，是六个。** 除 `terrainBlocks` / `terrainTypes` / `pathwayGroups` / `edges` 外，
**`gridSize` 与 `hexOrientation` 同样不在 `MapDiff` 里**。

区分（只说事实，不做设计裁决）：

- `gridSize` / `hexOrientation`：`MapResolver.applyDiff` 在重建 `MapData` 时直接透传 `base.gridSize()` / `base.hexOrientation()`（`MapResolver.java:256-257`），
  所以**子节点即便改了这两项，resolve 后也拿不回来**。
  但我在 main 代码里**没找到任何一处构造出与源不同的 gridSize/hexOrientation**：
  main 全部 `new MapData(` 调用点的首参实测为——`MapData.java:91`（`empty()`，字面量 `30`）、`MapResolver.java:256`（`base.gridSize()`）、
  `CompressionValidator.java:225`（`map.gridSize()`）、`MapService.java:290/359/407/516/597/646/908/1088/1201/1236`（全部 `map.gridSize()`）、
  `MapService.java:1329/1347`（`source.gridSize()`）。即**当前没有任何写路径会改动这两个字段**。
- `cities`：`MapDiff.compute` 只判断 key 的存在性（`MapDiff.java:126-131`，`containsKey`），**不比较 `City` 值是否变化**；
  `applyDiff` 也只有 `citiesRemoved` 删 + `citiesAdded` 放（`MapResolver.java:233-239`）。→ 同 key 改内容（如改名）**记录不下来**。
- `rivers` / `roads`：`MapDiff.compute` 恒定传 `List.of()`（`MapDiff.java:141-142`）；`applyDiff` 恒定写 `List.of()`（`MapResolver.java:242-243`）。
  → 即便字段在 `MapDiff` 里，**也从不往返**。
- `compressedRegions`：`MapDiff.compute` 直接把 `child.compressedRegions()` **整份拷贝**塞进 diff（`MapDiff.java:143`），不是增量；
  `applyDiff` 里另有专门逻辑：子节点 CR 非空则**整体替换**父 CR（`MapResolver.java:245-253`）。

---

## 4. `MapDiff` 怎么被应用 —— `MapResolver.applyDiff` 原文

入口：`resolve(...)`（`MapResolver.java:62-85`）→ 沿 parent chain 从 root 起，逐个 `MapStore.loadDiff` 后调 `applyDiff`（77-81）。
`history(...)` 走同一条路（120-128）。`applyDiff` 全文本体在 `MapResolver.java:208-268`。

逐字段写入方式：

| `MapDiff` 字段 | 写入方式 | 行号 |
|---|---|---|
| `removed` | 从 `base.hexes()` 的副本里 `remove` | 216-218 |
| `changed` | 往同一副本 `put` | 219-221 |
| `provincesRemoved` | 从 `base.provinces()` 副本里 `remove` | 225-227 |
| `provincesChanged` | 往同一副本 `put` | 228-230 |
| `citiesRemoved` | 从 `base.cities()` 副本里 `remove` | 234-236 |
| `citiesAdded` | 往同一副本 `put` | 237-239 |
| `riversAdded` | **忽略，硬写 `List.of()`** | 242 |
| `roadsAdded` | **忽略，硬写 `List.of()`** | 243 |
| `compressedRegions` | **不按本 diff 写**：从 `baseCrs` 起，遍历 chain 上每个子节点的 diff，非空则整体替换 | 245-253 |
| `parentNodeId` | 不写入 `MapData`（`MapData` 无此字段） | — |

**★ 关键：`applyDiff` 无条件透传 6 个字段**（`MapResolver.java:255-267`）：

```java
        return new MapData(
                base.gridSize(),          // 256
                base.hexOrientation(),    // 257
                hexes,                    // 258  ← diff 影响
                base.terrainBlocks(),     // 259  ← 永远来自 base
                provinces,                // 260  ← diff 影响
                cities,                   // 261  ← diff 影响
                rivers,                   // 262  ← 硬写 List.of()（242）
                roads,                    // 263  ← 硬写 List.of()（243）
                base.terrainTypes(),      // 264  ← 永远来自 base
                crs,                      // 265  ← CR 特例逻辑
                base.pathwayGroups(),     // 266  ← 永远来自 base
                base.edges());            // 267  ← 永远来自 base
```

**回答"有没有字段是子节点（非 root）时不写的"**：有，**6 个**——`gridSize`(256)、`hexOrientation`(257)、
`terrainBlocks`(259)、`terrainTypes`(264)、`pathwayGroups`(266)、`edges`(267)，外加 `rivers`/`roads` 被硬写 `List.of()`(242-243, 262-263)。
子节点上对这些字段的任何修改，`resolve` 后一律丢失。

---

## 5. `MapDiff` 怎么被产生 —— `MapDiff.compute`

**没有** `MapData.diff(other)`（见 Q1 的 grep：`MapData.java` 里零匹配）。
产生入口是静态方法 `public static MapDiff compute(String parentNodeId, MapData parent, MapData child)`（`MapDiff.java:92-144`，**共 53 行**）。

调用链：`MapService.saveMap`（`MapService.java:314-330`）是**唯一保存入口**（其 Javadoc 自述 `This is the ONLY save entry point`）；
非 root 节点走 `MapDiff.compute(parentId, parent, updated)`（`MapService.java:326`）→ `MapStore.saveDiff`（327）。

`compute` **确实是逐字段手写的三段 + 一次构造**：

- hex 段（93-108）：并 parent/child 的 `hexes().keySet()`，key 不在 child → `removed`；key 不在 parent 或 `!pc.equals(cc)` → `changed`（**用了 record equals**）
- province 段（111-121）：同构，`!pp.equals(cp)` → `provChanged`
- city 段（124-131）：**只 `containsKey` 双向比对，不比较 value**
- 构造（133-143）：**比较了 3 个领域字段（hexes / provinces / cities）**，其中 `rivers`/`roads` 写死 `List.of()`（141-142），`compressedRegions` 整份拷贝（143）。

类级 Javadoc 自述其口径（`MapDiff.java:83-85`）：

```
     * Compute diff between two full MapData instances.
     * Only tracks hex/province/city changes; rivers and roads are not diffed
     * (they use pixel coordinates which change with zoom/pan — store full list instead).
```

**注意**：这段 Javadoc 只说"rivers/roads 不 diff"，**没有提到** `terrainBlocks` / `terrainTypes` / `pathwayGroups` / `edges` / `gridSize` / `hexOrientation` —— 即后六个字段的缺席连注释都没交代。

---

## 6. 相等性（`equals` / `hashCode`）

| 类 | 是否 record | 是否手写 `equals`/`hashCode` | 实际比较范围 |
|---|---|---|---|
| `MapData` | 是（`:33`） | **否**（`git grep -E "equals\(|hashCode\(" MapData.java` → exit=1，零匹配） | **编译器生成的 record equals/hashCode，覆盖全部 12 个 component** |
| `MapDiff` | 是（`:34`） | **否**（同 grep 只命中 `MapDiff.java:105/120` 的 `pc.equals(cc)` / `pp.equals(cp)` 调用，非定义） | **编译器生成的 record equals/hashCode，覆盖全部 10 个 component** |

→ 两个类都**不存在"手写 equals 漏比字段"的问题**；record 语义保证逐字段比对。
`MapDiff.java:105`（`!pc.equals(cc)`）与 `:120`（`!pp.equals(cp)`）正是**依赖 record equals** 做差异判定的证据。
参考测试也印证这一依赖：`gsim-map/src/test/java/com/gsim/map/map/HexCellTagsTest.java:18` 注释
`{@link MapDiff#compute} through the record {@code equals} chain`。

**但**：`MapData` 的 record equals 比较的是**全部 12 字段**，而 `MapDiff.compute` 只搬运其中 3 个领域字段 ——
即 `parent.equals(child)` 为 false 时，`compute` 仍可能返回 `isEmpty()==true` 的 diff。这是两个相等性口径之间的裂缝（可推知的后果，未实测）。

---

## 7. 序列化

**Jackson 注解清单（实测，无遗漏）**：

- `MapData`（`MapData.java:32`）：`@JsonDeserialize`（类级，无参）
- `MapDiff`（`MapDiff.java:33`）：`@JsonDeserialize`（类级，无参）
- 两个类的**每一个** component 都带 `@JsonProperty("<名字>")`（`MapData.java:34-46` 共 12 条；`MapDiff.java:35-44` 共 10 条）
- **`MapDiff` 唯一一处 `@JsonIgnore`**（`MapDiff.java:69-70`）：
  ```java
      /** Is this diff empty (no changes)? */
      @com.fasterxml.jackson.annotation.JsonIgnore
      public boolean isEmpty() {
  ```
  —— 但 `isEmpty()` 不是 record component，注解属**冗余**（即使不加也不会被序列化）
- **`MapData.java` 中 `@JsonIgnore` / `@JsonCreator` / `@JsonSerialize` 零出现**（实测 grep 无命中）
- **两处 `MapData.HexCell` 的迁移逻辑在紧凑构造器内**（`MapData.java:200-207`）：`riverMask` 非 0 且 `edgeTags` 为空时把 mask 展开成 `edgeTags`；这不是注解，但影响反序列化后的对象形态

**有没有字段不参与序列化？**

- `MapData` / `MapDiff` 的**所有 component 都参与**序列化，无 `@JsonIgnore` 落在任何 component 上。
- 两个**派生访问器**不参与（都不是 component）：`MapData.CompressedRegion.size()`（`MapData.java:534-536`，其 Javadoc 自述 `Derived from hexKeys size; not serialized.`）与 `MapDiff.isEmpty()`（69-70）。
- **未核实点**：我没有找到任何含 `compressedRegions` 的落盘 JSON 样例（`git grep -rln "compressedRegions" -- '*.json'` → 无输出），
  因此"`size()` 实际没被写进 JSON"这一条**只有代码推理，没有实测痕迹**。

**读写路径（供参考）**：`MapStore.saveFull` / `saveDiff` → `NodeLoader.saveAttachmentFile`（`NodeLoader.java:115-158`）
→ `Files.writeString(attachFile, JsonUtils.toJson(data))`（119）；读取 → `NodeLoader.loadAttachmentFile`（175-212）
→ `JsonUtils.fromJson(json, type)`（201）或 inline 回退 `JsonUtils.MAPPER.convertValue(raw, type)`（211）。
`JsonUtils.MAPPER` 配置为 `registerModule(new JavaTimeModule()).disable(FAIL_ON_UNKNOWN_PROPERTIES)`（`gsim-agentsmanager/src/main/java/com/gsim/agentsmanager/util/JsonUtils.java:15-18`）；
`gsim-docslib` 下另有一份同名同配置的 `JsonUtils`（两者同名不同包，**未核实 `NodeLoader` 实际 import 的是哪一个**）。

---

## 8. 现有测试对这条链的覆盖（事实陈述）

- 与 `MapResolver`/`MapDiff` 相关的测试**只有 3 个文件**：
  `gsim-map/src/test/java/com/gsim/map/map/HexCellTagsTest.java`、
  `gsim-map/src/test/java/com/gsim/map/service/MapServiceChildNodeSaveTest.java`、
  `gsim-map/src/test/java/com/gsim/map/service/MapServiceHexTagsTest.java`
- `MapServiceChildNodeSaveTest` 是**最接近**子节点保存链的用例：它只断言"父基线被自动创建 + 子 diff 文件存在"（`:66-71`），
  **不断言 resolve 回来的内容**
- 我**没有找到**任何断言"子节点写入 `terrainBlocks` / `terrainTypes` / `pathwayGroups` / `edges` / `gridSize` / `hexOrientation` 后，
  resolve 能拿回同值"的用例——即**没有任何守卫覆盖本报告第 3、4 节列出的漂移**。

---

## 9. 我未能核实的（明写）

1. **`JsonUtils` 的归属**：`NodeLoader` 里 `JsonUtils` 的 import 未逐字核对（存在 `gsim-agentsmanager` 与 `gsim-docslib` 两份同名类）。
   这不影响主结论（两份配置逐字相同），但"用哪一份"没实测。
2. **`CompressedRegion.size()` 是否真的没进 JSON**：无落盘样例可查，仅有代码推理。
3. **铁律 5 意义上的"往返不变式"**：GSimulator 里**本来就没有**这样的测试（第 8 节），
   所以"历史上是否曾有、后来被删"我**没有查**（未查 git log/历史）。
4. **漂移是何时引入的**：未查 `git log -p` 追这四个（六个）字段的引入时点，本次取证只做**当前状态**。
