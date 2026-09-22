### Task 5: `map` 包 —— `HexCell` / `City` / `GameMap`

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/HexCell.java` `CityId.java` `City.java` `GameMap.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/generate/GenerationSpec.java`（★ **只建骨架**）
- ~~Modify: `.../map/hex/HexGrid.java`（补内容访问）~~ **★ R-48-d：已删** —— `GameMap` 自己持有
  `Map<HexCoord, HexCell> hexes`，不经 `HexGrid`；`HexGrid` **保持纯几何**（Task 1 已交付完毕）

★ **`GenerationSpec` 的骨架为什么由本任务建**（dispatch 前冲突扫描查出）：
`GameMap` 的 `spec` 组件**需要这个类型存在才能编译**，而完整参数面在 Task 8。
⇒ **本任务只建最小骨架**（`record GenerationSpec(long seed)` + `defaults(long)`），
**Task 8 再扩写为完整参数面**。不这样做，Task 5 根本编译不过。
- Test: `simos-map/src/test/java/io/mosire/simos/map/GameMapTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/HexCellTest.java`

**这个任务解的是 L7 的一半**（海拔落盘）与 **L8**（构造参数复制）。

- [ ] **Step 1: 写 `HexCell`（★ 加 `height`、删连通性字段）**

```java
package io.mosire.simos.map;

/**
 * 一个六边形格。
 *
 * <p>★ **有 {@code height}** —— GSimulator 的等价类没有，海拔只活在内存 LRU 里、问到就丢
 * （fallback 写死 0/0.5，{@code .height()} 全仓零调用点）。总纲 §5.1 的"自动河流：根据地形海拔"
 * 在没有落盘海拔的前提下**做不了**。
 *
 * <p>★ **没有任何连通性字段** —— GSimulator 的 {@code edgeTags}/{@code riverMask} 是 L2 的第二份存储
 * （Java 侧只读不写，只有前端写，且前端一存就把所有边的 props 抹平）。主存储是 GameMap.edges。
 */
public record HexCell(String terrain, double height) {
  public HexCell {
    if (terrain == null || terrain.isBlank()) throw new IllegalArgumentException("terrain 不得为空白");
    if (!Double.isFinite(height)) throw new IllegalArgumentException("height 必须是有限数: " + height);
    if (height < 0.0 || height > 1.0) throw new IllegalArgumentException("height 必须在 [0,1]: " + height);
  }
}
```

★ **`height` 归一到 `[0,1]`**：GSimulator 那套噪声输出的量纲不明（侦察 D 实测标度混用）。
**归一化让量纲成为类型的一部分**，跨模块（UnitSimos 的移动成本）才有同一个基准。

- [ ] **Step 2: 写 `CityId` / `City`**

```java
public record CityId(String value) { /* 非空白，同 RegionId 形制 */
  // ★ R-48-f：除值校验外，**必须**手写 `toString()` 返回**裸 value**，并配 `static CityId parse(String)`。
  //   理由：`keyOf = toString()`（Task 6）—— record 默认的 `CityId[value=c1]` 会让变更集的 key
  //   在 apply 侧还原不回来，`applyRebuildsTargetExactly` 当场红。与 `RegionId`/`PathwayId` 同形制。
}

public record City(CityId id, String name, io.mosire.simos.map.hex.HexCoord at,
                   io.mosire.simos.map.region.RegionId region,
                   java.util.Map<String, Object> props) {
  // 构造期校验 + 保序不可变
}
```

- [ ] **Step 3: 写 `GameMap`（★ 8 个组件，逐条对照 GSimulator 的 12 个）**

```java
package io.mosire.simos.map;

/**
 * 地图状态。
 *
 * <p>与 GSimulator 的 {@code MapData}（12 组件）逐条对照：
 * <ul>
 *   <li>删 {@code gridSize} —— 恒 30 的死值，不参与取格，与真实半径 80 矛盾
 *   <li>删 {@code hexOrientation} —— 恒 false、无读取分支，而实际公式是 pointy-top
 *   <li>删 {@code rivers}/{@code roads} —— 两个已废弃 record，语义由 {@link Pathway} 承载
 *   <li>删 {@code terrainBlocks} —— 编辑 Command 的历史，不是状态
 *   <li>删 {@code compressedRegions} —— 渲染缓存，可随时重算
 *   <li>加 {@code pathways} —— 取代废弃的 rivers/roads
 *   <li>加 {@code spec} —— 落盘 seed 与全部生成参数（L7）
 * </ul>
 */
public record GameMap(
    java.util.Map<HexCoord, HexCell> hexes,
    java.util.Map<RegionId, Region> regions,
    java.util.Map<CityId, City> cities,
    java.util.Map<String, TerrainType> terrainTypes,
    java.util.Map<PathwayId, Pathway> pathways,
    java.util.Map<String, PathwayGroup> pathwayGroups,
    java.util.Map<EdgeRef, EdgeTags> edges,
    GenerationSpec spec) {

  /** 空图。**所有 Map 都用保序不可变包装**（Map.copyOf 会打乱顺序）。 */
  public static GameMap empty() { /* … */ }

  /** 逐组件替换。**8 个 with 方法** —— 取代 GSimulator 的 12 参数构造复制。 */
  public GameMap withHexes(java.util.Map<HexCoord, HexCell> v) { /* … */ }
  // …另外 7 个

  /** 派生：归属反向索引。**不进组件、不进变更集**。 */
  public RegionIndex regionIndex() { /* … */ }
}
```

★ **本任务原本还有一个 `boundaryOf(RegionId)`，U2 之后删掉了**。理由：
U2 把边界变成 `Region` 的组件，于是 `regions().get(id).boundary()` 就是权威路径，
再开一个 `boundaryOf` 就是**同一概念的第二条路**（`RegionIndex` 与它不同：索引是派生的，
没有"存起来的那一份"可比）。**若你在旧草稿里看到 `boundaryOf` 且注释写着"派生、不进组件"，
那是 U2 之前的文本，别照抄。**
★ **注意 `RegionIndex` 与 `RegionBoundary` 在 U2 之后地位相反**：索引**仍**是派生、不进存储；
边界**不**是。Task 3 已把这条讲清，这里只作提醒。

★ **`spec` 的类型是 `GenerationSpec` 骨架**（本任务 Step 1 前建）。

★ **R-48-e 更正：`spec` 从本任务起就非 null，不留"临时可空"。**
原稿写"本任务先声明可空（`empty()` 给 `null`），Task 8 再收紧" —— **那样中间会留两轮
nullable 世界**（Task 6 的 `apply`、Task 7 的反射枚举都要绕开它），而收紧那一步**没人把守**。
现在：`empty()` 直接给 `GenerationSpec.defaults(0L)`；`spec` 组件**从不 null**。
`specIsNeverNullAfterTask8`（Task 8）是**守卫**，钉住这条不变量 —— 不是"收紧动作"本身。

★ **`empty()` 里的 `0L` 是"空图的种子"，不是"没有种子"** —— 语义上说得通：空图没有生成历史，
种子取规范值 0。**不要**为了"看起来诚实"改成 null。

- [ ] **Step 4: 写用例**

```
HexCellTest
  - rejectsBlankTerrain / rejectsNonFiniteHeight / rejectsOutOfRangeHeight
  - ★ hasNoConnectivityField        : 反射断言组件只有 {terrain, height}   ← 钉 L2
  - equalityIsComponentwise

GameMapTest
  - emptyIsNotNull且组件为空
  - ★ componentCountIsExactlyEight  : 反射断言 record components == 8      ← 钉字段清单
  - ★ noGridSizeNoHexOrientation    : 反射断言组件名里无 "gridSize"/"hexOrientation"
  - ★ noRiversNoRoadsNoTerrainBlocksNoCompressedRegions
  - withMethodsPreserveOtherComponents : 逐组件：只改一个，其余 equals 原值
  - mapsAreInsertionOrdered         : ★ 落盘序稳定（不用 Map.copyOf）
  - mapsAreImmutable                : put → UnsupportedOperationException
  - ★ regionIndexIsDerivedNotStored : 反射断言组件里没有 RegionIndex
  - ★ regionsCarryTheirBoundary     : ★ 放进 regions 的 Region，取回来 boundary() 非 null
                                      且 == RegionBoundary.of(它的 hexes)   ← U2 的落地检查

  # ★ R-48-f：`CityId` 的三件套（`RegionId` 同形制，见 Task 3）
  - cityIdToStringIsBareValue       : new CityId("c1").toString() 恰为 "c1"（不是 `CityId[value=c1]`）
  - cityIdParseRoundTripsFrozenLiteral
  - cityIdParseRejectsBlank
```

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| 给 `HexCell` 加回一个 `edgeTags` 组件 | **红** | `hasNoConnectivityField` 真会响（钉 L2） |
| 给 `GameMap` 加回 `gridSize` | **红** | `componentCountIsExactlyEight` 与 `noGridSizeNoHexOrientation` 真会响 |
| `empty()` 里改用 `Map.copyOf` | **红** | `mapsAreInsertionOrdered` 真的钉住了保序 |
| `withHexes` 里顺手把 `regions` 也改了 | **红** | `withMethodsPreserveOtherComponents` 有判别力 |
| `withRegions` 里把每个 `Region` 的 `boundary` 抹成空环 | **红**（由 `Region` 构造器抛 IAE） | ★ **U2 的钉子穿到了 GameMap 层**：连"从 Map 这一侧塞进不一致的 Region"也拦得住。**红来自异常，报告里写明** |

- [ ] **Step 6: 跑门禁并提交**

提交信息 `feat(map): GameMap——8 组件状态，删死字段与缓存，加海拔与生成参数`。

---

