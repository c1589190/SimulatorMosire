# UtilSimos（M1）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `simos-util` 从空模块建成 M1 交付物：地址 AST 与解析/渲染、身份、时间与版本、三个协议接口、`InfoSystem`、`TemporalSeries`、`Resolver` SPI、Facet 协议、往返不变式测试框架。

**Architecture:** 全部是**不可变值类型 + 两个注册表 + 一个断言工具**。地址走"两层"设计：宽容解析人类写法、canonical 唯一输出（按需加引）。跨模块可见性只经 Facet 协议，领域模块自注册、`MapSimos` 不知情。变更集的字段漂移由 `apply(diff(base,target), base).equals(target)` 的 record `equals()` 兜住——这是 L1 事故的直接对症药。

**Tech Stack:** Java 21、Maven（`./mvnw`）、JUnit 5 + AssertJ、google-java-format（Spotless）、Checkstyle、SpotBugs。**不引入任何新依赖**（`simos-util` 只有 Jackson databind + SLF4J）。

**Spec:** `docs/superpowers/specs/2026-09-16-util-simos-design.md`（M1 spec；**类型形状已于上一会话批准**，**spec 本体仍「待用户评审」**）——本计划的每一步都从它派生；总纲是 `docs/superpowers/specs/2026-09-16-simos-master-design.md`。**执行者必须同时读这两份**，尤其是 spec §三（Address）与 §九（往返框架）。

> **⚠️ 全文代码草图是计划期产物，执行期已就地校正过（2026-09-16 关账时补记）。**
> 下面的 Java 草图**不是权威**——**spec 与已落地的 `simos-util/src` 才是权威**。已知分歧各自在
> 对应位置有**取代说明**：任务地图的 Task 2 行、以及 Task 7 Step 1 前·Step 4、Task 8 Step 1 前、
> Task 9 Step 1 前、Task 10 Step 1 前·Step 2 各一处。
> **取代说明一律保留草图原貌**：计划的写法本身是记录，抹掉它等于抹掉"spec 在执行期被磨尖过"这件事。
> **M2 及以后照抄本计划的草图会重蹈覆辙**——照抄 Task 9 或 Task 10 的草图**编译得过但跑不过**，理由见各处的取代说明。

## Global Constraints

- **Java 21**（`maven.compiler.release=21`）；Maven `[3.8,)`；父 POM `io.mosire:simos-parent:0.1.0-SNAPSHOT`，**不继承** `io.mosire:mosire-parent`
- **依赖白名单**（enforcer 构建期强制，越界即构建失败）：compile 只有 `com.fasterxml.jackson.core:jackson-databind`、`org.slf4j:slf4j-api`；test 只有 `org.junit.jupiter:junit-jupiter:6.1.3`、`org.assertj:assertj-core:3.27.7`。**不得新增任何依赖**，不得依赖 `io.mosire:agentlib-mosire` 或任何 simos 模块
- **不碰文件系统**：main 与 test 源码都不得出现 `java.io` / `java.nio.file` / `Files` / `Path`
- **无领域词汇**：main 源码不得出现 hex / region / unit / population / terrain 等词（Javadoc 举例与测试假数据除外）
- **中文注释与文档**；Javadoc **不手工调行宽**（google-java-format 按字符数折行，CJK 计 1 列）——写完跑 `./mvnw -q spotless:apply`
- **测试风格**：JUnit 5 + AssertJ；测试类包级私有、**测试类名与方法名用英文**（沿用 `AgentLibAvailabilityTest` 的既有风格），Javadoc 与注释用中文
- **迭代只跑相关单条用例**：`./mvnw -q -pl simos-util -Dtest=<类名> test`（从仓库根目录执行）
- **关账门禁**：`./mvnw clean verify` = Spotless(check) + Checkstyle(validate) + SpotBugs(verify) + Surefire。`mvn test` **不跑** SpotBugs
- **护栏必须自证**（G13）：每条新护栏都要有一个故意违规用例证明它真的会响
- **提交纪律**：只 `git add <本步明确列出的文件>`，**绝不 `git add -A`**；提交前扫 `git diff --cached`；**该推就推**（私有仓库；★ 原写"不擅自推送"，系控制器自加、非用户裁定，2026-09-17 已撤）
- **不可变与 equals**：所有状态类型不可变，集合组件一律 `List.copyOf` / `Map.copyOf`；`equals`/`hashCode`/`toString` **一律由 record 提供，禁止手写**（`equals` 是往返断言的判据本身）

---

## 文件结构（M1 全景）

| 文件 | 职责 |
|---|---|
| `simos-util/src/main/java/io/mosire/simos/util/address/AddressText.java` | 包内词法工具：裸词判定、引号转义、"按需加引"（spec §3.4） |
| `.../address/AddressSegment.java` | sealed 接口 + `canonical()` |
| `.../address/Namespace.java` `Entity.java` `Index.java` `Property.java` | 四种段（record + 构造期校验） |
| `.../address/Address.java` | 段序列 + `parse` / `canonical` / `namespace` |
| `.../address/AddressParser.java` | 包内解析器：切段、宽容写法、段类型判定（spec §3.2/§3.5） |
| `.../identity/SubjectId.java` `ResolvedSubject.java` `QueryResult.java` | 稳定身份与候选结果 |
| `.../time/SimosTimestamp.java` `TimeRange.java` | 模拟时间与有效区间 |
| `.../time/TemporalSeries.java` `Segment.java` `Event.java` `EventMode.java` `SegmentedSeries.java` | 时态序列协议与通用实现（spec §七） |
| `.../state/BranchId.java` `RevisionId.java` `StateRef.java` `StateMeta.java` | 数据版本坐标 |
| `.../state/Snapshot.java` `ChangeSet.java` `Command.java` `SimulationState.java` | 三个协议接口与状态容器 |
| `.../info/InfoEntry.java` `InfoSystem.java` `InMemoryInfoSystem.java` | 外挂时态属性（spec §十-D4：不可变，`put` 返回新实例） |
| `.../resolve/Resolver.java` `ResolveContext.java` `ResolverRegistry.java` | 解析 SPI 与唯一映射注册表 |
| `.../facet/FacetProvider.java` `FacetEntry.java` `FacetRegistry.java` | 跨模块可见性协议（spec §八） |
| `.../verify/RoundTripAssertions.java` | 往返不变式断言（main 源码，不依赖 JUnit） |

测试文件与实现同包，位于 `simos-util/src/test/java/io/mosire/simos/util/<pkg>/`。

## 任务地图（按依赖排序，逐个提交）

| # | 任务 | 交付物 |
|---|---|---|
| 1 | 地址段类型与 canonical 渲染 | 四种段 + `AddressText` |
| 2 | `Address.parse` / `canonical` + 冻结样例 | 解析器 + 14 条冻结样例核对（**执行期更正，2026-09-16**：本行原写 14 条，**与本计划 Task 2 Step 5 自己的"15 条冻结样例"不一致**；实际 spec §3.6 表是 **14 行、15 条**——`unit:U:hex` / `unit:U:speed` 同占一行。已落地的 `AddressParseTest.frozenSamplesRoundTrip` 是 **15** 个 `@ValueSource` 参数，surefire 报告亦为 15 条） |
| 3 | 身份：`SubjectId` / `ResolvedSubject` / `QueryResult` | identity 包 |
| 4 | 时间基础：`SimosTimestamp` / `TimeRange` | time 包（上半） |
| 5 | 外挂属性：`InfoEntry` / `InfoSystem` / `InMemoryInfoSystem` | info 包 |
| 6 | 版本坐标与三个协议 + `SimulationState` | state 包 |
| 7 | `TemporalSeries` 与 `SegmentedSeries` | time 包（下半，四条时间语义） |
| 8 | `Resolver` SPI 与注册表 | resolve 包 |
| 9 | Facet 协议与注册表 | facet 包 |
| 10 | 往返不变式框架 + 漂移自证 | verify 包（M1 硬判据） |
| 11 | M1 关账 | 全量 verify + 判据核对 + 状态同步 |

---

### Task 1: 地址段类型与 canonical 渲染

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/AddressText.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/AddressSegment.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/Namespace.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/Entity.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/Index.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/Property.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/address/AddressQuoteTest.java`

**Interfaces:**
- Consumes: 无（本任务是 M1 的第一步）
- Produces: `AddressSegment`（sealed，`String canonical()`）；`Namespace(String ident)`、`Entity(Optional<String> kind, String name)` + 工厂 `Entity.of(String name)` / `Entity.of(String kind, String name)`、`Index(List<Integer> coords)`、`Property(String ident)`；包内 `AddressText.isBareWord/quote/quoteIfNeeded`

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** spec §3.4：三条"按需加引"条件、`""` 转义，与段类型的构造期校验（§3.2）。 */
class AddressQuoteTest {

  @Test
  void bareWordNamesAreNotQuoted() {
    assertThat(Entity.of("hex", "4_3").canonical()).isEqualTo("hex.4_3");
    assertThat(Entity.of("region", "Nation.区域A").canonical()).isEqualTo("region.Nation.区域A");
  }

  @Test
  void namesWithStructuralCharactersAreQuoted() {
    assertThat(Entity.of("region", "河口:渡口").canonical()).isEqualTo("region.\"河口:渡口\"");
    assertThat(Entity.of("region", "[前线]").canonical()).isEqualTo("region.\"[前线]\"");
    assertThat(Entity.of("region", " 区域A ").canonical()).isEqualTo("region.\" 区域A \"");
    assertThat(Entity.of("region", "").canonical()).isEqualTo("region.\"\"");
  }

  @Test
  void quoteCharacterIsDoubled() {
    assertThat(Entity.of("region", "A\"B").canonical()).isEqualTo("region.\"A\"\"B\"");
  }

  @Test
  void kindlessDottedNameIsQuoted() {
    assertThat(Entity.of("高地人旅指挥部.1营指挥部").canonical())
        .isEqualTo("\"高地人旅指挥部.1营指挥部\"");
    assertThat(Entity.of("Map1").canonical()).isEqualTo("Map1");
  }

  @Test
  void kindMustBeBareWord() {
    assertThat(Entity.of("hex", "4_3").kind()).contains("hex");
    assertThat(Entity.of("Map1").kind()).isEmpty();
    assertThatThrownBy(() -> Entity.of("a.b", "x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Entity.of("a b", "x")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void namespaceIndexAndPropertyRenderCanonically() {
    assertThat(new Namespace("map").canonical()).isEqualTo("map");
    assertThat(new Index(List.of(4, 3)).canonical()).isEqualTo("[4,3]");
    assertThat(new Index(List.of(7)).canonical()).isEqualTo("[7]");
    assertThat(new Property("population_growth").canonical()).isEqualTo("population_growth");
    assertThatThrownBy(() -> new Namespace("a:b")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Property("a b")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest=AddressQuoteTest test`
Expected: 编译失败——`cannot find symbol: class AddressSegment`（Java 里新类型的第一个失败就是编译错误，正常）

- [ ] **Step 3: 实现词法工具与四种段**

`AddressText.java`（包内可见，不对外暴露）：

```java
package io.mosire.simos.util.address;

/** 地址词法工具：裸词判定、引号转义与"按需加引"（spec §3.3/§3.4）。包内可见。 */
final class AddressText {

  private AddressText() {}

  /** 裸词：非空、无首尾空白、且不含 `: . [ ] "`。 */
  static boolean isBareWord(String s) {
    if (s == null || s.isEmpty() || !s.equals(s.strip())) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      switch (s.charAt(i)) {
        case ':', '.', '[', ']', '"' -> {
          return false;
        }
        default -> {}
      }
    }
    return true;
  }

  /** 无条件加引：内部 `"` 写成 `""`。 */
  static String quote(String s) {
    return '"' + s.replace("\"", "\"\"") + '"';
  }

  /**
   * 名字的规范写法：命中 spec §3.4 三条加引条件才加引。
   *
   * @param kindAbsent 该名字所属的 Entity 是否缺省了 kind（条件 3：缺 kind 且名字含 `.` 必须加引）
   */
  static String quoteIfNeeded(String name, boolean kindAbsent) {
    boolean needs =
        name.isEmpty()
            || !name.equals(name.strip())
            || name.indexOf(':') >= 0
            || name.indexOf('[') >= 0
            || name.indexOf(']') >= 0
            || name.indexOf('"') >= 0
            || (kindAbsent && name.indexOf('.') >= 0);
    return needs ? quote(name) : name;
  }
}
```

`AddressSegment.java`：

```java
package io.mosire.simos.util.address;

/** 地址的一段。段间用 `:` 分隔，段内用 `.` 分隔 kind 与 name（spec §3.2）。 */
public sealed interface AddressSegment permits Namespace, Entity, Index, Property {

  /** 本段的规范写法（canonical，按需加引，spec §3.4）。 */
  String canonical();
}
```

`Namespace.java`：

```java
package io.mosire.simos.util.address;

/**
 * 命名空间段（`map` / `social` / `unit` / `agent`）：地址的第 1 段，必须是裸词（spec §3.2）。
 */
public record Namespace(String ident) implements AddressSegment {

  public Namespace {
    if (!AddressText.isBareWord(ident)) {
      throw new IllegalArgumentException("命名空间必须是裸词（非空、无首尾空白、不含 : . [ ] \"）：" + ident);
    }
  }

  @Override
  public String canonical() {
    return ident;
  }
}
```

`Entity.java`：

```java
package io.mosire.simos.util.address;

import java.util.Objects;
import java.util.Optional;

/**
 * 实体段：`kind.name`（`hex.4_3`）或根主体名（`Map1`，kind 缺省）。
 *
 * <p>kind 必须是裸词——它是结构词（类型判别用），任意字符的载体是 name（spec §3.2/§3.4）。
 */
public record Entity(Optional<String> kind, String name) implements AddressSegment {

  public Entity {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    if (kind.isPresent() && !AddressText.isBareWord(kind.get())) {
      throw new IllegalArgumentException("kind 必须是裸词（非空、无首尾空白、不含 : . [ ] \"）：" + kind.get());
    }
  }

  /** 缺省 kind 的实体（根主体，或 Human 形式里省略类型词的实体）。 */
  public static Entity of(String name) {
    return new Entity(Optional.empty(), name);
  }

  public static Entity of(String kind, String name) {
    return new Entity(Optional.of(kind), name);
  }

  @Override
  public String canonical() {
    return kind.map(k -> k + ".").orElse("") + AddressText.quoteIfNeeded(name, kind.isEmpty());
  }
}
```

`Index.java`：

```java
package io.mosire.simos.util.address;

import java.util.List;
import java.util.stream.Collectors;

/** 索引段：`[q,r]` 或 `[i]`（Human 形式；canonical 的单个 hex 用 `hex.4_3` 形式）。 */
public record Index(List<Integer> coords) implements AddressSegment {

  public Index {
    coords = List.copyOf(coords);
    if (coords.isEmpty()) {
      throw new IllegalArgumentException("Index 段至少一个坐标");
    }
  }

  @Override
  public String canonical() {
    return coords.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
  }
}
```

`Property.java`：

```java
package io.mosire.simos.util.address;

/**
 * 属性段：`population` / `height` / `member`——地址第 3 段起的裸词（spec §3.2）。
 */
public record Property(String ident) implements AddressSegment {

  public Property {
    if (!AddressText.isBareWord(ident)) {
      throw new IllegalArgumentException("属性名必须是裸词（非空、无首尾空白、不含 : . [ ] \"）：" + ident);
    }
  }

  @Override
  public String canonical() {
    return ident;
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest=AddressQuoteTest test`
Expected: PASS（6 个用例）

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/address/ simos-util/src/test/java/io/mosire/simos/util/address/
git diff --cached --stat   # 确认只有本任务的 7 个文件
git commit -m "feat(util): 地址段类型与按需加引的 canonical 渲染（M1 Task 1）"
```

---

### Task 2: `Address.parse` / `canonical` 与冻结样例

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/Address.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/address/AddressParser.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/address/AddressParseTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/address/AddressTolerantParseTest.java`
- Modify: `simos-util/src/test/java/io/mosire/simos/util/address/AddressQuoteTest.java`（补"冗余引号归一"用例，spec §3.4 后半）

**Interfaces:**
- Consumes: Task 1 的四种段 + `AddressText`
- Produces: `Address(List<AddressSegment> segments)`，`Address.parse(String)`、`Address.canonical()`、`Address.namespace()`

- [ ] **Step 1: 写失败测试（冻结样例 + 段类型判定）**

```java
package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** spec §3.6：总纲 §4.4 的冻结样例逐条核对——解析结果与 canonical 往返。 */
class AddressParseTest {

  /** 冻结样例（逐字抄自 spec §3.6 表）。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "map:Map1",
        "map:Map1:hex.4_3",
        "map:Map1:terra.Grass",
        "map:Map1:terra.Grass:height",
        "map:Map1:region.Nation.区域A",
        "map:Map1:region.Nation.区域A:hexes",
        "map:Map1:conn.river.r-f82a",
        "social:Map1:hex.4_3:population",
        "social:Map1:hex.4_3:population_growth",
        "unit:U:member",
        "unit:U:equipment.步枪",
        "unit:U:hex",
        "unit:U:speed",
        "agent:bind.b-f82a",
        "agent:map:Map1:region.Nation.区域A"
      })
  void frozenSamplesRoundTrip(String canonical) {
    assertThat(Address.parse(canonical).canonical()).isEqualTo(canonical);
  }

  @Test
  void secondSegmentIsRootSubjectWithAbsentKind() {
    Address a = Address.parse("map:Map1");
    assertThat(a.namespace()).isEqualTo("map");
    assertThat(a.segments()).hasSize(2);
    assertThat(a.segments().get(1)).isEqualTo(Entity.of("Map1"));
  }

  @Test
  void kindNameSplitHappensOnFirstUnquotedDot() {
    assertThat(Address.parse("map:Map1:region.Nation.区域A").segments().get(2))
        .isEqualTo(Entity.of("region", "Nation.区域A"));
    assertThat(Address.parse("map:Map1:conn.river.r-f82a").segments().get(2))
        .isEqualTo(Entity.of("conn", "river.r-f82a"));
  }

  @Test
  void bareWordAfterPositionTwoIsProperty() {
    assertThat(Address.parse("unit:U:member").segments().get(2)).isEqualTo(new Property("member"));
    assertThat(Address.parse("map:Map1:region.Nation.区域A:hexes").segments().get(3))
        .isEqualTo(new Property("hexes"));
  }

  @Test
  void indexSegmentIsParsed() {
    assertThat(Address.parse("map:Map1:[4,3]").segments().get(2)).isEqualTo(new Index(List.of(4, 3)));
    assertThat(Address.parse("map:Map1:[7]").segments().get(2)).isEqualTo(new Index(List.of(7)));
  }

  @Test
  void quotedKindlessComponentsCollapseIntoOneName() {
    assertThat(Address.parse("map:Map1:\"Nation\".\"区域A\"").segments().get(2))
        .isEqualTo(Entity.of("Nation.区域A"));
  }

  @Test
  void addressRequiresAtLeastTwoSegmentsAndNamespaceFirst() {
    assertThat(Address.parse("map:Map1").segments().get(0)).isInstanceOf(Namespace.class);
    assertThatThrownBy(() -> new Address(List.of(Entity.of("Map1"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Address(List.of(Entity.of("Map1"), new Property("x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

再给 Task 1 建的 `AddressQuoteTest` 追加"归一"用例（spec §3.4 后半：引号只用于消歧，不进 AST）：

```java
  @Test
  void redundantQuotesAreNormalizedAway() {
    Address quoted = Address.parse("map:Map1:region.\"Nation\".\"区域A\"");
    Address plain = Address.parse("map:Map1:region.Nation.区域A");
    assertThat(quoted).isEqualTo(plain);
    assertThat(quoted.canonical()).isEqualTo("map:Map1:region.Nation.区域A");
  }
```

- [ ] **Step 2: 写失败测试（宽容写法与非法形态）**

```java
package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** spec §3.5：宽容只限"引号可省/可冗余"与 `.[` 一处兼容写法；其余一律明确报错。 */
class AddressTolerantParseTest {

  @Test
  void strayDotBeforeIndexIsTolerated() {
    Address a = Address.parse("social:Map1.[4,3]:population");
    assertThat(a.segments())
        .containsExactly(
            new Namespace("social"),
            Entity.of("Map1"),
            new Index(List.of(4, 3)),
            new Property("population"));
    assertThat(a.canonical()).isEqualTo("social:Map1:[4,3]:population");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "map", // 段数 < 2
        "map:", // 空段
        ":Map1", // 空段（首段为空）
        "map::Map1", // 空段
        "map:M[a", // 名字未加引却含 `[`
        "map:a b", // 含空格却不是裸词、也未加引
        "map:[]", // 空 Index
        "map:[4,]", // 坐标不是整数
        "map:[4,x]", // 坐标不是整数
        "map:[4,3", // Index 未闭合
        "map:\"a", // 引号未闭合
      })
  void malformedAddressesAreRejected(String text) {
    assertThatThrownBy(() -> Address.parse(text)).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest='Address*Test' test`
Expected: 编译失败——`cannot find symbol: class Address`

- [ ] **Step 4: 实现 `Address`**

```java
package io.mosire.simos.util.address;

import java.util.List;

/**
 * 地址：段序列。首段是命名空间、第 2 段是该命名空间的根主体（spec §3.2）。
 *
 * <p>解析宽容（人类形式），{@link #canonical()} 唯一（机器协议只用这个，spec §3.4）。
 */
public record Address(List<AddressSegment> segments) {

  public Address {
    segments = List.copyOf(segments);
    if (segments.size() < 2) {
      throw new IllegalArgumentException("地址至少两段（命名空间 + 根主体）：" + segments);
    }
    if (!(segments.get(0) instanceof Namespace)) {
      throw new IllegalArgumentException("地址首段必须是命名空间：" + segments.get(0));
    }
  }

  public static Address parse(String text) {
    return AddressParser.parse(text);
  }

  /** 首段的命名空间标识，例如 `map`。 */
  public String namespace() {
    return ((Namespace) segments.get(0)).ident();
  }

  public String canonical() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segments.size(); i++) {
      if (i > 0) {
        sb.append(':');
      }
      sb.append(segments.get(i).canonical());
    }
    return sb.toString();
  }
}
```

- [ ] **Step 5: 实现 `AddressParser`**

```java
package io.mosire.simos.util.address;

import java.util.ArrayList;
import java.util.List;

/**
 * 地址解析：切段（`:`，引号内的不切）、宽容写法（`.[`）、按位置与表面形式判定段类型（spec §3.2/§3.5）。
 * 包内可见——对外只有 {@link Address#parse(String)}。
 */
final class AddressParser {

  private AddressParser() {}

  static Address parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("地址不得为空");
    }
    List<String> tokens = new ArrayList<>();
    List<String> raw = splitOnColons(text);
    for (int i = 0; i < raw.size(); i++) {
      String token = raw.get(i);
      if (token.isEmpty()) {
        throw new IllegalArgumentException("第 " + (i + 1) + " 段为空：`" + text + "`");
      }
      tokens.addAll(splitTolerantDot(token, text));
    }
    if (tokens.size() < 2) {
      throw new IllegalArgumentException("地址至少两段（命名空间 + 根主体）：`" + text + "`");
    }
    List<AddressSegment> segments = new ArrayList<>(tokens.size());
    for (int i = 0; i < tokens.size(); i++) {
      segments.add(toSegment(tokens.get(i), i, text));
    }
    return new Address(segments);
  }

  /** 按 `:` 切段；引号内的 `:` 按字面处理（连续两个 `"` 是转义，来回抵消）。 */
  private static List<String> splitOnColons(String text) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (c == ':' && !inQuotes) {
        out.add(cur.toString());
        cur.setLength(0);
        continue;
      }
      cur.append(c);
    }
    if (inQuotes) {
      throw new IllegalArgumentException("引号未闭合：`" + text + "`");
    }
    out.add(cur.toString());
    return out;
  }

  /** 兼容写法：未加引号的 `.` 紧跟 `[` 时在此断开（`Map1.[4,3]` → `Map1` + `[4,3]`）。 */
  private static List<String> splitTolerantDot(String token, String whole) {
    int idx = indexOfUnquotedDotBeforeBracket(token);
    if (idx < 0) {
      return List.of(token);
    }
    String left = token.substring(0, idx);
    String right = token.substring(idx + 1);
    if (left.isEmpty() || right.isEmpty()) {
      throw new IllegalArgumentException("兼容写法 `.` `[` 的两侧不得为空：`" + token + "`（地址：`" + whole + "`）");
    }
    return List.of(left, right);
  }

  private static int indexOfUnquotedDotBeforeBracket(String token) {
    boolean inQuotes = false;
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (!inQuotes && c == '.' && i + 1 < token.length() && token.charAt(i + 1) == '[') {
        return i;
      }
    }
    return -1;
  }

  /** 段类型判定（spec §3.2 表）：位置 + 表面形式，没有隐式兜底。 */
  private static AddressSegment toSegment(String token, int position, String whole) {
    if (position == 0) {
      if (!AddressText.isBareWord(token)) {
        throw new IllegalArgumentException("首段必须是裸词的命名空间：`" + token + "`（地址：`" + whole + "`）");
      }
      return new Namespace(token);
    }
    if (token.charAt(0) == '[') {
      return toIndex(token, whole);
    }
    int dot = indexOfUnquotedDot(token);
    if (dot < 0) {
      if (isQuoted(token)) {
        return Entity.of(unquote(token));
      }
      if (!AddressText.isBareWord(token)) {
        throw new IllegalArgumentException(
            "第 " + (position + 1) + " 段既不是裸词也不是引号包裹的名字：`" + token + "`（地址：`" + whole + "`）");
      }
      return position == 1 ? Entity.of(token) : new Property(token);
    }
    String left = token.substring(0, dot);
    String right = token.substring(dot + 1);
    if (isQuoted(left)) {
      return Entity.of(nameParts(token, whole));
    }
    if (!AddressText.isBareWord(left)) {
      throw new IllegalArgumentException(
          "kind 必须是裸词或整段加引：`" + left + "`（地址：`" + whole + "`）");
    }
    return Entity.of(left, nameParts(right, whole));
  }

  private static Index toIndex(String token, String whole) {
    if (!token.endsWith("]")) {
      throw new IllegalArgumentException("Index 段未闭合：`" + token + "`（地址：`" + whole + "`）");
    }
    String body = token.substring(1, token.length() - 1);
    if (body.isEmpty()) {
      throw new IllegalArgumentException("Index 段不得为空：`" + token + "`（地址：`" + whole + "`）");
    }
    List<Integer> coords = new ArrayList<>();
    for (String part : body.split(",", -1)) {
      try {
        coords.add(Integer.parseInt(part.strip()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Index 坐标不是整数：`" + part + "`（地址：`" + whole + "`）", e);
      }
    }
    return new Index(coords);
  }

  /** 第一个不在引号内的 `.`，没有则返回 -1。 */
  private static int indexOfUnquotedDot(String token) {
    return indexOfUnquotedDotFrom(token, 0);
  }

  private static int indexOfUnquotedDotFrom(String token, int from) {
    boolean inQuotes = false;
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (!inQuotes && c == '.' && i >= from) {
        return i;
      }
    }
    return -1;
  }

  /** 名字部分：按未加引号的 `.` 切组件，各组件去引号后以 `.` 连接（spec §3.2）。 */
  private static String nameParts(String part, String whole) {
    StringBuilder sb = new StringBuilder();
    int start = 0;
    while (true) {
      int dot = indexOfUnquotedDotFrom(part, start);
      String component = dot < 0 ? part.substring(start) : part.substring(start, dot);
      sb.append(componentText(component, whole));
      if (dot < 0) {
        return sb.toString();
      }
      sb.append('.');
      start = dot + 1;
    }
  }

  private static String componentText(String component, String whole) {
    if (isQuoted(component)) {
      return unquote(component);
    }
    if (AddressText.isBareWord(component)) {
      return component;
    }
    throw new IllegalArgumentException(
        "名字组件必须加引号（含 : [ ] \" 或空白）：`" + component + "`（地址：`" + whole + "`）");
  }

  /** 整个 token 是否恰好是一个引号包裹的段（`""` 转义计入内容）。 */
  private static boolean isQuoted(String s) {
    if (s.length() < 2 || s.charAt(0) != '"') {
      return false;
    }
    int i = 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"') {
        if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
          i += 2;
          continue;
        }
        return i == s.length() - 1;
      }
      i++;
    }
    return false;
  }

  /** 引号包裹 token 的内容：`""` → `"`。 */
  private static String unquote(String s) {
    return s.substring(1, s.length() - 1).replace("\"\"", "\"");
  }
}
```

- [ ] **Step 6: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest='Address*Test' test`
Expected: PASS（15 条冻结样例 + 段判定用例 + 11 条非法形态）

- [ ] **Step 7: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/address/Address.java simos-util/src/main/java/io/mosire/simos/util/address/AddressParser.java simos-util/src/test/java/io/mosire/simos/util/address/
git diff --cached --stat
git commit -m "feat(util): Address 解析（宽容）与 canonical 渲染，冻结样例逐条核对（M1 Task 2）"
```

---

### Task 3: 身份（`identity` 包）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/identity/SubjectId.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/identity/ResolvedSubject.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/identity/QueryResult.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/identity/SubjectIdTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/identity/ResolvedSubjectTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/identity/QueryResultTest.java`

**Interfaces:**
- Consumes: `Address`（Task 2）——`ResolvedSubject` 用它守"canonicalAddress 必须是 canonical"
- Produces: `SubjectId(String namespace, String localId)`；`ResolvedSubject(SubjectId id, String canonicalAddress, String typeName)`；`QueryResult(List<ResolvedSubject> candidates)`（`candidates` 保序、不可变）

- [ ] **Step 1: 写失败测试**

`SubjectIdTest.java`：

```java
package io.mosire.simos.util.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** spec §四：稳定身份的值语义；命名层级由各模块自定，Util 不校验。 */
class SubjectIdTest {

  @Test
  void valueSemantics() {
    assertThat(new SubjectId("map.hex", "h-0001")).isEqualTo(new SubjectId("map.hex", "h-0001"));
    assertThat(new SubjectId("map.hex", "h-0001"))
        .hasSameHashCodeAs(new SubjectId("map.hex", "h-0001"));
    assertThat(new SubjectId("map.hex", "h-0001").toString()).contains("map.hex", "h-0001");
  }

  @Test
  void blankPartsAreRejected() {
    assertThatThrownBy(() -> new SubjectId(" ", "h-0001"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SubjectId("map.hex", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void namespaceHierarchyIsFreeForm() {
    // §四：`<模块>` 或 `<模块>.<类型>` 都由模块自定，Util 不校验层级
    assertThat(new SubjectId("unit", "U-1").namespace()).isEqualTo("unit");
    assertThat(new SubjectId("map.region", "R-1").namespace()).isEqualTo("map.region");
  }
}
```

`ResolvedSubjectTest.java`：

```java
package io.mosire.simos.util.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** spec §四：候选 = 稳定 ID + canonical 地址 + 展示用类型名。 */
class ResolvedSubjectTest {

  @Test
  void carriesIdentityAddressAndType() {
    ResolvedSubject s = hexSubject("map:Map1:hex.4_3");
    assertThat(s.id()).isEqualTo(new SubjectId("map.hex", "h-0001"));
    assertThat(s.canonicalAddress()).isEqualTo("map:Map1:hex.4_3");
    assertThat(s.typeName()).isEqualTo("Hex");
  }

  @Test
  void valueSemantics() {
    assertThat(hexSubject("map:Map1:hex.4_3")).isEqualTo(hexSubject("map:Map1:hex.4_3"));
    assertThatThrownBy(() -> new ResolvedSubject(new SubjectId("map.hex", "h-0001"), " ", "Hex"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResolvedSubject(new SubjectId("map.hex", "h-0001"), "map:Map1", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonCanonicalAddressIsRejected() {
    // §四：调用方选定后一律回传 canonical。宽容写法不得成为 Resolver 的输出。
    assertThatThrownBy(
            () -> new ResolvedSubject(new SubjectId("social.population", "p-1"), "social:Map1.[4,3]:population", "Population"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical");
  }

  private static ResolvedSubject hexSubject(String address) {
    return new ResolvedSubject(new SubjectId("map.hex", "h-0001"), address, "Hex");
  }
}
```

`QueryResultTest.java`：

```java
package io.mosire.simos.util.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** spec §四：候选列表保序、不可变（Human 形式可多解，全部列出）。 */
class QueryResultTest {

  @Test
  void candidatesKeepInsertionOrder() {
    ResolvedSubject first = subject("h-0001");
    ResolvedSubject second = subject("h-0002");
    assertThat(new QueryResult(List.of(first, second)).candidates()).containsExactly(first, second);
    assertThat(new QueryResult(List.of()).candidates()).isEmpty();
  }

  @Test
  void candidatesAreDefensivelyCopied() {
    List<ResolvedSubject> mutable = new ArrayList<>();
    mutable.add(subject("h-0001"));
    QueryResult result = new QueryResult(mutable);
    mutable.clear();
    assertThat(result.candidates()).hasSize(1);
    assertThatThrownBy(() -> result.candidates().add(subject("h-0002")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static ResolvedSubject subject(String localId) {
    return new ResolvedSubject(new SubjectId("map.hex", localId), "map:Map1:hex.4_3", "Hex");
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest='SubjectIdTest,ResolvedSubjectTest,QueryResultTest' test`
Expected: 编译失败——`cannot find symbol: class SubjectId`

- [ ] **Step 3: 实现三个 record**

`SubjectId.java`：

```java
package io.mosire.simos.util.identity;

/**
 * 稳定身份：`namespace` + `localId`（铁律 1——地址是定位方式，ID 是身份）。
 *
 * <p>`namespace` 取 `<模块>` 或 `<模块>.<类型>`（`map.hex` / `unit.equipment`），层级由各模块自定，Util 不校验；
 * `localId` 的生成规则（前缀、长度、随机源）同样属于各领域模块。
 */
public record SubjectId(String namespace, String localId) {

  public SubjectId {
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("SubjectId.namespace 不得为空白");
    }
    if (localId == null || localId.isBlank()) {
      throw new IllegalArgumentException("SubjectId.localId 不得为空白");
    }
  }
}
```

`ResolvedSubject.java`：

```java
package io.mosire.simos.util.identity;

import io.mosire.simos.util.address.Address;

/**
 * 解析结果的候选项：稳定 ID + canonical 地址 + 展示/路由用类型名（spec §四）。
 *
 * <p>地址必须是 canonical 形式——机器协议只用 canonical，宽容写法在解析入口就被归一掉了。
 */
public record ResolvedSubject(SubjectId id, String canonicalAddress, String typeName) {

  public ResolvedSubject {
    if (id == null) {
      throw new IllegalArgumentException("ResolvedSubject.id 不得为 null");
    }
    if (canonicalAddress == null || canonicalAddress.isBlank()) {
      throw new IllegalArgumentException("ResolvedSubject.canonicalAddress 不得为空白");
    }
    if (!Address.parse(canonicalAddress).canonical().equals(canonicalAddress)) {
      throw new IllegalArgumentException(
          "canonicalAddress 必须是 canonical 形式：" + canonicalAddress);
    }
    if (typeName == null || typeName.isBlank()) {
      throw new IllegalArgumentException("ResolvedSubject.typeName 不得为空白");
    }
  }
}
```

`QueryResult.java`：

```java
package io.mosire.simos.util.identity;

import java.util.List;

/** 解析结果：候选列表，保序（spec §四）。Human 形式可多解，全部列出；空列表 = 没有候选，不是错误。 */
public record QueryResult(List<ResolvedSubject> candidates) {

  public QueryResult {
    candidates = List.copyOf(candidates);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest='SubjectIdTest,ResolvedSubjectTest,QueryResultTest' test`
Expected: PASS

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/identity/ simos-util/src/test/java/io/mosire/simos/util/identity/
git diff --cached --stat
git commit -m "feat(util): SubjectId/ResolvedSubject/QueryResult 身份三件（M1 Task 3）"
```

---

### Task 4: 时间基础（`SimosTimestamp` / `TimeRange`）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/SimosTimestamp.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/TimeRange.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/time/SimosTimestampTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/time/TimeRangeTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `SimosTimestamp(long tick, Optional<String> calendarLabel)` + `of(long)` / `of(long, String)` / `plus(long)`；`TimeRange(SimosTimestamp from, Optional<SimosTimestamp> to)` + `since(SimosTimestamp)` / `contains(SimosTimestamp)`

**计划期新增的两条细则**（spec §五 只写了 `tick` 是第一序与"左闭右开"，未写这两条，Task 11 回填 spec）：

1. `TimeRange.to` 必须**严格晚于** `from`（左闭右开区间不得为空），否则构造期抛 `IllegalArgumentException`。
2. `equals` 含 `calendarLabel`，`compareTo` 只看 `tick`——两者口径不同是**有意**的（§十一 禁手写 `equals`）。判"同刻"一律用 `compareTo == 0`。

- [ ] **Step 1: 写失败测试**

`SimosTimestampTest.java`：

```java
package io.mosire.simos.util.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** spec §五：tick 是第一序、plus 保留 label、类型本身不提供复写入口。 */
class SimosTimestampTest {

  @Test
  void orderingUsesTickOnly() {
    assertThat(SimosTimestamp.of(3)).isEqualByComparingTo(SimosTimestamp.of(3, "第 3 日"));
    assertThat(SimosTimestamp.of(2)).isLessThan(SimosTimestamp.of(3, "第 3 日"));
    assertThat(SimosTimestamp.of(0, "第 0 日")).isGreaterThan(SimosTimestamp.of(-1));
  }

  @Test
  void plusAdvancesAndRewindsWhileKeepingTheLabel() {
    SimosTimestamp t = SimosTimestamp.of(10, "第 10 日");
    assertThat(t.plus(5)).isEqualTo(SimosTimestamp.of(15, "第 10 日"));
    assertThat(t.plus(-7)).isEqualTo(SimosTimestamp.of(3, "第 10 日"));
    assertThat(t).isEqualTo(SimosTimestamp.of(10, "第 10 日")); // 原实例不变
  }

  @Test
  void equalityStillIncludesTheLabelWhereasOrderingDoesNot() {
    // 计划期新增细则 2：同 tick 的两种写法排序相等但不 equals，判"同刻"用 compareTo == 0。
    assertThat(SimosTimestamp.of(3)).isEqualByComparingTo(SimosTimestamp.of(3, "第 3 日"));
    assertThat(SimosTimestamp.of(3)).isNotEqualTo(SimosTimestamp.of(3, "第 3 日"));
  }

  @Test
  void sortingWorks() {
    assertThat(
            Stream.of(SimosTimestamp.of(3), SimosTimestamp.of(1), SimosTimestamp.of(2))
                .sorted()
                .map(SimosTimestamp::tick)
                .collect(java.util.stream.Collectors.toList()))
        .containsExactly(1L, 2L, 3L);
  }
}
```

`TimeRangeTest.java`：

```java
package io.mosire.simos.util.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** spec §十-D5：有效区间左闭右开；`to` 缺省 = 无上界。 */
class TimeRangeTest {

  @Test
  void intervalIsHalfOpen() {
    TimeRange range = new TimeRange(SimosTimestamp.of(10), Optional.of(SimosTimestamp.of(20)));
    assertThat(range.contains(SimosTimestamp.of(9))).isFalse();
    assertThat(range.contains(SimosTimestamp.of(10))).isTrue(); // 左闭
    assertThat(range.contains(SimosTimestamp.of(19))).isTrue();
    assertThat(range.contains(SimosTimestamp.of(20))).isFalse(); // 右开
  }

  @Test
  void absentToMeansUnbounded() {
    TimeRange range = TimeRange.since(SimosTimestamp.of(10));
    assertThat(range.contains(SimosTimestamp.of(10))).isTrue();
    assertThat(range.contains(SimosTimestamp.of(1_000_000))).isTrue();
    assertThat(range.contains(SimosTimestamp.of(9))).isFalse();
  }

  @Test
  void emptyOrInvertedIntervalIsRejected() {
    assertThatThrownBy(
            () -> new TimeRange(SimosTimestamp.of(10), Optional.of(SimosTimestamp.of(10))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new TimeRange(SimosTimestamp.of(10), Optional.of(SimosTimestamp.of(5))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest='SimosTimestampTest,TimeRangeTest' test`
Expected: 编译失败——`cannot find symbol: class SimosTimestamp`

- [ ] **Step 3: 实现两个 record**

`SimosTimestamp.java`：

```java
package io.mosire.simos.util.time;

import java.util.Objects;
import java.util.Optional;

/**
 * 模拟时间戳：`tick` 是第一序，`calendarLabel` 仅作展示（spec §五）。时间戳与版本正交
 * （`RevisionId` 管数据版本），两把尺子互不换算。
 *
 * <p><b>排序与相等的口径不同，这是有意的</b>：{@link #compareTo} 只看 `tick`，而 record 的
 * {@link #equals} 含全部组件。判"同刻"一律用 {@code compareTo == 0}，不要用 `equals`。
 *
 * <p>没有 setter：推进只经 {@link #plus(long)}（总纲 §4.9）。
 */
public record SimosTimestamp(long tick, Optional<String> calendarLabel)
    implements Comparable<SimosTimestamp> {

  public SimosTimestamp {
    Objects.requireNonNull(calendarLabel, "calendarLabel");
  }

  public static SimosTimestamp of(long tick) {
    return new SimosTimestamp(tick, Optional.empty());
  }

  public static SimosTimestamp of(long tick, String calendarLabel) {
    return new SimosTimestamp(tick, Optional.of(calendarLabel));
  }

  /** 推进（负值即回拨），保留 label。 */
  public SimosTimestamp plus(long delta) {
    return new SimosTimestamp(tick + delta, calendarLabel);
  }

  @Override
  public int compareTo(SimosTimestamp other) {
    return Long.compare(tick, other.tick);
  }
}
```

`TimeRange.java`：

```java
package io.mosire.simos.util.time;

import java.util.Objects;
import java.util.Optional;

/**
 * 有效区间：**左闭右开** `[from, to)`；`to` 缺省表示无上界（spec §十-D5）。
 *
 * <p>`to` 必须严格晚于 `from`——空区间是配置错误，不给它静默存在的机会。
 */
public record TimeRange(SimosTimestamp from, Optional<SimosTimestamp> to) {

  public TimeRange {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    to.ifPresent(
        end -> {
          if (end.compareTo(from) <= 0) {
            throw new IllegalArgumentException(
                "TimeRange 的 to 必须晚于 from（左闭右开区间不得为空）：" + from + " .. " + end);
          }
        });
  }

  /** 自 `from` 起、无上界的区间。 */
  public static TimeRange since(SimosTimestamp from) {
    return new TimeRange(from, Optional.empty());
  }

  /** `t` 是否落在 `[from, to)` 内（同刻判定用 {@code compareTo}，见 {@link SimosTimestamp}）。 */
  public boolean contains(SimosTimestamp t) {
    if (t.compareTo(from) < 0) {
      return false;
    }
    return to.map(end -> t.compareTo(end) < 0).orElse(true);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest='SimosTimestampTest,TimeRangeTest' test`
Expected: PASS

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/time/ simos-util/src/test/java/io/mosire/simos/util/time/
git diff --cached --stat
git commit -m "feat(util): SimosTimestamp 与 TimeRange（M1 Task 4）"
```

---

### Task 5: 外挂时态属性（`info` 包）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/info/InfoEntry.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/info/InfoSystem.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/info/InMemoryInfoSystem.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/info/InMemoryInfoSystemTest.java`

**Interfaces:**
- Consumes: `Address`（Task 2）、`SubjectId`（Task 3）、`SimosTimestamp` / `TimeRange`（Task 4）
- Produces: `InfoEntry(String key, Object value, TimeRange valid, SubjectId source, Optional<String> note)`；`InfoSystem.get(Address, String key, SimosTimestamp) → Optional<InfoEntry>`、`InfoSystem put(Address, InfoEntry) → InfoSystem`；`InMemoryInfoSystem.empty()`；`InMemoryInfoSystem.put(...) → InMemoryInfoSystem`

**对总纲的偏离**（spec §十-D4，本节落地）：

- `subject` 用 **`Address`**（总纲 §4.6 原样）——Info 能挂到 `map` 命名空间本身、`map:Map1`、甚至 `map:Map1:terra.Grass:height` 上，这些锚点没有 `SubjectId`；`SubjectId` 只出现在 `InfoEntry.source`（这条信息的来源实体）。
- `get` **增 `key` 参数**（一个主体可同时挂多个 key）。
- `put` **返回新实例**（总纲草案是 `void put`）——否则 `SimulationState` 的 `equals()` 往返断言无从谈起（铁律 5）。

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.util.info;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** spec §十-D4：按 key + 时刻取值、有效期左闭右开、`put` 返回新实例。 */
class InMemoryInfoSystemTest {

  private static final Address HEX = Address.parse("map:Map1:hex.4_3");

  @Test
  void readsByKeyAndMoment() {
    InMemoryInfoSystem info =
        InMemoryInfoSystem.empty()
            .put(HEX, entry("alias", "河口渡口", TimeRange.since(SimosTimestamp.of(0))))
            .put(HEX, entry("icon", "anchor", TimeRange.since(SimosTimestamp.of(0))));
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(5))).map(InfoEntry::value).contains("河口渡口");
    assertThat(info.get(HEX, "icon", SimosTimestamp.of(5))).map(InfoEntry::value).contains("anchor");
    assertThat(info.get(HEX, "unknown", SimosTimestamp.of(5))).isEmpty();
    assertThat(info.get(Address.parse("map:Map1"), "alias", SimosTimestamp.of(5))).isEmpty();
  }

  @Test
  void validityWindowIsHalfOpen() {
    InMemoryInfoSystem info =
        InMemoryInfoSystem.empty()
            .put(
                HEX,
                entry(
                    "alias",
                    "旧名",
                    new TimeRange(SimosTimestamp.of(0), Optional.of(SimosTimestamp.of(10)))))
            .put(HEX, entry("alias", "新名", TimeRange.since(SimosTimestamp.of(10))));
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(9))).map(InfoEntry::value).contains("旧名");
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(10)))
        .map(InfoEntry::value)
        .contains("新名"); // 切换点归新条目
  }

  @Test
  void theLastInsertedOverlappingEntryWins() {
    InMemoryInfoSystem info =
        InMemoryInfoSystem.empty()
            .put(HEX, entry("alias", "先写的", TimeRange.since(SimosTimestamp.of(0))))
            .put(HEX, entry("alias", "后写的", TimeRange.since(SimosTimestamp.of(0))));
    assertThat(info.get(HEX, "alias", SimosTimestamp.of(1)))
        .map(InfoEntry::value)
        .contains("后写的");
  }

  @Test
  void putReturnsANewInstanceAndLeavesTheOriginalUntouched() {
    InMemoryInfoSystem base = InMemoryInfoSystem.empty();
    InMemoryInfoSystem next = base.put(HEX, entry("alias", "甲", TimeRange.since(SimosTimestamp.of(0))));
    assertThat(base.get(HEX, "alias", SimosTimestamp.of(0))).isEmpty();
    assertThat(next.get(HEX, "alias", SimosTimestamp.of(0))).isPresent();
  }

  private static InfoEntry entry(String key, Object value, TimeRange valid) {
    return new InfoEntry(key, value, valid, new SubjectId("map.hex", "h-0001"), Optional.empty());
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest=InMemoryInfoSystemTest test`
Expected: 编译失败——`cannot find symbol: class InMemoryInfoSystem`

- [ ] **Step 3: 实现三个类型**

`InfoEntry.java`：

```java
package io.mosire.simos.util.info;

import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.time.TimeRange;
import java.util.Objects;
import java.util.Optional;

/**
 * 一条外挂信息：`key` + 结构化值 + 有效期 + 来源（总纲 §4.6）。
 *
 * <p>**Info ≠ 领域字段**：凡影响领域计算的（`TerraType.height` 影响河流生成）都是领域字段，
 * 不许走这条路；否则 Info 会退化成绕过领域模型的 JSON 垃圾桶（总纲 §4.6）。
 *
 * <p>`value` 是结构化 `Object`，不是预格式化字符串。
 */
public record InfoEntry(String key, Object value, TimeRange valid, SubjectId source, Optional<String> note) {

  public InfoEntry {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("InfoEntry.key 不得为空白");
    }
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(valid, "valid");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(note, "note");
  }
}
```

`InfoSystem.java`：

```java
package io.mosire.simos.util.info;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Optional;

/**
 * 外挂时态属性系统（总纲 §4.6）：**不挂在任何 Java 对象上**，是"地址 → 信息"的外挂关系。
 *
 * <p>实现必须不可变——{@link #put} 返回新实例（spec §十-D4），否则 `equals()` 往返断言无从谈起。
 */
public interface InfoSystem {

  /** 取 `subject` 上 `key` 在时刻 `at` 有效的那条；同一 key 重叠时**插入序最后者胜**。 */
  Optional<InfoEntry> get(Address subject, String key, SimosTimestamp at);

  /** 追加一条（新开有效期的写法就是"改值"）；返回新实例，原实例不变。 */
  InfoSystem put(Address subject, InfoEntry entry);
}
```

`InMemoryInfoSystem.java`：

```java
package io.mosire.simos.util.info;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** {@link InfoSystem} 的内存实现：写时复制，读不修改任何状态。 */
public final class InMemoryInfoSystem implements InfoSystem {

  private final Map<Address, List<InfoEntry>> bySubject;

  private InMemoryInfoSystem(Map<Address, List<InfoEntry>> bySubject) {
    this.bySubject = bySubject;
  }

  public static InMemoryInfoSystem empty() {
    return new InMemoryInfoSystem(Map.of());
  }

  @Override
  public Optional<InfoEntry> get(Address subject, String key, SimosTimestamp at) {
    InfoEntry found = null;
    for (InfoEntry entry : bySubject.getOrDefault(subject, List.of())) {
      if (entry.key().equals(key) && entry.valid().contains(at)) {
        found = entry;
      }
    }
    return Optional.ofNullable(found);
  }

  @Override
  public InMemoryInfoSystem put(Address subject, InfoEntry entry) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(entry, "entry");
    Map<Address, List<InfoEntry>> next = new LinkedHashMap<>(bySubject);
    List<InfoEntry> entries = new ArrayList<>(next.getOrDefault(subject, List.of()));
    entries.add(entry);
    next.put(subject, List.copyOf(entries));
    return new InMemoryInfoSystem(Map.copyOf(next));
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest=InMemoryInfoSystemTest test`
Expected: PASS

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/info/ simos-util/src/test/java/io/mosire/simos/util/info/
git diff --cached --stat
git commit -m "feat(util): InfoEntry/InfoSystem/InMemoryInfoSystem（M1 Task 5）"
```

---

### Task 6: 版本坐标、三个协议接口与 `SimulationState`

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/BranchId.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/RevisionId.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/StateRef.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/StateMeta.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/Snapshot.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/ChangeSet.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/Command.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/state/SimulationState.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/state/StateRefTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/state/SnapshotProtocolTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/state/SimulationStateTest.java`

**Interfaces:**
- Consumes: `SimosTimestamp`（Task 4）、`InfoSystem` / `InMemoryInfoSystem`（Task 5）
- Produces: `BranchId(String value)`；`RevisionId(long value)`（`Comparable`）；`StateRef(BranchId, RevisionId)`；`StateMeta(StateRef, SimosTimestamp)`；`Snapshot{ref(), timestamp(), namespace()}`；`ChangeSet{baseRevision()}`；`Command{expectedRevision()}`；`SimulationState(StateMeta, Map<String,Snapshot>, InfoSystem)` + `module(String) → Optional<Snapshot>`

- [ ] **Step 1: 写失败测试**

`StateRefTest.java`：

```java
package io.mosire.simos.util.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/** spec §五：`RevisionId` 只在分支内有意义，跨分支的完整坐标是 `StateRef`。 */
class StateRefTest {

  @Test
  void branchAndRevisionFormTheCoordinate() {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(7));
    assertThat(ref.branch().value()).isEqualTo("main");
    assertThat(ref.revision().value()).isEqualTo(7);
    assertThat(ref).isEqualTo(new StateRef(new BranchId("main"), new RevisionId(7)));
  }

  @Test
  void revisionsAreOrdered() {
    assertThat(new RevisionId(7)).isGreaterThan(new RevisionId(6));
    assertThat(new RevisionId(7)).isEqualByComparingTo(new RevisionId(7));
  }

  @Test
  void blankBranchIsRejected() {
    assertThatThrownBy(() -> new BranchId(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stateMetaCarriesRefAndTimestamp() {
    StateMeta meta =
        new StateMeta(
            new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(3, "第 3 日"));
    assertThat(meta.ref().revision()).isEqualTo(new RevisionId(1));
    assertThat(meta.timestamp().tick()).isEqualTo(3);
  }
}
```

`SnapshotProtocolTest.java`：

```java
package io.mosire.simos.util.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/** spec §六：三个协议接口保持最小——不用万能父类，ChangeSet/Command 都是单方法接口。 */
class SnapshotProtocolTest {

  /** 玩具快照：证明模块快照只需实现三个方法即可接入协议。 */
  private record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int alpha)
      implements Snapshot {}

  @Test
  void toySnapshotImplementsTheThreeProtocolMethods() {
    ToySnapshot snapshot =
        new ToySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(2)), SimosTimestamp.of(1), "toy", 42);
    assertThat(snapshot.ref().revision()).isEqualTo(new RevisionId(2));
    assertThat(snapshot.timestamp().tick()).isEqualTo(1);
    assertThat(snapshot.namespace()).isEqualTo("toy");
    assertThat(snapshot.alpha()).isEqualTo(42);
  }

  @Test
  void changeSetAndCommandExposeTheirStamps() {
    ChangeSet changeSet = () -> new RevisionId(2);
    Command command = () -> new RevisionId(2);
    assertThat(changeSet.baseRevision()).isEqualTo(new RevisionId(2));
    assertThat(command.expectedRevision()).isEqualTo(new RevisionId(2));
  }
}
```

`SimulationStateTest.java`：

```java
package io.mosire.simos.util.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** spec §六：`module(namespace)` 是唯一取用入口；无跨模块访问器。 */
class SimulationStateTest {

  @Test
  void moduleIsTheOnlyLookup() {
    SimulationState state = stateWith(toySnapshot("map", 1), toySnapshot("unit", 2));
    assertThat(state.module("map")).map(Snapshot::namespace).contains("map");
    assertThat(state.module("unit")).map(Snapshot::namespace).contains("unit");
    assertThat(state.module("social")).isEmpty();
  }

  @Test
  void moduleKeysMustMatchSnapshotNamespace() {
    assertThatThrownBy(
            () ->
                new SimulationState(
                    meta(), Map.of("map", toySnapshot("unit", 1)), InMemoryInfoSystem.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void modulesMapIsDefensivelyCopied() {
    Map<String, Snapshot> mutable = new HashMap<>();
    mutable.put("map", toySnapshot("map", 1));
    SimulationState state = new SimulationState(meta(), mutable, InMemoryInfoSystem.empty());
    mutable.clear();
    assertThat(state.modules()).hasSize(1);
    assertThatThrownBy(() -> state.modules().put("unit", toySnapshot("unit", 2)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void stateExposesNoCrossModuleAccessor() {
    // 铁律 3/4：跨模块可见性只走 Facet。公开方法只有下面这些——
    // 任何"返回领域类型的便捷访问器"（state.map() 之类）都会让本用例红。
    assertThat(publicMethodNames(SimulationState.class))
        .containsExactlyInAnyOrder(
            "meta", "modules", "info", "module", "equals", "hashCode", "toString");
  }

  @Test
  void theAccessorCheckFlagsAReplicaThatHasOne() {
    // G13 自证：同一个检查器对故意违规的夹具必须报出多余的方法。
    assertThat(publicMethodNames(NonCompliantState.class)).contains("map");
    assertThat(new NonCompliantState().map()).isEmpty();
  }

  private static List<String> publicMethodNames(Class<?> type) {
    return Arrays.stream(type.getDeclaredMethods())
        .filter(m -> Modifier.isPublic(m.getModifiers()))
        .map(Method::getName)
        .sorted()
        .distinct()
        .toList();
  }

  /** 故意违规夹具（G13 的自证对象）：一个挂跨模块便捷访问器的假状态类型。 */
  private static final class NonCompliantState {

    public Map<String, Snapshot> map() {
      return Map.of();
    }
  }

  private static SimulationState stateWith(Snapshot... snapshots) {
    Map<String, Snapshot> modules = new HashMap<>();
    for (Snapshot snapshot : snapshots) {
      modules.put(snapshot.namespace(), snapshot);
    }
    return new SimulationState(meta(), modules, InMemoryInfoSystem.empty());
  }

  private static Snapshot toySnapshot(String namespace, int revision) {
    return new ToySnapshot(
        new StateRef(new BranchId("main"), new RevisionId(revision)),
        SimosTimestamp.of(revision),
        namespace,
        revision);
  }

  private static StateMeta meta() {
    return new StateMeta(new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(1));
  }

  private record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int alpha)
      implements Snapshot {}
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest='StateRefTest,SnapshotProtocolTest,SimulationStateTest' test`
Expected: 编译失败——`cannot find symbol: class StateRef`

- [ ] **Step 3: 实现四个坐标类型与三个协议接口**

`BranchId.java`：

```java
package io.mosire.simos.util.state;

/** 分支标识（总纲 §4.1）：`RevisionId` 只在分支内有意义。 */
public record BranchId(String value) {

  public BranchId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("BranchId.value 不得为空白");
    }
  }
}
```

`RevisionId.java`：

```java
package io.mosire.simos.util.state;

/** 数据版本号：只管数据版本，与模拟时间（`SimosTimestamp`）互不换算（总纲 §0.1）。 */
public record RevisionId(long value) implements Comparable<RevisionId> {

  @Override
  public int compareTo(RevisionId other) {
    return Long.compare(value, other.value);
  }
}
```

`StateRef.java`：

```java
package io.mosire.simos.util.state;

import java.util.Objects;

/** 状态的唯一坐标：分支 + 版本（总纲 §4.1）。 */
public record StateRef(BranchId branch, RevisionId revision) {

  public StateRef {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(revision, "revision");
  }
}
```

`StateMeta.java`：

```java
package io.mosire.simos.util.state;

import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/** 整个世界状态的坐标：版本坐标 + 模拟时刻（spec §五）。 */
public record StateMeta(StateRef ref, SimosTimestamp timestamp) {

  public StateMeta {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(timestamp, "timestamp");
  }
}
```

`Snapshot.java`：

```java
package io.mosire.simos.util.state;

import io.mosire.simos.util.time.SimosTimestamp;

/**
 * 模块状态切片（总纲 §4.5）：**不用万能父类**，各模块的快照是各自独立的 record 树。
 *
 * <p>`namespace()` 是 spec §十-D1 的增补——Util 不认识领域类型，只能靠它把切片与模块对上号。
 */
public interface Snapshot {

  StateRef ref();

  SimosTimestamp timestamp();

  /** 本快照所属模块（`"map"` / `"social"` / `"unit"`）。 */
  String namespace();
}
```

`ChangeSet.java`：

```java
package io.mosire.simos.util.state;

/**
 * 变更集（总纲 §4.5）：字段清单由**各模块从自己的 Snapshot 类型派生**（铁律 5），
 * Util 只给接口与往返断言工具（{@code io.mosire.simos.util.verify.RoundTripAssertions}）。
 */
public interface ChangeSet {

  RevisionId baseRevision();
}
```

`Command.java`：

```java
package io.mosire.simos.util.state;

/**
 * 命令（总纲 §4.5）：保持最小——只有乐观并发所需的 `expectedRevision()`。
 *
 * <p>`commandId` / `correlationId` / 发起者属于**命令信封**（总纲 §8.1），由 Core 的 Command Bus 承担（spec §十-D6）。
 */
public interface Command {

  RevisionId expectedRevision();
}
```

- [ ] **Step 4: 实现 `SimulationState`**

```java
package io.mosire.simos.util.state;

import io.mosire.simos.util.info.InfoSystem;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 整个模拟状态：元信息 + 各模块切片 + 外挂信息（spec §六）。
 *
 * <p>**不提供跨模块访问器**（没有 `state.map().units()` 这类入口）：跨模块可见性只走 Facet
 * （铁律 3/4）。这条由 {@code SimulationStateTest} 的反射用例钉住。
 */
public record SimulationState(StateMeta meta, Map<String, Snapshot> modules, InfoSystem info) {

  public SimulationState {
    Objects.requireNonNull(meta, "meta");
    Objects.requireNonNull(modules, "modules");
    Objects.requireNonNull(info, "info");
    modules = Map.copyOf(modules);
    for (Map.Entry<String, Snapshot> entry : modules.entrySet()) {
      String namespace = entry.getValue().namespace();
      if (!entry.getKey().equals(namespace)) {
        throw new IllegalArgumentException(
            "modules 的键必须等于该快照的 namespace()：键=" + entry.getKey() + "，快照=" + namespace);
      }
    }
  }

  /** 唯一的取用入口。 */
  public Optional<Snapshot> module(String namespace) {
    return Optional.ofNullable(modules.get(namespace));
  }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest='StateRefTest,SnapshotProtocolTest,SimulationStateTest' test`
Expected: PASS

- [ ] **Step 6: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/state/ simos-util/src/test/java/io/mosire/simos/util/state/
git diff --cached --stat
git commit -m "feat(util): 版本坐标、三个协议接口与 SimulationState（M1 Task 6）"
```

---

### Task 7: `TemporalSeries` 与 `SegmentedSeries`

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/TemporalSeries.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/Segment.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/Event.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/EventMode.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/time/SegmentedSeries.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/time/TemporalSeriesTest.java`

**Interfaces:**
- Consumes: `SimosTimestamp`（Task 4）
- Produces: `TemporalSeries<T>{valueAt(SimosTimestamp), segments(), events()}`；`Segment<T>(SimosTimestamp from, T value)`；`Event<T>(SimosTimestamp at, T value, EventMode mode)`；`EventMode{ADD, SET}`；`SegmentedSeries.of(List<Segment<T>>, List<Event<T>>, BinaryOperator<T>)`

**本节的四条时间语义**（spec §七，逐条可测）：段边界**左闭右开**；anchor 之前**向前恒定延拓**；同刻**先切段、再施加事件**；同刻多事件按**插入序**。**不做插值**——段是阶跃常量。

> **取代说明（2026-09-16 执行期，用例数）**：本节 Step 1 的草图是**计划期下限**——已落地的
> `TemporalSeriesTest` 是 **16** 条（草图 9 条）。派发前逐条补齐了 G13 守卫自证：null 守卫要钉**字段级消息**、
> 同一个共享 `addition` 构建的两个同构序列**相等**、两个等价但不同的 lambda 构建的序列**不相等**。

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.util.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** spec §七：四条时间语义逐条钉死。 */
class TemporalSeriesTest {

  @Test
  void segmentBoundariesAreHalfOpen() {
    TemporalSeries<Long> series = series(List.of(segment(0, 100L), segment(10, 200L)), List.of());
    assertThat(series.valueAt(SimosTimestamp.of(9))).isEqualTo(100L);
    assertThat(series.valueAt(SimosTimestamp.of(10))).isEqualTo(200L); // 切换点归新段
    assertThat(series.valueAt(SimosTimestamp.of(1_000))).isEqualTo(200L); // 最后一段延伸到无穷
  }

  @Test
  void beforeTheFirstSegmentTheValueIsExtendedBackwardsConstantly() {
    TemporalSeries<Long> series = series(List.of(segment(10, 5L)), List.of());
    assertThat(series.valueAt(SimosTimestamp.of(10))).isEqualTo(5L);
    assertThat(series.valueAt(SimosTimestamp.of(0))).isEqualTo(5L);
    assertThat(series.valueAt(SimosTimestamp.of(-99))).isEqualTo(5L);
  }

  @Test
  void atTheSwitchPointTheSegmentIsAppliedBeforeTheEvents() {
    TemporalSeries<Long> series =
        series(List.of(segment(0, 100L), segment(10, 200L)), List.of(event(10, 5L, EventMode.ADD)));
    assertThat(series.valueAt(SimosTimestamp.of(10))).isEqualTo(205L); // 先切到 200，再加 5
  }

  @Test
  void addAccumulatesAndSetOverrides() {
    TemporalSeries<Long> additive =
        series(
            List.of(segment(0, 100L)),
            List.of(event(3, 5L, EventMode.ADD), event(5, 7L, EventMode.ADD)));
    assertThat(additive.valueAt(SimosTimestamp.of(2))).isEqualTo(100L);
    assertThat(additive.valueAt(SimosTimestamp.of(3))).isEqualTo(105L);
    assertThat(additive.valueAt(SimosTimestamp.of(5))).isEqualTo(112L);

    TemporalSeries<Long> overridden =
        series(List.of(segment(0, 100L)), List.of(event(5, 42L, EventMode.SET)));
    assertThat(overridden.valueAt(SimosTimestamp.of(5))).isEqualTo(42L);
  }

  @Test
  void eventsAtTheSameMomentApplyInInsertionOrder() {
    TemporalSeries<Long> additiveThenSet =
        series(
            List.of(segment(0, 100L)),
            List.of(event(5, 10L, EventMode.ADD), event(5, 0L, EventMode.SET)));
    assertThat(additiveThenSet.valueAt(SimosTimestamp.of(5))).isEqualTo(0L);

    TemporalSeries<Long> setThenAdditive =
        series(
            List.of(segment(0, 100L)),
            List.of(event(5, 0L, EventMode.SET), event(5, 10L, EventMode.ADD)));
    assertThat(setThenAdditive.valueAt(SimosTimestamp.of(5))).isEqualTo(10L);
  }

  @Test
  void futureEventsAreNotApplied() {
    TemporalSeries<Long> series =
        series(List.of(segment(0, 100L)), List.of(event(5, 10L, EventMode.ADD)));
    assertThat(series.valueAt(SimosTimestamp.of(4))).isEqualTo(100L);
    assertThat(series.valueAt(SimosTimestamp.of(5))).isEqualTo(110L);
  }

  @Test
  void addEventsWithoutAdditionFailAtConstruction() {
    assertThatThrownBy(
            () ->
                SegmentedSeries.of(
                    List.of(segment(0, 100L)), List.of(event(5, 10L, EventMode.ADD)), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("addition");
    // 没有 ADD 事件时 addition 可省（SET 不需要算术）
    assertThat(
            SegmentedSeries.of(
                    List.of(segment(0, 100L)), List.of(event(5, 42L, EventMode.SET)), null)
                .valueAt(SimosTimestamp.of(5)))
        .isEqualTo(42L);
  }

  @Test
  void malformedSeriesAreRejectedAtConstruction() {
    assertThatThrownBy(() -> SegmentedSeries.of(List.of(), List.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SegmentedSeries.of(List.of(segment(10, 1L), segment(10, 2L)), List.of(), null))
        .isInstanceOf(IllegalArgumentException.class); // 同刻两段
    assertThatThrownBy(
            () ->
                SegmentedSeries.of(
                    List.of(segment(0, 1L)),
                    List.of(event(5, 1L, EventMode.SET), event(3, 2L, EventMode.SET)),
                    null))
        .isInstanceOf(IllegalArgumentException.class); // 事件时刻回退
  }

  @Test
  void segmentsAndEventsAreDefensivelyCopied() {
    List<Segment<Long>> mutableSegments = new ArrayList<>(List.of(segment(0, 100L)));
    TemporalSeries<Long> series = SegmentedSeries.of(mutableSegments, List.of(), null);
    mutableSegments.clear();
    assertThat(series.segments()).hasSize(1);
    assertThatThrownBy(() -> series.segments().add(segment(1, 1L)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static TemporalSeries<Long> series(List<Segment<Long>> segments, List<Event<Long>> events) {
    return SegmentedSeries.of(segments, events, Long::sum);
  }

  private static Segment<Long> segment(long from, long value) {
    return new Segment<>(SimosTimestamp.of(from), value);
  }

  private static Event<Long> event(long at, long value, EventMode mode) {
    return new Event<>(SimosTimestamp.of(at), value, mode);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest=TemporalSeriesTest test`
Expected: 编译失败——`cannot find symbol: class TemporalSeries`

- [ ] **Step 3: 实现协议与数据载体**

`TemporalSeries.java`：

```java
package io.mosire.simos.util.time;

import java.util.List;

/**
 * 时态序列：分段常量 + 离散事件（总纲 §4.7）。
 *
 * <p>**不做插值**：段内恒定，只在段边界或事件处跳变。连续变化（如人口那种积分型序列）由
 * SocialSimos 自己实现本接口，本模块只保证四条时间语义被它继承（spec §七）。
 */
public interface TemporalSeries<T> {

  /** 即时计算，不物化中间点。 */
  T valueAt(SimosTimestamp t);

  /** 分段常量，按 `from` 严格升序。 */
  List<Segment<T>> segments();

  /** 离散跳变，按插入序（同刻多事件即"插入序"）。 */
  List<Event<T>> events();
}
```

`Segment.java`：

```java
package io.mosire.simos.util.time;

import java.util.Objects;

/** 一段阶跃常量：自 `from` 起取值 `value`，直到下一段的 `from`（左闭右开，spec §七）。 */
public record Segment<T>(SimosTimestamp from, T value) {

  public Segment {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(value, "value");
  }
}
```

`Event.java`：

```java
package io.mosire.simos.util.time;

import java.util.Objects;

/** 一个离散跳变：时刻 `at`、值 `value`、方式 `mode`（spec §七）。 */
public record Event<T>(SimosTimestamp at, T value, EventMode mode) {

  public Event {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(mode, "mode");
  }
}
```

`EventMode.java`：

```java
package io.mosire.simos.util.time;

/** 事件施加方式：`ADD` 需调用方注入加法，`SET` 直接覆盖（spec §七）。 */
public enum EventMode {
  ADD,
  SET
}
```

- [ ] **Step 4: 实现 `SegmentedSeries`**

> **取代说明（2026-09-16 执行期；不要照抄下面的草图）**：草图是 `public final class SegmentedSeries<T>`
> ——私有构造器、显式字段、**校验与拷贝全在 `of` 里**（私有构造器自身不校验、不拷贝）。而 **spec §七 强制它是
> record，不是 `final class`**：往返断言（铁律 5）要拿它当判据，`equals` 必须由 record 提供、**禁手写**（spec §十一）。
> 已落地的是 **record + 紧凑构造器校验 + `List.copyOf` 防御拷贝**——record 的规范构造器是公开的，
> 校验放进紧凑构造器才能让 `new` 与 `of` 两条入口受同一套守卫约束；且**不**手写 `segments()` / `events()` 覆盖。
> 代价照旧：`addition` 按**身份**比较（spec §七 已述）。

```java
package io.mosire.simos.util.time;

import java.util.List;
import java.util.Objects;
import java.util.function.BinaryOperator;

/**
 * 通用实现：分段常量 + 离散事件（spec §七）。
 *
 * <p>四条时间语义：段边界左闭右开；anchor 之前向前恒定延拓；同刻先切段再施加事件；同刻多事件按插入序。
 * `ADD` 的算术由调用方以 {@link BinaryOperator} 注入——Util 不把 `T` 限制成数字。
 */
public final class SegmentedSeries<T> implements TemporalSeries<T> {

  private final List<Segment<T>> segments;
  private final List<Event<T>> events;
  private final BinaryOperator<T> addition;

  private SegmentedSeries(
      List<Segment<T>> segments, List<Event<T>> events, BinaryOperator<T> addition) {
    this.segments = segments;
    this.events = events;
    this.addition = addition;
  }

  /**
   * @param segments 至少一段（第一段即 anchor），按 `from` 严格升序
   * @param events 按 `at` 非递减；同刻多事件按给定顺序施加
   * @param addition `ADD` 的加法；序列含 `ADD` 事件时**不得为 null**
   */
  public static <T> SegmentedSeries<T> of(
      List<Segment<T>> segments, List<Event<T>> events, BinaryOperator<T> addition) {
    Objects.requireNonNull(segments, "segments");
    Objects.requireNonNull(events, "events");
    List<Segment<T>> copiedSegments = List.copyOf(segments);
    List<Event<T>> copiedEvents = List.copyOf(events);
    if (copiedSegments.isEmpty()) {
      throw new IllegalArgumentException("序列至少一个段（第一段即 anchor）");
    }
    requireStrictlyAscending(copiedSegments);
    requireNonDecreasing(copiedEvents);
    if (addition == null && copiedEvents.stream().anyMatch(e -> e.mode() == EventMode.ADD)) {
      throw new IllegalArgumentException("含 ADD 事件的序列必须在构造期提供 addition（spec §七）");
    }
    return new SegmentedSeries<>(copiedSegments, copiedEvents, addition);
  }

  @Override
  public T valueAt(SimosTimestamp t) {
    Objects.requireNonNull(t, "t");
    T value = baseValueAt(t);
    for (Event<T> event : events) {
      if (event.at().compareTo(t) <= 0) {
        value =
            event.mode() == EventMode.ADD ? addition.apply(value, event.value()) : event.value();
      }
    }
    return value;
  }

  @Override
  public List<Segment<T>> segments() {
    return segments;
  }

  @Override
  public List<Event<T>> events() {
    return events;
  }

  /** 段值：`t` 早于第一段则为第一段的值（向前恒定延拓）。 */
  private T baseValueAt(SimosTimestamp t) {
    T value = segments.get(0).value();
    for (Segment<T> segment : segments) {
      if (segment.from().compareTo(t) > 0) {
        break;
      }
      value = segment.value(); // t == from 即新段生效（左闭右开）
    }
    return value;
  }

  private static <T> void requireStrictlyAscending(List<Segment<T>> segments) {
    for (int i = 1; i < segments.size(); i++) {
      if (segments.get(i).from().compareTo(segments.get(i - 1).from()) <= 0) {
        throw new IllegalArgumentException(
            "段必须按 from 严格升序（同刻两段无法判定谁生效）："
                + segments.get(i - 1).from()
                + " → "
                + segments.get(i).from());
      }
    }
  }

  private static <T> void requireNonDecreasing(List<Event<T>> events) {
    for (int i = 1; i < events.size(); i++) {
      if (events.get(i).at().compareTo(events.get(i - 1).at()) < 0) {
        throw new IllegalArgumentException(
            "事件必须按 at 非递减给出（同刻多事件才谈得上插入序）："
                + events.get(i - 1).at()
                + " → "
                + events.get(i).at());
      }
    }
  }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest=TemporalSeriesTest test`
Expected: PASS（9 个用例）

- [ ] **Step 6: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/time/ simos-util/src/test/java/io/mosire/simos/util/time/
git diff --cached --stat
git commit -m "feat(util): TemporalSeries 与 SegmentedSeries，四条时间语义可测（M1 Task 7）"
```

---

### Task 8: `Resolver` SPI 与注册表（`resolve` 包）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/resolve/ResolveContext.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/resolve/Resolver.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/resolve/ResolverRegistry.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/resolve/ResolverRegistryTest.java`

**Interfaces:**
- Consumes: `Address`（Task 2）、`QueryResult` / `ResolvedSubject` / `SubjectId`（Task 3）、`SimosTimestamp`（Task 4）、`SimulationState`（Task 6）
- Produces: `ResolveContext(SimulationState state, SimosTimestamp at)`；`Resolver{namespace(), resolve(Address, ResolveContext)}`；`ResolverRegistry{register(Resolver), namespaces(), resolve(Address, ResolveContext)}`

**裁决口径**（spec §〇 第 3 项）：**namespace 唯一映射**——无顺序、无兜底、重复注册立即抛异常。与 GSimulator 的"多解析器按优先级遮蔽、不匹配就静默兜底"相反。

> **取代说明（2026-09-16 执行期，两处）**：
> ① **用例数是计划期下限**——下面的草图 4 条，已落地的 `ResolverRegistryTest` 是 **8** 条：派发时逐条补齐了
> null 守卫（`register(null)`、`resolver(null, …)`）与 `namespaces()` 的**快照语义**用例。
> ② **注册序反了，且是有意的**：草图写 `map` → `unit` 并断言 `containsExactly("map", "unit") // 注册序`——
> 但 `map` → `unit` **恰好等于字母序**，于是"注册序"那条断言换成 `TreeMap` 实现也照样绿（**空转护栏**）。
> 已落地的是 `unit` → `map` + `containsExactly("unit", "map") // 注册序，非排序`：只有真正保序的实现
> （`LinkedHashMap`）才通过。**照抄草图的顺序会把这条护栏重新变成装饰。**

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.util.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** spec §〇 裁决 3：namespace 唯一映射、无顺序、无兜底、重复注册立即抛异常。 */
class ResolverRegistryTest {

  @Test
  void dispatchesToTheResolverOfTheAddressNamespace() {
    ResolverRegistry registry = new ResolverRegistry();
    registry.register(resolver("map", "m-1"));
    registry.register(resolver("unit", "u-1"));
    assertThat(registry.namespaces()).containsExactly("map", "unit"); // 注册序
    assertThat(registry.resolve(Address.parse("unit:U"), context()).candidates().get(0).id().localId())
        .isEqualTo("u-1");
    assertThat(
            registry
                .resolve(Address.parse("map:Map1"), context())
                .candidates()
                .get(0)
                .id()
                .localId())
        .isEqualTo("m-1");
  }

  @Test
  void duplicateRegistrationIsRejected() {
    ResolverRegistry registry = new ResolverRegistry();
    registry.register(resolver("map", "m-1"));
    assertThatThrownBy(() -> registry.register(resolver("map", "m-2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("map");
  }

  @Test
  void unregisteredNamespaceIsRejectedWithNoFallback() {
    // 与 GSimulator 的"静默遮蔽"相反：没有注册就是错，不给兜底解析器。
    ResolverRegistry registry = new ResolverRegistry();
    assertThatThrownBy(() -> registry.resolve(Address.parse("map:Map1"), context()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("map");
  }

  @Test
  void blankNamespaceIsRejected() {
    ResolverRegistry registry = new ResolverRegistry();
    assertThatThrownBy(() -> registry.register(resolver(" ", "m-1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Resolver resolver(String namespace, String localId) {
    return new Resolver() {

      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public QueryResult resolve(Address address, ResolveContext ctx) {
        return new QueryResult(
            List.of(new ResolvedSubject(new SubjectId(namespace, localId), address.canonical(), "Toy")));
      }
    };
  }

  private static ResolveContext context() {
    return new ResolveContext(
        new SimulationState(
            new StateMeta(
                new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(1)),
            Map.of(),
            InMemoryInfoSystem.empty()),
        SimosTimestamp.of(1));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest=ResolverRegistryTest test`
Expected: 编译失败——`cannot find symbol: class Resolver`

- [ ] **Step 3: 实现三个类型**

`ResolveContext.java`：

```java
package io.mosire.simos.util.resolve;

import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/**
 * 解析上下文（spec §〇 附表）：`revision` 管数据版本、`at` 管模拟时间，两者正交。
 *
 * <p>**不加通用扩展袋**——需要什么就在这里显式长出来。
 */
public record ResolveContext(SimulationState state, SimosTimestamp at) {

  public ResolveContext {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(at, "at");
  }
}
```

`Resolver.java`：

```java
package io.mosire.simos.util.resolve;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;

/**
 * 命名空间解析器 SPI（总纲 §4.8）：各领域模块自己实现，Core 装配期注册。
 *
 * <p>返回**候选列表**（Human 形式可多解）；调用方选定后一律使用 canonical 地址。
 */
public interface Resolver {

  /** 本解析器负责的命名空间，与地址首段一致（`map` / `social` / `unit` / `agent`）。 */
  String namespace();

  QueryResult resolve(Address address, ResolveContext ctx);
}
```

`ResolverRegistry.java`：

```java
package io.mosire.simos.util.resolve;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 解析器注册表：**namespace 唯一映射**（spec §〇 裁决 3）。
 *
 * <p>注册顺序只影响 {@link #namespaces()} 的展示顺序，**不构成优先级**；重复注册立即抛异常，
 * 未知命名空间不给兜底。
 */
public final class ResolverRegistry {

  private final Map<String, Resolver> resolvers = new LinkedHashMap<>();

  public void register(Resolver resolver) {
    Objects.requireNonNull(resolver, "resolver");
    String namespace = resolver.namespace();
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("Resolver.namespace() 不得为空白");
    }
    if (resolvers.putIfAbsent(namespace, resolver) != null) {
      throw new IllegalArgumentException(
          "命名空间 " + namespace + " 已有解析器，不允许重复注册（注册表不设优先级）");
    }
  }

  /** 注册序。 */
  public List<String> namespaces() {
    return List.copyOf(resolvers.keySet());
  }

  /** 分发到地址首段对应的解析器；未注册的命名空间**抛异常，不兜底**。 */
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    Resolver resolver = resolvers.get(address.namespace());
    if (resolver == null) {
      throw new IllegalArgumentException(
          "没有注册命名空间 " + address.namespace() + " 的解析器（已注册：" + resolvers.keySet() + "）");
    }
    return resolver.resolve(address, ctx);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest=ResolverRegistryTest test`
Expected: PASS（4 个用例）

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/resolve/ simos-util/src/test/java/io/mosire/simos/util/resolve/
git diff --cached --stat
git commit -m "feat(util): Resolver SPI 与唯一映射注册表（M1 Task 8）"
```

---

### Task 9: Facet 协议与注册表（`facet` 包）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/facet/FacetProvider.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/facet/FacetEntry.java`
- Create: `simos-util/src/main/java/io/mosire/simos/util/facet/FacetRegistry.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/facet/FacetRegistryTest.java`

**Interfaces:**
- Consumes: `Address`（Task 2）、`ResolveContext`（Task 8）
- Produces: `FacetProvider{facetName(), query(Address, ResolveContext)}`；`FacetEntry(String namespace, String label, String typeName, Object value)`；`FacetRegistry{register(FacetProvider), facetNames(), queryAll(Address, ResolveContext)}`

**解决什么**：`inspect map:Map1:hex.4_3` 要能列出该 hex 上的单位，而 `MapSimos` 绝不能知道 `UnitSimos` 存在（铁律 3）。模块自注册提供者，`MapSimos` 对这些扩展完全不知情。

> **取代说明（2026-09-16 执行期，两处；照抄下面的草图必然编译过但跑不过）**：
> ① **草图自相矛盾**：Step 1 的 `queryAllConcatenatesInRegistrationOrder` 断言
> `registry.facetNames()).containsExactly("unit", "social") // 注册序`，而**同一张草图**的 Step 3 实现里
> `register` 是 `putIfAbsent(facetName, provider)`、`facetNames()` 返回 `keySet()`——**返回的是 facet 名，不是 namespace**。
> 断言与实现互斥，逐字照抄必跑不过。根因是 Step 1 里那个草图助手
> `provider(String namespace, String facetName, FacetEntry... entries)` 的第 1 参 **从未被引用**（死参）。
> 已落地的是 **`provider(String facetName, FacetEntry...)`（死参已删）+ `containsExactly("unitsHere", "population")`**。
> **⇒ 计划原文与已落地断言值不同，M2 照抄草图会重蹈覆辙。**（旁注一：草图那条 `// 注册序` 的**判别力论证是错的**——
> 它按 **namespace** 比较字母序（`social` < `unit`），而 `facetNames()` 返回的是 **facet 名**；正确的那条轴上是
> `"population"` < `"unitsHere"`。结论（`unitsHere` → `population` 非字母序）**恰好仍成立**——2026-09-16 以 jshell 实测：
> `List.of("unitsHere","population").stream().sorted()` → `[population, unitsHere]`，与已落地断言**相反**，故该断言对排序实现确有判别力。
> 旁注二：**这是"断言值与实现互斥"，不是"注释里顺序写错"**——照抄者会直接撞墙，且会先怀疑自己的环境。）
> ② **用例数是计划期下限**：草图 5 条，已落地的 `FacetRegistryTest` 是 **10** 条：派发时逐条补齐了
> 提供者返回 `null` 的守卫与 `facetNames()` 的**快照语义**用例。

- [ ] **Step 1: 写失败测试**

```java
package io.mosire.simos.util.facet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** spec §八：facetName 唯一；queryAll 按注册序；空列表不是错误。 */
class FacetRegistryTest {

  private static final Address HEX = Address.parse("map:Map1:hex.4_3");

  @Test
  void queryAllConcatenatesInRegistrationOrder() {
    FacetRegistry registry = new FacetRegistry();
    registry.register(provider("unit", "unitsHere", entry("unit", "unitsHere", List.of("U-1"))));
    registry.register(provider("social", "population", entry("social", "population", 10_000)));
    assertThat(registry.facetNames()).containsExactly("unit", "social"); // 注册序
    assertThat(registry.queryAll(HEX, context()).stream().map(FacetEntry::label).toList())
        .containsExactly("unitsHere", "population");
  }

  @Test
  void duplicateFacetNameIsRejected() {
    FacetRegistry registry = new FacetRegistry();
    registry.register(provider("unit", "unitsHere", entry("unit", "unitsHere", List.of())));
    assertThatThrownBy(
            () -> registry.register(provider("unit", "unitsHere", entry("unit", "unitsHere", List.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unitsHere");
  }

  @Test
  void aProviderWithNothingToSayIsNotAnError() {
    FacetRegistry registry = new FacetRegistry();
    registry.register(provider("unit", "unitsHere")); // 返回空列表
    assertThat(registry.queryAll(HEX, context())).isEmpty();
  }

  @Test
  void valuesStayStructured() {
    // spec §八：value 是结构化 Object，不是预先格式化的字符串。
    FacetEntry entry = entry("social", "population", Map.of("total", 10_000, "growth", 0.03));
    assertThat(entry.value()).isInstanceOf(Map.class);
    assertThat(new FacetRegistry().facetNames()).isEmpty();
  }

  @Test
  void blankOrNullPartsAreRejected() {
    assertThatThrownBy(() -> new FacetEntry(" ", "label", "Type", "v"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FacetEntry("unit", "label", "Type", null))
        .isInstanceOf(NullPointerException.class); // 空值走 Objects.requireNonNull，不裹成 IAE
  }

  private static FacetProvider provider(String namespace, String facetName, FacetEntry... entries) {
    return new FacetProvider() {

      @Override
      public String facetName() {
        return facetName;
      }

      @Override
      public List<FacetEntry> query(Address subject, ResolveContext ctx) {
        return List.of(entries);
      }
    };
  }

  private static FacetEntry entry(String namespace, String label, Object value) {
    return new FacetEntry(namespace, label, "Toy", value);
  }

  private static ResolveContext context() {
    return new ResolveContext(
        new SimulationState(
            new StateMeta(new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(1)),
            Map.of(),
            InMemoryInfoSystem.empty()),
        SimosTimestamp.of(1));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest=FacetRegistryTest test`
Expected: 编译失败——`cannot find symbol: class FacetProvider`

- [ ] **Step 3: 实现三个类型**

`FacetProvider.java`：

```java
package io.mosire.simos.util.facet;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.resolve.ResolveContext;
import java.util.List;

/**
 * 跨模块可见性协议（spec §八）：模块自己实现并注册，`MapSimos` 对提供者完全不知情。
 *
 * <p>"某个 hex 上有哪些单位"因此**不能**写成 `MapManager.getUnitsAt(hex)`（铁律 3）。
 */
public interface FacetProvider {

  /** 本面的名字，全注册表唯一（`"unitsHere"` / `"population"`）。 */
  String facetName();

  /** 空列表 = "该主体上我这一面没有内容"，不是错误。 */
  List<FacetEntry> query(Address subject, ResolveContext ctx);
}
```

`FacetEntry.java`：

```java
package io.mosire.simos.util.facet;

import java.util.Objects;

/**
 * 一条跨模块视图（spec §八）。
 *
 * @param namespace 贡献者的模块名（`unit` / `social`），用于分组与显示排序
 * @param label 显示标签
 * @param typeName 供 UI 着色/图标
 * @param value **结构化** `Object`（Jackson 可序列化），不是预先格式化的字符串——GUI / MCP / LLM
 *     三种消费者对同一个值有不同呈现需求，字符串化会把结构在 Core 层丢掉
 */
public record FacetEntry(String namespace, String label, String typeName, Object value) {

  public FacetEntry {
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("FacetEntry.namespace 不得为空白");
    }
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("FacetEntry.label 不得为空白");
    }
    if (typeName == null || typeName.isBlank()) {
      throw new IllegalArgumentException("FacetEntry.typeName 不得为空白");
    }
    Objects.requireNonNull(value, "value");
  }
}
```

`FacetRegistry.java`：

```java
package io.mosire.simos.util.facet;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.resolve.ResolveContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Facet 注册表：注册语义与 {@code ResolverRegistry} 一致——名字唯一映射、重复注册立即抛异常、无兜底。
 *
 * <p>{@link #queryAll} **按注册顺序**拼接，显示顺序因此是确定性的（由 Core 的装配顺序决定），不引入排序规则。
 */
public final class FacetRegistry {

  private final Map<String, FacetProvider> providers = new LinkedHashMap<>();

  public void register(FacetProvider provider) {
    Objects.requireNonNull(provider, "provider");
    String facetName = provider.facetName();
    if (facetName == null || facetName.isBlank()) {
      throw new IllegalArgumentException("FacetProvider.facetName() 不得为空白");
    }
    if (providers.putIfAbsent(facetName, provider) != null) {
      throw new IllegalArgumentException(
          "facetName " + facetName + " 已有提供者，不允许重复注册");
    }
  }

  /** 注册序。 */
  public List<String> facetNames() {
    return List.copyOf(providers.keySet());
  }

  /** 按注册顺序拼接所有提供者的结果；提供者返回空列表不报错。 */
  public List<FacetEntry> queryAll(Address subject, ResolveContext ctx) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(ctx, "ctx");
    List<FacetEntry> entries = new ArrayList<>();
    for (FacetProvider provider : providers.values()) {
      List<FacetEntry> fromProvider = provider.query(subject, ctx);
      if (fromProvider == null) {
        throw new IllegalStateException(
            "FacetProvider " + provider.facetName() + " 返回了 null，应返回空列表");
      }
      entries.addAll(fromProvider);
    }
    return List.copyOf(entries);
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest=FacetRegistryTest test`
Expected: PASS（5 个用例）

- [ ] **Step 5: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/facet/ simos-util/src/test/java/io/mosire/simos/util/facet/
git diff --cached --stat
git commit -m "feat(util): Facet 协议与注册表（M1 Task 9）"
```

---

### Task 10: 往返不变式框架（`verify` 包，M1 的硬判据）

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/verify/RoundTripAssertions.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsTest.java`
- Test: `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsDriftTest.java`

**Interfaces:**
- Consumes: `Snapshot` / `ChangeSet` / `RevisionId`（Task 6）
- Produces: `RoundTripAssertions.assertRoundTrip(S base, S target, BiFunction<S,S,C> diff, BiFunction<C,S,S> apply)`；`RoundTripAssertions.assertSnapshotRoundTrip(...)`（同形，`S extends Snapshot`）

**工具必须在 main 源码里**（M2~M4 都要用），因此**不能依赖 JUnit**——失败以 `AssertionError` 抛出。

> **取代说明（2026-09-16 执行期，两处；照抄下面的草图必然抛 AssertionError）**：
> ① **草图自相矛盾**：本节 Step 1 草图的 `ToySnapshot.apply` 写的是
> `new StateRef(base.ref().branch(), changeSet.baseRevision())`，而 `changeSet.baseRevision()` 是 **base 的**版本
> （`diff` 传的正是 `base.ref().revision()`）。于是 `apply` 产出的 ref **恒等于 base 的 ref**，
> 而本步所有用例都是 `base = ref(1)` / `target = ref(2)`——**`target` 永远不可达**，
> Step 5（`Expected: PASS`）那一次运行**不可能通过**：`aCorrectRoundTripPasses` 断言的是"不得抛出"，
> 而它必然抛 `AssertionError`。**2026-09-16 关账时以真实值类型在 jshell 上实跑证实**：
> `target.rev=2`、`appliedPerPlan.rev=1`、`target.equals(appliedPerPlan)=false`；
> 按已落地写法（`+1`）则 `applied.rev=2`、`equals=true`。
> 已落地的是产出**下一个**版本（`changeSet.baseRevision().value() + 1`），`DriftingSnapshot.apply`（Step 2）同改。
> ② **用例数是计划期下限**：草图 4 条，已落地的是 `RoundTripAssertionsTest` **6** 条 + `RoundTripAssertionsDriftTest` **1** 条
> （共 7 条）：派发时补齐了 null 返回守卫（`diff` / `apply` 各自，且 `assertSnapshotRoundTrip` 里那一份**单独**要有一条——
> 同一文件内的不对称即是证据）与"框架对 `S` 不设上界"的用例。

- [ ] **Step 1: 写失败测试（框架本身）**

```java
package io.mosire.simos.util.verify;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/** spec §九：正常往返通过、盖错版本戳抛错、破裂时给得出三份 toString。 */
class RoundTripAssertionsTest {

  @Test
  void aCorrectRoundTripPasses() {
    ToySnapshot base = new ToySnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    ToySnapshot target = new ToySnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    assertThatCode(
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base, target, ToySnapshot::diff, ToySnapshot::apply))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                RoundTripAssertions.assertSnapshotRoundTrip(
                    base, target, ToySnapshot::diff, ToySnapshot::apply))
        .doesNotThrowAnyException();
  }

  @Test
  void aMisStampedChangeSetIsRejected() {
    ToySnapshot base = new ToySnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    ToySnapshot target = new ToySnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertSnapshotRoundTrip(
                    base,
                    target,
                    (b, t) -> new ToyChangeSet(new RevisionId(999), t.timestamp(), t.alpha(), t.beta()),
                    ToySnapshot::apply))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("baseRevision");
  }

  @Test
  void aBrokenRoundTripReportsAllThreeStates() {
    ToySnapshot base = new ToySnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    ToySnapshot target = new ToySnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base,
                    target,
                    (b, t) ->
                        new ToyChangeSet(b.ref().revision(), t.timestamp(), t.alpha(), b.beta()),
                    ToySnapshot::apply))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("target")
        .hasMessageContaining("actual");
  }

  /** 玩具快照：证明框架不关心 `S` 是什么，只要它是 record 并实现 `Snapshot`。 */
  record ToySnapshot(StateRef ref, SimosTimestamp timestamp, String namespace, int alpha, int beta)
      implements Snapshot {

    static ToyChangeSet diff(ToySnapshot base, ToySnapshot target) {
      return new ToyChangeSet(
          base.ref().revision(), target.timestamp(), target.alpha(), target.beta());
    }

    static ToySnapshot apply(ToyChangeSet changeSet, ToySnapshot base) {
      return new ToySnapshot(
          new StateRef(base.ref().branch(), changeSet.baseRevision()),
          changeSet.timestamp(),
          base.namespace(),
          changeSet.alpha(),
          changeSet.beta());
    }
  }

  record ToyChangeSet(RevisionId baseRevision, SimosTimestamp timestamp, int alpha, int beta)
      implements ChangeSet {}

  private static StateRef ref(long revision) {
    return new StateRef(new BranchId("main"), new RevisionId(revision));
  }
}
```

- [ ] **Step 2: 写失败测试（**护栏自证**，spec §9.3 + G13）**

> **取代说明（2026-09-16 执行期，本 Step 的草图另有两处必须改）**：
> ① `DriftingSnapshot.apply` 里的 `new StateRef(base.ref().branch(), changeSet.baseRevision())` 同 Step 1 的错——
> 照抄时 applied 的 ref 比 target **低一版**，于是**即便 `diff` 把 `beta` 一并带上（漂移消失），`assertRoundTrip`
> 仍然会抛**：本用例的断言是"必须抛 `AssertionError`"，故它**照样通过**，但它证明的已不是"抓到了 beta 漂移"，
> 而是"ref 不匹配"——**判别力在无声中丢掉了**（"红了还要问为什么红"）。已落地为 `base.ref().revision().value() + 1`。
> ② `.hasMessageContaining("beta")` **零判别力**：报文里 base 行与 target 行是**无条件打印**的，两侧都带 `beta` 字样，
> 只钉字段名对"差异出在哪个字段"毫无鉴别。已落地为**钉 actual 那一整行**（`"  actual    = " + expectedActual`）——
> `apply` 的预期结果与 target **只差 beta**，由构造本身把这件事钉死，而不是靠子串碰巧满足。
> （更早一版的修法钉 `beta=9` / `beta=2` 两条具体值，仍不够：那两条 needle 分别被 base 行与 target 行满足，见已落地用例的注释。）

```java
package io.mosire.simos.util.verify;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import org.junit.jupiter.api.Test;

/**
 * G13（护栏必须自证）：**故意漏字段的变更集必须让往返断言响**。
 *
 * <p>这条路一旦断，往返护栏就只是装饰——L1 事故的四个漂移字段正是死在"没有用例证明它抓得住"上。
 */
class RoundTripAssertionsDriftTest {

  @Test
  void aChangeSetThatDropsAFieldMustBeCaught() {
    DriftingSnapshot base = new DriftingSnapshot(ref(1), SimosTimestamp.of(0), "toy", 1, 2);
    DriftingSnapshot target = new DriftingSnapshot(ref(2), SimosTimestamp.of(1), "toy", 5, 9);
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertRoundTrip(
                    base, target, DriftingSnapshot::diff, DriftingSnapshot::apply))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("beta");
  }

  /** 故意漂移的玩具快照：`beta` 在快照里有、在变更集里没有——L1 事故的最小重演。 */
  record DriftingSnapshot(
      StateRef ref, SimosTimestamp timestamp, String namespace, int alpha, int beta)
      implements Snapshot {

    static DriftingChangeSet diff(DriftingSnapshot base, DriftingSnapshot target) {
      return new DriftingChangeSet(base.ref().revision(), target.timestamp(), target.alpha()); // 漏了 beta
    }

    static DriftingSnapshot apply(DriftingChangeSet changeSet, DriftingSnapshot base) {
      return new DriftingSnapshot(
          new StateRef(base.ref().branch(), changeSet.baseRevision()),
          changeSet.timestamp(),
          base.namespace(),
          changeSet.alpha(),
          base.beta()); // beta 只能沿袭 base —— 这正是漂移的形态
    }
  }

  record DriftingChangeSet(RevisionId baseRevision, SimosTimestamp timestamp, int alpha)
      implements ChangeSet {}

  private static StateRef ref(long revision) {
    return new StateRef(new BranchId("main"), new RevisionId(revision));
  }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./mvnw -q -pl simos-util -Dtest='RoundTripAssertions*Test' test`
Expected: 编译失败——`cannot find symbol: class RoundTripAssertions`

- [ ] **Step 4: 实现 `RoundTripAssertions`**

```java
package io.mosire.simos.util.verify;

import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 往返不变式断言（铁律 5 / spec §九）。
 *
 * <p>为什么是测试而不是编译期：状态 record 加字段会让 `apply` 的全参重建**编译失败**（覆盖得了），
 * 但 `ChangeSet` 的字段清单漏字段**不会**编译失败——L1 事故里 `MapData` 加字段根本不会让 `MapDiff` 红。
 * 漏掉的那一半由 record 的 `equals()` 兜住：只要断言写成
 * `apply(diff(base, target), base).equals(target)`，任何漏在 ChangeSet/diff/apply 里的字段都会让测试红，
 * 不需要反射，也不随字段增长而失效。
 *
 * <p>本类位于 **main** 源码（M2~M4 都要用），因此不依赖 JUnit：失败以 {@link AssertionError} 抛出。
 */
public final class RoundTripAssertions {

  private RoundTripAssertions() {}

  /** 通用：任何状态类型（模块快照或整个 `SimulationState`）。 */
  public static <S, C extends ChangeSet> void assertRoundTrip(
      S base, S target, BiFunction<S, S, C> diff, BiFunction<C, S, S> apply) {
    C changeSet = Objects.requireNonNull(diff.apply(base, target), "diff 返回 null");
    checkApplied(base, target, changeSet, apply);
  }

  /** 快照专用：额外要求变更集**相对于它被施加的那个 base**——防止 diff 盖错版本戳。 */
  public static <S extends Snapshot, C extends ChangeSet> void assertSnapshotRoundTrip(
      S base, S target, BiFunction<S, S, C> diff, BiFunction<C, S, S> apply) {
    C changeSet = Objects.requireNonNull(diff.apply(base, target), "diff 返回 null");
    RevisionId declared = changeSet.baseRevision();
    RevisionId actual = base.ref().revision();
    if (!actual.equals(declared)) {
      throw new AssertionError(
          "变更集必须相对它被施加的 base：changeSet.baseRevision()="
              + declared
              + "，base.ref().revision()="
              + actual);
    }
    checkApplied(base, target, changeSet, apply);
  }

  private static <S, C extends ChangeSet> void checkApplied(
      S base, S target, C changeSet, BiFunction<C, S, S> apply) {
    S applied = Objects.requireNonNull(apply.apply(changeSet, base), "apply 返回 null");
    if (!target.equals(applied)) {
      throw new AssertionError(
          "往返不变式破裂：apply(diff(base, target), base) 与 target 不等。\n"
              + "  base      = "
              + base
              + "\n  target    = "
              + target
              + "\n  actual    = "
              + applied
              + "\n  changeSet = "
              + changeSet
              + "\n提示：ChangeSet（或 diff/apply 本身）漏了 target 比 base 多出来的字段——这正是 L1 事故的形态。");
    }
  }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -q -pl simos-util -Dtest='RoundTripAssertions*Test' test`
Expected: PASS（3 + 1 个用例；漂移用例证明护栏真的会响）

- [ ] **Step 6: 格式化并提交**

```bash
./mvnw -q spotless:apply
git add simos-util/src/main/java/io/mosire/simos/util/verify/ simos-util/src/test/java/io/mosire/simos/util/verify/
git diff --cached --stat
git commit -m "feat(util): 往返不变式框架 + 漂移自证（M1 Task 10）"
```

---

### Task 11: M1 关账

**Files:**
- Modify: `docs/superpowers/specs/2026-09-16-util-simos-design.md`（回填计划期新增细则）
- Modify: `docs/superpowers/plans/2026-09-16-simos-master-plan.md`（§二 M1 状态）
- Modify: `CLAUDE.md`（"当前状态"表）

- [ ] **Step 1: 全量门禁**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS（Spotless check + Checkstyle validate + SpotBugs verify + Surefire 全绿）。
**`mvn test` 不跑 SpotBugs**——关账必须以 `verify` 为准。

- [ ] **Step 2: 逐条核对关账判据（spec §1.2）**

| 判据 | 核对方式 |
|---|---|
| 八大件各有单测 | `Address*` / `SubjectIdTest` + `ResolvedSubjectTest` + `QueryResultTest` / `SimosTimestampTest` / `StateRefTest` / `SnapshotProtocolTest` / `SimulationStateTest` / `RoundTripAssertionsTest` / `InMemoryInfoSystemTest` / `TemporalSeriesTest` / `ResolverRegistryTest` / `FacetRegistryTest` 全绿 |
| 往返框架有故意漂移的失败用例 | `RoundTripAssertionsDriftTest` 绿（它断言的是"必须抛 AssertionError"） |
| 冻结样例逐条往返 | `AddressParseTest.frozenSamplesRoundTrip` 的 15 条参数全绿 |
| `./mvnw verify` 绿 | Step 1 |

- [ ] **Step 3: 把计划期新增的三条细则回填 spec**

1. §五 补：`TimeRange.to` 必须严格晚于 `from`，否则构造期抛 `IllegalArgumentException`。
2. §五 补：`SimosTimestamp` 的 `equals` 含 `calendarLabel` 而 `compareTo` 只看 `tick`——判"同刻"一律用 `compareTo == 0`。
3. §十二 测试清单补 `TimeRangeTest`（spec §十二 是下限不是上限）。

- [ ] **Step 4: 更新状态表**

- `docs/superpowers/plans/2026-09-16-simos-master-plan.md` §二：M1 由 ⬜ 改 ✅（列出 11 个任务的产出与 `verify` 结论）
- `CLAUDE.md` "当前状态"表：M1 行改 ✅，M2 行标注"待裁决 MapSimos 待决项（spec §十三）"

- [ ] **Step 5: 提交（不推送）**

```bash
git add docs/ CLAUDE.md
git diff --cached --stat
git commit -m "docs: M1 UtilSimos 关账——判据核对、计划期细则回填 spec、状态表同步"
```

---

## 自审记录（writing-plans 的三项自查）

**1. spec 覆盖**：spec §二 包结构的 8 个包 ↔ Task 1–10 逐个落地；§三 Address ↔ Task 1/2；§四 身份 ↔ Task 3；§五 时间与版本 ↔ Task 4/6；§六 三协议与容器 ↔ Task 6；§七 TemporalSeries ↔ Task 7；§八 Facet ↔ Task 9；§九 往返框架 ↔ Task 10；§十二 测试清单逐类落到任务里（`AddressQuoteTest` 由 Task 1 建、Task 2 补归一用例；额外的 `TimeRangeTest` 由 Task 11 Step 3 回填进清单）；§十 偏离 D1–D7 在 Task 5/6 落地并在接口处注明。**§十三 不做清单**（各领域 Resolver、Jackson 序列化、两阶段时间推进、命令信封、世界时钟、单位编制链地址、`agent:` 嵌套语义）在计划中**无对应步骤**——这是有意的。

**2. 占位符扫描**：每个代码步骤都给了可编译的完整代码与确切路径；无 TBD/TODO/"类似 Task N"。唯一引用他处代码的措辞是 Task 1 的 `AddressText`（同任务 Step 3 内给出）与 Task 2 复用 Task 1 的段类型（同文件、已给全）。

**3. 类型一致性**：逐项核对过——`Entity.of(String)` / `Entity.of(String, String)`、`Address.canonical()`、`Index(List<Integer>)`、`SegmentedSeries.of(segments, events, addition)`、`TimeRange.since(...)`、`InfoSystem.put(...)`、`SimulationState.module(String)`、`RoundTripAssertions.assertRoundTrip/assertSnapshotRoundTrip` 的参数顺序（`diff` 为 `(S,S)→C`、`apply` 为 `(C,S)→S`）在各任务间一致；Task 10 的玩具类型用**静态** `apply(C, S)` 方法以匹配该签名（写成实例方法会让方法引用顺序颠倒、编译不过）。
