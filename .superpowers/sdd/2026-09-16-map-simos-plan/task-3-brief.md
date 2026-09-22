### Task 3: `region` 包 —— 权威区域 + **入存储的**边界 + 反向索引

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/region/RegionId.java` `RegionMeta.java` `Region.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/region/RegionBoundary.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/region/RegionIndex.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/region/RegionTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/region/RegionBoundaryTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/region/RegionIndexTest.java`

**这个任务解的是 L4 与 L5。**

> **★ 用户裁决 U2 推翻了控制器的原裁定**（详见 spec §〇.0 U2 与 §4.2/§4.3）：
> 控制器原本裁"边界纯派生、不进状态"，**用户的理由是「要不然数据持久化会出问题」**。
> 现在的形态：**`boundary` 是 `Region` 的组件**（因此自然落盘、自然往返，`MapChangeSet` **不需要新组件**），
> 而**规范构造器校验它等于由 `hexes` 重算的值**，不等即抛 —— **漂移在构造期就不可能发生**。
> **本节下面已按 U2 重写**，若你看到的还是"边界不在这里"，那是旧文本。

- [ ] **Step 1: 写 `RegionId` / `RegionMeta` / `Region`**

```java
package io.mosire.simos.map.region;

/** 区域的稳定身份。**与名字分离** —— GSimulator 的 Province 拿 map 的键当身份，改名就要重建键。 */
public record RegionId(String value) {
  public RegionId {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("RegionId 不得为空白");
  }
}

/** 区域的非内容元数据。 */
public record RegionMeta(String color, String tag, String description, String annexedBy) {
  /** 全空的元数据。 */
  public static RegionMeta empty() {
    return new RegionMeta(null, null, null, null);
  }
}
```

```java
package io.mosire.simos.map.region;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Set;

/**
 * 权威区域：一组 hex 的**命名**集合，**连同它的边界**。
 *
 * <p>★ 边界是**组件**（用户裁决 U2）：落盘、往返、进变更集都自然成立 —— 不需要为它单开字段。
 * 代价是它可能与 {@code hexes} 漂移，故**规范构造器把它钉死**：重算一遍，不等即抛。
 *
 * <p>★ GSimulator 的 {@code Province} 既无 name 字段（名字是 map 的键）也无边界字段，此处都补上。
 */
public record Region(RegionId id, String name, Set<HexCoord> hexes,
                     RegionBoundary boundary, RegionMeta meta) {

  public Region {
    if (id == null) throw new IllegalArgumentException("id 不得为 null");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不得为空白");
    hexes = Set.copyOf(hexes);          // 不可变；注意 Set.copyOf 不保序，故 hexes 的迭代序不可依赖
    if (boundary == null) throw new IllegalArgumentException("boundary 不得为 null");
    if (meta == null) meta = RegionMeta.empty();
    // ★ U2 的钉子：边界必须与 hexes 一致。这一步让"漂移"在构造期就不可能存在。
    RegionBoundary recomputed = RegionBoundary.of(hexes);
    if (!recomputed.equals(boundary)) {
      throw new IllegalArgumentException(
          "boundary 与 hexes 不一致：hexes 重算得 " + recomputed + "，传入的是 " + boundary);
    }
  }

  /**
   * ★ **正常代码走这个工厂**：边界**由 hexes 算出来**，不手写。
   * <p>直接调构造器只在反序列化（边界已由存档给出、需要被校验）时才合理。
   */
  public static Region of(RegionId id, String name, Set<HexCoord> hexes, RegionMeta meta) {
    return new Region(id, name, hexes, RegionBoundary.of(hexes), meta);
  }

  /** 是否含某格。**O(1)** —— GSimulator 是 List<String>.contains 线性扫描。 */
  public boolean contains(HexCoord c) {
    return hexes.contains(c);
  }

  /**
   * ★ **必须重算边界** —— U2 落地后这是最容易写错的一处。
   * 写成 {@code new Region(id, name, newHexes, boundary, meta)} 会被构造器当场抛掉（这正是钉子生效），
   * 但**别指望它**：直接用 {@link #of} 更省事，也让意图明了。
   */
  public Region withHexes(Set<HexCoord> newHexes) {
    return Region.of(id, name, newHexes, meta);
  }

  /** 改名不动内容 ⇒ 边界不变，可直接复用（**这是唯一可以原样传 boundary 的地方**）。 */
  public Region withName(String newName) {
    return new Region(id, newName, hexes, boundary, meta);
  }
}
```

★ **代价，写清楚**：边界的重算发生在**每一次 `new Region`** 上（含反序列化）。这是 O(边界格数)，
对一张地图的 region 总数而言是可接受的；**但它不是免费的，也不该被"顺手"调用** —— 批量构造
region 时优先用 `Region.of`，别在循环里先造了再改。

★ **`hexes` 用 `Set.copyOf`（不保序）是有意的**：它是**集合语义**，迭代序不该被依赖。
**需要保序的只有 `TerrainCatalog`**（落盘的是它）。两处的理由不同，**不要统一**。
★ 但 U2 之后这条**多了一层后果**：`boundary` 是 `Region` 的组件、`Region.equals` 是逐组件的，
**所以 `RegionBoundary.of` 必须是 `hexes` 的纯函数、且与迭代序无关** —— 见 Step 2 的规范性要求。

- [ ] **Step 2: 写 `RegionBoundary`（★ 入存储、但**可重算**，且必须**规范**）**

```java
package io.mosire.simos.map.region;

import io.mosire.simos.map.hex.HexCoord;
import java.util.List;
import java.util.Set;

/**
 * 区域的闭环边界。**是 {@link Region} 的组件（U2），同时是由 hexes 唯一确定的纯函数。**
 *
 * <p>★ 两个性质都不可少：**入存储**解决持久化（存档必须自带边界，否则每个读档方都要重新实现
 * 一遍推导 —— 那正是 GSimulator 的 {@code edgeKey} 四份副本那类病）；**可重算**解决漂移
 * （构造器重算一遍比对，不等即抛）。
 *
 * <p>★ **规范性**：{@link #of} 的结果必须**只由集合内容决定**，与入参 Set 的迭代序无关。
 * {@code hexes} 用 {@code Set.copyOf}（不保序），若本方法顺着迭代序走，两个内容相同的 Region
 * 会得到不同的 {@code boundary}，于是 `equals` 为假 —— 而它们本该相等。
 * **实现要求：先按 {@link HexCoord#compareTo} 排序，再定环的起点与绕行方向，二者都取规范值。**
 *
 * @param rings 每一条闭环。外环 + 可能的内环（洞），**环表本身也按规范序**。
 */
public record RegionBoundary(List<List<HexCoord>> rings) {
  public RegionBoundary {
    rings = rings.stream().map(List::copyOf).toList();
  }

  /**
   * 从 hex 集合计算边界。**纯函数**：同集合必得同结果，与迭代序无关。
   *
   * <p>★ 取 {@code Set<HexCoord>} 而**不是** {@code Region} —— U2 之后 {@code Region} 的构造
   * 需要 {@code RegionBoundary}，若本方法收 {@code Region} 就成死循环。
   */
  public static RegionBoundary of(Set<HexCoord> hexes) {
    // 实现：① 复制并排序（规范序）② 对每个边界格收集其朝外的边 ③ 串联成环
    //      ④ 每条环旋到字典序最小的顶点开头，绕行方向取规范（同向）
  }
}
```

★ **环的起点与方向也必须规范**，理由同上：两个内容相同的 Region 若起点不同，`rings` 就不同，
`equals` 就为假。**排序只解决"从哪个格开始扫"，不解决"环从哪个顶点开始"**，两者都要做。

- [ ] **Step 3: 写 `RegionIndex`（★ 派生索引，解 L5）**

```java
package io.mosire.simos.map.region;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Map;

/**
 * hex → 区域 的反向索引。**派生，不进变更集。**
 *
 * <p>★ 解 L5：GSimulator 有 6 处逐字重复的 {@code for (entry : map.provinces()) if (hexes.contains(key))}
 * 线性扫描（其中一处**不 break**，每次渲染都全扫），故每次 {@code hex:{q}_{r}} 地址解析都全表扫。
 * 本类把 {@code regionOf} 做成 O(1)。
 */
public final class RegionIndex {
  private final Map<HexCoord, RegionId> byHex;

  private RegionIndex(Map<HexCoord, RegionId> byHex) {
    this.byHex = byHex;
  }

  public static RegionIndex of(java.util.Collection<Region> regions) {
    // 重叠区域的裁决规则：**先按 id 字典序，后写入者不覆盖先写入者**（确定性）
  }

  public RegionId regionOf(HexCoord c) {
    return byHex.get(c);   // 无归属返回 null
  }

  public boolean hasRegion(HexCoord c) {
    return byHex.containsKey(c);
  }
}
```

- [ ] **Step 4: 写用例**

```
RegionTest
  - constructorRejectsBlankIdAndName
  - constructorRejectsNullId
  - constructorRejectsNullBoundary
  - hexesIsImmutable                     : 改入参 Set → 不影响 Region
  - containsIsSetBased                   : 含与不含各一例
  - equalityIsComponentwise              : 同五元组 → equal；id/name/hexes/boundary/meta 任一不同 → 不等
  - withHexesKeepsIdAndName              : ★ id/name 不随内容变            ← 铁律 1
  - withNameKeepsId                      : ★ 改名不改身份                  ← 铁律 1
  - metaDefaultsToEmptyWhenNull

  # ★ 以下三条是 U2 的钉子
  - boundaryIsAStoredComponent           : ★ 反射断言 Region **含** boundary 组件、类型为 RegionBoundary
                                           （与旧裁定的 `boundaryIsDerivedNotStored` **恰好相反**）
  - factoryComputesBoundaryFromHexes     : Region.of(...) 的 boundary == RegionBoundary.of(hexes)
  - constructorRejectsBoundaryThatDisagreesWithHexes
                                         : ★ 直接 new 一个 boundary 与 hexes 不符的 Region
                                           → IllegalArgumentException，消息含 "不一致"
  - withHexesRecomputesBoundary          : ★ withHexes(新集合) 后：
                                           ① hexes 确实是新的 ② boundary == 由新 hexes 重算的值
  - withNameKeepsBoundary                 : 改名不动内容 ⇒ boundary **引用不变**

RegionBoundaryTest
  - singleHexRingHasSixVertices          : 单格边界 6 个顶点
  - twoAdjacentHexesShareOneRing         : 相邻两格 → **一个**环（不是两个）
  - nonContiguousHexesGiveMultipleRings  : 两簇不连通 → **两个**环
  - storedBoundaryEqualsRecomputed       : region.boundary() equals RegionBoundary.of(region.hexes())
  - ★ boundaryIsIndependentOfInputSetIterationOrder
                                         : ★ 用两个**迭代序不同**的 Set（如 LinkedHashSet 正序 与 反序）
                                           装**同一批** hex → 两次 RegionBoundary.of 结果 equals。
                                           见下

RegionIndexTest
  - regionOfIsConstantTime               : ★ 见下
  - overlappingRegionsResolveDeterministically : 同输入两次结果相同
  - unknownHexReturnsNull
  # ★ 注意：本任务**不写** "GameMap 不含 RegionIndex" 那条断言 —— GameMap 在 Task 5 才存在。
  #   该断言归 Task 5 的 `regionIndexIsDerivedNotStored`，**只写一处**，不要两处重复。
  #   注意那里的"派生"指的是 **RegionIndex**，与 U2 之后**入存储的 region 边界**不冲突，别混。
```

★ **`boundaryIsIndependentOfInputSetIterationOrder` 是本任务最容易漏、也最该写的一条**。
U2 把 `boundary` 变成组件之后，`Region.equals` 就依赖它；而 `hexes` 是 `Set.copyOf`（**不保序**）。
**若 `RegionBoundary.of` 顺着迭代序走，内容相同的两个 Region 会 `equals` 为假** —— 一个
只在"两次构造的 Set 迭代序恰好不同"时才现形的 bug。上述用例**故意造出两种迭代序**，
正是为了让两种实现**在断言处真的分叉**（否则两种实现下断言全等价，等于空转）。

★ **`storedBoundaryEqualsRecomputed` 的第二种写法（必须用）**：不要只写
`RegionBoundary.of(r.hexes()).equals(RegionBoundary.of(r.hexes()))` —— 那是"算两次比两次"，
**只证明了确定性，没证明存储的那份是对的**。必须是
`r.boundary().equals(RegionBoundary.of(r.hexes()))`：**拿存储的那份去比**。

★ **`regionOfIsConstantTime` 怎么写才有判别力**：**不要**测时间（不稳）。
用**结构性断言**：索引的构造是 O(n)，`regionOf` 只做一次 `Map.get`。
可行的写法：构造 1000 hex × 100 region 与 1 hex × 1 region 两组，
断言 `regionOf` 的结果**不依赖索引里其他条目的数量** —— 即
`index1000.regionOf(c).equals(index1.regionOf(c))` 对同一个 `c` 成立，
**且**用一个 `CountingMap` 包一层断言 `regionOf` 只触发 **1 次** `get` 调用。

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| ★ **删掉规范构造器里"重算并比对"那两行** | **红** | `constructorRejectsBoundaryThatDisagreesWithHexes` 有判别力 —— **这就是 U2 的钉子本身** |
| `Region.withHexes` 里把 `newHexes` 写成 `hexes`（保持旧内容） | **红** | `withHexesRecomputesBoundary` 不是装饰。★ 见下 |
| `Region.withHexes` 改成原样传旧 `boundary`（不重算） | **红**（构造器抛 IAE） | ★ **预期红，但红的理由是构造器的校验，不是断言** —— 记进报告，别当成"这条用例钉住了" |
| `RegionBoundary.of` 不排序、顺着入参迭代序走 | **红** | `boundaryIsIndependentOfInputSetIterationOrder` 有判别力 |
| `RegionBoundary.of` 排了序，但环的起点不旋到规范顶点 | **红** | 同上 —— 证明"排序只解决一半"那句话不是空话 |
| `RegionIndex.regionOf` 改成遍历全部 regions 线性找 | **红** | 计数断言有判别力 |
| `Region.withHexes` 里改成 `new Region(new RegionId(name), ...)`（用 name 当 id） | **红** | `withHexesKeepsIdAndName` 真的钉住了"ID 是身份" |

★ **第 2 行是这张表里最该认真做的一条**：它的变异**不触发构造器异常**（内容与边界自洽，
只是内容是旧的），所以**红的必须来自断言** —— 这才证明 `withHexesRecomputesBoundary` 有判别力。
第 3 行则相反，红来自异常。**两行的"红"理由不同，报告里要分开写**（形态 1：红了还要问为什么红）。

★ **一条被 U2 反转的历史，记在这里免得后人看糊涂**：旧裁定下本表有一行是
"`Region` 加一个 `boundary` 组件 → 红（`boundaryIsDerivedNotStored`）"，
即**加组件是错的**。U2 之后**恰好相反**：`boundary` 是组件，而**去掉**它才会红。
若你在别处看到"边界是派生物"的旧措辞（例如 Task 5 的 `regionIndexIsDerivedNotStored`），
注意那条说的是 **`RegionIndex`** —— 索引仍是派生、不进存储；**边界不是**。

- [ ] **Step 6: 跑门禁并提交**

提交信息 `feat(map): region 包——权威 Region + 入存储的边界（构造期校验）+ O(1) 归属索引`。

---

