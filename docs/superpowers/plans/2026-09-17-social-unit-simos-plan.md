# SocialSimos + UnitSimos（M3）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `simos-social` 与 `simos-unit` 从空模块建成 M3 交付物：人口分段积分时态序列 + 社会状态切片 + `social:` 寻址；单位身份的严格编制树 + 按时刻判定的位置继承 + `unit:` 寻址；按通行成本的最短路径（A\*）与按时间戳推进的移动物化。

**Architecture:** 两个模块各自是「不可变值类型 → 从完整状态类型派生的变更集 → 一个解析器」，与 M2 的 MapSimos 同形；两模块之间**零依赖**（都只依赖 `simos-util` + `simos-map`）。三处跨模块机制：

1. **`FieldDelta` + 它的 `diff`/`rebuild` 机制上移到 `util.state`**——三个变更集共用一份实现。★ spec 只说"上移 FieldDelta"（C7），本计划把 `diff`/`rebuild` **一并**上移并让 `MapChangeSet` 改为委托：否则 social/unit 会出现两份**逐字重复**的逻辑块（评审口径把"逐字重复的逻辑块"直接算缺陷），且三份实现必然漂移。
2. **`TerrainType.IMPASSABLE_MOVE_COST`** 是"不可通行"的单一来源（C5），unit 侧不复制第二份哨兵。
3. **人口积分与移动物化都不物化中间点**：per-tick 网格不是数据结构，是"当前段 + 查询点"的一个纯函数（C1 / 总纲 §5.3）。

**Tech Stack:** Java 21、Maven（`./mvnw`）、JUnit 5 + AssertJ、google-java-format（Spotless）、Checkstyle、SpotBugs。**不引入任何新依赖**。

**Spec:** `docs/superpowers/specs/2026-09-17-social-unit-simos-design.md`（M3 spec，**已获用户批准，2026-09-17**）。
上游：总纲 `2026-09-16-simos-master-design.md`、M1 spec `2026-09-16-util-simos-design.md`（已执行）、M2 spec（已关账）。
**执行者必须读 M3 spec 全文**（本计划的每一步都从它派生）；**spec 与 `src` 才是权威**。

> **⚠️ 本计划的代码草图是计划期产物。** M1 的教训：草图**编译得过但可能跑不过**。
> 执行期就地校正处**一律保留草图原貌 + 加取代说明**，**不要抹掉计划原文**——抹掉它等于抹掉"spec 在执行期被磨尖过"这件事。
> 本计划已在下列地方**主动记了取代说明**（执行者照取代后的口径做，不要照 spec 原文）：Task 3 的 `withEvent` 非递减校验归属、Task 8 的 `minStepCostMillis` 扫描口径、Task 12 的 ID-优先判据。

> **⚠️ 取值纪律（与 M2 计划的三类不同，M3 只有一类）：** 本计划出现的每个数字——`18036`、`10000`、`6300`、`15000`、`40000`、`12500`、`32500`、`27500`、`−5000`、`5000`、`1000`——**全部由 spec §3.6 / §4.5 冻结**。**照抄，不得自行发挥**；跑出来对不上 ⇒ 是**实现或本计划**有错，当场记录取代说明，**不许改夹具去迁就实现**。

---

## Global Constraints

- **Java 21**（`maven.compiler.release=21`）；Maven `[3.8,)`；父 POM `io.mosire:simos-parent:0.1.0-SNAPSHOT`，**不继承** `io.mosire:mosire-parent`
- **依赖白名单**（enforcer 构建期强制，越界即构建失败）：`simos-social` / `simos-unit` 的 compile 只有 `io.mosire:simos-util`、`io.mosire:simos-map`、`com.fasterxml.jackson.core:jackson-databind`、`org.slf4j:slf4j-api`；test 只有 `org.junit.jupiter:junit-jupiter`、`org.assertj:assertj-core`。**不得新增任何依赖**；**`simos-social` 永不 import `simos-unit`，`simos-unit` 永不 import `simos-social`**（enforcer 强制，不是约定）
- **不做任何存储**：**main** 源码不得出现 `java.io` / `java.nio.file` / `Files` / `Path` / `ProcessBuilder`。★ **测试可以**用 `Files` 做源码扫描——M2 的 `RegressionGuardsTest.repoRoot()` 即此形态，R1 沿用它
- **无领域词汇**：`simos-social/src/main` 不得出现 `unit` / `parent` / `mobility` / `route` 等 unit 领域词；`simos-unit/src/main` 不得出现 `population` / `growth` 等 social 词（Javadoc 举例与测试假数据除外）
- **中文注释与文档**；Javadoc **不手工调行宽**（google-java-format 按字符数折行，CJK 计 1 列）——写完跑 `./mvnw -q spotless:apply`
- **测试风格**：JUnit 5 + AssertJ；测试类包级私有、**类名与方法名用英文**（沿用 M2 风格），Javadoc 与注释用中文
- **迭代只跑相关单条用例**：`./mvnw -q -pl simos-social -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<类名> test`（`simos-unit` 同理；都在仓库根执行）
- **关账门禁**：`./mvnw clean verify` = Spotless(check) + Checkstyle(validate) + SpotBugs(verify) + enforcer + Surefire。**`mvn test` 不跑 SpotBugs**
- **护栏必须自证**（G13）：每条新护栏都要有**故意违规**的变异体证明它真的会响。五条形态（CLAUDE.md 纪律节）：① 删掉被保护那行看用例是否真红；**变异体先自证字节不同**（编一份原件比 md5）；**按白名单推成目标类名**（按变异文件名拷入会把"红"变成编译错误，不算数）；**每轮恢复干净世界**（`target/classes` 的旧 `.class` 会活到下一轮）；**强制断言 `COMPILATION ERROR` 计数为 0**；② `requireNonNull(x,"x")` 类的守卫只有**精确匹配**消息才有判别力；③ 判"同刻/相等"的用例输入必须落在两种实现会**分叉**的地方；④ 纯转发型 SPI 要有"参数原样转交"的用例；⑤ **"我验过了"与"我记得是这样"分开**——写进报告的每个 Expected 都要有当场跑过的痕迹
- **不可变与 equals**：所有状态类型是 record；`equals`/`hashCode` **一律由 record 提供、禁止手写**（往返断言的判据）。需要保序的 `Map` 用 `Collections.unmodifiableMap(new LinkedHashMap<>(…))`，**绝不用 `Map.copyOf`**（迭代序不是内容的纯函数，M2 实测）
- **提交纪律**：只 `git add <本步明确列出的文件>`，**绝不 `git add -A`**；提交前扫 `git diff --cached`；**该推就推**（私有仓库）
- **提交信息不加 `Co-Authored-By` trailer**：本仓既有提交**都没有**（`git log --format=%B` 实测）⇒ 沿用仓库风格。★ 本条**取代 M2 计划 Global Constraints 里"提交信息末尾加一行 Co-Authored-By"**那一条
- **本机 `grep` 是 `ugrep`**：尊重 `.gitignore` 且跳过隐藏目录，会**静默返回空**。查全仓用 `git grep`，或 `grep --hidden --no-ignore-files`
- **两个模块的 `src/test` 目录当前不存在**（只有 `package-info.java` 在 `src/main` 下），本计划各任务负责从零建立自己那部分

---

## 文件结构（M3 全景）

| 文件 | 职责 |
|---|---|
| `simos-util/.../util/state/FieldDelta.java` | ★ **上移**：一个状态组件的差异（四变体）**+ `diff`/`rebuild` 机制**（三个变更集共用） |
| `simos-map/.../map/terrain/TerrainType.java` | 增补 `IMPASSABLE_MOVE_COST` 具名常量（无行为变化） |
| `simos-map/.../map/change/MapChangeSet.java` | 改 import + `diff`/`rebuild` 改委托（机械重构，测试是回归网） |
| `simos-social/.../social/SocialData.java` | 社会状态：`Map<HexCoord, PopulationSeries>` |
| `simos-social/.../social/SocialSnapshot.java` | social 切片（`namespace() == "social"`） |
| `simos-social/.../social/population/PopulationSeries.java` | ★ 人口积分：anchor + 分段增长率 + 离散事件 |
| `simos-social/.../social/change/SocialChangeSet.java` | 人口变更集（委托 `FieldDelta.diff`/`rebuild`） |
| `simos-social/.../social/resolve/SocialResolver.java` | `social:` 寻址 |
| `simos-unit/.../unit/UnitId.java` | 单位身份（裸值 `toString` + `static parse` + 空白即抛） |
| `simos-unit/.../unit/Unit.java` | 单位（含 `parent` / `position` 两条时态序列） |
| `simos-unit/.../unit/Route.java` `Movement.java` | 路线与在途行程（**`Unit` 的组件** ⇒ 与 `Unit` 同包） |
| `simos-unit/.../unit/UnitState.java` | 编制树状态 + `effectivePosition` |
| `simos-unit/.../unit/UnitSnapshot.java` | unit 切片（`namespace() == "unit"`） |
| `simos-unit/.../unit/change/UnitChangeSet.java` | 单位变更集 |
| `simos-unit/.../unit/move/MovementCost.java` `TerrainMovementCost.java` | 毫 MP 成本的单一下界来源（`minStepCostMillis` 即 A\* 启发） |
| `simos-unit/.../unit/move/PathFinder.java` | A\* |
| `simos-unit/.../unit/move/MovementStatus.java` `MovementState.java` `UnitMoves.java` | 按时刻物化移动 |
| `simos-unit/.../unit/ops/UnitOperations.java` | 编制树操作面 8 项 |
| `simos-unit/.../unit/resolve/UnitResolver.java` | `unit:` 寻址（ID 形 + 链式定位形） |

测试文件与实现同包，位于各模块 `src/test/java/.../<pkg>/`。**子包不必加 `package-info.java`**（M2 的 `hex`/`terrain`/`region`/`pathway` 子包即无，只有模块根有）。

## 任务地图（按依赖排序，逐个提交）

| # | 任务 | 交付物 | 依赖 |
|---|---|---|---|
| 1 | `FieldDelta` 上移 util + `diff`/`rebuild` 提为静态机制 + M2 侧委托 + R1 守卫 | util 的 `FieldDelta`；map 改 import | — |
| 2 | `TerrainType.IMPASSABLE_MOVE_COST` + R2 守卫 | map 的常量 | 1 |
| 3 | `PopulationSeries`：构造校验 + 积分 + `segments()` + R3 + R4 | social 首个类型 | — |
| 4 | `SocialData` / `SocialSnapshot` / `SocialChangeSet` + 往返 | social 状态与变更集 | 1, 3 |
| 5 | `SocialResolver` + R12/R13 的 social 半 | `social:` 寻址 | 4 |
| 6 | `UnitId` / `Unit` / `UnitState`（树校验）+ `effectivePosition` + R5/R6/R7 | unit 状态层 | — |
| 7 | `UnitChangeSet` + 往返 | unit 变更集 | 1, 6 |
| 8 | `MovementCost` + `TerrainMovementCost` + 判据二夹具 | 成本层 | 2, 6 |
| 9 | `PathFinder`（A\*）+ R8 对拍与决定论 | 路径规划 | 8 |
| 10 | `Route` / `Movement` / `MovementState` / `UnitMoves.evaluate` + R9/R10 | 判据二 | 9 |
| 11 | `UnitOperations` 8 项 + R11 | 操作面 | 7, 10 |
| 12 | `UnitResolver` + R12/R13 的 unit 半 | `unit:` 寻址 | 11 |
| 13 | M3 关账：`./mvnw clean verify` + 四条判据逐条核 + `CLAUDE.md` + 报告 | 关账 | 全部 |

---

### Task 1: `FieldDelta` 上移 util + `diff`/`rebuild` 提为静态机制

**Files:**
- Move: `simos-map/src/main/java/io/mosire/simos/map/change/FieldDelta.java` → `simos-util/src/main/java/io/mosire/simos/util/state/FieldDelta.java`
- Modify: `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java`（import + 删私有 `diff`/`rebuild` 改委托）
- Modify（仅 import）: `simos-map/src/main/java/io/mosire/simos/map/generate/RegionRandomizer.java`、`simos-map/src/main/java/io/mosire/simos/map/generate/RiverBuilder.java`
- Modify（仅 import）: `simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java`、`.../map/change/MapChangeSetTest.java`、`.../map/change/RoundTripComponentsTest.java`、`.../map/generate/RegionRandomizerTest.java`、`.../map/generate/RiverBuilderTest.java`
- Test: 上面 `RegressionGuardsTest.java` 内新增 R1 用例（**不新建扫描底座**，复用它的 `repoRoot()` / `javaFilesUnder()` / `rawContent()` / `relative()`）

**Interfaces:**
- Consumes: 无（本任务在最上游）
- Produces:
  - `io.mosire.simos.util.state.FieldDelta<T>`：sealed interface，四变体 `Unchanged<T>` / `Upsert<T>(Map<String,T>)` / `Remove<T>(Set<String>)` / `Patch<T>(Upsert<T>,Remove<T>)`，`changed()`、`lookup(String)`
  - `static <K,V> FieldDelta<V> FieldDelta.diff(Map<K,V> base, Map<K,V> target)`
  - `static <K,V> Map<K,V> FieldDelta.rebuild(Map<K,V> base, FieldDelta<V> delta, Function<String,K> parse)`
  - `MapChangeSet.between` / `MapChangeSet.apply` 行为**逐字节不变**（M2 的 245 条用例是回归网）

- [ ] **Step 1: 移动文件**

```bash
git mv simos-map/src/main/java/io/mosire/simos/map/change/FieldDelta.java \
       simos-util/src/main/java/io/mosire/simos/util/state/FieldDelta.java
```

- [ ] **Step 2: 改 package 与类 Javadoc 的模块专指**

把首行 `package io.mosire.simos.map.change;` 改为 `package io.mosire.simos.util.state;`，
`import` 段追加 `java.util.function.Function`（供 `rebuild` 用）。
类 Javadoc **逐条改这三处**（其余原样保留——那些语义段落是 M2 的实测结论，一字不动）：

1. `（{@code MapChangeSet.apply} 逐条照此实现）` ⇒ `（各变更集的 {@code apply} 逐条照此实现）`
2. `{@code GameMap} 的 map key 由 {@code toString()} 变成地址串（五个 key 类型各有…` ⇒ `状态 map 的 key 由 {@code toString()} 变成规范串（各 key 类型各有…`
3. `{@code MapChangeSet.between} 不会产出这种重叠` ⇒ `{@link #diff} 不会产出这种重叠`

类 Javadoc 开头**新增一段**（记录它为什么在 util）：

```java
 * <p>★ **本类型在 Util，不在 map**（C7）：它是通用机制件——三个变更集（map / social / unit）共用同一份
 * "差异 + 重建"语义，放在 map 里会让 social/unit 的变更集依赖 map 的变更机制（语义错位）。配套机制
 * {@link #diff} / {@link #rebuild} 一并在此，故任何模块都不需要第二份实现（R1 扫描守卫把守"全仓恰一份"）。
```

- [ ] **Step 3: 把 `diff` / `rebuild` 提为接口的静态方法**

从 `MapChangeSet` **原样搬**这两个私有方法进来（**逻辑一字不改**，只加 `public static`、去掉 `private`），插在 `changed()` 之前：

```java
  /**
   * 两份 map 的差异（{@link #rebuild} 的逆）。
   *
   * <p>★ **顺着 {@code target} 的迭代序读**，故 upsert 的键序 = target 的序（保序不可变是前提，见类注释）。
   *
   * <p>★ **同时有"增"与"删" ⇒ {@link Patch}**（两侧各自是 {@link Upsert} 与 {@link Remove}），**两侧都保留、
   * 不丢任何一侧** —— 丢删除正是 GSimulator"只改了一条边产生空 diff"的病根。
   */
  static <K, V> FieldDelta<V> diff(Map<K, V> base, Map<K, V> target) {
    Map<String, V> upserts = new LinkedHashMap<>();
    Set<String> removals = new LinkedHashSet<>();
    for (Map.Entry<K, V> entry : target.entrySet()) {
      if (!entry.getValue().equals(base.get(entry.getKey()))) {
        // 同 key 不同 value 与"新增的 key"走同一条：base.get 缺席即 null，equals 必为 false。
        upserts.put(entry.getKey().toString(), entry.getValue());
      }
    }
    for (K key : base.keySet()) {
      if (!target.containsKey(key)) {
        removals.add(key.toString());
      }
    }
    if (upserts.isEmpty() && removals.isEmpty()) {
      return new Unchanged<>();
    }
    if (upserts.isEmpty()) {
      return new Remove<>(removals);
    }
    if (removals.isEmpty()) {
      return new Upsert<>(upserts);
    }
    return new Patch<>(new Upsert<>(upserts), new Remove<>(removals));
  }

  /**
   * 从 base 与差异重建一份 map（{@link #diff} 的逆）。四条变体各一路，见 {@link Patch} 的语义。
   *
   * <p>★ **只有新出现的 key 需要 {@code parse}**：已在 base 里的 key 直接复用原对象（这正是各 key 类型
   * "裸值 {@code toString()} + {@code static parse}" 三件套被用到的地方）。
   */
  static <K, V> Map<K, V> rebuild(
      Map<K, V> base, FieldDelta<V> delta, Function<String, K> parse) {
    if (!delta.changed()) {
      return base;
    }
    if (delta instanceof Remove<V> remove) {
      Map<K, V> out = new LinkedHashMap<>();
      for (Map.Entry<K, V> entry : base.entrySet()) {
        if (!remove.keys().contains(entry.getKey().toString())) {
          out.put(entry.getKey(), entry.getValue());
        }
      }
      return out;
    }
    if (delta instanceof Upsert<V> upsert) {
      Map<String, V> entries = upsert.entries();
      Set<String> fromBase = new LinkedHashSet<>();
      Map<K, V> out = new LinkedHashMap<>();
      for (Map.Entry<K, V> entry : base.entrySet()) {
        String key = entry.getKey().toString();
        fromBase.add(key);
        out.put(entry.getKey(), entries.containsKey(key) ? entries.get(key) : entry.getValue());
      }
      for (Map.Entry<String, V> entry : entries.entrySet()) {
        if (!fromBase.contains(entry.getKey())) {
          out.put(parse.apply(entry.getKey()), entry.getValue());
        }
      }
      return out;
    }
    if (delta instanceof Patch<V> patch) {
      // ★ **先删后增**（见 Patch 的语义），且**复用上面那两路**：Patch 的正确性恰恰**等于**
      //   "那两条纯情形的语义"，这里重新实现一遍就有了跟它们分叉的可能。递归调用即复用。
      return rebuild(rebuild(base, patch.removals(), parse), patch.upserts(), parse);
    }
    // 四条变体已穷尽；走到这里说明 FieldDelta 新增了变体而这里没跟上 —— 与铁律 5 同源的漂移，必须响。
    throw new IllegalStateException("未知的 FieldDelta 变体: " + delta.getClass());
  }
```

- [ ] **Step 4: `MapChangeSet` 改为委托**

`MapChangeSet.java`：把 import `io.mosire.simos.map.change` 那条（其实同包、原本没有）换成
`import io.mosire.simos.util.state.FieldDelta;`；**删掉两个私有方法 `diff` / `rebuild` 的整个方法体**（连 Javadoc）——它们的 Javadoc 要点已在 `FieldDelta` 的两个静态方法上；调用点改成静态入口：

```java
    return new MapChangeSet(
        FieldDelta.diff(base.hexes(), target.hexes()),
        FieldDelta.diff(base.regions(), target.regions()),
        FieldDelta.diff(base.cities(), target.cities()),
        FieldDelta.diff(base.terrainTypes(), target.terrainTypes()),
        FieldDelta.diff(base.pathways(), target.pathways()),
        FieldDelta.diff(base.pathwayGroups(), target.pathwayGroups()),
        FieldDelta.diff(base.edges(), target.edges()));
```

```java
    return new GameMap(
        FieldDelta.rebuild(base.hexes(), cs.hexes(), HexCoord::parse),
        FieldDelta.rebuild(base.regions(), cs.regions(), RegionId::parse),
        FieldDelta.rebuild(base.cities(), cs.cities(), CityId::parse),
        FieldDelta.rebuild(base.terrainTypes(), cs.terrainTypes(), STRING_KEY),
        FieldDelta.rebuild(base.pathways(), cs.pathways(), PathwayId::parse),
        FieldDelta.rebuild(base.pathwayGroups(), cs.pathwayGroups(), STRING_KEY),
        FieldDelta.rebuild(base.edges(), cs.edges(), EdgeRef::parse),
        base.spec());
```

`MapChangeSet` 的类 Javadoc 里 `{@link FieldDelta}` / `{@link FieldDelta.Patch}` 等引用**保持不变**（新 import 让它们继续解析）；`STRING_KEY` 常量保留。

- [ ] **Step 5: 机械修正其余 import**

逐个文件把 `import io.mosire.simos.map.change.FieldDelta;` 改成 `import io.mosire.simos.util.state.FieldDelta;`：

```bash
# main
#   simos-map/src/main/java/io/mosire/simos/map/generate/RegionRandomizer.java
#   simos-map/src/main/java/io/mosire/simos/map/generate/RiverBuilder.java
# test
#   simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java
#   simos-map/src/test/java/io/mosire/simos/map/change/MapChangeSetTest.java
#   simos-map/src/test/java/io/mosire/simos/map/change/RoundTripComponentsTest.java
#   simos-map/src/test/java/io/mosire/simos/map/generate/RegionRandomizerTest.java
#   simos-map/src/test/java/io/mosire/simos/map/generate/RiverBuilderTest.java
git grep -n "map.change.FieldDelta" -- 'simos-map/src'   # 改完必须为空
```

★ `CityId.java` / `PathwayId.java` / `RegionId.java` / `EdgeRefTest.java` / `PathwayGroupTest.java` 里的 `{@code FieldDelta}` 是 **Javadoc 文本**（不是 `{@link}`），**不需要** import、**不要**动它们。

- [ ] **Step 6: 跑 M2 既有用例，确认重构是行为不变的**

```bash
./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=MapChangeSetTest,RoundTripComponentsTest,RegressionGuardsTest,RiverBuilderTest,RegionRandomizerTest test
```

期望：**全绿**（这是本次重构唯一的回归网）。★ 若这里红，**先判定是重构错了而不是测试错了**——把这些用例当判据，别先改测试。

- [ ] **Step 7: 写 R1 守卫（全仓恰一份 `FieldDelta`）**

在 `simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java` 里追加（**复用该类既有的扫描底座**，只加一个方法）：

```java
  /**
   * ★ **R1（M3）**：全仓恰一份 {@code FieldDelta} 声明。
   *
   * <p>病灶形态：M3 有三个变更集（map / social / unit）共用这一套差异语义——若哪个模块自己再写一份，
   * 两份的语义立刻开始漂移（`Patch` 的先删后增、`Upsert` 的保序冻结都可能只改一边），而且**没有任何东西会响**。
   */
  @Test
  void R1_thereIsExactlyOneFieldDelta() {
    Map<String, Long> hits = new LinkedHashMap<>();
    for (String module : List.of("simos-util", "simos-map", "simos-social", "simos-unit")) {
      for (Path file : javaFilesUnder(repoRoot().resolve(module + "/src/main"))) {
        for (String line : rawLines(file)) {
          if (line.contains("interface FieldDelta")) {
            hits.merge(relative(file), 1L, Long::sum);
          }
        }
      }
    }
    assertThat(hits)
        .as("四个模块的 src/main 里必须恰有一份 FieldDelta 声明（第二份 = 语义必然漂移）")
        .containsExactly(
            entry("simos-util/src/main/java/io/mosire/simos/util/state/FieldDelta.java", 1L));
  }
```

（若该类还没 `import static org.assertj.core.api.Assertions.entry;` 就补上；`List` / `Path` / `LinkedHashMap` / `Map` 它都已 import。）

- [ ] **Step 8: 跑 R1 用例并做变异自证（G13）**

```bash
./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RegressionGuardsTest test
```
期望：`Tests run: 9`（原 8 + R1），全绿。

变异自证（**在 `/tmp` 的仓库副本上做，工作树一字不动**）：

1. 干净世界：逐文件 md5 清单对工作树，**清单之外的新增 `.java` = 0**；
2. 改前跑一遍（绿）；
3. 变异：在 `simos-map/src/main/java/io/mosire/simos/map/change/` 下**副本一份** `FieldDelta.java`（去掉 `sealed` 与四变体的细节也无妨，只要声明还在），文件名推成 `LegacyFieldDelta.java` **并确保类名与文件名一致**（`LegacyFieldDelta`）——★ 这条是"按**白名单**推成目标类名"的落地：不许把变异文件直接拷成 `FieldDelta.java`（那会与 util 那份同名，编译错，**不算数**）；
4. 自证：新落盘的文件非空、与原件**字节不同**；实际改动 == 声明集合；
5. 改后跑：**红点必须是** `R1_thereIsExactlyOneFieldDelta` 的 `containsExactly`，消息里带出那个多余的键；且 `COMPILATION ERROR count = 0`、测试真的跑过；
6. 红了问"为什么红"——理由必须是"多了一份声明"这件事本身。

- [ ] **Step 9: 门禁与提交**

```bash
./mvnw -q spotless:apply
./mvnw -q -Dtest=FieldDeltaGuardTest test   # 无此类，跳过；这一步只跑 spotless
./mvnw clean verify
```
期望：`BUILD SUCCESS`、`Tests run` 里 util 156 / map **246**（245 + R1）、`BugInstance size is 0` ×5。

```bash
git add simos-util/src/main/java/io/mosire/simos/util/state/FieldDelta.java \
        simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java \
        simos-map/src/main/java/io/mosire/simos/map/generate/RegionRandomizer.java \
        simos-map/src/main/java/io/mosire/simos/map/generate/RiverBuilder.java \
        simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java \
        simos-map/src/test/java/io/mosire/simos/map/change/MapChangeSetTest.java \
        simos-map/src/test/java/io/mosire/simos/map/change/RoundTripComponentsTest.java \
        simos-map/src/test/java/io/mosire/simos/map/generate/RegionRandomizerTest.java \
        simos-map/src/test/java/io/mosire/simos/map/generate/RiverBuilderTest.java
git diff --cached --stat      # 确认没有多扫
git commit -m "refactor(util): FieldDelta 上移 util.state 并把 diff/rebuild 提为共用静态机制（M3 Task 1）"
```

---

### Task 2: `TerrainType.IMPASSABLE_MOVE_COST` + R2 守卫

**Files:**
- Modify: `simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainType.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/terrain/ImpassableSentinelTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 无（本任务只碰 map）
- Produces: `public static final int TerrainType.IMPASSABLE_MOVE_COST = 999;`（unit 侧 Task 8 读它）

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.map.terrain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * ★ **R2（M3）**：不可通行的判据只有一处 —— {@link TerrainType#IMPASSABLE_MOVE_COST}。
 *
 * <p>UnitSimos 的成本函数要判"这格能不能走"，判据必须与词表里的海洋取值**同源**：各写各的 999
 * 就会在下一次调词表时静默分叉（改了一处、另一处还按老数走）。
 */
class ImpassableSentinelTest {

  @Test
  void oceanUsesTheImpassableSentinel() {
    assertThat(TerrainCatalog.of("ocean").moveCost())
        .as("海洋的 moveCost 必须就是那个具名哨兵（不是另写的字面量）")
        .isEqualTo(TerrainType.IMPASSABLE_MOVE_COST);
  }

  @Test
  void everyOtherTerrainIsBelowTheSentinel() {
    assertThat(TerrainCatalog.KEYS)
        .as("除海洋外每一项的 moveCost 都必须严格小于哨兵——哨兵不参与任何算术")
        .allSatisfy(
            key ->
                assertThat(TerrainCatalog.of(key).moveCost())
                    .as("地形 %s 的 moveCost", key)
                    .isLessThanOrEqualTo(TerrainType.IMPASSABLE_MOVE_COST - 1));
  }
}
```

★ 若 `TerrainCatalog` 没有公开 `KEYS`（M2 的 L9 守卫读过它，执行前先 `git grep -n "KEYS" -- simos-map/src/main` 核实），就改成显式列出七个 key 的字面量——**但那时要在 Javadoc 里写明"这七项照 `TerrainCatalog` 定序"，并且让断言逐项出现**，不要写成"看词表"。

- [ ] **Step 2: 跑测试确认失败**

```bash
./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ImpassableSentinelTest test
```
期望：**编译失败**——`TerrainType.IMPASSABLE_MOVE_COST` 还不存在（这是本任务唯一一次"红=编译错"，接下来必须消失）。

- [ ] **Step 3: 加常量**

`TerrainType` 的 record 体最前面加（并补一句 Javadoc）：

```java
  /**
   * ★ **不可通行的唯一判据**（M3 C5）：{@code moveCost >= 本常量} ⇒ 不可通行。
   *
   * <p>与 {@code TerrainCatalog} 里海洋的取值同源——**由 R2 守卫钉住**（{@code ImpassableSentinelTest}）。
   * 本常量**不参与任何算术**：它是"此路不通"的标记，不是"很贵的路"。
   */
  public static final int IMPASSABLE_MOVE_COST = 999;
```

并把类 Javadoc 里"不可通行以 `moveCost` 的哨兵值表达（本词表里海洋取的那个数）"改为
"不可通行以 `moveCost` 的哨兵值表达（**具名常量** {@link #IMPASSABLE_MOVE_COST}，海洋取的就是它）"。

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ImpassableSentinelTest test
```
期望：`Tests run: 2, Failures: 0`。

- [ ] **Step 5: 变异自证（G13）**

洁净副本上两轮，各自红点必须落在**被保护的那行**：

1. **m1**：把常量改成 `998` ⇒ `oceanUsesTheImpassableSentinel` 红（消息里 `expected: 998 but was: 999`），且 `everyOtherTerrainIsBelowTheSentinel` 大概率仍绿——**这说明两条用例各管一件事，不是冗余**；
2. **m2**：把 `TerrainCatalog` 里 ocean 的 `moveCost` 从哨兵改成 `998` ⇒ `oceanUsesTheImpassableSentinel` 红；
   两轮都要 `COMPILATION ERROR count = 0`、改前绿、变异体字节不同、跑前恢复干净世界。

- [ ] **Step 6: 提交**

```bash
./mvnw -q spotless:apply
git add simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainType.java \
        simos-map/src/test/java/io/mosire/simos/map/terrain/ImpassableSentinelTest.java
git diff --cached --stat
git commit -m "feat(map): TerrainType.IMPASSABLE_MOVE_COST 具名常量 + 词表一致性守卫（M3 Task 2）"
```

---

### Task 3: `PopulationSeries`：积分语义 + R3 + R4

**Files:**
- Create: `simos-social/src/main/java/io/mosire/simos/social/population/PopulationSeries.java`
- Test: `simos-social/src/test/java/io/mosire/simos/social/population/PopulationSeriesTest.java`
- Modify: `simos-social/src/main/java/io/mosire/simos/social/package-info.java`（把"不完善的测试模块"那句改成指向 `population` 包的一句话；**顺手但不强求**）

**Interfaces:**
- Consumes: `io.mosire.simos.util.time.{Segment, Event, EventMode, SegmentedSeries, SimosTimestamp, TemporalSeries}`
- Produces:
  - `record PopulationSeries(Segment<Long> anchor, SegmentedSeries<Double> growth, List<Event<Long>> events) implements TemporalSeries<Long>`
  - `public static final BinaryOperator<Long> PopulationSeries.ADDITION = Long::sum;`
  - `long valueAt(SimosTimestamp)`、`List<Segment<Long>> segments()`（= `List.of(anchor)`）、`List<Event<Long>> events()`
  - `PopulationSeries withGrowthSegment(SimosTimestamp at, double rate)`
  - `PopulationSeries withEvent(Event<Long> e)`

- [ ] **Step 1: 写失败测试（判据一 + 两条边界语义 + 四条构造校验）**

```java
package io.mosire.simos.social.population;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 判据一（M3 spec §3.6 的冻结手算表）+ 构造期校验（R4）。
 *
 * <p>★ 每个期望值都是从 spec 的表里**抄**来的，不是跑出来的：跑出来对不上 ⇒ 实现错了。
 */
class PopulationSeriesTest {

  /** 种子例：anchor 10000、growth 2%→1%→−3%、t=45 减 800。 */
  private static PopulationSeries seed() {
    return new PopulationSeries(
        new Segment<>(SimosTimestamp.of(0), 10000L),
        new SegmentedSeries<>(
            List.of(
                new Segment<>(SimosTimestamp.of(0), 0.02),
                new Segment<>(SimosTimestamp.of(20), 0.01),
                new Segment<>(SimosTimestamp.of(70), -0.03)),
            List.of(),
            null),
        List.of(new Event<>(SimosTimestamp.of(45), -800L, EventMode.ADD)));
  }

  @Test
  void seedExampleMatchesTheHandComputedTable() {
    assertThat(seed().valueAt(SimosTimestamp.of(53)))
        .as("[0,20)=+4000→14000；[20,45)=+3500→17500；t=45 −800→16700；[45,53)=+1336")
        .isEqualTo(18036L);
  }

  @Test
  void segmentBoundaryAndEventAtTheSameTick() {
    PopulationSeries series =
        new PopulationSeries(
            new Segment<>(SimosTimestamp.of(0), 1000L),
            new SegmentedSeries<>(
                List.of(
                    new Segment<>(SimosTimestamp.of(0), 0.1),
                    new Segment<>(SimosTimestamp.of(10), 0.2)),
                List.of(),
                null),
            List.of(new Event<>(SimosTimestamp.of(10), 100L, EventMode.ADD)));

    assertThat(series.valueAt(SimosTimestamp.of(20)))
        .as("t=10 必须先切段（速率 0.2 生效）再施事件：2100 + round(2100×0.2×10) = 6300")
        .isEqualTo(6300L);
  }

  @Test
  void multipleEventsAtTheSameTickFollowInsertionOrder() {
    PopulationSeries series =
        new PopulationSeries(
            new Segment<>(SimosTimestamp.of(0), 1000L),
            new SegmentedSeries<>(
                List.of(
                    new Segment<>(SimosTimestamp.of(0), 0.1),
                    new Segment<>(SimosTimestamp.of(10), 0.2)),
                List.of(),
                null),
            List.of(
                new Event<>(SimosTimestamp.of(10), 100L, EventMode.ADD),
                new Event<>(SimosTimestamp.of(10), 5000L, EventMode.SET)));

    assertThat(series.valueAt(SimosTimestamp.of(20)))
        .as("同刻按插入序：[5000 + round(5000×0.2×10)] = 15000（换成 SET 在前就得到另一个数）")
        .isEqualTo(15000L);
  }

  @Test
  void beforeAnchorIsConstant() {
    assertThat(seed().valueAt(SimosTimestamp.of(-5)))
        .as("anchor 之前向前恒定延拓：不增长、不施事件")
        .isEqualTo(10000L);
  }

  @Test
  void segmentsIsExactlyTheAnchor() {
    assertThat(seed().segments()).as("唯一的分段常量声明就是 anchor（spec 偏离 1）").hasSize(1);
    assertThat(seed().segments().get(0).from()).isEqualTo(SimosTimestamp.of(0));
  }

  // ── R4：构造期校验 ────────────────────────────────────────────────

  @Test
  void growthFirstSegmentStartsAfterAnchorThrows() {
    assertThatThrownBy(
            () ->
                new PopulationSeries(
                    new Segment<>(SimosTimestamp.of(5), 1000L),
                    new SegmentedSeries<>(
                        List.of(new Segment<>(SimosTimestamp.of(10), 0.02)), List.of(), null),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("growth");
  }

  @Test
  void growthCarryingEventsIsRejected() {
    assertThatThrownBy(
            () ->
                new PopulationSeries(
                    new Segment<>(SimosTimestamp.of(0), 1000L),
                    new SegmentedSeries<>(
                        List.of(new Segment<>(SimosTimestamp.of(0), 0.02)),
                        List.of(new Event<>(SimosTimestamp.of(3), 0.05, EventMode.SET)),
                        null),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("growth");
  }

  @Test
  void eventsMustBeNonDecreasing() {
    PopulationSeries base = seed();
    assertThatThrownBy(() -> base.withEvent(new Event<>(SimosTimestamp.of(1), 1L, EventMode.ADD)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非递减");
  }

  @Test
  void nullComponentsAreRejected() {
    assertThatThrownBy(() -> new PopulationSeries(null, seed().growth(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PopulationSeries(seed().anchor(), null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PopulationSeries(seed().anchor(), seed().growth(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withGrowthSegmentAndWithEventReturnNewValues() {
    PopulationSeries longer = seed().withGrowthSegment(SimosTimestamp.of(100), 0.5);
    assertThat(longer.growth().segments()).hasSize(4);
    assertThat(seed().growth().segments()).as("record 值语义：旧值不变").hasSize(3);
    assertThat(seed().valueAt(SimosTimestamp.of(0))).isEqualTo(10000L);

    PopulationSeries withEvent =
        seed().withEvent(new Event<>(SimosTimestamp.of(46), 100L, EventMode.ADD));
    assertThat(withEvent.events()).hasSize(2);
    assertThat(seed().events()).hasSize(1);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./mvnw -q -pl simos-social -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PopulationSeriesTest test
```
期望：编译失败——`PopulationSeries` 不存在。

- [ ] **Step 3: 实现**

```java
package io.mosire.simos.social.population;

import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TemporalSeries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BinaryOperator;

/**
 * 人口序列：**积分型**时态序列（M3 spec §3.2）。
 *
 * <p>与 {@link SegmentedSeries} 的"分段常量"不同，人口在段内**连续变化**：段内单利
 * `Δp = round(p × rate × Δt)`、跨段复利（每段的基数 = 上一段末的人口）、事件在其时刻立刻改变基数。
 * 故 {@link #segments()} 只声明 anchor 这一件分段常量，增长率的分段货真价实地活在 {@link #growth()} 里。
 *
 * <p>★ **不缓存**（C1）：每次 {@link #valueAt} 从 anchor 现算，复杂度 O(段数 + 事件数)。
 *
 * <p>★ 本类型**不物化 per-tick 网格**：tick 网格是"当前段 + 查询点"的纯函数，不是数据结构。
 */
public record PopulationSeries(
    Segment<Long> anchor, SegmentedSeries<Double> growth, List<Event<Long>> events)
    implements TemporalSeries<Long> {

  /** 人口 `ADD` 的加法（M1 纪律：模块级 `static final`，各写各的 lambda 会让往返以"序列不相等"假红）。 */
  public static final BinaryOperator<Long> ADDITION = Long::sum;

  public PopulationSeries {
    if (anchor == null) {
      throw new IllegalArgumentException("anchor 不得为 null");
    }
    if (growth == null) {
      throw new IllegalArgumentException("growth 不得为 null");
    }
    if (events == null) {
      throw new IllegalArgumentException("events 不得为 null");
    }
    List<Event<Long>> frozenEvents = new ArrayList<>(events.size());
    for (Event<Long> event : events) {
      if (event == null) {
        throw new IllegalArgumentException("events 不得含 null");
      }
      frozenEvents.add(event);
    }
    events = Collections.unmodifiableList(frozenEvents);
    if (growth.segments().get(0).from().compareTo(anchor.from()) > 0) {
      throw new IllegalArgumentException(
          "growth 的首段晚于 anchor：anchor 到首段之间无速率可依（不许向前瞎猜，spec §3.2 第 2 条）");
    }
    if (!growth.events().isEmpty()) {
      throw new IllegalArgumentException(
          "growth 不得带事件：速率的变更一律用追加段表达（否则段内积分会按左端点取速率、静默算错，spec §3.2 第 3 条）");
    }
    requireNonDecreasing(events);
  }

  /**
   * ★ **取代说明（计划期）**：spec §3.3 把"事件非递减"记在 `SegmentedSeries` 名下——但本类型的事件是**裸 `List`**，
   * 不经过 `SegmentedSeries`，那条校验根本不会发生。责任改由本构造器承担，**约束不变**（`at` 早于现末尾 ⇒ 抛）。
   */
  private static void requireNonDecreasing(List<Event<Long>> events) {
    for (int i = 1; i < events.size(); i++) {
      if (events.get(i).at().compareTo(events.get(i - 1).at()) < 0) {
        throw new IllegalArgumentException(
            "events 必须按 at 非递减（同刻允许——那正是"同刻多事件"的表达）：第 "
                + i
                + " 个事件回退了");
      }
    }
  }

  /** 追加一个增长率段（不改人口、不动 anchor 与 events）。同刻重复由严格升序校验抛。 */
  public PopulationSeries withGrowthSegment(SimosTimestamp at, double rate) {
    List<Segment<Double>> segments = new ArrayList<>(growth.segments());
    segments.add(new Segment<>(at, rate));
    return new PopulationSeries(
        anchor, new SegmentedSeries<>(segments, List.of(), null), events);
  }

  /** 把事件追加到列表末尾（列表序即施加序）。`at` 早于现末尾 ⇒ 抛。 */
  public PopulationSeries withEvent(Event<Long> event) {
    if (event == null) {
      throw new IllegalArgumentException("event 不得为 null");
    }
    List<Event<Long>> next = new ArrayList<>(events);
    next.add(event);
    return new PopulationSeries(anchor, growth, next);
  }

  /**
   * spec §3.2 的五步算法，逐字实现：
   *
   * <ol>
   *   <li>{@code t < anchor.from()} ⇒ 恒定延拓，返回 anchor 值；
   *   <li>切分点 = anchor 起 ∪ (anchor, t] 内的 growth 段边界 ∪ [anchor, t] 内的事件时刻 ∪ {t}，去重升序；
   *   <li>逐区间 {@code p += Math.round(p × growth.valueAt(区间左端) × 宽度)}；
   *   <li>每个切分点处**先切段（上一步已用新速率）再施事件**，同刻多事件按列表序（ADD 累加 / SET 覆盖）；
   *   <li>返回 {@code p}（不夹取）。
   * </ol>
   */
  @Override
  public Long valueAt(SimosTimestamp t) {
    Objects.requireNonNull(t, "t");
    SimosTimestamp start = anchor.from();
    if (t.compareTo(start) < 0) {
      return anchor.value();
    }
    List<SimosTimestamp> cuts = cutPoints(t);
    long population = anchor.value();
    population = applyEventsAt(population, start); // anchor 那一瞬的事件（若有）
    for (int i = 0; i + 1 < cuts.size(); i++) {
      SimosTimestamp from = cuts.get(i);
      SimosTimestamp to = cuts.get(i + 1);
      double rate = growth.valueAt(from);
      long width = to.tick() - from.tick();
      if (rate != 0.0 && width > 0) {
        population += Math.round(population * rate * width);
      }
      population = applyEventsAt(population, to);
    }
    return population;
  }

  /** 切分点：anchor 起、growth 段边界、事件时刻、查询点本身；去重（按 `compareTo`）升序。 */
  private List<SimosTimestamp> cutPoints(SimosTimestamp t) {
    List<SimosTimestamp> cuts = new ArrayList<>();
    SimosTimestamp start = anchor.from();
    cuts.add(start);
    for (Segment<Double> segment : growth.segments()) {
      if (segment.from().compareTo(start) > 0 && segment.from().compareTo(t) <= 0) {
        cuts.add(segment.from());
      }
    }
    for (Event<Long> event : events) {
      if (event.at().compareTo(start) >= 0 && event.at().compareTo(t) <= 0) {
        cuts.add(event.at());
      }
    }
    cuts.add(t);
    cuts.sort(SimosTimestamp::compareTo);
    List<SimosTimestamp> deduped = new ArrayList<>(cuts.size());
    for (SimosTimestamp cut : cuts) {
      if (deduped.isEmpty() || deduped.get(deduped.size() - 1).compareTo(cut) != 0) {
        deduped.add(cut);
      }
    }
    return deduped;
  }

  /** `at` 时刻的事件，按列表插入序施加。 */
  private long applyEventsAt(long population, SimosTimestamp at) {
    long result = population;
    for (Event<Long> event : events) {
      if (event.at().compareTo(at) != 0) {
        continue;
      }
      result =
          event.mode() == EventMode.ADD ? ADDITION.apply(result, event.value()) : event.value();
    }
    return result;
  }

  /** ★ 本序列唯一的"分段常量"就是 anchor（spec 偏离 1）：人口在段内连续变化，不是分段常量。 */
  @Override
  public List<Segment<Long>> segments() {
    return List.of(anchor);
  }
}
```

★ 注意 `requireNonDecreasing` 的消息里有**中文引号**——Spotless/Checkstyle 不会拦，但若工具链报字符问题，改写成「同刻多事件」。

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q -pl simos-social -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PopulationSeriesTest test
```
期望：`Tests run: 10, Failures: 0`。

- [ ] **Step 5: 变异自证（G13，至少 3 轮）**

1. **m1**：把 `[45, 53)` 那段改成"整段一次性积分"（把 `cuts.add(t)` 去掉，令末区间变成 `[45, 70)`…换一种最小改法：把 `population += Math.round(...)` 的舍入挪到整段外——**任选一种能改变结果的**），期望 `seedExampleMatchesTheHandComputedTable` 红且消息里 `expected: 18036L`；
2. **m2**：`applyEventsAt` 的 SET/ADD 分支对调 ⇒ `multipleEventsAtTheSameTickFollowInsertionOrder` 红；
3. **m3**：删掉 `growth.events().isEmpty()` 那条校验 ⇒ `growthCarryingEventsIsRejected` 红。
   每轮：干净世界（md5 清单、清单外新增 `.java` = 0）→ 改前绿 → 变异 → 字节不同自证 → 改后红且 `COMPILATION ERROR count = 0` → red 的理由就是被保护那行。

- [ ] **Step 6: 提交**

```bash
./mvnw -q spotless:apply
git add simos-social/src/main/java/io/mosire/simos/social/population/PopulationSeries.java \
        simos-social/src/test/java/io/mosire/simos/social/population/PopulationSeriesTest.java \
        simos-social/src/main/java/io/mosire/simos/social/package-info.java
git diff --cached --stat
git commit -m "feat(social): PopulationSeries 分段积分时态序列 + 判据一手算表与构造校验（M3 Task 3）"
```

---

### Task 4: `SocialData` / `SocialSnapshot` / `SocialChangeSet` + 往返框架

**Files:**
- Create: `simos-social/src/main/java/io/mosire/simos/social/SocialData.java`
- Create: `simos-social/src/main/java/io/mosire/simos/social/SocialSnapshot.java`
- Create: `simos-social/src/main/java/io/mosire/simos/social/change/SocialChangeSet.java`
- Test: `simos-social/src/test/java/io/mosire/simos/social/change/SocialChangeSetTest.java`
- Test: `simos-social/src/test/java/io/mosire/simos/social/change/SocialRoundTripTest.java`

**Interfaces:**
- Consumes: `FieldDelta.diff` / `FieldDelta.rebuild`（Task 1）；`PopulationSeries`（Task 3）
- Produces:
  - `record SocialData(Map<HexCoord, PopulationSeries> populations)` + `static SocialData empty()` + `SocialData withPopulations(Map<…>)`
  - `record SocialSnapshot(StateRef ref, SimosTimestamp timestamp, SocialData data) implements Snapshot`（`namespace() == "social"`）
  - `record SocialChangeSet(FieldDelta<PopulationSeries> populations)` + `between` / `apply` / `isEmpty`

- [ ] **Step 1: 写失败测试**

`SocialChangeSetTest`（行为；夹具放本类）：

```java
package io.mosire.simos.social.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 人口变更集：与 `MapChangeSet` 同形（同键覆盖、新增、删除、又增又删 = Patch、空集）。 */
class SocialChangeSetTest {

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final HexCoord H20 = new HexCoord(2, 0);

  private static PopulationSeries population(long people) {
    return new PopulationSeries(
        new Segment<>(SimosTimestamp.of(0), people),
        new SegmentedSeries<>(
            List.of(new Segment<>(SimosTimestamp.of(0), 0.02)), List.of(), null),
        List.of());
  }

  private static SocialData data(Map<HexCoord, PopulationSeries> populations) {
    return new SocialData(populations);
  }

  @Test
  void unchangedDataGivesAnEmptyChangeSet() {
    SocialData base = data(Map.of(H00, population(100)));
    assertThat(SocialChangeSet.between(base, base).isEmpty()).isTrue();
    assertThat(SocialChangeSet.between(base, base).populations())
        .isInstanceOf(FieldDelta.Unchanged.class);
  }

  /** ★ 只改一条记录 ⇒ 非空。这一条就是 GSimulator"只改了一条边产生空 diff"的逆否形态。 */
  @Test
  void aSingleChangedEntryIsNotAnEmptyChangeSet() {
    SocialData base = data(Map.of(H00, population(100), H10, population(200)));
    Map<HexCoord, PopulationSeries> next = new LinkedHashMap<>();
    next.put(H00, population(100));
    next.put(H10, population(201));
    SocialData target = data(next);

    SocialChangeSet cs = SocialChangeSet.between(base, target);
    assertThat(cs.isEmpty()).isFalse();
    assertThat(SocialChangeSet.apply(cs, base)).isEqualTo(target);
  }

  @Test
  void removalWithoutUpsertIsRemoveAndSurvivesApply() {
    Map<HexCoord, PopulationSeries> next = new LinkedHashMap<>();
    next.put(H00, population(100));
    SocialData base = data(Map.of(H00, population(100), H10, population(200)));
    SocialData target = data(next);

    SocialChangeSet cs = SocialChangeSet.between(base, target);
    assertThat(cs.populations()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(SocialChangeSet.apply(cs, base).populations()).containsOnlyKeys(H00);
  }

  @Test
  void addAndRemoveTogetherIsAPatch() {
    Map<HexCoord, PopulationSeries> next = new LinkedHashMap<>();
    next.put(H00, population(100));
    next.put(H20, population(300));
    SocialData base = data(Map.of(H00, population(100), H10, population(200)));
    SocialData target = data(next);

    SocialChangeSet cs = SocialChangeSet.between(base, target);
    assertThat(cs.populations()).isInstanceOf(FieldDelta.Patch.class);
    assertThat(SocialChangeSet.apply(cs, base)).as("Patch 两侧都不许丢").isEqualTo(target);
  }

  @Test
  void applyOfUnchangedKeepsTheBaseMapIdentical() {
    SocialData base = data(Map.of(H00, population(100)));
    SocialChangeSet cs = SocialChangeSet.between(base, base);
    assertThat(SocialChangeSet.apply(cs, base).populations()).isSameAs(base.populations());
  }

  @Test
  void socialDataIsFrozenAndRejectsNulls() {
    Map<HexCoord, PopulationSeries> mutable = new LinkedHashMap<>();
    mutable.put(H00, population(100));
    SocialData data = data(mutable);
    mutable.put(H10, population(200));
    assertThat(data.populations()).as("构造期已冻结").containsOnlyKeys(H00);
    assertThat(data.populations().get(H00).anchor().value()).isEqualTo(100L);

    Map<HexCoord, PopulationSeries> withNull = new LinkedHashMap<>();
    withNull.put(H00, null);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> data(withNull))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> data(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

`SocialRoundTripTest`（铁律 5 的机械化落地）：

```java
package io.mosire.simos.social.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 铁律 5 的机械化落地（照 M2 的 `RoundTripComponentsTest`）：反射枚举 {@link SocialData} 的 record
 * 组件，逐组件造差异，三条断言 —— 新增状态组件若忘了进变更集，本测试自动红。
 */
class SocialRoundTripTest {

  private static final HexCoord H00 = new HexCoord(0, 0);

  /** ★ 唯一的豁免集合：M3 的 SocialData 没有"不进变更集"的组件 ⇒ 必须是空集，且被单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  @Test
  void everySocialDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : SocialData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      SocialData base = SocialData.empty();
      SocialData target = mutate(base, name);
      SocialChangeSet cs = SocialChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 变更集整个为空）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(SocialChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET)
        .as("SocialData 没有豁免项；要加名字必须在 diff 里现形")
        .isEmpty();
  }

  @Test
  void changeSetHasExactlyOneComponent() {
    assertThat(SocialChangeSet.class.getRecordComponents()).hasSize(1);
    assertThat(componentNames(SocialChangeSet.class))
        .as("变更集的每个组件都必须在 SocialData 里有同名的 record 组件")
        .isSubsetOf(componentNames(SocialData.class));
    assertThat(componentNames(SocialData.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(SocialChangeSet.class));
  }

  @Test
  void snapshotNamespaceIsSocial() {
    SocialSnapshot snapshot =
        new SocialSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(5),
            SocialData.empty());
    assertThat(snapshot.namespace()).isEqualTo("social");
  }

  private static SocialData mutate(SocialData base, String name) {
    return switch (name) {
      case "populations" -> base.withPopulations(onePopulation());
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(SocialChangeSet cs, String name) {
    return switch (name) {
      case "populations" -> cs.populations().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  private static Map<HexCoord, PopulationSeries> onePopulation() {
    return Map.of(
        H00,
        new PopulationSeries(
            new Segment<>(SimosTimestamp.of(0), 10000L),
            new SegmentedSeries<>(
                List.of(new Segment<>(SimosTimestamp.of(0), 0.02)), List.of(), null),
            List.of()));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./mvnw -q -pl simos-social -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SocialChangeSetTest,SocialRoundTripTest test
```
期望：编译失败（三个类型都不存在）。

- [ ] **Step 3: 实现三个类型**

`SocialData`：

```java
package io.mosire.simos.social;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.population.PopulationSeries;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 社会状态（M3 spec §3.1）：当前只有"每个 hex 一条人口序列"。
 *
 * <p>★ `populations` **保序不可变**：`LinkedHashMap` + `unmodifiableMap`，**绝不用 `Map.copyOf`**
 * ——它的迭代序不是内容的纯函数（M2 实测），字节级往返因此不成立。
 */
public record SocialData(Map<HexCoord, PopulationSeries> populations) {

  public SocialData {
    if (populations == null) {
      throw new IllegalArgumentException("populations 不得为 null");
    }
    Map<HexCoord, PopulationSeries> copy = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, PopulationSeries> entry : populations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("populations 的键与值都不得为 null");
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    populations = Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
  }

  /** 往返用例的起点。 */
  public static SocialData empty() {
    return new SocialData(Map.of());
  }

  /** 一个组件一个 with（照 M2 的 MapSimons 形制）。 */
  public SocialData withPopulations(Map<HexCoord, PopulationSeries> value) {
    return new SocialData(value);
  }
}
```

`SocialSnapshot`：

```java
package io.mosire.simos.social;

import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;

/**
 * social 模块的状态切片（总纲 §4.5）：**独立的 record 树，只实现 {@link Snapshot}**——与 MapSnapshot /
 * UnitSnapshot 之间没有万能父类。
 *
 * <p>★ {@link #namespace()} 固定返回 {@code "social"}：{@code SimulationState} 构造期校验
 * "modules 的键 == snapshot.namespace()"，装配时键名写歪会当场抛。
 */
public record SocialSnapshot(StateRef ref, SimosTimestamp timestamp, SocialData data)
    implements Snapshot {

  public SocialSnapshot {
    if (ref == null) {
      throw new IllegalArgumentException("ref 不得为 null");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp 不得为 null");
    }
    if (data == null) {
      throw new IllegalArgumentException("data 不得为 null");
    }
  }

  @Override
  public String namespace() {
    return "social";
  }
}
```

`SocialChangeSet`（★ **机制全部委托给 util 的 `FieldDelta`，本类不重实现 diff/rebuild**）：

```java
package io.mosire.simos.social.change;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Objects;

/**
 * 社会状态的变更集。**组件与 {@link SocialData} 的 record 组件一一对应**（当前 1 个）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 {@code SocialRoundTripTest} 的**反射枚举**把守——
 * 新增状态组件若不进变更集，那个测试自动红。
 *
 * <p>★ **差异与重建的语义不在这里**：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}
 * （与 {@code MapChangeSet} / {@code UnitChangeSet} 共用同一份，R1 守卫把守"全仓恰一份"）。
 *
 * <p>★ **不实现 util 的 {@code ChangeSet} 接口**（C8）：版本戳属 Revision 层，照 M2 先例。
 */
public record SocialChangeSet(FieldDelta<PopulationSeries> populations) {

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static SocialChangeSet between(SocialData base, SocialData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new SocialChangeSet(FieldDelta.diff(base.populations(), target.populations()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static SocialData apply(SocialChangeSet cs, SocialData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new SocialData(
        FieldDelta.rebuild(base.populations(), cs.populations(), HexCoord::parse));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !populations.changed();
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q -pl simos-social -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SocialChangeSetTest,SocialRoundTripTest test
```
期望：全绿（两类的用例数之和）。

- [ ] **Step 5: 变异自证（G13，至少 3 轮）**

1. **m1**：`SocialChangeSet.between` 改传 `base.populations()` 而不是 `target` 那一份 ⇒ `aSingleChangedEntryIsNotAnEmptyChangeSet` 与往返用例同时红（**红了要问为什么红**：红的理由是"没有真的比较两侧"）；
2. **m2**：`SocialData` 的 `Collections.unmodifiableMap(copy)` 换成 `Map.copyOf(copy)` ⇒ 先看它是否红；★ **按键数当场量判别力**（M2 的教训：`copyOf` 在 2~3 键时可能恰好保序）——若夹具键数太少而不红，**当场把夹具加到 4~6 键并把量到的结果写进报告**（"没红要问为什么没红"）；
3. **m3**：`SocialChangeSet.apply` 的 `rebuild` 传 `cs.populations()` 之外的另一份（或删掉 `Patch` 那条路径）⇒ `addAndRemoveTogetherIsAPatch` 红。
   每轮：干净世界 → 改前绿 → 变异自证字节不同 → 改后红、`COMPILATION ERROR count = 0`、红点落在被保护那行。

- [ ] **Step 6: 提交**

```bash
./mvnw -q spotless:apply
git add simos-social/src/main/java/io/mosire/simos/social/SocialData.java \
        simos-social/src/main/java/io/mosire/simos/social/SocialSnapshot.java \
        simos-social/src/main/java/io/mosire/simos/social/change/SocialChangeSet.java \
        simos-social/src/test/java/io/mosire/simos/social/change/SocialChangeSetTest.java \
        simos-social/src/test/java/io/mosire/simos/social/change/SocialRoundTripTest.java
git diff --cached --stat
git commit -m "feat(social): SocialData/SocialSnapshot/SocialChangeSet + 反射往返框架（M3 Task 4）"
```

---

### Task 5: `SocialResolver` + R12/R13 的 social 半

**Files:**
- Create: `simos-social/src/main/java/io/mosire/simos/social/resolve/SocialResolver.java`
- Test: `simos-social/src/test/java/io/mosire/simos/social/resolve/SocialResolverTest.java`

**Interfaces:**
- Consumes: `SocialData` / `SocialSnapshot`（Task 4）；`io.mosire.simos.util.resolve.{Resolver, ResolveContext}`；`io.mosire.simos.util.identity.{QueryResult, ResolvedSubject, SubjectId}`；`io.mosire.simos.util.address.*`
- Produces: `public final class SocialResolver implements Resolver`（`namespace() == "social"`）

- [ ] **Step 1: 先读模板**

```bash
sed -n '1,180p' simos-map/src/main/java/io/mosire/simos/map/resolve/MapResolver.java
sed -n '30,80p' simos-map/src/test/java/io/mosire/simos/map/resolve/MapResolverTest.java
```
`SocialResolver` **逐条照 `MapResolver` 的形制**：空候选 vs 抛的分工、段 2 必须是 `Entity(∅,·)`、段数 > 3 空候选、
`SubjectId(ns, local)`、canonical **只由 `Address` AST 构造后 `canonical()` 产出**。测试的 `SimulationState` 装配照 `MapResolverTest.setUp()`（不用 Mockito）。

- [ ] **Step 2: 写失败测试**

```java
package io.mosire.simos.social.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * social: 寻址。夹具：两格有人口（经 SocialSnapshot 装进真实 SimulationState）。
 *
 * <p>★ R12：空候选 vs 抛的分工逐条；★ R13：canonical 由 AST 产出（mapId 含 `:` 时引号必须自动正确）。
 */
class SocialResolverTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp TS = SimosTimestamp.of(5);
  private static final HexCoord H00 = new HexCoord(0, 0);

  private final SocialResolver resolver = new SocialResolver();

  private static PopulationSeries population(long people) {
    return new PopulationSeries(
        new Segment<>(SimosTimestamp.of(0), people),
        new SegmentedSeries<>(List.of(new Segment<>(SimosTimestamp.of(0), 0.02)), List.of(), null),
        List.of());
  }

  private static ResolveContext ctx(String namespace, Snapshot snapshot) {
    SimulationState state =
        new SimulationState(
            new StateMeta(REF, TS), Map.of(namespace, snapshot), InMemoryInfoSystem.empty());
    return new ResolveContext(state, TS);
  }

  private static ResolveContext goodCtx() {
    return ctx(
        "social",
        new SocialSnapshot(REF, TS, new SocialData(Map.of(H00, population(18036)))));
  }

  @Test
  void rootOfTheNamespaceResolves() {
    var result = resolver.resolve(Address.parse("social:Map1"), goodCtx());
    assertThat(result.candidates()).hasSize(1);
    assertThat(result.candidates().get(0).id().localId()).isEqualTo("Map1");
    assertThat(result.candidates().get(0).typeName()).isEqualTo("Social");
    assertThat(result.candidates().get(0).canonicalAddress()).isEqualTo("social:Map1");
  }

  @Test
  void hexWithARecordResolvesAndCanonicalises() {
    var result = resolver.resolve(Address.parse("social:Map1:hex.0_0"), goodCtx());
    assertThat(result.candidates()).hasSize(1);
    assertThat(result.candidates().get(0).id().localId()).isEqualTo("0_0");
    assertThat(result.candidates().get(0).typeName()).isEqualTo("HexPopulation");
  }

  @Test
  void humanIndexFormCanonicalisesToTheKindNameForm() {
    var result = resolver.resolve(Address.parse("social:Map1:[0,0]"), goodCtx());
    assertThat(result.candidates()).as("Human 进、canonical 出").hasSize(1);
    assertThat(result.candidates().get(0).canonicalAddress()).isEqualTo("social:Map1:hex.0_0");
  }

  // ── R12：空候选（合法但本模块不服务 / 不存在） ──────────────────────

  @Test
  void absentHexIsAnEmptyCandidateNotAnError() {
    assertThat(resolver.resolve(Address.parse("social:Map1:hex.9_9"), goodCtx()).candidates())
        .isEmpty();
  }

  @Test
  void attributeSegmentsAndOverlongAddressesAreEmptyCandidates() {
    assertThat(resolver.resolve(Address.parse("social:Map1:population"), goodCtx()).candidates())
        .isEmpty();
    assertThat(
            resolver.resolve(Address.parse("social:Map1:hex.0_0:population"), goodCtx()).candidates())
        .isEmpty();
    assertThat(resolver.resolve(Address.parse("social:Map1:city.c1"), goodCtx()).candidates())
        .isEmpty();
  }

  @Test
  void foreignNamespaceIsAnEmptyCandidate() {
    assertThat(resolver.resolve(Address.parse("map:Map1"), goodCtx()).candidates()).isEmpty();
  }

  // ── R12：抛（装配故障） ──────────────────────────────────────────

  @Test
  void missingSliceThrows() {
    ResolveContext noSlice =
        new ResolveContext(
            new SimulationState(new StateMeta(REF, TS), Map.of(), InMemoryInfoSystem.empty()), TS);
    assertThatThrownBy(() -> resolver.resolve(Address.parse("social:Map1"), noSlice))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("social");
  }

  @Test
  void wrongSliceTypeThrows() {
    Snapshot impostor = new Snapshot() {
      @Override public StateRef ref() { return REF; }
      @Override public SimosTimestamp timestamp() { return TS; }
      @Override public String namespace() { return "social"; }
    };
    assertThatThrownBy(() -> resolver.resolve(Address.parse("social:Map1"), ctx("social", impostor)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

★ **已核实（2026-09-17 当场 `git grep` 读源码，不是记忆）**：`public record QueryResult(List<ResolvedSubject> candidates)` ⇒ 访问器 **`candidates()`**（**不是** `subjects()`）；`public record ResolvedSubject(SubjectId id, String canonicalAddress, String typeName)`；`public record SubjectId(String namespace, String localId)` ⇒ 访问器 **`localId()`**（**不是** `localPart()`）。断言惯用法照 `MapResolverTest`：`result.candidates().getFirst().id()` / `.canonicalAddress()` / `.typeName()`，并**别只断字符串**——候选的 `localId` 要能解析回真实存在的那条记录（`MapResolverTest#resolvesHexByAddress` 即此形）。

- [ ] **Step 3: 跑测试确认失败**

```bash
./mvnw -q -pl simos-social -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SocialResolverTest test
```
期望：编译失败（`SocialResolver` 不存在）。

- [ ] **Step 4: 实现**

```java
package io.mosire.simos.social.resolve;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Index;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.Resolver;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Objects;

/**
 * {@code social:} 命名空间的地址解析器（M3 spec §3.5）。认三类地址：
 *
 * <ul>
 *   <li>{@code social:<mapId>} —— 该地图的社会切片根主体（第 2 段是根主体 {@code Entity(∅,·)}）
 *   <li>{@code social:<mapId>:hex.<q>_<r>} —— 该格的人口序列；无记录 ⇒ 空候选
 *   <li>{@code social:<mapId>:[q,r]} —— 上一条的 Human 形式（Index 段恰 2 元），canonical 一律输出 {@code hex.q_r}
 * </ul>
 *
 * <p>**空候选与抛的分工**（与 {@code MapResolver} 同款）：合法但本模块不服务（其它 kind、属性段、段数 &gt; 3、
 * 没有记录的格）一律空候选；**抛只有两处**——装配故障（state 里没有 social 切片 / 切片类型不对）与认领了的
 * kind 里**名字解析失败**（{@link HexCoord#parse} 抛它自己的 IAE，不包不吞）。
 *
 * <p>★ **canonical 只能由 {@link Address} AST 构造后调 {@code canonical()} 产出**：M1 §3.4 的按需加引规则
 * 不在本类重实现。`mapId` **只回显、不校验**（{@code GameMap} 没有 id 字段，M2 遗留挂起项）。
 */
public final class SocialResolver implements Resolver {

  private static final String NAMESPACE = "social";

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    if (!NAMESPACE.equals(address.namespace())) {
      return empty();
    }
    SocialData data = dataOf(ctx);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isPresent()) {
      return empty();
    }
    String mapId = root.name();
    if (segments.size() == 2) {
      return single(new SubjectId(NAMESPACE, mapId), rootAddress(mapId), "Social");
    }
    if (segments.size() > 3) {
      return empty(); // 属性访问（social:m1:hex.0_0:population）M3 不服务
    }
    AddressSegment third = segments.get(2);
    if (third instanceof Index index) {
      if (index.coords().size() != 2) {
        return empty();
      }
      return resolveHex(data, mapId, new HexCoord(index.coords().get(0), index.coords().get(1)));
    }
    if (!(third instanceof Entity entity) || entity.kind().isEmpty()) {
      return empty();
    }
    return switch (entity.kind().get()) {
      case "hex" -> resolveHex(data, mapId, HexCoord.parse(entity.name()));
      default -> empty();
    };
  }

  private static QueryResult resolveHex(SocialData data, String mapId, HexCoord hex) {
    if (!data.populations().containsKey(hex)) {
      return empty(); // 合法但不存在的格：空候选，不是错误
    }
    return single(
        new SubjectId("social.hex", hex.toString()),
        entityAddress(mapId, "hex", hex.toString()),
        "HexPopulation");
  }

  /** 切片只能从 social 模块拿（铁律 3/4：SimulationState 没有跨模块访问器）。 */
  private static SocialData dataOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 social 模块切片——SocialResolver 需要 SocialSnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalArgumentException(
          "social 模块切片不是 SocialSnapshot：" + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  private static Address rootAddress(String mapId) {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of(mapId)));
  }

  private static Address entityAddress(String mapId, String kind, String localId) {
    return new Address(
        List.of(new Namespace(NAMESPACE), Entity.of(mapId), Entity.of(kind, localId)));
  }

  private static QueryResult single(SubjectId id, Address canonicalAddress, String typeName) {
    return new QueryResult(
        List.of(new ResolvedSubject(id, canonicalAddress.canonical(), typeName)));
  }

  private static QueryResult empty() {
    return new QueryResult(List.of());
  }
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
./mvnw -q -pl simos-social -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SocialResolverTest test
```
期望：`Tests run: 8, Failures: 0`。

- [ ] **Step 6: 变异自证（G13，至少 3 轮）**

1. **m1**：`resolveHex` 的 `containsKey` 判断删掉（无条件给候选）⇒ `absentHexIsAnEmptyCandidateNotAnError` 红；
2. **m2**：把 `single(...)` 的 canonical 换成手写字符串拼接（如 `"social:" + mapId + ":hex." + hex`）⇒ **R13 的判别力在此**：用一个 mapId 含 `:` 的地址（如 `social:"m:1":hex.0_0`）验证手写拼接会丢引号 ⇒ canonical 断言红。★ 若现有用例抓不到，**当场补一条含 `:` 的 mapId 用例**（R13 的靶子必须是"会分叉的输入"）；
3. **m3**：`dataOf` 的 `orElseThrow` 改成返回空切片（或删掉类型判断）⇒ `missingSliceThrows` / `wrongSliceTypeThrows` 红。
   每轮：干净世界 → 改前绿 → 字节不同 → 改后红、`COMPILATION ERROR count = 0`。

- [ ] **Step 7: 提交**

```bash
./mvnw -q spotless:apply
git add simos-social/src/main/java/io/mosire/simos/social/resolve/SocialResolver.java \
        simos-social/src/test/java/io/mosire/simos/social/resolve/SocialResolverTest.java
git diff --cached --stat
git commit -m "feat(social): SocialResolver 的 social: 寻址 + 空候选/抛分工守卫（M3 Task 5）"
```

---

### Task 6: `UnitId` / `Unit` / `Route` / `Movement` / `UnitState` / `UnitSnapshot` + `effectivePosition` + R5/R6/R7

**Files:**
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/UnitId.java`
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/Route.java`
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/Movement.java`
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/Unit.java`
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java`
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/UnitSnapshot.java`
- Test: `.../unit/UnitIdTest.java`、`.../unit/UnitTest.java`、`.../unit/UnitStateTest.java`

**Interfaces:**
- Consumes: `io.mosire.simos.util.time.{Segment, SegmentedSeries, SimosTimestamp}`；`io.mosire.simos.map.hex.HexCoord`
- Produces:
  - `record UnitId(String value)`：`toString()` = 裸值、`static UnitId parse(String)`、空白即抛
  - `record Route(List<HexCoord> waypoints, List<HexCoord> path)`（校验见下）
  - `record Movement(Route route, SimosTimestamp departedAt, int speedAtDeparture, int mobilityAtDeparture)`
  - `record Unit(UnitId id, String name, SegmentedSeries<Optional<UnitId>> parent, SegmentedSeries<Optional<HexCoord>> position, int member, Map<String,Integer> equipment, int speed, int mobilityPerMille, Optional<Movement> movement)`
  - `record UnitState(Map<UnitId, Unit> units)` + `static UnitState empty()` + `withUnits` + `Optional<HexCoord> effectivePosition(UnitId, SimosTimestamp)`
  - `record UnitSnapshot(StateRef ref, SimosTimestamp timestamp, UnitState state) implements Snapshot`（`namespace() == "unit"`）

★ **`Route` / `Movement` 放在 `io.mosire.simos.unit` 根包、与 `Unit` 同包**：它们是 `Unit` 的组件
（`Unit.movement()`），放 `...unit.move` 会让 `unit` ↔ `unit.move` 两个包互相 import。算法类
（`MovementCost` / `PathFinder` / `UnitMoves` / `MovementState` / `MovementStatus`）才住 `...unit.move`。

- [ ] **Step 1: 写失败测试（先写有判别力的那几条）**

```java
package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** UnitId 的三件套：裸值 toString、static parse、空白即抛（形制照 RegionId/CityId）。 */
class UnitIdTest {

  @Test
  void toStringIsTheBareValueAndParseRoundTrips() {
    assertThat(new UnitId("u-f82a").toString()).isEqualTo("u-f82a");
    assertThat(UnitId.parse("u-f82a")).isEqualTo(new UnitId("u-f82a"));
  }

  @Test
  void blankValuesAreRejected() {
    assertThatThrownBy(() -> new UnitId(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value");
    assertThatThrownBy(() -> new UnitId("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> UnitId.parse(" ")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

`UnitTest`（R5：两条序列不许带事件；自环；数值下限；装备冻结）：

```java
package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit 的构造期守卫（R5：parent/position 不得带事件）+ 自环 + 数值下限。 */
class UnitTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord H11 = new HexCoord(1, 1);

  static SegmentedSeries<Optional<UnitId>> noParent() {
    return new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.empty())), List.of(), null);
  }

  static SegmentedSeries<Optional<HexCoord>> positionAt(HexCoord hex) {
    return new SegmentedSeries<>(
        List.of(new Segment<>(T0, Optional.ofNullable(hex))), List.of(), null);
  }

  static Unit unit(UnitId id) {
    return new Unit(
        id, "第一连", noParent(), positionAt(H11), 100, Map.of("步枪", 50), 2, 1000,
        Optional.empty());
  }

  @Test
  void aWellFormedUnitIsConstructed() {
    Unit u = unit(new UnitId("u-1"));
    assertThat(u.parent().valueAt(T0)).isEmpty();
    assertThat(u.position().valueAt(T0)).contains(H11);
    assertThat(u.mobilityPerMille()).isEqualTo(1000);
  }

  // ── R5 ──────────────────────────────────────────────────────────

  @Test
  void parentAndPositionRejectEvents() {
    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"),
                    "第一连",
                    new SegmentedSeries<>(
                        List.of(new Segment<>(T0, Optional.<UnitId>empty())),
                        List.of(new Event<>(T0, Optional.<UnitId>empty(), EventMode.SET)),
                        null),
                    positionAt(H11),
                    100,
                    Map.of(),
                    2,
                    1000,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parent");

    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"),
                    "第一连",
                    noParent(),
                    new SegmentedSeries<>(
                        List.of(new Segment<>(T0, Optional.<HexCoord>empty())),
                        List.of(new Event<>(T0, Optional.of(H11), EventMode.SET)),
                        null),
                    100,
                    Map.of(),
                    2,
                    1000,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("position");
  }

  @Test
  void parentPointingToItselfThrows() {
    UnitId id = new UnitId("u-1");
    assertThatThrownBy(
            () ->
                new Unit(
                    id,
                    "第一连",
                    new SegmentedSeries<>(
                        List.of(new Segment<>(T0, Optional.of(id))), List.of(), null),
                    positionAt(H11),
                    100,
                    Map.of(),
                    2,
                    1000,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parent");
  }

  @Test
  void numericBudgetsAreEnforced() {
    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"), "第一连", noParent(), positionAt(H11), -1, Map.of(), 2,
                    1000, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"), "第一连", noParent(), positionAt(H11), 0, Map.of(), 0,
                    1000, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"), "第一连", noParent(), positionAt(H11), 0, Map.of(), 2, 0,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void equipmentIsFrozenAndValidated() {
    Map<String, Integer> mutable = new LinkedHashMap<>();
    mutable.put("步枪", 50);
    Unit u =
        new Unit(
            new UnitId("u-1"), "第一连", noParent(), positionAt(H11), 100, mutable, 2, 1000,
            Optional.empty());
    mutable.put("炮", 1);
    assertThat(u.equipment()).containsOnlyKeys("步枪");

    Map<String, Integer> negative = new LinkedHashMap<>();
    negative.put("炮弹", -1);
    assertThatThrownBy(
            () ->
                new Unit(
                    new UnitId("u-1"), "第一连", noParent(), positionAt(H11), 100, negative, 2,
                    1000, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

`UnitStateTest`（R6 跨单位环 + 合法改编不误报；R7 位置继承）：

```java
package io.mosire.simos.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 编制树不变量（R6）+ 位置继承（R7）。 */
class UnitStateTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T10 = SimosTimestamp.of(10);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H22 = new HexCoord(2, 2);

  private static Unit unit(
      String id,
      Optional<String> parent,
      Optional<HexCoord> position,
      SimosTimestamp parentAt) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(parentAt, parent.map(UnitId::new))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of(),
        2,
        1000,
        Optional.empty());
  }

  // ── R6 ──────────────────────────────────────────────────────────

  @Test
  void cycleAcrossUnitsThrowsAtConstruction() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("a"), unit("a", Optional.of("b"), Optional.of(H11), T0));
    units.put(new UnitId("b"), unit("b", Optional.of("a"), Optional.of(H22), T0));

    assertThatThrownBy(() -> new UnitState(units))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("成环");
  }

  @Test
  void legalReparentAcrossTimeIsNotACycle() {
    // t<10：a 无父、b 的父是 a；t>=10：a 的父是 b。任一时刻都是链，**不是环**。
    Unit a =
        new Unit(
            new UnitId("a"),
            "单位 a",
            new SegmentedSeries<>(
                List.of(
                    new Segment<>(T0, Optional.<UnitId>empty()),
                    new Segment<>(T10, Optional.of(new UnitId("b")))),
                List.of(),
                null),
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            100,
            Map.of(),
            2,
            1000,
            Optional.empty());
    Unit b = unit("b", Optional.of("a"), Optional.of(H22), T0);

    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("a"), a);
    units.put(new UnitId("b"), b);
    UnitState state = new UnitState(units); // 不得抛
    assertThat(state.units()).hasSize(2);
  }

  // ── R7 ──────────────────────────────────────────────────────────

  @Test
  void effectivePositionPrefersOwnThenWalksToParent() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child = unit("c", Optional.of("p"), Optional.empty(), T0);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("p"), parent);
    units.put(new UnitId("c"), child);
    UnitState state = new UnitState(units);

    assertThat(state.effectivePosition(new UnitId("p"), T0)).contains(H22);
    assertThat(state.effectivePosition(new UnitId("c"), T0))
        .as("自身无位置 ⇒ 向父取")
        .contains(H22);
  }

  @Test
  void ownPositionWinsOverParent() {
    Unit parent = unit("p", Optional.empty(), Optional.of(H22), T0);
    Unit child = unit("c", Optional.of("p"), Optional.of(H11), T0);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("p"), parent);
    units.put(new UnitId("c"), child);
    UnitState state = new UnitState(units);

    assertThat(state.effectivePosition(new UnitId("c"), T0)).contains(H11);
  }

  @Test
  void noPositionAnywhereIsEmpty() {
    UnitState state = new UnitState(Map.of(new UnitId("c"), unit("c", Optional.empty(), Optional.empty(), T0)));
    assertThat(state.effectivePosition(new UnitId("c"), T0)).isEmpty();
    assertThat(state.effectivePosition(new UnitId("nobody"), T0)).as("查不存在的主体 ⇒ 空").isEmpty();
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitIdTest,UnitTest,UnitStateTest test
```
期望：编译失败。

- [ ] **Step 3: 实现**

`UnitId`（照 `RegionId` 逐字同形）与 `Route`：

```java
package io.mosire.simos.unit;

import java.util.List;
import java.util.Objects;

/** 单位 ID：与 {@code RegionId}/{@code CityId} 同形（裸值 toString + static parse + 空白即抛）。 */
public record UnitId(String value) {

  public UnitId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("value 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层，M3 spec §8.6）。 */
  public static UnitId parse(String text) {
    return new UnitId(text);
  }
}
```

```java
package io.mosire.simos.unit;

/**
 * 路线（M3 spec §4.5）：路径点 + 展开后的格序列。**两者的首尾必须一致**，且 `path` 是逐格连续的简单路径。
 */
public record Route(List<HexCoord> waypoints, List<HexCoord> path) {

  public Route {
    if (waypoints == null || path == null) {
      throw new IllegalArgumentException("waypoints 与 path 都不得为 null");
    }
    waypoints = List.copyOf(waypoints);
    path = List.copyOf(path);
    if (waypoints.size() < 2) {
      throw new IllegalArgumentException("waypoints 至少两个（总纲：多个路径点，最低两个）");
    }
    if (path.size() < 2) {
      throw new IllegalArgumentException("path 至少两格");
    }
    if (!path.get(0).equals(waypoints.get(0))
        || !path.get(path.size() - 1).equals(waypoints.get(waypoints.size() - 1))) {
      throw new IllegalArgumentException("path 的首尾必须等于 waypoints 的首尾");
    }
    int index = 0;
    for (HexCoord hex : path) {
      if (index < waypoints.size() && waypoints.get(index).equals(hex)) {
        index++;
      }
    }
    if (index != waypoints.size()) {
      throw new IllegalArgumentException("waypoints 必须是 path 的子序列");
    }
    for (int i = 1; i < path.size(); i++) {
      if (path.get(i - 1).distanceTo(path.get(i)) != 1) {
        throw new IllegalArgumentException("path 相邻格必须相邻：" + path.get(i - 1) + " → " + path.get(i));
      }
    }
    if (new java.util.LinkedHashSet<>(path).size() != path.size()) {
      throw new IllegalArgumentException("path 不得有重复格（A* 产物天然是简单路径）");
    }
  }
}
```
（`import io.mosire.simos.map.hex.HexCoord;` 与 `import java.util.LinkedHashSet;` 提到 import 段，别写在表达式里。）

`Movement`：

```java
package io.mosire.simos.unit;

import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/**
 * 在途行程（M3 spec §4.5）。
 *
 * <p>★ **出发时刻的速度与机动性在此冻结**：在途行程不得因参数变更而"时间反演"（速度翻倍让昨天已走的
 * 路程突然变长）。地图变化**仍实时生效**——那正是 {@code MovementStatus.NEED_REPLAN} 的来源。
 */
public record Movement(
    Route route, SimosTimestamp departedAt, int speedAtDeparture, int mobilityAtDeparture) {

  public Movement {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(departedAt, "departedAt");
    if (speedAtDeparture < 1) {
      throw new IllegalArgumentException("speedAtDeparture 必须 ≥ 1: " + speedAtDeparture);
    }
    if (mobilityAtDeparture < 1) {
      throw new IllegalArgumentException("mobilityAtDeparture 必须 ≥ 1: " + mobilityAtDeparture);
    }
  }
}
```

`Unit`：

```java
package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.TemporalSeries;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 单位（M3 spec §4.1）：严格树的一个节点。`parent` 与 `position` 是**时态序列**（军队会改编、会调动），
 * 其余是普通值（C3；历史由 M4 的 revision 日志承载）。
 *
 * <p>★ `parent` 指向**自身 id** 在构造期就抛（便宜）；**跨单位的环**由 {@link UnitState} 构造期查
 * ——两者分工见 spec §4.2。
 *
 * <p>★ `position` 允许为空（"不知道在哪"），无则向父取（{@link UnitState#effectivePosition}）。
 */
public record Unit(
    UnitId id,
    String name,
    SegmentedSeries<Optional<UnitId>> parent,
    SegmentedSeries<Optional<HexCoord>> position,
    int member,
    Map<String, Integer> equipment,
    int speed,
    int mobilityPerMille,
    Optional<Movement> movement) {

  public Unit {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    requireNoEvents(parent, "parent");
    requireNoEvents(position, "position");
    for (Segment<Optional<UnitId>> segment : parent.segments()) {
      if (segment.value().filter(id::equals).isPresent()) {
        throw new IllegalArgumentException("parent 不得指向自身: " + id);
      }
    }
    if (member < 0) {
      throw new IllegalArgumentException("member 必须 ≥ 0: " + member);
    }
    equipment = frozenEquipment(equipment);
    if (speed < 1) {
      throw new IllegalArgumentException("speed 必须 ≥ 1: " + speed);
    }
    if (mobilityPerMille < 1) {
      throw new IllegalArgumentException("mobilityPerMille 必须 ≥ 1: " + mobilityPerMille);
    }
    if (movement == null) {
      throw new IllegalArgumentException("movement 不得为 null（无在途路线用 Optional.empty()）");
    }
  }

  /** ★ 两条时态序列的变化一律用"追加段"表达：`ADD` 对 `Optional` 无定义，`SET` 与段重复（spec §4.1 第 3 条）。 */
  private static void requireNoEvents(TemporalSeries<?> series, String field) {
    if (series == null) {
      throw new IllegalArgumentException(field + " 不得为 null");
    }
    if (!series.events().isEmpty()) {
      throw new IllegalArgumentException(field + " 不得带事件：变化一律用追加段表达（spec §4.1）");
    }
  }

  private static Map<String, Integer> frozenEquipment(Map<String, Integer> equipment) {
    if (equipment == null) {
      throw new IllegalArgumentException("equipment 不得为 null");
    }
    Map<String, Integer> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : equipment.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("equipment 的键不得空白");
      }
      if (entry.getValue() == null || entry.getValue() < 0) {
        throw new IllegalArgumentException("equipment 的值必须 ≥ 0: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }
}
```

`UnitState`：

```java
package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 单位状态（M3 spec §4.1/§4.2）：一张 `id → Unit` 的表 + **构造期的编制树不变量**。
 *
 * <p>★ **无环校验按"关键时点"逐点查**，**不得**把所有边合并成一张图查——`A→B`（t1）与 `B→A`（t2）
 * 各自合法（改编是允许的），合并图会把它们误报成环。关键时点 = 所有 `parent` 段 `from` 的集合，
 * 父图只在段边界变化，故查遍关键时点即覆盖全时间轴（含 anchor 之前的恒定延拓）。
 *
 * <p>★ `units` **保序不可变**（`LinkedHashMap` + `unmodifiableMap`，**绝不用 `Map.copyOf`**）。
 */
public record UnitState(Map<UnitId, Unit> units) {

  public UnitState {
    if (units == null) {
      throw new IllegalArgumentException("units 不得为 null");
    }
    Map<UnitId, Unit> copy = new LinkedHashMap<>();
    for (Map.Entry<UnitId, Unit> entry : units.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("units 的键与值都不得为 null");
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    units = Collections.unmodifiableMap(copy);
    requireNoCycleAtKeyTimes(units);
  }

  public static UnitState empty() {
    return new UnitState(Map.of());
  }

  public UnitState withUnits(Map<UnitId, Unit> value) {
    return new UnitState(value);
  }

  /**
   * 有效位置（M3 spec §4.2）：自身有位置 ⇒ 它；否则向父取，递归；无父或查无此人 ⇒ 空。
   *
   * <p>★ 正常路径下构造期已拒环；这里撞环抛 {@link IllegalStateException} 是因为**手工拼出的状态**
   * 仍可能绕过（它属数据故障，不是"没有候选"）。
   */
  public Optional<HexCoord> effectivePosition(UnitId id, SimosTimestamp at) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(at, "at");
    Unit unit = units.get(id);
    Set<UnitId> seen = new LinkedHashSet<>();
    while (unit != null) {
      if (!seen.add(unit.id())) {
        throw new IllegalStateException("编制链成环（UnitState 构造期本应拒绝）: " + unit.id());
      }
      Optional<HexCoord> here = unit.position().valueAt(at);
      if (here.isPresent()) {
        return here;
      }
      // 查无此父（手工拼的状态）⇒ 链到此为止
      unit = unit.parent().valueAt(at).map(units::get).orElse(null);
    }
    return Optional.empty();
  }

  private static void requireNoCycleAtKeyTimes(Map<UnitId, Unit> units) {
    Set<SimosTimestamp> keyTimes = new LinkedHashSet<>();
    for (Unit unit : units.values()) {
      for (Segment<Optional<UnitId>> segment : unit.parent().segments()) {
        keyTimes.add(segment.from());
      }
    }
    for (SimosTimestamp at : keyTimes) {
      Map<UnitId, UnitId> parentOf = new LinkedHashMap<>();
      for (Unit unit : units.values()) {
        unit.parent().valueAt(at).ifPresent(parent -> parentOf.put(unit.id(), parent));
      }
      for (UnitId start : parentOf.keySet()) {
        Set<UnitId> seen = new LinkedHashSet<>();
        UnitId current = start;
        while (current != null && parentOf.containsKey(current)) {
          if (!seen.add(current)) {
            throw new IllegalArgumentException("编制树在 " + at + " 成环，环上含 " + current);
          }
          current = parentOf.get(current);
        }
      }
    }
  }
}
```

`UnitSnapshot`：形制逐字照 `SocialSnapshot`（三组件、null 抛、`namespace()` 返回 `"unit"`），此处不重复贴。

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitIdTest,UnitTest,UnitStateTest test
```
期望：全绿。

- [ ] **Step 5: 变异自证（G13，至少 4 轮——R5/R6/R7 各至少一条）**

1. **m1**：删掉 `requireNoEvents` 的 `events().isEmpty()` 判断 ⇒ `parentAndPositionRejectEvents` 红；
2. **m2**：`requireNoCycleAtKeyTimes` 换成"合并所有边查环"（或直接改成 `return`）⇒ 前者红（`cycleAcrossUnitsThrowsAtConstruction`）；**同时必须验证 `legalReparentAcrossTimeIsNotACycle` 在合并图实现下会红**——这正是"为什么不得用合并图"的判别力证据，写进报告；
3. **m3**：`effectivePosition` 里"自身优先"改成"先问父"⇒ `ownPositionWinsOverParent` 红；
4. **m4**：`parent` 的**自环**校验删掉 ⇒ `parentPointingToItselfThrows` 红。
   每轮：干净世界 → 改前绿 → 变异自证字节不同 → 改后红、`COMPILATION ERROR count = 0`、红点落在被保护那行。

- [ ] **Step 6: 提交**

```bash
./mvnw -q spotless:apply
git add simos-unit/src/main/java/io/mosire/simos/unit/UnitId.java \
        simos-unit/src/main/java/io/mosire/simos/unit/Route.java \
        simos-unit/src/main/java/io/mosire/simos/unit/Movement.java \
        simos-unit/src/main/java/io/mosire/simos/unit/Unit.java \
        simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java \
        simos-unit/src/main/java/io/mosire/simos/unit/UnitSnapshot.java \
        simos-unit/src/test/java/io/mosire/simos/unit/UnitIdTest.java \
        simos-unit/src/test/java/io/mosire/simos/unit/UnitTest.java \
        simos-unit/src/test/java/io/mosire/simos/unit/UnitStateTest.java
git diff --cached --stat
git commit -m "feat(unit): UnitId/Route/Movement/Unit/UnitState 编制树与位置继承（M3 Task 6）"
```

---

### Task 7: `UnitChangeSet` + 往返框架

**Files:**
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/change/UnitChangeSet.java`
- Test: `.../unit/change/UnitChangeSetTest.java`、`.../unit/change/UnitRoundTripTest.java`

**Interfaces:**
- Consumes: `FieldDelta.diff` / `FieldDelta.rebuild`（Task 1）；`UnitState` / `Unit` / `UnitId`（Task 6）
- Produces: `record UnitChangeSet(FieldDelta<Unit> units)` + `between` / `apply` / `isEmpty`（key = `UnitId.toString()` / `UnitId.parse`）

- [ ] **Step 1: 写失败测试**

两个测试类**照 Task 4 的 `SocialChangeSetTest` / `SocialRoundTripTest` 的同名方法逐条平移**（键类型换成 `UnitId`、值类型换成 `Unit`、`empty()` 换成 `UnitState.empty()`）。这里给出**必须存在的用例清单**与两个类的**特有**部分：

```java
// UnitChangeSetTest 的方法清单（照 SocialChangeSetTest 逐条平移）：
//   unchangedStateGivesAnEmptyChangeSet
//   aSingleChangedUnitIsNotAnEmptyChangeSet
//   removalWithoutUpsertIsRemoveAndSurvivesApply
//   addAndRemoveTogetherIsAPatch
//   applyOfUnchangedKeepsTheBaseMapIdentical
//   unitStateIsFrozenAndRejectsNulls
// 夹具：两个 Unit（u-1 / u-2），只在 member 上不同的 target。
// ★ 注意：**变更集不校验编制树不变量**——它只做"逐组件 diff + 重建"；
//    构造一个真实 UnitState 时仍受 Task 6 的守卫约束（夹具因此必须是合法的树）。
```

```java
// UnitRoundTripTest 的特有部分（其余照 SocialRoundTripTest）：
  /** ★ 唯一的豁免集合：UnitState 没有"不进变更集"的组件 ⇒ 空集，且被单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  @Test
  void changeSetHasExactlyOneComponent() {
    assertThat(UnitChangeSet.class.getRecordComponents()).hasSize(1);
    assertThat(componentNames(UnitChangeSet.class)).isSubsetOf(componentNames(UnitState.class));
    assertThat(componentNames(UnitState.class)).isSubsetOf(componentNames(UnitChangeSet.class));
  }

  @Test
  void snapshotNamespaceIsUnit() { /* new UnitSnapshot(REF, TS, UnitState.empty()).namespace() == "unit" */ }

  private static UnitState mutate(UnitState base, String name) {
    return switch (name) {
      case "units" -> base.withUnits(oneUnit());
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(UnitChangeSet cs, String name) {
    return switch (name) {
      case "units" -> cs.units().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitChangeSetTest,UnitRoundTripTest test
```
期望：编译失败（`UnitChangeSet` 不存在）。

- [ ] **Step 3: 实现**

```java
package io.mosire.simos.unit.change;

import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Objects;

/**
 * 单位状态的变更集。**组件与 {@link UnitState} 的 record 组件一一对应**（当前 1 个）。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 {@code UnitRoundTripTest} 的反射枚举把守。
 *
 * <p>★ 差异与重建一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 map/social 共用
 * 同一份机制，R1 守卫把守）。
 *
 * <p>★ **不实现 util 的 {@code ChangeSet} 接口**（C8）。
 */
public record UnitChangeSet(FieldDelta<Unit> units) {

  public static UnitChangeSet between(UnitState base, UnitState target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new UnitChangeSet(FieldDelta.diff(base.units(), target.units()));
  }

  public static UnitState apply(UnitChangeSet cs, UnitState base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new UnitState(FieldDelta.rebuild(base.units(), cs.units(), UnitId::parse));
  }

  public boolean isEmpty() {
    return !units.changed();
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitChangeSetTest,UnitRoundTripTest test
```
期望：全绿。

- [ ] **Step 5: 变异自证（G13，2 轮）**

1. **m1**：`between` 两侧对调（`diff(target, base)`）⇒ 单组件变化那条与往返用例同时红（红点理由 = 比较方向反了）；
2. **m2**：往返循环里 `changedOf` 的 `default -> throw` 改成 `default -> true`（温和兜底）⇒ 要求**当场造一个"新增组件"的形态**验证它会漏（若造不出来，就在报告里如实写"未验证"，**不许**写成已验证）。

- [ ] **Step 6: 提交**

```bash
./mvnw -q spotless:apply
git add simos-unit/src/main/java/io/mosire/simos/unit/change/UnitChangeSet.java \
        simos-unit/src/test/java/io/mosire/simos/unit/change/UnitChangeSetTest.java \
        simos-unit/src/test/java/io/mosire/simos/unit/change/UnitRoundTripTest.java
git diff --cached --stat
git commit -m "feat(unit): UnitChangeSet + 反射往返框架（M3 Task 7）"
```

---

### Task 8: `MovementCost` + `TerrainMovementCost` + 判据二夹具

**Files:**
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/move/MovementCost.java`
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/move/TerrainMovementCost.java`
- Test: `simos-unit/src/test/java/io/mosire/simos/unit/move/MoveFixture.java`（**测试夹具**，判据二的地图与单位）
- Test: `simos-unit/src/test/java/io/mosire/simos/unit/move/TerrainMovementCostTest.java`

**Interfaces:**
- Consumes: `TerrainType.IMPASSABLE_MOVE_COST`（Task 2）；`Unit`（Task 6）；`GameMap` / `HexCell` / `TerrainType`（M2）
- Produces:
  - `interface MovementCost { OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map); long minStepCostMillis(Unit unit, GameMap map); }`
  - `final class TerrainMovementCost implements MovementCost`，`public static final TerrainMovementCost INSTANCE`
  - `MoveFixture`（测试）：判据二的图 `[1,1]/[1,2]/[1,3]`、地形 25/65/999、单位 `u-f82a`（speed 2、mobility ‰500）

- [ ] **Step 1: 写夹具与失败测试**

`MoveFixture`：

```java
package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★ **判据二的冻结夹具**（M3 spec §4.5）：三格直线走廊，两条夹具地形（25 / 65）+ 一条不可通行（999）。
 *
 * <p>地形**不在** {@code TerrainCatalog} 里——夹具自建 `TerrainType`（spec §4.3 末段）：
 * `GameMap.terrainTypes` 本就是任意词表，判据因此与地形词表解耦。
 *
 * <p>★ `TerrainType` 的形参序以**源码**为准（key, name, color, minHeight, maxHeight, food, gold,
 * stone, moveCost, description）。
 */
final class MoveFixture {

  static final SimosTimestamp T0 = SimosTimestamp.of(0);
  static final HexCoord H11 = new HexCoord(1, 1);
  static final HexCoord H12 = new HexCoord(1, 2);
  static final HexCoord H13 = new HexCoord(1, 3);
  static final UnitId U_F82A = new UnitId("u-f82a");

  static final TerrainType FLAT_25 = terrain("flat25", 25);
  static final TerrainType STEEP_65 = terrain("steep65", 65);
  static final TerrainType IMPASSABLE_999 = terrain("impassable999", 999);

  private MoveFixture() {}

  private static TerrainType terrain(String key, int moveCost) {
    return new TerrainType(key, key, "#336699", 0.0, 1.0, 0, 0, 0, moveCost, "夹具地形");
  }

  /** `[1,3]` 的地形可换成 {@code IMPASSABLE_999}（"中途变不可通行"那条用例）。 */
  static GameMap map(TerrainType hex13Terrain) {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(FLAT_25.key(), 0.5));
    hexes.put(H12, new HexCell(FLAT_25.key(), 0.5));
    hexes.put(H13, new HexCell(hex13Terrain.key(), 0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(FLAT_25.key(), FLAT_25);
    terrainTypes.put(STEEP_65.key(), STEEP_65);
    terrainTypes.put(IMPASSABLE_999.key(), IMPASSABLE_999);
    return new GameMap(
        hexes, Map.of(), Map.of(), terrainTypes, Map.of(), Map.of(), Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 判据二的单位：speed = 2 MP/小时、mobility ‰500、位置 `[1,1]`、无在途路线。 */
  static Unit unit() {
    return new Unit(
        U_F82A,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty());
  }
}
```

`TerrainMovementCostTest`：

```java
package io.mosire.simos.unit.move;

import static io.mosire.simos.unit.move.MoveFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import org.junit.jupiter.api.Test;

/** 毫 MP 成本：判据二的两条成本数（12500 / 32500）+ 不可通行 + 越界 + 调用方 bug。 */
class TerrainMovementCostTest {

  private final MovementCost cost = TerrainMovementCost.INSTANCE;

  @Test
  void stepCostsMatchTheFrozenFixture() {
    GameMap map = map(STEEP_65);
    assertThat(cost.costMillis(H11, H12, unit(), map))
        .as("25 × 1000 = 25000 毫，按 ‰500 缩放 ⇒ floor((25000×500+500)/1000) = 12500")
        .hasValue(12500L);
    assertThat(cost.costMillis(H12, H13, unit(), map))
        .as("65 × 1000 = 65000 毫，按 ‰500 缩放 ⇒ 32500")
        .hasValue(32500L);
  }

  @Test
  void impassableTerrainHasNoCost() {
    assertThat(cost.costMillis(H12, H13, unit(), map(IMPASSABLE_999))).isEmpty();
  }

  @Test
  void hexOutsideTheMapHasNoCost() {
    assertThat(cost.costMillis(H11, new HexCoord(9, 9), unit(), map(STEEP_65))).isEmpty();
  }

  @Test
  void nonAdjacentStepsAreACallerBug() {
    assertThatThrownBy(() -> cost.costMillis(H11, H13, unit(), map(STEEP_65)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cost.costMillis(H11, H11, unit(), map(STEEP_65)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownTerrainKeyThrows() {
    GameMap map = map(STEEP_65);
    GameMap broken =
        new GameMap(
            map.hexes(), map.regions(), map.cities(), java.util.Map.of(), map.pathways(),
            map.pathwayGroups(), map.edges(), map.spec());
    assertThatThrownBy(() -> cost.costMillis(H11, H12, unit(), broken))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void minStepCostIsTheCheapestTraversableTerrainScaled() {
    assertThat(cost.minStepCostMillis(unit(), map(STEEP_65)))
        .as("最便宜的可通行地形是 25 ⇒ 12500；999 那条不算")
        .isEqualTo(12500L);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TerrainMovementCostTest test
```
期望：编译失败（`MovementCost` / `TerrainMovementCost` 不存在）。

- [ ] **Step 3: 实现**

```java
package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import java.util.OptionalLong;

/**
 * 移动成本的**唯一抽象**（M3 spec §4.3）：从 `from` 踏入 `to` 要付多少毫 MP。
 *
 * <p>★ **格式/河流修正的扩展点就是本接口**：v1 只有 {@code TerrainMovementCost}。成本公式的顺序
 * （地形 → 机动性 → 道路 → 河流）已在 spec C6 冻结，**v1 的后两步是恒等，不为恒等写空操作代码**。
 *
 * <p>★ {@link #minStepCostMillis} 是 A\* 启发函数的下界来源 —— **启发与成本出自同一实现**（总纲的关键约束）：
 * 任一单步成本 ≥ 下界 ⇒ 启发可采纳且一致，首次弹出即最优。
 */
public interface MovementCost {

  /** 从 {@code from} 踏入 {@code to} 的毫 MP 成本；不可通行（或 `to` 不在图上）⇒ 空。 */
  OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map);

  /** 本实现给出的**单步成本下界**（毫 MP）。无法给出下界时返回 0（退化为 Dijkstra，仍正确）。 */
  long minStepCostMillis(Unit unit, GameMap map);
}
```

```java
package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.Unit;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * v1 的成本实现（M3 spec §4.3）：只吃地形与单位机动性。**无状态**（`INSTANCE` 单例，没有可变的局面）。
 *
 * <p>公式（顺序冻结，见 C6）：`scale(地形(to).moveCost × 1000, 单位的 mobilityPerMille)`，
 * 其中 `scale(v, ‰) = Math.floorDiv(v × ‰ + 500, 1000)`（即 `floor(x + 0.5)`，U4 的定点舍入）。
 */
public final class TerrainMovementCost implements MovementCost {

  public static final TerrainMovementCost INSTANCE = new TerrainMovementCost();

  private TerrainMovementCost() {}

  @Override
  public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(map, "map");
    if (!map.hexes().containsKey(to)) {
      return OptionalLong.empty();
    }
    if (from.equals(to) || from.distanceTo(to) != 1) {
      throw new IllegalArgumentException(
          "相邻性是调用方的前提（不是\"没有候选\"）: " + from + " → " + to);
    }
    return costOf(terrainOf(map, map.hexes().get(to)), unit.mobilityPerMille());
  }

  @Override
  public long minStepCostMillis(Unit unit, GameMap map) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(map, "map");
    long best = Long.MAX_VALUE;
    for (HexCell cell : map.hexes().values()) {
      long scaled = scale(terrainOf(map, cell).moveCost() * 1000L, unit.mobilityPerMille());
      if (terrainOf(map, cell).moveCost() < TerrainType.IMPASSABLE_MOVE_COST) {
        best = Math.min(best, scaled);
      }
    }
    return best == Long.MAX_VALUE ? 0L : best; // 无任何可通行格 ⇒ 0 仍是合法下界
  }
```
★ **取代说明（计划期）**：此处的"取最小"按 **`moveCost`（未缩放的值）**判可通行、再缩放比较——实现时**一次遍历里同时判断与取最小**（上面草图为了两件事各自可读写成了两次查表，实现时合并成一次，别把 `terrainOf(cell)` 调两遍）。

```java
  /** `moveCost >= IMPASSABLE_MOVE_COST` ⇒ 空（**单一来源**：unit 侧不复制第二份哨兵，C5）。 */
  private static OptionalLong costOf(TerrainType type, int mobilityPerMille) {
    if (type.moveCost() >= TerrainType.IMPASSABLE_MOVE_COST) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(scale(type.moveCost() * 1000L, mobilityPerMille));
  }

  /** 缺 key ⇒ 抛（与 `TerrainCatalog.of` 的"不兜底"同口径）。 */
  private static TerrainType terrainOf(GameMap map, HexCell cell) {
    TerrainType type = map.terrainTypes().get(cell.terrain());
    if (type == null) {
      throw new IllegalArgumentException("地形 key 不在词表里: " + cell.terrain());
    }
    return type;
  }

  /** `floor(v × ‰ / 1000 + 0.5)`（U4）。`v ≥ 0`、`‰ ≥ 1` ⇒ 不会溢出（`moveCost` 最大 998）。 */
  static long scale(long millis, int perMille) {
    return Math.floorDiv(millis * perMille + 500, 1000);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TerrainMovementCostTest test
```
期望：`Tests run: 6, Failures: 0`。

- [ ] **Step 5: 变异自证（G13，至少 3 轮）**

1. **m1**：`scale` 改成 `Math.round(v * ‰ / 1000.0)` ⇒ **先看它红不红**；若夹具刚好等值（12500/32500 是整千，可能一样），**当场换一组会分叉的夹具**（如 `moveCost = 7`、`‰ = 333` ⇒ `floorDiv(7000×333+500, 1000) = 2331`，`Math.round(7000×333/1000.0) = 2331`——仍同！**必须实测找到会分叉的输入**：如 `moveCost = 1, ‰ = 501` ⇒ `floorDiv(1001+500)= …` 当场量），把量到的输入与两侧结果写进报告；
2. **m2**：`moveCost >= IMPASSABLE_MOVE_COST` 改成 `> ` 或删掉该判断 ⇒ `impassableTerrainHasNoCost` 红；
3. **m3**：`minStepCostMillis` 的"跳过不可通行"删掉 ⇒ `minStepCostIsTheCheapestTraversableTerrainScaled` 红（夹具里 999 会赢）。
   每轮：干净世界 → 改前绿 → 字节不同 → 改后红、`COMPILATION ERROR count = 0`。

- [ ] **Step 6: 提交**

```bash
./mvnw -q spotless:apply
git add simos-unit/src/main/java/io/mosire/simos/unit/move/MovementCost.java \
        simos-unit/src/main/java/io/mosire/simos/unit/move/TerrainMovementCost.java \
        simos-unit/src/test/java/io/mosire/simos/unit/move/MoveFixture.java \
        simos-unit/src/test/java/io/mosire/simos/unit/move/TerrainMovementCostTest.java
git diff --cached --stat
git commit -m "feat(unit): MovementCost/TerrainMovementCost 毫 MP 定点成本 + 判据二夹具（M3 Task 8）"
```

---

### Task 9: `PathFinder`（A\*）+ R8

**Files:**
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/move/PathFinder.java`
- Test: `.../unit/move/PathFinderTest.java`

**Interfaces:**
- Consumes: `MovementCost` / `TerrainMovementCost`（Task 8）
- Produces: `PathFinder.findPath(GameMap map, HexCoord start, HexCoord goal, Unit unit, MovementCost cost) → Optional<List<HexCoord>>`

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.unit.move;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.Unit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** A\*（R8）：最优性对拍、决定论、边界、绕行。 */
class PathFinderTest {

  /** `h ≡ 0` 的包装 ⇒ 同一份搜索退化成 Dijkstra，用来对拍"启发没有破坏最优性"。 */
  private record NoHeuristic(MovementCost delegate) implements MovementCost {
    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      return delegate.costMillis(from, to, unit, map);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return 0L;
    }
  }

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final HexCoord H20 = new HexCoord(2, 0);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H21 = new HexCoord(2, 1);

  /**
   * 直线 `[0,0]→[1,0]→[2,0]` 中间那格是山（10）⇒ 11000 毫；
   * 绕行 `[0,0]→[1,1]→[2,1]→[2,0]` 三格平地（1）⇒ 3000 毫。A\* 必须选绕行。
   */
  private static GameMap detourMap() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H00, new HexCell("flat", 0.5));
    hexes.put(H10, new HexCell("hill", 0.5));
    hexes.put(H20, new HexCell("flat", 0.5));
    hexes.put(H11, new HexCell("flat", 0.5));
    hexes.put(H21, new HexCell("flat", 0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put("flat", new TerrainType("flat", "平地", "#336699", 0.0, 1.0, 0, 0, 0, 1, "夹具"));
    terrainTypes.put("hill", new TerrainType("hill", "丘陵", "#4477AA", 0.0, 1.0, 0, 0, 0, 10, "夹具"));
    return new GameMap(
        hexes, Map.of(), Map.of(), terrainTypes, Map.of(), Map.of(), Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static long pathCost(
      MovementCost cost, GameMap map, Unit unit, List<HexCoord> path) {
    long total = 0;
    for (int i = 0; i + 1 < path.size(); i++) {
      total += cost.costMillis(path.get(i), path.get(i + 1), unit, map).orElseThrow();
    }
    return total;
  }

  @Test
  void aStarDetoursAroundExpensiveTerrain() {
    GameMap map = detourMap();
    Optional<List<HexCoord>> path =
        PathFinder.findPath(map, H00, H20, MoveFixture.unit(), TerrainMovementCost.INSTANCE);
    assertThat(path).isPresent();
    assertThat(pathCost(TerrainMovementCost.INSTANCE, map, MoveFixture.unit(), path.get()))
        .as("绕行三格平地：3000 毫（直线要 11000）")
        .isEqualTo(3000L);
    assertThat(path.get()).as("首尾必须钉住").startsWith(H00).endsWith(H20);
  }

  @Test
  void matchesDijkstraOnCost() {
    GameMap map = detourMap();
    Unit unit = MoveFixture.unit();
    MovementCost astar = TerrainMovementCost.INSTANCE;

    List<HexCoord> a =
        PathFinder.findPath(map, H00, H20, unit, astar).orElseThrow();
    List<HexCoord> d =
        PathFinder.findPath(map, H00, H20, unit, new NoHeuristic(astar)).orElseThrow();

    assertThat(pathCost(astar, map, unit, a))
        .as("启发函数不得破坏最优性：两条路径的成本必须相等")
        .isEqualTo(pathCost(astar, map, unit, d));
  }

  @Test
  void sameInputTwiceGivesTheSamePath() {
    GameMap map = detourMap();
    Unit unit = MoveFixture.unit();
    List<HexCoord> first =
        PathFinder.findPath(map, H00, H20, unit, TerrainMovementCost.INSTANCE).orElseThrow();
    for (int i = 0; i < 20; i++) {
      assertThat(PathFinder.findPath(map, H00, H20, unit, TerrainMovementCost.INSTANCE).orElseThrow())
          .as("第 %d 次重跑", i)
          .isEqualTo(first);
    }
  }

  @Test
  void startEqualsGoalIsTheSingleHexPath() {
    assertThat(PathFinder.findPath(detourMap(), H00, H00, MoveFixture.unit(), TerrainMovementCost.INSTANCE))
        .contains(List.of(H00));
  }

  @Test
  void hexesOutsideTheMapAreEmpty() {
    assertThat(
            PathFinder.findPath(
                detourMap(), new HexCoord(9, 9), H20, MoveFixture.unit(),
                TerrainMovementCost.INSTANCE))
        .isEmpty();
    assertThat(
            PathFinder.findPath(
                detourMap(), H00, new HexCoord(9, 9), MoveFixture.unit(),
                TerrainMovementCost.INSTANCE))
        .isEmpty();
  }

  @Test
  void unreachableGoalIsEmpty() {
    GameMap map = detourMap();
    // 只留 [0,0]：其余格从图里去掉 ⇒ [2,0] 不可达
    GameMap lonely =
        new GameMap(
            Map.of(H00, new HexCell("flat", 0.5)), map.regions(), map.cities(), map.terrainTypes(),
            map.pathways(), map.pathwayGroups(), map.edges(), map.spec());
    assertThat(PathFinder.findPath(lonely, H00, H20, MoveFixture.unit(), TerrainMovementCost.INSTANCE))
        .isEmpty();
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PathFinderTest test
```
期望：编译失败（`PathFinder` 不存在）。

- [ ] **Step 3: 实现**

```java
package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A\* 路径规划（M3 spec §4.4）。
 *
 * <p>★ **启发与成本出自同一实现**：{@code h(n) = cost.minStepCostMillis(unit, map) × n.distanceTo(goal)}。
 * 任一单步成本 ≥ 该下界 ⇒ 启发可采纳且**一致** ⇒ 首次弹出即最优，`closed` 集合可以直接用（不需要 reopen）。
 *
 * <p>★ **决定论**：优先队列按 `(f, h, q, r)` **全序**比较，邻居按 {@code HexCoord.neighbors()} 的枚举序展开
 * ⇒ 同一输入永远给同一条路径。
 */
public final class PathFinder {

  private PathFinder() {}

  /** 搜索节点：`f = g + h` 即里算，不入构造器。 */
  private record Node(HexCoord hex, long g, long h) {
    long f() {
      return g + h;
    }
  }

  /** ★ 平局定序必须是**全序**（`f` 相等时还能靠 `h`、`q`、`r` 分开）——否则决定论会把锅甩给堆的内部实现。 */
  private static final Comparator<Node> ORDER =
      Comparator.comparingLong(Node::f)
          .thenComparingLong(Node::h)
          .thenComparingInt(node -> node.hex().q())
          .thenComparingInt(node -> node.hex().r());

  /**
   * 从 {@code start} 到 {@code goal} 的最低成本路径（含首尾）。
   *
   * <p>前置：起点或终点不在图上 ⇒ 空（合法但不存在）；{@code start.equals(goal)} ⇒ 单元素路径；
   * 不可达 ⇒ 空。
   */
  public static Optional<List<HexCoord>> findPath(
      GameMap map, HexCoord start, HexCoord goal, Unit unit, MovementCost cost) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(goal, "goal");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(cost, "cost");
    if (!map.hexes().containsKey(start) || !map.hexes().containsKey(goal)) {
      return Optional.empty();
    }
    if (start.equals(goal)) {
      return Optional.of(List.of(start));
    }
    long minStep = cost.minStepCostMillis(unit, map);
    Map<HexCoord, Long> bestG = new HashMap<>();
    Map<HexCoord, HexCoord> cameFrom = new HashMap<>();
    Set<HexCoord> closed = new HashSet<>();
    PriorityQueue<Node> open = new PriorityQueue<>(ORDER);
    bestG.put(start, 0L);
    open.add(new Node(start, 0L, minStep * start.distanceTo(goal)));

    while (!open.isEmpty()) {
      Node current = open.poll();
      if (!closed.add(current.hex())) {
        continue; // 一致性保证：先进入 closed 的那一份就是最优，后来的同格节点直接丢
      }
      if (current.hex().equals(goal)) {
        return Optional.of(reconstruct(cameFrom, goal));
      }
      for (HexCoord next : current.hex().neighbors()) {
        if (closed.contains(next)) {
          continue;
        }
        OptionalLong step = cost.costMillis(current.hex(), next, unit, map);
        if (step.isEmpty()) {
          continue; // 不可通行的边
        }
        long g = current.g() + step.getAsLong();
        Long known = bestG.get(next);
        if (known != null && known <= g) {
          continue;
        }
        bestG.put(next, g);
        cameFrom.put(next, current.hex());
        open.add(new Node(next, g, minStep * next.distanceTo(goal)));
      }
    }
    return Optional.empty();
  }

  private static List<HexCoord> reconstruct(Map<HexCoord, HexCoord> cameFrom, HexCoord goal) {
    Deque<HexCoord> path = new ArrayDeque<>();
    HexCoord current = goal;
    while (current != null) {
      path.addFirst(current);
      current = cameFrom.get(current);
    }
    return List.copyOf(path);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PathFinderTest test
```
期望：`Tests run: 6, Failures: 0`。

- [ ] **Step 5: 变异自证（G13，至少 3 轮）**

1. **m1**：把 `h` 改成**高估**（如 `minStep * 2 * distance`）⇒ **先看 `aStarDetoursAroundExpensiveTerrain` 红不红**；若该图下仍得最优（可能），换成"高估会漏掉绕行"的图（直线是大成本地形、绕行两格更便宜：`h` 高估两倍 ⇒ 直线被优先弹出）——★ 这条**必须当场实测找到会分叉的夹具**，把量到的结果写进报告；
2. **m2**：比较器去掉 `.thenComparingLong(Node::h)` 等平局项（只留 `f`）⇒ `sameInputTwiceGivesTheSamePath` **有可能仍绿**（同一 JVM 里堆的行为稳定）——**若绿就如实写"未红，理由是……"**，不许假装它红；同时补一条**对称夹具**（两条等成本路径）再试；
3. **m3**：`closed.add` 的判重删掉 ⇒ 对拍用例红（或路径成本变差）。
   每轮：干净世界 → 改前绿 → 字节不同 → 改后红/未红都写实、`COMPILATION ERROR count = 0`。

- [ ] **Step 6: 提交**

```bash
./mvnw -q spotless:apply
git add simos-unit/src/main/java/io/mosire/simos/unit/move/PathFinder.java \
        simos-unit/src/test/java/io/mosire/simos/unit/move/PathFinderTest.java
git diff --cached --stat
git commit -m "feat(unit): PathFinder（A*，启发与成本同源）+ Dijkstra 对拍与决定论守卫（M3 Task 9）"
```

---

### Task 10: `MovementStatus` / `MovementState` / `UnitMoves.evaluate` + R9 + R10（判据二）

**Files:**
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/move/MovementStatus.java`
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/move/MovementState.java`
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/move/UnitMoves.java`
- Test: `.../unit/move/UnitMovesTest.java`

**Interfaces:**
- Consumes: `Route` / `Movement`（Task 6）；`MovementCost` / `TerrainMovementCost`（Task 8）；`MoveFixture`（Task 8）
- Produces:
  - `enum MovementStatus { IN_TRANSIT, ARRIVED, NEED_REPLAN }`
  - `record MovementState(HexCoord currentHex, Optional<HexCoord> nextHex, OptionalLong remainingEdgeCostMillis, MovementStatus status)`
  - `UnitMoves.evaluate(Unit unit, SimosTimestamp at, GameMap map, MovementCost cost) → MovementState`

- [ ] **Step 1: 写失败测试（判据二的逐值表 + R10）**

```java
package io.mosire.simos.unit.move;

import static io.mosire.simos.unit.move.MoveFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** ★ **判据二**（M3 spec §4.5 的冻结夹具与逐值表）+ R10（出发时冻结）。 */
class UnitMovesTest {

  private static final Route ROUTE = new Route(List.of(H11, H13), List.of(H11, H12, H13));

  /** `at = T0 + n` 的在途单位（路线 `[1,1]→[1,2]→[1,3]`，出发于 T0）。 */
  private static Unit inTransit(long hours) {
    Unit base = unit();
    return new Unit(
        base.id(), base.name(), base.parent(), base.position(), base.member(), base.equipment(),
        base.speed(), base.mobilityPerMille(),
        Optional.of(new Movement(ROUTE, T0, base.speed(), base.mobilityPerMille())));
  }

  @Test
  void atTwentyHoursIsInTransitWithFiveMpRemaining() {
    MovementState state =
        UnitMoves.evaluate(inTransit(20), T0.plus(20), map(STEEP_65), TerrainMovementCost.INSTANCE);

    assertThat(state.currentHex()).as("预算 40000 ⇒ 40000−12500=27500；27500−32500=−5000").isEqualTo(H12);
    assertThat(state.nextHex()).contains(H13);
    assertThat(state.remainingEdgeCostMillis()).hasValue(5000L);
    assertThat(state.status()).isEqualTo(MovementStatus.IN_TRANSIT);
  }

  @Test
  void atTwentyTwoHoursIsOneThousandShort() {
    MovementState state =
        UnitMoves.evaluate(inTransit(22), T0.plus(22), map(STEEP_65), TerrainMovementCost.INSTANCE);
    assertThat(state.currentHex()).isEqualTo(H12);
    assertThat(state.nextHex()).contains(H13);
    assertThat(state.remainingEdgeCostMillis()).as("预算 44000 比 45000 少 1000").hasValue(1000L);
    assertThat(state.status()).isEqualTo(MovementStatus.IN_TRANSIT);
  }

  @Test
  void atTwentyThreeHoursArrives() {
    MovementState state =
        UnitMoves.evaluate(inTransit(23), T0.plus(23), map(STEEP_65), TerrainMovementCost.INSTANCE);
    assertThat(state.currentHex()).isEqualTo(H13);
    assertThat(state.nextHex()).as("ARRIVED ⇒ nextHex / remaining 皆空").isEmpty();
    assertThat(state.remainingEdgeCostMillis()).isEmpty();
    assertThat(state.status()).isEqualTo(MovementStatus.ARRIVED);
  }

  @Test
  void impassableNextStepNeedsReplan() {
    MovementState state =
        UnitMoves.evaluate(
            inTransit(20), T0.plus(20), map(IMPASSABLE_999), TerrainMovementCost.INSTANCE);
    assertThat(state.currentHex()).as("卡住前所在格").isEqualTo(H12);
    assertThat(state.nextHex()).isEmpty();
    assertThat(state.remainingEdgeCostMillis()).isEmpty();
    assertThat(state.status()).isEqualTo(MovementStatus.NEED_REPLAN);
  }

  /** ★ R10：出发后改单位速度，`evaluate` 结果不变（`speedAtDeparture` 已冻结）。 */
  @Test
  void speedChangeAfterDepartureDoesNotChangeTheResult() {
    Unit departed = inTransit(20);
    Unit faster =
        new Unit(
            departed.id(), departed.name(), departed.parent(), departed.position(),
            departed.member(), departed.equipment(), 99, departed.mobilityPerMille(),
            departed.movement());

    assertThat(UnitMoves.evaluate(faster, T0.plus(20), map(STEEP_65), TerrainMovementCost.INSTANCE))
        .as("在途行程不得因参数变更而时间反演")
        .isEqualTo(UnitMoves.evaluate(departed, T0.plus(20), map(STEEP_65), TerrainMovementCost.INSTANCE));
  }

  /** ★ 同一条的机动性半：`mobilityAtDeparture` 冻结（成本函数读的是 `frozen` 视图）。 */
  @Test
  void mobilityChangeAfterDepartureDoesNotChangeTheResult() {
    Unit departed = inTransit(20);
    Unit nimbler =
        new Unit(
            departed.id(), departed.name(), departed.parent(), departed.position(),
            departed.member(), departed.equipment(), departed.speed(), 1000,
            departed.movement());

    assertThat(UnitMoves.evaluate(nimbler, T0.plus(20), map(STEEP_65), TerrainMovementCost.INSTANCE))
        .isEqualTo(UnitMoves.evaluate(departed, T0.plus(20), map(STEEP_65), TerrainMovementCost.INSTANCE));
  }

  @Test
  void noRouteOrEarlierThanDepartureIsACallerBug() {
    assertThatThrownBy(
            () -> UnitMoves.evaluate(unit(), T0, map(STEEP_65), TerrainMovementCost.INSTANCE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                UnitMoves.evaluate(
                    inTransit(20), T0.plus(-1), map(STEEP_65), TerrainMovementCost.INSTANCE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void movementStateRejectsSelfContradictoryCombinations() {
    assertThatThrownBy(
            () ->
                new MovementState(
                    H12, Optional.empty(), OptionalLong.empty(), MovementStatus.IN_TRANSIT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new MovementState(H13, Optional.of(H12), OptionalLong.of(1), MovementStatus.ARRIVED))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitMovesTest test
```
期望：编译失败。

- [ ] **Step 3: 实现**

```java
package io.mosire.simos.unit.move;

/** 在途行程的状态（M3 spec §4.5）。 */
public enum MovementStatus {
  /** 正在走：还有没付清的段。 */
  IN_TRANSIT,
  /** 所有段都付清了。 */
  ARRIVED,
  /** 下一段不可通行（地图变了）：路径保留、就地暂停。 */
  NEED_REPLAN
}
```

```java
package io.mosire.simos.unit.move;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import io.mosire.simos.map.hex.HexCoord;

/**
 * 移动物化的结果（M3 spec §4.5）：**当前在哪、正在走哪一段、还欠多少毫 MP**。
 *
 * <p>★ 三个字段与 {@link MovementStatus} 的搭配是**冻结语义**，构造期即拒自相矛盾的组合：
 * `IN_TRANSIT` ⇒ `nextHex` present 且 `remaining > 0`；`ARRIVED` / `NEED_REPLAN` ⇒ 两者皆空。
 */
public record MovementState(
    HexCoord currentHex,
    Optional<HexCoord> nextHex,
    OptionalLong remainingEdgeCostMillis,
    MovementStatus status) {

  public MovementState {
    Objects.requireNonNull(currentHex, "currentHex");
    Objects.requireNonNull(nextHex, "nextHex");
    Objects.requireNonNull(remainingEdgeCostMillis, "remainingEdgeCostMillis");
    Objects.requireNonNull(status, "status");
    if (status == MovementStatus.IN_TRANSIT) {
      if (nextHex.isEmpty() || remainingEdgeCostMillis.isEmpty() || remainingEdgeCostMillis.getAsLong() <= 0) {
        throw new IllegalArgumentException(
            "IN_TRANSIT 必须有下一格且未付清的余量严格 > 0: " + remainingEdgeCostMillis);
      }
    } else if (nextHex.isPresent() || remainingEdgeCostMillis.isPresent()) {
      throw new IllegalArgumentException(status + " 不得带 nextHex 或余量");
    }
  }
}
```

```java
package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Unit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 按时间戳物化移动（M3 spec §4.5）：**纯函数**，不写回状态（写回属 M4 的两阶段推进，spec 偏离 5）。
 *
 * <p>预算模型：`budget = speedAtDeparture × 1000 × (at.tick − departedAt.tick)`（毫 MP），沿 `path` 逐段付；
 * 付不起的那一段 ⇒ `IN_TRANSIT` 并给出余量；全付清 ⇒ `ARRIVED`。
 *
 * <p>★ **机动性冻结口在此**：成本函数从 `Unit` 上读 `mobilityPerMille()`，而在途行程必须用
 * `mobilityAtDeparture` ⇒ 本类**副本一份单位**再调成本函数（不是改 `MovementCost` 的签名）。
 */
public final class UnitMoves {

  private UnitMoves() {}

  /**
   * 计算 `at` 时刻的移动状态。
   *
   * @throws IllegalArgumentException 单位没有在途路线，或 `at` 早于出发时刻（调用方 bug）
   */
  public static MovementState evaluate(
      Unit unit, SimosTimestamp at, GameMap map, MovementCost cost) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(cost, "cost");
    Movement movement =
        unit.movement()
            .orElseThrow(() -> new IllegalArgumentException("单位没有在途路线: " + unit.id()));
    if (at.compareTo(movement.departedAt()) < 0) {
      throw new IllegalArgumentException(
          "查询时刻早于出发时刻: " + at + " < " + movement.departedAt());
    }
    long budget = movement.speedAtDeparture() * 1000L * (at.tick() - movement.departedAt().tick());
    Unit frozen =
        new Unit(
            unit.id(),
            unit.name(),
            unit.parent(),
            unit.position(),
            unit.member(),
            unit.equipment(),
            unit.speed(),
            movement.mobilityAtDeparture(),
            unit.movement());

    List<HexCoord> path = movement.route().path();
    for (int i = 0; i + 1 < path.size(); i++) {
      HexCoord from = path.get(i);
      HexCoord to = path.get(i + 1);
      OptionalLong step = cost.costMillis(from, to, frozen, map);
      if (step.isEmpty()) {
        return new MovementState(from, Optional.empty(), OptionalLong.empty(), MovementStatus.NEED_REPLAN);
      }
      long edgeCost = step.getAsLong();
      if (budget >= edgeCost) {
        budget -= edgeCost;
        continue;
      }
      return new MovementState(
          from, Optional.of(to), OptionalLong.of(edgeCost - budget), MovementStatus.IN_TRANSIT);
    }
    return new MovementState(
        path.get(path.size() - 1), Optional.empty(), OptionalLong.empty(), MovementStatus.ARRIVED);
  }
}
```

★ 实现时补上 `import io.mosire.simos.util.time.SimosTimestamp;`。

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitMovesTest test
```
期望：`Tests run: 9, Failures: 0`。

- [ ] **Step 5: 变异自证（G13，至少 4 轮）**

1. **m1**：`budget >= edgeCost` 改成 `>` ⇒ `atTwentyThreeHoursArrives`（预算恰好够）红；
2. **m2**：`frozen` 视图改回原单位（`cost.costMillis(from, to, unit, map)`）⇒ `mobilityChangeAfterDepartureDoesNotChangeTheResult` 红（**这条就是 R10 的第二个靶子**）；
3. **m3**：`step.isEmpty()` 那条分支改成 `continue`（跳过不可通行段）⇒ `impassableNextStepNeedsReplan` 红；
4. **m4**：`at.compareTo(...) < 0` 的守卫删掉 ⇒ `noRouteOrEarlierThanDepartureIsACallerBug` 的第二个断言红。
   每轮：干净世界 → 改前绿 → 字节不同 → 改后红、`COMPILATION ERROR count = 0`、红点落在被保护那行。

- [ ] **Step 6: 提交**

```bash
./mvnw -q spotless:apply
git add simos-unit/src/main/java/io/mosire/simos/unit/move/MovementStatus.java \
        simos-unit/src/main/java/io/mosire/simos/unit/move/MovementState.java \
        simos-unit/src/main/java/io/mosire/simos/unit/move/UnitMoves.java \
        simos-unit/src/test/java/io/mosire/simos/unit/move/UnitMovesTest.java
git diff --cached --stat
git commit -m "feat(unit): UnitMoves.evaluate 按时刻物化移动（判据二逐值表）+ 出发值冻结（M3 Task 10）"
```

---

### Task 11: `UnitOperations` 8 项 + R11

**Files:**
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`
- Test: `.../unit/ops/UnitOperationsTest.java`

**Interfaces:**
- Consumes: Task 6/7/10 的类型
- Produces: `UnitOperations` 的 8 个静态方法（签名见 Step 3，**逐字照 spec §4.6**）

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.unit.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 编制树操作面 8 项（R11：每项一条负向 + 一条正例）。 */
class UnitOperationsTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T10 = SimosTimestamp.of(10);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId BRIGADE = new UnitId("u-brigade");
  private static final UnitId COMPANY = new UnitId("u-company");

  private static Unit unit(String id, Optional<String> parent, Optional<HexCoord> position) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, parent.map(UnitId::new))), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        1000,
        Optional.empty());
  }

  private static UnitState twoUnits() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(BRIGADE, unit("u-brigade", Optional.empty(), Optional.of(H11)));
    units.put(COMPANY, unit("u-company", Optional.of("u-brigade"), Optional.empty()));
    return new UnitState(units);
  }

  @Test
  void createRejectsDuplicateId() {
    assertThatThrownBy(
            () -> UnitOperations.create(twoUnits(), unit("u-company", Optional.empty(), Optional.empty())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("u-company");
  }

  @Test
  void createRejectsAnUnknownParent() {
    assertThatThrownBy(
            () ->
                UnitOperations.create(
                    twoUnits(), unit("u-new", Optional.of("u-ghost"), Optional.of(H12))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createAcceptsAWellFormedUnit() {
    UnitState state =
        UnitOperations.create(twoUnits(), unit("u-new", Optional.of("u-brigade"), Optional.of(H12)));
    assertThat(state.units()).containsKey(new UnitId("u-new"));
  }

  @Test
  void reparentAppendsASegmentAndRejectsUnknownParents() {
    UnitState state = UnitOperations.reparent(twoUnits(), COMPANY, Optional.of(BRIGADE), T10);
    assertThat(state.units().get(COMPANY).parent().valueAt(T10)).contains(BRIGADE);
    assertThat(state.units().get(COMPANY).parent().valueAt(T0)).isEmpty();
    assertThat(twoUnits().units().get(COMPANY).parent().segments())
        .as("纯函数：旧状态不变")
        .hasSize(1);

    assertThatThrownBy(
            () -> UnitOperations.reparent(twoUnits(), COMPANY, Optional.of(new UnitId("u-ghost")), T10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> UnitOperations.reparent(twoUnits(), new UnitId("u-ghost"), Optional.empty(), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reparentOntoItselfThrows() {
    assertThatThrownBy(() -> UnitOperations.reparent(twoUnits(), COMPANY, Optional.of(COMPANY), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void renameChangesOnlyTheName() {
    UnitState state = UnitOperations.rename(twoUnits(), COMPANY, "一营指挥部");
    assertThat(state.units().get(COMPANY).name()).isEqualTo("一营指挥部");
    assertThat(state.units().get(COMPANY).member()).isEqualTo(100);
    assertThatThrownBy(() -> UnitOperations.rename(twoUnits(), new UnitId("u-ghost"), "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void setStrengthReplacesMemberAndEquipment() {
    UnitState state = UnitOperations.setStrength(twoUnits(), COMPANY, 80, Map.of("炮", 4));
    assertThat(state.units().get(COMPANY).member()).isEqualTo(80);
    assertThat(state.units().get(COMPANY).equipment()).containsOnlyKeys("炮");
    assertThatThrownBy(
            () -> UnitOperations.setStrength(twoUnits(), new UnitId("u-ghost"), 1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void placeAtAppendsAPositionSegmentAndClearsTheRoute() {
    UnitState state = UnitOperations.placeAt(twoUnits(), BRIGADE, Optional.of(H12), T10);
    assertThat(state.units().get(BRIGADE).position().valueAt(T10)).contains(H12);
    assertThat(state.units().get(BRIGADE).position().valueAt(T0)).contains(H11);
    assertThatThrownBy(
            () -> UnitOperations.placeAt(twoUnits(), new UnitId("u-ghost"), Optional.of(H12), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void planRouteRequiresAStartThatMatchesTheEffectivePosition() {
    Route route = new Route(List.of(H11, H12), List.of(H11, H12));
    UnitState state = UnitOperations.planRoute(twoUnits(), BRIGADE, route, T10);
    assertThat(state.units().get(BRIGADE).movement()).isPresent();
    assertThat(state.units().get(BRIGADE).movement().orElseThrow().route()).isEqualTo(route);

    // 无位置的单位（COMPANY 整链都没给位置）：抛
    assertThatThrownBy(() -> UnitOperations.planRoute(twoUnits(), COMPANY, route, T10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("位置");
    // 起点对不上：抛
    assertThatThrownBy(
            () -> UnitOperations.planRoute(twoUnits(), BRIGADE, new Route(List.of(H12, H11), List.of(H12, H11)), T10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> UnitOperations.planRoute(twoUnits(), new UnitId("u-ghost"), route, T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cancelRouteClearsTheMovement() {
    Route route = new Route(List.of(H11, H12), List.of(H11, H12));
    UnitState planned = UnitOperations.planRoute(twoUnits(), BRIGADE, route, T10);
    assertThat(UnitOperations.cancelRoute(planned, BRIGADE).units().get(BRIGADE).movement()).isEmpty();
    assertThatThrownBy(() -> UnitOperations.cancelRoute(twoUnits(), new UnitId("u-ghost")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disbandRefusesWhileSubordinatesExist() {
    assertThatThrownBy(() -> UnitOperations.disband(twoUnits(), BRIGADE, T10))
        .as("在 at 时刻有下属 ⇒ 先改编子单位、再解散")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("u-company");

    UnitState state = UnitOperations.disband(twoUnits(), COMPANY, T10);
    assertThat(state.units()).doesNotContainKey(COMPANY);
    assertThatThrownBy(() -> UnitOperations.disband(twoUnits(), new UnitId("u-ghost"), T10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 解散的时点敏感：改编走在前 ⇒ 同一对单位在**晚一点的时刻**可以解散。 */
  @Test
  void disbandIsTimeSensitive() {
    UnitState reparented =
        UnitOperations.reparent(twoUnits(), COMPANY, Optional.empty(), SimosTimestamp.of(20));
    assertThat(UnitOperations.disband(reparented, BRIGADE, SimosTimestamp.of(20)).units())
        .containsKey(COMPANY);
    assertThatThrownBy(() -> UnitOperations.disband(reparented, BRIGADE, T10))
        .as("在 T10 时刻 COMPANY 仍挂在 BRIGADE 下")
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

★ 夹具里 `unit("u-company", Optional.of("u-brigade"), Optional.empty())` 在 T0 已是 `BRIGADE` 的下属
⇒ `disbandIsTimeSensitive` 的 `reparent(..., T20)` 把 T20 起的父清空 ✓（同刻已有段会抛，故用 T20）。

- [ ] **Step 2: 跑测试确认失败**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitOperationsTest test
```
期望：编译失败。

- [ ] **Step 3: 实现**

```java
package io.mosire.simos.unit.ops;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 编制树操作面（M3 spec §4.6，用户裁定 U5 的**全套 8 项**）：创建 / 改编 / 改名 / 人数·装备变更 /
 * 位置设置 / 下达路线 / 取消路线 / 解散。
 *
 * <p>★ **每个操作都是纯函数**（产出的都是新 {@code UnitState}）。变更集**唯一**的生产路径是
 * {@code UnitChangeSet.between(base, target)}——**不做**"操作直接拼增量变更集"的第二条路径
 * （两条路径必然分叉，正是本项目最贵的教训形态）。
 *
 * <p>★ 名单外的编辑（速度、机动性、装备之外的自定义字段）**不在操作面**：需要时走 `between`，
 * 即"改字段"永远是变更集的语义，不是操作面的语义（spec §4.6 第 7 条）。
 *
 * <p>★ **成环不在这里重复实现**：`reparent` 只校验新父存在，环由 {@link UnitState} 构造期拒绝。
 */
public final class UnitOperations {

  private UnitOperations() {}

  /** 创建：同 id 已在 ⇒ 抛；`parent` 值（若 present）必须在 `units` 里。 */
  public static UnitState create(UnitState state, Unit unit) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(unit, "unit");
    if (state.units().containsKey(unit.id())) {
      throw new IllegalArgumentException("单位 id 已存在: " + unit.id());
    }
    for (Segment<Optional<UnitId>> segment : unit.parent().segments()) {
      segment.value().ifPresent(parent -> requireExists(state, parent));
    }
    return withUnit(state, unit);
  }

  /** 改编：追加一条 `parent` 段（`from = at`）。同刻已有段 ⇒ 由严格升序校验抛。 */
  public static UnitState reparent(
      UnitState state, UnitId id, Optional<UnitId> newParent, SimosTimestamp at) {
    Objects.requireNonNull(newParent, "newParent");
    Unit unit = require(state, id);
    newParent.ifPresent(parent -> requireExists(state, parent));
    return withUnit(
        state, copy(unit, unit.name(), append(unit.parent(), at, newParent), unit.position(),
            unit.member(), unit.equipment(), unit.speed(), unit.mobilityPerMille(), unit.movement()));
  }

  public static UnitState rename(UnitState state, UnitId id, String name) {
    Unit unit = require(state, id);
    return withUnit(
        state, copy(unit, name, unit.parent(), unit.position(), unit.member(), unit.equipment(),
            unit.speed(), unit.mobilityPerMille(), unit.movement()));
  }

  public static UnitState setStrength(
      UnitState state, UnitId id, int member, Map<String, Integer> equipment) {
    Unit unit = require(state, id);
    return withUnit(
        state, copy(unit, unit.name(), unit.parent(), unit.position(), member, equipment,
            unit.speed(), unit.mobilityPerMille(), unit.movement()));
  }

  /** 位置设置：追加一条 `position` 段；**顺带清空在途路线**（改了位置，旧路线不再有意义）。 */
  public static UnitState placeAt(
      UnitState state, UnitId id, Optional<HexCoord> hex, SimosTimestamp at) {
    Objects.requireNonNull(hex, "hex");
    Unit unit = require(state, id);
    return withUnit(
        state, copy(unit, unit.name(), unit.parent(), append(unit.position(), at, hex),
            unit.member(), unit.equipment(), unit.speed(), unit.mobilityPerMille(),
            Optional.empty()));
  }

  /** 下达路线：路线起点必须等于该单位在 `at` 的 {@code effectivePosition}（无位置 ⇒ 抛）。 */
  public static UnitState planRoute(
      UnitState state, UnitId id, Route route, SimosTimestamp at) {
    Objects.requireNonNull(route, "route");
    Unit unit = require(state, id);
    HexCoord start =
        state
            .effectivePosition(id, at)
            .orElseThrow(
                () -> new IllegalArgumentException("单位 " + id + " 在 " + at + " 没有可确定的位置，无法下达路线"));
    HexCoord routeStart = route.waypoints().get(0);
    if (!start.equals(routeStart)) {
      throw new IllegalArgumentException(
          "路线起点 " + routeStart + " 不是单位在 " + at + " 的位置 " + start);
    }
    return withUnit(
        state, copy(unit, unit.name(), unit.parent(), unit.position(), unit.member(),
            unit.equipment(), unit.speed(), unit.mobilityPerMille(),
            Optional.of(new Movement(route, at, unit.speed(), unit.mobilityPerMille()))));
  }

  public static UnitState cancelRoute(UnitState state, UnitId id) {
    Unit unit = require(state, id);
    return withUnit(
        state, copy(unit, unit.name(), unit.parent(), unit.position(), unit.member(),
            unit.equipment(), unit.speed(), unit.mobilityPerMille(), Optional.empty()));
  }

  /** 解散：**在 `at` 时刻有下属 ⇒ 抛**（判据 = 遍历所有单位在该时刻的 `parent` 值是否指向它）。 */
  public static UnitState disband(UnitState state, UnitId id, SimosTimestamp at) {
    Unit unit = require(state, id);
    for (Unit other : state.units().values()) {
      if (other.id().equals(id)) {
        continue;
      }
      if (other.parent().valueAt(at).filter(id::equals).isPresent()) {
        throw new IllegalArgumentException(
            "单位 " + id + " 在 " + at + " 仍有下属 " + other.id() + "：先改编、再解散");
      }
    }
    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    next.remove(id);
    return new UnitState(next);
  }

  // ── 私有助手 ────────────────────────────────────────────────────

  private static Unit require(UnitState state, UnitId id) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(id, "id");
    Unit unit = state.units().get(id);
    if (unit == null) {
      throw new IllegalArgumentException("单位不存在: " + id);
    }
    return unit;
  }

  private static void requireExists(UnitState state, UnitId id) {
    if (!state.units().containsKey(id)) {
      throw new IllegalArgumentException("父单位不存在: " + id);
    }
  }

  private static UnitState withUnit(UnitState state, Unit unit) {
    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    next.put(unit.id(), unit); // 覆盖时**保持原键位**（LinkedHashMap 的既有键不改变位置）
    return new UnitState(next);
  }

  private static <T> SegmentedSeries<T> append(
      SegmentedSeries<T> series, SimosTimestamp at, T value) {
    List<Segment<T>> segments = new ArrayList<>(series.segments());
    segments.add(new Segment<>(at, value));
    return new SegmentedSeries<>(segments, series.events(), series.addition());
  }

  private static Unit copy(
      Unit unit,
      String name,
      SegmentedSeries<Optional<UnitId>> parent,
      SegmentedSeries<Optional<HexCoord>> position,
      int member,
      Map<String, Integer> equipment,
      int speed,
      int mobilityPerMille,
      Optional<Movement> movement) {
    return new Unit(
        unit.id(), name, parent, position, member, equipment, speed, mobilityPerMille, movement);
  }
}
```

★ **取代说明（计划期）**：spec §4.6 第 2 条把"`parent` 值必须在 `units` 里"归给 `create`——**本计划照此实现**
（`requireExists`）；`reparent` 同（spec 第 3 条）。两处都**不**依赖 `UnitState` 的环校验（它管的是环，不管"父不存在"）。

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitOperationsTest test
```
期望：全绿。

- [ ] **Step 5: 变异自证（G13——8 项各至少打一枪，可合并成 4~5 轮）**

1. **m1**：`create` 的"同 id ⇒ 抛"删掉 ⇒ `createRejectsDuplicateId` 红；
2. **m2**：`disband` 的"有下属 ⇒ 抛"循环删掉 ⇒ `disbandRefusesWhileSubordinatesExist` 红；再把 `valueAt(at)` 换成"合并所有时刻"（即 `valueAt` 恒取最新）⇒ `disbandIsTimeSensitive` 红（**时点敏感性的判别力证据**）；
3. **m3**：`planRoute` 的起点相等判断删掉 ⇒ `planRouteRequiresAStartThatMatchesTheEffectivePosition` 红；
4. **m4**：`placeAt` 的 `Optional.empty()`（清空路线）换回 `unit.movement()` ⇒ 需**补一条用例**：先 `planRoute` 再 `placeAt`，断言 `movement()` 为空——**先写用例再变异**，若用例不存在就先补上（这就是 spec §4.6 第 5 条的靶子）；
5. **m5**：`append` 用 `series.segments()` 之外的方式构造（或 `reparent` 不追加段）⇒ `reparentAppendsASegmentAndRejectsUnknownParents` 红。
   每轮：干净世界 → 改前绿 → 字节不同 → 改后红、`COMPILATION ERROR count = 0`、红点落在被保护那行。

- [ ] **Step 6: 提交**

```bash
./mvnw -q spotless:apply
git add simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java \
        simos-unit/src/test/java/io/mosire/simos/unit/ops/UnitOperationsTest.java
git diff --cached --stat
git commit -m "feat(unit): UnitOperations 编制树操作面 8 项（M3 Task 11）"
```

---

### Task 12: `UnitResolver` + R12/R13 的 unit 半

**Files:**
- Create: `simos-unit/src/main/java/io/mosire/simos/unit/resolve/UnitResolver.java`
- Test: `.../unit/resolve/UnitResolverTest.java`

**Interfaces:**
- Consumes: Task 6/11 的类型；`Resolver` / `ResolveContext` / `Address` / `QueryResult`（M1）
- Produces: `public final class UnitResolver implements Resolver`（`namespace() == "unit"`）

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.unit.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * unit: 寻址（R12 空候选 vs 抛 + R13 canonical 只回 ID）。
 *
 * <p>夹具：`高地人旅指挥部`（无父）→ `1营指挥部` → `1连指挥部` 三级链 + 一个用 ID 定位的 `u-f82a`。
 */
class UnitResolverTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp TS = SimosTimestamp.of(5);
  private static final HexCoord H11 = new HexCoord(1, 1);

  private static Unit unit(String id, String name, String parent) {
    return new Unit(
        new UnitId(id),
        name,
        new SegmentedSeries<>(
            List.of(new Segment<>(TS, parent == null ? Optional.<UnitId>empty() : Optional.of(new UnitId(parent)))),
            List.of(),
            null),
        new SegmentedSeries<>(
            List.of(new Segment<>(TS, Optional.of(H11))), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        1000,
        Optional.empty());
  }

  private static ResolveContext ctx() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("u-brigade"), unit("u-brigade", "高地人旅指挥部", null));
    units.put(new UnitId("u-bn1"), unit("u-bn1", "1营指挥部", "u-brigade"));
    units.put(new UnitId("u-co1"), unit("u-co1", "1连指挥部", "u-bn1"));
    units.put(new UnitId("u-f82a"), unit("u-f82a", "独立连", null));
    UnitSnapshot snapshot = new UnitSnapshot(REF, TS, new UnitState(units));
    return new ResolveContext(
        new SimulationState(new StateMeta(REF, TS), Map.of("unit", snapshot), InMemoryInfoSystem.empty()),
        TS);
  }

  @Test
  void idFormResolvesAndCanonicalisesToTheId() {
    var result = new UnitResolver().resolve(Address.parse("unit:u-f82a"), ctx());
    assertThat(result.candidates()).hasSize(1);
    assertThat(result.candidates().get(0).typeName()).isEqualTo("Unit");
    assertThat(result.candidates().get(0).canonicalAddress()).isEqualTo("unit:u-f82a");
  }

  /** ★ **R13**：链式输入（人读形式）的 canonical 一律回 ID。 */
  @Test
  void chainFormCanonicalisesToTheId() {
    var result =
        new UnitResolver()
            .resolve(Address.parse("unit:\"高地人旅指挥部.1营指挥部.1连指挥部\""), ctx());
    assertThat(result.candidates()).as("链式逐级定位").hasSize(1);
    assertThat(result.candidates().get(0).canonicalAddress()).as("canonical 一律回 ID").isEqualTo("unit:u-co1");
  }

  @Test
  void chainWithMultipleHitsIsOrderedByUnitId() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("u-b"), unit("u-b", "同名连", null));
    units.put(new UnitId("u-a"), unit("u-a", "同名连", null));
    UnitSnapshot snapshot = new UnitSnapshot(REF, TS, new UnitState(units));
    ResolveContext ctx =
        new ResolveContext(
            new SimulationState(new StateMeta(REF, TS), Map.of("unit", snapshot), InMemoryInfoSystem.empty()),
            TS);

    var result = new UnitResolver().resolve(Address.parse("unit:\"同名连\""), ctx);
    assertThat(result.candidates()).hasSize(2);
    assertThat(result.candidates().get(0).canonicalAddress()).as("多解按 UnitId 字典序保序").isEqualTo("unit:u-a");
  }

  @Test
  void chainIsOnlyServedForTwoSegmentAddresses() {
    // 两段之外（unit:"链".equipment.X 会落成三段）⇒ 空候选
    assertThat(
            new UnitResolver()
                .resolve(Address.parse("unit:\"高地人旅指挥部.1营指挥部\":member"), ctx())
                .candidates())
        .isEmpty();
  }

  @Test
  void equipmentResolvesOnlyWhenTheNameExists() {
    var hit = new UnitResolver().resolve(Address.parse("unit:u-f82a:equipment.步枪"), ctx());
    assertThat(hit.candidates()).hasSize(1);
    assertThat(hit.candidates().get(0).canonicalAddress()).isEqualTo("unit:u-f82a:equipment.步枪");
    assertThat(hit.candidates().get(0).id().localId()).isEqualTo("u-f82a/步枪");

    assertThat(new UnitResolver().resolve(Address.parse("unit:u-f82a:equipment.炮"), ctx()).candidates())
        .as("没有该装备 ⇒ 空候选")
        .isEmpty();
  }

  // ── R12 ────────────────────────────────────────────────────────

  @Test
  void unknownUnitAndUnknownChainAreEmptyCandidates() {
    assertThat(new UnitResolver().resolve(Address.parse("unit:u-ghost"), ctx()).candidates()).isEmpty();
    assertThat(
            new UnitResolver().resolve(Address.parse("unit:\"不存在的名字\""), ctx()).candidates())
        .isEmpty();
    assertThat(
            new UnitResolver()
                .resolve(Address.parse("unit:\"高地人旅指挥部.不存在的营\""), ctx())
                .candidates())
        .isEmpty();
  }

  @Test
  void attributeSegmentsAndForeignNamespacesAreEmptyCandidates() {
    assertThat(new UnitResolver().resolve(Address.parse("unit:u-f82a:member"), ctx()).candidates())
        .isEmpty();
    assertThat(new UnitResolver().resolve(Address.parse("unit:u-f82a:hex.1_1"), ctx()).candidates())
        .isEmpty();
    assertThat(new UnitResolver().resolve(Address.parse("map:Map1"), ctx()).candidates()).isEmpty();
  }

  @Test
  void missingSliceThrows() {
    ResolveContext noSlice =
        new ResolveContext(
            new SimulationState(new StateMeta(REF, TS), Map.of(), InMemoryInfoSystem.empty()), TS);
    assertThatThrownBy(() -> new UnitResolver().resolve(Address.parse("unit:u-f82a"), noSlice))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unit");
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitResolverTest test
```
期望：编译失败。

- [ ] **Step 3: 实现**

```java
package io.mosire.simos.unit.resolve;

import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.Resolver;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit:} 命名空间的地址解析器（M3 spec §4.8）。**canonical = {@code unit:<unitId>}**（用户裁定 U2）：
 * 链式地址（{@code unit:"高地人旅指挥部.1营指挥部"}）是**定位/人读形式**，与 ID 形两路都收，**canonical 一律回 ID**。
 *
 * <p>★ **两段地址的判据：先按 ID 查，未命中且名字含 {@code .} 才走链式定位。**（**取代说明（计划期）**：
 * spec §4.8 没写两路的先后——M1 的地址 AST **不保留引号**，`unit:u-f82a` 与 `unit:"A.B.C"` 在结构上同形，
 * 只能按内容分。ID 优先 = 铁律 1"身份优先于名字"在解析层的落地；代价是**含点的 ID 只能用 ID 形定位**，
 * 与"含点的名字只能用 ID 形定位"是同一枚硬币的两面。）
 *
 * <p>★ 链式定位只服务**两段地址**（`unit:"链":equipment.X` 这类组合 ⇒ 空候选，避免链多解 × 子实体的组合爆炸）。
 *
 * <p>**空候选与抛的分工**同 {@code MapResolver}/{@code SocialResolver}：合法但不服务的一律空候选；
 * 抛只有两处——装配故障与认领路径上**名字解析失败**（`UnitId.parse` 自己的 IAE，不包不吞）。
 */
public final class UnitResolver implements Resolver {

  private static final String NAMESPACE = "unit";

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    if (!NAMESPACE.equals(address.namespace())) {
      return empty();
    }
    UnitState state = stateOf(ctx);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity second) || second.kind().isPresent()) {
      return empty();
    }
    if (segments.size() == 2) {
      return resolveRootLevel(state, second.name());
    }
    if (segments.size() > 3) {
      return empty();
    }
    return resolveChild(state, second.name(), segments.get(2));
  }

  /** 两段：先 ID、后链式（见类注释的取代说明）。 */
  private static QueryResult resolveRootLevel(UnitState state, String name) {
    UnitId id = UnitId.parse(name);
    Unit byId = state.units().get(id);
    if (byId != null) {
      return single(byId, "Unit");
    }
    if (!name.contains(".")) {
      return empty(); // 既不是已知 ID、也不是链式形态
    }
    return resolveChain(state, List.of(name.split("\\.", -1)));
  }

  /** 链式定位：按 `ctx.at()` 从**无父者**逐级按 `name` 匹配；每个命中都是一个候选。 */
  private static QueryResult resolveChain(UnitState state, List<String> names) {
    List<Unit> frontier = new ArrayList<>();
    for (Unit unit : state.units().values()) {
      if (unit.parent().valueAt(AT.get()).isEmpty()) { // AT 见下：由调用方设置的查询时刻
        frontier.add(unit);
      }
    }
    ...
  }
```
★ **写实现时改掉上面的 `AT` 占位**：把 `ctx.at()` 一路传参下去（`resolveChain(state, names, at)`、`childrenAt(state, parents, at)`）——**本类不得有可变静态状态**。

```java
  private static QueryResult resolveChain(UnitState state, List<String> names, SimosTimestamp at) {
    List<Unit> frontier = new ArrayList<>();
    for (Unit unit : state.units().values()) {
      if (unit.parent().valueAt(at).isEmpty()) {
        frontier.add(unit);
      }
    }
    for (int level = 0; level < names.size(); level++) {
      String wanted = names.get(level);
      List<Unit> hits = new ArrayList<>();
      for (Unit unit : frontier) {
        if (unit.name().equals(wanted)) {
          hits.add(unit);
        }
      }
      if (hits.isEmpty()) {
        return empty();
      }
      if (level == names.size() - 1) {
        hits.sort(Comparator.comparing(unit -> unit.id().value())); // 多解按 UnitId 字典序保序
        List<ResolvedSubject> subjects = new ArrayList<>(hits.size());
        for (Unit hit : hits) {
          subjects.add(subjectOf(hit, "Unit"));
        }
        return new QueryResult(subjects);
      }
      frontier = childrenAt(state, hits, at);
    }
    return empty();
  }

  private static List<Unit> childrenAt(UnitState state, List<Unit> parents, SimosTimestamp at) {
    List<Unit> children = new ArrayList<>();
    for (Unit unit : state.units().values()) {
      Optional<UnitId> parent = unit.parent().valueAt(at);
      if (parent.isPresent()
          && parents.stream().anyMatch(candidate -> candidate.id().equals(parent.get()))) {
        children.add(unit);
      }
    }
    return children;
  }

  /** 三段：只服务 `unit:<id>:equipment.<名>`（ID 形；链式后接子实体 ⇒ 空候选）。 */
  private static QueryResult resolveChild(UnitState state, String name, AddressSegment third) {
    if (!(third instanceof Entity entity) || entity.kind().isEmpty()) {
      return empty();
    }
    UnitId id = UnitId.parse(name);
    Unit unit = state.units().get(id);
    if (unit == null) {
      return empty();
    }
    if (!"equipment".equals(entity.kind().get())) {
      return empty();
    }
    if (!unit.equipment().containsKey(entity.name())) {
      return empty();
    }
    SubjectId subjectId = new SubjectId("unit.equipment", id.value() + "/" + entity.name());
    Address canonical =
        new Address(
            List.of(
                new Namespace(NAMESPACE), Entity.of(id.value()), Entity.of("equipment", entity.name())));
    return new QueryResult(List.of(new ResolvedSubject(subjectId, canonical.canonical(), "Equipment")));
  }

  private static ResolvedSubject subjectOf(Unit unit, String typeName) {
    Address canonical =
        new Address(List.of(new Namespace(NAMESPACE), Entity.of(unit.id().value())));
    return new ResolvedSubject(new SubjectId(NAMESPACE, unit.id().value()), canonical.canonical(), typeName);
  }

  private static UnitState stateOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 unit 模块切片——UnitResolver 需要 UnitSnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalArgumentException(
          "unit 模块切片不是 UnitSnapshot：" + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  private static QueryResult empty() {
    return new QueryResult(List.of());
  }
}
```

★ 实现时：`resolveRootLevel` / `resolveChain` / `resolveChild` 都要把 `ctx.at()` 传下去（草图里 `resolveRootLevel(state, second.name())` 的签名要加 `at`）。

- [ ] **Step 4: 跑测试确认通过**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitResolverTest test
```
期望：全绿。

- [ ] **Step 5: 变异自证（G13，至少 4 轮）**

1. **m1**：`resolveChain` 的 canonical 改成"把链原样回显"⇒ `chainFormCanonicalisesToTheId` 红（**R13 的靶子**）；
2. **m2**：`childrenAt` 的 `valueAt(at)` 改成"只看首段值"⇒ **未必红**（链上无改编时两者同值）——若绿，**当场补一条带改编的夹具**（T0 时 A→B、T10 时 A 无父）让两者分叉，再跑；
3. **m3**：`resolveRootLevel` 去掉 `name.contains(".")` 判断（任何名字都走链式）⇒ `unknownUnitAndUnknownChainAreEmptyCandidates` 的 `unit:u-ghost` 那条**仍绿**（链式也查不到），**不是合格靶子**——换个方向：把 `resolveChain` 的根候选改成"所有单位"（不筛无父）⇒ 那条链式四段/前缀命中用例红；
4. **m4**：`equipment` 的 `containsKey` 判断删掉 ⇒ `equipmentResolvesOnlyWhenTheNameExists` 红。
   每轮：干净世界 → 改前绿 → 字节不同 → 改后红/未红的理由要写清、`COMPILATION ERROR count = 0`。

- [ ] **Step 6: 提交**

```bash
./mvnw -q spotless:apply
git add simos-unit/src/main/java/io/mosire/simos/unit/resolve/UnitResolver.java \
        simos-unit/src/test/java/io/mosire/simos/unit/resolve/UnitResolverTest.java
git diff --cached --stat
git commit -m "feat(unit): UnitResolver 的 unit: 寻址（ID 形 + 链式定位，canonical 只回 ID）（M3 Task 12）"
```

---

### Task 13: M3 关账

**Files:**
- Modify: `CLAUDE.md`（当前状态表：M3 行 → ✅、推送状态行）
- Create: `.superpowers/sdd/2026-09-17-social-unit-simos-plan/task-13-report.md`（**以 `git add -f` 入库**，`.superpowers/**` 在 `.gitignore` 下）

**Interfaces:**
- Consumes: Task 1~12 的全部交付物
- Produces: M3 关账结论（四条判据逐条核过 + 未核实清单 + 挂起项）

- [ ] **Step 1: 全量门禁**

```bash
./mvnw clean verify 2>&1 | tee /tmp/m3-clean-verify.log
```
逐项记录（**照 M2 的关账口径**）：退出码、六模块各自结果、`Tests run` 三个数（util / map / social / unit / core）、`BugInstance size is 0` 的次数、`^\[ERROR\]` 行数、`^\[WARNING\]` 行数与出处。★ 期望：social 与 unit 的 `Tests run` **从 0 变成各自的实际条数**（M2 关账时它们是 0）。

- [ ] **Step 2: 判据一逐条核**

```bash
./mvnw -q -pl simos-social -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PopulationSeriesTest test
git grep -n "18036\|6300\|15000" -- simos-social/src/test
```
报告里给出：用例名 + 期望值 + **用途例跑出来的痕迹**（日志里的 `Tests run` 行）。**不许**只写"已核对"。

- [ ] **Step 3: 判据二逐条核**

```bash
./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitMovesTest,TerrainMovementCostTest test
git grep -n "12500\|32500\|5000\|1000" -- simos-unit/src/test
```
报告里给出 §4.5 那三行表的**逐行对应**（`27500` 的中间值也要有痕迹：它由 `40000 − 12500` 得到，
写清"这是怎么核到的"——**中间值没有直接断言就不许说"核过"**）。★ 若发现"27.5 MP"这类中间量**真的没有直接用例**，
**当场补一条断言 `StepCosts` 与预算算式的用例**（判据二点名要求逐值验算），或者在报告里如实标成**未核实**。

- [ ] **Step 4: 判据三**

`./mvnw clean verify` 的 rc=0 日志（Step 1 的 `/tmp/m3-clean-verify.log`），照 M2 的格式留痕。

- [ ] **Step 5: 判据四（G13 汇总）**

逐任务列出**变异轮数**与**红点是否落在声明靶子**。口径照 M2：轮数 = 证据目录里的轮次文件数（目录数与台账文字数字不一致时**以证据目录为准并如实并列**）；**未红的轮次如实写"未红 + 理由"**（不许把"没验"写成"验过"）。**按推导记录、未补轮**的项也逐条列出并给理由。

- [ ] **Step 6: 更新 `CLAUDE.md`**

- 「当前状态」表加 M3 行：`✅ 已完成（13/13，2026-09-17）` + spec/计划/台账路径 + 四条判据的核对结论；
- 「推送状态」行更新（本分支 `feat/m3-social-unit-simos`）。

- [ ] **Step 7: 写关账报告并提交**

```bash
git add CLAUDE.md docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md
git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/progress.md \
           .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-13-report.md
git diff --cached --stat
git commit -m "docs(sdd): M3（SocialSimos + UnitSimos）关账：判据逐条核过、台账与报告入库（M3 Task 13）"
git push -u origin feat/m3-social-unit-simos
```

★ 关账报告必须包含 **「我未能核实的」** 段（M2 关账有，M3 照办）——至少覆盖：`GameMap` 无 id ⇒ `social:<mapId>` 只回显（挂起项 1）、属性段地址不服务（挂起项 2）、materialize 写回状态不在 M3（挂起项 3）、人口 cache 未做（挂起项 5）、A\* 规模未测（挂起项 9）、**链式定位与含点名字/ID 的取舍**（本计划 Task 12 的取代说明）。

---

## 计划自审（writing-plans 的第三步，已跑）

**1. spec 覆盖**：spec §3.1→Task 4、§3.2→Task 3、§3.3→Task 3、§3.4→Task 4、§3.5→Task 5、§3.6→Task 3；
§4.1→Task 6、§4.2→Task 6、§4.3→Task 8、§4.4→Task 9、§4.5→Task 10、§4.6→Task 11、§4.7→Task 7、§4.8→Task 12；
§五（时间语义）→ Task 3（第 3/4 条）+ Task 6（`parent`/`position` 的段语义）；§6.1/§6.2→各任务的往返与 R1~R13（**R1→Task 1、R2→Task 2、R3/R4→Task 3、R5/R6/R7→Task 6、R8→Task 9、R9/R10→Task 10、R11→Task 11、R12/R13→Task 5 + Task 12**）；§1.2 的交付物表逐项有落点；§七的 13 任务与本计划任务表一一对应。**无遗漏**。

**2. 占位符扫描**：无 `TBD` / `TODO` / "类似 Task N"；每个代码步骤都给了真实代码。**三处取代说明**（Task 3 的 `withEvent` 校验归属、Task 8 的 `minStepCostMillis` 扫描实现、Task 12 的 ID 优先判据）与**一处执行期修订点**（Task 12 的 `AT` 占位与 `ctx.at()` 传参——本类不得有可变静态状态）已显式标出，不是占位符。

**4. 上游 API 核对（写完计划后当场做的，不是记忆）**：`git grep` 逐条读了 M1/M2 的真实签名，修正了计划草图的 **三处漂移**（全部在 resolver 测试里）：`QueryResult.subjects()` ⇒ **`candidates()`**、`SubjectId.localPart()` ⇒ **`localId()`**（第三处是同一批的连带改写）。同批核实无误的还有：`ResolveContext(SimulationState, SimosTimestamp)`、`Address.namespace()`、`Entity.of(name)` / `Entity.of(kind, name)`、`Index(List<Integer> coords)`、`Namespace(String ident)`、`MapResolver` 的 `single/empty/entityAddress` 私有助手形制、`GameMap` 八组件、`HexCell(String, double)`、`TerrainType` 十参数序、`TerrainCatalog.of/KEYS`、`GenerationSpec.defaults(long)`、`InMemoryInfoSystem.empty()`、`HexCoord.neighbors()/distanceTo()/parse/toString`、`EventMode.ADD/SET`、`SimulationState.module(String) → Optional<Snapshot>`。

**3. 类型一致性**：`FieldDelta.diff/rebuild`（Task 1）在 Task 4/7 被调用，签名一致；`Unit` 的 9 个组件在 Task 6/7/10/11 逐处一致；`Route`/`Movement` 在 `io.mosire.simos.unit`（**不是** `...unit.move`）——Task 6 定义、Task 10 使用、Task 11 构造，三处一致；`MovementState` 的四组件与 `UnitMoves` 的构造点一致；`UnitOperations` 的 8 个签名**逐字照 spec §4.6**（`placeAt` 收 `Optional<HexCoord>`，不是 `HexCoord`）。

---

## 执行期取代说明汇总（Task 1~12 关账时回填）

> M3 执行期发现并处置的计划缺陷/取值校正，目前只在各 brief/report/台账里；本节是**索引**——只记"计划原文在哪、取代后写成什么、详情在哪"，**不重写、不删除任何草图原文**。
> 出处三件套：`task-N-brief.md`（派单扫描结论）+ `task-N-report.md`（实测处置）+ 台账 `progress.md`（关账裁定），均在 `.superpowers/sdd/2026-09-17-social-unit-simos-plan/` 下。

| 任务 | 计划原文所在 | 取代后写法（一句） | 详情出处 |
|---|---|---|---|
| Task 4 | 第 945 行：`applyOfUnchanged` 用例里 `…isSameAs(base.populations())` | `SocialData` 构造期总是冻结拷贝 ⇒ `apply` 必返回新实例，`isSameAs` 不可满足（首轮实测即红）；改 `containsExactlyEntriesOf`（含迭代序，贴 spec §3.4 的"原样（连键序）"冻结语义），实现一字未动 | task-4-brief.md / task-4-report.md；progress.md Task 4 |
| Task 6 | Step 1 的 `legalReparentAcrossTimeIsNotACycle` 测试数据（b 单段 `[T0→a]`） | 单段数据按"向前恒定延拓"在 t≥10 与 `a→b` 同在场 ⇒ 真环，计划的实现对该数据必抛（首轮实测确实抛）；改**测试数据**：b 补 `[T10→空]`（合法改编要求双方同刻各改一段），实现一字未动 | task-6-brief.md R-6-b / task-6-report.md §二.2；progress.md Task 6 |
| Task 8 | Step 5 的 m1（`scale` 的 `floorDiv` → `Math.round`）与 m3 靶子（计划自带 minStep 用例） | ① m1 前提不成立：`v = moveCost×1000` 恒使 `+500` 不进位，`scale` 是精确乘法 ⇒ `Math.round` 形态为**等价变体**（1 002 990 对 (moveCost,‰) 扫描 0 分叉），真变异改跑 m1'（`costOf` 丢 ×1000）红在 `stepCostsMatchTheFrozenFixture:21`；② 计划自带 minStep 用例用 `map(STEEP_65)`（无 999 格）对 m3 无判别输入 ⇒ "全不可通行 ⇒ 0" 边界**转正**为提交用例 `allImpassableMapHasZeroLowerBound`（fix-1），m3 重跑红点落 `:73` | task-8-brief.md R-8-a / R-8-b；task-8-report.md（含 fix-1 节）；progress.md Task 8 |
| Task 9 | Step 1 的绕行路径与成本数字（`H00→H11(1,1)→H21(2,1)→H20`；11000/3000） | ① 计划绕行首步 `H00→H11` 的 `distanceTo=2`（不相邻），原图从 H00 只能走山格 ⇒ 改 `H21`→`H01=(0,1)`（平地），绕行 = `H00→H01→H11→H20`（三步各 `distanceTo=1`）；② `11000/3000` 按 ‰1000 写错 ⇒ 按冻结夹具 ‰500 重算：直线 5500、绕行 1500（`MoveFixture.unit()` 未动） | task-9-brief.md R-9-a；task-9-report.md；progress.md Task 9 |
| Task 10 | Step 5 的 m1（`budget >= edgeCost` → `>`） | at=23 时第二步 33500 > 32500 仍 `ARRIVED`、提交套件无"预算恰等于段成本"的等值边界用例 ⇒ m1 存活（实测在案，8 测试世界）；补 `exactBudgetArrivalIsArrived`（speed=5、at=T0+9 ⇒ 预算恰 45000）后重跑 m1 ⇒ 红点落该用例（`remaining=0` 触发构造期 IAE——"恰够也是够"） | task-10-brief.md R-10-b；task-10-report.md；progress.md Task 10 |
| Task 11 | Step 1 的 `planRouteRequiresAStartThatMatchesTheEffectivePosition`（COMPANY 无位置 ⇒ 抛）与 `reparentAppendsASegmentAndRejectsUnknownParents`（断言 `parent().valueAt(T0)).isEmpty()`） | ① COMPANY 位置从 BRIGADE 继承出 H11 ⇒"无位置 ⇒ 抛"不成立，改钉 `create` 出的 `u-lost`（真整链无位置）；② COMPANY 在 T0 已是 BRIGADE 下属 ⇒ `valueAt(T0)` 恒非空，该断言对正确实现也红 ⇒ 改钉 `create` 出的无父新兵 `u-recruit`（T0 空、T10 起 BRIGADE）的追加段时间作用域 | task-11-brief.md R-11-a；task-11-report.md §二；progress.md Task 11 |
| Task 12 | Step 3 的 `resolveRootLevel`：`if (!name.contains(".")) return empty();` | 单元素链（名字无点）会被该守卫直接空候选，与计划自己的 `chainWithMultipleHitsIsOrderedByUnitId`（期望 2 候选）矛盾，spec §4.8 也不要求链含点 ⇒ **删守卫**：ID 未命中即走链式定位；类 Javadoc 的"未命中且名字含 . 才走链式定位"同步改为"未命中即走链式定位"（"ID 优先 = 身份优先于名字"的理由仍成立） | task-12-brief.md R-12-a；task-12-report.md；progress.md Task 12 |
