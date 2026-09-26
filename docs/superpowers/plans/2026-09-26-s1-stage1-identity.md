# S1 阶段 1：身份（`SocialClassId`）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「社会阶层」成为**全局、显式声明**的人口身份类型，而不是每个产业各自声明的槽位 ——
即把"四模板同构"从**填充习惯**变成**类型约束**。

**Architecture:** 一次**类型替换**：新建 `SocialClassId`（全局阶层词表），把它装进 `ClassSlot.id`
与 `ClassKey.slot` 两个位置，退役 `ClassSlotId`。**不动键的形状、不动产出归属、不动守恒式。**

**Tech Stack:** Java 21 · Maven（`./mvnw`）· JUnit 5 + AssertJ · 本仓聚合式经济（`simos-economy` / `simos-economy-api` / `simos-app`）

**Spec:** `docs/superpowers/specs/2026-09-26-s1-actor-property-design.md` §2.6（`CohortKey` / `SocialClassId`）
**Breakdown:** `docs/superpowers/plans/2026-09-26-s1-stage-breakdown.md` §三 阶段 1

---

## ★ 先修正 breakdown 的一处错误（2026-09-26 调查发现）

breakdown 阶段 1 写的是 **"`ClassKey` → `CohortKey`（去 IndustryId）"**。**这一条不能独立做**：

> 去掉 `ClassKey` 里的 industry 之后，`weave@hex|*` 这四个行**就不存在了** ——
> 而它们此刻正是**布的唯一落点**（探针实测：62.1B 毫，占全境 99.8%）。
> ⇒ "键去掉 industry"与"产出从 `ClassRow` 挪到 Actor"（阶段 4）**是同一件事的两面**，
> 拆开做会在中间态**丢掉产出该记在哪**。

**⇒ 阶段 1 因此收窄为**：**只把阶层的身份全局化**（`ClassSlotId → SocialClassId`），
**键的形状（含 industry）原样不动**。`ClassKey → CohortKey` 并入**阶段 4**，与产出归属一起做。

★ 这个收窄**不动摇 S1 的目标**：spec §2.6 要的是"人的身份不含产业"，而**阶层词表全局化**正是它的第一步；
`CohortKey` 的建成时机只是被推后到它能被安全建成的那一轮。

## 调查得到的事实（写本计划的依据）

| 事实 | 值 |
|---|---|
| `ClassSlotId` 的用法面 | **22 文件 / 73 处**（`simos-economy` 16 · `simos-app` 4 · `economy-api` 2） |
| 用法性质 | **全部是经济侧自我引用**：类型定义、`ClassSlot.id`、`ClassKey.slot`、codec 解析、seeder 常量表、6 个 import —— **没有别的领域在用** |
| 四模板是否同构 | **是**，但靠**共用一对全局数组**：`CLASS_IDS = {"peasant","middle","rich","landlord"}`、`CLASS_LABOR_PER_MILLE = {950,900,750,100}`（三处产业模板都循环它们） |
| ⇒ 所以问题不是"恰好一样" | 而是**类型上不成立**：`ClassSlot.id` 是 `ClassSlotId`（产业内槽位），谁都可以填别的值 |
| `ClassKey` 规范串 | `"<industry>\|<slot>"`（如 `farm@-36_-70\|peasant`）—— 它是**变更集的 key**，也是 JSON Map 的键 |

## Global Constraints

- **Java 21**；只走 `./mvnw`（父 POM 是 `io.mosire:simos-parent`）
- **门禁 fail-closed**：看 `target/surefire-reports/*.txt` 的 **mtime 落在本轮**，**不看 rc**
- **测试一律一次一个类**（`-Dtest=A,B` 多类过滤会假绿 —— R2 踩过）
- ★★ **`-pl X -am` 与 `-Dtest=Y` 并用会在依赖模块报 `No tests matching pattern`** ⇒ 必须加
  `-Dsurefire.failIfNoSpecifiedTests=false`，**并以 surefire 报告核对真的跑了那个类**
- **一次只能跑一个 Maven**；跑前 `pgrep -af "surefirebooter|classworlds.launcher"`
- **收尾前台跑 `./mvnw clean verify`**，把 Reactor 逐模块结果抄进报告
- **注释与提交信息用中文**（`AGENT.md` §五.8）
- **护栏必须自证**：每条新断言都要**变异自证**（改坏 ⇒ 当场红 ⇒ `Edit` 反向重写还原 ⇒ 复绿）
- ★ **不许放宽既有断言**；因本次改型而变红的，按新口径**手算重算**期望值（算式写进注释），**不许抄实际值**

## Review Focus

以下五类，spec 隐含要求但既有测试**不覆盖**，最可能咬到人（每条都在下面的任务里配了测试）：

1. **词表外的阶层 id**（有人往 `ClassSlot` 塞 `"noble"`）—— 期望：**抛**（fail-closed），不是静默接受
2. **旧档里的规范串**（`farm@x|peasant`）—— 期望：按 §"值的选择"明确定下的口径处理（读得进或明确报错，**不许静默读成另一个阶层**）
3. **两个不同产业里的同名阶层**（`farm@x|poor_peasant` 与 `craft@x|poor_peasant`）—— 期望：**是同一个 `SocialClassId`**（这正是本阶段要建立的不变量）
4. **`ClassSlot` 的劳动参与率**（950/900/750/100）—— 期望：**仍住在产业侧**（`ClassSlot`），**不随 `SocialClassId` 走**（阶层身份不携带经济参数）
5. **`ClassKey.parse` 的往返**（`parse(toString()) == 自身`）—— 期望：值换了之后**往返仍成立**

---

### ★ 值的选择（**唯一需要你确认的设计点**）

新词表用哪一套，决定旧档是否需要重建：

| 选项 | 词 | 旧档 | 说明 |
|---|---|---|---|
| **A（推荐）** | `poor_peasant` / `middle_peasant` / `rich_peasant` / `landlord` | **失效**（规范串第二段变了 ⇒ 旧 revision 的 key 对不上） | 符合 spec §2.6 原文；`peasant` 一词本就有歧义（可指贫农、也可泛指农民） |
| B | 沿用 `peasant` / `middle` / `rich` / `landlord` | **仍可读**（串一字不变） | 零破坏，但**放弃了"新类型"的语义清晰度**，且与 spec 原文不符 |

★ **推荐 A**，依据是 v3 spec §十.4 的用户裁定原文："**旧档：重建也没关系，怎么方便怎么来**
⇒ **不做迁移工具、不为旧档写兼容分支**"。本计划**按 A 展开**；若你选 B，只需把 Task 1 的词表常量换掉，
其余步骤一字不动（`ClassKey` 的规范串因此不变，Task 3 的往返断言也照旧）。

---

### Task 1: 新建 `SocialClassId`（全局阶层词表）

**Files:**
- Create: `simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/SocialClassId.java`
- Modify: `simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/ClassSlotId.java`（加"将被取代"的注解，**本轮不删**）
- Test: `simos-economy-api/src/test/java/io/mosire/simos/economy/api/id/SocialClassIdTest.java`

**Interfaces:**
- Produces: `SocialClassId(String value)` record + `SocialClassId.parse(String)` + 四个具名常量
  `POOR_PEASANT` / `MIDDLE_PEASANT` / `RICH_PEASANT` / `LANDLORD`；
  以及 `public static List<SocialClassId> all()`（保序，供遍历与断言用）

- [ ] **Step 1: 写失败的测试**

Create: `SocialClassIdTest.java`

```java
package io.mosire.simos.economy.api.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * ★★ **社会阶层是全局身份**（S1 spec §2.6）：它不是"某个产业允许的槽位"，
 * 而是**产业无关**的人口身份 —— 同一个 {@code poor_peasant} 出现在农业行与手工业行里,
 * 指的是**同一个社会阶层**。
 *
 * <p>★ 判别力：把 {@link SocialClassId#parse} 改成静默接受任意文本 ⇒ 第 2 条红。
 */
class SocialClassIdTest {

  @Test
  void knowsExactlyTheFourStrata() {
    assertThat(SocialClassId.all())
        .as("★ 词表就是这四个（保序）—— 多一个或少一个都必须让本用例红")
        .containsExactly(
            SocialClassId.POOR_PEASANT,
            SocialClassId.MIDDLE_PEASANT,
            SocialClassId.RICH_PEASANT,
            SocialClassId.LANDLORD);
  }

  /** ★ **词表外即抛**（fail-closed）：静默接受会让"写错阶层"变成运行时幽灵。 */
  @Test
  void rejectsAnythingOutsideTheVocabulary() {
    assertThatThrownBy(() -> SocialClassId.parse("noble"))
        .as("★★ 词表外的阶层必须当场抛（且消息里列出合法值）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("noble");
    assertThatThrownBy(() -> SocialClassId.parse(" "))
        .as("空白同样抛")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 往返：`parse(toString()) == 自身`（铁律 1 的"裸值 + parse"三件套）。 */
  @Test
  void roundTripsThroughText() {
    for (SocialClassId id : SocialClassId.all()) {
      assertThat(SocialClassId.parse(id.toString())).isEqualTo(id);
    }
  }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `./mvnw -pl simos-economy-api -am test -Dtest=SocialClassIdTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: **FAIL** —— 编译错（`SocialClassId` 不存在）

- [ ] **Step 3: 写最小实现**

Create: `SocialClassId.java` —— **照 `ClassSlotId` 的形制**（裸值 record + 校验 + `parse` + `toString`），
但**多一层词表校验**：

```java
package io.mosire.simos.economy.api.id;

import java.util.List;

/**
 * ★★ **社会阶层 id**（S1 spec §2.6）：**产业无关**的人口身份 —— 与 {@link ClassSlotId}（"一个产业内该制度允许的
 * 一个槽位"）不同，本类型的值**全局唯一且固定**：同一个 {@code poor_peasant} 出现在任何产业里都是同一个阶层。
 *
 * <p>★★ **为什么必须与 {@code ClassSlotId} 分开**（spec 原文）：槽位是"制度允许哪些角色"，
 * 各产业可自定；而阶层是**人**的属性。四模板**当前**恰好同构（靠共用一对全局数组），
 * 但那是**填充习惯**、不是**类型约束** —— 本类型把后者补上。
 *
 * <p>★ **词表外即抛**（fail-closed）：静默接受会让"写错阶层"变成运行时幽灵。
 *
 * <p>★ **它不携带任何经济参数**：劳动参与率住在 {@code ClassSlot}（产业侧），不随身份走。
 */
public record SocialClassId(String value) {

  public static final SocialClassId POOR_PEASANT = new SocialClassId("poor_peasant");
  public static final SocialClassId MIDDLE_PEASANT = new SocialClassId("middle_peasant");
  public static final SocialClassId RICH_PEASANT = new SocialClassId("rich_peasant");
  public static final SocialClassId LANDLORD = new SocialClassId("landlord");

  private static final List<SocialClassId> ALL =
      List.of(POOR_PEASANT, MIDDLE_PEASANT, RICH_PEASANT, LANDLORD);

  public SocialClassId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("SocialClassId 不得为空白");
    }
    boolean known = ALL.stream().anyMatch(id -> id.value.equals(value));
    if (!known) {
      throw new IllegalArgumentException(
          "词表外的社会阶层: " + value + "（合法值: " + ALL.stream().map(SocialClassId::value).toList() + "）");
    }
  }

  /** 词表（保序）—— 供遍历与断言用。 */
  public static List<SocialClassId> all() {
    return ALL;
  }

  @Override
  public String toString() {
    return value;
  }

  public static SocialClassId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("SocialClassId 不得为空白: " + text);
    }
    return new SocialClassId(text); // ★ 构造期已做词表校验
  }
}
```

★ **同时**在 `ClassSlotId` 的类注里加一行：`@deprecated 将由 {@link SocialClassId} 取代（S1 阶段 1）` ——
**本轮不删**（Task 2 才换装）。

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl simos-economy-api -am test -Dtest=SocialClassIdTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: **PASS**（3/3）

- [ ] **Step 5: 变异自证**

把 `SocialClassId` 的构造校验里的 `if (!known)` 整段**临时删掉**，跑同一条测试。

Expected: **FAIL**（`rejectsAnythingOutsideTheVocabulary` 红）⇒ `Edit` 反向重写还原 ⇒ **PASS**

- [ ] **Step 6: 提交**

```bash
git add simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/SocialClassId.java \
        simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/ClassSlotId.java \
        simos-economy-api/src/test/java/io/mosire/simos/economy/api/id/SocialClassIdTest.java
git commit -m "feat(economy-api): SocialClassId —— 产业无关的社会阶层身份（S1 阶段 1 第一步）

## 为什么
现状的 ClassSlotId 是「**一个产业内**该制度允许的一个槽位」——四模板当前恰好同构，
但那是**填充习惯**（三处产业模板共用 CLASS_IDS/CLASS_LABOR_PER_MILLE 一对全局数组），
不是**类型约束**：谁都可以往 ClassSlot 里填别的值。S1 spec §2.6 要的是**人**的身份，
故新建一个**词表固定、产业无关**的类型。

## 改了什么
- 新建 SocialClassId（裸值 record + **词表校验** + parse/toString 三件套）
- ClassSlotId 类注加 deprecated 提示（本轮不删，Task 2 才换装）

## 验收
SocialClassIdTest 3/3（词表恰四个 / 词表外即抛 / 往返成立）
★ 变异自证：删掉词表校验 ⇒ rejectsAnythingOutsideTheVocabulary 红 ⇒ 还原复绿

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: 换装 —— `ClassSlot.id` 与 `ClassKey.slot` 改用 `SocialClassId`

**Files:**
- Modify: `simos-economy/src/main/java/io/mosire/simos/economy/model/ClassSlot.java`（`id` 的类型）
- Modify: `simos-economy/src/main/java/io/mosire/simos/economy/model/ClassKey.java`（`slot` 的类型 + `parse`/`toString`）
- Modify: `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`（`CLASS_IDS` → `SocialClassId` 常量）
- Modify: `simos-economy/.../spi/EconomyPayloads.java`（codec 的 `slot` 解析）
- Delete: `simos-economy-api/.../id/ClassSlotId.java`（**没有第三方在用** —— 调查已确认 73 处全是经济侧自我引用）
- Test: `simos-economy/src/test/java/io/mosire/simos/economy/model/ClassKeyTest.java`

**Interfaces:**
- Consumes: `SocialClassId`（Task 1）
- Produces: `ClassSlot(SocialClassId id, String name, int laborParticipationPerMille)`；
  `ClassKey(IndustryId industry, SocialClassId slot)`；`ClassKey.parse` 的**规范串第二段变成 `SocialClassId` 的裸值**

- [ ] **Step 1: 写失败的测试**

在 `ClassKeyTest`（若不存在则新建 `simos-economy/src/test/java/io/mosire/simos/economy/model/ClassKeyTest.java`）加：

```java
  /**
   * ★★ **本阶段要建立的不变量**：两个不同产业里的同名阶层，是**同一个** {@code SocialClassId}。
   *
   * <p>★ 判别力：若 `ClassKey.slot` 仍是产业内的 `ClassSlotId`，"同一个阶层"这件事
   * 就只能靠**值相等**侥幸成立；本类型把它变成**类型上成立**。
   */
  @Test
  void theSameStratumInTwoIndustriesIsTheSameIdentity() {
    ClassKey farm = ClassKey.parse("farm@0_0|poor_peasant");
    ClassKey craft = ClassKey.parse("craft@0_0|poor_peasant");

    assertThat(farm.slot())
        .as("★★ 两个产业里的同一个阶层必须是同一个 SocialClassId（不是两个碰巧同值的槽位）")
        .isSameAs(craft.slot())
        .isEqualTo(SocialClassId.POOR_PEASANT);
  }

  /** ★ **往返**：`parse(toString()) == 自身`（值换了之后仍须成立）—— `ClassKey` 是变更集的 key，这条挂了会静默丢状态。 */
  @Test
  void roundTripsThroughItsCanonicalString() {
    for (SocialClassId stratum : SocialClassId.all()) {
      ClassKey key = new ClassKey(new IndustryId("farm@0_0"), stratum);
      assertThat(ClassKey.parse(key.toString())).isEqualTo(key);
    }
  }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `./mvnw -pl simos-economy -am test -Dtest=ClassKeyTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: **FAIL** —— 编译错（`ClassKey.slot()` 的类型与 `SocialClassId` 不匹配 / `SocialClassId` 未 import）

- [ ] **Step 3: 换装**

按调查得到的用法面（**22 文件 / 73 处**）逐处替换 —— **类型替换，不改逻辑**：

| 位置 | 改法 |
|---|---|
| `ClassSlot.id` | `ClassSlotId` → `SocialClassId` |
| `ClassKey.slot` | `ClassSlotId` → `SocialClassId`；`parse` 的第二段改 `SocialClassId.parse`；`toString` **不变**（`industry + "\|" + slot`，只是第二段的值换了） |
| `EconomySeeder` | `CLASS_IDS` 换成 `SocialClassId.all()` 的遍历（**顺序必须是 POOR→MIDDLE→RICH→LANDLORD**，与 `CLASS_LABOR_PER_MILLE` 对齐）；`INITIAL_RATION_DAYS_BY_CLASS` 的键类型跟着换 |
| `EconomyPayloads` | `slot` 字段的解析改 `SocialClassId.parse` |
| `ClassSlotId.java` | **删除**（调查确认无第三方在用） |
| 6 个 import | 跟着换 |

★ **不改**：`ClassSlot.laborParticipationPerMille`（**仍住产业侧** —— Review Focus ④）、
`ClassKey` 的键形状（**仍含 industry** —— 见本计划开头的修正）、任何守恒式。

- [ ] **Step 4: 跑测试确认它通过**

Run: `./mvnw -pl simos-economy -am test -Dtest=ClassKeyTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: **PASS**

★ 然后**逐个**跑受影响的既有测试类（**一次一个类**，看 surefire 报告）：
`EconomySettlementEndToEndTest`、`EconomySeederTest`、`EconomyFlowCycleTest`、`PopulationR4Test`、`CrisisMonitorPhaseTest`。

★ **其中若有断言因规范串换值而红**：那是**预期内**的 —— 按新口径**手算重算**期望值
（算式写进注释），**不许抄实际值**；若某条红的原因**不是**规范串换值，**先查被测物**。

- [ ] **Step 5: 变异自证**

把 `SocialClassId.parse` **临时改回**"只校验非空白"（去掉词表校验），跑 `ClassKeyTest`。

Expected: **FAIL**（往返那条会因构造期不再抛而**通过**，但 `theSameStratumInTwoIndustriesIsTheSameIdentity` 的
`isSameAs` 仍应成立 —— ★ **若这一变异不红，说明本阶段的判别力不在 `parse` 而在类型本身**：
那就把变异改到 `ClassKey.slot` 的类型上（改回 `ClassSlotId`）⇒ 编译错 ⇒ 红）。
**如实记录你实际用的变异体与结果。**

- [ ] **Step 6: 提交**

```bash
git add -A simos-economy/src simos-economy-api/src simos-app/src
git commit -m "refactor(economy,economy-api,app): 阶层身份全局化 —— ClassSlotId → SocialClassId（S1 阶段 1）

## 为什么
四模板当前恰好同构，但靠的是**共用一对全局数组**（填充习惯），类型上仍是"产业内槽位"。
本步把它变成**类型约束**：同一个 poor_peasant 在任何产业里都是同一个 SocialClassId。

## 改了什么
ClassSlot.id / ClassKey.slot 的类型换装；EconomySeeder 改用 SocialClassId.all() 遍历；
codec 跟进；ClassSlotId 退役（调查确认 73 处全是经济侧自我引用，无第三方）。
★ **不动**：键的形状（仍含 industry —— 见计划的修正说明）、劳动参与率（仍住产业侧）、任何守恒式。

## 验收
（逐类测试结果 + 规范串换值后重算的断言清单）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: 验收 —— 人口与劳动**逐值不变**

**Files:** 无源码改动；产出是一份验收读数

- [ ] **Step 1: 打包 + 起实例 + 种三国**

```bash
./mvnw -DskipTests package -pl simos-app -am
tools/run-shaded.sh simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --store <JOB_TMP>/simos-s1a --gui-port 5857 --mcp-port 5755 --approval-port 5753
```

★ 收工用 **PID** 停，**不要 `pkill -f`**；curl 一律 `--noproxy '*'`。
★ store 放 **job 目录**（`/tmp` 曾被系统清理过）。

- [ ] **Step 2: 读三条判据**

| # | 判据 | 期望 |
|---|---|---|
| **I1.1** | **逐格人口、逐格劳动供给、逐格劳动配额** 与改型前**逐值相同** | 差 0 |
| **I1.2** | **同一 cohort 同时参与两个 activity ⇒ 只有一个身份**（V9） | `weave` 行的行数不再随产业增加 |
| **I1.3** | **`ClassSlotId` 不出现在任何新领域 API 的签名里** | 源扫描：`grep -rn "ClassSlotId" simos-*/src/main` 为空 |

★ I1.1 的对照基线：用**改型前**的代码跑同一次推进，取同样的三个量（本仓的 probe 脚本在
`.superpowers/sdd/2026-09-26-year-one-simulation/` 与 `2026-09-26-b1b2-probe/`，改端口即可复用）。

- [ ] **Step 3: 写读数记录并提交**

写进 `.superpowers/sdd/2026-09-26-s1-stage1/readings.md`，`git add -f` 后提交。

---

### 收尾（不属于任何单个 Task）

- [ ] 前台跑 **`./mvnw clean verify`**，把 **Reactor 逐模块结果**抄进报告
- [ ] 核对 `target/surefire-reports/*.txt` 的 **mtime 落在本轮**
- [ ] 确认 Spotless / Checkstyle / SpotBugs(0 bug) / 前端门禁 全过

---

## Self-Review

**Spec coverage**（对照 spec §2.6）：

| Spec 要求 | 落点 |
|---|---|
| `SocialClassId` 是**全局**词表、不再由各 Industry 声明 | Task 1（词表 + 校验）+ Task 2（换装） |
| 现有 `ClassSlot` 按语义**映射**到 `SocialClassId`，映射**显式化**并加断言 | Task 2 Step 1 的 `theSameStratumInTwoIndustriesIsTheSameIdentity` + Step 4 的逐类回归 |
| 劳动参与**另建关系**（`LaborAllocation(CohortKey, ActivityId)`） | ★ **不在本计划** —— 它需要 `CohortKey`，而后者并入阶段 4（见开头的修正） |
| `PeopleLot` 保持人口动力学内部粒度 | 本计划**未触碰** `PeopleLot` |

**Placeholder scan**：无 TBD/TODO。★ Task 2 Step 5 的变异体给了**两个备选**并说明"若 A 不红就用 B"——
这不是 placeholder，而是**判别力可能不在预期位置**时的诚实处理（并要求如实记录实际用了哪个）。

**Type consistency**：`SocialClassId.parse(String)` / `all()` 在 Task 1 定义、Task 2 消费；
`ClassKey(IndustryId, SocialClassId)` 的规范串形态在 Task 2 的往返断言与 codec 改动里一致。

**Review Focus coverage**：① 词表外即抛 → Task 1 Step 1 第二条；
② 旧档规范串 → §"值的选择"（按 A ⇒ 明确失效，不做兼容分支）；
③ 跨产业同名阶层 → Task 2 Step 1 第一条；
④ 参与率仍住产业侧 → Task 2 Step 3 的"不改"清单 + 逐类回归；
⑤ `ClassKey.parse` 往返 → Task 2 Step 1 第二条。

## 本计划的验证边界

- **未清点**：`ClassKey` 规范串换值后，**哪些既有断言会红**（Task 2 Step 4 要求实现者逐类跑并如实记录）。
- **未评估**：`SocialClassId` 是否该在阶段 2 上移到 `actor-api`（现在的落点是 `economy-api`，与 `ClassSlotId` 同处）。
- **未做**：旧档迁移（按 spec §十.4 的裁定**不做** —— 旧档重建）。
