### Task 7: ★ 往返框架 + 反射组件枚举 + 自证（M2 的硬判据）

**Files:**
- Create: `simos-map/src/test/java/io/mosire/simos/map/change/RoundTripComponentsTest.java`
- Modify: `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java`（若反射发现缺口）

**这是 M2 的判据 4（G13）与铁律 5 的落地，也是最要紧的一个任务。**

- [ ] **Step 1: 写反射枚举的往返测试**

```java
package io.mosire.simos.map.change;

/**
 * ★ **铁律 5 的机械化落地。**
 *
 * <p>不靠纪律：本测试**反射枚举 {@link GameMap} 的全部 record 组件**，逐组件制造差异，
 * 断言该差异真的进了变更集且能往返。**新增状态组件若忘了进变更集，本测试自动红。**
 *
 * <p>GSimulator 的教训：{@code MapDiff} 手工维护 ⇒ 6 个组件漂移出去、零守卫。
 */
class RoundTripComponentsTest {

  @Test
  void everyGameMapComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : GameMap.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;                       // ★ 唯一的豁免口，且 EXCLUDED 本身被下面钉死
      }
      // 1. base = GameMap.empty()；target = 在 rc 上换成合法新值（其余不动）
      // 2. cs = MapChangeSet.between(base, target)
      // 3. 断言 cs 在对应组件上 **不是** Unchanged     ← 忘了加进变更集 ⇒ 这里红
      // 4. assertThat(MapChangeSet.apply(cs, base)).isEqualTo(target);   ← 往返
    }
  }

  /** ★ 唯一的豁免集合。**加一项就是一次有意的决定，会在 diff 里现形。** */
  private static final java.util.Set<String> EXCLUDED_FROM_CHANGE_SET = java.util.Set.of("spec");

  @Test
  void theExclusionListIsExactlySpec() {
    // ★ 钉死豁免集本身 —— 否则"加字段忘了改"的补救方式会变成"往豁免集里塞一项"，
    //   护栏就出现了一个正好等于新字段大小的洞。
    assertThat(EXCLUDED_FROM_CHANGE_SET).containsExactly("spec");
  }

  @Test
  void everyChangeSetComponentCorrespondsToAGameMapComponent() {
    // 反方向：变更集的每个组件都必须在 GameMap 里有同名的 record 组件
  }

  @Test
  void changeSetHasExactlySevenComponents() {
    // ★ R-48-g（spec §9.1b 的 U2 守卫明文要求，原稿漏了）：
    //   反射断言 MapChangeSet.class.getRecordComponents().length == 7。
    // ★ 它与上一条**不重复**：上一条只保证"变更集的组件在 GameMap 里有同名者"，
    //   挡不住"两边**同时**多出一个同名的第 8 个组件"（那种漂移两边对称，反方向全绿）。
  }

  @Test
  void specIsDeliberatelyExcludedFromTheChangeSet() {
    // ★ 显式钉住那个有意的 8 vs 7 不对称：GameMap 有 spec，MapChangeSet 没有
  }
}
```

★ **豁免集为什么必须有，且必须被钉死**（dispatch 前冲突扫描查出的一处真冲突）：
`GameMap` 有 **8** 个组件，`MapChangeSet` 有意只有 **7** 个（`spec` 是生成输入、不是可变更状态）。
若本测试无条件遍历全部 8 个，`spec` 那一轮**必然**断言失败；若为它写一个 `if (name.equals("spec")) continue`，
**豁免口就成了一个洞** —— 以后任何人"加字段忘了进变更集"，都能靠往这个 `if` 里再加一个名字糊过去。
⇒ **把豁免写成一个被单独用例钉死的集合**：加名字是显式动作，diff 里看得见。

★ **U2 的连锁，报告里要明写**：`RegionBoundary` 成了 `Region` 的组件 ⇒ **`MapChangeSet` 不需要
为边界新开组件**（`regions` 整个 `Region` 值被比对，而 `Region.equals` 逐组件含 `boundary`）。
⇒ **组件数仍是 8 vs 7，`spec` 仍是唯一豁免项，V6 照旧。**
不写这句，后人看"boundary 没进变更集"会以为是被漏掉的。

★ **"在 rc 上换成合法新值"需要每个组件一个构造器**。用一个 `switch` 按组件名分派
⇒ **新增组件时这个 `switch` 会编译不过或走 `default` 抛异常**，**这正是想要的**：
它迫使加字段的人来读这个测试。

- [ ] **Step 2: ★ 护栏自证（G13）——本任务的核心工作**

**必须跑的变异（全部在 `/tmp` 的副本上做，绝不动工作树）**：

| # | 变异 | 期望 | 证明什么 |
|---|---|---|---|
| **V1** | 从 `MapChangeSet` 的 record 组件里**删掉 `edges`** | **红** | ★ **本护栏最核心的判别力**：漂移出去即响 |
| **V2** | 从 `MapChangeSet` 里删掉 `terrainTypes` | **红** | 同上，第二个组件 |
| **V3** | 给 `GameMap` 加一个新组件（如 `foo`），**不加进变更集** | **红** | ★ **"新增字段忘了加"这个场景真的被接住** |
| **V4** | `between` 里 `edges` 的比较改成恒 `Unchanged` | **红** | 断言 3（不是 Unchanged）有判别力 |
| **V5** | `apply` 里重建时**丢掉 `edges`** | **红** | 断言 4（往返）有判别力 |
| **V6** | 往 `EXCLUDED_FROM_CHANGE_SET` 里**加一项 `"edges"`** | **红** | ★ **豁免口不是洞** —— `theExclusionListIsExactlySpec` 有判别力；这一条防的是"用豁免糊过 V1/V3" |

**★ V3 是这一族里最要紧的一条** —— 它模拟的正是 GSimulator 出事的那个场景。
**它必须真的编译得过、真的跑到断言、真的红**；若它编译失败，那说明"加字段忘了改"是被
**编译期**接住的（那更好），**但要如实记进报告，不要把它算成测试期护栏**。

**变异体自证**：每条变异**读完测试结果之前**先比 `md5(变异产物) ≠ md5(原件参照)`。
用 `javac` 编一份原件作参照。**控制器在 M1 期间踩过"变异源码算了却没写盘、javac 编的是原件、
三向全绿"的坑 —— 不要信任 `/tmp` 里任何残留装置，自己重建。**

- [ ] **Step 3: ★ V1~V6 的"改前"必须真的跑在当前 HEAD 上**（★ R-48-h：**六条都要**，
      表上是 V1~V6 而这里原写 V1~V5；V6 是"往豁免集里加 `edges`"，**豁免口不是洞**、同样要证改前绿）

若你先改了文件才想跑"改前"，用 `git stash` 或 `git worktree` 取一份 HEAD 副本到 `/tmp`，
**不要在时间上撒谎**。报告里每条变异各带**改前一次、改后一次**的原始输出。

- [ ] **Step 4: 跑门禁并提交**

提交信息 `test(map): 反射把守的往返框架——组件漂移即红`。

---

