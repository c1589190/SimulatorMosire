# 聚合式经济 v2 · 计划 2：播种时序 + 种子瓶颈（V3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让"种子"成为产能的**第三路瓶颈**，且它的优先性**来自时点**（播种日在当天吃饭之前扣种）而不是来自任何判断条件——于是"冬春把缸吃空 ⇒ 播种日扣不到种 ⇒ 投入面积缩 ⇒ 减产 ⇒ 下一年更缺"这条链条**不需要一个 if**，它就是时序加数字。

**Architecture:** 不动状态形状（除 `Industry` 加两个字段）、不动模块划分、不动编码器结构。四件事：① `Industry` 新增 `cycleInputPerUnit`（每单位生产资料**每周期一次性**投入，形状照 `dailyInputPerUnit`）与 `cycleSeedUsedMilli`（本周期实际扣到的种子累加器，形状照 `cycleLaborMilli`）；② `EconomyPayloads` 解析这两个字段（缺键 ⇒ 空 map / 0，旧载荷不拒）；③ `EconomySettlement.settleOneDay` 的**日流程重排**为 `播种 → 消费（settleHexes）→ 产业循环`，播种步逐 `ClassRow` 从**它自己的** `goods` 里扣种；④ `harvest` 的瓶颈从两路 `min` 变三路 `min`（`seedCapMu = cycleSeedUsedMilli / seedPerMu`，`seedPerMu == 0` 时**不加约束**）。

**Tech Stack:** Java 21、Maven（父 POM `io.mosire:simos-parent`）、JUnit 5 + AssertJ、Jackson（`databind` + `datatype-jdk8`）。

**Spec:** [`docs/superpowers/specs/2026-09-25-aggregate-economy-v2-design.md`](../specs/2026-09-25-aggregate-economy-v2-design.md)（本计划实现它的 **V3** 一行；执行者请同时读该 spec 的 §二、§3.2、§3.3、§八、§九、§10.3，以及 **§三 的三路瓶颈公式**）
**前序计划：** [`2026-09-25-aggregate-economy-v2-plan1.md`](2026-09-25-aggregate-economy-v2-plan1.md)（V1+V2，已关账）

★ **V3 的验收判据（spec §九 原文，逐字）**：`cycleInputPerUnit` 进 codec 往返；播种日先扣种子；**第三路瓶颈**（缺种子 ⇒ 投入面积缩 ⇒ 减产）；"冬春吃空缸 ⇒ 播种日扣不到 ⇒ 减产"逐值断言。

---

## ★ 先读这一段：骨架与三处偏离

控制器给的实现骨架**照此执行**，但有三处它没写到、而写实现时必然会撞上的决定。**这三处都在下面给了替代方案与理由，不是默默改**：

### 偏离 ①（补白，不是改）：**播种器（`EconomySeeder`）本计划不配种子** —— 真档的 `cycleInputPerUnit` 是空 map

- 骨架与 spec §九 V3 都没有说 `economy.Seed` 载荷里该写多少"每亩需种"。
- **全仓与全部文档里没有任何"每亩需种"的数**（`grep -rn "留种\|每亩需种\|seedPerMu\|cycleInput" docs/superpowers/ AGENT.md` 只命中"它是个参数"这类**归属**描述，一个数都没有）。spec §4.1/§4.7 明确把"每亩需种"列在**制度层 / 覆盖式参数**，归属 **V7 参数目录**。
- 本仓纪律（spec §十 口径表末行、`EconomySeeder` 的既定注释）是**没有依据就置 0，不臆造**。
- ⇒ **V3 的播种器写 `"cycleInputPerUnit":{}`（显式空 map，留出接缝）+ `"cycleSeedUsedMilli":0`**，并在注释里点名"V7 落地后由参数供给"。
- ★ **这同时是一条护栏**：plan-1 刚关账的端到端字面量（`EconomySettlementEndToEndTest` 的 176,545 粮/格、饿死 90 人、等价性……）**一个都不改**——它们就是"未配种子的产业行为与 V2 逐字一致"的端到端证据。任务 6 专门把这条留白**用一条用例钉住**，免得它被静默抹平。

### 偏离 ②（补白）：**播种扣减必须记进 `FlowRow.consumed`**

- 骨架只说了"扣库存、累加 `cycleSeedUsedMilli`"。但本仓有一条**已被端到端用例钉住的守恒式**（`EconomySettlement` 类注释 §6.1 / `EconomySettlementEndToEndTest.assertConserved`）：
  `Σ(推进前库存) − Σ(推进后库存) == Σ(流水消费) − Σ(流水所得)`。
- 只扣库存而不入账 ⇒ 这条等式在**配了种子的世界**上立刻不成立 ⇒ 那条守恒用例会变成**假绿**（它跑的是未配种子的夹具）。
- ⇒ 播种把扣掉的量并入当日 `consumedGrain` 累加器（与该行当日口粮同一张表）。**这也是 spec §二 把"留种的计量"列在"数"里的落地**：留种要看得见，不是从库里凭空消失。
- ★ 判别力：任务 5 有一条守恒断言；把 `consumedGrain.merge(...)` 那行删掉 ⇒ 那条当场红。

### 偏离 ③（骨架里的 `if (常量)` 会造出测不到的死分支）：**次序预设用"包内可见的重载"，不用 `static final boolean` 当开关**

- 骨架要求：`plantingDrawsBeforeConsumption` 先用常量（默认 `true`），V7 再迁入参数表。
- 但若写成 `static final boolean PLANTING_DRAWS_BEFORE_CONSUMPTION = true;` + `if (常量) {...} else {...}`，**取 false 的那一支在编译期就是死代码，任何用例都到不了它** ⇒ 它就是 spec §3.2 要防的那种"看起来在、其实永远走不到"的假预设（本仓最忌的那一族）；而 spec §3.2 明写"参数化**不许改变流程形状**：无论取真取假，播种日这个**步骤**都在"。
- ⇒ 定案：**次序作为 `settleOneDay` 的一个参数**，公开入口用常量当默认值喂它：
  ```java
  public static final boolean PLANTING_DRAWS_BEFORE_CONSUMPTION = true;   // ★ V7 迁入参数表
  private static EconomyData settleOneDay(EconomyData base, long day, LinkedHashMap<ClassKey, FlowRow> flows) {
    return settleOneDay(base, day, flows, PLANTING_DRAWS_BEFORE_CONSUMPTION);
  }
  static EconomyData settleOneDay(          // ← 包内可见：只为让"另一种次序"能被逐值测到
      EconomyData base, long day, LinkedHashMap<ClassKey, FlowRow> flows, boolean plantingDrawsFirst) { ... }
  ```
  仓库先例：`EconomySettlement.allocate` 就是**包内可见的纯函数**，专为直测而放宽（见 `EconomySettlementTest.allocateDistributesResidueInSlotOrderKeepingTheTotal`）。
- 于是"取 false = 吃饭优先、种子看运气"**真的跑得到、且被逐值断言**（任务 3 的
  `drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown`：两种次序给出 20,000 与 0 两个不同的种子量）。
- ★ 若门禁（Checkstyle/SpotBugs）对"包内可见的静态方法"有意见，**不许**退回常量分支；改为把该重载标注 `@VisibleForTesting` 风格的中文注释（本仓既有做法：`EconomySeeder.payload(...)` 也是包内可见 + 注释说明"用例塞替身即可"）。

**其余照骨架，一字不改**：常量名 `plantingDrawsBeforeConsumption` 的中文口径、`sowIfCycleStart` 的位置与算法、`seedCapMu` 的三元式、六种 `AssetKind` 都允许而 v1 只种 `LAND`、周期关账清零。

★ **本计划不做（留白，有理由，不是遗漏）**：
1. **读口**：`ApiViews.economyHex` / GUI / MCP **不暴露** `cycleInputPerUnit` / `cycleSeedUsedMilli`。理由：V3 判据不含读口；而 spec §八.8「读数必须与结算同源」尚未做（V5）⇒ 现在加读口会先造一个半截的读数面（面板上有个数，结算与它对不上）。V5 落地读数同源时一并暴露。
2. **分配按"实际播种面积"而不是"土地占比"**：本计划结束时，干缸行的地虽然荒着，它**仍按土地权重分到一份产出**（`harvest` 的 `weights` 用 `rowLand/totalLand`）。理由：spec §3.1 的 `plantedMu` 是**产业级**单一数字，没有逐行播种归属这个概念；引入它 = 另立设计。**记入"已知约束"**（任务 6 末尾），留给 V5/V6 的评审决定。
3. **`dailyInputPerUnit` 的日原料扣减**：与 V2 一样仍是零读取点（spec §3.3 明说两个字段并存、语义各自清楚）。

---

## Global Constraints

以下每一条都取自 spec 或 `AGENT.md`，**每个任务的要求都隐含包含本节**：

- **量纲（spec §七 + §3.3）**：人口「人」；劳动「**千分劳动**」；土地「**千分亩**」（`ClassRow.meansOfProduction`）；粮「**毫粮**」（1 粮 = 1000 毫粮）；权重 / 利率 / 投入率一律「**千分数**」。
  ★★ **本计划新增的两个量纲，不许混**：`cycleInputPerUnit` 的 `LAND` 键值是「**毫粮/亩**」（与 `outputPerUnit` 的「粮/亩」、`harvest` 里按**亩**算的口径同侧）；而 `meansOfProduction` 的 `LAND` 是「**千分亩**」。故播种步里**必须先 `/ 1000` 换成亩**再乘。全部 `long`。
- **禁用浮点**：`double` / `float` **不得参与**钱、粮、人口（spec §七）。本计划全程整数乘除。
- **保序不可变**：一切容器用 `LinkedHashMap` / `ArrayList` 复制后 `Collections.unmodifiableMap/List` **冻在字段赋值处**；**绝不用 `Map.copyOf`**（它的迭代序不是内容的纯函数，字节级往返因此不成立）。夹具里的多行/多产业表同理用 `LinkedHashMap`。
- **注释与文档用中文**，与既有风格一致；行宽由 google-java-format（Spotless）决定，**不要手工断行**。
- **模块边界由 enforcer 强制**：`simos-economy` 不得依赖 `simos-social/unit/sd/core/app/agentlib-mosire/ledger`；`simos-util` 只依赖 Jackson + SLF4J。
- **一次只能跑一个 Maven**：跑前 `pgrep -af "surefirebooter|classworlds.launcher"`，有命中就等。
- **迭代命令**：`./mvnw -q -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false test -pl <模块> -am`
  ★ `-q` 会吞掉 surefire 汇总 ⇒ **判过不过要看 `target/surefire-reports/*.txt` 的 mtime 落在本轮**，不看 `rc=0`。
- **关账命令**：`./mvnw clean verify`，**必须前台**（后台跑会被内存守卫杀，而"被杀"既不是红也不是绿）。
- **提交纪律**：**不 `git add -A`**；按批次提交；提交信息中文，写清「**改了什么 + 为什么 + 验收的实际数字 + 未验的部分**」。
- **服务在跑时**验代码用 `./mvnw test`（`test` 阶段不经 `package`/`shade`，不动 jar）。
- ★ **夹具若违反新的构造期不变量，要修夹具，不许放宽护栏**（plan-1 任务 2/3 的先例）。本计划特有的形态：`new Industry(...)` 是**位置参数**，加两个组件会让**全仓 9 个构造点**一起编译不过 —— 那是**预期的红**，逐个按语义补参，**不许**为了少改几处而给新组件加 setter/重载。
- ★ **测试期望值优先写成由常量推出的表达式**（plan-1 的教训）：凡涉及 `LAND_MU_PER_LABOR` / `PRODUCTION_CONSUMPTION_PER_MILLE` / `DAILY_GRAIN_MILLI_PER_PERSON` / `FAMINE_MORTALITY_PER_MILLE` 的期望值，一律引用 `EconomySettlement` 的常量做算式，并在注释里把算式**复述一遍**；只有"夹具自己的字面量"（人口、亩数、种子率、亩产）才写数字。
- ★ **既有的 `public static final` 常量一个都不删**（`DAILY_GRAIN_MILLI_PER_PERSON` / `MILLI_PER_GRAIN` / `GRAIN` / `LAND_MU_PER_LABOR` / `PRODUCTION_CONSUMPTION_PER_MILLE` / `FAMINE_MORTALITY_PER_MILLE`）：既有测试与 e2e 引用它们。

---

## Review Focus

下面十二条输入 / 条件，是 spec 隐含要求、但**本计划任何任务的测试如果不专门写就不会覆盖**的，也正是最可能咬人的。每一条都已在它所属的任务里配了测试（括号内为该任务号）：

1. **未配种子的产业 / 旧档**（`cycleInputPerUnit` 空 map）：播种日**一字不扣**，收获与 V2 **逐值相同**——绝不许被第三路瓶颈打成一粒无收。（任务 3、4、6）
2. **`seedPerMu == 0` 但键在**（`{LAND: 0}`）：同上**不加约束**。★ 这是用户点名的风险点：写成 `seedCapMu = cycleSeedUsedMilli / seedPerMu` 会除零、写成 `? 0 :` 会让旧档颗粒无收。（任务 4）
3. **播种日缸是空的**（`库存 == 0`）：扣 0、**不抛**、`seedCapMu == 0` ⇒ 该产业这周期**颗粒无收**（不是 V2 的土地瓶颈满产）——这正是"冬春吃空缸 ⇒ 减产"。（任务 3、4、5）
4. **库存恰好 == `need`**：扣光后为 0，当天口粮全缺 ⇒ 走缺口/借粮路径。**边界不许写成 `<`**。（任务 3）
5. **行没有地**（`rowLandMu == 0`，真档里每座城的手工业行都是）：`need == 0` ⇒ 不扣、不累加、不进 `seedCap`。（任务 3）
6. **六种 `AssetKind` 键都允许，v1 只读 `LAND`**：配 `CATTLE/TOOL/WORKSHOP/MACHINE/SHIP` 的改变**不动任何数**（spec §3.1"声明但不启用"）。（任务 1、4）
7. **两行各扣各的**：贫农干缸 ⇒ **它的地荒着**（不进 `seedCap`），地主缸足 ⇒ 它的地照种。不许改成"从全格池子扣"。（任务 5，含变异自证）
8. **种子累加器按周期清零**：不清零 ⇒ 第 2 周期的 `seedCapMu` 会带上第 1 周期的种子 ⇒ **凭空多出可种面积**。（任务 3）
9. **`cycleDays == 1`**：第 1 天既是播种日又是收获日 ⇒ **收获必须读到当天刚播的数**（若收获读的是播种前的 `Industry` 快照，`seedCapMu` 会恒为 0）。（任务 3 的 `aOneDayCycleSowsAndHarvestsOnTheSameDay`；它跨任务 3/4 都必须绿——任务 4 之后收获才开始读种子累加器）
10. **两种次序都要真的跑到**：`plantingDrawsFirst=false`（吃饭优先）必须可达且被逐值断言——否则它是假预设（见"偏离 ③"）。（任务 3）
11. **账要平**：种子扣减进 `consumed` ⇒ `库存减少 == 消费 − 所得` 在**配了种子**的世界上仍成立。（任务 5）
12. **一次 N 天 == N 次单日**（spec §十一 的等价性）：在种子口径下仍成立——播种**恰发生一次**，不会"每推一次就扣一遍"。（任务 3）

---

### Task 1: `Industry` 新增 `cycleInputPerUnit` 与 `cycleSeedUsedMilli`（形状 + 构造期守卫 + 全仓 9 个构造点）

**Files:**
- Modify: `simos-economy/src/main/java/io/mosire/simos/economy/model/Industry.java`
- Modify: `simos-economy/src/test/java/io/mosire/simos/economy/model/EconomyInvariantsTest.java`
- Modify（**只补两个实参**，见 Step 4）：`simos-economy/.../spi/EconomyPayloads.java`、`simos-economy/.../time/EconomySettlement.java`、`simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java`、`simos-economy/src/test/java/io/mosire/simos/economy/codec/EconomyCodecTest.java`（2 处）、`simos-economy/src/test/java/io/mosire/simos/economy/time/EconomyCycleBoundaryTest.java`（3 处）、`simos-economy/src/test/java/io/mosire/simos/economy/time/EconomySettlementTest.java`（2 处）、`simos-app/src/test/java/io/mosire/simos/app/world/EconomyTestWorld.java`、`simos-app/src/test/java/io/mosire/simos/app/gui/GuiApiTest.java`

**Interfaces:**
- Consumes: `AssetKind`（六种键）、`CommodityId`（`outputPerUnit` 的键）
- Produces（`Industry` 的**两个新组件**，位置在组件表的第 9 与第 13 位）：
  ```java
  public record Industry(
      IndustryId id,
      String name,
      RegimeId regime,
      long cycleDays,
      long progressDays,
      Map<AssetKind, Long> dailyInputPerUnit,
      long dailyLaborPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      Map<AssetKind, Long> cycleInputPerUnit,   // ★ 新增（第 9 位：紧跟 outputPerUnit，四张"每单位"表聚在一起）
      List<ClassSlot> slots,
      AllocationRule allocation,
      long cycleLaborMilli,
      long cycleSeedUsedMilli) { ... }          // ★ 新增（末位：两个"本周期累加器"相邻）
  ```
  ★ 位置是**刻意的**：第 9 位与 `outputPerUnit` 相邻（都是"每单位生产资料的参数表"），末位与 `cycleLaborMilli` 相邻（都是"周期关账后清零的累加器"）。9 个构造点按此位置补参，不许用重载/setter 绕开。

★ **病灶（spec §3.3）**：v1 只有 `dailyInputPerUnit`（**每日**原料需求），**语义不适配种子**——种子是**播种日一次性**扣完，不是每日。硬塞进 `dailyInputPerUnit` 会让"每天扣一遍种子"变成一个没人会在代码里读到、却能算出来的错账。
★ **`cycleSeedUsedMilli` 为什么必须住在 `Industry` 里**：它是**产业**在本周期实际扣到的种子总量（收获日要用它算 `seedCapMu`），不是某一行的量；且它必须随 revision 落盘（分岔管理：两条分支各自带自己的播种账），故不能是结算函数的局部变量。

- [ ] **Step 1: 写失败测试（新字段不存在 ⇒ 编译不过，即"红"）**

在 `EconomyInvariantsTest.java` 追加（沿用该文件既有的 `assertThatThrownBy` 风格；helper 见 Step 1 末）：
```java
  // ── 一次性投入槽与种子累加器（v2 spec §3.3）────────────────────────────────────────

  /** ★ 空 map 的语义是"不用空 map"，null 是坏数据 ⇒ 构造期拒（与 dailyInputPerUnit 同制）。 */
  @Test
  void rejectsNullCycleInputMap() {
    assertThatThrownBy(() -> industryWithCycleInput(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycleInputPerUnit");
  }

  /** ★ 逐值 ≥ 0（§6.4 存量非负的下界）。 */
  @Test
  void rejectsNegativeCycleInputQuantity() {
    assertThatThrownBy(() -> industryWithCycleInput(Map.of(AssetKind.LAND, -1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycleInputPerUnit");
  }

  /** ★★ **六种键都允许**（spec §3.3：协议上不设限；v1 只让 LAND 真的被读到）。 */
  @Test
  void acceptsCycleInputForAllSixAssetKinds() {
    Industry industry =
        industryWithCycleInput(
            Map.of(
                AssetKind.LAND, 100L,
                AssetKind.CATTLE, 1L,
                AssetKind.TOOL, 2L,
                AssetKind.WORKSHOP, 3L,
                AssetKind.MACHINE, 4L,
                AssetKind.SHIP, 5L));

    assertThat(industry.cycleInputPerUnit())
        .as("形状不设限（v1 读不读是结算的事）")
        .containsOnlyKeys(
            AssetKind.LAND,
            AssetKind.CATTLE,
            AssetKind.TOOL,
            AssetKind.WORKSHOP,
            AssetKind.MACHINE,
            AssetKind.SHIP);
  }

  /** ★ 保序不可变（冻在字段赋值处；绝不用 Map.copyOf —— 迭代序不是内容的纯函数）。 */
  @Test
  void cycleInputKeepsInsertionOrderAndIsFrozen() {
    Map<AssetKind, Long> input = new LinkedHashMap<>();
    input.put(AssetKind.SHIP, 5L);
    input.put(AssetKind.LAND, 100L);
    Industry industry = industryWithCycleInput(input);

    assertThat(industry.cycleInputPerUnit().keySet())
        .as("插入序即迭代序（字节级往返的前提）")
        .containsExactly(AssetKind.SHIP, AssetKind.LAND);
    assertThatThrownBy(() -> industry.cycleInputPerUnit().clear())
        .as("冻在字段赋值处")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★ `cycleSeedUsedMilli` 形制同 `cycleLaborMilli`：不得为负。 */
  @Test
  void rejectsNegativeCycleSeedAccumulator() {
    assertThatThrownBy(() -> industryWithCycleSeedUsed(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycleSeedUsedMilli");
  }

  /** 一个产业的槽位上限 1000‰、`cycleInputPerUnit = cycleInput`、`cycleSeedUsedMilli = 0`。 */
  private static Industry industryWithCycleInput(Map<AssetKind, Long> cycleInput) {
    return industryWithCycleState(cycleInput, 0L);
  }

  private static Industry industryWithCycleSeedUsed(long cycleSeedUsedMilli) {
    return industryWithCycleState(Map.of(), cycleSeedUsedMilli);
  }

  private static Industry industryWithCycleState(
      Map<AssetKind, Long> cycleInput, long cycleSeedUsedMilli) {
    return new Industry(
        FARM,
        "农业",
        new RegimeId("tenant"),
        120L,
        0L,
        Map.of(),
        500L,
        Map.of(GRAIN, 7L),
        cycleInput,
        List.of(new ClassSlot(PEASANT, "贫农", 1000)),
        new AllocationRule.Split(700, 300),
        0L,
        cycleSeedUsedMilli);
  }
```
（`LinkedHashMap` / `LinkedHashSet` 若未 import，一并补上。）

- [ ] **Step 2: 跑测试确认它红**

Run: `./mvnw -q -Dtest=EconomyInvariantsTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: **编译错误**（`找不到符号: 方法 cycleInputPerUnit()` / `构造器 Industry 无法应用于给定类型`）。这是本步要的"红"。
★ 此刻**全仓 9 个 `new Industry(` 构造点一起编译不过** —— 这是预期，不要试图只修一部分。

- [ ] **Step 3: 建字段与守卫**

`Industry.java`：record 组件表按 Task 1 的 **Interfaces** 改（`cycleInputPerUnit` 插在 `outputPerUnit` 之后、`cycleSeedUsedMilli` 追加在末位）；compact constructor 里紧接 `outputPerUnit` 那一段之后加：
```java
    if (cycleInputPerUnit == null) {
      throw new IllegalArgumentException("Industry.cycleInputPerUnit 不得为 null（无一次投入用空 map）");
    }
    Map<AssetKind, Long> cycleInputCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : cycleInputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Industry.cycleInputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "Industry.cycleInputPerUnit 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      cycleInputCopy.put(entry.getKey(), entry.getValue());
    }
    cycleInputPerUnit = Collections.unmodifiableMap(cycleInputCopy); // ★ 冻在字段赋值处
```
并把 `cycleSeedUsedMilli < 0` 的守卫加在 `cycleLaborMilli < 0` 那条旁边：
```java
    if (cycleSeedUsedMilli < 0) {
      throw new IllegalArgumentException("Industry.cycleSeedUsedMilli 不得为负: " + cycleSeedUsedMilli);
    }
```
javadoc 补两条 `@param`，**必须写进注释的三件事**（后来人最容易混的三点）：
```java
 * @param cycleInputPerUnit 每单位生产资料**每周期一次性**投入（v2 spec §3.3）。**量纲**：{@code LAND} 的键值是
 *     **毫粮/亩**（与 {@code outputPerUnit} 的「粮/亩」、结算里按**亩**算的口径同侧）；★ 别与
 *     {@code ClassRow.meansOfProduction} 的 {@code LAND}（**千分亩**）混——播种步里要先 {@code / 1000} 换成亩。
 *     六种 {@link AssetKind} 键都允许（协议不设限），但 **v1 的结算只读 {@code LAND}**（= 每亩需种）；
 *     其余五种是"声明但不启用"，与 {@code dailyInputPerUnit} 同状态。键值非空、逐值 ≥ 0。
 * @param cycleSeedUsedMilli 本周期**实际扣到的种子**（毫粮）累加器，形制同 {@link #cycleLaborMilli()}：
 *     播种日（{@code progressDays == 0}）逐行累加、周期关账后清零。不得为负。
 *     ★ 它同时是"留种的计量"（v2 spec §二 把留种列在**数**里）：收获日用
 *     {@code cycleSeedUsedMilli / cycleInputPerUnit[LAND]} 得**种子能支撑的亩数**，构成第三路瓶颈。
```

- [ ] **Step 4: 逐个构造点补参（9 处，按语义选值）**

| 文件 | 传什么 | 为什么 |
|---|---|---|
| `simos-economy/.../spi/EconomyPayloads.java` | `Map.of()`、`0L`（**本任务只补参，不解析**） | 解析是任务 2；本任务先让编译绿 |
| `simos-economy/.../time/EconomySettlement.java` 的 `withProgressAndLabor` | 透传 `industry.cycleInputPerUnit()` 与 `industry.cycleSeedUsedMilli()` | 结算换进度时**不许**把这两个字段丢了（丢了 = 静默清零） |
| `EconomyRoundTripTest.industry(...)` | `Map.of(AssetKind.CATTLE, 1L)` 作 `cycleInputPerUnit`、`0L` | ★ **必须非空**：空 map 与"字段没进变更集"在值层面不可区分 |
| `EconomyCodecTest.industry(...)` 与 `workshopIndustry()` | `Map.of(AssetKind.LAND, 1200L)` 与 `Map.of()`、`0L` | 同上；`workshopIndustry` 是 `WageFirst` 多态夹具，用空 map 即可 |
| `EconomyCycleBoundaryTest`（3 处）、`EconomySettlementTest`（2 处） | `Map.of()`、`0L`（**一个都不配种子**） | ★ 这两个文件里的字面量是 V2 口径算好的；**配种子会一次打红十几条断言**，且那不是本任务要测的东西 |
| `simos-app/.../world/EconomyTestWorld.industry(...)` | `Map.of()`、`0L` | ★★ **必须保持空**：它服务的 5 格端到端夹具是"未配种子 ⇒ V2 行为不变"的**证据**（见"偏离 ①"） |
| `simos-app/.../gui/GuiApiTest`（`:531`） | `Map.of()`、`0L` | 同 GUI 读口用例的字面量 |

★ **`EconomyCodecTest` 除了补参，还要在 `deltaValuesSurviveAsIndustryWithCommodityKeys` 里加一条断言**
（这是 spec §九 V3 判据 1「`cycleInputPerUnit` 进 codec 往返」的落点）：
```java
    assertThat(farm.cycleInputPerUnit())
        .as("★ 新字段必须真的过线：空 map 与「字段没进线格式」在值层面不可区分")
        .containsEntry(AssetKind.LAND, 1200L);
```
（`industry(...)` 夹具已按上表把 `cycleInputPerUnit` 置为 `{LAND:1200}`。）
★ `encodingIsByteLevelStableForEconomyData` 与 `snapshotRoundTripsWithLabeledTimestamp` **不需要改** ——
它们跟着夹具走，新字段一旦进线格式就被自动覆盖（这也是"字段加进夹具"比"另写一条往返"更省的地方）。

- [ ] **Step 5: 跑测试确认全绿**

Run: `./mvnw -q test -pl simos-economy,simos-app -am`
Expected: 全绿。**核对两个模块的 `target/surefire-reports/*.txt` mtime 落在本轮**。

- [ ] **Step 6: 变异自证（证明 Step 3 的守卫有判别力）**

把 `cycleInputPerUnit == null` 那条守卫**整段删掉** ⇒ 重跑 `EconomyInvariantsTest` ⇒ **必须红**，红的理由是 `rejectsNullCycleInputMap` 没拿到 `IllegalArgumentException`（若 Jackson 路径掩盖了它，本条只会红在这一条上，那也够了——它证明守卫是唯一的拦路者）。
★ 验完**按字节还原**（用 `git checkout -- <文件>` 或重新 Edit；`cp` 备份会让 mtime 变新），重跑确认回绿。
（`cycleSeedUsedMilli < 0` 的变异自证同理：改成 `if (cycleSeedUsedMilli < -1)` ⇒ `rejectsNegativeCycleSeedAccumulator` 红 ⇒ 还原。）

- [ ] **Step 7: 提交**

```bash
git add simos-economy/src/main/java/io/mosire/simos/economy/model/Industry.java \
        simos-economy/src/test/java/io/mosire/simos/economy/model/EconomyInvariantsTest.java \
        simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java \
        simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java \
        simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java \
        simos-economy/src/test/java/io/mosire/simos/economy/codec/EconomyCodecTest.java \
        simos-economy/src/test/java/io/mosire/simos/economy/time/EconomyCycleBoundaryTest.java \
        simos-economy/src/test/java/io/mosire/simos/economy/time/EconomySettlementTest.java \
        simos-app/src/test/java/io/mosire/simos/app/world/EconomyTestWorld.java \
        simos-app/src/test/java/io/mosire/simos/app/gui/GuiApiTest.java
git commit -m "feat(economy): Industry 加 cycleInputPerUnit 与 cycleSeedUsedMilli（v2 spec §3.3）+ 构造期守卫"
```
提交信息须含：两个字段的量纲（毫粮/亩 vs 千分亩）、六种键都允许而 v1 只读 LAND、**未验的部分**（"结算还没有读它们，第三路瓶颈在任务 4"）。

---

### Task 2: `EconomyPayloads` 解析这两个字段（缺键 ⇒ 空 map / 0）+ 载荷文档 + `CatalogTool`

**Files:**
- Modify: `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java`
- Modify: `simos-economy/src/test/java/io/mosire/simos/economy/spi/EconomySeedHandlerTest.java`
- Modify: `simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java`

**Interfaces:**
- Consumes: `Industry` 的两个新组件（任务 1）、既有的 `assetMap(JsonNode, String)` / `optionalObject(JsonNode, String)` / `optionalLong(JsonNode, String, long)` 三个形状助手
- Produces: 载荷键 `cycleInputPerUnit`（对象，键 = `AssetKind` 名，值 = 整数）与 `cycleSeedUsedMilli`（整数，缺省 0）

★ **口径**：**缺键 = 空 / 0，不拒**（旧载荷与旧生成器继续可播）。这与 `optionalObject`→`assetMap`、`optionalLong(...,0)` 的既有行为一致：**命令路径天然宽容**，而 `Industry` 的 null 守卫只拦"编解码器给出 null"那条路（那条路该响）。

- [ ] **Step 1: 写失败测试**

在 `EconomySeedHandlerTest.java` 追加（**不动**现有的 `PAYLOAD` 常量 —— 见 Step 1 末）：
```java
  /** ★★ 缺键 ⇒ 空 map / 0（旧载荷兼容：命令路径不许因为多了一个字段就把老生成器挡在门外）。 */
  @Test
  void legacyPayloadWithoutCycleInputStillSeedsEmptyAndZero() {
    EconomyData after = apply(PAYLOAD, EconomyData.empty(), T7);

    Industry industry = after.industries().get(FARM);
    assertThat(industry.cycleInputPerUnit())
        .as("旧载荷没提一次性投入 ⇒ 空 map（不是 null、不拒）")
        .isEmpty();
    assertThat(industry.cycleSeedUsedMilli()).as("旧载荷没提种子累加器 ⇒ 0").isZero();
  }

  /** ★★ 两个新字段**逐值**过载荷：六种键都收、累加器读到。 */
  @Test
  void parsesCycleInputForAllKindsAndTheSeedAccumulator() {
    String payload =
        PAYLOAD.replace(
            "\"dailyInputPerUnit\":{}",
            "\"dailyInputPerUnit\":{},\"cycleInputPerUnit\":{\"LAND\":1200,\"CATTLE\":1},"
                + "\"cycleSeedUsedMilli\":5000");
    assertThat(payload).as("替换必须真的发生（否则本用例测的是缺键那条路）").isNotEqualTo(PAYLOAD);

    Industry industry = apply(payload, EconomyData.empty(), T7).industries().get(FARM);

    assertThat(industry.cycleInputPerUnit())
        .as("每亩需种 1200 毫粮/亩，另带一种 v1 不读的键")
        .containsExactly(entry(AssetKind.LAND, 1200L), entry(AssetKind.CATTLE, 1L));
    assertThat(industry.cycleSeedUsedMilli()).isEqualTo(5_000L);
  }

  /** ★ 逐值校验：负的一次性投入 ⇒ 拒（`Industry` 的构造期守卫，经 handler 的 catch 折成 Rejected）。 */
  @Test
  void rejectsNegativeCycleInput() {
    String payload =
        PAYLOAD.replace(
            "\"dailyInputPerUnit\":{}", "\"dailyInputPerUnit\":{},\"cycleInputPerUnit\":{\"LAND\":-1}");

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("cycleInputPerUnit");
  }
```
（`entry(...)` 是 AssertJ 的 `org.assertj.core.api.Assertions.entry`，与 `assertThat(Map).containsExactly(entry(...))` 同用；若与既有 import 冲突，改用 `containsEntry` 两条。）
★ **为什么不动 `PAYLOAD`**：它**没有**这两个键，正是"缺键 ⇒ 空/0"的**活体守卫**（上面第一条用例就是它的断言）。给 `PAYLOAD` 补上新键会把这条覆盖一次性抹掉。

- [ ] **Step 2: 跑测试确认它红**

Run: `./mvnw -q -Dtest=EconomySeedHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: `parsesCycleInputForAllKindsAndTheSeedAccumulator` **红**（期望 `LAND=1200`，实际空 map）—— 这是**值红**（不是编译红），恰好证明"字段存在但没人解析 = 假字段"这件事会被抓住。另两条绿。

- [ ] **Step 3: 实现解析**

`EconomyPayloads.industry(...)` 里，在 `output` 那两行之后加：
```java
    // ★ v2 spec §3.3：每单位生产资料**每周期一次性**投入（v1 只有 LAND = 每亩需种，单位毫粮/亩）。
    //   缺键 ⇒ 空 map（旧载荷兼容；六种 AssetKind 都收，v1 只读 LAND）。
    Map<AssetKind, Long> cycleInput =
        assetMap(optionalObject(node, "cycleInputPerUnit"), "cycleInputPerUnit");
    // ★ 本周期实际扣到的种子（毫粮）累加器；缺键 ⇒ 0（旧载荷兼容）。负值由 Industry 的构造期守卫拒。
    long cycleSeedUsed = optionalLong(node, "cycleSeedUsedMilli", 0L);
```
并按 Task 1 的位置顺序把它俩传进 `new Industry(...)`（`cycleInput` 在 `output` 之后、`cycleSeedUsed` 在 `cycleLabor` 之后）。
类 javadoc 的载荷样例里补上这两个键（样例当前在 `"dailyLaborPerUnit":0,` 之后接 `"outputPerUnit":{"grain":67},` ⇒ 改成 `"outputPerUnit":{"grain":67},"cycleInputPerUnit":{"LAND":1200},"cycleSeedUsedMilli":0,`）。

- [ ] **Step 4: 跑测试确认三条都绿**

Run: `./mvnw -q -Dtest=EconomySeedHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: PASS（**核对 `simos-economy/target/surefire-reports/*.txt` mtime 落在本轮**）。

- [ ] **Step 5: 载荷形态的文档面（`CatalogTool`）**

`simos-app/.../tools/read/CatalogTool.java` 的 `"economy.Seed"` 那一行里，把
`"dailyInputPerUnit?,dailyLaborPerUnit?,outputPerUnit?,allocation(@class=split|wage_first),"` 改成
`"dailyInputPerUnit?,dailyLaborPerUnit?,outputPerUnit?,cycleInputPerUnit?(键=生产资料种类，v1 只读 LAND),"
+ "cycleSeedUsedMilli?,allocation(@class=split|wage_first),"`。
★ 这条只改**文档字符串**，不改任何行为；但它是 MCP 工具面的自述，**声明了却读不到**正是本仓最忌的"假参数"——所以字段进了载荷就必须进这条。

- [ ] **Step 6: 变异自证**

把 `cycleInput` 那两行改成 `Map<AssetKind, Long> cycleInput = Map.of();`（模拟"字段声明了但没人解析"）⇒ 重跑 ⇒ **必须红**，红的理由是 `parsesCycleInputForAllKindsAndTheSeedAccumulator` 的 `containsExactly` 收到空 map。按字节还原 ⇒ 回绿。

- [ ] **Step 7: 提交**

```bash
git add simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java \
        simos-economy/src/test/java/io/mosire/simos/economy/spi/EconomySeedHandlerTest.java \
        simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java
git commit -m "feat(economy,app): economy.Seed 载荷解析 cycleInputPerUnit / cycleSeedUsedMilli（缺键 ⇒ 空 / 0）"
```

---

### Task 3: 播种步 `sowIfCycleStart` + `settleOneDay` 流程重排（**播种 → 消费 → 产业循环**）

**Files:**
- Modify: `simos-economy/.../time/EconomySettlement.java`
- Create: `simos-economy/src/test/java/io/mosire/simos/economy/time/EconomySowingTest.java`

**Interfaces:**
- Consumes: `Industry.cycleInputPerUnit()` / `cycleSeedUsedMilli()`（任务 1）、`grainOf` / `withGoodsGrain` / `classKeysOf`（既有私有助手）
- Produces:
  - `public static final boolean EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION = true;`
  - `static EconomyData EconomySettlement.settleOneDay(EconomyData base, long day, LinkedHashMap<ClassKey, FlowRow> flows, boolean plantingDrawsFirst)` —— **包内可见**（供"另一种次序"逐值直测；见"偏离 ③"）
  - `private static void EconomySettlement.sowIfCycleStart(LinkedHashMap<IndustryId, Industry> industries, LinkedHashMap<ClassKey, ClassRow> rows, LinkedHashMap<ClassKey, Long> consumedGrain)`
  - `private static Industry EconomySettlement.withCycleState(Industry industry, long progress, long cycleLabor, long cycleSeedUsed)`（**取代** `withProgressAndLabor`）

★ **为什么必须重排**：种子粮与口粮是**同一个商品**（spec §3.2：不设 `seed`）。于是"播种优先"这件事**只能靠时点表达**——若当天先吃饭，缸里的种子当天就被吃光，"缺粮格永远播不了种、产量永久归零"（spec §3.2 的原话）。重排后：`播种 → settleHexes（消费 + 同格借粮）→ 产业循环（进度/劳动/收获/分配/饿死）`。
★ **顺带出现的、正确的副作用**：播种把库存划走后，`settleHexes` 看到的是**扣完种子之后**的库存 ⇒ 当天口粮不够就该走借粮/缺口的那条既有路径，**不许**为种子另开一条"保护额度"。

- [ ] **Step 1: 写失败测试（新测试类，夹具在下面四步里被复用）**

Create `simos-economy/src/test/java/io/mosire/simos/economy/time/EconomySowingTest.java`:
```java
package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ **V3：播种时序 + 种子瓶颈**（v2 spec §3.2/§3.3，验收判据逐条落在下面）。
 *
 * <p>夹具（除非某条用例另有说明）：一格、一个农业产业、**一行贫农**；周期 {@value #CYCLE_DAYS} 天；
 * 人口 {@value #POPULATION}（有效劳动 232,000 千分劳动、投入率 1000‰）；地 {@code 400 亩}；
 * 每亩需种 {@value #SEED_PER_MU} 毫粮；亩产 {@value #YIELD_PER_MU} 粮/亩。
 *
 * <pre>
 * 劳动可经营亩 = 400 × 580 × {@link EconomySettlement#LAND_MU_PER_LABOR}(7) / 1000 = 1,624 亩  &gt; 400 亩
 * ⇒ **土地**是 V2 的基线瓶颈（故种子一缩面积就看得见，不会被劳动瓶颈掩盖）
 * 满种的种子量 = 400 亩 × 100 毫粮/亩                     = 40,000 毫粮
 * 一日口粮     = 400 × {@link EconomySettlement#DAILY_GRAIN_MILLI_PER_PERSON}(83) = 33,200 毫粮
 * 满产地净产   = 400 × 67 粮/亩 × 1000 × (1 − 15%)        = 22,780,000 毫粮
 * </pre>
 */
class EconomySowingTest {

  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final IndustryId CRAFT = new IndustryId("craft@0_0");
  private static final ClassSlotId PEASANT = new ClassSlotId("peasant");
  private static final ClassSlotId LANDLORD = new ClassSlotId("landlord");
  private static final ClassKey PEASANT_KEY = new ClassKey(FARM, PEASANT);
  private static final ClassKey LANDLORD_KEY = new ClassKey(FARM, LANDLORD);
  private static final CommodityId GRAIN = new CommodityId("grain");

  /** 周期（天）：一天播种、一天收获 —— 算账最短，且第 2 天就能看见累加器清零。 */
  private static final long CYCLE_DAYS = 2L;
  /** 每亩需种（毫粮/亩）= 0.1 粮/亩。 */
  private static final long SEED_PER_MU = 100L;
  /** 亩产（粮/亩）：与 v2 spec §10.3 定案 A 同值。 */
  private static final long YIELD_PER_MU = 67L;
  /** 一个人的有效劳动（千分劳动）：与 §十"D4 默认"同（400 人 ⇒ 232,000）。 */
  private static final long LABOR_PER_PERSON = 580L;
  private static final long POPULATION = 400L;
  /** 一行的地（千分亩）：400,000 千分亩 = 400 亩。 */
  private static final long LAND_MILLI_MU = 400_000L;

  private static final long DAILY_NEED =
      POPULATION * EconomySettlement.DAILY_GRAIN_MILLI_PER_PERSON; // 33,200
  private static final long FULL_SEED = (LAND_MILLI_MU / 1000L) * SEED_PER_MU; // 40,000
  private static final long FULL_HARVEST_NET =
      (LAND_MILLI_MU / 1000L)
          * YIELD_PER_MU
          * EconomySettlement.MILLI_PER_GRAIN
          * (1000L - EconomySettlement.PRODUCTION_CONSUMPTION_PER_MILLE)
          / 1000L; // 22,780,000

  /** 一行的贫农（人口 400 / 投入率 1000‰ / 地 {@link #LAND_MILLI_MU} 千分亩 / 缸 {@code stock} 毫粮）。 */
  private static ClassRow peasantRow(long stock) {
    return peasantRow(stock, LAND_MILLI_MU);
  }

  /** 同上，但地由调用方给（两行夹具里贫农只占 200 亩）。 */
  private static ClassRow peasantRow(long stock, long landMilliMu) {
    return new ClassRow(
        PEASANT_KEY,
        POPULATION,
        POPULATION * LABOR_PER_PERSON,
        1000,
        Map.of(AssetKind.LAND, landMilliMu),
        stock > 0L ? Map.of(GRAIN, stock) : Map.of(),
        0L,
        List.of(),
        Map.of(GRAIN, DAILY_NEED),
        Map.of());
  }

  /** **不占地**的一行（真档里每座城的手工业行都是这一形态）：无生产资料 ⇒ 种子需求恒 0。 */
  private static ClassRow landlessRow(ClassKey key, long stock) {
    return new ClassRow(
        key,
        POPULATION,
        POPULATION * LABOR_PER_PERSON,
        1000,
        Map.of(),
        stock > 0L ? Map.of(GRAIN, stock) : Map.of(),
        0L,
        List.of(),
        Map.of(GRAIN, DAILY_NEED),
        Map.of());
  }

  /** 一行的地主（0 人、0 劳动、投入率 0 —— 它的缸只是种子本钱与同格放贷的余粮）。 */
  private static ClassRow landlordRow(long stock, long landMilliMu) {
    return new ClassRow(
        LANDLORD_KEY,
        0L,
        0L,
        0,
        Map.of(AssetKind.LAND, landMilliMu),
        stock > 0L ? Map.of(GRAIN, stock) : Map.of(),
        0L,
        List.of(),
        Map.of(),
        Map.of());
  }

  /** 一个产业：两个槽位（上限 1000‰，够放本夹具的行）、`Split(700, 300)`、亩产 {@value #YIELD_PER_MU} 粮/亩。 */
  private static Industry industry(
      IndustryId id, String name, long cycleDays, Map<AssetKind, Long> cycleInput) {
    return new Industry(
        id,
        name,
        new RegimeId("feudal"),
        cycleDays,
        0L,
        Map.of(),
        0L,
        Map.of(GRAIN, YIELD_PER_MU),
        cycleInput,
        List.of(new ClassSlot(PEASANT, "贫农", 1000), new ClassSlot(LANDLORD, "地主", 1000)),
        new AllocationRule.Split(700, 300),
        0L,
        0L);
  }

  /** 一份经济状态。★ 两张表都用 {@code LinkedHashMap}（迭代序是内容的纯函数）。 */
  private static EconomyData data(
      Map<ClassKey, ClassRow> rows, Map<IndustryId, Industry> industries) {
    EconomyMeta meta =
        new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty());
    return new EconomyData(Optional.of(meta), industries, rows, Map.of(), Map.of());
  }

  /** 一格、一个农业产业、**一行贫农**（地 {@link #LAND_MILLI_MU} 千分亩）、周期 {@value #CYCLE_DAYS} 天。 */
  private static EconomyData farm(long stock, Map<AssetKind, Long> cycleInput) {
    return farm(stock, cycleInput, CYCLE_DAYS);
  }

  private static EconomyData farm(long stock, Map<AssetKind, Long> cycleInput, long cycleDays) {
    LinkedHashMap<ClassKey, ClassRow> rows = new LinkedHashMap<>();
    rows.put(PEASANT_KEY, peasantRow(stock));
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, "农业", cycleDays, cycleInput));
    return data(rows, industries);
  }

  private static long grainOf(EconomyData data, ClassKey key) {
    return data.classes().get(key).goods().getOrDefault(GRAIN, 0L);
  }

  private static long consumedOf(EconomyData data, ClassKey key) {
    FlowRow flow = data.flows().get(key);
    return flow == null ? 0L : flow.consumed().getOrDefault(GRAIN, 0L);
  }

  private static long unmetOf(EconomyData data, ClassKey key) {
    FlowRow flow = data.flows().get(key);
    return flow == null ? 0L : flow.unmetNeed();
  }

  // ── ① 播种日先扣种（spec §九 V3 判据 2）────────────────────────────────────────────

  /**
   * 缸 {@code 40,000 + 2 × 33,200 = 106,400} 恰够"满种 + 两天口粮"：
   *
   * <pre>
   * 第 1 天（progressDays == 0 ⇒ 播种日）：扣种 400 亩 × 100 = 40,000 ⇒ 66,400；再吃 33,200 ⇒ 33,200
   * 第 2 天：不播种，只吃 33,200 ⇒ 0；周期末收获（土地是瓶颈 400 亩）⇒ 净 22,780,000 ⇒ 22,780,000
   * </pre>
   */
  @Test
  void sowingDayDrawsTheSeedBeforeTheDayIsEaten() {
    EconomyData base = farm(106_400L, Map.of(AssetKind.LAND, SEED_PER_MU));

    EconomyData day1 = EconomySettlement.settle(base, 0L, 1L);

    assertThat(grainOf(day1, PEASANT_KEY))
        .as("库存 = 期初 − 种子 40,000 − 当日口粮 33,200")
        .isEqualTo(106_400L - FULL_SEED - DAILY_NEED);
    assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
        .as("本周期实际扣到的种子 = 满种量")
        .isEqualTo(FULL_SEED);
    assertThat(consumedOf(day1, PEASANT_KEY))
        .as("★ 留种要记账（spec §二：留种的计量是\"数\"）：消费 = 种子 + 口粮")
        .isEqualTo(FULL_SEED + DAILY_NEED);
    assertThat(unmetOf(day1, PEASANT_KEY)).as("缸够 ⇒ 没有缺口").isZero();

    EconomyData day2 = EconomySettlement.settle(day1, 1L, 2L);

    assertThat(grainOf(day2, PEASANT_KEY))
        .as("第 2 天只吃口粮 ⇒ 0，然后收获满产净额")
        .isEqualTo(FULL_HARVEST_NET);
    assertThat(day2.industries().get(FARM).cycleSeedUsedMilli())
        .as("周期关账 ⇒ 累加器清零（不清零第 2 周期的 seedCap 会凭空变大）")
        .isZero();
    assertThat(day2.industries().get(FARM).progressDays()).as("关账后进度归零").isZero();
    assertThat(day2.meta().orElseThrow().lastClosedCycle()).as("关账周期序号 1").hasValue(1L);
  }

  /** 非播种日（`progressDays != 0`）**一次都不扣**：库存只减当日口粮。 */
  @Test
  void daysAfterTheSowingDayDrawNothing() {
    EconomyData day1 = EconomySettlement.settle(farm(106_400L, Map.of(AssetKind.LAND, SEED_PER_MU)), 0L, 1L);

    EconomyData day2 = EconomySettlement.settle(day1, 1L, 2L);

    assertThat(consumedOf(day2, PEASANT_KEY) - consumedOf(day1, PEASANT_KEY))
        .as("第 2 天的消费只有口粮（外加 15% 生产损耗的份额）")
        .isEqualTo(DAILY_NEED + (FULL_HARVEST_NET * EconomySettlement.PRODUCTION_CONSUMPTION_PER_MILLE / (1000L - EconomySettlement.PRODUCTION_CONSUMPTION_PER_MILLE)));
  }

  // ── ② 次序预设：先扣 vs 后扣（spec §3.2 的"可调预设"，不许是死分支）────────────────────

  /**
   * ★★ **次序的判别力**（这条用例是"偏离 ③"存在的理由）：缸 {@code 20,000} **不够**满种（40,000）也**不够**一天口粮（33,200）。
   *
   * <pre>
   * 先扣种（默认 true）：扣 min(20,000, 40,000) = 20,000 ⇒ 缸 0 ⇒ 当天口粮 0 ⇒ 缺口 33,200
   * 先吃饭（false）    ：吃 min(20,000, 33,200) = 20,000 ⇒ 缸 0 ⇒ 再扣种 0 ⇒ **种子一颗没留住**，缺口 13,200
   * </pre>
   *
   * <p>★ 两种次序给出**不同的种子量（20,000 vs 0）与不同的缺口（33,200 vs 13,200）**——这正是 spec §3.2
   * 把"播种该不该先于吃饭扣种"做成预设的原因：代码不替 GM 选。判别力：把播种步挪到
   * {@code settleHexes} 之后（或把次序参数恒传 false）⇒ 本条的"先扣"一半必红。
   */
  @Test
  void drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown() {
    EconomyData base = farm(20_000L, Map.of(AssetKind.LAND, SEED_PER_MU));
    LinkedHashMap<ClassKey, FlowRow> flowsFirst = new LinkedHashMap<>();
    LinkedHashMap<ClassKey, FlowRow> flowsAfter = new LinkedHashMap<>();

    EconomyData first = EconomySettlement.settleOneDay(base, 1L, flowsFirst, true);
    EconomyData after = EconomySettlement.settleOneDay(base, 1L, flowsAfter, false);

    assertThat(first.industries().get(FARM).cycleSeedUsedMilli())
        .as("先扣种 ⇒ 缸里那 20,000 全变成种子")
        .isEqualTo(20_000L);
    assertThat(after.industries().get(FARM).cycleSeedUsedMilli())
        .as("先吃饭 ⇒ 颗粒无种（这就是 GM 取 false 时的后果）")
        .isZero();
    assertThat(unmetOf(first, PEASANT_KEY)).as("先扣种 ⇒ 当天一口没吃").isEqualTo(DAILY_NEED);
    assertThat(unmetOf(after, PEASANT_KEY))
        .as("先吃饭 ⇒ 吃了 20,000，缺口只剩 33,200 − 20,000")
        .isEqualTo(DAILY_NEED - 20_000L);
  }

  // ── ③ 未配种子 ⇒ 与 V2 逐字一致（spec §3.3 的口径 + 旧档护栏）────────────────────────

  /**
   * ★★ **没配种子（空 map）或每亩需种为 0 ⇒ 播种日一字不扣**。这条是"旧档与未配种子的产业行为与 V2
   * 完全一致"的护栏——第三路瓶颈**绝不许**把它们变成一粒无收。
   */
  @Test
  void anIndustryWithoutASeedRateDrawsNothingAtAll() {
    for (Map<AssetKind, Long> cycleInput : List.of(Map.<AssetKind, Long>of(), Map.of(AssetKind.LAND, 0L))) {
      EconomyData day1 =
          EconomySettlement.settle(farm(106_400L, cycleInput), 0L, 1L);

      assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
          .as("没配种子 ⇒ 累加器恒 0（%s）", cycleInput)
          .isZero();
      assertThat(grainOf(day1, PEASANT_KEY))
          .as("库存只减当日口粮（%s）", cycleInput)
          .isEqualTo(106_400L - DAILY_NEED);
    }
  }

  /** ★ `need == 0` 的行（**没有地** ⇒ 真档里每座城的手工业行）⇒ 不扣、不累加。 */
  @Test
  void aRowWithoutLandIsNeverDrawnFrom() {
    LinkedHashMap<ClassKey, ClassRow> rows = new LinkedHashMap<>();
    rows.put(PEASANT_KEY, peasantRow(106_400L)); // 农业行：400 亩、缸够满种 + 两天口粮
    ClassKey craftPeasant = new ClassKey(CRAFT, PEASANT);
    rows.put(craftPeasant, landlessRow(craftPeasant, 1_000_000L)); // 手工业行：不占地、缸很足
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, "农业", CYCLE_DAYS, Map.of(AssetKind.LAND, SEED_PER_MU)));
    industries.put(CRAFT, industry(CRAFT, "手工业", CYCLE_DAYS, Map.of(AssetKind.LAND, SEED_PER_MU)));
    EconomyData base = data(rows, industries);

    EconomyData day1 = EconomySettlement.settle(base, 0L, 1L);

    assertThat(grainOf(day1, craftPeasant))
        .as("手工业行没有地 ⇒ 不扣它的粮（只吃口粮）")
        .isEqualTo(1_000_000L - DAILY_NEED);
    assertThat(day1.industries().get(CRAFT).cycleSeedUsedMilli())
        .as("无地产业的种子累加器恒 0")
        .isZero();
    assertThat(day1.industries().get(FARM).cycleSeedUsedMilli())
        .as("农业那一路照扣（不许被无地产业带偏）")
        .isEqualTo(FULL_SEED);
  }

  // ── ④ 等价性与 1 天周期（§十一 / Review Focus 9、12）──────────────────────────────

  /** ★ 一次 2 天 == 两次单日：**播种恰发生一次**（不会"每推一次就扣一遍"）。 */
  @Test
  void settlingTwoDaysAtOnceEqualsTwoSingleDayStepsWithSeeds() {
    EconomyData base = farm(106_400L, Map.of(AssetKind.LAND, SEED_PER_MU));

    EconomyData once = EconomySettlement.settle(base, 0L, 2L);
    EconomyData twice =
        EconomySettlement.settle(EconomySettlement.settle(base, 0L, 1L), 1L, 2L);

    assertThat(once).as("§十一：一次 2 天 == 两次单日（终态逐值）").isEqualTo(twice);
    assertThat(once.flows()).as("流水也逐值相同").isEqualTo(twice.flows());
    assertThat(once.industries().get(FARM).cycleSeedUsedMilli()).as("关账后归零").isZero();
  }

  /**
   * ★★ `cycleDays == 1`：**同一天先播后收**。收获日读的必须是**当天刚播**的种子累加器
   * （若收获读的是播种前的 `Industry` 快照，`seedCapMu` 会恒为 0 ⇒ 本条的库存断言必红）。
   *
   * <pre>
   * 第 1 天：播 40,000 ⇒ 66,400；吃 33,200 ⇒ 33,200；progressed(1) >= cycleDays(1) ⇒ 收获
   *     劳动可经营 = 232,000 × 7 / 1000 = 1,624 亩；土地 400 亩；种子可支撑 = 40,000 / 100 = 400 亩 ⇒ 取 400
   *     ⇒ 净 22,780,000 ⇒ 库存 33,200 + 22,780,000 = 22,813,200；随后关账清零
   * </pre>
   */
  @Test
  void aOneDayCycleSowsAndHarvestsOnTheSameDay() {
    EconomyData next =
        EconomySettlement.settle(farm(106_400L, Map.of(AssetKind.LAND, SEED_PER_MU), 1L), 0L, 1L);

    assertThat(grainOf(next, PEASANT_KEY))
        .as("当天播、当天收（种子必须先扣、收获必须读到它）")
        .isEqualTo(33_200L + FULL_HARVEST_NET);
    assertThat(next.industries().get(FARM).cycleSeedUsedMilli()).as("关账后归零").isZero();
    assertThat(next.industries().get(FARM).progressDays()).isZero();
  }
}
```

- [ ] **Step 2: 跑测试确认它红**

Run: `./mvnw -q -Dtest=EconomySowingTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: **编译错误**（`找不到符号: 方法 settleOneDay(EconomyData,long,LinkedHashMap,boolean)` / `settleOneDay 在 EconomySettlement 中是 private 访问控制`）。这是本步要的"红"。

- [ ] **Step 3: 实现——次序常量、包内可见重载、播种步、流程重排、累加器关账清零**

`EconomySettlement.java`：

1）在 `FAMINE_MORTALITY_PER_MILLE` 附近加常量：
```java
  /**
   * ★★ **播种是否先于当日消费扣种**（v2 spec §3.2 的行为预设；用户 2026-09-25 定案：先用常量，默认 {@code true}）。
   *
   * <p>★ **V7 参数目录（spec §四）落地后，它迁入 {@code economy} 切片的参数表并成为 GM 可调**（spec §3.2：
   * "凡行为一律做成预设"，故它**不许**被写死成"代码选一个聪明的"）。届时本常量只作默认值。
   *
   * <p>★ 取 {@code false} = "吃饭优先、种子看运气"：那是 GM 的选择，不是代码该替他做的判断。
   * 无论取真取假，**播种日这个步骤都在**（spec §3.2：参数化不许改变流程形状），只是扣减次序不同。
   */
  public static final boolean PLANTING_DRAWS_BEFORE_CONSUMPTION = true;
```
2）把 `settleOneDay` 拆成"默认秩序 + 带次序的重载"（**包内可见**的那一个只为直测，见"偏离 ③"）：
```java
  private static EconomyData settleOneDay(
      EconomyData base, long day, LinkedHashMap<ClassKey, FlowRow> flows) {
    return settleOneDay(base, day, flows, PLANTING_DRAWS_BEFORE_CONSUMPTION);
  }

  /**
   * 结算一天（次序可注入）。{@code plantingDrawsFirst == false} 的那条路**不是死代码**：
   * 它是"吃饭优先"这个预设的实现，由 {@code EconomySowingTest.drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown} 逐值钉住。
   */
  static EconomyData settleOneDay(
      EconomyData base,
      long day,
      LinkedHashMap<ClassKey, FlowRow> flows,
      boolean plantingDrawsFirst) {
```
（方法体里其余部分原样保留，只在其开头与 `settleHexes` 前后各插一段：）
```java
    // ── 0. 播种（周期的第一天）：**在当天吃饭之前**把种子划走（v2 spec §3.2）──────────────
    //   ★ 次序可注入（preset）：取 false 时把同一步挪到消费之后。
    if (plantingDrawsFirst) {
      sowIfCycleStart(industries, rows, consumedGrain);
    }

    // ── 1~2. 消费 + 同格缺口（借粮 / 记未满足需求）────────────────────────────────────
    settleHexes(rows, debts, consumedGrain, borrowing, unmetToday, day, dueCycle);

    if (!plantingDrawsFirst) {
      sowIfCycleStart(industries, rows, consumedGrain);
    }
```
3）产业循环里，周期末那一段加 `nextSeedUsed`（其余不动）：
```java
      long nextCycleLabor = cycledLabor;
      long nextSeedUsed = industry.cycleSeedUsedMilli(); // 非关账日：原样带过
      ...
      if (progressed >= industry.cycleDays()) {
        harvest(industry, rows, keys, cycledLabor, income, productionLoss);
        ...
        nextProgress = 0L;
        nextCycleLabor = 0L;
        nextSeedUsed = 0L; // ★ 与 cycleLaborMilli 同处清零（不清零 ⇒ 下周期的 seedCap 凭空变大）
        anyCycleClosed = true;
      }
      industries.put(id, withCycleState(industry, nextProgress, nextCycleLabor, nextSeedUsed));
```
4）新增播种步（放在 `settleHexes` 之前，与 `// ── 消费 + 同格借粮 ──` 那一段相邻）：
```java
  // ── 播种（周期的第一天）──────────────────────────────────────────────────────────────

  /**
   * ★★ **播种步**（v2 spec §3.2/§3.3）：**周期的第一天**（{@code progressDays == 0}）逐 {@link ClassRow}
   * 从它**自己的** {@code goods} 里扣种，并把实际扣到的量累加进 {@link Industry#cycleSeedUsedMilli()}。
   *
   * <pre>
   * rowLandMu = meansOfProduction[LAND] / 1000        // 千分亩 ⇒ 亩（★ 与 cycleInputPerUnit 的"毫粮/亩"同侧）
   * seedPerMu = cycleInputPerUnit.getOrDefault(LAND, 0)// 毫粮/亩；0 ⇒ 不扣（旧档/未配种子 ⇒ 与 V2 一字不差）
   * need      = rowLandMu × seedPerMu                 // 毫粮
   * 库存 ≥ need ⇒ 扣 need；库存 &lt; need ⇒ **扣光库存**（⇒ 收获日的 seedCapMu 自然缩小）
   * </pre>
   *
   * <p>★★ **种子各扣各的**（定案）：逐 {@code ClassRow} 从它自己的 {@code goods} 里扣，**不从全格池子扣**。
   * 理由：与"粮住在阶层行里"一致，且能自然产生阶级差异——贫农缸空 ⇒ 它的地荒着、地主的地照种
   * （收获日按 {@code Σ实际扣到的种子 / seedPerMu} 算可支撑亩数）。
   *
   * <p>★ **扣掉的量并入当日 {@code consumedGrain}**：留种是**本期的消费**（spec §二 把"留种的计量"列在"数"里），
   * 记进去才能保住 §6.1 的守恒式 {@code 库存减少 == Σ消费 − Σ所得}（否则配了种子的世界上那条等式不成立 ⇒
   * 端到端的守恒用例会变成假绿）。
   *
   * <p>★ **为什么不在这里扣"每日原料"**：{@code dailyInputPerUnit} 是**每日**口径，v1 仍是零读取点（spec §3.3
   * 明说两个字段并存、语义各自清楚），不在本步范围。
   */
  private static void sowIfCycleStart(
      LinkedHashMap<IndustryId, Industry> industries,
      LinkedHashMap<ClassKey, ClassRow> rows,
      LinkedHashMap<ClassKey, Long> consumedGrain) {
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      Industry industry = industries.get(id);
      if (industry.progressDays() != 0L) {
        continue; // 只有周期的第一天播种
      }
      long seedPerMu = industry.cycleInputPerUnit().getOrDefault(AssetKind.LAND, 0L);
      if (seedPerMu == 0L) {
        continue; // ★ 0 与"缺键"同义：不扣、不缩地 ⇒ 未配种子的产业行为与 V2 一字不差
      }
      long sown = 0L;
      for (ClassKey key : classKeysOf(rows, id)) {
        ClassRow row = rows.get(key);
        long rowLandMu = row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L) / 1000L;
        long need = rowLandMu * seedPerMu; // 毫粮（★ 亩 × 毫粮/亩）
        if (need == 0L) {
          continue; // 没有地 ⇒ 没有种子需求（真档里每座城的手工业行都是这一形态）
        }
        long stock = grainOf(row);
        long drawn = Math.min(stock, need); // ★ 扣不动就扣光库存（seedCapMu 会跟着缩）
        if (drawn == 0L) {
          continue;
        }
        rows.put(key, withGoodsGrain(row, stock - drawn));
        consumedGrain.merge(key, drawn, Long::sum);
        sown += drawn;
      }
      if (sown > 0L) {
        // ★★ 必须写回 industries 工作副本：收获（同一次日结算里、稍后跑）读的就是这一份累加器。
        industries.put(id, withCycleState(industry, industry.progressDays(), industry.cycleLaborMilli(), industry.cycleSeedUsedMilli() + sown));
      }
    }
  }
```
5）把 `withProgressAndLabor` 改名/改签名为 `withCycleState(industry, progress, cycleLabor, cycleSeedUsed)`，并透传两个新字段：
```java
  /** 换进度、周期劳动累计与周期种子累计（其余字段原样带过）。 */
  private static Industry withCycleState(
      Industry industry, long progress, long cycleLabor, long cycleSeedUsed) {
    return new Industry(
        industry.id(),
        industry.name(),
        industry.regime(),
        industry.cycleDays(),
        progress,
        industry.dailyInputPerUnit(),
        industry.dailyLaborPerUnit(),
        industry.outputPerUnit(),
        industry.cycleInputPerUnit(),
        industry.slots(),
        industry.allocation(),
        cycleLabor,
        cycleSeedUsed);
  }
```
6）类 javadoc 的日流程清单加一行（现在写的是"1. 消费 2. 缺口 3. 进度 4. 劳动投入"）：
```java
 *   <li>**播种**：周期的第一天（{@code progressDays == 0}）先扣种子（{@link #sowIfCycleStart}）——**先于当天消费**
 *       （{@link #PLANTING_DRAWS_BEFORE_CONSUMPTION}，v2 spec §3.2）。种子粮与口粮是同一个商品，优先性来自**时点**。
```
并在"守恒（§6.1）"那一段补一句：种子扣减计入 {@code consumed}。

- [ ] **Step 4: 跑测试确认全绿**

Run: `./mvnw -q -Dtest=EconomySowingTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: PASS（**核对 `simos-economy/target/surefire-reports/*.txt` mtime 落在本轮**）。
★ 若某条字面量对不上：**先核算式再改期望值**——本用例的期望值全是算出来的；对不上说明我对结算的理解有偏差，
此时**停下来把实际值记进提交信息并报给控制器**，不要直接把断言改成"实际值"（那会让用例变成同义反复）。

- [ ] **Step 5: 回归（等价性 / 边界 / 既有结算）**

Run: `./mvnw -q test -pl simos-economy -am`
Expected: 全绿。★ `EconomySettlementTest` / `EconomyCycleBoundaryTest` 的夹具**没有** `cycleInputPerUnit`
（空 map）⇒ 它们的字面量必须**一个都不变**。若它们红了，说明播种步被误做成了"无条件扣"——**修实现，不改字面量**。

- [ ] **Step 6: 变异自证（三条，逐条验）**

1. **次序**：把 `settleOneDay` 里 `plantingDrawsFirst` 那两段都改成"只在消费之后播种"（= 恒 false）⇒ 重跑 ⇒
   `drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown` 与 `sowingDayDrawsTheSeedBeforeTheDayIsEaten` **必须红**。还原 ⇒ 回绿。
2. **写回累加器**：把 `industries.put(id, withCycleState(...))` 那一行删掉（或只传 `industry.progressDays()` 与旧累加器）
   ⇒ 重跑 ⇒ `aOneDayCycleSowsAndHarvestsOnTheSameDay` **必须红**。还原 ⇒ 回绿。
3. **关账清零**：把 `nextSeedUsed = 0L;` 删掉 ⇒ 重跑 ⇒ `sowingDayDrawsTheSeedBeforeTheDayIsEaten` 的"关账清零"一半
   **必须红**。还原 ⇒ 回绿。

★ 三轮都**按字节还原**（`git checkout -- <文件>` 或重新 Edit），每轮还原后重跑一次确认回绿。

- [ ] **Step 7: 提交**

```bash
git add simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java \
        simos-economy/src/test/java/io/mosire/simos/economy/time/EconomySowingTest.java
git commit -m "feat(economy): 播种步 sowIfCycleStart + 日流程重排（播种 → 消费 → 产业循环）"
```
提交信息须含：重排前后的流程、`PLANTING_DRAWS_BEFORE_CONSUMPTION` 的默认值与 V7 迁入参数表的标注、
**实测数字**（播种日扣 40,000 / 缺口 33,200 vs 13,200 的两种次序）、未验的部分（第三路瓶颈在任务 4）。

---

### Task 4: 第三路瓶颈——`harvest` 的 `min` 从两路变三路（`seedPerMu == 0` 不加约束）

**Files:**
- Modify: `simos-economy/.../time/EconomySettlement.java`（只改 `harvest`）
- Modify: `simos-economy/src/test/java/io/mosire/simos/economy/time/EconomySowingTest.java`（追加一段）

**Interfaces:**
- Consumes: `Industry.cycleInputPerUnit()` / `cycleSeedUsedMilli()`（任务 1、3）
- Produces: 无新 API（`harvest` 的瓶颈公式多一路）

★ **口径（spec §3.1，逐字）**：
```
availableMu = totalLandMilliMu / 1000
ableMu      = avgLaborMilli × LAND_MU_PER_LABOR / 1000
seedCapMu   = seedPerMu == 0 ? availableMu : cycleSeedUsedMilli / seedPerMu   // 毫粮 ÷ (毫粮/亩) = 亩
actualMu    = min(availableMu, ableMu, seedCapMu)
```
★ **`seedPerMu == 0` 时取 `availableMu` 而不是 `0`**：那等于**不施加这一路约束**（`min` 里它不可能更小），
于是旧档与未配种子的产业**与 V2 逐值一致**。写成 `? 0 :` 会让它们**颗粒无收**——这是本任务最主要的风险点。
★ **一条恒成立的不等式（实现时顺手记住）**：`seedCapMu ≤ availableMu` —— 因为扣到的种子最多是
`Σ地亩 × seedPerMu`，相除最多还原出地亩。故第三路**只会缩面积、永远不会放大它**；`seedPerMu == 0` 取
`availableMu` 是这一路"存在但不约束"的唯一正确写法。

- [ ] **Step 1: 写失败测试（追加到 `EconomySowingTest`）**

```java
  // ── ⑤ 第三路瓶颈（spec §九 V3 判据 3）──────────────────────────────────────────────

  /**
   * ★★ **缺种子 ⇒ 投入面积缩 ⇒ 减产**：缸 {@code 20,000} < 满种 40,000 ⇒ 先扣种时**扣光**（任务 3 已断言），
   * 收获日可支撑亩数 = {@code 20,000 / 100 = 200 亩}（&lt; 土地的 400 亩、&lt; 劳动的 1,624 亩）⇒ **种子是瓶颈**。
   *
   * <pre>
   * 毛产 = 200 × 67 × 1000 = 13,400,000；扣 15% ⇒ 净 11,390,000
   * 对照（同夹具**不配种子**）：土地是瓶颈 ⇒ 400 亩 ⇒ 净 22,780,000（= {@link #FULL_HARVEST_NET}）
   * </pre>
   */
  @Test
  void aShortJarShrinksTheSownAreaAndCutsTheHarvest() {
    long seedCapMu = 20_000L / SEED_PER_MU; // = 200 亩
    long expectedNet =
        seedCapMu
            * YIELD_PER_MU
            * EconomySettlement.MILLI_PER_GRAIN
            * (1000L - EconomySettlement.PRODUCTION_CONSUMPTION_PER_MILLE)
            / 1000L;

    EconomyData withSeeds = EconomySettlement.settle(farm(20_000L, Map.of(AssetKind.LAND, SEED_PER_MU)), 0L, 2L);
    EconomyData withoutSeeds = EconomySettlement.settle(farm(20_000L, Map.of()), 0L, 2L);

    assertThat(grainOf(withSeeds, PEASANT_KEY))
        .as("20,000 毫粮的种子只够种 200 亩（土地本来能种 400 亩）⇒ 终态 = 200 亩的净产")
        .isEqualTo(expectedNet);
    assertThat(withSeeds.industries().get(FARM).cycleSeedUsedMilli())
        .as("扣光：缸里那 20,000 全变成种子（当天口粮一件不剩 ⇒ 下面那条缺口断言）")
        .isEqualTo(20_000L);
    assertThat(unmetOf(withSeeds, PEASANT_KEY)).as("两天全缺口").isEqualTo(2L * DAILY_NEED);
    assertThat(grainOf(withoutSeeds, PEASANT_KEY))
        .as("对照：不配种子 ⇒ 第三路不施加约束 ⇒ 土地瓶颈满产；那 20,000 被第 1 天口粮吃光"
            + "（起点 0）⇒ 终态恰为净产")
        .isEqualTo(FULL_HARVEST_NET);
  }

  /** ★★ **缸全空 ⇒ 播种日扣不到 ⇒ 颗粒无收**（"冬春吃空缸 ⇒ 减产"在单周期的极端形态）。 */
  @Test
  void anEmptyJarYieldsNothingInsteadOfTheLandBoundHarvest() {
    EconomyData next = EconomySettlement.settle(farm(0L, Map.of(AssetKind.LAND, SEED_PER_MU)), 0L, 2L);

    assertThat(next.industries().get(FARM).cycleSeedUsedMilli()).as("一颗都没扣到").isZero();
    assertThat(grainOf(next, PEASANT_KEY))
        .as("seedCapMu = 0 ⇒ 0 亩 ⇒ 不产粮（V2 会按土地 400 亩满产 22,780,000）")
        .isZero();
    assertThat(unmetOf(next, PEASANT_KEY)).as("两天全缺口 = 2 × 33,200").isEqualTo(2L * DAILY_NEED);
  }

  /**
   * ★★ **`seedPerMu == 0`（键在、值是 0）不许把产量压成 0 亩**（本任务最主要的风险点）：
   * 三元式写成 `? 0 :` 或直接除零 ⇒ 本条必红。
   */
  @Test
  void aZeroSeedRateLeavesTheHarvestExactlyAsBefore() {
    EconomyData next =
        EconomySettlement.settle(farm(106_400L, Map.of(AssetKind.LAND, 0L)), 0L, 2L);

    assertThat(grainOf(next, PEASANT_KEY))
        .as("每亩需种 0 ⇒ 这一路不施加约束（min 里它不可能更小）⇒ 与 V2 同值")
        .isEqualTo(106_400L - 2L * DAILY_NEED + FULL_HARVEST_NET);
  }

  /** ★ 六种键都允许，但 **v1 只读 `LAND`**：配上其余五种不改任何数（spec §3.1"声明但不启用"）。 */
  @Test
  void allSixAssetKindsAreAcceptedButOnlyLandIsRead() {
    LinkedHashMap<AssetKind, Long> sixKinds = new LinkedHashMap<>();
    sixKinds.put(AssetKind.CATTLE, 1L);
    sixKinds.put(AssetKind.TOOL, 2L);
    sixKinds.put(AssetKind.LAND, SEED_PER_MU);
    sixKinds.put(AssetKind.WORKSHOP, 3L);
    sixKinds.put(AssetKind.MACHINE, 4L);
    sixKinds.put(AssetKind.SHIP, 5L);

    EconomyData next = EconomySettlement.settle(farm(106_400L, sixKinds), 0L, 2L);

    assertThat(next.industries().get(FARM).cycleSeedUsedMilli())
        .as("只按 LAND 那一档扣（其余五档不进公式）")
        .isEqualTo(FULL_SEED);
    assertThat(grainOf(next, PEASANT_KEY))
        .as("与只配 LAND 的同夹具逐值相同")
        .isEqualTo(106_400L - 2L * DAILY_NEED + FULL_HARVEST_NET);
  }

  /**
   * ★ **第三路不许盖过另外两路**：满种时 {@code seedCapMu} **恰等于** {@code availableMu}
   * （因为 {@code 扣到的种子 = Σ地亩 × seedPerMu} ⇒ 相除还原出地亩）⇒ 取小仍按土地。
   * ★★ 由此得一条**恒成立的不等式**：{@code seedCapMu ≤ availableMu} —— 第三路只会**缩**面积，
   * 永远不会**放大**它（这条也是"`seedPerMu == 0` 时取 `availableMu` = 不施加约束"的依据）。
   */
  @Test
  void aHugeSeedAmountNeverOverridesTheOtherTwoBottlenecks() {
    EconomyData next =
        EconomySettlement.settle(farm(106_400L * 100L, Map.of(AssetKind.LAND, 1L)), 0L, 2L);

    assertThat(grainOf(next, PEASANT_KEY))
        .as("每亩需种 1 毫粮 ⇒ 满种只需 400 毫粮；可支撑亩 = 400 ÷ 1 = 400 亩 = 土地 ⇒ 取小仍按土地"
            + "（库存 = 期初 − 满种 400 毫粮 − 两天口粮 + 满产净额）")
        .isEqualTo(106_400L * 100L - 400L - 2L * DAILY_NEED + FULL_HARVEST_NET);
  }
```
（`aHugeSeedAmount...` 的缸放大 100 倍是为了让"满种后仍够吃两天"成立：`1 毫粮/亩 × 400 亩 = 400` 毫粮的种子，对库存 10,640,000 微不足道。）

- [ ] **Step 2: 跑测试确认它红**

Run: `./mvnw -q -Dtest=EconomySowingTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: `aShortJarShrinksTheSownAreaAndCutsTheHarvest` 与 `anEmptyJarYieldsNothingInsteadOfTheLandBoundHarvest`
**红**（实际是按 400 亩满产的 `22,780,000` 级别的数）；`aZeroSeedRateLeavesTheHarvestExactlyAsBefore` 等三条
**绿**（它们描述的是"不施加约束"，恰好是 v1 的现状——**这正是它们的判别力所在：实现做错时它们会红**）。

- [ ] **Step 3: 实现（只改 `harvest` 的瓶颈三行）**

```java
    long avgLaborMilli = cycledLabor / industry.cycleDays(); // 平均每日实际劳动（千分劳动）
    long availableMu = totalLandMilliMu / 1000L; // 千分亩 ⇒ 亩（向下取整）
    long ableMu = avgLaborMilli * LAND_MU_PER_LABOR / 1000L; // 劳动可经营亩数（向下取整）
    // ★★ **第三路瓶颈**（v2 spec §3.1/§3.2）：本周期**实际扣到的种子**能支撑多少亩。
    //   ★ seedPerMu == 0 ⇒ **不加约束**（取 availableMu，min 里它不可能更小），**不是**"0 亩"：
    //     旧档与未配种子的产业据此与 V2 逐值一致；写成 0 会让它们颗粒无收。
    //   量纲：毫粮 ÷ (毫粮/亩) = 亩（与上面两路同为"亩"，故能进同一个 min）。
    long seedPerMu = industry.cycleInputPerUnit().getOrDefault(AssetKind.LAND, 0L);
    long seedCapMu = seedPerMu == 0L ? availableMu : industry.cycleSeedUsedMilli() / seedPerMu;
    long actualMu = Math.min(availableMu, Math.min(ableMu, seedCapMu)); // 三路取小
```
并把 `harvest` 的 javadoc 从"取小后向下取整到亩"改成三路，注明第三路读的是 {@code Industry#cycleSeedUsedMilli()}。

- [ ] **Step 4: 跑测试确认全绿**

Run: `./mvnw -q -Dtest=EconomySowingTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: PASS（**核对 surefire 报告 mtime 落在本轮**）。

- [ ] **Step 5: 回归（既有结算与端到端）**

Run: `./mvnw -q test -pl simos-economy,simos-app -am`
Expected: 全绿。★ `EconomySettlementEndToEndTest` 的字面量**一个都不许改**（5 格夹具未配种子 ⇒ 第三路不施加约束）。

- [ ] **Step 6: 变异自证（两条）**

1. 把三元式改成 `seedCapMu = seedPerMu == 0L ? 0L : industry.cycleSeedUsedMilli() / seedPerMu;`
   ⇒ 重跑 ⇒ `aZeroSeedRateLeavesTheHarvestExactlyAsBefore` 与 `anIndustryWithoutASeedRateDrawsNothingAtAll`
   （任务 3）**必须红**，且端到端那条会一起红（未配种子的真档颗粒无收）。还原 ⇒ 回绿。
2. 把 `seedCapMu` 从 `min` 里去掉（退回两路）⇒ 重跑 ⇒ `aShortJarShrinksTheSownAreaAndCutsTheHarvest` 与
   `anEmptyJarYieldsNothingInsteadOfTheLandBoundHarvest` **必须红**。还原 ⇒ 回绿。

- [ ] **Step 7: 提交**

```bash
git add simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java \
        simos-economy/src/test/java/io/mosire/simos/economy/time/EconomySowingTest.java
git commit -m "feat(economy): 收获的第三路瓶颈 seedCapMu（缺种子 ⇒ 投入面积缩 ⇒ 减产；seedPerMu==0 不加约束）"
```

---

### Task 5: 验收链——"冬春吃空缸 ⇒ 播种日扣不到 ⇒ 减产"逐值 + 各扣各的阶级差异 + 账要平

**Files:**
- Modify: `simos-economy/src/test/java/io/mosire/simos/economy/time/EconomySowingTest.java`（追加最后一段）

**Interfaces:**
- Consumes: 任务 1~4 的全部产出
- Produces: 无新 API（本任务全部是断言）

- [ ] **Step 1: 写失败测试（三条，逐值）**

```java
  // ── ⑥ 验收链（spec §九 V3 判据 4）与阶级差异 ────────────────────────────────────────

  /**
   * ★★ **各扣各的：贫农缸空 ⇒ 它的地荒着；地主缸足 ⇒ 它的地照种**（定案：逐 {@code ClassRow} 从它自己的
   * {@code goods} 里扣，不从全格池子扣）。
   *
   * <p>夹具：贫农（400 人 / 200 亩 / **缸空**）+ 地主（0 人 / 800 亩 / 缸 5,000,000）。周期 2 天。
   *
   * <pre>
   * 播种日：贫农扣 min(0, 200 × 100 = 20,000) = 0；地主扣 800 × 100 = 80,000 ⇒ 累加器 80,000
   * 可支撑亩 = 80,000 / 100 = 800 亩（&lt; 土地 1,000 亩、&lt; 劳动 1,624 亩）⇒ **贫农那 200 亩荒着**
   * 满产地净产（按 800 亩）= 800 × 67 × 1000 × 0.85 = 45,560,000
   * 分配权重：贫农 = (700 × 200‰土地 + 300 × 1000‰劳动) / 1000 = 440‰；地主 = (700 × 800‰ + 300 × 0) / 1000 = 560‰
   * 贫农得 45,560,000 × 440 / 1000 = 20,046,400；地主得 25,513,600（Σ = 净产，无残差）
   * 贫农两天缺口由地主借出（同格借粮）：2 × 33,200 = 66,400 ⇒ 两条债务
   * 贫农库存 = 0 − 0 − 0 + 20,046,400 = 20,046,400
   * 地主库存 = 5,000,000 − 80,000 − 33,200 − 33,200 + 25,513,600 = 30,367,200
   * </pre>
   */
  @Test
  void eachClassRowDrawsItsOwnSeedSoTheDryRowLeavesItsLandFallow() {
    LinkedHashMap<ClassKey, ClassRow> rows = new LinkedHashMap<>();
    rows.put(PEASANT_KEY, peasantRow(0L, 200_000L)); // 200 亩、缸空
    rows.put(LANDLORD_KEY, landlordRow(5_000_000L, 800_000L)); // 800 亩、缸足
    LinkedHashMap<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, "农业", CYCLE_DAYS, Map.of(AssetKind.LAND, SEED_PER_MU)));
    EconomyData base = data(rows, industries);

    EconomyData next = EconomySettlement.settle(base, 0L, 2L);

    long sownMu = 800L;
    long net = sownMu * YIELD_PER_MU * EconomySettlement.MILLI_PER_GRAIN
        * (1000L - EconomySettlement.PRODUCTION_CONSUMPTION_PER_MILLE) / 1000L;
    assertThat(next.industries().get(FARM).cycleSeedUsedMilli())
        .as("只有地主扣到了种（80,000 = 800 亩 × 100）")
        .isEqualTo(sownMu * SEED_PER_MU);
    assertThat(grainOf(next, PEASANT_KEY))
        .as("贫农：0 − 0 + 净产的 440‰（它的地荒着，但分配仍按土地权重——见\"已知约束\"）")
        .isEqualTo(net * 440L / 1000L);
    assertThat(grainOf(next, LANDLORD_KEY))
        .as("地主：5,000,000 − 80,000 − 2 × 33,200（借给贫农）+ 净产的 560‰")
        .isEqualTo(5_000_000L - sownMu * SEED_PER_MU - 2L * DAILY_NEED + net * 560L / 1000L);
    assertThat(next.classes().get(PEASANT_KEY).debts()).as("贫农两天各借一条").hasSize(2);
    assertThat(next.debts().values())
        .allSatisfy(debt -> assertThat(debt.principal()).as("每天借的量 = 当日缺口").isEqualTo(DAILY_NEED));
  }

  /**
   * ★★ **验收判据 4 逐值**：缸一直是空的 ⇒ 每个周期的播种日都扣不到 ⇒ **每个周期都颗粒无收** ⇒ 人越死越少
   * （"冬春吃空缸 ⇒ 播种日扣不到 ⇒ 减产"的多周期形态；判别力：删掉第三路瓶颈 ⇒ 第 1 周期就满产 22,780,000，
   * 缸被填上、没人饿死 ⇒ 本条全红）。
   *
   * <pre>
   * 第 1 周期（第 1~2 天）：播 0、吃 0 ⇒ 缺口 66,400 = 全额需求 ⇒ faminePerMille = 1000‰
   *     死 400 × 1000/1000 × 200/1000 = 80 ⇒ 人口 320、劳动 232,000 × 320/400 = 185,600
   *     收获 = 0（seedCapMu = 0）
   * 第 2 周期（第 3~4 天）：缸仍空 ⇒ 播 0、吃 0 ⇒ 缺口 = 2 × 320 × 83 = 53,120 = 全额需求 ⇒ 1000‰
   *     死 320 × 200/1000 = 64 ⇒ 人口 256、劳动 185,600 × 256/320 = 148,480；收获仍 = 0
   * </pre>
   */
  @Test
  void theEmptySpringJarMakesTheNextSowingFailAndTheHarvestCollapse() {
    EconomyData base = farm(0L, Map.of(AssetKind.LAND, SEED_PER_MU));

    EconomyData next = EconomySettlement.settle(base, 0L, 4L);

    ClassRow row = next.classes().get(PEASANT_KEY);
    FlowRow flow = next.flows().get(PEASANT_KEY);
    assertThat(next.industries().get(FARM).cycleSeedUsedMilli()).as("第 2 周期的播种日同样扣不到").isZero();
    assertThat(grainOf(next, PEASANT_KEY)).as("两个周期都颗粒无收").isZero();
    assertThat(flow.unmetNeed()).as("第 2 周期缺口 = 2 × 320 × 83").isEqualTo(2L * 320L * 83L);
    assertThat(flow.deaths()).as("累计饿死 = 80 + 64").isEqualTo(80L + 64L);
    assertThat(row.population()).as("400 → 320 → 256").isEqualTo(256L);
    assertThat(row.laborMilli()).as("劳动同比例缩：185,600 × 256/320").isEqualTo(148_480L);
    assertThat(next.meta().orElseThrow().lastClosedCycle()).as("两个周期都关过账").hasValue(2L);
  }

  /**
   * ★★ **账要平**（spec §6.1 / `EconomySettlementEndToEndTest.assertConserved` 的纯函数版）：
   * 种子扣减计入 {@code consumed} 之后，{@code Σ库存减少 == Σ消费 − Σ所得} 在**配了种子**的世界上仍成立。
   *
   * <p>★ 判别力：把播种步里的 {@code consumedGrain.merge(key, drawn, Long::sum)} 删掉 ⇒ 左边少了种子那一笔
   * （20,000）⇒ 本条必红。
   */
  @Test
  void theLedgerStaysBalancedEvenWithSeedDraws() {
    EconomyData base = farm(20_000L, Map.of(AssetKind.LAND, SEED_PER_MU));
    EconomyData next = EconomySettlement.settle(base, 0L, 2L);

    long stockBefore = grainOf(base, PEASANT_KEY);
    long stockAfter = grainOf(next, PEASANT_KEY);
    FlowRow flow = next.flows().get(PEASANT_KEY);
    long consumed = flow.consumed().getOrDefault(GRAIN, 0L);

    assertThat(consumed).as("消费里含种子 20,000（留种是本期消费）").isGreaterThanOrEqualTo(20_000L);
    assertThat(stockBefore - stockAfter)
        .as("Σ库存减少 == Σ消费 − Σ所得（毛产口径）")
        .isEqualTo(consumed - flow.income());
  }
```

- [ ] **Step 2: 跑测试确认三条都绿（本任务不含实现改动）**

Run: `./mvnw -q -Dtest=EconomySowingTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`
Expected: PASS（**核对 surefire 报告 mtime 落在本轮**）。
★ 若 `theEmptySpringJarMakesTheNextSowingFailAndTheHarvestCollapse` 的人口/劳动对不上：**先核算式**（饿死比例的
分母是"本周期总需求"、`deaths` 逐周期累加、劳动按人口比例缩），再决定是改期望值还是报给控制器。

- [ ] **Step 3: 变异自证（两条）**

1. **"各扣各的"不许退化成"从全格池子扣"**：把 `sowIfCycleStart` 改成按**行业**汇总一次扣（
   `totalNeed = Σ rowLandMu × seedPerMu`，从同格**任一有粮的行**扣）⇒ 重跑 ⇒
   `eachClassRowDrawsItsOwnSeedSoTheDryRowLeavesItsLandFallow` **必须红**（贫农会被地主的粮种上 ⇒ 累加器 100,000 ≠ 80,000、
   库存与收获全变）。还原 ⇒ 回绿。
2. **留种必须入账**：删掉 `consumedGrain.merge(key, drawn, Long::sum);` ⇒ 重跑 ⇒
   `theLedgerStaysBalancedEvenWithSeedDraws` **必须红**。还原 ⇒ 回绿。

- [ ] **Step 4: 提交**

```bash
git add simos-economy/src/test/java/io/mosire/simos/economy/time/EconomySowingTest.java
git commit -m "test(economy): V3 验收链逐值 —— 冬春吃空缸 ⇒ 扣不到 ⇒ 减产、各扣各的、账要平"
```
提交信息须含：三条用例的**实测数字**（累加器 80,000 / 人口 400→320→256 / 死 144）与算式。

---

### Task 6: 播种器显式留白 + 真档行为恒等（端到端零改字面量）+ 关账

**Files:**
- Modify: `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`
- Modify: `simos-app/src/test/java/io/mosire/simos/app/world/EconomySeederTest.java`

**Interfaces:**
- Consumes: `EconomySeedHandler` 的载荷键（任务 2）
- Produces: 载荷里显式的 `"cycleInputPerUnit": {}` 与 `"cycleSeedUsedMilli": 0`（**留白 + 接缝**）

★ **为什么留白**（"偏离 ①"的完整理由）：全仓没有任何"每亩需种"的数；spec §4.1/§4.7 把它列在**制度层参数**、
归属 **V7 参数目录**；本仓纪律是"没有依据就置 0，不臆造"。而一旦给真档配一个拍出来的种子率，
plan-1 刚关账的十几条端到端字面量会一起失效——那些数字是**标定**出来的，不是随手写的。

- [ ] **Step 1: 播种器显式写出留白（+注释点名接缝）**

`EconomySeeder.industry(...)` 里，`outputPerUnit` 与 `cycleLaborMilli` 之间插：
```java
    industry.put("outputPerUnit", Map.of(COMMODITY_GRAIN, GRAIN_OUTPUT_PER_MU));
    // ★★ 一次性投入（v2 spec §3.3：播种日现扣的种子）：**V3 显式留白** —— 全仓与全部文档里
    //   没有任何"每亩需种"的数，spec §4.1/§4.7 把它列在**制度层参数**、归属 V7 的参数目录。
    //   故此处与 dailyInputPerUnit 同制：**不臆造**（空 map ⇒ 播种步一字不扣 ⇒ 与 V2 逐值一致；
    //   这条恒等由 EconomySeederTest.theSeederLeavesTheSeedRateUnsetOnPurpose 与
    //   EconomySettlementEndToEndTest 的全部字面量共同钉住）。
    //   ★ V7 落地后：由参数表供给（作用域 全局→国家→格/产业，spec §4.2），本行改读参数。
    industry.put("cycleInputPerUnit", Map.of());
    // ★ R3a：周期累计实际劳动——创世 = 0（新周期尚未投入；日结算每天累加）。
    industry.put("cycleLaborMilli", 0);
    // ★ V3：本周期实际扣到的种子（毫粮）——创世 = 0（与 cycleLaborMilli 同形制）。
    industry.put("cycleSeedUsedMilli", 0);
```
★ 两处插入**保持字段插入序的语义分组**（与 `Industry` 的组件顺序一致：`outputPerUnit → cycleInputPerUnit`、
`cycleLaborMilli → cycleSeedUsedMilli`），载荷逐字节可复现的性质不变。

- [ ] **Step 2: 写守卫用例（把留白钉住，别让谁静默配上一个种子率）**

在 `EconomySeederTest.java` 追加：
```java
  /**
   * ★★ **播种器显式不配种子**（V3 的留白）：`cycleInputPerUnit` 是空 map、`cycleSeedUsedMilli` 是 0。
   *
   * <p>★ 这条**不是**"顺手多写一条断言"：全仓没有任何"每亩需种"的依据（spec §4.1/§4.7 把它列在制度层参数、
   * 归属 V7 的参数目录），本仓纪律是"没依据就置 0，不臆造"。而 `EconomySettlementEndToEndTest` 的全部字面量
   * （176,545 粮/格、饿死 90 人、等价性…）**都以此为前提**：一旦这里出现非零种子率，那些数字同时失效。
   * ⇒ 将来谁要配种子，必须**同时**重标定那些字面量，这条用例就是那个提醒。
   */
  @Test
  void theSeederLeavesTheSeedRateUnsetOnPurpose() throws Exception {
    JsonNode farm = entry(payload(), 0, 0).get("industries").get(0);

    assertThat(farm.get("cycleInputPerUnit")).as("V3 显式留白：空 map（V7 参数目录落地后才由参数供给）").isEmpty();
    assertThat(farm.get("cycleSeedUsedMilli").asLong()).as("创世时未投入任何种子").isZero();
  }
```

- [ ] **Step 3: 跑 app 侧全量（★ 端到端字面量必须一个都没改）**

Run: `./mvnw -q test -pl simos-app -am`
Expected: 全绿，且 `git diff` 里 `EconomySettlementEndToEndTest.java` / `WorldgenInitializeToolTest.java`
**没有任何改动**。★ 这就是"未配种子的产业 ⇒ V3 对真档行为恒等"的端到端证据（也是任务 3/4 的回归网）。
★ 若这里红了：**先怀疑实现，不怀疑夹具**——未配种子时第三路不许施加约束、播种步不许扣任何东西。

- [ ] **Step 4: 变异自证（证明留白那条守卫有判别力）**

临时给播种器写上 `industry.put("cycleInputPerUnit", Map.of("LAND", GRAIN_OUTPUT_PER_MU));`（= 每亩留种 67 粮，
纯属模拟"有人静默配上了一个数"）⇒ 重跑 `./mvnw -q test -pl simos-app -am` ⇒ **必须红**：
`theSeederLeavesTheSeedRateUnsetOnPurpose` 红，且 `EconomySettlementEndToEndTest` 的字面量一起红
（那些数字的前提被打破）—— 两处红**同时出现**才是这条留白真正的判别力。
★ 验完 `git checkout -- simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`（**按字节还原**），重跑确认回绿。

- [ ] **Step 5: 提交**

```bash
git add simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java \
        simos-app/src/test/java/io/mosire/simos/app/world/EconomySeederTest.java
git commit -m "feat(app): 播种器显式留白 cycleInputPerUnit（V3 不臆造每亩需种；V7 由参数目录供给）"
```

---

## 收尾：本计划的关账（照 plan-1 的口径）

- [ ] `pgrep -af "surefirebooter|classworlds.launcher"` ⇒ 没有别的 Maven 在跑
- [ ] `./mvnw clean verify`（**前台**）⇒ 11 个模块全绿，Spotless / Checkstyle / SpotBugs / Surefire / 前端门禁都过
- [ ] ★ 逐条核对 V3 的四条判据**都有对应的用例**（在提交信息里点名用例与方法名）：
  1. `cycleInputPerUnit` 进 codec 往返 ⇒ 任务 1（`EconomyCodecTest` 夹具带非空值 + 字节级往返）
  2. 播种日先扣种子 ⇒ 任务 3（`sowingDayDrawsTheSeedBeforeTheDayIsEaten`、`drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown`）
  3. 第三路瓶颈（缺种子 ⇒ 投入面积缩 ⇒ 减产）⇒ 任务 4（`aShortJarShrinksTheSownAreaAndCutsTheHarvest`）
  4. "冬春吃空缸 ⇒ 播种日扣不到 ⇒ 减产"逐值 ⇒ 任务 5（`theEmptySpringJarMakesTheNextSowingFailAndTheHarvestCollapse`）
- [ ] 更新 `.superpowers/sdd/2026-09-25-aggregate-economy/progress.md`：补 V3 一行（含**实际数字**与证据路径：
  播种 40,000 / seedCap 200 亩 / 11,390,000 / 人口 400→320→256）
- [ ] ★ **把两条"已知约束"追加进 spec 的 §10.2 表**（**追加**，不篡改历史行，AGENT.md §五.4）：
  1. **分配按土地占比而非实际播种占比**：干缸行的地荒着，它仍按土地权重分到一份产出（`harvest` 的 `weights`）。
     v1 的 `plantedMu` 是产业级单一数字，没有逐行播种归属；引入它属设计变更，留给 V5/V6 的评审。
  2. **真档不配种子**（V3 留白）：`EconomySeeder` 写空 `cycleInputPerUnit` ⇒ 播种步对真档恒等；
     "每亩需种"随 V7 参数目录落地，届时需**重新标定**端到端字面量。
