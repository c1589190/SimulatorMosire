# B1/B2 前置修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉两个与 S1 正交的真 bug —— 生理压力漏算无劳动配额的人口、实时危机指标的时间基准不一致 —— 并用 regression test 把两条不变量钉死，为 S1 建立可信的验收基线。

**Architecture:** 两处都是**局部修正**，不改任何数据形状、不动任何守恒式。B1 给 `applyDailyStress` 补一条兜底（与 `applyPopulationChange` 已有的兜底共用同一实现）；B2 让 `CrisisMonitor` 的分子分母用同一个时间基准。两条都**测试先行**。

**Tech Stack:** Java 21 · Maven（`./mvnw`）· JUnit 5 + AssertJ · 本仓聚合式经济（`simos-economy` / `simos-social` / `simos-app`）

**Spec:** `docs/superpowers/specs/2026-09-26-s1-actor-property-design.md` §十（落地顺序与两条不变量）

## Global Constraints

- **Java 21**；构建只走 `./mvnw`（父 POM 是 `io.mosire:simos-parent`，**不继承** `io.mosire.mosire-parent`）
- **门禁 fail-closed**：跑测试看 `target/surefire-reports/*.txt` 的 **mtime 落在本轮**，**不看 rc**（`-q` 会吞掉 surefire 汇总）
- **测试一律一次一个类**（R2 踩过 `-Dtest` 多类过滤的假绿）
- **一次只能跑一个 Maven**；跑前 `pgrep -af "surefirebooter|classworlds.launcher"`
- **收尾必须前台跑 `./mvnw clean verify`**，把 Reactor 逐模块结果抄进报告
- **注释与提交信息用中文**，与既有风格一致（`AGENT.md` §五.8）
- **不 `git add -A`**；按批次提交；**提交信息要写清"改了什么 + 为什么 + 验收的实际数字 + 未验的部分"**
- **护栏必须自证**：每条新断言都要**变异自证**（改坏 ⇒ 当场红 ⇒ `Edit` 反向重写还原 ⇒ 复绿）
- ★ **不许放宽既有断言**；若某条既有断言因本次修复而变红，**先查被测物**，并按新口径**手算重算**期望值（算式写进注释），**不许抄实际值**
- ★ **真档数字会变**（B1 修完，儿童与新生儿开始累积压力）⇒ 这是**修正**不是回归，但要在提交信息里给出新数字

## Review Focus

以下五类输入/条件，spec 隐含要求但没有任何既有测试覆盖，最可能咬到人（每条都已在下面的任务里配了测试）：

1. **新生儿批次（出生后自然无 `LaborAllocation`）** —— 期望：它和它的母亲一样承受压力；现值：恒 0
2. **0-14 档创世批次（劳动系数 0 ⇒ 创世不发配额）** —— 同上
3. **一个"该格完全没有经济状态"的批次**（世界还没播种到这里）—— 期望：跳过而不是崩
4. **周期第一天（`progressDays == 0`）读危机** —— 期望：不因分母为 0 而崩，也不因分母是整周期而虚高
5. **同一场持续缺粮在周期不同相位读** —— 期望：红灯类别一致

---

### Task 1: B1 —— 让所有有人的 cohort 都参与压力计算

**Files:**
- Modify: `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java`（提取 `industriesAt` 为包内可见的共用方法）
- Modify: `simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java:186-189`（`applyDailyStress` 补兜底）
- Test: `simos-app/src/test/java/io/mosire/simos/app/world/PopulationR4Test.java`（加一条用例）

**Interfaces:**
- Consumes: `EconomySettlement.applyPopulationChange` 里已有的兜底写法 `industriesAt(base, change.at())`
- Produces: `EconomySettlement.industriesAt(EconomyData base, HexCoord at)` —— **包内可见静态方法**，返回 `List<IndustryId>`（该格的全部产业，保序；无则空表）。**两处共用它**（`applyPopulationChange` 与 `applyDailyStress`）

- [ ] **Step 1: 写失败的测试**

在 `PopulationR4Test.java` 末尾（最后一个 `}` 之前）加：

```java
  // ── ★★ B1 regression：压力与劳动配额无关 ──────────────────────────────────────────────

  /**
   * ★★ **不变量原文**（S1 spec §十）：
   * <em>所有存在人口的 cohort 都参与生理压力计算，与其有没有 {@code LaborAllocation} 完全无关。</em>
   *
   * <p>★ 为什么要钉它：修 B1 之前，{@code applyDailyStress} 用 {@code industriesOf}
   * （来自 {@code economy.allocations()}）过滤批次，**没有配额的批次直接 continue ⇒ 压力恒 0**。
   * 两类批次从未有过配额：0-14 档的创世批次（劳动系数 0）、以及**全部新生儿批次**（创世之后产生）。
   *
   * <p>★ 判别力：把兜底改回 {@code continue} ⇒ 本条两条断言都红。
   */
  @Test
  void everyPopulatedCohortAccumulatesStressRegardlessOfLaborQuota() {
    Fixture after = advanced(seeded(), 180L);
    SocialData social = after.social();
    EconomyData economy = after.economy();

    // ① 创世就有、且**没有劳动配额**的批次：0-14 档（年龄 < 15 年）
    List<PopulationGroup> minors =
        social.groups().values().stream()
            .filter(g -> g.ageDaysAt(180L) < PopulationDynamics.FERTILE_MIN_DAYS)
            .filter(g -> !economy.laborSupply().containsKey(g.id()))
            .toList();
    assertThat(minors).as("夹具前提：真的存在没有配额的未成年批次").isNotEmpty();
    assertThat(minors.stream().mapToLong(PopulationGroup::physiologicalStress).max().orElse(0L))
        .as("★★ 没有配额 ≠ 不挨饿：0-14 档也要累积压力")
        .isPositive();

    // ② 新生儿批次（id 的 cohort 段以 'b' 开头）
    List<PopulationGroup> newborns =
        social.groups().values().stream()
            .filter(g -> g.id().value().contains(":b"))
            .toList();
    assertThat(newborns).as("夹具前提：半年里真的生了孩子").isNotEmpty();
    assertThat(newborns.stream().mapToLong(PopulationGroup::physiologicalStress).max().orElse(0L))
        .as("★★ 新生儿同样要累积压力（否则饥荒抑制只对创世那一代生效）")
        .isPositive();
  }
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `./mvnw -pl simos-app -am test -Dtest=PopulationR4Test`

Expected: **FAIL** —— 两条 `.isPositive()` 断言都红（实测 `stress` 恒 0）

- [ ] **Step 3: 提取共用兜底**

在 `EconomySettlement.java` 里把现有的 `industriesAt`（当前是 `private static`，约 598 行）改成**包内可见**并在 javadoc 里注明两处共用：

```java
  /**
   * ★★ **某一格的全部产业**（保序：产业表的插入序）。**两处共用**（{@code applyPopulationChange} 与
   * {@code PopulationEconomyTimeParticipant#applyDailyStress}）—— 抽成一个方法是因为"没有配额的批次该
   * 按哪一格算"这个问题**只能有一个答案**，两处各写一遍必然漂（S1 spec §十的原文）。
   */
  static List<IndustryId> industriesAt(EconomyData base, HexCoord at) {
    // ...（方法体一字不动，只把 private 去掉）
  }
```

- [ ] **Step 4: 给 `applyDailyStress` 补兜底**

`PopulationEconomyTimeParticipant.java` 中，把：

```java
      List<IndustryId> targets = industriesOf.getOrDefault(group.id(), List.of());
      if (targets.isEmpty()) {
        continue;
      }
```

改成：

```java
      List<IndustryId> targets = industriesOf.getOrDefault(group.id(), List.of());
      if (targets.isEmpty()) {
        // ★★ B1 修复：**没有劳动配额的批次照样要吃饭、照样会挨饿** —— 按它**住的那一格**的产业行算满足率。
        //   两类批次走到这里：0-14 档（劳动系数 0 ⇒ 创世不发配额）与**全部新生儿批次**（创世之后产生）。
        //   与 EconomySettlement.applyPopulationChange 的兜底**共用同一个方法** —— 那个问题是同一个，
        //   答案也只能有一个（两处各写一遍必然漂）。
        targets = EconomySettlement.industriesAt(economy, group.residence());
      }
      if (targets.isEmpty()) {
        continue; // 该格本来就没有任何经济状态（世界还没播种到这里）⇒ 没有可算的满足率
      }
```

- [ ] **Step 5: 跑测试确认它通过**

Run: `./mvnw -pl simos-app -am test -Dtest=PopulationR4Test`

Expected: **PASS**（含新增那条；既有 7 条也全绿 —— 若某条既有断言红了，**先查被测物**，再按新口径手算重算期望值）

- [ ] **Step 6: 变异自证**

把 Step 4 的兜底**临时改回** `continue`（只留 `if (targets.isEmpty()) { continue; }`），跑同一条测试。

Run: `./mvnw -pl simos-app -am test -Dtest=PopulationR4Test`

Expected: **FAIL**（新增用例的两条 `.isPositive()` 红）⇒ 用 `Edit` **反向重写**还原 Step 4 的代码 ⇒ 再跑一次 **PASS**

- [ ] **Step 7: 记录新数字（提交信息要用）**

在 `PopulationR4Test` 那条用例里**临时**加一行打印（跑完删掉），或用 `-Dtest` 单独跑一次并读 surefire 报告，记下：
- 修前：未成年批次压力 max = ____、新生儿压力 max = ____（应均为 0）
- 修后：未成年批次压力 max = ____、新生儿压力 max = ____（应 > 0）
- `advanced(seeded(), 180L)` 的**总死亡数**修前 ____ / 修后 ____（预期**变大** —— 儿童开始承受超额死亡）

★ 这组数字是"改了什么"的证据，**必须进提交信息**。

- [ ] **Step 8: 提交**

```bash
git add simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java \
        simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java \
        simos-app/src/test/java/io/mosire/simos/app/world/PopulationR4Test.java
git commit -m "fix(social,economy,app): B1 —— 没有劳动配额的人口照样挨饿（压力漏算整类批次）

## 为什么
applyDailyStress 用 industriesOf（来自 economy.allocations()）过滤批次，
没有配额的批次直接 continue ⇒ 压力恒 0。两类批次从未有过配额：
0-14 档的创世批次（劳动系数 0），以及**全部新生儿批次**（创世之后产生）。

后果：① 儿童不承受压力导致的超额死亡；② 新生儿长大到育龄仍无配额
⇒ 饥荒的生育抑制**只对创世那一代生效**，随时间自我失效。

## 改了什么
兜底到"它住的那一格的产业行"，与 EconomySettlement.applyPopulationChange
的既有兜底**共用同一个方法**（industriesAt 提为包内可见）——
"没有配额的批次该按哪一格算"只能有一个答案。

## 验收的实际数字
（填 Step 7 记下的四组数）

## 未验
这是**修正不是回归**：儿童与新生儿开始累积压力 ⇒ 死亡数变大。
未清点它对其它端到端字面量的影响面。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: B2 —— 危机指标的时间基准一致

**Files:**
- Modify: `simos-app/src/main/java/io/mosire/simos/app/crisis/CrisisMonitor.java`（`lightsAt` 的分母）
- Create: `simos-app/src/test/java/io/mosire/simos/app/crisis/CrisisMonitorPhaseTest.java`

**Interfaces:**
- Consumes: `CrisisMonitor.lightsAt(HexCoord, EconomyData, SocialData, long atTick)`（**纯函数，可直接单测**）；`Industry.progressDays()`
- Produces: 无新公开 API（只改口径）

- [ ] **Step 1: 写失败的测试**

Create: `simos-app/src/test/java/io/mosire/simos/app/crisis/CrisisMonitorPhaseTest.java`

```java
package io.mosire.simos.app.crisis;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.world.EconomyTestWorld;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * ★★ **B2 regression：实时危机指标必须与"你在周期的第几天读"无关**（S1 spec §十的第二条不变量）。
 *
 * <p>修之前：分子是"本周期累计的 {@code unmetNeed}"、分母是 {@code cumulativeRationMilli(pop, cycleDays)}
 * （**整周期**需求）⇒ 周期初分子只累计几天、分母却已按 120 天算 ⇒ **满足率虚高 ⇒ 持续危机读成空**。
 *
 * <p>★ 判别力：把分母改回 {@code cycleDays} ⇒ 本条红。
 */
class CrisisMonitorPhaseTest {

  private static final HexCoord HEX = new HexCoord(0, 0);

  /** 同一场持续缺粮，在同一个周期的三个相位读，**红灯类别集合必须相同**。 */
  @Test
  void crisisKindsAreInvariantAcrossCyclePhase() {
    // 夹具：一格从第 1 天起就吃不饱（沙漠 ⇒ 可耕地系数 0），故"缺粮"是**持续**的，不是脉冲。
    EconomyTestWorld world = EconomyTestWorld.of(/* 见下：按 EconomyTestWorld 的实际工厂方法填写 */);

    Set<String> day1 = kindsAt(world, day(1));
    Set<String> day60 = kindsAt(world, day(60));
    Set<String> day119 = kindsAt(world, day(119));

    assertThat(day119).as("前提：周期末确实报着红灯（否则本条测不出东西）").isNotEmpty();
    assertThat(day1).as("★★ 同一场持续危机，周期第 1 天读到的类别必须和周期末一致").isEqualTo(day119);
    assertThat(day60).as("★ 第 60 天同理").isEqualTo(day119);
  }

  private static Set<String> kindsAt(EconomyTestWorld world, long tick) {
    return CrisisMonitor.lightsAt(HEX, world.economy(), world.social(), tick).stream()
        .map(light -> light.kind().name())
        .collect(Collectors.toSet());
  }

  private static long day(long n) {
    return n;
  }
}
```

★ **实现者注意**：`EconomyTestWorld` 的**实际工厂方法名与"造一个第 N 天状态"的写法要按该类的现有 API 填**
（本仓惯例是"读类注 + 既有用例怎么用的"；`PopulationR4Test` 的 `Fixture`/`advanced(...)` 是同一套夹具的用法示例）。
**不许**为了迁就测试去改 `EconomyTestWorld` 的公开 API。

- [ ] **Step 2: 跑测试确认它失败**

Run: `./mvnw -pl simos-app -am test -Dtest=CrisisMonitorPhaseTest`

Expected: **FAIL** —— `day1` 是空集（周期第 1 天读不到任何红灯），而 `day119` 非空

- [ ] **Step 3: 改分母的时间基准**

`CrisisMonitor.java` 的 `lightsAt(...)` 里，把两处：

```java
      long cycleDays =
          economy.industries().containsKey(key.industry())
              ? economy.industries().get(key.industry()).cycleDays()
              : 1L;
      grainNeed += EconomyVocabulary.cumulativeRationMilli(row.population(), cycleDays);
      clothNeed += EconomyVocabulary.cumulativeClothMilli(row.population(), cycleDays);
```

改成：

```java
      // ★★ B2 修复：**分子与分母必须同基准**。
      //   分子是"本周期**至今**累计的 unmetNeed"，故分母必须是"本周期**至今**的需求"，
      //   而不是整周期的需求 —— 否则周期初分子只有几天、分母已是 120 天 ⇒ 满足率虚高 ⇒ 红灯不亮。
      //   `cumulativeRationMilli` 的类注本就写明它是"**天的函数**（不是'周期内第几天'的函数）"。
      long elapsedDays =
          economy.industries().containsKey(key.industry())
              ? Math.max(1L, economy.industries().get(key.industry()).progressDays())
              : 1L; // 周期第 0 天按 1 天算：既不除零，也不把"刚开始"读成"完全满足"
      grainNeed += EconomyVocabulary.cumulativeRationMilli(row.population(), elapsedDays);
      clothNeed += EconomyVocabulary.cumulativeClothMilli(row.population(), elapsedDays);
```

★ **同时在红灯的 `evidence` 里加上相位信息**（让人知道这是"部分周期"的读数）：

```java
      Map<String, Object> evidence = new LinkedHashMap<>();
      evidence.put("grainSatisfactionPerMille", grainSatisfaction);
      evidence.put("grainUnmetMilli", grainUnmet);
      evidence.put("elapsedDays", elapsedDaysIn(economy, key)); // ★ 新增
      evidence.put("cycleDays", cycleDaysIn(economy, key));     // ★ 新增
```

（两个私有助手各返回该产业的 `progressDays()` / `cycleDays()`，缺席时返回 `1L`。）

- [ ] **Step 4: 跑测试确认它通过**

Run: `./mvnw -pl simos-app -am test -Dtest=CrisisMonitorPhaseTest`

Expected: **PASS**

- [ ] **Step 5: 变异自证**

把 Step 3 的 `elapsedDays` 临时改回 `cycleDays`，跑同一条测试。

Run: `./mvnw -pl simos-app -am test -Dtest=CrisisMonitorPhaseTest`

Expected: **FAIL** ⇒ `Edit` 反向重写还原 ⇒ 再跑 **PASS**

- [ ] **Step 6: 记录新数字**

同一格在 `tick=1 / 60 / 119` 三处读到的：`grainSatisfactionPerMille` 修前 ____ / ____ / ____
（修前第 1 天应接近 1000）、修后 ____ / ____ / ____（三者应彼此接近）。★ 进提交信息。

- [ ] **Step 7: 提交**

```bash
git add simos-app/src/main/java/io/mosire/simos/app/crisis/CrisisMonitor.java \
        simos-app/src/test/java/io/mosire/simos/app/crisis/CrisisMonitorPhaseTest.java
git commit -m "fix(app): B2 —— 危机红灯的时间基准一致（周期初不再把持续危机读成空）

## 为什么
分子用"本周期累计的 unmetNeed"、分母用 cycleDays 对应的**整周期**需求
⇒ 周期初分子只累计几天、分母已按 120 天算 ⇒ 满足率虚高 ⇒ 红灯不亮。
而该指标的设计语义是**实时**红灯（spec §七：按'**当前**需求满足水平'），
读口又挂在实时的 /api/social/population 上 ⇒ 是 bug，不是采样问题。

## 改了什么
分母改用本周期**已过天数**（progressDays，周期第 0 天按 1 天算）；
红灯 evidence 增加 elapsedDays / cycleDays 两个字段，让人知道这是部分周期的读数。

## 验收的实际数字
（填 Step 6 的六个数）

## 未验
未评估对既有 GUI/MCP 读口消费方的影响（evidence 只增字段，不改既有键）。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: 最小 probe —— 证明两个 bug 已消失

**Files:**
- 无源码改动；产出是一份**读数记录**

**Interfaces:**
- Consumes: Task 1 / Task 2 的修复
- Produces: 一份最小 probe 的读数（进 `.superpowers/sdd/` 台账）

★ **为什么不做完整 v3 报表**（用户 2026-09-26 裁定）：
> **不需要重新做一份巨大 v3 报表再开始 S1。** 修完 B1/B2 后跑足以证明两个 bug 已消失的最小 probe 就行，
> 因为 **S1 本来就会让旧经济数值基线大面积失效**。

- [ ] **Step 1: 打包并起实例**

```bash
./mvnw -DskipTests package -pl simos-app -am
tools/run-shaded.sh simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --store <JOB_TMP>/simos-probe2 --gui-port 5847 --mcp-port 5745 --approval-port 5743
```

★ 收工用 **PID** 停（`ps -eo pid,cmd | grep simos-shaded` ⇒ `kill <pid>`），**不要 `pkill -f`**。
★ 所有 curl 都要 `--noproxy '*'`。

- [ ] **Step 2: 种三国并推进 180 天**

用 `.superpowers/sdd/2026-09-26-year-one-simulation/` 里的四个脚本（改端口常量即可复用）。

- [ ] **Step 3: 读两条判据**

```bash
# 判据 ①：0-14 与新生儿批次的压力不再恒 0
python3 -c "…读 changeset 的 social.groups，按 cohort 段分组取 stress 的 max…"
# 判据 ②：同一周期在不同相位读红灯，类别集合一致（对若干格抽样）
```

Expected：
- 判据 ① —— 0-14 档与 `:b*` 新生儿的压力 **max > 0**（修前恒 0）
- 判据 ② —— 同一格在周期第 1 天与第 119 天读到的 **红灯类别集合相同**

- [ ] **Step 4: 写读数记录并提交**

把两组数字写进 `.superpowers/sdd/2026-09-26-b1b2-probe/readings.md`（含世界参数、读数时刻、原始 JSON 路径），
按 `.superpowers/sdd/.gitignore` 的先例 `git add -f` 后提交。

```bash
git add -f .superpowers/sdd/2026-09-26-b1b2-probe/
git commit -m "docs(sdd): B1/B2 修复后的最小 probe 读数（两条不变量的实档证据）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 收尾（不属于任何单个 Task）

- [ ] 前台跑 **`./mvnw clean verify`**，把 **Reactor 逐模块结果**（`Tests run: … Failures: … Errors: …`）抄进最终报告
- [ ] 核对 `target/surefire-reports/*.txt` 的 **mtime 落在本轮**
- [ ] 确认 Spotless / Checkstyle / SpotBugs(0 bug) / 前端门禁 全过

---

## Self-Review

**Spec coverage**（对照 `docs/superpowers/specs/2026-09-26-s1-actor-property-design.md` §十）：

| Spec 要求 | 落点 |
|---|---|
| B1 regression + fix | Task 1（Step 1 写不变量，Step 4 修） |
| B2 regression + fix | Task 2（Step 1 写不变量，Step 3 修） |
| "压力与劳动配额无关"的不变量 | Task 1 Step 1 的 `everyPopulatedCohortAccumulatesStressRegardlessOfLaborQuota` |
| "实时危机指标使用一致时间基准"的不变量 | Task 2 Step 1 的 `crisisKindsAreInvariantAcrossCyclePhase` |
| 最小 population/crisis probe | Task 3 |
| 两个独立小提交 | Task 1 Step 8、Task 2 Step 7 |

**Placeholder scan**：无 TBD/TODO。★ 唯一需要实现者按现场填写的是 Task 2 Step 1 里
`EconomyTestWorld` 的**工厂方法名** —— 已在注释里写明"按该类现有 API 填，不许为迁就测试改它的公开 API"，
这不是 placeholder 而是**指向既有代码的指令**（本仓测试夹具的用法在 `PopulationR4Test` 有现成示例）。

**Type consistency**：`industriesAt(EconomyData, HexCoord)` 在 Task 1 的 Step 3 定义、Step 4 使用，签名一致；
`lightsAt(HexCoord, EconomyData, SocialData, long)` 在 Task 2 的测试与实现里同一签名。

**Review Focus coverage**：五条各有着落 —— ① 新生儿 → Task 1 Step 1 ②；② 0-14 → Task 1 Step 1 ①；
③ 无经济状态的格 → Task 1 Step 4 的第二道 `if`（空 targets 仍 `continue`）；
④ `progressDays == 0` → Task 2 Step 3 的 `Math.max(1L, …)`；⑤ 相位不变性 → Task 2 Step 1。
