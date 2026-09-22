### Task 6: `change` 包 —— `FieldDelta` / `MapChangeSet`

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/change/FieldDelta.java` `MapChangeSet.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/change/MapChangeSetTest.java`

**这个任务解的是铁律 5**（变更集从完整状态类型派生），是 **M2 最硬的一块**。

- [ ] **Step 1: 写 `FieldDelta`**

```java
package io.mosire.simos.map.change;

/**
 * 一个状态组件的差异。
 *
 * <p>★ {@code Unchanged} 与"变为空"是**两件事** —— GSimulator 的 {@code MapDiff.isEmpty()}
 * 混淆了这两者，导致"只改了一条边"产生空 diff、进而**根本不进 apply 流程**。
 */
public sealed interface FieldDelta<T> {

  /** 未变。 */
  record Unchanged<T>() implements FieldDelta<T> {}

  /** 新增或覆盖。key → 新值。 */
  record Upsert<T>(java.util.Map<String, T> entries) implements FieldDelta<T> {
    public Upsert {
      entries = /* 保序不可变 */;
      if (entries.isEmpty()) throw new IllegalArgumentException("Upsert 不得为空");
    }
  }

  /** 删除。 */
  record Remove<T>(java.util.Set<String> keys) implements FieldDelta<T> {
    public Remove {
      keys = java.util.Set.copyOf(keys);
      if (keys.isEmpty()) throw new IllegalArgumentException("Remove 不得为空");
    }
  }

  /** 本组件是否有变化。 */
  default boolean changed() {
    return !(this instanceof Unchanged<T>);
  }

  /** 取出本组件里的某 key 的新值；未变或不在 Upsert 里则返回缺席。 */
  java.util.Optional<T> lookup(String key);
}
```

- [ ] **Step 2: 写 `MapChangeSet`（★ 组件与 `GameMap` 一一对应）**

```java
package io.mosire.simos.map.change;

/**
 * 地图状态的变更集。**组件与 {@link GameMap} 的 record 组件一一对应。**
 *
 * <p>铁律 5：变更集从完整状态类型派生。GSimulator 的 {@code MapDiff} 是**手工对着 MapData 维护**的，
 * 后果是 6 个组件漂移出去且零守卫。本类型由 {@code MapChangeSetTest} 的**反射枚举**把守 ——
 * 新增状态组件若不进变更集，那个测试自动红。
 */
public record MapChangeSet(
    FieldDelta<HexCell> hexes,
    FieldDelta<Region> regions,
    FieldDelta<City> cities,
    FieldDelta<TerrainType> terrainTypes,
    FieldDelta<Pathway> pathways,
    FieldDelta<PathwayGroup> pathwayGroups,
    FieldDelta<EdgeTags> edges) {

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static MapChangeSet between(GameMap base, GameMap target) { /* … */ }

  /**
   * 逐组件重建。铁律 5 的原文。
   *
   * <p>★ **R-48-e：7 个组件逐一从变更集重建，`spec` 从 `base` 原样带过来。**
   * 变更集里没有 `spec`（它是生成输入、不是可变更状态），所以**只有它**取自 base ——
   * 这一条要写进 Javadoc，否则后人会以为 `spec` 是漏掉的。
   * ★ **不得对 `spec` 写任何 null 兜底**（如 `cs.spec() != null ? … : base.spec()`）：
   * `GameMap.spec` 从 Task 5 起就非 null，兜底是**为不存在的世界写的代码**，且会掩盖真的漏传。
   */
  public static GameMap apply(MapChangeSet cs, GameMap base) { /* … */ }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() { /* … */ }
}
```

★ **`spec` 不进变更集**（它是生成输入，地图生成后只是溯源信息），
故 `MapChangeSet` 是 **7 个组件**而 `GameMap` 是 **8 个** —— 这个不对称是**有意的**，
但**必须被测试显式钉住**（见 Task 7），否则它会变成下一个"漂移"。

★ **`between` 的 key 类型**：`FieldDelta` 的 key 是 `String`，而 `GameMap` 的 map key 是
`HexCoord`/`RegionId`/`PathwayId`/`CityId`/`EdgeRef` ⇒ 需要一个 `keyOf` 转换。**它必须是 `toString()`**
（即 `HexCoord` 的 `"q_r"`），且 `apply` 侧用对应的 `parse` 还原。

★ **R-48-f（派单前扫描查出，这是本阶段最容易漏的一条）**：`keyOf = toString()` 要成立，
**每一个被当作 key 的类型都必须自己提供"裸值 `toString()` + `static parse(String)` + 冻结字面量往返用例"**
三件套。实测当时只有 `HexCoord` 齐备（Task 1 已交付）；`RegionId`/`PathwayId`/`CityId` 都是
`record X(String value)`，**既不覆写 `toString`（默认输出 `PathwayId[value=abc]`）、也没有 `parse`**
⇒ 往返当场断掉、`applyRebuildsTargetExactly` 必红。`EdgeRef` 有手写 `toString` 但**没有 `parse`、
也没有冻结串用例**（全局约束明文：没有冻结用例的手写 `toString` 算违规）。
⇒ 三件套**各自归其创建任务**：`RegionId`→Task 3、`PathwayId`/`EdgeRef`→Task 4、`CityId`→Task 5。
`toString()` 一律**裸值**（是地址，不是调试输出）。
★ 更正：本条原写「**这是 `"q_r"` 唯一被允许出现的地方**」——与 Task 4 里 `EdgeRef.toString()`
的「`a + "|" + b` 作为变更集 key」**冲突**。正确口径：**这是"地图 key 的规范串"的唯一允许处**，
`EdgeRef` 的 `"a|b"` 是**另一类 key**，两者并列、各自有冻结串用例。

- [ ] **Step 3: 写用例**

```
MapChangeSetTest（基础部分；★ 反射枚举部分在 Task 7）
  - betweenIdenticalIsAllUnchanged    : ★ 全 Unchanged，且 isEmpty() 为 true
  - betweenDetectsAddedHex            : Upsert 含新 key
  - betweenDetectsRemovedHex          : Remove 含旧 key
  - betweenDetectsChangedHexValue     : ★ 同 key 不同 value → Upsert（不是 Unchanged）
  - betweenDetectsChangedTerrainType  : ★ **R-48-i 补**：同 key 的 TerrainType 换了值 → 该组件 Upsert。
                                        **没有这条，下一行变异就是装饰**（见 Step 4 的说明）
  - applyRebuildsTargetExactly        : apply(between(b,t), b).equals(t)
  - applyOfAllUnchangedReturnsBase    : apply(between(b,b), b).equals(b)
  - upsertCannotBeEmptyOrRemoveCannotBeEmpty : 构造期拒绝空 delta
  - deltasAreImmutable
  - ★ emptyDiffStillEntersApply       : 见 Step 4
```

- [ ] **Step 4: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `between` 里把 `hexes` 的比较改成恒 `Unchanged` | **红** | `betweenDetectsChangedHexValue` 有判别力（这是 L1 的形态） |
| `between` 里 `terrainTypes` 的比较整个删掉（默认 Unchanged） | **红** | ★ **该组件漂移出去时测试会响** —— 这是铁律 5 的核心。★ **R-48-i：红的必须来自 `betweenDetectsChangedTerrainType`**。原稿只列了三条 `betweenDetects*` 且**全是 hexes**，删掉 `terrainTypes` 比较它们**照样绿** —— 那时红的只有 Task 7 的反射枚举，**别把它记成这条用例的判别力** |
| `between(x,x)` 改成返回 `null` | **红** | `betweenIdenticalIsAllUnchanged` 不是装饰 |
| `isEmpty()` 改成 `hexes.changed()`（只看一个组件） | **红** | ★ `emptyDiffStillEntersApply` 有判别力 |

★ **`emptyDiffStillEntersApply` 的来历**：侦察 B 实测，GSimulator 的
`MapResolver:76-82` **只在 `!diff.isEmpty()` 时才调 `applyDiff`**，
而 `isEmpty()` 也排除了 edges ⇒ "只改了一条边"产生空 diff、**根本不进 apply**。
**这是 L1 的第四个叠加成因。** 本用例钉住"空 diff 也必须能被 apply 且返回 base"。

- [ ] **Step 5: 跑门禁并提交**

提交信息 `feat(map): change 包——与 GameMap 组件一一对应的变更集`。

---

