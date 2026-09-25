package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
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
  private static final ClassSlotId PEASANT = new ClassSlotId("peasant");
  private static final ClassSlotId LANDLORD = new ClassSlotId("landlord");
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
                    Optional.of(meta()), Map.of(), Map.of(PEASANT_KEY, row), Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ClassRow.key");
  }

  /** ★ 不变量 3 的流水侧（§3.3）：{@code flows} 的键必须与 {@code FlowRow.key} 一致。 */
  @Test
  void rejectsFlowsKeyNotMatchingRowKey() {
    FlowRow row = new FlowRow(LANDLORD_KEY, 10L, Map.of(), 0L, 0L, 0L, 0L, 10L, 0L, 0L);
    assertThatThrownBy(
            () ->
                new EconomyData(
                    Optional.of(meta()), Map.of(), Map.of(), Map.of(), Map.of(PEASANT_KEY, row)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FlowRow.key");
  }

  /** ★ 不变量（2026-09-25 新增字段）：未满足需求与饿死数都不得为负（存量非负口径的流水侧）。 */
  @Test
  void rejectsNegativeUnmetNeedOrDeaths() {
    assertThatThrownBy(() -> new FlowRow(PEASANT_KEY, 0L, Map.of(), 0L, 0L, 0L, 0L, 0L, -1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unmetNeed");
    assertThatThrownBy(() -> new FlowRow(PEASANT_KEY, 0L, Map.of(), 0L, 0L, 0L, 0L, 0L, 0L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deaths");
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
        Optional.of(meta()), Map.of(FARM, industry), Map.of(PEASANT_KEY, row), Map.of(), Map.of());
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
            Map.of());
    assertThat(data.debts()).as("两端都在的债务必须放行").hasSize(1);
  }

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

  private static Industry industryWithSlots(List<ClassSlot> slots) {
    return new Industry(
        FARM,
        "农业",
        new RegimeId("tenant"),
        120L,
        0L,
        Map.of(),
        500L,
        Map.of(GRAIN, 7L),
        Map.of(),
        slots,
        new AllocationRule.Split(700, 300),
        0L,
        0L);
  }

  private static Industry industryWithProgress(long progress, long cycleDays) {
    return new Industry(
        FARM,
        "农业",
        new RegimeId("tenant"),
        cycleDays,
        progress,
        Map.of(),
        500L,
        Map.of(GRAIN, 7L),
        Map.of(),
        List.of(new ClassSlot(PEASANT, "贫农", 1000)),
        new AllocationRule.Split(700, 300),
        0L,
        0L);
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
}
