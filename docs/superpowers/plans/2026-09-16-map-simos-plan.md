# MapSimos（M2）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `simos-map` 从空模块建成 M2 交付物：六边形网格、唯一地形词表、`Region`、
统一连通性系统（稳定 ID）、`GameMap`、`MapChangeSet`（含反射把守的往返框架）、
地形生成三模式（从头生成 / 框选随机化 / 自动河流）、`map:` 寻址。

**Architecture:** 全部是**不可变值类型 + 一张由 record 组件反射把守的归属表**。
四条贯穿的设计决定（均见 spec §〇 的五项裁决 + 用户裁决 U1~U2）：

1. **`HexCoord` 是身份，`"q_r"` 只是它的一个渲染** —— 坐标不再借字符串当身份（铁律 1）。
2. **可重算的索引不进状态** —— 归属反向索引（`RegionIndex`）与渲染缓存**是派生**的，
   不进 `GameMap`、不进变更集。
3. **★ 但边界进状态**（用户裁决 U2，**推翻了控制器原本"边界纯派生"的裁定**）——
   `RegionBoundary` 是 `Region` 的**组件**，理由是「**要不然数据持久化会出问题**」：
   只活在计算里的边界，会逼每个读档方各自重实现一遍推导。
   漂移由**规范构造器重算比对**堵死，不是靠"它反正是派生的"。
   ★ **第 2 条与第 3 条不矛盾，别合并**：索引没有"存起来的那一份"可比，边界有。
4. **连通性只有一个主存储** —— `Map<EdgeRef, EdgeTags>`；`HexCell` 里那份是 L2 的第二份，删掉。

**Tech Stack:** Java 21、Maven（`./mvnw`）、JUnit 5 + AssertJ、google-java-format（Spotless）、
Checkstyle、SpotBugs。**不引入任何新依赖**（`simos-map` 只有 `simos-util` + Jackson databind + SLF4J）。

**Spec:** `docs/superpowers/specs/2026-09-16-map-simos-design.md`（M2 spec，**状态：待用户评审**）。
本计划的每一步都从它派生；上游是 `docs/superpowers/specs/2026-09-16-simos-master-design.md`（总纲）与
`docs/superpowers/specs/2026-09-16-util-simos-design.md`（M1 spec，已执行）。
**执行者必须读 M2 spec 与 M1 spec 的 §九（往返框架）**，尤其是 M2 spec §七（变更集）。

> **⚠️ 本计划的代码草图是计划期产物。** M1 的教训（见 M1 计划头部的取代说明）：草图**编译得过但可能跑不过**。
> **`simos-map/src` 与 spec 才是权威**；执行期就地校正处**一律保留草图原貌 + 加取代说明**，
> **不要抹掉计划原文**——抹掉它等于抹掉"spec 在执行期被磨尖过"这件事。

> **⚠️ 本计划有三类取值，执行时不要混淆，报告里也要分开标：**
> - **① 本计划写死的值**（枚举序、字段清单、类型形状、判据）—— **照抄，不得自行发挥**。
> - **② 标注「执行期从 GSimulator 现读」的值**（`GenerationSpec` 的 ~60 个阈值）——
>   **计划里故意不给数**，因为控制器**没有实测过它们**，写进来就是**编造设计**
>   （总纲 §六 的硬门原话）。执行者按各任务给出的**读取程序**从
>   `~/DevMosire/GSimulator` 现读并记录出处。
> - **③ 本任务新定的值**（**`TerrainCatalog` 的 7 行全部数值**、`TerrainClassifier` 的湿度阈值）——
>   U1 把 GSimulator 那份 9 项表**整个作废**，所以这些值**不是抄来的，是新定的**。
>   控制器给的是**结构与硬约束**（哪 7 项、序按高度、带要连续且覆盖），**具体数值由执行者定**。
>   ★ **这类值在报告里必须明写「本任务新定，非来自 GSimulator」** —— 与第 ② 类分开标，
>   否则后人会以为它们是实测遗产。
>   ★ 第 ② 类**去 GSimulator 是为了抄数**；Task 2 Step 3 那次**去 GSimulator 是为了记录被丢弃的旧 key**
>   （M6 的导入器要用）—— **同样是"读 GSimulator"，目的相反，别混**。

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
- **提交纪律**：只 `git add <本步明确列出的文件>`，**绝不 `git add -A`**；提交前扫 `git diff --cached`；**该推就推**（私有仓库；★ 原写"不擅自推送"，系控制器自加、非用户裁定，2026-09-17 已撤）
- **提交信息末尾**加一行：`Co-Authored-By: Claude Code <noreply@anthropic.com>`
- **不可变与 equals**：所有状态类型不可变，集合组件一律 `List.copyOf` / `Set.copyOf` / `Map.copyOf`；**`equals`/`hashCode` 一律由 record 提供，禁止手写**（`equals` 是往返断言的判据本身）。
  ★ **`toString` 不在此列**（本条原写作"`equals`/`hashCode`/`toString` 一律禁止手写"，**过宽，已按 R-T1-c 收窄**）：
  有**渲染契约**时可以手写，**但必须被一条冻结串用例钉住**（`HexCoord` 的 `"q_r"` 即此类，
  由 `toStringAndParseRoundTrip` 的冻结串把守）。**没有冻结用例的手写 `toString` 仍算违规。**
  ★ **例外**：需要保序的 `Map` 用 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`，**不用 `Map.copyOf`**（它会打乱迭代序，使字节级往返不成立 —— 这是 GSimulator 的实测缺陷，spec §6.1）
- **本机 `grep` 是 `ugrep`**：尊重 `.gitignore` 且默认跳过隐藏目录，会**静默返回空**。查 `.superpowers/**` 或被忽略的文件用 `git grep` 或 `grep --hidden --no-ignore-files`

---

## 文件结构（M2 全景）

| 文件 | 职责 |
|---|---|
| `.../map/hex/HexCoord.java` | 轴向坐标，**六边形格的唯一身份**（spec §3.1） |
| `.../map/hex/HexDirection.java` | **全模块唯一的方向常量表**，枚举 6 项（spec §3.2） |
| `.../map/hex/HexGrid.java` | ★ **纯几何**：邻居/范围/遍历，**不含任何状态**（R-48-d 更正：原写"`Map<HexCoord, HexCell>` 容器"是残留；内容归 `GameMap` 自己的 map）（spec §3.1/§3.3） |
| `.../map/hex/HexVertex.java` | ★ **格角顶点**（整数标签 `(u,w)`）+ 格→6 顶点的偏移表（Task 3 执行期新增，spec §4.3 的类型校正） |
| `.../map/terrain/TerrainType.java` | 地形类型（**10 字段**，含高度带，spec §6.1） |
| `.../map/terrain/TerrainCatalog.java` | ★ **唯一词表**（**7 项**，高度升序）+ 默认集（spec §6.1） |
| `.../map/region/RegionId.java` `RegionMeta.java` `Region.java` | 权威区域，**含边界组件**（spec §4.2） |
| `.../map/region/RegionBoundary.java` | 闭环边界：**入存储**，但由 `hexes` 唯一确定、构造期校验（spec §4.3，用户裁决 U2） |
| `.../map/region/RegionIndex.java` | **派生的**归属反向索引，`regionOf` 为 O(1)（spec §4.4） |
| `.../map/pathway/EdgeRef.java` | 无向边（**规范序在构造期完成**，spec §5.1） |
| `.../map/pathway/EdgeTags.java` `PathwayId.java` `Pathway.java` `PathwayGroup.java` | 边上的标注与**稳定 ID 的线**（spec §5.2/§5.4） |
| `.../map/GameMap.java` `CityId.java` `City.java` `HexCell.java` | 地图状态（spec §7.1） |
| `.../map/change/FieldDelta.java` `MapChangeSet.java` | 变更集（spec §7.2） |
| `.../map/generate/GenerationSpec.java` | 生成参数面（spec §6.4） |
| `.../map/generate/TerrainClassifier.java` | 海拔+气候 → **7 种全覆盖**，高度走词表查表、**无私有阈值**（spec §6.2） |
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
| 3 | `region` 包：权威区域 + **入存储的边界** + 反向索引 | region 包全部 | 1 |
| 4 | `pathway` 包：边与线 | pathway 包全部 | 1 |
| 5 | `map` 包：`HexCell` / `City` / `GameMap`（+ `GenerationSpec` 骨架） | map 包（除 change） | 1,2,3,4 |
| 6 | `change` 包：`FieldDelta` / `MapChangeSet` | change 包 | 5 |
| 7 | ★ **往返框架 + 反射组件枚举 + 自证** | `MapChangeSetTest` 的硬判据 | 6 |
| 8 | `GenerationSpec`：参数面（★ 扩写 Task 5 的骨架） | `GenerationSpec` + 子 record | 1,5 |
| 9 | `TerrainClassifier`：7 项全覆盖、无私有阈值 | `TerrainClassifier` | 2,8 |
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
  // ★ R-48-d：**本类永远不持有内容** —— 没有 `cells` 字段，没有 `cell(HexCoord)`。
  //   `Map<HexCoord, HexCell>` 归 `GameMap`（Task 5）自己。
}
```

★ **本任务只交付坐标运算，而且这就是它的最终形态**（R-48-d 更正）。
原稿写"`HexGrid` 的内容类型是 `HexCell`，Task 5 再补内容访问" —— **那是残留**：
实测 `GameMap` **自己持有** `Map<HexCoord, HexCell> hexes`，根本不经过 `HexGrid`。
⇒ **`HexGrid` 保持纯几何**（范围导出、邻域遍历、半径内枚举），**不引入 `HexCell`、以后也不引入**。
**一件事只有一个地方说**：内容归 `GameMap`，几何归 `HexGrid`。

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

#### ★ 执行期校正（Task 1 修复轮 R1，控制器裁定 —— 上面草图原貌保留）

首次实现的提交是 `b0bcf0e`。以下是**控制器在该轮交回后裁定**的改动，
**不是原草图的错误**，是执行期磨尖的结果。裁定全文见 M2 台账「Task 1 交回：7 条顾虑的裁定」。

- [ ] **R1-a：`withinRadius` 负数半径改为抛异常**（裁定 R-T1-b）

原实现让 `for (int dq = -radius; ...)` 自然不执行、返回空集，并靠用例冻结了这个语义。
**改为**：`radius < 0` 时抛 `IllegalArgumentException`。
**理由**：Global Constraints 与 `GenerationSpec` 都写着"范围校验在构造期抛异常、**不静默夹取**"；
同模块 `minQ()` 空网格已抛 `IllegalStateException`。**空集是静默夹取的近亲。**
`radius = 0 → {center}` **不变**。
**连带**：`cellsWithinRadiusIsClosedBall` 里 `-1 → empty` 那条断言改为断言抛 IAE。

- [ ] **R1-b：补 `HexCoord.round(double q, double r)`**（裁定 R-T1-d）

**spec §3.4（`182-189` 行「一份距离、一份取整」）是绑定权威**，它把 `round` 与 `distanceTo`
并列裁决为"各一个实现"——`distanceTo` 已落地，`round` **在 15 个任务里没有任何一个承载**，
是 spec 与计划之间的空隙。**归 Task 1**（它就在 `hex` 包的几何契约里）。
★ 编号订正：本项在裁定 R-T1-d 时被写成 §3.3，**§3.3 是"删 `gridSize`/`hexOrientation`"**；
`round` 的出处是 **§3.4**。裁定的实质不变。

**语义**：cube 最近格。**不许写成 `new HexCoord((int) Math.round(q), (int) Math.round(r))`**。
★ 朴素舍入的缺陷**不是**"产出 `q + r + s ≠ 0` 的非法格"——`s()` 在本类型里是**导出**的
（`-q - r`），那条恒等式**永远**成立，`(1, 1)` 是合法格。真实缺陷是**它给的不是最近格**：
`(0.5, 0.5)` 朴素舍入给 `(1, 1)`（cube 距离 **1**），而真正最近的是 `(1, 0)` 与 `(0, 1)`（距离 **0.5**）。

**guard（必须有，否则本方法就是新装饰）**：`roundIsNearestHex` ——
拿一个 `(q, r)` 分数网格，对每个输入**暴力枚举**候选格求出真正最近的那个（用 `distanceTo` 比较），
断言 `round` 的结果**其 cube 距离等于暴力求得的最小距离**。
★ **不要断言"与暴力解坐标一致"**：最近格**存在并列**（`(0.5, 0.5)` 就有两个），
按坐标比会在并列处**假红**。断言距离等价，朴素舍入照样必红（距离 1 ≠ 0.5）。
**变异**：把 `round` 换成朴素的 `(int) Math.round(q), (int) Math.round(r)` ⇒ 必须红。

**有限性守卫**（与 R1-a 同一条纪律：**宁抛不静默**）：`NaN`/无穷**抛 `IllegalArgumentException`**——
`Math.round(Double.NaN)` 是 `0`，静默产出 `(0, 0)` 是把错误藏起来。
**超出 int 范围**的输入行为**不定义**（Javadoc 写明即可）：本项目的坐标量级到不了。

**裁定**：**不钉并列时选哪一侧**——spec §3.4 未规定，且"一份取整"意味着全模块只有这一份实现，
不存在跨实现对表的场景。

★ **执行期记录（修复轮 1 实测）**：教科书参考实现的末支是 `else { rs = -rq - rr; }`，
**这里故意不写** —— 该分支要修正的是 **s 轴**，而 `s` 在本类型里是**导出量、不参与返回**，
于是那行对返回值毫无影响，SpotBugs 会当场判 `DLS_DEAD_LOCAL_STORE` 让 `verify` 变红。
**行为等价**（`rs` 本就只参与偏差比较）。**后来者不要把它"补回去"。**
代价：最坏少修正一次"并列"，而并列取哪一侧上面已裁定为不定义。

- [ ] **R1-c：补变异 M8「删掉 `parse` 的形状检查」**（裁定 R-T1-a）

**代码不改**（实测证明形状检查有判别力，见台账那张成对表）。
要补的是**变异行**：删掉 `i <= 0 || i == text.length() - 1` ⇒ 必须红，
**红的理由是异常类型从 `IllegalArgumentException` 变成 `StringIndexOutOfBoundsException`**
（`i = -1` 时 `substring(0, -1)` 先炸），**判别力来自用例第一条 `""`**，不是来自消息。
★ 报告里**不要把 `"_"`/`"1_"`/`"_2"` 算作这条变异的判别来源** —— 它们删掉形状检查后抛
`NumberFormatException`，**而 NFE 是 IAE 的子类，断言根本不翻**（实测见台账）。

- [ ] **R1-d：给报告 §7 第 1 项补上"判形分支"的自证**

> ⚠️ **本条已在第 2 轮 F-新1 就地更正**：原标题写作"更正报告里'形状检查**无判别力**'的结论"，
> **那是控制器的误转述**——报告原文（`4335b52`）说的是"我**没有**实跑'删掉判形分支'的变异，
> 故此条只算'已测'、不算'已自证'"，**不是**"没有判别力"。实现者照着这条错误指令写，于是在报告的审计轨迹里
> 留下了一段**替原文认罪的假自白**（已订正，见报告 §7 第 1 项的 ⚠️ 段）。

报告 §7 按台账那张实测表**补自证**（R-M1 实跑"删掉判形分支"⇒ 红在 `HexCoordTest.parseRejectsMalformed:45`，
来源是 `""`；`"_"`/`"1_"`/`"_2"` 不是来源），
并**保留"实现者当初为什么那么判"**——那是执行期的真实轨迹，抹掉它等于抹掉一次判别力误判的记录。

- [ ] **R1-e：给"序"补钉子**（任务级评审第 1 轮 F1，**Important**，本任务最要害的漏洞）

`HexDirection` 的 Doc 写着"**索引即边序号，全模块唯一**"，而**序本身没有任何用例钉住**。
评审实测两个变异体**存活**（均 exit=0、21/21 全绿，且变异确实编译进去了）：

| 变异 | 结果 | 为什么没红 |
|---|---|---|
| **M10**：`HexCoord.neighbors()` 加 `.reversed()` | 全绿 | 现有三条只看 `hasSize(6)`/`doesNotHaveDuplicates`/距离=1，**都不看顺序** |
| **M11**：交换 `next()` 的 `+1` 与 `prev()` 的 `+5` | 全绿 | `nextAndPrevAreInverse` 在**整体交换下是对称的**，**没有一条断言绝对转移** |

★ **`offsetsMatchFrozenTable` 看着像钉子，钉的却是另一件事**：它比的是 `HexDirection.values()`
（**声明序**），既不是 `ALL` 的消费序，更不是 `neighbors()` 的输出序。
**"钉住了"与"漏掉了"不是同一条**——这正是形态 4 里 `facetNames()` 钉住、`queryAll()` 漏掉的翻版。
（旁证，反方向：`opposite()` **反而被钉死了**——`+3` 是 6 元集上唯一的**无不动点对合**，
`oppositeIsInvolution` + `oppositeIsNotSelf` 两条合起来迫使它只能是 `+3`。）

**要补的用例**（一律**冻结表**，不得写成"与某表达式一致"——那与被测实现同源）：

1. `neighborsFollowDirectionOrder`（`HexCoordTest`）：对 `(0, 0)` 逐项冻结 A 序
   `(1,0) (0,1) (-1,1) (-1,0) (0,-1) (1,-1)`；再对非原点 `(2, -3)` 钉同一张表**平移后**的形状
   `(3,-3) (2,-2) (1,-2) (1,-3) (2,-4) (3,-4)`——证明序与中心无关。
2. `nextAndPrevFollowFrozenCycle`（`HexDirectionTest`）：★ **断言绝对目标**，不是断言互为逆——
   `E.next()=SE, SE.next()=SW, SW.next()=W, W.next()=NW, NW.next()=NE, NE.next()=E`；
   `E.prev()=NE, NE.prev()=NW, NW.prev()=W, W.prev()=SW, SW.prev()=SE, SE.prev()=E`。
   绝对转移**才是 M11 的判别力来源**（形态 3：输入必须落在两种实现会**分叉**的地方）。
3. 可并入 1：从任一方向连走 6 次 `next()` 应回到起点，且沿途**按 A 序**访问全部 6 个方向。

**变异自证**：补完用例后 M10、M11 **各自重跑**，两个都必须从"存活（全绿）"变成**红**，
且红的理由正是上面第 1、2 条用例。

- [ ] **R1-f：三处小修 + 一处口径注释**（同轮评审 F2/F3/F4 + 一条实测补强）

1. **F2 / Minor —— 注释过度声称**（`HexCoordTest.java:76-78`）。该注释称
   `distanceMatchesCubeFormula` 能抓住"把 `s()` 写成 `q + r`"。**实测抓不住**：
   对拍**两侧都调 `s()`**，而 `{dq, dr, ds}` 恒有 `ds = -(dq + dr)`，
   于是 `max(|dq|,|dr|,|ds|) == (|dq|+|dr|+|ds|)/2` 这条恒等式**在任何一致的 `s()` 下都成立**。
   ⇒ 改写成它**真正**在钉的东西：`distanceTo` 的**组合规则**（漏掉 `/2`、或只用两轴，都会红）；
   并点明 `s()` 本身**另有钉子**（`sAxisInvariant` 的 `q + r + s() == 0`）。
2. **F3 / Minor —— 两处中文接缝空格**：`HexCoord.java:10`「唯一两份 实现」、
   `HexGrid.java:12`「落地后 另行补入」，都是手工断行留下的。
   ★ **Spotless 抓不到这一类**（门禁实测全绿），只能靠眼睛查。
3. **F4 —— 控制器裁定：撤回，不加守卫。** `HexGrid.of(null)`/`contains(null)`/`withinRadius(null, …)`
   抛 NPE，与 `parse(null)` 抛 IAE **不是两套口径，是两种东西**：`parse` 的入参是
   **JSON 边界上来的外部数据**（`null` 意味着"数据非法"）；`HexGrid` 的入参是**程序内部对象**，
   NPE 与 JDK 自身（`Set.copyOf(null)`）一致。★ 且按**形态 2**，加 `requireNonNull` 只会造出
   消息恰为字段名的**装饰护栏**（删掉后 JDK 的热心 NPE 消息**同样含该名**），
   要让它有判别力还得再补 `hasMessage` 精确匹配——成本换不来收益。
   **只补一句 Javadoc** 讲清这个区别，免得后续评审重复提。
4. **补强**：`parseRejectsMalformed` **加入 `"12"`**（无分隔符）。
   控制器的成对实测表把 `"12"` 判为"会翻"，但它**不在用例里** ⇒ 当时只能算**推导**。
   ★ **但"加进去就变成用例钉住的事实"这句本就说过头了**（修复轮实测推翻）：
   `""` 与 `"12"` 命中的是**同一行**、同一变异下是**同一机制**（`i = -1 → substring(0, -1)` 抛 SIOOBE），
   而断言在 `""` 那条就中止（R-M1 实测红在 `parseRejectsMalformed:45`），`:46` 根本不执行。
   ⇒ **裁定：不为此新开用例**——再加一条同源用例**不增加任何判别力**，只增加运行时间，
   那正是本项目反对的"装饰"。`"12"` 留在列表里仍是对的（同形态第二个样本，且 `""` 若被移出它接棒），
   **但不得声称它有独立的套件级证据**：`"12"` 的判别力**只到探针级**，套件级由 `""` 承担。

---

### Task 2: `terrain` 包 —— 唯一词表

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainType.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainCatalog.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/terrain/TerrainCatalogTest.java`

**这个任务解的是 L9**（至少 9 份地形词表副本，两份在同一批 key 上完全分叉）。

> **★ 词表已由用户裁决 U1 重定**（详见 spec §〇.0 与 §6.1）：**7 项，按高度从小到大**。
> GSimulator 那 9 项（`water/lowland/plains/hills/mountain/forest/swamp/desert/tundra`）**整个作废**，
> **不是改名**。本节下面的内容已按 U1 重写，**不要再去 GSimulator 抄那份 9 项表**。

- [ ] **Step 1: 写 `TerrainType`**

```java
package io.mosire.simos.map.terrain;

/**
 * 地形类型。**高度带是类型自己的属性** —— 用户裁决 U1 要求"按高度从小到大"。
 *
 * <p>★ 带进词表、**不进分类器的代码**：这样判据可断言（带连续、不重叠、覆盖 [0,1]），
 * 分类器退化成一个查表，**不再是第二个藏着阈值的词表**（GSimulator 的 L9 正是那么来的）。
 *
 * <p>★ 逐格的**海拔值**仍在 {@code HexCell}；本类型携带的是它的**带**。
 */
public record TerrainType(
    String key,
    String name,
    String color,
    double minHeight,
    double maxHeight,
    int food,
    int gold,
    int stone,
    int moveCost,
    String description) {

  public TerrainType {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("key 不得为空白");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不得为空白");
    if (color == null || !color.matches("#[0-9A-Fa-f]{6}")) {
      throw new IllegalArgumentException("color 必须是 #RRGGBB 形式: " + color);
    }
    if (!(minHeight >= 0.0 && minHeight < maxHeight && maxHeight <= 1.0)) {
      throw new IllegalArgumentException("高度带非法: [" + minHeight + ", " + maxHeight + "]");
    }
    if (moveCost < 1) throw new IllegalArgumentException("moveCost 必须 >= 1: " + moveCost);
    if (food < 0 || gold < 0 || stone < 0) throw new IllegalArgumentException("产出不得为负");
  }
}
```

★ **10 个字段**（数一遍：`key`/`name`/`color`/`minHeight`/`maxHeight`/`food`/`gold`/`stone`/`moveCost`/`description`）。
**高度带的左闭右开**（`minHeight <= h < maxHeight`）—— 这样相邻带天然不重叠，无需特判边界。

- [ ] **Step 2: 写 `TerrainCatalog`（★ 唯一词表）**

**7 项 key 固定为**（U1 的序即**高度升序**，**这个顺序是语义序，落盘就用它**）：

| # | 名称 | key | 特性（★ 控制器补裁，用户未给数值，**需过目**） |
|---|---|---|---|
| 1 | 海洋 | `ocean` | 最低；不可通行；无产出 |
| 2 | 平原 | `plains` | 产能最高、最好走 |
| 3 | 沙漠 | `desert` | **★ 额外的低湿度门**；贫瘠、难走 |
| 4 | 低矮丘陵 | `low_hills` | 产量中等、略难走；矿藏起点 |
| 5 | 山地 | `mountains` | 石/矿富集、很难走 |
| 6 | 平缓高原 | `plateau` | **高但平坦** —— 海拔高却相对好走 |
| 7 | 高原山地 | `plateau_mountains` | 最高；几乎不可通行 |

★ **7 行的具体数值（颜色 / 产出 / moveCost / 高度带边界）执行期由实现者定，本计划故意不给** ——
控制器**没有实测过**它们，写进来就是编造（总纲 §六 的硬门）。
**但两条硬约束是给定的**：① 高度带必须**连续、不重叠、覆盖 `[0,1]`**；
② `moveCost` 的**相对大小必须与上表"特性"栏一致**（海洋最难走、平原最好走）。
**数值必须在报告里明写为"本任务新定，非来自 GSimulator"** —— 与从 GSimulator 抄来的值分开标。

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

  /** 7 项的 key，**顺序 = 高度升序 = 落盘顺序**（U1）。 */
  public static final java.util.List<String> KEYS =
      java.util.List.of(
          "ocean", "plains", "desert", "low_hills", "mountains", "plateau", "plateau_mountains");

  /**
   * ★ 唯一的默认词表。**迭代序 = 高度升序**。
   *
   * <p>★ **保序**：GSimulator 的 {@code Map.copyOf} 会打乱迭代序，使同一份表在两个存档里顺序不同，
   * 字节级往返因此不成立。此处用 {@link LinkedHashMap} 且**不 copyOf**。
   */
  public static Map<String, TerrainType> defaults() {
    Map<String, TerrainType> m = new LinkedHashMap<>();
    // ← 7 行在此；高度带必须连续、不重叠、覆盖 [0,1]（Step 4 有用例钉死）
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

- [ ] **Step 3: ★ 调查 GSimulator 的旧词表（**是为了记录被丢弃的东西，不是为了抄**）**

**不要从 GSimulator 抄任何地形数值** —— U1 已把那份 9 项表整个作废。但**要留下记录**，
因为 **M6 的老存档导入器**得知道旧 key 要映射到什么：

```bash
cd ~/DevMosire/GSimulator
git grep -n "defaultTerrainTypes" -- '*.java'      # 词表 B 的定义处与全部副本
git grep -n "TerrainType.defaults" -- '*.java'     # 词表 A 的定义处
git grep -c "#6CC261" -- .                          # 串味的兜底色（A 的平原绿）有几个副本
```

**在报告里给一张映射表**：旧 key（9 项）→ 新 key（7 项）或"无对应"。
★ **这张表是给 M6 用的**，不是本任务的实现输入。**明写它属于 M6 的输入**，别让它看着像 M2 的需求。
（已知的难点：旧表的 `forest`/`swamp`/`lowland`/`tundra` 在新表的 7 项里**没有显然的对应物** ——
**如实写"无直接对应，待 M6 裁决"，不要替 M6 编一个映射**。控制器已知旧表的 `plains` 叫"山区"，是命名事故。）

- [ ] **Step 4: 写用例**

```
TerrainCatalogTest
  - catalogHasExactlySevenKeys            : KEYS.size() == 7
  - defaultsKeySetEqualsKeys              : defaults().keySet() 与 KEYS **顺序**一致   ← 钉保序
  - ~~defaultsIterationOrderIsStable~~    : ★ **已删**（R-2b，见本任务末的「执行期校正」）——
                                            它是个**装饰用例**：把 defaults() 换成 Map.copyOf 它照样绿，
                                            而 copyOf 正是它要抓的那个实现
  - defaultsIsUnmodifiable                : put → UnsupportedOperationException
  - ofThrowsOnUnknownKey                  : of("nope") → IllegalArgumentException，消息含 "未知地形类型"
  - ofNeverFallsBack                      : ★ 断言 of() 里**没有** default 分支 —— 用变异证明（Step 5）
  - everyTypeHasDistinctNameAndColor      : 7 项 name 两两不同、color 两两不同
  - everyColorMatchesHexPattern           : 7 项全过 #RRGGBB
  - everyTypeIsConstructible              : 7 项都能构造（构造期校验不误伤）
  - ★ heightBandsAreContiguousAndCoverUnitInterval
                                          : 按 minHeight 升序排开，首带 minHeight == 0.0、
                                            末带 maxHeight == 1.0、且**相邻处**上一个 maxHeight
                                            == 下一个 minHeight（浮点直接 ==，见下）
  - ★ keysAreInAscendingHeightOrder       : ★ KEYS 的下标序 == 按 minHeight 升序排出的序
  - ★ moveCostOrderMatchesCharacteristics : plains 严格最小；ocean ≥ plateau_mountains；
                                            plateau < mountains
  - plainsIsPlainsNotMountains            : ★ plains 的 name **不含**"山"            ← 钉住命名事故
  - noTypeRevivesAKnownFallbackColor      : ★ 任何一项的 color 都不等于 "#6CC261"
                                            也不等于 "#5B8C3E"（**大小写不敏感**）    ← 见下
  - constructorRejectsInvalidFields       : ★ 六条构造期守卫**逐条**给一个违例       ← 见下
```

★ **`heightBandsAreContiguousAndCoverUnitInterval` 为什么用浮点 `==` 而不是容差**：带边界是
**同一批字面量**（上一个的 `maxHeight` 与下一个的 `minHeight` 写的是同一个数），不是两次数值计算的结果。
用容差会让"差 0.001 的缝"变成绿 —— 而那正是这个用例要抓的东西。**"容差"在这里是判别力的敌人**。

★ **`keysAreInAscendingHeightOrder` 的用意**：Step 2 的表把"顺序 = 高度升序"写成了**注释里的承诺**。
注释不算护栏。这条用例把它变成**可红的断言** —— 否则将来有人往中间插一项、注释还写着"升序"。

★ **`noTypeRevivesAKnownFallbackColor` 的用意**（原名 `plainsGreenIsNotTheOldFallback`，执行期改名并加宽）：
`#6CC261` 是**词表 A** 的平原绿，出现在 `ContourQueryEngine.terrainColor` 的 `default` 分支里 —— **跨词表串味的物证**；
`#5B8C3E` 是**词表 B 族**的低地绿，出现在 `CompressionService.terrainColor` 的 `default` 分支里 —— 第二个物证，
来自**另一个**词表族。新表**不得**再出现这两个值中的任何一个。★ 注意它**不是**在钉"plains 的颜色"，而是在钉
"**这些已知污染值不许在任何一项上复活**" —— 一个**排除用例**（见 §9.3 的排除集合那类）。
★ **必须大小写不敏感**：本类型颜色校验正则允许小写（`#[0-9A-Fa-f]{6}`），故 `"#6cc261"` 是一条与物证**同值**的真实漏路。

★ **`constructorRejectsInvalidFields` 的用意**：m9 只自证了"高度带"**一条**守卫，其余五条
（key 空白 / name 空白 / color 正则 / moveCost &lt; 1 / 产出为负）此前**无人故意违规过** —— 按 G13 那就是装饰。
这条例例把六条**逐条**变成可红的断言。判别力来源是**抛不抛**（这些守卫的消息都是自定义文案，
不是 `requireNonNull` 那种"消息恰是字段名"的形态 2）；消息断言只用来钉**是哪一条**响的。

★ **`moveCostOrderMatchesCharacteristics`** 把 Step 2 表"特性"栏里那句相对大小写成断言。
**具体断言给定如下**（这是控制器的裁定，别自己改）：
`plains` 严格小于其余六项；`ocean ≥ plateau_mountains`（"不可通行"不弱于"几乎不可通行"）；
`plateau < mountains`（"高但平坦、相对好走"）。**其余两两之间不设断言** —— 计划没给依据的，
不许编成断言。

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| `of()` 加一个 `return defaults().get("plains")` 兜底 | **红** | `ofThrowsOnUnknownKey` 有判别力（★ 不另写 `ofNeverFallsBack`，见下） |
| `defaults()` 改用 `Map.copyOf` | **红** | `defaultsKeySetEqualsKeys` 真的钉住了保序（★ `defaultsIterationOrderIsStable` 已删，见下） |
| 把 `plains` 的 name 改回"山区" | **红** | `plainsIsPlainsNotMountains` 有判别力 |
| 删掉一项（6 项） | **红** | `catalogHasExactlySevenKeys` 有判别力 |
| ★ 把某一带的 `maxHeight` 缩小 0.01（造出一条缝） | **红** | `heightBandsAreContiguousAndCoverUnitInterval` 抓得住缝，且**证明它没用容差** |
| ★ 交换 `KEYS` 里 `plateau` 与 `plateau_mountains` | **红** | `keysAreInAscendingHeightOrder` 有判别力 |
| ★ 把 `plains` 的 `moveCost` 改成全表最大 | **红** | `moveCostOrderMatchesCharacteristics` 有判别力 |
| ★ 把某项的 `color` 改成 `#6CC261`（小写 `#6cc261` 也要试） | **红** | `noTypeRevivesAKnownFallbackColor` 是排除用例、不是空转，且**大小写不敏感那半也有判别力** |
| ★ 把某带的 `minHeight` 设成等于它的 `maxHeight` | **红** | **`TerrainType` 的构造器校验**有判别力（构造期护栏也要自证） |
| ★ **删掉 `TerrainType` 的某一条守卫**（如 `key` 空白那一条） | **红** | `constructorRejectsInvalidFields` 的**逐条**判别力 |

★ 每个变异体**都要先自证**：编一份原件作参照、比 `md5`，**确认变异产物 ≠ 原件**再看测试结果。
否则"三向全绿"可能只是"变异根本没写进磁盘"。**这条自身也要有痕迹**（把两份 md5 贴进报告）。

- [ ] **Step 6: 跑门禁并提交**

同 Task 1 的形制，路径换成 `terrain/`，提交信息
`feat(map): terrain 包——唯一地形词表（7 项，高度升序，保序）`。

**★ 执行期校正（2026-09-17，控制器自读 diff 后当场裁定；未另开评审轮）**

用户 2026-09-17 立了红线「**评审的体量不得压过代码本身**」，故 Task 2 **不派评审者、不生成评审包** ——
控制器读 diff 即评审，已确证的发现在发现的那一刻修掉。四处裁定：

- **R-2a｜不写 `ofNeverFallsBack`**。`of()` 的唯一未知 key 路径与兜底路径**同一个断言**（都要求抛），
  两条用例同红同绿 = 重复的一种。m1 变异体照跑，红的会是 `ofThrowsOnUnknownKey`。
- **R-2b｜删 `defaultsIterationOrderIsStable`**。实现者实测：把 `defaults()` 换成 `Map.copyOf`，它
  **照样绿** —— 而 `copyOf` 正是它要抓的那个实现（同一 JVM 内 key 序确定，跨进程才不同）。名字在承诺
  一件它测不了的事。保序的钉子是 `defaultsKeySetEqualsKeys`（与冻结的 `KEYS` 逐项比字面量序）。
  **残留**：跨进程序无人把守，单进程用例够不到 → 记台账，不在本模块解决。
- **R-2c｜兜底色补第二个值，且改大小写不敏感**。`#5B8C3E` 是**另一个**词表族的兜底色（见 spec §6.1 补记）。
  逐字符 `isNotEqualTo` 会放过同值小写写法 `"#6cc261"`，而颜色校验正则明确允许小写。用例改名
  `noTypeRevivesAKnownFallbackColor`。
- **R-2d｜补 `constructorRejectsInvalidFields`**。m9 只覆盖"高度带"一条，其余五条守卫无自证。

自证：拆 `key` 守卫 → `:171` 红；plains 改 `#6cc261` → `:156` 红（消息指名 plains）；两轮均无
`COMPILATION ERROR`，事后 `clean verify` 全绿（**不信任 `target/classes` 里的陈旧产物** —— 第六形态）。

---

### Task 3: `region` 包 —— 权威区域 + **入存储的**边界 + 反向索引

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/region/RegionId.java` `RegionMeta.java` `Region.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/hex/HexVertex.java`（★ 执行期新增，见本任务末尾校正）
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
public record RegionBoundary(List<List<HexVertex>> rings) {   // ★ 原写 HexCoord，执行期校正见下
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

---

**★★ 执行期校正（2026-09-17，控制器；完整裁定见 `.superpowers/sdd/2026-09-16-map-simos-plan/task-3-rulings.md`）**

本任务原文**自相矛盾**：上面的 `List<List<HexCoord>>` 说环的元素是**格**，而 Step 4 的
`singleHexRingHasSixVertices` 说单格边界有 6 个**顶点**。**格与顶点是两套东西。**
实测老仓权威算法（`TerrainGeometry.hexSetToBoundaryWithHoles`，`hexSetToBoundaryWithHoles` 逐格逐边
收集暴露边、取边的**两个端点**串环，注释写明「closed polygon」「Canvas **evenodd** fill」）确证
**环是格角顶点**。故：

- **R-3a**：环元素改为**新增的 `hex.HexVertex(int u, int w)`**（整数标签、全格唯一、可比）。
  不重用 `HexCoord`（它是格），不引入 `Pt`（那是老仓的像素渲染类型）。
- **R-3b**：`(u,w)` 定义 —— `u = x/(size·√3/2)`、`w = y/(size/2)`（各向异性缩放，**是标签不是坐标，
  不可用来算距离/角度**）。格心 → `(2q+r, 3r)`；第 `i` 个顶点 → `(2q+r+U[i], 3r+W[i])`，
  六项常量 `U/W` 见裁定文件。**`HexDirection` 第 `d` 条边的两端点是第 `d` 与第 `(d+1)%6` 个顶点。**
  逐项对表已验证：本仓 `HexDirection` 枚举序与老仓 `DIRS` 完全相同。
- **R-3c**：**不沿用**老代码 `hexSet.size() < 3 → 空` 的短路（那是渲染期的多边形下限，不是几何事实）。
  单格 → 1 条环 6 顶点；相邻两格 → 1 条环 10 顶点。
- **R-3d**：规范化（旋到最小顶点开头、取字典序较小方向、环表按首顶点排序）放进**紧凑构造器**，
  幂等 ⇒ `RegionBoundary` 成为真正的值类型。★ 绕向规范化**没有几何含义**（消费端 evenodd），
  不要在 Javadoc 里说成"顺时针"。
- **R-3e**：★ 顶点度**恒为 2**（六角格每顶点恰 3 格 3 边，k 格属于本区则暴露边 = k(3−k)，k=1/2 都得 2）
  —— 这是**控制器的推导**，故**必须落成代码护栏**：走环时发现度 ≠ 2 就**抛**，不许取"第一个未访问邻居"糊过去。
- **R-3f**：偏移表是推导的 ⇒ 落码前先钉两条（相邻两格顶点交集恰 2 个；单格 6 顶点互不相同）。不过就报告。
- **R-3h**：不写"边界不进存储"这类已作废的断言；`GameMap` 的那条归 Task 5，只写一处。

★ 老代码用 `Math.round(x*1000)+"_"+Math.round(y*1000)` 当顶点身份（**浮点舍入当身份**，同顶点可能对不上键
而断环）—— 整数标签正是为消除它。

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

### Task 4: `pathway` 包 —— 边与线

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/pathway/EdgeRef.java` `EdgeTags.java`
- Create: `simos-map/src/main/java/io/mosire/simos/map/pathway/PathwayId.java` `Pathway.java` `PathwayGroup.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/pathway/EdgeRefTest.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/pathway/EdgeTagsTest.java`（★ 派单前扫描补，R-48-b）
- Test: `simos-map/src/test/java/io/mosire/simos/map/pathway/PathwayTest.java`（`PathwayId` 的三件套用例
  并进这里，**不另开文件** —— 与 Task 3 把 `RegionId` 的用例放进 `RegionTest` 同形制）

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

  /**
   * 规范字符串形式。★ **两种用途**（R-48-f 更正）：① 它是变更集里 {@code edges} 组件的 **key**
   * （`MapChangeSet.between/apply` 用 `keyOf = toString()` 比对与还原）；② JSON 边界。
   * 原稿只写了 ②，与 Task 6 的变更集口径**对不上**，已改。
   */
  @Override
  public String toString() {
    return a + "|" + b;
  }

  /** ★ **往返的另一半**（R-48-f）：没有它就断链 —— 变更集 key 还原不回来。 */
  public static EdgeRef parse(String s) {
    // 按 '|' 切成两段，各交给 HexCoord.parse；段数不为 2 即抛（宁抛不静默）
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

  /** ★ **裸值**，不是 record 默认的 `PathwayId[value=…]`（R-48-f）—— 它是**地址**，是变更集的 key。 */
  @Override
  public String toString() { return value; }

  /** ★ 往返的另一半（R-48-f）。缺了它 `MapChangeSet.apply` 就还原不回来。 */
  public static PathwayId parse(String s) { /* 非空白即收，否则抛 */ }
}
```

```java
/** 线的**组定义**（river / road …）。老仓 `MapData.PathwayGroup`（实测 `:451`），字段照搬。 */
public record PathwayGroup(String id, String name, String color, String description,
                           boolean visible, java.util.Map<String, PropertyDef> properties) {
  public PathwayGroup {
    // ★ 老仓对 id/name 等做 `if (x == null) x = "";` **静默填空** —— 本项目禁。
    //   空白即抛（与 TerrainType / RegionId 同族）。
  }
}

/** 组属性的一条定义。**嵌套 record** —— Task 7 的反射枚举要能穿透它（R-48-c：不许为省事删掉）。 */
public record PropertyDef(String type, Object defaultValue, String description) {}
```

★ **老仓的两组默认值**（`MapData.java:469 defaultPathwayGroups`，实测）：`river` 河流 `#3295D2` / `road` 道路 `#8B7355`。
是否需要一份 `defaults()` 由 Task 6 的 `GenerationSpec` 决定；**本任务只交付类型**。

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
  - toStringMatchesFrozenLiteral          : ★ **冻结串**（R-48-f）：`new EdgeRef(c(3,4), c(-1,0)).toString()`
                                            恰为字面量 `"-1_0|3_4"`（**规范序在前**，不是入参序：
                                            `HexCoord.compareTo` 先 q 后 r，−1 < 3 故 `(-1,0)` 在前）。
                                            全局约束「没有冻结用例的手写 toString 算违规」—— `toStringIsStable`
                                            只比两个方向，**不算冻结**，光有它不达标。
  - parseRoundTripsFrozenLiteral           : ★ `EdgeRef.parse("-1_0|3_4")` equals 上式结果；
                                            段数不为 2 的串 → IllegalArgumentException
  - compareToIsTotalOrder

PathwayTest
  - constructorRejectsBlankGroupIdAndNullId
  - edgesIsImmutable
  - lengthIsEdgeCount
  - startAndEndAreTheChainEnds            : 三格链 → start/end 是两头，不是中间
  - ★ idsArePersistedNotDerived           : 见 Step 5
  - equalityIsComponentwise

  # ★ R-48-f：`PathwayId` 的三件套（缺一，Task 6 的往返就断）
  - pathwayIdToStringIsBareValue          : new PathwayId("p1").toString() 恰为 "p1"
                                            （**不是** `PathwayId[value=p1]` —— 默认实现会让变更集 key
                                             变成 `PathwayId[value=p1]`，apply 侧认不出来）
  - pathwayIdParseRoundTripsFrozenLiteral : PathwayId.parse("p1").equals(new PathwayId("p1"))
  - pathwayIdParseRejectsBlank            : parse("") / parse("  ") → IllegalArgumentException

EdgeTagsTest
  - preservesInsertionOrder               : ★ 保序（前端曾因 props 被抹平而丢数据）
  - isImmutable

PathwayIdTest                             # ★ R-48-f：ID 三件套，缺一往返就断
  - toStringIsBareValue                   : new PathwayId("p1").toString() 恰为 "p1"
                                            （**不是** `PathwayId[value=p1]` —— 那条默认实现会让
                                             变更集 key 变成 `PathwayId[value=p1]`，apply 侧认不出来）
  - parseRoundTripsFrozenLiteral          : PathwayId.parse("p1").equals(new PathwayId("p1"))
  - parseRejectsBlank                     : parse("") / parse("  ") → IllegalArgumentException
  - rejectsBlankValue                     : 构造期校验不是装饰
```

- [ ] **Step 5: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| 删掉 `EdgeRef` 构造期的 `a.compareTo(b) > 0` 交换 | **红** | `orderIsCanonical` 真的钉住了规范序 |
| 删掉自环校验 | **红** | `selfLoopIsRejected` 不是装饰 |
| `Pathway` 加一个派生 id 的方法并让构造器**忽略传入 id** | **红** | ★ `idsArePersistedNotDerived` 有判别力 |
| `EdgeTags` 改用 `Map.copyOf` | **红** | `preservesInsertionOrder` 真的钉住了保序 |
| `PathwayId` 里**删掉手写 `toString()`**（退回 record 默认） | **红** | ★ R-48-f 的钉子：`pathwayIdToStringIsBareValue` 直接红。**这条是"往返会不会断"的早期报警** —— 它在 Task 4 红，比拖到 Task 6 的 `applyRebuildsTargetExactly` 才红便宜得多 |

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

### Task 9: `TerrainClassifier` —— **7 项全覆盖，且不许自带阈值**

**Files:**
- Create: `simos-map/src/main/java/io/mosire/simos/map/generate/TerrainClassifier.java`
- Test: `simos-map/src/test/java/io/mosire/simos/map/generate/TerrainClassifierTest.java`

**这个任务解的是 L9 的后半**：GSimulator 的 `classify` **只产出 6 种**，
`forest`/`desert`/`tundra` **永远产生不出来** —— 一份产不出来的词表是谎话。

> **★ U1 之后本任务的形态变了**（spec §6.1）。词表是 **7 项**，而且
> **高度带由 `TerrainCatalog` 唯一持有** —— 于是分类器**退化成一个查表**，
> **它自己不许再有第二套高度阈值**。GSimulator 的 L9 正是"词表一份、阈值另一份"长出来的。
> **这个任务的判别力，全在"分类器有没有自己的数"这一条上。**

- [ ] **Step 1: 写 `TerrainClassifier`**

```java
package io.mosire.simos.map.generate;

/**
 * 从海拔与气候判定地形。**必须覆盖 TerrainCatalog 的全部 7 项。**
 *
 * <p>★ **高度判定是查表，不是阈值**：按高度落进 {@link TerrainCatalog} 里唯一那条带
 * （带构成 [0,1] 的划分，故落点唯一）。**本类里不许出现任何高度字面量。**
 *
 * <p>★ GSimulator 的等价物只产出 6 种（mountain/hills/plains/lowland/swamp/water），
 * {@code forest}/{@code desert}/{@code tundra} 在词表里但产出不来。
 */
public final class TerrainClassifier {

  /**
   * 判定。输入的 humidity 与 temperature 都在 [0,1]。
   *
   * <p>★ 只有 {@code desert} 带带**气候门**（低湿度）：落在 desert 带而湿度不低时**退到
   * {@code plains}** —— **总函数**，任何输入都有返回值，且返回值恒在 {@code KEYS} 内。
   */
  public static String classify(double height, double humidity, double temperature) { /* … */ }
}
```

★ **沙漠那道门的具体形态（控制器的裁定，别自己改）**：带是划分 ⇒ 高度先唯一定位候选带。
`desert` 带再加一道**低湿度**门；**过不了门就返回 `plains`**。
选 `plains` 而不是"相邻低带"是因为**要一个写死的常量，不要一条算法** ——
"相邻低带"将来插一项就变意思，`plains` 不会。
**这道门必须是用例钉住的**（见 Step 2 的 `desertBandFallsBackToPlainsWhenHumid`），
否则它就是一段没人验过的分支。

★ **湿度的具体阈值由执行者定** —— 控制器没实测过，写进来就是编造（总纲 §六 的硬门）。
**数值必须在报告里明写为"本任务新定，非来自 GSimulator"**。温度参数本任务**先收下但不使用**
（U1 的 7 项里没有靠温度区分的项）—— **报告里明写"temperature 目前不参与判定、为 M3+ 预留"**，
别让它看着像忘了用。

- [ ] **Step 2: 写用例**

```
TerrainClassifierTest
  - ★ everyCatalogKeyIsProducible       : 遍历 TerrainCatalog.KEYS，断言**每一项**都存在
                                          至少一组 (height,humidity,temperature) 判出它
                                          ← 核心断言，直接钉住"词表不是谎话"
  - ★ classifierFollowsCatalogBands     : ★ 见下 —— 本任务判别力最强的一条
  - ★ desertBandFallsBackToPlainsWhenHumid
                                        : desert 带内、湿度取中值 → "plains"（不是 desert、
                                          也不是别的）；同高度、低湿度 → "desert"
  - classifyNeverReturnsUnknownKey      : 扫一个三维网格，断言返回值恒在 KEYS 里（**不兜底**）
  - oceanIsLowestBand                   : 最低带的输入 → ocean，**且与湿度温度无关**
  - plateauMountainsIsHighestBand       : 最高带的输入 → plateau_mountains
  - classifyIsDeterministic             : 同输入两次同输出
  - classifyIsTotal                     : 全域有定义，不抛异常
```

★ **`classifierFollowsCatalogBands` 怎么写**：**不许把高度值写死在用例里**。
遍历 `TerrainCatalog.defaults()`，对每个类型 `t` 取**由它自己的带算出来的**采样点
（`t.minHeight()`、带中点、`t.maxHeight()`；`maxHeight()` 用 `Math.nextDown` 收进来以钉左闭右开），
断言 `classify(该点, 中性湿度, 中性温度) == t.key()`（`desert` 按上面那条单独处理）。
**这样写，分类器里但凡藏着自己的一套数，用例就红** —— 若把高度值抄进用例，两种实现下
断言全等价，这条就白写了。

- [ ] **Step 3: ★ 护栏自证（G13）**

| 变异 | 期望 | 证明什么 |
|---|---|---|
| 删掉 `desert` 的分支（退回 6 项） | **红** | ★ `everyCatalogKeyIsProducible` 有判别力 —— 这条正是 L9 的守卫 |
| ★ **在分类器里写死一套高度阈值**（把带边界抄成字面量，再改动 `TerrainCatalog` 里的带） | **红** | ★ `classifierFollowsCatalogBands` 有判别力 —— **这是本任务存在的理由** |
| ★ 去掉沙漠的低湿度门（落 desert 带一律 desert） | **红** | `desertBandFallsBackToPlainsWhenHumid` 有判别力 |
| `classify` 末尾加一个 `default -> return "plains"` 兜底 | **红** | `classifyNeverReturnsUnknownKey` 的"不兜底"半 |
| 让 `classify` 对某段输入抛异常 | **红** | `classifyIsTotal` 有判别力 |
| ★ 把 `ocean` 带也加上气候门 | **红** | `oceanIsLowestBand` 的"与湿度温度无关"半有判别力 |

★ 第 2 行**要这么做**：变异体不是"改分类器"，而是**同时改两处** —— 分类器里写死一套旧阈值，
再把 `TerrainCatalog` 里某条带的边界挪一点。**若分类器真的查表，结果跟着变（绿）；
若它自带阈值，结果不变而用例期望它变（红）**。这条变异的构造比别条麻烦，**但它是本任务唯一
能证明"没有第二份词表"的办法**，不许省。

- [ ] **Step 4: 跑门禁并提交**

提交信息 `feat(map): TerrainClassifier——7 项地形全覆盖，高度走词表查表、无私有阈值`。

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
  - riverEndsAtOceanOrBoundary         : 终点是 ocean 格或图边界
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
| Task 1 ↔ Task 5 | `HexGrid.java` | ★ **本条原写"Task 5 补内容访问"——派单前扫描证明是残留**（R-48-d）：实测 `GameMap` **自己持有 `Map<HexCoord, HexCell> hexes`**，根本不经过 `HexGrid`。⇒ **Task 5 不再 Modify `HexGrid`**，它**保持纯几何**；原"已写进 Task 1 Step 3"的说法也不成立（Task 1 Step 3 只写了"不引入 `HexCell`"）。**已就地删掉那行 Modify** |
| Task 5 ↔ Task 8 | `GenerationSpec` | ★ **真冲突**：Task 5 的 `GameMap.spec` **需要该类型存在才能编译**，而完整参数面在 Task 8。**已判**：Task 5 建骨架（只有 `seed`），Task 8 扩写；Task 8 的 Files 已改为 **Modify** |
| Task 5 ↔ Task 8 | `GameMap.spec` 的可空性 | ★ **R-48-e 撤回了原裁定**：原写"Task 5 声明可空、Task 8 收紧"。否决理由——**中间会留两轮 nullable 世界**（Task 6 的 `apply`、Task 7 的反射枚举都得绕开 `spec`），而"收紧"那一步**没人把守**。**新口径**：Task 5 的 `empty()` 起就 `GenerationSpec.defaults(0L)`，**`spec` 从不 null**；Task 8 仍加 `specIsNeverNullAfterTask8`，但它是**守卫**不是收紧动作，`MapChangeSet.java` 列进 Task 8 Files 只为**核实前提**（无 null 兜底就如实报告"无需改动"） |
| Task 6 ↔ Task 7 | `MapChangeSet` 的组件数（7）vs `GameMap`（8） | ★ **真冲突**：Task 7 的 `everyGameMapComponentParticipatesInTheChangeSet` 遍历**全部 8 个** `GameMap` 组件，而 `spec` 有意不在变更集里 ⇒ 该轮**必然红**。**已判**：引入**被单独用例钉死的豁免集** `EXCLUDED_FROM_CHANGE_SET = Set.of("spec")`，并加 V6 变异证明豁免口不是洞 |
| Task 3 ↔ Task 5 | `RegionIndex` 的"不进状态"断言 | 两边都想写 ⇒ **已判归 Task 5 独有**（Task 3 里那条会引用尚不存在的 `GameMap`）。**已写进 Task 3 Step 4** |
| Task 2 ↔ Task 10 | `TerrainCatalog` 的顺序 | Task 5 的 `GameMap` 用保序不可变、Task 10 断言 `terrainTypes()` 与 catalog **顺序一致**。**一致** |
| Task 10/11/12 → Task 6 | `MapChangeSet` 返回值 | ★ **依赖漏记**：三者都返回 `MapChangeSet`，任务地图里 10/11/12 的依赖**都没写 6**。**已补** |
| Task 4 ↔ Task 6 | `PathwayGroup` / `EdgeTags` 类型 | Task 4 建、Task 6 用作 `FieldDelta` 的类型参数。**顺序正确**（4 在 6 前） |
| Task 1 ↔ Task 11/12/13 | `HexCoord` | 全部单向消费。**一致** |

★ **用户裁决 U1 / U2 落地后补扫的四行**（这两条裁决**改掉了接口形状**，必须重扫）：

| 谁与谁共享 | 共享的东西 | 查到了什么 |
|---|---|---|
| Task 2 ↔ Task 9 | 词表的**项数** | U1 把 9 项改成 7 项。Task 2 的 `KEYS`、Task 9 的 `everyCatalogKeyIsProducible`、文件结构表、任务地图**四处都写过"9"**。**已判**：四处全部改为 7，**并以 Task 2 的 `catalogHasExactlySevenKeys` 为唯一权威** —— 其余三处是叙述，不是判据 |
| Task 2 ↔ Task 8 ↔ Task 9 | **高度带归属** | ★ **真冲突**：U1 说"高度从小到大"⇒ 高度带进词表；而 Task 8（`GenerationSpec`）与 Task 9（分类器）都可能各自持有一套高度阈值 ⇒ **退回 L9 的病**。**已判**：**高度带的唯一持有者是 `TerrainCatalog`**；`GenerationSpec` 不得再有按地形的高度阈值，分类器**不含任何高度字面量**（Task 9 的 `classifyFollowsCatalogBands` 与第 2 条变异钉死） |
| Task 3 ↔ Task 5 | `RegionBoundary` 的归属 | ★ **真冲突（U2 造成）**：Task 3 原写"`boundaryIsDerivedNotStored`：反射断言 `Region` **不含** boundary"，Task 5 原写"`boundaryOf` 派生、不进组件" —— U2 之后**两条都反了**。**已判**：Task 3 改为 `boundaryIsAStoredComponent`（断言**含**该组件），Task 5 **删掉 `boundaryOf`**（`regions().get(id).boundary()` 已是权威路径，再开一条就是同一概念的第二条路） |
| Task 3 ↔ Task 5 ↔ 铁律 5 | `boundary` 进不进变更集 | ★ **U2 的连锁**：`boundary` 成了 `Region` 的组件 ⇒ **`MapChangeSet` 不需要新组件**（`regions` 整个 `Region` 值被比对，`equals` 含 `boundary`）。**已判**：Task 6/7 的组件数**不变**（8 vs 7），`spec` 仍是唯一豁免项，**V6 变异照旧**。**但要在 Task 7 的报告里明写这条推理链**，否则后人会以为边界被漏掉了 |

★ **U2 自身的一致性**：`Region` 构造器要 `boundary`、而 `boundary` 要由 `hexes` 算 ——
若 `RegionBoundary.of` 收 `Region` 就成死循环。**已判**：`of` 收 `Set<HexCoord>`（Task 3 Step 2 已写明）。

★ **U2 引出的新护栏（原计划没有）**：`hexes` 是 `Set.copyOf`（不保序），而 `boundary` 现在参与
`equals` ⇒ **`RegionBoundary.of` 必须是集合内容的纯函数、与迭代序无关**（排序 + 环起点规范）。
否则两个内容相同的 `Region` 会 `equals` 为假 —— 一个只在迭代序恰好分叉时才现形的 bug。
**已判**：`boundaryIsIndependentOfInputSetIterationOrder` 用**两种迭代序**构造同一批 hex。

**自洽检查（每个任务自身）**：
Task 5 的 `componentCountIsExactlyEight` 与 §7.1 的字段表一致（8 个 —— **`Region` 从 4 组件变 5 组件不影响它**，`GameMap` 的组件是 `Map<RegionId, Region>`，仍算一个）；
Task 6 的 `MapChangeSet` 7 组件与 Task 7 的 8 vs 7 不对称叙述一致；
Task 2 的 `KEYS` **7 项**与 Task 9 的 `everyCatalogKeyIsProducible` 同源；
Task 2 的 `TerrainType` **10 字段**与文件结构表的"10 字段"一致；
Task 3 的 `Region` **5 组件**与 §4.2 一致（`id`/`name`/`hexes`/`boundary`/`meta`）。
**扫描不是"干净"两个字，是上面这张表。**

### 1~3. 三项自查

1. **每个 Task 都有明确的 Files 与可执行的 Step** —— 是。Task 1~15 全部给出文件路径与步骤；
   Task 1~14 各带护栏自证表，Task 7 与 Task 14 的自证是**成对证据**要求。

2. **三类取值分开标**（U1 之后从两类变三类）—— 是。**本计划写死的**（枚举序、字段清单、
   类型形状、判据）、**标注「执行期从 GSimulator 现读」的**（`GenerationSpec` 的 ~60 个阈值）、
   与**本任务新定的**（`TerrainCatalog` 的 **7 行全部数值**、`TerrainClassifier` 的湿度阈值）
   在头部统一声明，并在各任务里再次点明。**控制器没有实测过的值一律不写进来**；
   **新定的值必须与抄来的值分开标**，否则后人分不清哪些是实测遗产。

3. **依赖顺序自洽** —— 是。Task 1（hex）与 Task 2（terrain）无依赖；
   Task 3/4 依赖 1；Task 5 依赖 1~4；Task 6 依赖 5；Task 7 依赖 6；
   Task 8 依赖 1；Task 9 依赖 2/8；Task 10 依赖 5/8/9；Task 11 依赖 5/10；
   Task 12 依赖 5/8；Task 13 依赖 5；Task 14 依赖全部；Task 15 依赖全部。
   **无环。**

**已知的计划期弱点（留给执行期）**：
- ~~Task 5 的 `spec` 组件是临时可空的，Task 8 收紧~~ **★ R-48-e 已撤回**：
  中间会留**两轮 nullable 世界**（Task 6 的 `apply`、Task 7 的反射枚举都得绕开 `spec`），
  而"收紧"那一步**没人把守**。新口径：Task 5 的 `empty()` 起就 `GenerationSpec.defaults(0L)`，
  **`spec` 从不 null**；Task 8 的 `specIsNeverNullAfterTask8` 是**守卫**，不是收紧动作。
  （Task 5 仍然只建 `GenerationSpec` 的**最小骨架**，Task 8 扩写为完整参数面 —— 这一半不变。）
- **Task 13 的 `map:<mapId>` 段位判定依赖 M1 spec §3.5** —— 计划里没写死答案，
  因为那是 M1 spec 的管辖范围，**执行者要去读，不要凭直觉**。
- **本计划没有给 `RegionBoundary.of` 与 `RegionIndex.of` 的完整算法** ——
  形状已定（闭环 / 反向索引）、用例已定（相邻两格一个环 / 重叠确定性），**实现细节留给执行者**。
  这与 M1 计划的粒度一致（M1 的 `AddressParser` 也是给契约不给算法）。
  ★ **但两者的契约在 U2 之后相反了，别照抄旧话**：`RegionIndex` 是**派生、不进状态**；
  `RegionBoundary` 是**入存储的组件**（构造期校验 + 规范化）。Task 3 Step 2 已写明。
- **★ U2 新增了一个原计划没有的设计要求：`RegionBoundary.of` 必须规范化**（排序 + 环起点/方向
  取规范值）。控制器**给了要求与判据，没给算法** —— 因为环的规范化怎么写有多种正确解，
  而判据只有一条：**同集合必得同结果，与迭代序无关**。执行者自选一种并**在报告里写明选了哪种、
  以及为什么它对**。这条是本计划里**唯一一处"契约清楚但实现空间较大"**的地方，评审时重点看它。
