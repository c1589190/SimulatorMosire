package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
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
        slots,
        new AllocationRule.Split(700, 300),
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
        List.of(new ClassSlot(PEASANT, "贫农", 1000)),
        new AllocationRule.Split(700, 300),
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
