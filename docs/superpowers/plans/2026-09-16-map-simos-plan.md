# MapSimos（M2）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `simos-map` 从空模块建成 M2 交付物：六边形网格、唯一地形词表、`Region`、
统一连通性系统（稳定 ID）、`GameMap`、`MapChangeSet`（含反射把守的往返框架）、
地形生成三模式（从头生成 / 框选随机化 / 自动河流）、`map:` 寻址。

**Architecture:** 全部是**不可变值类型 + 一张由 record 组件反射把守的归属表**。
三条贯穿的设计决定（均见 spec §〇 的五项裁决）：

1. **`HexCoord` 是身份，`"q_r"` 只是它的一个渲染** —— 坐标不再借字符串当身份（铁律 1）。
2. **可重算的东西不进状态** —— 闭环边界（`RegionBoundary`）、归属反向索引（`RegionIndex`）、
   渲染缓存，**一律派生**，故不进 `GameMap`、不进变更集。
3. **连通性只有一个主存储** —— `Map<EdgeRef, EdgeTags>`；`HexCell` 里那份是 L2 的第二份，删掉。

**Tech Stack:** Java 21、Maven（`./mvnw`）、JUnit 5 + AssertJ、google-java-format（Spotless）、
Checkstyle、SpotBugs。**不引入任何新依赖**（`simos-map` 只有 `simos-util` + Jackson databind + SLF4J）。

**Spec:** `docs/superpowers/specs/2026-09-16-map-simos-design.md`（M2 spec，**状态：待用户评审**）。
本计划的每一步都从它派生；上游是 `docs/superpowers/specs/2026-09-16-simos-master-design.md`（总纲）与
`docs/superpowers/specs/2026-09-16-util-simos-design.md`（M1 spec，已执行）。
**执行者必须读 M2 spec 与 M1 spec 的 §九（往返框架）**，尤其是 M2 spec §七（变更集）。

> **⚠️ 本计划的代码草图是计划期产物。** M1 的教训（见 M1 计划头部的取代说明）：草图**编译得过但可能跑不过**。
> **`simos-map/src` 与 spec 才是权威**；执行期就地校正处**一律保留草图原貌 + 加取代说明**，
> **不要抹掉计划原文**——抹掉它等于抹掉"spec 在执行期被磨尖过"这件事。

> **⚠️ 本计划有两类取值，执行时不要混淆：**
> - **本计划写死的值**（枚举序、字段清单、类型形状、判据）—— **照抄，不得自行发挥**。
> - **标注「执行期从 GSimulator 现读」的值**（`TerrainCatalog` 的 9 行具体数值、`GenerationSpec` 的
>   ~60 个阈值）—— **计划里故意不给数**，因为控制器**没有实测过它们**，写进来就是**编造设计**
>   （总纲 §六 的硬门原话）。执行者按各任务给出的**读取程序**从
>   `~/DevMosire/GSimulator` 现读并记录出处。

---

## Global Constraints

- **Java 21**（`maven.compiler.release=21`）；Maven `[3.8,)`；父 POM `io.mosire:simos-parent:0.1.0-SNAPSHOT`，**不继承** `io.mosire:mosire-parent`
- **依赖白名单**（enforcer 构建期强制，越界即构建失败）：compile 只有 `io.mosire:simos-util`、`com.fasterxml.jackson.core:jackson-databind`、`org.slf4j:slf4j-api`；test 只有 `org.junit.jupiter:junit-jupiter:6.1.3`、`org.assertj:assertj-core:3.27.7`。**不得新增任何依赖**，**永不** import `simos-social` / `simos-unit` / `agentlib-mosire`
- **不做任何存储**：main 与 test 源码都不得出现 `java.io` / `java.nio.file` / `Files` / `Path`
- **无领域词汇**：main 源码不得出现 `unit` / `population` 等**其他模块**的领域词（`hex` / `region` / `terrain` / `city` 是 **MapSimos 自己的**词汇，允许；Javadoc 举例与测试假数据除外）
- **中文注释与文档**；Javadoc **不手工调行宽**（google-java-format 按字符数折行，CJK 计 1 列）——写完跑 `./mvnw -q spotless:apply`
- **测试风格**：JUnit 5 + AssertJ；测试类包级私有、**测试类名与方法名用英文**（沿用 `AgentLibAvailabilityTest` 既有风格），Javadoc 与注释用中文
- **迭代只跑相关单条用例**：`./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<类名> test`（从仓库根目录执行）
- **关账门禁**：`./mvnw clean verify` = Spotless(check) + Checkstyle(validate) + SpotBugs(verify) + Surefire。**`mvn test` 不跑 SpotBugs**
- **护栏必须自证**（G13）：每条新护栏都要有一个故意违规用例证明它真的会响。**怎么确认**：把被保护的那行**删掉**、跑该用例、看它是否真的红；**红了还要问为什么红、没红也要问为什么没红**（CLAUDE.md 纪律节五条形态）
- **变异体也要自证**：读完测试结果**之前**，先证明"变异后的产物与原件字节不同"（编一份原件作参照比 md5）。否则"全绿"可能只是**变异根本没进去**
- **提交纪律**：只 `git add <本步明确列出的文件>`，**绝不 `git add -A`**；提交前扫 `git diff --cached`；**不擅自推送**
- **提交信息末尾**加一行：`Co-Authored-By: Claude Code <noreply@anthropic.com>`
- **不可变与 equals**：所有状态类型不可变，集合组件一律 `List.copyOf` / `Set.copyOf` / `Map.copyOf`；`equals`/`hashCode`/`toString` **一律由 record 提供，禁止手写**（`equals` 是往返断言的判据本身）。★ **例外**：需要保序的 `Map` 用 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`，**不用 `Map.copyOf`**（它会打乱迭代序，使字节级往返不成立 —— 这是 GSimulator 的实测缺陷，spec §6.1）
- **本机 `grep` 是 `ugrep`**：尊重 `.gitignore` 且默认跳过隐藏目录，会**静默返回空**。查 `.superpowers/**` 或被忽略的文件用 `git grep` 或 `grep --hidden --no-ignore-files`

---

## 文件结构（M2 全景）

| 文件 | 职责 |
|---|---|
| `.../map/hex/HexCoord.java` | 轴向坐标，**六边形格的唯一身份**（spec §3.1） |
| `.../map/hex/HexDirection.java` | **全模块唯一的方向常量表**，枚举 6 项（spec §3.2） |
| `.../map/hex/HexGrid.java` | `Map<HexCoord, HexCell>` 容器 + 邻居/范围/遍历（spec §3.1/§3.3） |
| `.../map/terrain/TerrainType.java` | 地形类型（7 字段，spec §6.1） |
| `.../map/terrain/TerrainCatalog.java` | ★ **唯一词表**（9 项）+ 默认集（spec §6.1） |
| `.../map/region/RegionId.java` `RegionMeta.java` `Region.java` | 权威区域（spec §4.2） |
| `.../map/region/RegionBoundary.java` | **派生的**闭环边界（spec §4.3） |
| `.../map/region/RegionIndex.java` | **派生的**归属反向索引，`regionOf` 为 O(1)（spec §4.4） |
| `.../map/pathway/EdgeRef.java` | 无向边（**规范序在构造期完成**，spec §5.1） |
| `.../map/pathway/EdgeTags.java` `PathwayId.java` `Pathway.java` `PathwayGroup.java` | 边上的标注与**稳定 ID 的线**（spec §5.2/§5.4） |
| `.../map/GameMap.java` `CityId.java` `City.java` `HexCell.java` | 地图状态（spec §7.1） |
| `.../map/change/FieldDelta.java` `MapChangeSet.java` | 变更集（spec §7.2） |
| `.../map/generate/GenerationSpec.java` | 生成参数面（spec §6.4） |
| `.../map/generate/TerrainClassifier.java` | 海拔+噪声 → 9 种地形**全覆盖**（spec §6.2） |
| `.../map/generate/MapGenerator.java` | 从头生成（spec §6.5） |
| `.../map/generate/RiverBuilder.java` | 自动河流（spec §6.6） |
| `.../map/generate/RegionRandomizer.java` | 框选随机化（spec §6.6） |
| `.../map/resolve/MapResolver.java` | `map:` 寻址（spec §一） |

测试文件与实现同包，位于 `simos-map/src/test/java/io/mosire/simos/map/<pkg>/`。

## 任务地图（按依赖排序，逐个提交）

| # | 任务 | 交付物 | 依赖 |
|---|---|---|---|
| 1 | `hex` 包：坐标、方向、网格 | `HexCoord` / `HexDirection` / `HexGrid` | — |
| 2 | `terrain` 包：唯一词表 | `TerrainType` / `TerrainCatalog` | — |
| 3 | `region` 包：权威区域 + 派生边界 + 反向索引 | region 包全部 | 1 |
| 4 | `pathway` 包：边与线 | pathway 包全部 | 1 |
| 5 | `map` 包：`HexCell` / `City` / `GameMap`（+ `GenerationSpec` 骨架） | map 包（除 change） | 1,2,3,4 |
| 6 | `change` 包：`FieldDelta` / `MapChangeSet` | change 包 | 5 |
| 7 | ★ **往返框架 + 反射组件枚举 + 自证** | `MapChangeSetTest` 的硬判据 | 6 |
| 8 | `GenerationSpec`：参数面（★ 扩写 Task 5 的骨架） | `GenerationSpec` + 子 record | 1,5 |
| 9 | `TerrainClassifier`：9 项全覆盖 | `TerrainClassifier` | 2,8 |
| 10 | `MapGenerator`：从头生成 + seed 复现 | `MapGenerator` | 5,6,8,9 |
| 11 | `RiverBuilder`：自动河流 | `RiverBuilder` | 5,6,10 |
| 12 | `RegionRandomizer`：框选随机化 | `RegionRandomizer` | 5,6,8 |
| 13 | `MapResolver`：`map:` 寻址 | `MapResolver` | 5 |
| 14 | L1~L9 逐条守卫 | `L1..L9` 对应用例 | 全部 |
| 15 | M2 关账 | 全量 verify + 判据核对 + 状态同步 | 全部 |

---

### Task 1: `hex` 包 —— 坐标、方向、网格

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/hex/HexCoord.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/hex/HexDirection.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/hex/HexGrid.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/hex/HexCoordTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/hex/HexDirectionTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/hex/HexGridTest.java`

**这个任务解的是 L3 与 L6**（8 份方向表 / 坐标表述不一致）。根因（侦察 A 实测）是
`TerrainGeometry.DIRS` 与 `MapService.HEX_DIRS` **都是 package-private、都不导出公共 API**
⇒ 消费方只能复制，**无编译期一致性约束**。本任务把它变成一个 `public` 枚举。

- [ ] **Step 1: 写 `HexCoord`**

```java
package io.mosire.simos.map.hex;

/**
 * 轴向坐标（axial）。**六边形格的唯一身份**。
 *
 * <p>cube 第三轴恒由 {@code s = -q - r} 导出，不存储。
 */
public record HexCoord(int q, int r) implements Comparable<HexCoord> {

  /** cube 第三轴。恒等式 {@code q + r + s == 0}。 */
  public int s() {
    return -q - r;
  }

  /**
   * 到另一格的六边形距离（cube 曼哈顿距离的一半）。
   *
   * <p>GSimulator 有 4 份代数恒等的实现（Java 3 + JS 1），此处合并为唯一一份。
   */
  public int distanceTo(HexCoord other) {
    return (Math.abs(q - other.q) + Math.abs(r - other.r) + Math.abs(s() - other.s())) / 2;
  }

  /** 沿一个方向走一步。 */
  public HexCoord neighbor(HexDirection d) {
    return new HexCoord(q + d.dq(), r + d.dr());
  }

  /** 六条邻格，**枚举序**（即边序号序）。 */
  public java.util.List<HexCoord> neighbors() {
    return HexDirection.ALL.stream().map(this::neighbor).toList();
  }

  @Override
  public int compareTo(HexCoord o) {
    int c = Integer.compare(q, o.q);
    return c != 0 ? c : Integer.compare(r, o.r);
  }

  /** 规范字符串形式，**只在 JSON 边界使用**（身份是类型，不是这个串）。 */
  @Override
  public String toString() {
    return q + "_" + r;
  }

  /** 解析 {@link #toString()} 的产物。 */
  public static HexCoord parse(String text) {
    int i = text.indexOf('_');
    if (i <= 0 || i == text.length() - 1) {
      throw new IllegalArgumentException("非法坐标串: " + text);
    }
    return new HexCoord(Integer.parseInt(text.substring(0, i)), Integer.parseInt(text.substring(i + 1)));
  }
}
```

- [ ] **Step 2: 写 `HexDirection`（★ 全模块唯一的方向表）**

**索引序固定为 A 序（顺时针）：`E, SE, SW, W, NW, NE`。**
依据（spec §3.2）：A 序是**位掩码兼容序** —— GSimulator 的 `riverMask` 明写
`bits 0-5 for edges E,SE,SW,W,NW,NE`，前端 `DIR_VECTORS` 也是 A 序，且该链自洽。
**不要选 B 序**（`E,NE,NW,W,SW,SE`）—— 选它会连位序带前端一起推翻，收益为零。

```java
package io.mosire.simos.map.hex;

import java.util.List;

/**
 * 六条边的方向。**索引即边序号，全模块唯一。**
 *
 * <p>序为顺时针 {@code E, SE, SW, W, NW, NE}，与 GSimulator 的 {@code riverMask} 位序一致。
 * <p>GSimulator 全仓有 8 份方向表（6 Java + 2 JS），归为两种互逆索引序，
 * 根因是两张主表都是 package-private、不导出 API，消费方只能复制。此处合并为唯一一份。
 */
public enum HexDirection {
  E(1, 0),
  SE(0, 1),
  SW(-1, 1),
  W(-1, 0),
  NW(0, -1),
  NE(1, -1);

  private final int dq;
  private final int dr;

  HexDirection(int dq, int dr) {
    this.dq = dq;
    this.dr = dr;
  }

  public int dq() {
    return dq;
  }

  public int dr() {
    return dr;
  }

  /** 反向边。由枚举序保证（恰隔 3 项），不是巧合。 */
  public HexDirection opposite() {
    return ALL.get((ordinal() + 3) % 6);
  }

  /** 顺时针下一方向。 */
  public HexDirection next() {
    return ALL.get((ordinal() + 1) % 6);
  }

  /** 逆时针下一方向。 */
  public HexDirection prev() {
    return ALL.get((ordinal() + 5) % 6);
  }

  /** 按枚举序的全部方向。 */
  public static final List<HexDirection> ALL = List.of(values());
}
```

- [ ] **Step 3: 写 `HexGrid`**

```java
package io.mosire.simos.map.hex;

import java.util.Map;
import java.util.Set;

/**
 * 六边形格容器。格的身份是 {@link HexCoord}，不是字符串键。
 *
 * <p>★ 没有 {@code gridSize} 字段：GSimulator 那个字段只做构造期范围校验、不参与取格，
 * 且到处写死 30 而真实生成半径是 80 —— 范围由本类的 {@code minQ()/maxQ()/minR()/maxR()} 导出。
 */
public final class HexGrid {
  private final Map<HexCoord, ?> cells; // 具体化见 Task 5（HexCell 由 map 包提供）
  // …Task 5 落地后本类改为持有 Map<HexCoord, HexCell>；本任务先只做坐标运算部分
}
```

★ **本任务只交付坐标运算**。`HexGrid` 的**内容类型**是 `HexCell`，而 `HexCell` 在 Task 5 ——
⇒ 本任务先建 `HexGrid` 的**纯几何部分**（范围导出、邻域遍历、半径内枚举），
**不引入 `HexCell`**；Task 5 再补内容访问（`cell(HexCoord)` / `with(...)`）。

- [ ] **Step 4: 写用例**

```
HexCoordTest
  - toStringAndParseRoundTrip          : 冻结样例 (0,0) (0,-1) (-3,7) → toString → parse → equals
  - parseRejectsMalformed             : "" / "_" / "1_" / "_2" / "a_b" → IllegalArgumentException
  - sAxisInvariant                    : 遍历 q,r ∈ [-20,20]，断言 q + r + s() == 0
  - distanceIsSymmetricAndZeroOnSelf  : ∀ a,b : d(a,b) == d(b,a) 且 d(a,a) == 0
  - distanceMatchesCubeFormula        : 与独立写的 cube 公式逐点对拍（消除同源错误）
  - distanceOnKnownPairs              : 手算种子：d((0,0),(1,0))==1, d((0,0),(2,-1))==2, d((0,0),(-3,3))==3
  - neighborIsInvolutive              : ∀ d : a.neighbor(d).neighbor(d.opposite()).equals(a)
  - neighborsAreSixDistinctAtDistance1: 6 个邻格互不相同，且到中心距离恒为 1
  - compareToIsTotalOrder             : 先 q 后 r；与 (q,r) 字典序一致

HexDirectionTest
  - oppositeIsInvolution              : ∀ d : d.opposite().opposite() == d
  - oppositeIsNotSelf                 : ∀ d : d.opposite() != d      ← 钉住"隔 3 项"不是"隔 0 项"
  - nextAndPrevAreInverse             : ∀ d : d.next().prev() == d
  - allSixDistinctAndCoversEnum       : ALL.size()==6 且 equals values()
  - offsetsMatchFrozenTable           : ★ 冻结 6 行 (E,1,0) (SE,0,1) (SW,-1,1) (W,-1,0) (NW,0,-1) (NE,1,-1)
                                         —— 这一条是"单一方向表"的**唯一真值锚**，改序必红
  - neighborOffsetsAreAllDistinct     : 6 个 (dq,dr) 互不相同
  - everyNeighborIsAtDistanceOne      : ∀ d : new HexCoord(0,0).neighbor(d).distanceTo(new HexCoord(0,0)) == 1

HexGridTest
  - boundsDerivedFromCells            : 空集合的 min/max 抛 IllegalStateException；
                                        给 {(0,0),(3,-2),(-1,5)} → minQ=-1 maxQ=3 minR=-2 maxR=5
  - cellsWithinRadiusIsClosedBall     : radius=0 → 1 格；radius=1 → 7 格；radius=2 → 19 格
  - cellsWithinRadiusAllInRange       : ∀ c : c.distanceTo(center) <= radius
```

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `opposite()` 的 `+3` 改成 `+0` | **红** | `oppositeIsNotSelf` 不是装饰 |
| `HEX_DIRS` 冻结表里把 `SE` 与 `SW` 的偏移对调 | **红** | `offsetsMatchFrozenTable` 真的钉住了序 |
| `distanceTo` 的 `/ 2` 删掉 | **红** | 距离公式的 `/2` 不是装饰 |
| `neighbors()` 改成返回 5 个 | **红** | `neighborsAreSixDistinctAtDistance1` 有判别力 |

**变异体自证**：每条变异**读完测试结果之前**先比 `md5(变异产物) ≠ md5(原件参照)`。

- [ ] **Step 6: 跑门禁并提交**

```bash
./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='Hex*Test' test
./mvnw -q -pl simos-map spotless:apply
git add simos-map/src/main/java/io/mosire/simos/map/hex/ simos-map/src/test/java/io/mosire/simos/map/hex/
git diff --cached          # 先扫
git commit -m "feat(map): hex 包——HexCoord 身份、唯一方向表、网格几何"
```

---

### Task 2: `terrain` 包 —— 唯一词表

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainType.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainCatalog.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/terrain/TerrainCatalogTest.java`

**这个任务解的是 L9**（至少 9 份地形词表副本，两份在同一批 key 上完全分叉）。

- [ ] **Step 1: 写 `TerrainType`**

```java
package io.mosire.simos.map.terrain;

/**
 * 地形类型。
 *
 * <p>★ 字段取自 GSimulator 的实测等价类（{@code MapData.TerrainType}，7 字段），
 * **不是总纲 §5.1 写的 {@code color/height/pass/name}** —— GSimulator 全仓无 {@code height}/{@code pass}，
 * 且"海拔"是**格子**的属性不是**地形类型**的属性（见 spec §八 第 1 条）。
 */
public record TerrainType(
    String name, String color, int food, int gold, int stone, int moveCost, String description) {

  public TerrainType {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不得为空白");
    if (color == null || !color.matches("#[0-9A-Fa-f]{6}")) {
      throw new IllegalArgumentException("color 必须是 #RRGGBB 形式: " + color);
    }
    if (moveCost < 1) throw new IllegalArgumentException("moveCost 必须 >= 1: " + moveCost);
    if (food < 0 || gold < 0 || stone < 0) throw new IllegalArgumentException("产出不得为负");
  }
}
```

- [ ] **Step 2: 写 `TerrainCatalog`（★ 唯一词表）**

**9 项 key 固定为**（实测落盘数据用的就是这一组）：
`water / lowland / plains / hills / mountain / forest / swamp / desert / tundra`。

★ **9 行具体数值执行期从 GSimulator 现读，本计划故意不给** —— 控制器**没有实测过**每一个数，
写进来就是编造。读取程序见 Step 3。

```java
package io.mosire.simos.map.terrain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ **全模块唯一的地形词表。**
 *
 * <p>GSimulator 有至少 9 份互不相同的副本，其中两份在同一批 key 上完全分叉
 * （{@code plains} 在 A 里是"平原"/绿/3,1，在 B 里是"山区"/土黄/2,2），
 * 且 {@code ContourQueryEngine.terrainColor} 的兜底色正是 A 的平原绿 —— 串味铁证。
 */
public final class TerrainCatalog {

  /** 9 项的 key，**顺序即落盘顺序**。 */
  public static final java.util.List<String> KEYS =
      java.util.List.of("water", "lowland", "plains", "hills", "mountain", "forest", "swamp", "desert", "tundra");

  /**
   * ★ 唯一的默认词表。
   *
   * <p>★ **保序**：GSimulator 的 {@code Map.copyOf} 会打乱迭代序，使同一份表在两个存档里顺序不同，
   * 字节级往返因此不成立。此处用 {@link LinkedHashMap} 且**不 copyOf**。
   */
  public static Map<String, TerrainType> defaults() {
    Map<String, TerrainType> m = new LinkedHashMap<>();
    // ← 9 行在此，执行期从 GSimulator 现读（见 Step 3）
    return java.util.Collections.unmodifiableMap(m);
  }

  /** 按 key 取；不存在即抛，**不兜底**（兜底正是 GSimulator 串味的来源）。 */
  public static TerrainType of(String key) {
    TerrainType t = defaults().get(key);
    if (t == null) throw new IllegalArgumentException("未知地形类型: " + key);
    return t;
  }
}
```

- [ ] **Step 3: ★ 现读 GSimulator 的 9 行数值**

**不要照抄本计划的任何数**。按此程序现读并**把出处记进报告**：

```bash
cd ~/DevMosire/GSimulator
git grep -n "defaultTerrainTypes" -- '*.java'      # 词表 B 的定义处与全部副本
git grep -n "TerrainType.defaults" -- '*.java'     # 词表 A 的定义处
git grep -n "#6CC261" -- .                          # 串味的兜底色（A 的平原绿）
```

**★ `plains` 的处置（spec §6.1 的裁决）**：B 里 `plains` 的 `name` 是"**山区**"、色 `#B8A88A`、
产出 2,2 —— 这是**历史命名事故**。新表里 `plains` 必须是**平原**。
**其余 8 项的数值照 B 抄**（B 是实测落盘的那一份），`plains` 的 name/color/产出按"平原"重定，
**重定的取值必须在报告里明写为"本任务新定，非来自 GSimulator"** —— 与抄来的值分开标。

- [ ] **Step 4: 写用例**

```
TerrainCatalogTest
  - catalogHasExactlyNineKeys             : KEYS.size() == 9
  - defaultsKeySetEqualsKeys              : defaults().keySet() 与 KEYS **顺序**一致   ← 钉保序
  - defaultsIterationOrderIsStable        : 连调两次 defaults()，key 序逐项相同
  - defaultsIsUnmodifiable                : put → UnsupportedOperationException
  - ofThrowsOnUnknownKey                  : of("nope") → IllegalArgumentException，消息含 "未知地形类型"
  - ofNeverFallsBack                       : ★ 断言 of() 里**没有** default 分支 —— 用变异证明（Step 5）
  - everyTypeHasDistinctNameAndColor      : 9 项 name 两两不同、color 两两不同
  - everyColorMatchesHexPattern           : 9 项全过 #RRGGBB
  - plainsIsPlainsNotMountains            : ★ plains 的 name **不含**"山"            ← 钉住命名事故已修
  - plainsGreenIsNotTheOldFallback        : ★ plains.color != "#6CC261"            ← 见下
  - everyTypeIsConstructible              : 9 项都能构造（构造期校验不误伤）
```

★ **`plainsGreenIsNotTheOldFallback` 的用意**：`#6CC261` 是**词表 A** 的平原绿，
它出现在 `ContourQueryEngine.terrainColor` 的 `default` 分支里 —— **跨词表串味的物证**。
新表**不得**再出现这个值。

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `of()` 加一个 `return defaults().get("plains")` 兜底 | **红** | `ofNeverFallsBack` 不是装饰 |
| `defaults()` 改用 `Map.copyOf` | **红** | `defaultsIterationOrderIsStable` / `defaultsKeySetEqualsKeys` 真的钉住了保序 |
| 把 `plains` 的 name 改回"山区" | **红** | `plainsIsPlainsNotMountains` 有判别力 |
| 删掉一项（8 项） | **红** | `catalogHasExactlyNineKeys` 有判别力 |

- [ ] **Step 6: 跑门禁并提交**

同 Task 1 的形制，路径换成 `terrain/`，提交信息
`feat(map): terrain 包——唯一地形词表（9 项，保序）`。

---

### Task 3: `region` 包 —— 权威区域 + 派生边界 + 反向索引

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/region/RegionId.java` `RegionMeta.java` `Region.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/region/RegionBoundary.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/region/RegionIndex.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/region/RegionTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/region/RegionIndexTest.java`

**这个任务解的是 L4 与 L5**。

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
 * 权威区域：一组 hex 的**命名**集合。
 *
 * <p>★ 边界**不在这里** —— 它是派生物，见 {@link RegionBoundary}。
 * <p>★ GSimulator 的 {@code Province} 既无 name 字段（名字是 map 的键）也无边界字段，此处都补上。
 */
public record Region(RegionId id, String name, Set<HexCoord> hexes, RegionMeta meta) {

  public Region {
    if (id == null) throw new IllegalArgumentException("id 不得为 null");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不得为空白");
    hexes = Set.copyOf(hexes);          // 不可变；注意 Set.copyOf 不保序，故本类型不依赖迭代序
    if (meta == null) meta = RegionMeta.empty();
  }

  /** 是否含某格。**O(1)** —— GSimulator 是 List<String>.contains 线性扫描。 */
  public boolean contains(HexCoord c) {
    return hexes.contains(c);
  }

  public Region withHexes(Set<HexCoord> newHexes) {
    return new Region(id, name, newHexes, meta);
  }

  public Region withName(String newName) {
    return new Region(id, newName, hexes, meta);
  }
}
```

★ **`hexes` 用 `Set.copyOf`（不保序）是有意的**：它是**集合语义**，迭代序不该被依赖。
**需要保序的只有 `TerrainCatalog`**（落盘的是它）。两处的理由不同，**不要统一**。

- [ ] **Step 2: 写 `RegionBoundary`（★ 派生物）**

```java
package io.mosire.simos.map.region;

import io.mosire.simos.map.hex.HexCoord;
import java.util.List;

/**
 * 区域的闭环边界。**由 {@link Region#hexes()} 派生，可随时重算**。
 *
 * <p>★ **不进 GameMap、不进变更集** —— 一个可随时重算的东西不该是权威状态。
 * GSimulator 把渲染缓存塞进 {@code MapData}，后果是它整份拷贝而非增量，且那份 {@code boundary}
 * 已被标注 {@code deprecated}。
 *
 * @param rings 每一条闭环。外环 + 可能的内环（洞）。
 */
public record RegionBoundary(List<List<HexCoord>> rings) {
  public RegionBoundary {
    rings = rings.stream().map(List::copyOf).toList();
  }

  /** 从 hex 集合计算边界。 */
  public static RegionBoundary of(Region region) {
    // 实现：对每个边界格收集其朝外的边，串联成环
  }
}
```

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
  - hexesIsImmutable                     : 改入参 Set → 不影响 Region
  - containsIsSetBased                   : 含与不含各一例
  - equalityIsComponentwise              : 同 id 同 name 同 hexes 同 meta → equal；任一不同 → 不等
  - withHexesKeepsIdAndName              : ★ id/name 不随内容变            ← 铁律 1
  - withNameKeepsId                      : ★ 改名不改身份                  ← 铁律 1
  - metaDefaultsToEmptyWhenNull

RegionBoundaryTest
  - singleHexRingHasSixVertices          : 单格边界 6 个顶点
  - twoAdjacentHexesShareOneRing         : 相邻两格 → **一个**环（不是两个）
  - nonContiguousHexesGiveMultipleRings  : 两簇不连通 → **两个**环
  - boundaryIsRecomputable               : 同 Region 算两次 equals
  - boundaryIsDerivedNotStored           : ★ 反射断言 Region **不含** boundary 组件

RegionIndexTest
  - regionOfIsConstantTime               : ★ 见下
  - overlappingRegionsResolveDeterministically : 同输入两次结果相同
  - unknownHexReturnsNull
  # ★ 注意：本任务**不写** "GameMap 不含 RegionIndex" 那条断言 —— GameMap 在 Task 5 才存在。
  #   该断言归 Task 5 的 `regionIndexIsDerivedNotStored`，**只写一处**，不要两处重复。
```

★ **`regionOfIsConstantTime` 怎么写才有判别力**：**不要**测时间（不稳）。
用**结构性断言**：索引的构造是 O(n)，`regionOf` 只做一次 `Map.get`。
可行的写法：构造 1000 hex × 100 region 与 1 hex × 1 region 两组，
断言 `regionOf` 的结果**不依赖索引里其他条目的数量** —— 即
`index1000.regionOf(c).equals(index1.regionOf(c))` 对同一个 `c` 成立，
**且**用一个 `CountingMap` 包一层断言 `regionOf` 只触发 **1 次** `get` 调用。

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `Region.withHexes` 里改成 `new Region(new RegionId(name), ...)`（用 name 当 id） | **红** | `withHexesKeepsIdAndName` 真的钉住了"ID 是身份" |
| `RegionIndex.regionOf` 改成遍历全部 regions 线性找 | **红** | 计数断言有判别力 |
| `Region` 加一个 `boundary` 组件 | **红** | `boundaryIsDerivedNotStored` 有判别力 |

- [ ] **Step 6: 跑门禁并提交**

提交信息 `feat(map): region 包——权威 Region + 派生边界 + O(1) 归属索引`。

---

### Task 4: `pathway` 包 —— 边与线

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/pathway/EdgeRef.java` `EdgeTags.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/pathway/PathwayId.java` `Pathway.java` `PathwayGroup.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/pathway/EdgeRefTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/pathway/PathwayTest.java`

**这个任务解的是 L2，并落地待决项 3**（连通性稳定 ID）。

- [ ] **Step 1: 写 `EdgeRef`（★ 规范序在构造期完成）**

```java
package io.mosire.simos.map.pathway;

import io.mosire.simos.map.hex.HexCoord;

/**
 * 无向边。**身份就是那两格**，不需要 ID。
 *
 * <p>★ 构造期完成规范排序 ⇒ {@code (a,b)} 与 {@code (b,a)} 恒为同一对象。
 * GSimulator 有一份 {@code edgeKey} 手写逻辑的**4 份实现**（Java 2 + JS 1 + 1 处内联），
 * 其中前端那份的注释自陈 <i>"Must stay in sync with MapData.edgeKey() in Java"</i> ——
 * 靠注释维系的同步不是同步。此处由类型系统保证。
 */
public record EdgeRef(HexCoord a, HexCoord b) implements Comparable<EdgeRef> {

  public EdgeRef {
    if (a == null || b == null) throw new IllegalArgumentException("边的端点不得为 null");
    if (a.equals(b)) throw new IllegalArgumentException("边不能自环: " + a);
    if (a.compareTo(b) > 0) {
      HexCoord t = a;
      a = b;
      b = t;
    }
  }

  /** 规范字符串形式，**只在 JSON 边界使用**。 */
  @Override
  public String toString() {
    return a + "|" + b;
  }

  @Override
  public int compareTo(EdgeRef o) {
    int c = a.compareTo(o.a);
    return c != 0 ? c : b.compareTo(o.b);
  }
}
```

- [ ] **Step 2: 写 `EdgeTags` / `PathwayId` / `PathwayGroup`**

```java
/** 边上的标注：属于哪个 pathway 组的实例、带哪些属性。 */
public record EdgeTags(java.util.Map<String, java.util.Map<String, Object>> byPathway) {
  public EdgeTags { byPathway = /* 保序不可变 */; }
}
```

```java
/**
 * 线的稳定身份。
 *
 * <p>★ **一旦分配即持久化，不由内容派生** —— 内容派生会让"改一个中间节点"变成"换了一条河"。
 * 生成期由 {@code (seed, 序号)} 确定性派生（保证同种子可复现）；编辑期由 Command 分配并持久化；
 * 分裂出的新段拿新 ID，缩短不改 ID。
 */
public record PathwayId(String value) {
  public PathwayId {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("PathwayId 不得为空白");
  }
}
```

- [ ] **Step 3: 写 `Pathway`**

```java
/**
 * 一条**极大简单链**：两端是端点或分支点。
 *
 * <p>★ **分支点即端点** —— 度数 >= 3 的格是分支点，线在分支处断开 ⇒ "分支是独立的线"。
 * <p>★ GSimulator 的线段**只有 groupId**（所有河流共享 {@code "river"}），链的身份是**返回列表的下标**
 * ⇒ 总纲 §5.1 要的"单条连通性线段可寻址"当前做不到。本类型就是那个承载结构。
 */
public record Pathway(PathwayId id, String name, String groupId,
                      java.util.List<EdgeRef> edges,
                      java.util.Map<String, Object> props) {

  public Pathway {
    if (id == null) throw new IllegalArgumentException("id 不得为 null");
    if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("groupId 不得为空白");
    edges = java.util.List.copyOf(edges);
    props = /* 保序不可变 */;
  }

  /** 两端端点（链的两头）。闭环的处置见 spec §5.2：以规范序最小的格作锚。 */
  public HexCoord start() { /* … */ }

  public HexCoord end() { /* … */ }

  public int length() { return edges.size(); }
}
```

- [ ] **Step 4: 写用例**

```
EdgeRefTest
  - orderIsCanonical                      : new EdgeRef(a,b).equals(new EdgeRef(b,a))
  - canonicalFormTouchesASortedFields     : 反射断言 a.compareTo(b) <= 0 恒成立
  - selfLoopIsRejected                    : new EdgeRef(a,a) → IllegalArgumentException
  - nullEndpointIsRejected
  - toStringIsStable                      : 同一无向边的两个构造方向 toString **相同**
  - compareToIsTotalOrder

PathwayTest
  - constructorRejectsBlankGroupIdAndNullId
  - edgesIsImmutable
  - lengthIsEdgeCount
  - startAndEndAreTheChainEnds            : 三格链 → start/end 是两头，不是中间
  - ★ idsArePersistedNotDerived           : 见 Step 5
  - equalityIsComponentwise

EdgeTagsTest
  - preservesInsertionOrder               : ★ 保序（前端曾因 props 被抹平而丢数据）
  - isImmutable
```

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| 删掉 `EdgeRef` 构造期的 `a.compareTo(b) > 0` 交换 | **红** | `orderIsCanonical` 真的钉住了规范序 |
| 删掉自环校验 | **红** | `selfLoopIsRejected` 不是装饰 |
| `Pathway` 加一个派生 id 的方法并让构造器**忽略传入 id** | **红** | ★ `idsArePersistedNotDerived` 有判别力 |
| `EdgeTags` 改用 `Map.copyOf` | **红** | `preservesInsertionOrder` 真的钉住了保序 |

★ **`idsArePersistedNotDerived` 怎么写**：构造两条 `edges` 相同的 `Pathway`，
**只让 `id` 不同** ⇒ 断言**不相等**。
再把其中一条的一个中间 `EdgeRef` 换掉、**保持 id 不变** ⇒ 断言 `id()` **仍然相同**。
两条合起来才钉住"id 是持久身份、不随内容漂移"。

- [ ] **Step 6: 跑门禁并提交**

提交信息 `feat(map): pathway 包——规范序无向边与稳定 ID 的线`。

---

### Task 5: `map` 包 —— `HexCell` / `City` / `GameMap`

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/HexCell.java` `CityId.java` `City.java` `GameMap.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/generate/GenerationSpec.java`（★ **只建骨架**）
- Modify: `simos-map/src/main/java/io/mosire/simos/map/hex/HexGrid.java`（补内容访问）

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
public record CityId(String value) { /* 非空白，同 RegionId 形制 */ }

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

  /** 派生：某区域的闭环边界。**不进组件、不进变更集**。 */
  public RegionBoundary boundaryOf(RegionId id) { /* … */ }
}
```

★ **`spec` 的类型是 `GenerationSpec` 骨架**（本任务 Step 1 前建）——
本任务先把 `spec` 声明为**可空**（`GameMap.empty()` 给 `null`），
**Task 8 落地完整参数面时收紧为非 null**。**在报告里明写这个临时放宽**，不要让它悄悄留下。

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
```

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| 给 `HexCell` 加回一个 `edgeTags` 组件 | **红** | `hasNoConnectivityField` 真会响（钉 L2） |
| 给 `GameMap` 加回 `gridSize` | **红** | `componentCountIsExactlyEight` 与 `noGridSizeNoHexOrientation` 真会响 |
| `empty()` 里改用 `Map.copyOf` | **红** | `mapsAreInsertionOrdered` 真的钉住了保序 |
| `withHexes` 里顺手把 `regions` 也改了 | **红** | `withMethodsPreserveOtherComponents` 有判别力 |

- [ ] **Step 6: 跑门禁并提交**

提交信息 `feat(map): GameMap——8 组件状态，删死字段与缓存，加海拔与生成参数`。

---

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

  /** 逐组件重建。铁律 5 的原文。 */
  public static GameMap apply(MapChangeSet cs, GameMap base) { /* … */ }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() { /* … */ }
}
```

★ **`spec` 不进变更集**（它是生成输入，地图生成后只是溯源信息），
故 `MapChangeSet` 是 **7 个组件**而 `GameMap` 是 **8 个** —— 这个不对称是**有意的**，
但**必须被测试显式钉住**（见 Task 7），否则它会变成下一个"漂移"。

★ **`between` 的 key 类型**：`FieldDelta` 的 key 是 `String`，而 `GameMap` 的 map key 是
`HexCoord`/`RegionId`/… ⇒ 需要一个 `keyOf` 转换。**它必须是 `toString()`**（即 `HexCoord` 的 `"q_r"`），
且 `apply` 侧用对应的 `parse` 还原。**这是 `"q_r"` 唯一被允许出现的地方。**

- [ ] **Step 3: 写用例**

```
MapChangeSetTest（基础部分；★ 反射枚举部分在 Task 7）
  - betweenIdenticalIsAllUnchanged    : ★ 全 Unchanged，且 isEmpty() 为 true
  - betweenDetectsAddedHex            : Upsert 含新 key
  - betweenDetectsRemovedHex          : Remove 含旧 key
  - betweenDetectsChangedHexValue     : ★ 同 key 不同 value → Upsert（不是 Unchanged）
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
| `between` 里 `terrainTypes` 的比较整个删掉（默认 Unchanged） | **红** | ★ **该组件漂移出去时测试会响** —— 这是铁律 5 的核心 |
| `between(x,x)` 改成返回 `null` | **红** | `betweenIdenticalIsAllUnchanged` 不是装饰 |
| `isEmpty()` 改成 `hexes.changed()`（只看一个组件） | **红** | ★ `emptyDiffStillEntersApply` 有判别力 |

★ **`emptyDiffStillEntersApply` 的来历**：侦察 B 实测，GSimulator 的
`MapResolver:76-82` **只在 `!diff.isEmpty()` 时才调 `applyDiff`**，
而 `isEmpty()` 也排除了 edges ⇒ "只改了一条边"产生空 diff、**根本不进 apply**。
**这是 L1 的第四个叠加成因。** 本用例钉住"空 diff 也必须能被 apply 且返回 base"。

- [ ] **Step 5: 跑门禁并提交**

提交信息 `feat(map): change 包——与 GameMap 组件一一对应的变更集`。

---

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

- [ ] **Step 3: ★ V1~V5 的"改前"必须真的跑在当前 HEAD 上**

若你先改了文件才想跑"改前"，用 `git stash` 或 `git worktree` 取一份 HEAD 副本到 `/tmp`，
**不要在时间上撒谎**。报告里每条变异各带**改前一次、改后一次**的原始输出。

- [ ] **Step 4: 跑门禁并提交**

提交信息 `test(map): 反射把守的往返框架——组件漂移即红`。

---

### Task 8: `GenerationSpec` —— 参数面

**Files:**
- ★ **Modify**: `simos-map/src/main/java/io/mosire/simos/map/generate/GenerationSpec.java`
  （Task 5 建的是**只有 `seed` 的骨架**，本任务**扩写为完整参数面**）
- Create: `simos-map/src/main/java/io/mosire/simos/map/generate/NoiseBands.java` `RidgeParams.java` `FragmentParams.java`
- Modify: `simos-map/src/main/java/io/mosire/simos/map/GameMap.java`（★ **把 `spec` 从可空收紧为非 null**）
- Test: `simos-map/src/test/java/io/mosire/simos/map/generate/GenerationSpecTest.java`

**这个任务解的是 L8**（参数面）。

★ **收紧要连带改的地方**（dispatch 前冲突扫描查出）：`GameMap.empty()` 与 `MapChangeSet` 的
`apply` 重建里都可能写着 `null` spec；本任务必须**逐处改掉**，并**加一条用例**
`specIsNeverNullAfterTask8`（`GameMap.empty().spec()` 非 null）证明收紧真的落地了。

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
    if (mainRidges < 1) throw new IllegalArgumentException("mainRidges 必须 >= 1: " + mainRidges);   // ← 不夹取
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
  - equalityIsComponentwise
```

- [ ] **Step 4: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `mainRidges` 校验改成 `Math.max(1, Math.min(mainRidges, 2))`（即 GSimulator 的静默夹取） | **红** | `mainRidgesFiveThrows` 有判别力 |
| 删掉负差值校验 | **红** | `negativeFragmentDifferenceThrows` 不是装饰 |
| 加回 `worldId` 组件 | **红** | `noWorldIdNoCoastRoughness` 真会响 |

- [ ] **Step 5: 跑门禁并提交**

提交信息 `feat(map): GenerationSpec——参数面，静默夹取改构造期校验`。

---

### Task 9: `TerrainClassifier` —— 9 项全覆盖

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/generate/TerrainClassifier.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/generate/TerrainClassifierTest.java`

**这个任务解的是 L9 的后半**：GSimulator 的 `classify` **只产出 6 种**，
`forest`/`desert`/`tundra` **永远产生不出来** —— 一份产不出来的词表是谎话。

- [ ] **Step 1: 写 `TerrainClassifier`**

```java
package io.mosire.simos.map.generate;

/**
 * 从海拔与噪声判定地形。**必须覆盖 TerrainCatalog 的全部 9 项。**
 *
 * <p>★ GSimulator 的等价物只产出 6 种（mountain/hills/plains/lowland/swamp/water），
 * {@code forest}/{@code desert}/{@code tundra} 在词表里但产出不来。
 */
public final class TerrainClassifier {

  /** 判定。输入的 humidity 与 temperature 都在 [0,1]。 */
  public static String classify(double height, double humidity, double temperature) { /* … */ }
}
```

★ **判定阈值的具体取值执行期从 GSimulator 现读并补齐缺的三项** —— 前 6 项照抄，
后 3 项（forest/desert/tundra）**是新定的**，报告里分开标。
`forest` 用湿度、`desert` 用湿度低+温度高、`tundra` 用温度低 —— **这是 spec §6.2 的方向**，
具体阈值由执行者定并在报告里说明依据。

- [ ] **Step 2: 写用例**

```
TerrainClassifierTest
  - ★ everyCatalogKeyIsProducible       : 遍历 TerrainCatalog.KEYS，断言**每一项**都存在
                                          至少一组 (height,humidity,temperature) 判出它
                                          ← 这是本任务的核心断言，直接钉住"词表不是谎话"
  - classifyNeverReturnsUnknownKey      : 扫一个三维网格，断言返回值恒在 KEYS 里（**不兜底**）
  - waterIsLowest                       : height 极低 → water
  - mountainIsHighest                   : height 极高 → mountain
  - forestNeedsHumidity                 : 高湿度 + 中海拔 → forest（且**不是** plain）
  - desertNeedsDrynessAndHeat           : 低湿度 + 高温 → desert
  - tundraNeedsCold                     : 低温度 → tundra
  - classifyIsDeterministic             : 同输入两次同输出
  - classifyIsTotal                     : 全域有定义，不抛异常
```

- [ ] **Step 3: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| 删掉 `forest` 的分支（退回 GSimulator 的 6 项） | **红** | ★ `everyCatalogKeyIsProducible` 有判别力 —— 这条正是 L9 的守卫 |
| 删掉 `desert` 的分支 | **红** | 同上 |
| `classify` 末尾加一个 `default -> return "plains"` 兜底 | **红** | `classifyNeverReturnsUnknownKey` 的"不兜底"半 |
| 让 `classify` 对某段输入抛异常 | **红** | `classifyIsTotal` 有判别力 |

- [ ] **Step 4: 跑门禁并提交**

提交信息 `feat(map): TerrainClassifier——9 项地形全覆盖`。

---

### Task 10: `MapGenerator` —— 从头生成 + seed 复现

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/generate/MapGenerator.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/generate/MapGeneratorTest.java`

**这个任务解的是 L7 的其余部分**（seed 落盘 + 两条入口同路 + 地形只算一遍）。

- [ ] **Step 1: 写 `MapGenerator`**

```java
package io.mosire.simos.map.generate;

/**
 * 从头生成一张地图。
 *
 * <p>★ 与 GSimulator 的差别：
 * <ul>
 *   <li>入参 seed **被记录**（GSimulator 写进 contour 的是 {@code rng.nextLong()} 派生值，入参本身丢失）
 *   <li>生成**只跑一遍**（GSimulator 的 HTTP 入口在同一请求里算了两遍地形）
 *   <li>产出的 {@link GameMap} **自带 {@code spec}**，复现不需任何外部输入
 *   <li>**只此一条路径** —— GSimulator 的 MCP 路径不写 contour，故 MCP 生成的地图不可复现
 * </ul>
 */
public final class MapGenerator {

  /** 由 spec 完全决定。**同 spec 必然同图。** */
  public static GameMap generate(GenerationSpec spec) { /* … */ }
}
```

★ **`generate` 必须没有别的形参** —— 这是"参数面"的落地：**能影响结果的每一个输入都在 `spec` 里**。
**用一条反射/签名用例钉住它**（见 Step 2 的 `generateHasExactlyOneParameter`）。

- [ ] **Step 2: 写用例**

```
MapGeneratorTest
  - ★ sameSeedGivesIdenticalMap          : generate(spec(seed=42)) 两次 → equals   ← L7 的核心
  - ★ differentSeedGivesDifferentMap     : seed 42 vs 43 → 不等
  - ★ specIsCarriedOnTheResult           : map.spec().seed() == 42
  - ★ generateHasExactlyOneParameter     : 反射断言 generate 只有 1 个形参（参数面的守卫）
  - radiusGivesExpectedHexCount          : radius=1 → 7 格；radius=2 → 19 格
  - everyHexHasFiniteHeightInRange       : 全部 height ∈ [0,1]
  - everyHexTerrainIsInCatalog           : 全部 terrain ∈ TerrainCatalog.KEYS
  - terrainTypesComponentIsTheCatalog    : map.terrainTypes() 与 TerrainCatalog.defaults() 一致且**顺序一致**
  - generatedMapRoundTripsThroughChangeSet : between(empty, map) → apply → equals（接 Task 7）
  - ★ noSecondPathToGenerate             : git grep 断言全仓只有一个 MapGenerator.generate 调用面
```

- [ ] **Step 3: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `generate` 里用 `new Random()` 而不是从 `spec.seed()` 派生 | **红** | ★ `sameSeedGivesIdenticalMap` 有判别力（L7 核心） |
| 给 `generate` 加一个形参 | **红** | `generateHasExactlyOneParameter` 真会响 |
| 结果不带 `spec` | **红** | `specIsCarriedOnTheResult` 不是装饰 |
| `terrainTypes` 改用 `Map.copyOf` | **红** | `terrainTypesComponentIsTheCatalog` 的**顺序**半 |

- [ ] **Step 4: 跑门禁并提交**

提交信息 `feat(map): MapGenerator——单入口、seed 落盘、可复现`。

---

### Task 11: `RiverBuilder` —— 自动河流

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/generate/RiverBuilder.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/generate/RiverBuilderTest.java`

**这个功能 GSimulator 完全没有**（侦察 D 实测：全仓无任何按海拔造水系的代码；
"河"只是 edges 上的一个标签，`tracePathway` **只读不生成**，
`MapGenerator`/`ContourQueryEngine` 全文不产生任何 `"river"` 字符串）。

- [ ] **Step 1: 写 `RiverBuilder`**

```java
package io.mosire.simos.map.generate;

/**
 * 自动河流：按海拔从高到低生成水系。
 *
 * <p>★ GSimulator 没有这个功能。本实现复用 §五 的连通性系统：
 * 一条河就是一条 {@link Pathway}（{@code groupId = "river"}），
 * **单条可寻址**（有 {@link PathwayId}），**分支是独立的线**（分支点即端点）。
 */
public final class RiverBuilder {

  /**
   * 从 map 的海拔生成水系，返回新的 {@link MapChangeSet}（**不是新 GameMap**）。
   *
   * <p>★ 返回变更集而非整图 —— 编辑走铁律 2 的路径（Command → ChangeSet → Revision）。
   */
  public static MapChangeSet build(GameMap map, long seed) { /* … */ }
}
```

★ **确定性**：随机源从 `(seed, 起点 HexCoord)` 派生 ⇒ **同种子同结果**。

- [ ] **Step 2: 写用例**

```
RiverBuilderTest
  - riverStartsAtHighestHex            : 造一个单峰图，断言河的起点是最高格
  - riverNeverGoesUphill               : 沿途每一格的 height <= 前一格
  - riverEndsAtWaterOrBoundary         : 终点是 water 格或图边界
  - ★ riverIsAddressable               : 产出里每条河有**互不相同**的 PathwayId  ← 待决项 3 的验收
  - ★ branchesAreSeparatePathways      : 造一个带分支的输入，断言产出是**多条** Pathway（不是一条带分叉的）
  - ★ sameSeedGivesSameRivers          : 确定性
  - producesNoRiversOnFlatMap          : 全平图 → 空变更集（且 isEmpty() 而非 null）
  - edgesAreConsistentWithPathways     : ★ 每条 Pathway 的每一段 EdgeRef 都在 map.edges() 里有对应标注
  - doesNotMutateInput                 : 传入的 map 在调用后 equals 自身
```

- [ ] **Step 3: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| 起点改成随机格而不是最高格 | **红** | `riverStartsAtHighestHex` 有判别力 |
| 允许走上坡 | **红** | `riverNeverGoesUphill` 有判别力 |
| 所有河共用 `new PathwayId("river")` | **红** | ★ `riverIsAddressable` 有判别力 —— 这正是 GSimulator 的现状（所有河流共享 `"river"`） |
| 分支不切开（一条线带分叉） | **红** | `branchesAreSeparatePathways` 有判别力 |

- [ ] **Step 4: 跑门禁并提交**

提交信息 `feat(map): RiverBuilder——按海拔生成可寻址、可复现的水系`。

---

### Task 12: `RegionRandomizer` —— 框选随机化

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/generate/RegionRandomizer.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/generate/RegionRandomizerTest.java`

**这个功能 GSimulator 没有**（侦察 D 实测：最接近的 `LassoProcessor` 只做几何求内侧，
★ **全文零随机数调用**；地形由调用方传单一常量）。

- [ ] **Step 1: 写 `RegionRandomizer`**

```java
package io.mosire.simos.map.generate;

/**
 * 框选随机化：把一个区域内的格按占比随机重分配给两种地形。
 *
 * <p>★ GSimulator 没有这个功能。
 * <p>★ 确定性：随机源从 {@code (seed, RegionId)} 派生 ⇒ 同种子同结果，可复现、可往返测试。
 * <p>★ 返回变更集（铁律 2）。
 */
public final class RegionRandomizer {

  public static MapChangeSet randomize(
      GameMap map, RegionId region, String terrainA, String terrainB, double ratioA, long seed) {
    /* … */
  }
}
```

- [ ] **Step 2: 写用例**

```
RegionRandomizerTest
  - ★ ratioIsRespectedStatistically     : ratioA=0.5，1000 次抽样，占比落在 0.5 ± 0.05
  - ★ sameSeedGivesSameResult           : 确定性
  - differentSeedGivesDifferentResult   : 两个种子结果不同
  - ★ onlyTargetRegionIsTouched         : 区域外的格**一格都没变**
  - onlyTwoTerrainTypesAreUsed          : 产出的地形只含 terrainA 与 terrainB
  - ratioZeroGivesAllB                     : ratioA=0 → 全是 B
  - ratioOneGivesAllA
  - rejectsRatioOutOfRange              : ratioA=-0.1 / 1.1 → IllegalArgumentException
  - rejectsUnknownTerrainKey            : terrainA="nope" → IllegalArgumentException（经 TerrainCatalog.of）
  - returnsEmptyChangeSetOnEmptyRegion  : 空区域 → isEmpty()，不是 null
  - doesNotMutateInput
```

- [ ] **Step 3: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| 忽略 `seed`，用 `new Random()` | **红** | `sameSeedGivesSameResult` 有判别力 |
| 忽略 `region`，改全图 | **红** | `onlyTargetRegionIsTouched` 有判别力 |
| 忽略 `ratioA`，恒选 A | **红** | `ratioIsRespectedStatistically` 与 `ratioZeroGivesAllB` 有判别力 |
| 删掉 ratio 范围校验 | **红** | `rejectsRatioOutOfRange` 不是装饰 |

- [ ] **Step 4: 跑门禁并提交**

提交信息 `feat(map): RegionRandomizer——确定性框选随机化`。

---

### Task 13: `MapResolver` —— `map:` 寻址

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/resolve/MapResolver.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/resolve/MapResolverTest.java`

- [ ] **Step 1: 写 `MapResolver`**

实现 `simos-util` 的 `Resolver` SPI：`namespace()` 返回 `"map"`；
`resolve(Address, ResolveContext)` 认 `map:<mapId>`、`map:<mapId>:hex:<q>_<r>`、
`map:<mapId>:region:<regionId>`、`map:<mapId>:city:<cityId>` 四类地址。

★ **`map:<mapId>` 的第一段是什么** —— M1 的 `Address` 有四段（`Namespace`/`Entity`/`Index`/`Property`），
**按 M1 spec §3.5 判定**（执行者读 M1 spec 的 `AddressSegment` 判定规则），
**不要凭直觉**。判定结果写进报告。

- [ ] **Step 2: 写用例**

```
MapResolverTest
  - resolvesHexByAddress                : map:m1:hex:0_0 → 命中 HexCoord(0,0)
  - resolvesRegionByAddress
  - resolvesCityByAddress
  - resolvesMapItself
  - ★ unknownHexGivesEmptyNotException  : 不存在的坐标 → 空候选，**不抛**
  - ★ wrongNamespaceIsRejected          : map:… 之外的地址不由本解析器认领
  - ★ regionOfHexUsesTheIndex           : 见 Step 3（钉 O(1) 而非线性扫描）
  - malformedHexIndexIsRejected         : map:m1:hex:abc → 明确的错
  - resolverDoesNotDoIO                 : 源码断言：无 java.io / java.nio.file
```

- [ ] **Step 3: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `regionOf` 的查询改成遍历全部 regions 找 | **红** | ★ `regionOfHexUsesTheIndex` 有判别力（L5 的守卫） |
| 未知坐标改成抛异常 | **红** | `unknownHexGivesEmptyNotException` 有判别力 |
| `namespace()` 改成 `"mapx"` | **红** | 注册与解析的接缝 |
| 非法 index 静默返回空 | **红** | `malformedHexIndexIsRejected` 不是装饰 |

- [ ] **Step 4: 跑门禁并提交**

提交信息 `feat(map): MapResolver——map: 命名空间寻址`。

---

### Task 14: L1~L9 逐条守卫

**Files:**
- Create: `simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java`（或按缺陷分文件）

**这是 M2 判据的第一条**（"GSimulator 的 L1~L9 逐条有对应用例"）。
**逐个缺陷点名**，每条一行注释指向它的来源，**让"哪条守哪条"是可读的**。

- [ ] **Step 1: 写 L1~L9 的守卫（每条一个 `@Test`，命名带 `L1`~`L9`）**

| 缺陷 | 守卫用例 | 落在哪个已完成的类上 |
|---|---|---|
| **L1** 子节点写 `edges` 静默丢失 | `L1_edgesSurviveRoundTripOnNonRoot` | `MapChangeSet`（Task 6/7） |
| **L2** 双份连通性存储 | `L2_hexCellHasNoConnectivityField` | `HexCell`（Task 5） |
| **L3** 方向数组错位 | `L3_thereIsExactlyOneDirectionTable` | `HexDirection`（Task 1） |
| **L4** 三个 region 概念 | `L4_thereIsExactlyOneRegionType` | `region` 包（Task 3） |
| **L5** Province 归属线性扫描 | `L5_regionOfIsIndexedNotScanned` | `RegionIndex`（Task 3/13） |
| **L6** 坐标表述不一致 | `L6_hexCoordIsTheOnlyCoordinateType` | `HexCoord`（Task 1） |
| **L7** 无海拔无种子落盘 | `L7_heightAndSeedSurvivePersistence` | `HexCell` / `GenerationSpec`（Task 5/8/10） |
| **L8** 12 参数构造复制 | `L8_gameMapHasNoTwelveArgConstructor` | `GameMap`（Task 5） |
| **L9** 地形词表分裂 | `L9_thereIsExactlyOneTerrainCatalog` | `TerrainCatalog`（Task 2/9） |

★ **L1 必须包含一条非 root 的用例** —— GSimulator 只在**非 root** 丢数据
（root 走全量保存，**root 用例会掩盖它**）。

- [ ] **Step 2: 写用例（要点）**

```java
/** L3：全仓只有一张方向表。 */
@Test
void L3_thereIsExactlyOneDirectionTable() {
  // 反射断言 HexDirection 是 enum 且恰好 6 项
  // ★ 并断言不存在第二个 int[][] 方向常量 —— 用源码扫描：
  //   git grep -n "int\[\]\[\]" -- 'simos-map/src/main' 的命中集必须**不含**方向表形态
}

/** L6：HexCoord 是唯一坐标类型。 */
@Test
void L6_hexCoordIsTheOnlyCoordinateType() {
  // 源码断言："q_r" 这个拼接形式**只在 HexCoord.toString/parse 与 change 包的 keyOf/parseKey 出现**
  // 其余任何地方出现 ⇒ 红
}
```

★ **L3/L6 这类"全仓只有一份"的断言必须靠源码扫描**（`git grep`）而不是反射 ——
反射只能证明"当前这个类长这样"，证明不了"没有第二份"。
**源码扫描的用例要写在测试里**（读 `src/main` 目录树），**不要只写成报告里的一句话** ——
报告会失传，测试不会。

- [ ] **Step 3: ★ 护栏自证（G13）**

**逐条变异，每条证明它对应的 L 守卫会响**（9 条）：

| 变异 | 期望红的是 |
|---|---|
| 在 `MapChangeSet` 里把 `edges` 比较掉 | `L1_…` |
| 给 `HexCell` 加 `edgeTags` | `L2_…` |
| 在 `simos-map` 里加一个 `static final int[][] DIRS` | `L3_…` |
| 加第二个 region 类型 | `L4_…` |
| `RegionIndex.regionOf` 改线性 | `L5_…` |
| 某处手写 `q + "_" + r` | `L6_…` |
| 结果不带 `spec` | `L7_…` |
| 给 `GameMap` 加一个 12 参数的构造器 | `L8_…` |
| 加第二份地形表 | `L9_…` |

- [ ] **Step 4: 跑门禁并提交**

提交信息 `test(map): L1~L9 逐条守卫 + 逐条自证`。

---

### Task 15: M2 关账

- [ ] **Step 1: 全量门禁**

```bash
./mvnw clean verify
```
记：退出码、六模块结果、`simos-map` 的 `Tests run` 计数、`BugInstance size`。
**★ 注意 `mvn test` 不跑 SpotBugs。**

- [ ] **Step 2: 逐条核 M2 spec §1.2 的判据**

| # | 判据 | 证据 |
|---|---|---|
| 1 | L1~L9 逐条有对应用例 | Task 14 的 9 条，逐条给用例名 |
| 2 | 框选随机化与自动河流各有验收 | Task 11 / Task 12 的 `★` 用例 |
| 3 | `./mvnw clean verify` 绿 | Step 1 的原始输出 |
| 4 | 每条新护栏有故意违规用例自证 | Task 1~14 各自的自证表 |

- [ ] **Step 3: 更新 `CLAUDE.md` 的当前状态表**

M2 行从 `⬜ 未开始` 改为 `✅ 已完成`，并指向 M2 spec 与计划。

- [ ] **Step 4: 提交并报告**

★ **本任务的报告里必须包含"我未能核实的"清单** —— 宁可报"这条我核不了"，
也不要报一个没跑过的结论。

---

## 自审记录（writing-plans 的三项自查）

### 0. ★ dispatch 前冲突扫描（SDD 要求，逐对检查共享文件/接口的任务）

| 谁与谁共享 | 共享的东西 | 查到了什么 |
|---|---|---|
| Task 1 ↔ Task 5 | `HexGrid.java` | Task 1 建**纯几何**、Task 5 补内容访问。**已写进 Task 1 Step 3** —— Task 1 不引入 `HexCell`，故 Task 1 不受 Task 5 未落地影响。**一致** |
| Task 5 ↔ Task 8 | `GenerationSpec` | ★ **真冲突**：Task 5 的 `GameMap.spec` **需要该类型存在才能编译**，而完整参数面在 Task 8。**已判**：Task 5 建骨架（只有 `seed`），Task 8 扩写；Task 8 的 Files 已改为 **Modify** |
| Task 5 ↔ Task 8 | `GameMap.spec` 的可空性 | Task 5 声明可空、Task 8 收紧。**已写进 Task 8**：收紧要连带改 `empty()` 与 `apply` 重建，并加 `specIsNeverNullAfterTask8` 用例证明收紧落地 |
| Task 6 ↔ Task 7 | `MapChangeSet` 的组件数（7）vs `GameMap`（8） | ★ **真冲突**：Task 7 的 `everyGameMapComponentParticipatesInTheChangeSet` 遍历**全部 8 个** `GameMap` 组件，而 `spec` 有意不在变更集里 ⇒ 该轮**必然红**。**已判**：引入**被单独用例钉死的豁免集** `EXCLUDED_FROM_CHANGE_SET = Set.of("spec")`，并加 V6 变异证明豁免口不是洞 |
| Task 3 ↔ Task 5 | `RegionIndex` 的"不进状态"断言 | 两边都想写 ⇒ **已判归 Task 5 独有**（Task 3 里那条会引用尚不存在的 `GameMap`）。**已写进 Task 3 Step 4** |
| Task 2 ↔ Task 10 | `TerrainCatalog` 的顺序 | Task 5 的 `GameMap` 用保序不可变、Task 10 断言 `terrainTypes()` 与 catalog **顺序一致**。**一致** |
| Task 10/11/12 → Task 6 | `MapChangeSet` 返回值 | ★ **依赖漏记**：三者都返回 `MapChangeSet`，任务地图里 10/11/12 的依赖**都没写 6**。**已补** |
| Task 4 ↔ Task 6 | `PathwayGroup` / `EdgeTags` 类型 | Task 4 建、Task 6 用作 `FieldDelta` 的类型参数。**顺序正确**（4 在 6 前） |
| Task 1 ↔ Task 11/12/13 | `HexCoord` | 全部单向消费。**一致** |

**自洽检查（每个任务自身）**：Task 5 的 `componentCountIsExactlyEight` 与 §7.1 的字段表一致（8 个）；
Task 6 的 `MapChangeSet` 7 组件与 Task 7 的 8 vs 7 不对称叙述一致；
Task 2 的 `KEYS` 9 项与 Task 9 的 `everyCatalogKeyIsProducible` 同源。
**扫描不是"干净"两个字，是上面这张表。**

### 1~3. 三项自查

1. **每个 Task 都有明确的 Files 与可执行的 Step** —— 是。Task 1~15 全部给出文件路径与步骤；
   Task 1~14 各带护栏自证表，Task 7 与 Task 14 的自证是**成对证据**要求。

2. **两种取值分开标** —— 是。**本计划写死的**（枚举序、字段清单、类型形状、判据）
   与**标注「执行期从 GSimulator 现读」的**（`TerrainCatalog` 的 9 行数值、
   `GenerationSpec` 的 ~60 个阈值、`TerrainClassifier` 的阈值）在头部统一声明，
   并在各任务里再次点明。**控制器没有实测过的值一律不写进来。**

3. **依赖顺序自洽** —— 是。Task 1（hex）与 Task 2（terrain）无依赖；
   Task 3/4 依赖 1；Task 5 依赖 1~4；Task 6 依赖 5；Task 7 依赖 6；
   Task 8 依赖 1；Task 9 依赖 2/8；Task 10 依赖 5/8/9；Task 11 依赖 5/10；
   Task 12 依赖 5/8；Task 13 依赖 5；Task 14 依赖全部；Task 15 依赖全部。
   **无环。**

**已知的计划期弱点（留给执行期）**：
- **Task 5 的 `spec` 组件是临时可空的**（`GenerationSpec` 在 Task 8）——
  执行到 Task 8 时必须**收紧为非 null**，并在报告里明写这次收紧。
- **Task 13 的 `map:<mapId>` 段位判定依赖 M1 spec §3.5** —— 计划里没写死答案，
  因为那是 M1 spec 的管辖范围，**执行者要去读，不要凭直觉**。
- **本计划没有给 `RegionBoundary.of` 与 `RegionIndex.of` 的完整算法** ——
  它们的形状已定（闭环 / 反向索引）、契约已定（派生、不进状态）、
  用例已定（相邻两格一个环 / 重叠确定性），**实现细节留给执行者**。
  这与 M1 计划的粒度一致（M1 的 `AddressParser` 也是给契约不给算法）。
