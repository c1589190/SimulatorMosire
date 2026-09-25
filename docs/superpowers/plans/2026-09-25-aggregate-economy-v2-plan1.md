# 聚合式经济 v2 · 计划 1：常量上移 + 构造期不变量 + 产能与量纲标定

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让经济的产出与需求**在数量级上自洽**（修掉 25 倍超载），并把四条"模型允许、结算接不住"的接缝堵在构造期。

**Architecture:** 不动状态形状、不动模块划分、不动结算流程。只做三件事：① 把两份重复的口粮常量上移到 `simos-util` 并加"全仓恰一份"守卫；② 把四条构造期可判的不变量补进 `EconomyData` / `Industry`，把两条"到日推进才炸"的路径改成"构造期就拒"；③ 地形产能改读 `TerrainType.food`（map 已有的事实），并按 v2 spec §10.3 定案标定 `MU_PER_HEX` 与 `GRAIN_OUTPUT_PER_MU`。

**Tech Stack:** Java 21、Maven（父 POM `io.mosire:simos-parent`）、JUnit 5 + AssertJ、Jackson（`databind` + `datatype-jdk8`）。

**Spec:** [`docs/superpowers/specs/2026-09-25-aggregate-economy-v2-design.md`](../specs/2026-09-25-aggregate-economy-v2-design.md)（本计划实现它的 **V1、V2** 两个增量；执行者请同时读该 spec 的 §1.1、§1.1.1、§三、§八、§九、§10.3）

★ **本计划只是 v2 全量（V1~V9）的第一份**。按用户裁定「甲」，顺序是 V1 → V2 → V3 播种 → V4 缺口 → V5 流水 → V6 债务 → 再 V7 参数目录 / V8 索取源。
**后续计划各自单独成文**——因为 V6 会改 `Debt` 的语义、V7 会把本计划上移的常量吸收进参数表，后面的任务分解**依赖前面落地后的真实代码形状**；一次全写出来必然写出假 API。

---

## Global Constraints

以下每一条都取自 spec 或 `AGENT.md`，**每个任务的要求都隐含包含本节**：

- **量纲（spec §七）**：人口「人」；劳动「**千分劳动**」；土地「**千分亩**」；粮「**毫粮**」（1 粮 = 1000 毫粮）；货币「最小币值」；权重 / 利率 / 投入率一律「**千分数**」。全部 `long`（`int` 只用于 `participationPerMille` 这类已定的字段）。
- **禁用浮点**：`double` / `float` **不得参与**钱、粮、人口（spec §七）。本计划全程整数乘除。
- **保序不可变**：一切容器用 `LinkedHashMap` / `ArrayList` 复制后 `Collections.unmodifiableMap/List` **冻在字段赋值处**；**绝不用 `Map.copyOf`**（它的迭代序不是内容的纯函数，字节级往返因此不成立）。
- **注释与文档用中文**，与既有风格一致；行宽由 google-java-format（Spotless）决定，**不要手工断行**。
- **模块边界由 enforcer 强制**：`simos-economy` 不得依赖 `simos-social/unit/sd/core/app/agentlib-mosire/ledger`；`simos-util` **只依赖 Jackson + SLF4J**，不依赖任何 simos 模块、不碰文件系统。
- **一次只能跑一个 Maven**：跑前 `pgrep -af "surefirebooter|classworlds.launcher"`，有命中就等。
- **迭代命令**：`./mvnw -q -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false test -pl <模块> -am`
  ★ `-q` 会吞掉 surefire 汇总 ⇒ **判过不过要看 `target/surefire-reports/*.txt` 的 mtime 落在本轮**，不看 `rc=0`。
- **关账命令**：`./mvnw clean verify`，**必须前台**（后台跑会被内存守卫杀，而"被杀"既不是红也不是绿）。
- **提交纪律**：**不 `git add -A`**；按批次提交；提交信息中文，写清「**改了什么 + 为什么 + 验收的实际数字 + 未验的部分**」。
- **服务在跑时**验代码用 `./mvnw test`（`test` 阶段不经 `package`/`shade`，不动 jar）。

---

## Review Focus

下面十类输入 / 条件，是 spec 隐含要求、但**本计划任何任务的测试如果不专门写就不会覆盖**的，也正是最可能咬人的。每一条都已在它所属的任务里配了测试（括号内为该任务号）：

1. **`TerrainType.food == 0` 的格**（沙漠 / 山地 / 海洋）：必须得 **0 可耕地**且**不抛**——v1 是抛。（任务 6）
2. **地形 key 不在 `TerrainCatalog` 里**：必须 **fail-closed 抛**（不许默默当 0）。（任务 6）
3. **农村人口为 0 的纯城市格**：`laborMu = 0` ⇒ 投入面积 0 ⇒ **不产粮**，且**不许除零**。（任务 6）
4. **`food` 是 `int`、`MU_PER_HEX` 是 `long`**：乘法中间值不得溢出（`3,100 × 1,000 × 3 = 9.3e6`，安全；但要有一条上界断言钉住量级）。（任务 6）
5. **参与率超槽位上限的载荷**：构造期就拒——v1 静默通过 ⇒ **凭空造劳动**（不造粮，但瓶颈与按劳动权重的分配全被放大）。（任务 2）
6. **`Debt.debtor` / `creditor` 指向不存在的阶层，或 `ClassRow.debts` 指向不存在的债务**：构造期就拒——v1 只查 null。（任务 3）
7. **`progressDays == cycleDays`**（v2 spec §3.1 的闭区间上界，合法值）：构造期**允许**、结算**必须处理**——v1 直接抛 `IllegalArgumentException`，整条推进 revision 失败。（任务 4）
8. **`cycleDays == 1`**：`avgLaborMilli = cycledLabor / cycleDays` 不得除零；且第 1 天就收获。（任务 4）
9. **`AllocationRule.WageFirst`**：构造期就拒——v1 留到收获日才抛，异常穿出时间参与者。（任务 5）
10. **标定后低丘格必须走新地形公式**：不得残留 v1 的 `600‰` 手写表。（任务 7）

---

### Task 1: 口粮常量上移 —— `EconomyVocabulary` 进 `simos-util` + 「全仓恰一份」守卫

**Files:**
- Create: `simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java`
- Create: `simos-util/src/test/java/io/mosire/simos/util/economy/EconomyVocabularyTest.java`
- Modify: `simos-economy/.../time/EconomySettlement.java`（删自持的 83 与粮 id）
- Modify: `simos-app/.../world/EconomySeeder.java`（删自持的 83 与粮 id）
- Modify: `simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java`（加"恰一份"用例，**复用该文件已有的源扫描底座**）
- Modify: 任何引用被删常量的测试（清单见 Step 1）

**Interfaces:**
- Consumes: 无（第一个任务）
- Produces:
  - `io.mosire.simos.util.economy.EconomyVocabulary.GRAIN_COMMODITY_ID`：`String`，值 `"grain"`
  - `io.mosire.simos.util.economy.EconomyVocabulary.DAILY_GRAIN_MILLI_PER_PERSON`：`long`，值 `83L`
  - `io.mosire.simos.util.economy.EconomyVocabulary.MILLI_PER_GRAIN`：`long`，值 `1000L`

★ **为什么落 `simos-util` 而不是 `simos-economy-api`**：军队（`simos-unit`）将来也要按同一口径吃粮，而 `simos-unit` 只依赖 `util + map` —— **只有 `util` 是 `economy` 与 `unit` 都能看见的共同上游**。
★ **本类只能放 `String` / 原生类型**：`simos-util` 不能依赖 `simos-economy-api`（方向相反），故 `CommodityId` 那一层留在 economy 侧，由本常量构造。
★ `DAILY_GRAIN_MILLI_PER_PERSON` 是**临时居所**：v2 spec §四 的参数目录（增量 V7）落地后它迁入 `economy` 切片的 `worldParams` 并变成 GM 可调。届时本类只留商品 id。

- [ ] **Step 1: 先列出全部待改引用点**

Run:
```bash
git grep -n --untracked 'DAILY_GRAIN_MILLI_PER_PERSON\|COMMODITY_GRAIN\|MILLI_PER_GRAIN\|"grain"' -- '*.java'
```
Expected: 得到所有声明点与引用点。**逐条记下**，改完之后这条命令的输出里，声明点只剩 `EconomyVocabulary` 一处（`"grain"` 字面量同理）。这是后面守卫用例的判据来源。

- [ ] **Step 2: 写失败测试（新类不存在 ⇒ 编译不过，即"红"）**

Create `simos-util/src/test/java/io/mosire/simos/util/economy/EconomyVocabularyTest.java`:
```java
package io.mosire.simos.util.economy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 经济词表的取值与"它是常量"这两件事的守卫。
 *
 * <p>★ 本类**只钉取值**；"全仓恰一份"由 {@code RegressionGuardsTest} 的源扫描钉（那里有现成的底座）。
 */
class EconomyVocabularyTest {

  @Test
  void grainCommodityIdIsTheVocabularysSingleSpelling() {
    assertThat(EconomyVocabulary.GRAIN_COMMODITY_ID).as("粮的商品 id").isEqualTo("grain");
  }

  @Test
  void dailyRationMatchesTheTenGrainPerCycleRule() {
    // 10 粮/周期 ÷ 120 天 = 83.33 ⇒ 取 83（残差分派见 v2 spec §八.6）
    assertThat(EconomyVocabulary.DAILY_GRAIN_MILLI_PER_PERSON).as("每人每日口粮（毫粮）").isEqualTo(83L);
    assertThat(EconomyVocabulary.MILLI_PER_GRAIN).as("1 粮 = 1000 毫粮").isEqualTo(1000L);
  }
}
```

- [ ] **Step 3: 跑测试确认它红**

Run: `./mvnw -q -Dtest=EconomyVocabularyTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-util -am`
Expected: **编译错误**（`程序包 io.mosire.simos.util.economy 不存在` / `找不到符号`）。这是本步要的"红"。

- [ ] **Step 4: 建类**

Create `simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java`:
```java
package io.mosire.simos.util.economy;

/**
 * 跨模块共用的**经济词表**：全仓恰一份，由源扫描守卫钉住（{@code RegressionGuardsTest}）。
 *
 * <p>★★ **为什么在 {@code simos-util} 而不是 {@code simos-economy-api}**：军队（{@code simos-unit}）将来也要按同一
 * 口径吃粮，而 {@code simos-unit} 只依赖 util + map ⇒ **只有 util 是 economy 与 unit 都能看见的共同上游**。
 *
 * <p>★ **本类只放 {@code String} 与原生类型**：util 不能依赖 economy-api（方向相反），故 {@code CommodityId} 那一层留在
 * economy 侧，由 {@link #GRAIN_COMMODITY_ID} 构造。
 *
 * <p>★ {@link #DAILY_GRAIN_MILLI_PER_PERSON} 是**临时居所**：v2 spec §四 的参数目录（增量 V7）落地后，它迁入
 * {@code economy} 切片的 {@code worldParams} 并成为 GM 可调参数；届时本类只留商品 id。
 */
public final class EconomyVocabulary {

  /** 粮的商品 id（v2 spec §3.2：**粮与种子是同一个商品**，不设 {@code seed}）。 */
  public static final String GRAIN_COMMODITY_ID = "grain";

  /** 每人每日口粮（毫粮）：10 粮/周期 ÷ 120 天 = 83.33 ⇒ 取 83（残差分派见 v2 spec §八.6）。 */
  public static final long DAILY_GRAIN_MILLI_PER_PERSON = 83L;

  /** 1 粮 = 1000 毫粮（库存按最小计量单位，{@code outputPerUnit} 是「粮/亩」⇒ 入账前要换算）。 */
  public static final long MILLI_PER_GRAIN = 1000L;

  private EconomyVocabulary() {}
}
```

- [ ] **Step 5: 跑测试确认它绿**

Run: `./mvnw -q -Dtest=EconomyVocabularyTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-util -am`
Expected: PASS（**核对 `simos-util/target/surefire-reports/*.txt` 的 mtime 落在本轮，2 条用例**）。

- [ ] **Step 6: 改两处声明为引用**

- `simos-economy/.../time/EconomySettlement.java`：删掉自持的 `DAILY_GRAIN_MILLI_PER_PERSON` / `MILLI_PER_GRAIN` / `GRAIN` 三个声明的**字面量**，改成由词表构造，例如
  ```java
  /** 粮食商品 id（词表是唯一拼写点，见 {@link io.mosire.simos.util.economy.EconomyVocabulary}）。 */
  public static final CommodityId GRAIN =
      new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);
  ```
  并把它自身所有用到 `DAILY_GRAIN_MILLI_PER_PERSON` / `MILLI_PER_GRAIN` 的地方改成静态导入或 `EconomyVocabulary.` 前缀。
  ★ **保留 `public static final` 的同名常量**（供既有测试引用），但值来自词表 —— 守卫只钉"字面量只有一份"。
- `simos-app/.../world/EconomySeeder.java`：删掉 `DAILY_GRAIN_MILLI_PER_PERSON` / `COMMODITY_GRAIN` 两个字面量声明，改为
  ```java
  public static final long DAILY_GRAIN_MILLI_PER_PERSON =
      EconomyVocabulary.DAILY_GRAIN_MILLI_PER_PERSON;
  public static final String COMMODITY_GRAIN = EconomyVocabulary.GRAIN_COMMODITY_ID;
  ```
  （同理：保留同名常量，值来自词表。）

- [ ] **Step 7: 全量编译 + 跑受影响模块的测试**

Run: `./mvnw -q test -pl simos-economy,simos-app -am`
Expected: 全绿。**核对两模块的 `target/surefire-reports/*.txt` mtime 落在本轮**。
★ 若有测试引用被删的常量而编译不过，按报错逐个补 `EconomyVocabulary.` 前缀（这正是 Step 1 那份清单的用处）。

- [ ] **Step 8: 写「全仓恰一份」守卫（源扫描）**

Modify `simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java`，在既有 `R1_thereIsExactlyOneFieldDelta` 旁边加：
```java
  /**
   * ★★ **口粮常量与粮商品 id 全仓恰一份**（v2 spec §六）。
   *
   * <p>病灶形态：v1 里 {@code 83} 与 {@code "grain"} 各在 {@code EconomySeeder}（app）与 {@code EconomySettlement}
   * （economy）写了一份，**没有任何东西钉住两者相等** ⇒ 改一处即静默分叉（改口粮口径时必然踩）。军队接入后会有第三份。
   *
   * <p>★ 为什么这条守卫住在 {@code simos-map}：源扫描底座（{@code repoRoot}/{@code javaFilesUnder}/{@code rawLines}）
   * 在这个文件里，**复制一份底座才是本仓最忌的"两处各写一份"**；守卫的模块归属由"底座在哪"决定，不由"守谁"决定。
   * 本用例只读文件、不依赖 economy —— 故不需要 `simos-map` 依赖 economy。
   */
  @Test
  void R1b_thereIsExactlyOneEconomyVocabulary() {
    List<String> modules =
        List.of("simos-util", "simos-map", "simos-social", "simos-unit", "simos-core", "simos-sd",
            "simos-economy-api", "simos-ledger", "simos-economy", "simos-app");
    for (String token : List.of("DAILY_GRAIN_MILLI_PER_PERSON =", "GRAIN_COMMODITY_ID =")) {
      Map<String, Long> hits = new LinkedHashMap<>();
      for (String module : modules) {
        for (Path file : javaFilesUnder(repoRoot().resolve(module + "/src/main"))) {
          for (String line : rawLines(file)) {
            if (line.contains(token)) {
              hits.merge(relative(file), 1L, Long::sum);
            }
          }
        }
      }
      assertThat(hits)
          .as("口粮常量「%s」在全部模块的 src/main 里必须恰有一份声明（第二份 = 语义必然漂移）", token)
          .containsExactly(
              entry("simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java", 1L));
    }
  }
```

- [ ] **Step 9: 跑守卫，确认它绿；再做变异自证**

Run: `./mvnw -q -Dtest=RegressionGuardsTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-map -am`
Expected: PASS。

**变异自证（必做，证明这条守卫有判别力）**：在 `EconomySeeder.java` 里临时把 Step 6 那行改回字面量
```java
  public static final long DAILY_GRAIN_MILLI_PER_PERSON = 83L;
```
重跑同一条命令 ⇒ **必须红**，且红的理由是 `containsExactly` 收到 2 处命中。
★ 验完**按字节还原**（用 `cp` 从备份还原会让 mtime 变新而字节不变，故**用 `git checkout -- <文件>` 或重新 Edit**），并重跑确认回绿。

- [ ] **Step 10: 提交**

```bash
git add simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java \
        simos-util/src/test/java/io/mosire/simos/util/economy/EconomyVocabularyTest.java \
        simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java \
        simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java \
        simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java
git commit -m "refactor(util,economy,app): 口粮常量与粮 id 上移 simos-util + 「全仓恰一份」源扫描守卫"
```
提交信息须含：改了什么、为什么（v1 两份无守卫）、**验收的实际数字**（守卫实测命中数 1；变异轮命中数 2）、未验的部分。

---

### Task 2: 不变量 ① —— 参与率不得超过槽位上限

**Files:**
- Modify: `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java`
- Modify: `simos-economy/src/test/java/io/mosire/simos/economy/model/EconomyInvariantsTest.java`

**Interfaces:**
- Consumes: `ClassRow.participationPerMille()`（`int`）、`ClassSlot.laborParticipationPerMille()`（`int`）
- Produces: `EconomyData.requireSlotExists(...)` 的返回类型由 `void` 改为 **`ClassSlot`**（私有 helper，不影响公共 API）

★ **病灶（v2 spec §八.1）**：`ClassRow` 只守了 `participationPerMille ∈ [0, 1000]` 两头，**中间那条 `≤ 槽位上限` 无人守**；
`EconomyData` 同时持有 `industries` 与 `classes`，构造期**完全可判**却只 `return`。
后果：超上限的投入率安静入库 ⇒ `laborMilli × participationPerMille / 1000` 被放大 ⇒ 劳动瓶颈、产出、按劳动权重的分配全被悄悄放大，
且**账面看不出来**（不凭空造粮，但凭空造劳动）。

- [ ] **Step 1: 写失败测试**

在 `EconomyInvariantsTest.java` 追加（沿用该文件既有的 `assertThatThrownBy` 风格）：
```java
  @Test
  void participationPerMilleMustNotExceedItsSlotCeiling() {
    // 槽位上限 950‰（"贫农"），行里写 1000‰ ⇒ 凭空多出 5% 劳动
    assertThatThrownBy(() -> economyWithParticipation(1000))
        .as("参与率超槽位上限必须在构造期拒（v2 spec §八.1）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("participationPerMille")
        .hasMessageContaining("槽位");
  }

  @Test
  void participationPerMilleAtTheSlotCeilingIsAccepted() {
    // 边界值：恰好等于上限 ⇒ 必须放行（不许写成 < 而不是 ≤）
    assertThat(economyWithParticipation(950)).isNotNull();
  }
```
其中 helper `economyWithParticipation(int perMille)` 建一份最小 `EconomyData`：一个 `Industry`（slots 只含 `laborParticipationPerMille = 950` 的槽位）+ 一个 `ClassRow`（`participationPerMille = perMille`）。
★ **两份用例缺一不可**：只测"超限抛"会漏掉"边界值被误拒"（把 `≤` 写成 `<` 也是 bug）。

- [ ] **Step 2: 跑测试确认它红**

Run: `./mvnw -q -Dtest=EconomyInvariantsTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: `participationPerMilleMustNotExceedItsSlotCeiling` **红**（构造期没抛，`assertThatThrownBy` 失败）；另一条绿。

- [ ] **Step 3: 改 helper 让它交出槽位，并在 classes 那一段比一比**

`EconomyData.java`：把
```java
  private static void requireSlotExists(
      Map<IndustryId, Industry> industries, ClassKey key, String what) {
```
改为返回 `ClassSlot`（把末尾的 `return;` 改成 `return slot;`），签名改成
```java
  private static ClassSlot requireSlotExists(
      Map<IndustryId, Industry> industries, ClassKey key, String what) {
```
然后在 **classes** 那一段（`classesCopy` 的循环里）把 `requireSlotExists(...)` 的调用改成接返回值并加判：
```java
      ClassSlot slot = requireSlotExists(industriesCopy, entry.getKey(), "classes");
      ClassRow row = entry.getValue();
      if (row.participationPerMille() > slot.laborParticipationPerMille()) {
        throw new IllegalArgumentException(
            "classes 的 participationPerMille 不得超过其槽位上限（v2 spec §八.1）："
                + entry.getKey()
                + " 行="
                + row.participationPerMille()
                + "‰ > 槽位="
                + slot.laborParticipationPerMille()
                + "‰");
      }
```
**flows** 那一段的调用保持原样（丢弃返回值即可，Java 允许忽略返回值）。

- [ ] **Step 4: 跑测试确认两条都绿**

Run: `./mvnw -q -Dtest=EconomyInvariantsTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: PASS（**核对 `simos-economy/target/surefire-reports/*.txt` mtime 落在本轮**）。

- [ ] **Step 5: 变异自证**

把判据从 `>` 改成 `>=` ⇒ 重跑 ⇒ **`participationPerMilleAtTheSlotCeilingIsAccepted` 必须红**（边界值被误拒）。
按字节还原 ⇒ 重跑回绿。

- [ ] **Step 6: 提交**

```bash
git add simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java \
        simos-economy/src/test/java/io/mosire/simos/economy/model/EconomyInvariantsTest.java
git commit -m "fix(economy): 构造期守 participationPerMille ≤ 槽位上限（v1 只守了两头，中间那条无人守）"
```

---

### Task 3: 不变量 ② —— 债务引用两端必须完整

**Files:**
- Modify: `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java`
- Modify: `simos-economy/src/test/java/io/mosire/simos/economy/model/EconomyInvariantsTest.java`

**Interfaces:**
- Consumes: `Debt.debtor()` / `Debt.creditor()`（都是 `ClassKey`）、`ClassRow.debts()`（`List<DebtId>`）
- Produces: 无新 API

★ **病灶（v2 spec §八.2）**：`EconomyData` 的 debts 循环**只查 null** ⇒ `Debt.debtor/creditor` 可指向不存在的 `ClassKey`；
反向也漏 ⇒ `ClassRow.debts` 里的 id 可指向不存在的债务。**两张表都在手，构造期可判。**

- [ ] **Step 1: 写失败测试**

在 `EconomyInvariantsTest.java` 追加：
```java
  @Test
  void debtEndpointsMustExistInClasses() {
    assertThatThrownBy(() -> economyWithDanglingDebtEndpoint())
        .as("债务的 debtor/creditor 必须在 classes 里存在（v2 spec §八.2）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("债务");
  }

  @Test
  void classRowDebtRefsMustExistInDebts() {
    assertThatThrownBy(() -> economyWithDanglingDebtRef())
        .as("ClassRow.debts 的每个 id 必须在 debts 表里存在（v2 spec §八.2）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("债务");
  }
```
两个 helper 各造一份最小 `EconomyData`：前者债务指向一个不在 `classes` 里的 `ClassKey`；后者 `ClassRow.debts` 里放一个不在 `debts` 表里的 `DebtId`。

- [ ] **Step 2: 跑测试确认两条都红**

Run: `./mvnw -q -Dtest=EconomyInvariantsTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: 两条都**红**（v1 静默接受）。

- [ ] **Step 3: 实现（★ 注意构造顺序要调）**

`EconomyData.java` 的 compact constructor 里，当前顺序是 `industries → classes → debts → flows`。
因为 classes 现在要拿 debts 表做判据，**必须先把 `debtsCopy` 建出来**：

1. 把现有的 debts 那一段（`Map<DebtId, Debt> debtsCopy = ...` 到 `debts = ...`）**整段上移到 classes 那一段之前**；
2. 在 debts 段内加两端检查：
```java
      if (!classesCopy.containsKey(entry.getValue().debtor())
          || !classesCopy.containsKey(entry.getValue().creditor())) {
        throw new IllegalArgumentException(
            "债务的 debtor/creditor 必须是已存在的阶层行（v2 spec §八.2）："
                + entry.getKey()
                + " 债务="
                + entry.getValue().debtor()
                + " → "
                + entry.getValue().creditor());
      }
```
★ **但这与"debts 先于 classes"冲突**：debts 要查 classes，classes 要查 debts ⇒ **循环依赖**。
⇒ 正确做法是**两段都建完之后再做一遍交叉校验**，而不是在任一段内查对方：
```java
    // ★ 两张表的交叉引用（v2 spec §八.2）：必须等两张表都建完再判，否则陷入循环依赖。
    for (Map.Entry<DebtId, Debt> entry : debtsCopy.entrySet()) {
      Debt debt = entry.getValue();
      if (!classesCopy.containsKey(debt.debtor()) || !classesCopy.containsKey(debt.creditor())) {
        throw new IllegalArgumentException(
            "债务的 debtor/creditor 必须是已存在的阶层行（v2 spec §八.2）："
                + entry.getKey() + " " + debt.debtor() + " → " + debt.creditor());
      }
    }
    for (Map.Entry<ClassKey, ClassRow> entry : classesCopy.entrySet()) {
      for (DebtId debtId : entry.getValue().debts()) {
        if (!debtsCopy.containsKey(debtId)) {
          throw new IllegalArgumentException(
              "ClassRow.debts 引用了不存在的债务（v2 spec §八.2）："
                  + entry.getKey() + " → " + debtId);
        }
      }
    }
```
把这两段放在 **flows 段之前**（flows 的 `requireSlotExists` 只依赖 industries，无妨）。
★ **不要**把 debts 段上移——循环依赖的解法是"都建完再交叉判"，不是调顺序。

- [ ] **Step 4: 跑测试确认两条都绿**

Run: `./mvnw -q -Dtest=EconomyInvariantsTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: PASS。

- [ ] **Step 5: 回归：确认既有往返/编解码用例没被打红**

Run: `./mvnw -q test -pl simos-economy -am`
Expected: 全绿。★ 若 `EconomyRoundTripTest` / `EconomyCodecTest` 因为"它造的夹具里债务是悬空的"而红，
**说明那些夹具本身违反不变量** ⇒ 修夹具（补上被引用的阶层行/债务），**不许**放宽新守卫。

- [ ] **Step 6: 提交**

```bash
git add simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java \
        simos-economy/src/test/java/io/mosire/simos/economy/model/EconomyInvariantsTest.java
git commit -m "fix(economy): 构造期守债务引用两端完整（v1 的 debts 循环只查 null）"
```

---

### Task 4: 不变量 ③ —— `progressDays == cycleDays` 必须能结算（不许炸）

**Files:**
- Modify: `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java`（判据 `==` 改 `>=`）
- Create: `simos-economy/src/test/java/io/mosire/simos/economy/time/EconomyCycleBoundaryTest.java`

**Interfaces:**
- Consumes: `EconomySettlement.settle(EconomyData base, long fromTick, long toTick)`
- Produces: 无新 API（语义修正）

★ **病灶（v2 spec §八.3）**：`Industry` 允许 `progressDays ∈ [0, cycleDays]`（**闭区间**，v1 spec §3.1 原文），
而结算用 `progressed == cycleDays` 判收获、否则 `nextProgress = progressed` ⇒ 下一天构造出 `cycleDays + 1` 而**抛 IAE**，
异常穿出 `EconomyTimeParticipant.simulateWorld` ⇒ **整条推进 revision 失败**。
★ **定案：保留闭区间，改判据为 `>=`**（`progressDays == cycleDays` 的语义就是"周期已满、待收获"，收获并归零是正确的）。

- [ ] **Step 1: 写失败测试（自建最小夹具，不依赖既有 `fixture()` 的内部）**

Create `simos-economy/src/test/java/io/mosire/simos/economy/time/EconomyCycleBoundaryTest.java`:
```java
package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ **周期边界**（v2 spec §八.3）：{@code progressDays == cycleDays} 是**合法值**（闭区间上界），
 * 结算必须把它当"周期已满、待收获"处理，**不是**抛。
 *
 * <p>判别力：把判据改回 {@code ==} ⇒ 本条抛 {@code IllegalArgumentException}（progressDays 必须 ∈ [0, cycleDays]）⇒ 红。
 */
class EconomyCycleBoundaryTest {

  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final ClassSlotId PEASANT = new ClassSlotId("peasant");
  private static final ClassKey PEASANT_KEY = new ClassKey(FARM, PEASANT);
  private static final long CYCLE_DAYS = 120L;

  /** 夹具：一个"周期已满"的产业（progressDays == cycleDays），一行 100 人，全部劳动已记为周期累计。 */
  private static EconomyData fullCycleFixture() {
    ClassSlot slot = new ClassSlot(PEASANT, "贫农", 950);
    // 每标准劳动种 7 亩、100 人 × 580‰ = 58,000 千分劳动 ⇒ 日劳动 = 58,000 × 950‰ = 55,100
    long dailyLabor = 58_000L * 950L / 1000L;
    Industry farm =
        new Industry(
            FARM,
            "农业",
            new RegimeId("feudal"),
            CYCLE_DAYS,
            CYCLE_DAYS, // ★ 边界值：周期已满
            Map.of(),
            0L,
            Map.of(EconomySettlement.GRAIN, 67L),
            List.of(slot),
            new AllocationRule.Split(700, 300),
            dailyLabor * CYCLE_DAYS); // 周期累计劳动（供收获算平均日劳动）
    ClassRow row =
        new ClassRow(
            PEASANT_KEY,
            100L,
            58_000L,
            950,
            Map.of(AssetKind.LAND, 3_100_000L), // 3,100 亩（标定值）
            Map.of(EconomySettlement.GRAIN, 10_000_000L), // 10,000 粮
            0L,
            List.of(),
            Map.of(),
            Map.of());
    return new EconomyData(
        Optional.of(new EconomyMeta("m1", 0L, OptionalLong.empty(), "v2", Optional.empty())),
        Map.of(FARM, farm),
        Map.of(PEASANT_KEY, row),
        Map.of(),
        Map.of());
  }

  @Test
  void aFullCycleHarvestsInsteadOfThrowing() {
    EconomyData next = EconomySettlement.settle(fullCycleFixture(), 0L, 1L);

    assertThat(next.industries().get(FARM).progressDays()).as("周期已满 ⇒ 收获并归零").isZero();
    assertThat(next.industries().get(FARM).cycleLaborMilli()).as("周期累计清零").isZero();
    assertThat(next.meta().orElseThrow().lastClosedCycle()).as("关账周期序号 1").hasValue(1L);

    // 算账：日劳动 55,100 千分劳动 ⇒ 劳动可经营 55,100 × 7 ÷ 1000 = 385 亩（劳动是最短那块，地有 3,100 亩）
    //       毛产 385 × 67 × 1000 = 25,795,000 毫粮；扣 15% 生产消耗 = 3,869,250 ⇒ 净 21,925,750
    //       单行 ⇒ Split 权重 = (700×1000 + 300×1000) ÷ 1000 = 1000 ⇒ 全部归它
    //       库存 = 10,000,000 − 当日口粮 8,300 + 21,925,750 = 31,917,450
    assertThat(next.classes().get(PEASANT_KEY).goods().get(EconomySettlement.GRAIN))
        .as("吃一天 + 收获一次后的粮库存（毫粮）")
        .isEqualTo(31_917_450L);
  }
}
```

- [ ] **Step 2: 跑测试确认它红**

Run: `./mvnw -q -Dtest=EconomyCycleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: **红**，异常为 `IllegalArgumentException: Industry.progressDays 必须 ∈ [0, cycleDays]：progressDays=121, cycleDays=120`
（即 v1 的病灶原样复现）。把这条异常原文抄进提交信息。

- [ ] **Step 3: 改判据**

`EconomySettlement.java` 里把
```java
      if (progressed == industry.cycleDays()) {
```
改为
```java
      // ★ v2 spec §八.3：progressDays ∈ [0, cycleDays] 是**闭区间**（v1 spec §3.1 原文），
      //   cycleDays 的语义是"周期已满、待收获"。用 >= 才能把该合法状态收获掉；
      //   用 == 会让 progressDays=cycleDays 的下一日构造出 cycleDays+1 而在 Industry 构造期抛，
      //   异常穿出时间参与者 ⇒ 整条推进 revision 失败。
      if (progressed >= industry.cycleDays()) {
```

- [ ] **Step 4: 跑测试确认它绿**

Run: `./mvnw -q -Dtest=EconomyCycleBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: PASS。**核对 surefire 报告 mtime 落在本轮**。
★ 若 `31_917_450` 对不上：**先核算式再改期望值**——本用例的期望值是算出来的，对不上说明我对 `harvest` 的理解有偏差，
此时**停下来记下实际值并报给控制器**，不要直接把断言改成"实际值"（那会让用例变成同义反复）。

- [ ] **Step 5: 回归 `cycleDays == 1`（Review Focus 第 8 条）**

在同一个测试类里加：
```java
  @Test
  void aOneDayCycleHarvestsOnItsVeryFirstDayWithoutDividingByZero() {
    // cycleDays == 1 ⇒ avgLaborMilli = cycledLabor / 1；且第 1 天就满足 progressed >= cycleDays
    EconomyData base = withCycleDays(1L);
    EconomyData next = EconomySettlement.settle(base, 0L, 1L);
    assertThat(next.industries().get(FARM).progressDays()).as("1 天周期当天就收获并归零").isZero();
  }

  @Test
  void aZeroPopulationHexProducesNothingAndDoesNotDivideByZero() {
    // Review Focus 第 3 条：农村人口为 0 的纯城市格（劳动 0 ⇒ 投入面积 0 ⇒ 不产粮）
    EconomyData next = EconomySettlement.settle(withPopulation(0L), 0L, 1L);
    assertThat(next.classes().get(PEASANT_KEY).goods()).as("无劳动 ⇒ 不造粮").isEmpty();
  }
```
（`withCycleDays` / `withPopulation` 两个 helper 由执行者按 Step 1 的夹具改造：前者换 `cycleDays` 与 `progressDays`，后者把人口/劳动/库存置 0。）
★ 这两条是 Review Focus 里点名的、**不写就不会被覆盖**的输入类。

- [ ] **Step 6: 提交**

```bash
git add simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java \
        simos-economy/src/test/java/io/mosire/simos/economy/time/EconomyCycleBoundaryTest.java
git commit -m "fix(economy): 周期末判据 == 改 >=（progressDays == cycleDays 是合法值，v1 会炸整条推进）"
```

---

### Task 5: 不变量 ④ —— `WageFirst` 不许留到收获日才炸

**Files:**
- Modify: `simos-economy/src/main/java/io/mosire/simos/economy/model/Industry.java`
- Modify: `simos-economy/src/test/java/io/mosire/simos/economy/model/EconomyInvariantsTest.java`

**Interfaces:**
- Consumes: `AllocationRule.Split`（sealed interface 的唯一 v1 实现）
- Produces: 无新 API（构造期守卫）

★ **病灶（v2 spec §八.4）**：`EconomySettlement.harvest` 对非 `Split` 直接抛 `UnsupportedOperationException`，
异常穿出 `EconomyTimeParticipant.simulateWorld` ⇒ 整次 `AdvanceTime` 失败（既不是 Rejected 也不是降级）。
而 v2 spec §五 明列四种制度，第四种资本主义工业就是 `WageFirst`。
★ **本任务只把"接不住"提前到构造期**；`WageFirst` 的**结算**是后续增量，不在本计划。

- [ ] **Step 1: 写失败测试**

在 `EconomyInvariantsTest.java` 追加：
```java
  @Test
  void wageFirstAllocationIsRejectedAtConstructionTimeNotAtHarvest() {
    // ★ 判别力：v1 允许它入库，直到收获那天才抛 ⇒ 一条 revision 白跑
    assertThatThrownBy(() -> industryWith(new AllocationRule.WageFirst(1000L, Map.of())))
        .as("v1 不支持的分配函数必须在构造期拒（v2 spec §八.4）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Split");
  }

  @Test
  void splitAllocationIsStillAccepted() {
    assertThat(industryWith(new AllocationRule.Split(700, 300))).isNotNull();
  }
```
helper `industryWith(AllocationRule)` 造一个最小合法 `Industry`，只换 `allocation`。

- [ ] **Step 2: 跑测试确认它红**

Run: `./mvnw -q -Dtest=EconomyInvariantsTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: 第一条**红**（构造期没抛，`assertThatThrownBy` 失败）；第二条绿。

- [ ] **Step 3: 实现**

`Industry.java` 的 compact constructor 里，紧跟现有的
```java
    if (allocation == null) {
      throw new IllegalArgumentException("Industry.allocation 不得为 null");
    }
```
之后加：
```java
    // ★ v2 spec §八.4：v1 的周期结算只实现了 AllocationRule.Split（小农/封建租佃/手工业）。
    //   WageFirst（资本主义工业）是后续增量 —— 必须在**构造期**拒，而不是等到某个收获日
    //   在 EconomySettlement.harvest 里抛 UnsupportedOperationException：那个异常会穿出
    //   EconomyTimeParticipant.simulateWorld，让整条 AdvanceTime revision 失败。
    if (!(allocation instanceof AllocationRule.Split)) {
      throw new IllegalArgumentException(
          "v1 的周期分配只支持 AllocationRule.Split（v2 spec §八.4）：" + allocation);
    }
```
★ **`EconomySettlement.harvest` 里那道 `UnsupportedOperationException` 保留不动** —— 本仓的既定风格是"构造期守卫 + 运行期再兜一层"两道，删掉它会少一层。

- [ ] **Step 4: 跑测试确认两条都绿**

Run: `./mvnw -q -Dtest=EconomyInvariantsTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: PASS。

- [ ] **Step 5: 回归：编解码与载荷路径**

Run: `./mvnw -q test -pl simos-economy,simos-app -am`
Expected: 全绿。
★ `EconomyPayloads`（载荷解析）走 `new Industry(...)`，故 `WageFirst` 的载荷会被 handler 的 `catch (IllegalArgumentException)` 折成 `Rejected` —— 这是**期望行为**（可读理由），不是故障。若有用例期望它被接受，该用例要改。

- [ ] **Step 6: 提交**

```bash
git add simos-economy/src/main/java/io/mosire/simos/economy/model/Industry.java \
        simos-economy/src/test/java/io/mosire/simos/economy/model/EconomyInvariantsTest.java
git commit -m "fix(economy): WageFirst 在构造期拒（v1 留到收获日才抛，穿出时间参与者让整条推进失败）"
```

---

### Task 6: 地形产能改读 `TerrainType.food`

**Files:**
- Modify: `simos-app/.../world/EconomySeeder.java`（删 `terrainCoefPerMille` 与两个 `COEF_*` 常量，改读 map）
- Modify: `simos-app/src/test/java/io/mosire/simos/app/world/EconomySeederTest.java`

**Interfaces:**
- Consumes: `io.mosire.simos.map.terrain.TerrainCatalog.of(String)` → `TerrainType`；`TerrainType.food()` → `int`
- Produces: `EconomySeeder.arablePerMilleOf(int food)` → `int`（每格可耕地系数，千分）

★ **病灶**：`TerrainType`（map）已有 `int food`（粮食产能，**六档**：平原 3 / 低丘 2 / 平缓高原 1 / 沙漠 0 / 山地 0 / 海洋 0），而 `EconomySeeder.terrainCoefPerMille` 另写了一份**两档**表（平原 1.0 / 低丘 0.6），其余地形 **fail-closed 抛**。真相在 map、且比 economy 那份细。

★ **折算口径**（本计划新定，写进常量注释）：`arablePerMille = food × 1000 / 3`，其中 `3` 是"满可耕地"的产能档（= `TerrainCatalog` 里平原的 `food`）。
⇒ 平原 1000‰、低丘 666‰、平缓高原 333‰、沙漠/山地/海洋 0‰。
★ 与 v1 的差异：**低丘从 600‰ 变 666‰**（v1 的 600 是手挑的，没有依据）。

- [ ] **Step 1: 写失败测试**

在 `EconomySeederTest.java` 追加（沿用该文件已有的断言风格）：
```java
  @Test
  void arablePerMilleComesFromTheTerrainCatalogNotALocalTable() {
    // 满可耕地 = 平原的 food 档；这一条同时钉住"map 改了平原产能 ⇒ 经济侧立刻知道"
    assertThat(EconomySeeder.arablePerMilleOf(TerrainCatalog.of("plains").food()))
        .as("平原 = 满可耕地")
        .isEqualTo(1000);
    assertThat(EconomySeeder.arablePerMilleOf(TerrainCatalog.of("low_hills").food()))
        .as("低丘 = 2/3")
        .isEqualTo(666);
    assertThat(EconomySeeder.arablePerMilleOf(TerrainCatalog.of("plateau").food()))
        .as("平缓高原 = 1/3")
        .isEqualTo(333);
  }

  @Test
  void zeroFoodTerrainsGetZeroArableLandInsteadOfThrowing() {
    // ★ v1 的 fail-closed：这三种地形会抛 ⇒ 沙漠/山地格一旦有人口，整批 worldgen 回滚
    for (String terrain : List.of("desert", "mountains", "ocean")) {
      assertThat(EconomySeeder.arablePerMilleOf(TerrainCatalog.of(terrain).food()))
          .as("%s 必须得 0 而不是抛", terrain)
          .isZero();
    }
  }

  @Test
  void unknownTerrainStillFailsClosed() {
    // ★ 0 与"不认识"是两件事：前者是合法产能，后者是坏数据
    assertThatThrownBy(() -> TerrainCatalog.of("swamp"))
        .as("未知地形必须抛，不许当 0")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知地形类型");
  }
```
（若该文件尚未 import `TerrainCatalog` / `assertThatThrownBy`，一并补上。）

- [ ] **Step 2: 跑测试确认它红**

Run: `./mvnw -q -Dtest=EconomySeederTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-app -am`
Expected: **编译错误**（找不到 `arablePerMilleOf`）。

- [ ] **Step 3: 实现**

在 `EconomySeeder.java` 里：
1. 删 `COEF_PLAINS_PER_MILLE` / `COEF_LOW_HILLS_PER_MILLE` / `terrainCoefPerMille(String)`；
2. 加：
```java
  /** 满可耕地的产能档（= {@code TerrainCatalog} 里平原的 {@code food}）：{@link #arablePerMilleOf} 的分母。 */
  private static final int FOOD_AT_FULL_ARABLE = 3;

  /**
   * 每格可耕地系数（千分）：由 **map 的 {@link TerrainType#food()}** 折算，**不自建地形表**。
   *
   * <p>★ 为什么不自建：v1 在 economy 侧另写了一份两档表（平原 1.0 / 低丘 0.6），而 map 的 {@code TerrainType}
   * 早就有六档 {@code food} —— 同一个事实两处，且 map 那份更细。真相只有一个拼写点。
   *
   * <p>★ 为什么不抛：{@code food == 0} 是**合法产能**（沙漠/山地/海洋），得 0 亩即可；抛会把"这颗格不产粮"
   * 误报成"数据坏了"。（v1 对非平原/低丘一律抛 ⇒ 地形直方图一变就整批 worldgen 回滚。）
   */
  static int arablePerMilleOf(int food) {
    return food * 1000 / FOOD_AT_FULL_ARABLE;
  }
```
3. 把原先调 `terrainCoefPerMille(terrain)` 的地方改成 `arablePerMilleOf(TerrainCatalog.of(terrain).food())`。
4. 加一条**把折算分母钉到 map** 的用例（防 map 改平原 `food` 而经济侧无感）：
```java
  @Test
  void fullArableFoodMatchesTheCatalogPlain() {
    assertThat(TerrainCatalog.of("plains").food())
        .as("FOOD_AT_FULL_ARABLE 必须等于 map 里平原的产能档；map 改了这里就要红")
        .isEqualTo(3);
  }
```

- [ ] **Step 4: 跑测试确认它绿**

Run: `./mvnw -q -Dtest=EconomySeederTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-app -am`
Expected: PASS。**核对 surefire 报告的 mtime 落在本轮**。

- [ ] **Step 5: 变异自证**

把 `arablePerMilleOf` 的分母从 `FOOD_AT_FULL_ARABLE` 改成硬编码 `4`（模拟"分母与 map 脱钩"）⇒ 重跑 ⇒ **必须红**（低丘会变 500 而非 666）。
再改成"对 `food == 0` 也抛"（模拟 v1 的 fail-closed）⇒ 重跑 ⇒ **`zeroFoodTerrainsGetZeroArableLandInsteadOfThrowing` 必须红**。
两轮都按字节还原并重跑回绿。

- [ ] **Step 6: 提交**

```bash
git add simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java \
        simos-app/src/test/java/io/mosire/simos/app/world/EconomySeederTest.java
git commit -m "refactor(economy): 地形产能改读 map 的 TerrainType.food（删 economy 自建的两档表）"
```

---

### Task 7: 量纲标定（`MU_PER_HEX` → 3,100、亩产 → 67）+ 真档自给率验收

**Files:**
- Modify: `simos-app/.../world/EconomySeeder.java`（两个常量改值 + 注释写明依据）
- Modify: `simos-app/src/test/java/io/mosire/simos/app/world/EconomySeederTest.java`
- Modify: `simos-app/src/test/java/io/mosire/simos/app/tools/write/WorldgenInitializeToolTest.java`（凡断言旧库存/旧土地的期望值）
- Modify: `simos-app/src/test/java/io/mosire/simos/app/world/EconomySettlementEndToEndTest.java`（若夹具依赖旧亩产）

**Interfaces:**
- Consumes: `EconomySeeder.MU_PER_HEX`、`GRAIN_OUTPUT_PER_MU`（既有常量，本任务改值）
- Produces: 无新 API

★ **定案值（v2 spec §10.3）**：`MU_PER_HEX` `1,000 → 3,100`；`GRAIN_OUTPUT_PER_MU` `7 → 67`。
★ **两个数都改**——v1 的两个数都错：`7 粮/亩`（= 14 斤/亩）比前现代真实低约 10 倍；`1,000 亩/格` 是拍脑袋的。
★ **标定算式**（执行者必须能在注释里复述）：
```
需粮   = 14,806 人 × 9,960 毫粮/周期 ÷ 1000 = 147,468 粮/格/周期
净产   = 3,100 亩 × 67 粮/亩 × 0.85        = 176,545 粮/格/周期
自给率 = 176,545 ÷ 147,468                 = 119.7%
```
取 120%（而非刚好 100%）是因为农业还要养城市人口与军队，而手工业现产 0。

- [ ] **Step 1: 写失败测试（真档量级 + 自给率区间）**

在 `EconomySeederTest.java` 追加：
```java
  /**
   * ★★ **真档量级下的自给率**（v2 spec §1.1.1 / §九 V2 判据 ④）。
   *
   * <p>真档每格人口 11,830,000 ÷ 799 = 14,806 人；亩产与每格亩数是**拍出来的假设**，必须被这条钉住。
   * 标定前（v1 的 1,000 亩 × 7 粮/亩）这条是**红的**：自给率约 4%。
   */
  @Test
  void realScaleHexIsSelfSufficientWithinTheCalibratedBand() {
    long populationPerHex = 11_830_000L / 799L; // = 14,806
    long needMilli = populationPerHex * EconomySeeder.dailyGrainMilli(1L) * 120L; // 每格每周期需粮（毫粮）
    long grossMilli =
        EconomySeeder.MU_PER_HEX
            * EconomySeeder.GRAIN_OUTPUT_PER_MU
            * EconomyVocabulary.MILLI_PER_GRAIN;
    long netMilli = grossMilli * 850L / 1000L; // 饲料 + 折旧 = 15%
    long perMille = netMilli * 1000L / needMilli;

    assertThat(populationPerHex).as("真档每格人口").isEqualTo(14_806L);
    assertThat(perMille)
        .as("自给率必须落在 1100~1300‰（标定目标 1197‰）；标定前这里是 ~40‰")
        .isBetween(1100L, 1300L);
  }
```

- [ ] **Step 2: 跑测试确认它红**

Run: `./mvnw -q -Dtest=EconomySeederTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-app -am`
Expected: **红**，断言消息显示自给率约 `40`（v1 的 1000 × 7 × 1000 = 7e6 毫粮 vs 需 1.47e8 毫粮 ⇒ 约 47‰）。
★ 这一轮的红色是**本任务存在的理由**——把它的实际值抄进提交信息。

- [ ] **Step 3: 改两个常量**

`EconomySeeder.java`：
```java
  /**
   * 每格土地基准（亩）：**量纲标定值**（v2 spec §10.3 定案 A）。
   *
   * <p>★ 依据：真档每格 14,806 人 × 9,960 毫粮/周期 = 147,468 粮/格/周期需粮；
   * 3,100 亩 × 67 粮/亩 × 0.85 = 176,545 ⇒ 自给率 119.7%（余粮给城市人口与军队）。
   * ★ 格面积是**纯经济假设**（{@code HexCell} 只存 height），与地图无关。
   */
  public static final long MU_PER_HEX = 3_100L;

  /**
   * 农业每亩毛产（粮）：**量纲标定值**（v2 spec §10.3 定案 A）。67 粮/亩 = 134 斤/亩，
   * 是前现代北方旱地小麦的量级；v1 的 7（= 14 斤/亩）低约 10 倍。
   */
  public static final long GRAIN_OUTPUT_PER_MU = 67L;
```

- [ ] **Step 4: 跑测试确认它绿**

Run: `./mvnw -q -Dtest=EconomySeederTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-app -am`
Expected: PASS（自给率落在 1100~1300‰）。

- [ ] **Step 5: 修被改值打红的既有用例**

Run: `./mvnw -q test -pl simos-app -am`
Expected: 有若干用例红（它们钉的是旧亩产/旧土地的期望字面量）。
★ **逐个判断**：期望值里凡是 `1_000_000`（旧千分亩）或 `7`（旧亩产）相关的，按新值重算并更新；**不许**为了让测试变绿而放宽断言（例如把 `isEqualTo` 改成 `isPositive`）——那会消掉判别力，本仓明令禁止。
★ 更新后的字面量必须**用算式在注释里写清怎么来的**（例：`// 每格 3100 亩 × 1000 千分亩/亩 = 3_100_000 千分亩`）。

- [ ] **Step 6: 提交**

```bash
git add simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java \
        simos-app/src/test/java/io/mosire/simos/app/world/EconomySeederTest.java \
        simos-app/src/test/java/io/mosire/simos/app/tools/write/WorldgenInitializeToolTest.java \
        simos-app/src/test/java/io/mosire/simos/app/world/EconomySettlementEndToEndTest.java
git commit -m "fix(economy): 量纲标定 —— 每格亩数 1000→3100、亩产 7→67（自给率 4% → 119.7%）"
```
提交信息须含：标定前后的自给率实测值（红轮与绿轮各一个数）、算式、未验的部分。

---

## 收尾：本计划的关账

- [ ] `./mvnw clean verify`（**前台**，确认没有别的 Maven 在跑）
- [ ] 核对 **11 个模块**全绿，且 Spotless / Checkstyle / SpotBugs / 前端门禁都过
- [ ] 更新 `.superpowers/sdd/2026-09-25-aggregate-economy/progress.md`：补 V1、V2 两行（含**实际数字**与证据路径）
- [ ] 确认台账不再落后代码（v2 spec §11.2 列了它当前落后 5 个提交）
