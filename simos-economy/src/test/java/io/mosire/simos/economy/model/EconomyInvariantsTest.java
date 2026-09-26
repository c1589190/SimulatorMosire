package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * 经济模型的**构造期不变量**（新经济设计 §6 里构造期可判的那些，逐条落成用例）。
 *
 * <p>每条都断言**消息里的字段名/关键值**：消息不点名的话，将来某条不变量被误删，测试只会在"抛了"上转绿， 读不出是哪一条失守。
 */
class EconomyInvariantsTest {

  private static final IndustryId FARM = new IndustryId("farm");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final ClassKey PEASANT_KEY = new ClassKey(FARM, PEASANT);
  private static final ClassKey LANDLORD_KEY = new ClassKey(FARM, LANDLORD);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final DebtId D1 = new DebtId("debt-1");

  /** ★ 不变量 1（§6.3 的槽位侧，2026-09-25 修正后）：劳动投入率必须 ∈ [0, 1000]（0 = 不劳动者，允许）。 */
  @Test
  void rejectsLaborParticipationOutsideZeroToThousand() {
    assertThatThrownBy(() -> new ClassSlot(PEASANT, "贫农", -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("laborParticipationPerMille");
    assertThatThrownBy(() -> new ClassSlot(PEASANT, "贫农", 1001))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("laborParticipationPerMille");
  }

  /** ★ 不变量 2（修正后）：同一产业的槽位 **id 不得重复**（占比不再是槽位字段——人口比例由行派生）。 */
  @Test
  void rejectsDuplicateClassSlotIds() {
    assertThatThrownBy(
            () ->
                industryWithSlots(
                    List.of(
                        new ClassSlot(PEASANT, "贫农", 950), new ClassSlot(PEASANT, "贫农二号", 900))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id 不得重复");
  }

  /**
   * ★ 判别力对照（专门守着"占比之和 = 1000‰"这条**被删掉的**误解）：贫农 950‰ + 地主 100‰ 之和是 1050‰
   * ——它们是**劳动投入率**而不是人口占比，必须活得下来；若谁把旧检查加回来，这条当场红。
   */
  @Test
  void acceptsLaborParticipationRatesThatDoNotSumToOneThousand() {
    Industry industry =
        industryWithSlots(
            List.of(new ClassSlot(PEASANT, "贫农", 950), new ClassSlot(LANDLORD, "地主", 100)));
    assertThatThrownBy(() -> industry.slots().clear())
        .as("slots 保序不可变（冻在字段赋值处）")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★ 引用完整性（修正后新增）：阶层行不得悬空——产业必须存在。 */
  @Test
  void rejectsClassRowForAnUnknownIndustry() {
    assertThatThrownBy(
            () ->
                new EconomyData(
                    Optional.of(meta()),
                    Map.of(),
                    Map.of(PEASANT_KEY, classRow(PEASANT_KEY)),
                    Map.of(),
                    Map.of(),
                    // ★ R2 的两个新组件：本用例只谈阶层行的引用完整性 ⇒ 两张劳动表留空（合法状态）。
                    Map.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不存在的产业");
  }

  /** ★ 引用完整性（修正后新增）：阶层行的槽位必须在该产业的 slots 里（"凭空生成地主"的守卫）。 */
  @Test
  void rejectsClassRowWhoseSlotIsNotInItsIndustry() {
    assertThatThrownBy(
            () ->
                new EconomyData(
                    Optional.of(meta()),
                    Map.of(FARM, industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950)))),
                    Map.of(LANDLORD_KEY, classRow(LANDLORD_KEY)),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未允许的阶层槽位");
  }

  /** ★ 不变量 3（§3.2）：{@code classes} 的键必须与 {@code ClassRow.key} 一致。 */
  @Test
  void rejectsClassesKeyNotMatchingRowKey() {
    ClassRow row = classRow(LANDLORD_KEY);
    assertThatThrownBy(
            () ->
                new EconomyData(
                    Optional.of(meta()),
                    Map.of(),
                    Map.of(PEASANT_KEY, row),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ClassRow.key");
  }

  /** ★ 不变量 3 的流水侧（§3.3）：{@code flows} 的键必须与 {@code FlowRow.key} 一致。 */
  @Test
  void rejectsFlowsKeyNotMatchingRowKey() {
    FlowRow row =
        new FlowRow(LANDLORD_KEY, Map.of(), Map.of(), 0L, 0L, 0L, 0L, 10L, Map.of(), 0L, 0L);
    assertThatThrownBy(
            () ->
                new EconomyData(
                    Optional.of(meta()),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(PEASANT_KEY, row),
                    Map.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FlowRow.key");
  }

  /**
   * ★ 不变量（2026-09-25 新增字段；**R4 起 unmetNeed 逐商品**）：未满足需求（逐值）与饿死数都不得为负（存量非负口径的流水侧）。
   *
   * <p>★ R4 补第三条：**出生也不得为负**（与死亡对称的那一项）。
   */
  @Test
  void rejectsNegativeUnmetNeedOrDeaths() {
    assertThatThrownBy(
            () ->
                new FlowRow(
                    PEASANT_KEY,
                    Map.of(),
                    Map.of(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    Map.of(GRAIN, -1L),
                    0L,
                    0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unmetNeed");
    assertThatThrownBy(
            () ->
                new FlowRow(PEASANT_KEY, Map.of(), Map.of(), 0L, 0L, 0L, 0L, 0L, Map.of(), -1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deaths");
    assertThatThrownBy(
            () ->
                new FlowRow(PEASANT_KEY, Map.of(), Map.of(), 0L, 0L, 0L, 0L, 0L, Map.of(), 0L, -1L))
        .as("★ R4：出生与死亡对称 ⇒ 两侧都不许为负")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("births");
  }

  /**
   * ★★ **R3：{@code income} 逐商品**（原来是一个标量）—— 它与 {@code consumed} 对称，两条守卫逐一对应。
   *
   * <p>判别力：把 {@code income} 退回标量 ⇒ 本用例的构造器直接编译不过（形状层面的判别力）；把"逐值 ≥ 0"的守卫删掉 ⇒ 本用例红。
   */
  @Test
  void rejectsNegativeIncomeQuantity() {
    assertThatThrownBy(
            () ->
                new FlowRow(
                    PEASANT_KEY,
                    Map.of(GRAIN, -1L),
                    Map.of(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    Map.of(),
                    0L,
                    0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("income");
    assertThatThrownBy(
            () -> new FlowRow(PEASANT_KEY, null, Map.of(), 0L, 0L, 0L, 0L, 0L, Map.of(), 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("income");
  }

  /** ★ 不变量 4（§6.3 的上界）：{@code participationPerMille ∈ [0, 1000]}。 */
  @Test
  void rejectsClassRowParticipationOutsideZeroToThousand() {
    assertThatThrownBy(() -> classRowWithParticipation(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("participationPerMille");
    assertThatThrownBy(() -> classRowWithParticipation(1001))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("participationPerMille");
  }

  /** ★ 不变量 5（§6.4）：人口/有效劳动/货币/库存/生产资料逐值、债务本金一律 ≥ 0。 */
  @Test
  void rejectsNegativeStockLoads() {
    assertThatThrownBy(() -> classRowWithPopulation(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("population");
    assertThatThrownBy(() -> classRowWithLabor(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("laborMilli");
    assertThatThrownBy(() -> classRowWithMoney(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("money");
    assertThatThrownBy(() -> classRowWithGoods(Map.of(GRAIN, -1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("goods");
    assertThatThrownBy(() -> classRowWithMeans(Map.of(AssetKind.LAND, -1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("meansOfProduction");
    assertThatThrownBy(() -> debtWithPrincipal(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("principal");
  }

  /** ★ 不变量 6（§5）：{@code AllocationRule.Split} 两权重之和必须恰为 1000。 */
  @Test
  void rejectsSplitWeightsNotSummingToOneThousand() {
    assertThatThrownBy(() -> new AllocationRule.Split(700, 200))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1000");
    assertThatThrownBy(() -> new AllocationRule.Split(-1, 1001))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("WeightPerMille");
  }

  /** §3.1"当前进度 0..cycleDays"：进度越界与零长周期都即抛。 */
  @Test
  void rejectsIndustryProgressOrCycleOutOfRange() {
    assertThatThrownBy(() -> industryWithProgress(121L, 120L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("progressDays");
    assertThatThrownBy(() -> industryWithProgress(0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycleDays");
  }

  /** §5 {@code WageFirst}：工资不得为负、企业主剩余不得为负。 */
  @Test
  void rejectsWageFirstWithNegativeWageOrResidual() {
    assertThatThrownBy(() -> new AllocationRule.WageFirst(-1L, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wagePerLaborMilli");
    assertThatThrownBy(() -> new AllocationRule.WageFirst(1L, Map.of(GRAIN, -1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ownerResidual");
  }

  // ── 参与率上限（v2 spec §八.1）────────────────────────────────────────────────────

  /**
   * ★ 参与率 1000‰ 的行 + 上限 950‰ 的槽位 ⇒ 必须构造期拒。
   *
   * <p>病灶：v1 只守了 `[0, 1000]` 两头，**中间那条 `≤ 槽位上限` 无人守** ⇒ 凭空造劳动。
   */
  @Test
  void participationPerMilleMustNotExceedItsSlotCeiling() {
    assertThatThrownBy(() -> economyWithParticipation(1000))
        .as("参与率超槽位上限必须在构造期拒（v2 spec §八.1）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("participationPerMille")
        .hasMessageContaining("槽位");
  }

  /** ★ 边界值：恰好等于上限 ⇒ 必须放行（把 `≤` 写成 `<` 同样是 bug）。 */
  @Test
  void participationPerMilleAtTheSlotCeilingIsAccepted() {
    assertThat(economyWithParticipation(950)).isNotNull();
  }

  /** 一个产业的槽位上限 950‰；行里放 `participationPerMille` ⇒ 造一份最小 {@link EconomyData}。 */
  private static EconomyData economyWithParticipation(int participationPerMille) {
    Industry industry = industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950)));
    // ★ 行内 debts 置空：引用完整性守卫（v2 spec §八.2）上线后，悬空的 D1 会让这个夹具本身非法。
    ClassRow row =
        new ClassRow(
            PEASANT_KEY,
            120L,
            60000L,
            participationPerMille,
            Map.of(AssetKind.LAND, 2700L),
            Map.of(GRAIN, 300L),
            50L,
            List.of(),
            Map.of(GRAIN, 40L),
            Map.of(GRAIN, 30L));
    return new EconomyData(
        Optional.of(meta()),
        Map.of(FARM, industry),
        Map.of(PEASANT_KEY, row),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
  }

  // ── 债务引用两端（v2 spec §八.2）────────────────────────────────────────────────────

  /** ★ 债务的 creditor 指向一个不在 `classes` 里的阶层行 ⇒ 必须构造期拒（v1 只查 null）。 */
  @Test
  void debtEndpointsMustExistInClasses() {
    Debt dangling =
        new Debt(D1, PEASANT_KEY, LANDLORD_KEY, Optional.of(GRAIN), 100L, 20, 3L, false);
    assertThatThrownBy(
            () ->
                new EconomyData(
                    Optional.of(meta()),
                    Map.of(FARM, industryWithTwoSlots()),
                    Map.of(PEASANT_KEY, classRowWithoutDebts(PEASANT_KEY)),
                    Map.of(D1, dangling), // ★ classes 里没有 LANDLORD_KEY ⇒ creditor 悬空
                    Map.of(),
                    Map.of(),
                    Map.of()))
        .as("债务的 debtor/creditor 必须在 classes 里存在（v2 spec §八.2）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("债务");
  }

  /** ★ 反向：`ClassRow.debts` 里的 id 指向不存在的债务 ⇒ 必须构造期拒。 */
  @Test
  void classRowDebtRefsMustExistInDebts() {
    assertThatThrownBy(
            () ->
                new EconomyData(
                    Optional.of(meta()),
                    Map.of(FARM, industryWithTwoSlots()),
                    Map.of(PEASANT_KEY, classRowWithDebtRef(D1)),
                    Map.of(), // ★ 债务表为空 ⇒ 行内引用的 D1 悬空
                    Map.of(),
                    Map.of(),
                    Map.of()))
        .as("ClassRow.debts 的每个 id 必须在 debts 表里存在（v2 spec §八.2）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("债务");
  }

  /** 对照：两端都在 ⇒ 必须放行（否则上面两条可能只是"一律拒"）。 */
  @Test
  void aWellFormedDebtIsAccepted() {
    Debt debt = new Debt(D1, PEASANT_KEY, LANDLORD_KEY, Optional.of(GRAIN), 100L, 20, 3L, false);
    EconomyData data =
        new EconomyData(
            Optional.of(meta()),
            Map.of(FARM, industryWithTwoSlots()),
            Map.of(
                PEASANT_KEY,
                classRowWithDebtRef(D1),
                LANDLORD_KEY,
                classRowWithoutDebts(LANDLORD_KEY)),
            Map.of(D1, debt),
            Map.of(),
            Map.of(),
            Map.of());
    assertThat(data.debts()).as("两端都在的债务必须放行").hasSize(1);
  }

  // ── 经营主体 operator（S1 阶段 3 spec §2.1 + 裁定 R4）──────────────────────────────

  /**
   * ★★ 第 16 个组件与**其余 15 个同口径**：null 即抛。
   *
   * <p>★ 判别力：把紧凑构造器里那条守卫删掉（或在 `Industry` 里做 `null ⇒ 按 regime 推导`）⇒ 本用例红。 **缺省推导只允许发生在载荷边缘**（裁定
   * D1）：那样 25 处构造点里任一处漏传都会**静默换成默认值、不崩**， 正是本仓最反对的形态。
   */
  @Test
  void rejectsNullOperator() {
    assertThatThrownBy(() -> industryWithOperator(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operator");
  }

  /**
   * ★★ **裁定 R4：`regime` 与 `operator` 之间没有不变量** —— "operator 不是标签"的结构性证据。
   *
   * <p>★ 依据 spec §2.4 原文：「同一个 `feudal` 可以有 A 格地租 30% / B 格五五分成 / C 格领主直营 ——
   * 制度可以渐变而不用先改产业类型」。加任何"一致性守卫"都会让那些差异**不可表达**，本条当场红。
   */
  @Test
  void theRegimeDoesNotConstrainTheOperator() {
    Industry industry = industryWithOperator(new ActorRef(ActorKind.ESTATE, "estate-7"));

    assertThat(industry.operator().kind()).as("制度是租佃、经营主体是庄园 ⇒ 照常构造").isEqualTo(ActorKind.ESTATE);
    assertThat(industry.operator())
        .as("★ 显式值原样留下（逐值），没有被 regime 重新推导")
        .isEqualTo(new ActorRef(ActorKind.ESTATE, "estate-7"));
    assertThat(industry.operator())
        .as("★ 判别力：它**不等于** tenant 档的默认值（HOUSEHOLD:farm）—— 否则「显式给了却仍按 regime 推」会假绿")
        .isNotEqualTo(tenantOperator());
  }

  // ── 一次性投入槽与种子累加器（v2 spec §3.3）────────────────────────────────────────

  /** ★ 空 map 的语义是"不用空 map"，null 是坏数据 ⇒ 构造期拒（与 dailyInputPerUnit 同制）。 */
  @Test
  void rejectsNullCycleInputMap() {
    assertThatThrownBy(() -> industryWithCycleInput(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycleInputPerUnit");
  }

  /** ★ 逐值 ≥ 0（§6.4 存量非负的下界）；R3 起值侧还有一层商品维度。 */
  @Test
  void rejectsNegativeCycleInputQuantity() {
    assertThatThrownBy(() -> industryWithCycleInput(Map.of(AssetKind.LAND, Map.of(GRAIN, -1L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycleInputPerUnit");
  }

  /** ★★ **六种键都允许**（spec §3.3：协议上不设限；R3 只让农业/织机/作坊真的读到它）。 */
  @Test
  void acceptsCycleInputForAllSixAssetKinds() {
    Map<AssetKind, Map<CommodityId, Long>> input = new LinkedHashMap<>();
    for (AssetKind kind : AssetKind.values()) {
      input.put(kind, Map.of(GRAIN, 1L));
    }
    Industry industry = industryWithCycleInput(input);

    assertThat(industry.cycleInputPerUnit())
        .as("形状不设限（读不读是结算的事）")
        .containsOnlyKeys(
            AssetKind.LAND,
            AssetKind.CATTLE,
            AssetKind.TOOL,
            AssetKind.WORKSHOP,
            AssetKind.MACHINE,
            AssetKind.SHIP);
  }

  /**
   * ★★ **投入的分类键不必同时是产能约束**（R3）：{@code cycleInputPerUnit} 的键是"这段投入挂在哪种生产资料上"， 而 {@code
   * capacityPerUnit} 回答"每 1 单位规模需要多少生产资料" —— 两件事。「工具的保养要耗粮」完全可以只出现在前者里。
   */
  @Test
  void acceptsCycleInputForAnAssetKindWithoutCapacity() {
    Industry industry =
        new Industry(
            FARM,
            "农业",
            new RegimeId("tenant"),
            120L,
            0L,
            Map.of(AssetKind.LAND, 1000L),
            Map.of(),
            0L,
            0L,
            Map.of(GRAIN, 7L),
            Map.of(AssetKind.SHIP, Map.of(GRAIN, 1L)), // ★ SHIP 不是产能约束，只是分类
            List.of(new ClassSlot(PEASANT, "贫农", 1000)),
            new AllocationRule.Split(700, 300),
            0L,
            Map.of(),
            tenantOperator());

    assertThat(industry.inputPerUnit()).as("每 1 单位规模的投入 = 各分类的合计").containsEntry(GRAIN, 1L);
  }

  /** ★★ **产能那一路不得为空**（R3）：它是"单位规模"的锚，没有它规模无上界。 */
  @Test
  void rejectsEmptyCapacityPerUnit() {
    assertThatThrownBy(
            () ->
                new Industry(
                    FARM,
                    "农业",
                    new RegimeId("tenant"),
                    120L,
                    0L,
                    Map.of(),
                    Map.of(),
                    0L,
                    0L,
                    Map.of(GRAIN, 7L),
                    Map.of(),
                    List.of(new ClassSlot(PEASANT, "贫农", 1000)),
                    new AllocationRule.Split(700, 300),
                    0L,
                    Map.of(),
                    tenantOperator()))
        .as("capacityPerUnit 不得为空")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capacityPerUnit");
  }

  /** ★★ **产能需求必须为正**（0 那一档恒无约束，它不是一个"约束"，是写错了）。 */
  @Test
  void rejectsNonPositiveCapacityRequirement() {
    assertThatThrownBy(
            () ->
                new Industry(
                    FARM,
                    "农业",
                    new RegimeId("tenant"),
                    120L,
                    0L,
                    Map.of(AssetKind.LAND, 0L),
                    Map.of(),
                    0L,
                    0L,
                    Map.of(GRAIN, 7L),
                    Map.of(),
                    List.of(new ClassSlot(PEASANT, "贫农", 1000)),
                    new AllocationRule.Split(700, 300),
                    0L,
                    Map.of(),
                    tenantOperator()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capacityPerUnit");
  }

  /** ★ 保序不可变（冻在字段赋值处；绝不用 Map.copyOf —— 迭代序不是内容的纯函数）。 */
  @Test
  void cycleInputKeepsInsertionOrderAndIsFrozen() {
    Map<AssetKind, Map<CommodityId, Long>> input = new LinkedHashMap<>();
    input.put(AssetKind.SHIP, Map.of(GRAIN, 5L));
    input.put(AssetKind.LAND, Map.of(GRAIN, 100L));
    Industry industry = industryWithCycleInput(input);

    assertThat(industry.cycleInputPerUnit().keySet())
        .as("插入序即迭代序（字节级往返的前提）")
        .containsExactly(AssetKind.SHIP, AssetKind.LAND);
    assertThatThrownBy(() -> industry.cycleInputPerUnit().clear())
        .as("冻在字段赋值处")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> industry.capacityPerUnit().clear())
        .as("capacityPerUnit 同样冻在字段赋值处")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★ `cycleInputUsedMilli` 形制同 `cycleLaborMilli`：逐商品、不得为负。 */
  @Test
  void rejectsNegativeCycleInputAccumulator() {
    assertThatThrownBy(() -> industryWithCycleState(Map.of(), Map.of(GRAIN, -1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycleInputUsedMilli");
  }

  /**
   * 一个产业的槽位上限 1000‰、`cycleInputPerUnit = cycleInput`、`cycleInputUsedMilli = 累加器`。
   *
   * <p>★ **产能表按投入表的键现推**（每键 1）：{@code cycleInputPerUnit} 的键必须落在 {@code capacityPerUnit} 里（构造期守卫），
   * 而本夹具测的是"投入表本身的形状"，故让产能表跟着它走 —— 不另写一份会漂的键集。
   */
  private static Industry industryWithCycleInput(
      Map<AssetKind, Map<CommodityId, Long>> cycleInput) {
    return industryWithCycleState(cycleInput, Map.of());
  }

  private static Industry industryWithCycleState(
      Map<AssetKind, Map<CommodityId, Long>> cycleInput, Map<CommodityId, Long> cycleInputUsed) {
    Map<AssetKind, Long> capacity = new LinkedHashMap<>();
    if (cycleInput != null) {
      for (AssetKind kind : cycleInput.keySet()) {
        capacity.put(kind, 1L);
      }
    }
    if (capacity.isEmpty()) {
      capacity.put(AssetKind.LAND, 1L); // capacityPerUnit 不得为空（R3 的构造期守卫）
    }
    if (cycleInput == null) {
      return new Industry(
          FARM,
          "农业",
          new RegimeId("tenant"),
          120L,
          0L,
          capacity,
          Map.of(),
          500L,
          0L,
          Map.of(GRAIN, 7L),
          null, // ★ 本用例测的就是"null ⇒ 拒"
          List.of(new ClassSlot(PEASANT, "贫农", 1000)),
          new AllocationRule.Split(700, 300),
          0L,
          cycleInputUsed,
          tenantOperator());
    }
    return new Industry(
        FARM,
        "农业",
        new RegimeId("tenant"),
        120L,
        0L,
        capacity,
        Map.of(),
        500L,
        0L,
        Map.of(GRAIN, 7L),
        cycleInput,
        List.of(new ClassSlot(PEASANT, "贫农", 1000)),
        new AllocationRule.Split(700, 300),
        0L,
        cycleInputUsed,
        tenantOperator());
  }

  private static Industry industryWithTwoSlots() {
    return industryWithSlots(
        List.of(new ClassSlot(PEASANT, "贫农", 1000), new ClassSlot(LANDLORD, "地主", 1000)));
  }

  /** 无债务的阶层行（参与率 800 在其槽位上限之内）；`key` 必须与它在 `classes` 里的键一致。 */
  private static ClassRow classRowWithoutDebts(ClassKey key) {
    return new ClassRow(
        key,
        120L,
        60000L,
        800,
        Map.of(AssetKind.LAND, 2700L),
        Map.of(GRAIN, 300L),
        50L,
        List.of(),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  /** 行内引用一份债务（其两端由调用方保证）。 */
  private static ClassRow classRowWithDebtRef(DebtId debtId) {
    return new ClassRow(
        PEASANT_KEY,
        120L,
        60000L,
        800,
        Map.of(AssetKind.LAND, 2700L),
        Map.of(GRAIN, 300L),
        50L,
        List.of(debtId),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  // ── 夹具 ──

  private static EconomyMeta meta() {
    return new EconomyMeta("m1", 0L, OptionalLong.empty(), "rules-r1", Optional.empty());
  }

  /**
   * 一个 {@code tenant} 档的产业（其余字段照 {@link #industryWithSlots}），**第 16 个实参由调用方给**。
   *
   * <p>★ 与 {@link #tenantOperator()} 分成两个入口是**故意的**：判据用例（{@link
   * #theRegimeDoesNotConstrainTheOperator}）由此能塞进**非默认**的 operator，而通用夹具一律走派生值 —— "漏传 ⇒
   * 重新推导"的变异体只在判据用例上现形。
   */
  private static Industry industryWithOperator(ActorRef operator) {
    return new Industry(
        FARM,
        "农业",
        new RegimeId("tenant"),
        120L,
        0L,
        Map.of(AssetKind.LAND, 1000L),
        Map.of(),
        500L,
        0L,
        Map.of(GRAIN, 7L),
        Map.of(),
        List.of(new ClassSlot(PEASANT, "贫农", 1000)),
        new AllocationRule.Split(700, 300),
        0L,
        Map.of(),
        operator);
  }

  /**
   * 通用夹具的 operator：**派生**（{@code tenant} ⇒ {@code HOUSEHOLD:farm}，regime 与 id 都取自本文件的 tenant 夹具）。
   *
   * <p>★ 走 {@link RegimeOperators#defaultOperator} 而不是在每处写字面量：默认值只有**一处拼写点**（裁定 R1/D3）。
   */
  private static ActorRef tenantOperator() {
    return RegimeOperators.defaultOperator(new RegimeId("tenant"), FARM);
  }

  private static Industry industryWithSlots(List<ClassSlot> slots) {
    return new Industry(
        FARM,
        "农业",
        new RegimeId("tenant"),
        120L,
        0L,
        Map.of(AssetKind.LAND, 1000L),
        Map.of(),
        500L,
        0L,
        Map.of(GRAIN, 7L),
        Map.of(),
        slots,
        new AllocationRule.Split(700, 300),
        0L,
        Map.of(),
        tenantOperator());
  }

  private static Industry industryWithProgress(long progress, long cycleDays) {
    return new Industry(
        FARM,
        "农业",
        new RegimeId("tenant"),
        cycleDays,
        progress,
        Map.of(AssetKind.LAND, 1000L),
        Map.of(),
        500L,
        0L,
        Map.of(GRAIN, 7L),
        Map.of(),
        List.of(new ClassSlot(PEASANT, "贫农", 1000)),
        new AllocationRule.Split(700, 300),
        0L,
        Map.of(),
        tenantOperator());
  }

  private static ClassRow classRow(ClassKey key) {
    return new ClassRow(
        key,
        120L,
        60000L,
        800,
        Map.of(AssetKind.LAND, 2700L),
        Map.of(GRAIN, 300L),
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  private static ClassRow classRowWithParticipation(int participationPerMille) {
    return new ClassRow(
        PEASANT_KEY,
        120L,
        60000L,
        participationPerMille,
        Map.of(AssetKind.LAND, 2700L),
        Map.of(GRAIN, 300L),
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  private static ClassRow classRowWithPopulation(long population) {
    return new ClassRow(
        PEASANT_KEY,
        population,
        60000L,
        800,
        Map.of(AssetKind.LAND, 2700L),
        Map.of(GRAIN, 300L),
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  private static ClassRow classRowWithLabor(long laborMilli) {
    return new ClassRow(
        PEASANT_KEY,
        120L,
        laborMilli,
        800,
        Map.of(AssetKind.LAND, 2700L),
        Map.of(GRAIN, 300L),
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  private static ClassRow classRowWithMoney(long money) {
    return new ClassRow(
        PEASANT_KEY,
        120L,
        60000L,
        800,
        Map.of(AssetKind.LAND, 2700L),
        Map.of(GRAIN, 300L),
        money,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  private static ClassRow classRowWithGoods(Map<CommodityId, Long> goods) {
    return new ClassRow(
        PEASANT_KEY,
        120L,
        60000L,
        800,
        Map.of(AssetKind.LAND, 2700L),
        goods,
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  private static ClassRow classRowWithMeans(Map<AssetKind, Long> meansOfProduction) {
    return new ClassRow(
        PEASANT_KEY,
        120L,
        60000L,
        800,
        meansOfProduction,
        Map.of(GRAIN, 300L),
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L));
  }

  private static Debt debtWithPrincipal(long principal) {
    return new Debt(D1, PEASANT_KEY, LANDLORD_KEY, Optional.of(GRAIN), principal, 20, 3L, false);
  }

  // ── R2：劳动供给与配额的三条结构判据（第三阶段设计稿 §四）────────────────────────────
  //
  // ★ 为什么这三条必须**构造期判**：劳动是"可分配但不能凭空重复"的资源（本轮的目标原话），
  //   而"同一批人被两个产业各算一次满额"正是设计稿 §一.2 实测出的空洞 —— 判在构造期 ⇒
  //   任何一条路径（命令、旧档读入、夹具、将来的协调器）都造不出"配额超过可支配劳动"的状态。

  /**
   * ★★ **本阶段最重要的不变量**：{@code Σ 同一批次的配额 ≤ 该批次的可用劳动}。
   *
   * <p>★ 判别力：把这条判据删掉（或把 {@code >} 写成 {@code >=}... 后者不红，此处只谈删）⇒ 本条转绿 ⇒ 红。 ★
   * 对照条在下面：**恰好用满**（取等号）必须放行 —— 否则这条判据会退化成"多加一条配额就拒"的粗暴规则。
   */
  @Test
  void rejectsAllocationsThatExceedTheGroupsAvailableLabor() {
    assertThatThrownBy(
            () ->
                economyWith(
                    laborSupply(LOT, 100_000L, 0L, 0L),
                    Map.of(
                        ALLOC_A,
                        allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ESTATE, 60_000L),
                        // ★ 60,000 + 60,000 = 120,000 > 可用 100,000 ⇒ 同一批人的劳动被算了两次满额
                        ALLOC_B,
                        allocation(ALLOC_B, LOT, FARM.value(), ActorKind.ESTATE, 60_000L))))
        .as("Σ 配额超过可用劳动必须构造期拒（设计稿 §四 的那条不变量）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过其可用劳动");
  }

  /** ★ 对照：两产业各 60,000 + 40,000 = 100,000 = 可用 ⇒ **恰好用满，放行**（配额本来就该能取满）。 */
  @Test
  void acceptsAllocationsThatExactlyUseUpTheAvailableLabor() {
    EconomyData data =
        economyWith(
            laborSupply(LOT, 100_000L, 0L, 0L),
            Map.of(
                ALLOC_A,
                allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ESTATE, 60_000L),
                ALLOC_B,
                allocation(ALLOC_B, LOT, OTHER_FARM.value(), ActorKind.ESTATE, 40_000L)));

    assertThat(data.allocations()).as("同一批次供给两个产业：结构上成立").hasSize(2);
  }

  /**
   * ★ **两项扣除也进上限**：已服役 30,000 + 已承诺 20,000 ⇒ 可用 = 100,000 − 50,000 = 50,000 ⇒ 60,000 的配额必须拒（判别力：若把
   * {@code availableLabor()} 写成"直接返回毛额"，本条转绿 ⇒ 红）。
   */
  @Test
  void theCapCountsServedAndCommittedOutOfTheGrossLabor() {
    assertThatThrownBy(
            () ->
                economyWith(
                    laborSupply(LOT, 100_000L, 30_000L, 20_000L),
                    Map.of(
                        ALLOC_A,
                        allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ESTATE, 60_000L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过其可用劳动");
    // ★ 同一份供给，配额降到 50,000（= 可用）⇒ 放行
    assertThat(
            economyWith(
                    laborSupply(LOT, 100_000L, 30_000L, 20_000L),
                    Map.of(
                        ALLOC_A, allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ESTATE, 50_000L)))
                .allocations())
        .hasSize(1);
  }

  /** ★ **没有供给记录的配额没有上限** ⇒ 构造期拒（判别力：删掉这条判据 ⇒ 那条 60,000 的配额静默入库、谁也算不出它超没超）。 */
  @Test
  void rejectsAnAllocationWhoseGroupHasNoSupplyRecord() {
    assertThatThrownBy(
            () ->
                economyWith(
                    Map.of(),
                    Map.of(ALLOC_A, allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ESTATE, 1L))))
        .as("配额必须有一份同期的供给记录")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有劳动供给记录");
  }

  /** ★ **同期**才算有供给：配额在第 1 周期、供给写在第 2 周期 ⇒ 拒（否则"按周期发配额"这条口径就有后门）。 */
  @Test
  void rejectsAnAllocationWhoseSupplyIsFromAnotherPeriod() {
    assertThatThrownBy(
            () ->
                economyWith(
                    Map.of(LOT, new LaborSupply(LOT, 2L, 100_000L, 0L, 0L)),
                    Map.of(ALLOC_A, allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ESTATE, 1L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有劳动供给记录");
  }

  /**
   * ★★ **actor ↔ 产业 的对应关系是双条件的**（结算按 {@code actor.id()} 把配额归给产业）：产业型主体（庄园/作坊）
   * 必须指名**已存在**的产业；**其余非产业型主体**的 id **不得**与任何产业 id 撞名。
   *
   * <p>★ 判别力：两条各自挡一种错 ——
   *
   * <ul>
   *   <li>拼错产业 id（{@code famr@0_0}）⇒ 当日劳动静默变 0（不报错、只少产）⇒ 第一条；
   *   <li>{@link ActorKind#ORGANIZATION} 的 id 恰好等于某产业 id ⇒ 它的配额被静默算进那个产业 ⇒ 第二条。
   * </ul>
   *
   * <p>★★ **R3 起这一条改由 {@code ORGANIZATION} 承担，不再由 {@code HOUSEHOLD}**：农村家庭纺织是"家户自己承担的一个生产过程" （spec
   * §四）⇒ 家户的 actor id **可以**命名一个产业（那时它的配额照进该产业的 {@code cycleLaborMilli}）—— 那是**有意为之**， 不再是"撞名"。★
   * 于是"撞名"这条判据必须在**其余**非产业型种类上继续被钉住（{@link #acceptsAHouseholdActorOwningAnIndustry}）。
   */
  @Test
  void rejectsActorsThatDoNotMatchAnExistingIndustry() {
    assertThatThrownBy(
            () ->
                economyWith(
                    laborSupply(LOT, 100_000L, 0L, 0L),
                    Map.of(ALLOC_A, allocation(ALLOC_A, LOT, "famr@0_0", ActorKind.ESTATE, 1L))))
        .as("产业型主体的 id 必须是已存在的产业")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("对应关系不成立");
    assertThatThrownBy(
            () ->
                economyWith(
                    laborSupply(LOT, 100_000L, 0L, 0L),
                    Map.of(
                        ALLOC_A,
                        allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ORGANIZATION, 1L))))
        .as("非产业型主体（此处 = 组织）的 id 不得与产业 id 撞名（撞名 ⇒ 它的配额被算进那个产业）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("对应关系不成立");
  }

  /** ★★ **R3：家户可以"拥有"一个生产过程**（农村家庭纺织）⇒ 它的 actor id 命名一个产业时**放行**，且配额进该产业的劳动投入。 */
  @Test
  void acceptsAHouseholdActorOwningAnIndustry() {
    EconomyData data =
        economyWith(
            laborSupply(LOT, 100_000L, 0L, 0L),
            Map.of(ALLOC_A, allocation(ALLOC_A, LOT, FARM.value(), ActorKind.HOUSEHOLD, 40_000L)));

    assertThat(data.allocations()).as("家户的生产过程（家户织布）放行").hasSize(1);
  }

  /** ★ 家户（非产业型）的 id 与产业 id 不同 ⇒ 放行：它进守恒与读口，但**不占任何产业的劳动投入**。 */
  @Test
  void acceptsAHouseholdActorWhoseIdIsNotAnIndustry() {
    EconomyData data =
        economyWith(
            laborSupply(LOT, 100_000L, 0L, 0L),
            Map.of(ALLOC_A, allocation(ALLOC_A, LOT, "0_0", ActorKind.HOUSEHOLD, 40_000L)));

    assertThat(data.allocations()).hasSize(1);
  }

  /** ★ 两张新表的键必须与行内身份一致（与 {@code classes}/{@code flows} 同款的不变量）。 */
  @Test
  void rejectsLaborTableKeysThatDoNotMatchTheirRows() {
    assertThatThrownBy(
            () ->
                economyWith(
                    Map.of(
                        new PeopleLotId("rural:0_0:MALE:1"),
                        // ★ 行内的 group 是另一个 id ⇒ 键与值不一致
                        new LaborSupply(new PeopleLotId("rural:0_0:FEMALE:1"), 1L, 1L, 0L, 0L)),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LaborSupply.group");
    assertThatThrownBy(
            () ->
                economyWith(
                    laborSupply(LOT, 100_000L, 0L, 0L),
                    Map.of(
                        new LaborAllocationId("alloc-别的"),
                        allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ESTATE, 1L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LaborAllocation.id");
  }

  // ── R2 的夹具（本类自足）──────────────────────────────────────────────────────────

  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final LaborAllocationId ALLOC_A = new LaborAllocationId("alloc-a");
  private static final LaborAllocationId ALLOC_B = new LaborAllocationId("alloc-b");
  private static final IndustryId OTHER_FARM = new IndustryId("farm@0_0");

  /** 一份最小的经济：两个产业（{@code farm} 与 {@code farm@0_0}）+ 给定的两张劳动表。 */
  private static EconomyData economyWith(
      Map<PeopleLotId, LaborSupply> laborSupply,
      Map<LaborAllocationId, LaborAllocation> allocations) {
    return new EconomyData(
        Optional.of(meta()),
        Map.of(
            FARM,
            industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950))),
            OTHER_FARM,
            industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950)))),
        Map.of(),
        Map.of(),
        Map.of(),
        laborSupply,
        allocations);
  }

  private static Map<PeopleLotId, LaborSupply> laborSupply(
      PeopleLotId group, long gross, long served, long committed) {
    return Map.of(group, new LaborSupply(group, 1L, gross, served, committed));
  }

  /** 一条配额（第 1 周期、活动名 {@code farm}）。 */
  private static LaborAllocation allocation(
      LaborAllocationId id, PeopleLotId group, String actorId, ActorKind kind, long laborMilli) {
    return new LaborAllocation(id, group, new ActorRef(kind, actorId), "farm", laborMilli, 1L);
  }
}
