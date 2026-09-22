### Task 8: `GenerationSpec` —— 参数面

**Files:**
- ★ **Modify**: `simos-map/src/main/java/io/mosire/simos/map/generate/GenerationSpec.java`
  （Task 5 建的是**只有 `seed` 的骨架**，本任务**扩写为完整参数面**）
- Create: `simos-map/src/main/java/io/mosire/simos/map/generate/NoiseBands.java` `RidgeParams.java` `FragmentParams.java`
- ★ **Inspect（不预设要改）**: `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java`
  （R-48-e：**先去看一眼** `apply` 里有没有对 `spec` 写 null 兜底）
- Test: `simos-map/src/test/java/io/mosire/simos/map/generate/GenerationSpecTest.java`

**这个任务解的是 L8**（参数面）。

★ **R-48-e 更正了原稿的"收紧"叙述**。原稿说 `GameMap.empty()` 与 `MapChangeSet.apply` 里
"都可能写着 `null` spec"、要本任务逐处改掉。**那条前提已被推翻**：Task 5 的 `empty()` 起就非 null
（`GenerationSpec.defaults(0L)`），Task 6 的 `apply` 也不写 null 兜底。⇒ **本任务无 null 可收紧。**

⇒ 本任务**仍然**加 `specIsNeverNullAfterTask8`（`GameMap.empty().spec()` 非 null）——
但它的身份是**守卫**（钉住"spec 从不 null"这条不变量），**不是"收紧动作"的证明**。
★ `MapChangeSet.java` 列进 Files 是**要你去核实前提**（走 R-48-e 的第 4 条），
**不是"必须先改"** —— 若 `apply` 里确实没有 null 兜底，**如实在报告里写"无需改动"**，
不要为了凑一条 diff 去动它。

- [ ] **Step 1: ★ 现读 GSimulator 的参数与魔法数字**

```bash
cd ~/DevMosire/GSimulator
git grep -n "mapRadius\|coastFreq\|roughness\|landRatio\|ridge\|fragment" -- '*.java' | head -80
```

**逐条记录**：形参名、方法体内的字面量（**行号**）、它在哪一个方法里。
★ **~60 个魔法数字的具体清单执行期现读，本计划故意不给** —— 控制器没有实测过它们。

- [ ] **Step 2: 写 `GenerationSpec`**

```java
package io.mosire.simos.map.generate;

/**
 * 生成的**全部**输入。落盘进 GameMap，故同 spec 必然同图。
 *
 * <p>★ 删掉 GSimulator 的两个**装饰形参**：{@code worldId}（生成器不需要知道世界 ID）
 * 与 {@code coastRoughness}（在函数体内从未被引用）。
 * <p>★ {@code landRatio} **改名 {@code baseSeaLevel}** —— 实测它只影响一个数
 * （{@code baseSeaLevel = 0.18 + (1-landRatio)*0.05}），参数名必须诚实反映作用。
 * <p>★ 全部范围校验**在构造期抛异常**，**不静默夹取** —— GSimulator 的
 * {@code Math.max(1, Math.min(mainCount, 2))} 让传 5 静默变成 2。
 */
public record GenerationSpec(
    long seed,
    int mapRadius,
    double baseSeaLevel,
    int mainRidges,
    int fragments,
    NoiseBands bands,
    RidgeParams ridges,
    FragmentParams fragmentParams,
    int contourCacheMax) {

  public GenerationSpec {
    if (mapRadius < 1) throw new IllegalArgumentException("mapRadius 必须 >= 1: " + mapRadius);
    if (baseSeaLevel < 0.0 || baseSeaLevel > 1.0) throw new IllegalArgumentException("…");
    // ★ R-48-a：合法区间 [1, 2]、越界抛（spec §9.3 要求 mainRidges=5 必须抛；
    // 同任务的 mainRidgesTwoIsAccepted 反证上界恰为 2）。原稿只有下界、无上界，与用例互斥。
    if (mainRidges < 1 || mainRidges > 2)
      throw new IllegalArgumentException("mainRidges 必须在 [1, 2]: " + mainRidges);   // ← 不夹取
    if (fragments < 1) throw new IllegalArgumentException("…");
    // ★ fragmentCount - secondary 可为负 —— 显式校验，见 FragmentParams
  }

  /** **唯一一份**默认值。 */
  public static GenerationSpec defaults(long seed) { /* … */ }
}
```

★ **`FragmentParams` 里那个可为负的差值**：GSimulator 的
`frags = fragmentCount - secondary`（`MapGenerator.java:105`）**可为负**。
本 record 必须在构造期把它挡住，**并写一条用例证明它真的挡住了**。

★ **U1 给本任务加的一道硬约束（别漏）**：**高度带只许有一份，持有者是 `TerrainCatalog`。**
U1 之后"某个高度算哪种地形"由 `TerrainType.minHeight/maxHeight` 唯一决定。
**`GenerationSpec`（含 `NoiseBands`/`RidgeParams`/`FragmentParams`）里不得出现任何
"按高度切地形"的阈值** —— 那是 L9 的第二份词表换个地方长出来。
允许留在 spec 里的是**形状参数**（噪声频率、脊线数量、海岸粗糙度这类造海拔的过程参数），
**不允许的是"海拔多高算山"这类分界**。
**报告里必须明写你如何判定 `NoiseBands` 属于前者而非后者** —— 这一条控制器没有实测过
`NoiseBands` 的字段，**是把判断权交给你，不是已经替你判好了**。

- [ ] **Step 3: 写用例**

```
GenerationSpecTest
  - defaultsIsUsable                    : defaults(1) 构造成功
  - ★ mainRidgesFiveThrows             : new …(mainRidges=5) → IllegalArgumentException（**不是**静默夹到 2）
  - mainRidgesTwoIsAccepted            : 边界内可用
  - ★ negativeFragmentDifferenceThrows  : fragmentCount - secondary < 0 → 抛
  - mapRadiusOneIsAccepted / zeroThrows
  - baseSeaLevelRangeChecked
  - ★ noWorldIdNoCoastRoughness        : 反射断言组件名里没有 "worldId"/"coastRoughness"
  - ★ seedIsAComponent                 : 反射断言有 seed 组件（L7 的落盘前提）
  - ★ noTerrainHeightThresholds        : ★ 见下  ← U1 加的
  - ★ specIsNeverNullAfterTask8        : ★ R-48-e：`GameMap.empty().spec()` **非 null**。
                                         这是**守卫**（钉住"spec 从不 null"），不是"收紧动作"的证明——
                                         前提已改：Task 5 起就非 null，本任务无 null 可收紧。
  - equalityIsComponentwise
```

★ **`noTerrainHeightThresholds` 怎么写**：**递归遍历 `GenerationSpec` 的全部组件及其嵌套 record**
（`NoiseBands`/`RidgeParams`/`FragmentParams` 都在内），断言**不存在** `double`/`float` 字段
与 `TerrainCatalog.KEYS` 里任一 key 同处一个类型 —— 也就是**没有任何类型同时知道"一个高度数"
与"一个地形名"**。这是结构性断言，不依赖你对字段语义的判断。
**若某个嵌套类型里确实既有一个 [0,1] 的 double、又有一个地形 key，报告里必须解释它为什么不是分界**
（这条会红，而它红了**不一定是错**）—— 那种情况**交回控制器裁定，不要自己放行**。

- [ ] **Step 4: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `mainRidges` 校验改成 `Math.max(1, Math.min(mainRidges, 2))`（即 GSimulator 的静默夹取） | **红** | `mainRidgesFiveThrows` 有判别力 |
| 删掉负差值校验 | **红** | `negativeFragmentDifferenceThrows` 不是装饰 |
| 加回 `worldId` 组件 | **红** | `noWorldIdNoCoastRoughness` 真会响 |
| ★ 往 `NoiseBands` 里加一对 `double mountainAbove` + `String terrainKey` | **红** | ★ `noTerrainHeightThresholds` 有判别力 —— **这是 U1 之后"高度带只有一份"的守卫** |

- [ ] **Step 5: 跑门禁并提交**

提交信息 `feat(map): GenerationSpec——参数面，静默夹取改构造期校验`。

---

