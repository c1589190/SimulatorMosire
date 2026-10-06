package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 经济模型的**构造期不变量**（新经济设计 §6 里构造期可判的那些，逐条落成用例）。
 *
 * <p>每条都断言**消息里的字段名/关键值**：消息不点名的话，将来某条不变量被误删，测试只会在"抛了"上转绿， 读不出是哪一条失守。
 */
class EconomyInvariantsTest {

  /** 本夹具的格（H0：家户键 = 格 + 居住类型 + 阶层；这里只有一格）。 */
  private static final HexCoord HEX = new HexCoord(0, 0);

  private static final IndustryId FARM = new IndustryId("farm");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final CohortKey PEASANT_KEY = new CohortKey(HEX, ResidenceKind.RURAL, PEASANT);
  private static final CohortKey LANDLORD_KEY = new CohortKey(HEX, ResidenceKind.RURAL, LANDLORD);

  /** ★ S1：{@code classes}/{@code flows} 的键 = 家户稳定身份（旧 {@link CohortKey} 由 ofLegacy 迁移）。 */
  private static final HouseholdId PEASANT_HOUSE = HouseholdIds.ofLegacy(PEASANT_KEY);

  private static final HouseholdId LANDLORD_HOUSE = HouseholdIds.ofLegacy(LANDLORD_KEY);
  private static final CommodityId GRAIN = new CommodityId("grain");

  /** ★ E4a：粮债的默认条款与两个不同条款的连续合同身份（D1/D2 供“债务表是权威”的对照用例）。 */
  private static final DebtTerms GRAIN_TERMS = DebtTerms.legacyDefault();

  private static final DebtTerms GRAIN_SECOND_TERMS =
      DebtTerms.legacyDefault(DebtTerms.LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE + 1);

  private static final DebtContractId D1 =
      DebtContractId.idOf(PEASANT_HOUSE, LANDLORD_HOUSE, DebtUnit.commodity(GRAIN), GRAIN_TERMS);

  private static final DebtContractId D2 =
      DebtContractId.idOf(
          PEASANT_HOUSE, LANDLORD_HOUSE, DebtUnit.commodity(GRAIN), GRAIN_SECOND_TERMS);

  /** R4：经营主体住在 {@code ProductionProcess}（不再是 {@code Industry} 的模板字段）。 */
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ORGANIZATION, "estate-7");

  /** R3B.2：{@code ProductionUnitId.idOf(industry, operator)} 是 unit 身份的唯一拼写点。 */
  private static final ProductionUnitId UNIT = ProductionUnitId.idOf(FARM, ESTATE);

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

  /**
   * ★★ R4-B.4 改写（原 {@code rejectsClassRowForAnUnknownIndustry}）：**classes 行不再要求该格登记过产业**。
   *
   * <p>R4-B.4 起该守卫已删除，见 B4-report §1.2/§1.3（{@code EconomyData} 在 classes 侧的 {@code
   * requireStratumAllowed} 调用随"view 必须命中 Industry.slots"整体退场）。当前契约：classes 是独立的家户账，
   * 关系/行可以比产业先到（逐组件增量落盘）；本用例钉住"没有产业也放行"这一新契约 —— 谁把旧守卫加回来，这里当场红。
   */
  @Test
  void classRowWithoutAnyRegisteredIndustryIsAccepted() {
    EconomyData data =
        EconomyData.empty()
            .withMeta(Optional.of(meta()))
            .withHouseholdEconomies(Map.of(PEASANT_HOUSE, classRow(PEASANT_HOUSE, PEASANT_KEY)));

    assertThat(data.classes()).containsOnlyKeys(PEASANT_HOUSE);
  }

  /**
   * ★★ R4-B.4 改写（原 {@code rejectsClassRowWhoseSlotIsNotInItsIndustry}）：**{@code
   * HouseholdEconomy.view} 不再受 {@code Industry.slots} 约束**（旧守卫：view 必须命中 slots 且 {@code
   * participationPerMille ≤ slot 上限}）。
   *
   * <p>R4-B.4 起该守卫已删除，见 B4-report §1.3 的"删掉的守卫"第 1/2 条。当前契约：{@code Industry.slots} 只作
   * 生产方式内部的角色/劳动配置，家户阶层由 {@code HouseholdClassRule} 纯派生。本用例让地主 view 落在一个只有贫农槽位的 产业上，必须构造得出来 ——
   * 旧守卫一旦回来，这里当场红。
   */
  @Test
  void classRowViewIsNoLongerConstrainedByIndustrySlots() {
    EconomyData data =
        EconomyData.empty()
            .withMeta(Optional.of(meta()))
            .withIndustries(
                Map.of(FARM, industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950)))))
            .withHouseholdEconomies(Map.of(LANDLORD_HOUSE, classRow(LANDLORD_HOUSE, LANDLORD_KEY)));

    assertThat(data.classes()).containsOnlyKeys(LANDLORD_HOUSE);
  }

  /** ★ 不变量 3（§3.2）：{@code classes} 的键必须与 {@code HouseholdEconomy.id} 一致（S1 起键 = 稳定家户身份）。 */
  @Test
  void rejectsClassesKeyNotMatchingRowKey() {
    HouseholdEconomy row = classRow(LANDLORD_HOUSE, LANDLORD_KEY);
    assertThatThrownBy(
            () ->
                EconomyData.empty()
                    .withMeta(Optional.of(meta()))
                    .withHouseholdEconomies(Map.of(PEASANT_HOUSE, row)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ClassRow.id");
  }

  /** ★ 不变量 3 的流水侧（§3.3）：{@code flows} 的键必须与 {@code FlowRow.id} 一致。 */
  @Test
  void rejectsFlowsKeyNotMatchingRowKey() {
    FlowRow row =
        new FlowRow(
            LANDLORD_HOUSE,
            Map.of(),
            Map.of(),
            0L,
            0L,
            0L,
            0L,
            10L,
            Map.of(),
            0L,
            0L,
            Map.of(),
            Map.of());
    assertThatThrownBy(
            () ->
                EconomyData.empty()
                    .withMeta(Optional.of(meta()))
                    .withFlows(Map.of(PEASANT_HOUSE, row)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FlowRow.id");
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
                    PEASANT_HOUSE,
                    Map.of(),
                    Map.of(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    Map.of(GRAIN, -1L),
                    0L,
                    0L,
                    Map.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unmetNeed");
    assertThatThrownBy(
            () ->
                new FlowRow(
                    PEASANT_HOUSE,
                    Map.of(),
                    Map.of(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    Map.of(),
                    -1L,
                    0L,
                    Map.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deaths");
    assertThatThrownBy(
            () ->
                new FlowRow(
                    PEASANT_HOUSE,
                    Map.of(),
                    Map.of(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    Map.of(),
                    0L,
                    -1L,
                    Map.of(),
                    Map.of()))
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
                    PEASANT_HOUSE,
                    Map.of(GRAIN, -1L),
                    Map.of(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    Map.of(),
                    0L,
                    0L,
                    Map.of(),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("income");
    assertThatThrownBy(
            () ->
                new FlowRow(
                    PEASANT_HOUSE,
                    null,
                    Map.of(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    Map.of(),
                    0L,
                    0L,
                    Map.of(),
                    Map.of()))
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
    // ★★ 2026-09-27（H1/K1）：改前这里有"HouseholdEconomy.goods 逐值 ≥ 0"这一条断言。
    //   该字段已按裁定 D3-C/K1 **整个删除**（家户的商品库存搬到 actor 切片的 HouseholdInventory，economy 侧只在
    //   EconomyDayStepper 的会话工作副本里读它），故这条断言的**主语不存在了** —— 按"不许放宽断言"的口径，
    //   它是**整条删除**（不是改成断言别的东西，那会变成另一条用例），如实记在 H1 的变更说明里。
    //   ★ 等价守卫在新位置仍然在，而且是**更强的形态**：会话工作副本的余额由
    //   EconomySettlement.requireHouseholdAccounts（fail-closed）与 setStock 的口径守着 —— 负余额在
    //   "扣"那一路根本产生不出来（一律 min(余额, 需求)），而"发放"那一路只发正数。
    // ★★ 2026-09-27（H0/K3）：改前这里有"HouseholdEconomy.meansOfProduction 逐值 ≥ 0"这一条。
    //   该字段已按裁定 K3 **删除**（产能搬到 Industry.capacity），故这条断言的**主语不存在了** ——
    //   按"不许放宽断言"的口径，它是**整条删除**（不是改成断言别的东西，那会变成另一条用例），如实记在
    //   H0 的变更说明里。★ 等价守卫在新位置仍然在：Industry.capacity 的构造期守卫（负值即抛）——
    //   它在本模块目前**没有用例覆盖**（属"待补覆盖"，不是"没守卫"）。
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

  /**
   * §3.1"当前进度 0..cycleDays"（★ R3B.2 起进度/周期住在不同记录）：
   *
   * <ul>
   *   <li>{@code Industry.cycleDays ≥ 1}（零长周期即抛）；
   *   <li>{@code ProductionProcess.progressDays ≥ 0}（负进度即抛）；
   *   <li>跨表上界 {@code unit.progressDays ≤ industry.cycleDays} 由 {@code EconomyData} 判（本类型看不见模板）。
   * </ul>
   */
  @Test
  void rejectsProgressOrCycleOutOfRange() {
    assertThatThrownBy(() -> industryWithCycleDays(0L))
        .as("零长周期不是合法生产周期")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycleDays");
    assertThatThrownBy(
            () -> new ProductionProcess(UNIT, FARM, ESTATE, FARM.value(), -1L, 0L, Map.of()))
        .as("R3B.2：进度已移到 ProductionProcess，负进度即抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("progressDays");
    assertThatThrownBy(
            () ->
                EconomyData.empty()
                    .withMeta(Optional.of(meta()))
                    .withIndustries(
                        Map.of(FARM, industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950)))))
                    .withProcesses(
                        Map.of(
                            UNIT,
                            new ProductionProcess(
                                UNIT, FARM, ESTATE, FARM.value(), 121L, 0L, Map.of()))))
        .as("跨表上界：unit.progressDays ≤ industry.cycleDays（121 > 120）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ProductionProcess.progressDays")
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

  // ── 参与率不再受槽位上限约束（R4-B.4 改写）────────────────────────────────────────

  /**
   * ★★ R4-B.4 改写（原 {@code participationPerMilleMustNotExceedItsSlotCeiling} + {@code
   * participationPerMilleAtTheSlotCeilingIsAccepted} 两条）：**参与率不再被 {@code Industry.slots} 的上限约束**。
   *
   * <p>R4-B.4 起该跨对象守卫已删除，见 B4-report §1.3 的"删掉的守卫"第 2 条（{@code participationPerMille ≤ min(命中 slot
   * 的 laborParticipationPerMille)}）。当前契约：{@code HouseholdEconomy} 自己的参与率仍守 `[0,1000]`（见 {@link
   * #rejectsClassRowParticipationOutsideZeroToThousand}），但槽位上限那一层没有了 ⇒ 1000‰ 的行 + 950‰ 的槽位
   * 必须放行。谁把旧守卫加回来，这里当场红。
   */
  @Test
  void participationPerMilleIsNoLongerCappedByIndustrySlots() {
    EconomyData data = economyWithParticipation(1000);

    assertThat(data.classes().get(PEASANT_HOUSE).participationPerMille())
        .as("R4-B.4：1000‰ 不再被 950‰ 的槽位上限截断")
        .isEqualTo(1000);
  }

  /** 一个产业的槽位上限 950‰；行里放 `participationPerMille` ⇒ 造一份最小 {@link EconomyData}。 */
  private static EconomyData economyWithParticipation(int participationPerMille) {
    Industry industry = industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950)));
    HouseholdEconomy row =
        new HouseholdEconomy(
            PEASANT_HOUSE,
            PEASANT_KEY,
            120L,
            60000L,
            participationPerMille,
            50L,
            List.of(),
            Map.of(GRAIN, 40L),
            Map.of(GRAIN, 30L),
            0L);
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withIndustries(Map.of(FARM, industry))
        .withHouseholdEconomies(Map.of(PEASANT_HOUSE, row));
  }

  // ── 债务引用两端（v2 spec §八.2）────────────────────────────────────────────────────

  /** ★ 债务的 creditor 指向一个不在 `classes` 里的家户 ⇒ 必须构造期拒（v1 只查 null）。 */
  @Test
  void debtEndpointsMustExistInClasses() {
    DebtContract dangling = grainContract(D1, GRAIN_TERMS, 100L);
    assertThatThrownBy(
            () ->
                EconomyData.empty()
                    .withMeta(Optional.of(meta()))
                    .withHouseholdEconomies(
                        Map.of(PEASANT_HOUSE, classRowWithoutDebts(PEASANT_HOUSE, PEASANT_KEY)))
                    // ★ classes 里没有 LANDLORD_HOUSE ⇒ creditor 悬空
                    .withDebtContracts(Map.of(D1, dangling)))
        .as("债务的 debtor/creditor 必须在 classes 里存在（v2 spec §八.2）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("债务");
  }

  /**
   * ★★ B.3b 起契约改写（原 {@code classRowDebtRefsMustExistInDebts}）：{@code HouseholdEconomy.debts}
   * 是**派生索引**， 以 债务合同表为唯一权威逐行重建（陈旧引用被清掉、缺失引用被补上），不再逐条"悬空即抛"。
   *
   * <p>见 {@code DebtReferenceReconciler}（唯一实现；调用点 = {@code EconomyData} 构造期，R4-B.3b）。 判别力：① 合同表为空 ⇒
   * 行内陈旧的 D1 被删除；② 表里两条合同（故意反序放入）⇒ 行内引用被补全为 {@link DebtContractId} 规范串升序。 ★ E4a 语义变化：同一四元组只有一条连续合同
   * ⇒ 第二条合同必须用**不同 terms**（D2）才存在。
   */
  @Test
  void classRowDebtRefsAreReconciledFromTheDebtContractsTable() {
    EconomyData staleRefs =
        EconomyData.empty()
            .withMeta(Optional.of(meta()))
            .withHouseholdEconomies(Map.of(PEASANT_HOUSE, classRowWithDebtRef(D1)));
    assertThat(staleRefs.classes().get(PEASANT_HOUSE).debts())
        .as("合同表为空 ⇒ 行内陈旧的 D1 引用被对账清掉（不再抛）")
        .isEmpty();

    DebtContract debt1 = grainContract(D1, GRAIN_TERMS, 100L);
    DebtContract debt2 = grainContract(D2, GRAIN_SECOND_TERMS, 50L);
    Map<DebtContractId, DebtContract> debts = new LinkedHashMap<>();
    debts.put(D2, debt2); // 故意反序，钉住对账后的规范序
    debts.put(D1, debt1);
    EconomyData rebuilt =
        EconomyData.empty()
            .withMeta(Optional.of(meta()))
            .withHouseholdEconomies(
                Map.of(
                    PEASANT_HOUSE, classRowWithoutDebts(PEASANT_HOUSE, PEASANT_KEY),
                    LANDLORD_HOUSE, classRowWithoutDebts(LANDLORD_HOUSE, LANDLORD_KEY)))
            .withDebtContracts(debts);

    assertThat(rebuilt.classes().get(PEASANT_HOUSE).debts())
        .as("合同表是权威 ⇒ 缺失的引用被补上，且按 DebtContractId 规范串升序")
        .containsExactlyInAnyOrder(D1, D2)
        .isSortedAccordingTo(Comparator.comparing(DebtContractId::value));
  }

  /** 对照：两端都在 ⇒ 必须放行（否则上面两条可能只是"一律拒"）。 */
  @Test
  void aWellFormedDebtIsAccepted() {
    DebtContract debt = grainContract(D1, GRAIN_TERMS, 100L);
    EconomyData data =
        EconomyData.empty()
            .withMeta(Optional.of(meta()))
            .withHouseholdEconomies(
                Map.of(
                    PEASANT_HOUSE, classRowWithDebtRef(D1),
                    LANDLORD_HOUSE, classRowWithoutDebts(LANDLORD_HOUSE, LANDLORD_KEY)))
            .withDebtContracts(Map.of(D1, debt));

    assertThat(data.debtContracts()).as("两端都在的合同必须放行").hasSize(1);
    assertThat(data.classes().get(PEASANT_HOUSE).debts())
        .as("行的债务引用由合同表重建（逐值）")
        .containsExactly(D1);
  }

  // ── 经营主体 operator（R4：从 Industry 模板搬到 ProductionProcess）────────────────────

  /**
   * ★★ R4 改写（原第 16 个组件 {@code Industry.operator} 的 null 守卫）：**经营主体的 null 守卫现在住在 {@code
   * ProductionProcess}**。
   *
   * <p>R4/R3B.2 起 {@code Industry.operator} 只是旧档兼容位（允许 null，缺省推导只发生在载荷/迁移边缘）， 真正的经营主体是 {@code
   * ProductionProcess.operator}（{@code Objects.requireNonNull}）。判别力：把 {@code ProductionProcess} 的
   * operator null 守卫删掉 ⇒ 本用例红。
   */
  @Test
  void rejectsNullOperator() {
    assertThatThrownBy(
            () -> new ProductionProcess(UNIT, FARM, null, FARM.value(), 0L, 0L, Map.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("operator");
  }

  /**
   * ★★ **裁定 R4：`regime` 与 `operator` 之间没有不变量** —— "operator 不是标签"的结构性证据。
   *
   * <p>依据 spec §2.4 原文：「同一个 `feudal` 可以有 A 格地租 30% / B 格五五分成 / C 格领主直营 —— 制度可以渐变而不用先改产业类型」。R4 起
   * operator 是 {@code ProductionProcess}/关系表里的**显式数据**，制度只给默认值； 加任何"regime ⇒ operator
   * 重推"都会让显式值被静默覆盖，本条当场红。
   */
  @Test
  void theRegimeDoesNotConstrainTheOperator() {
    // ★ 关系必须有地点 ⇒ 这一条用带格键的产业 id（本文件通用的 FARM="farm" 没有格键，不做关系推导）。
    IndustryId farmAtHex = new IndustryId("farm@0_0");
    ProductionUnitId activity = ProductionUnitId.idOf(farmAtHex, ESTATE);
    ProductionProcess unit =
        new ProductionProcess(activity, farmAtHex, ESTATE, farmAtHex.value(), 0L, 0L, Map.of());
    ProductionRules relation =
        RegimeRelations.defaultRelation(
            new RegimeId("tenant"),
            unit.id(),
            farmAtHex,
            unit.operator(),
            Set.of(ResidenceKind.RURAL));

    assertThat(unit.operator())
        .as("★ 显式值原样留下（逐值），没有被 regime 重新推导")
        .isEqualTo(ESTATE)
        .isNotEqualTo(RegimeOperators.defaultOperator(new RegimeId("tenant"), farmAtHex));
    assertThat(relation.operator())
        .as("★ 制度只给缺省：显式传入的庄园 operator 原样留在关系里，不被 tenant 档的家户默认值替换")
        .isEqualTo(ESTATE);
    assertThat(relation.residualOwner()).as("E9：余额归显式 operator").isEqualTo(ESTATE);
  }

  // ── 生产关系表 relations（R3B.2 起键/activity = ProductionUnitId）────────────────────

  /**
   * ★★ **两条跨表守卫逐条**（R3B.2 改口径）：关系必须挂在一个**已存在的 unit** 上、{@code operator} 必须与 {@code unit.operator}
   * 一致（"谁经营"不许两处拼写）、键必须等于 {@code ProductionRules.activity()}。
   *
   * <p>★ 判别力：① unit 不存在 ⇒ 抛"关系指名的生产单元…不存在"；② operator 不一致 ⇒ fail-closed 拒（构造期的旧键对齐 迁移先看到它，见 {@code
   * LegacyHouseholdMigration.canonicalizeRelations}）；③ 键 ≠ 值内 activity ⇒ 抛； ④ 对照条保证前三条不是"一律拒"。
   */
  @Test
  void relationsMustPointAtAnExistingUnitAndAgreeWithItsOperator() {
    Industry industry = industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950)));
    ActorRef otherOperator = new ActorRef(ActorKind.HOUSEHOLD, "house-7");
    ProductionUnitId ghostA = new ProductionUnitId("unit-ghost-a");
    ProductionUnitId ghostB = new ProductionUnitId("unit-ghost-b");

    // ① 关系指名的 unit 不存在 ⇒ 抛（结算时按 id 取不到生产活动）
    assertThatThrownBy(
            () ->
                economyWithRelations(
                    Map.of(FARM, industry), Map.of(), Map.of(ghostA, relation(ghostA, ESTATE))))
        .as("关系指名的生产单元不存在 ⇒ 构造期拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("生产单元")
        .hasMessageContaining("不存在");

    // ② ★★ 两处拼写不一致：unit.operator 是 ESTATE、关系里写的是另一个 ⇒ fail-closed
    ProductionProcess unit = unitWithOperator(ESTATE);
    assertThatThrownBy(
            () ->
                economyWithRelations(
                    Map.of(FARM, industry),
                    Map.of(UNIT, unit),
                    Map.of(UNIT, relation(UNIT, otherOperator))))
        .as("★ relations[k].operator() 必须等于 unit.operator()（同一件事不许两处拼写）")
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("operator");

    // ③ 键 ≠ 值内的 activity ⇒ 抛（"键 == 值内 key"与 classes/flows 同款）
    assertThatThrownBy(
            () ->
                economyWithRelations(
                    Map.of(FARM, industry), Map.of(), Map.of(ghostA, relation(ghostB, ESTATE))))
        .as("relations 的键必须与 ProductionRules.activity 一致")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("activity");

    // ④ 对照：unit 在、operator 一致、activity 一致 ⇒ **必须放行**（否则上面三条可能只是"一律拒"）
    EconomyData data =
        economyWithRelations(
            Map.of(FARM, industry), Map.of(UNIT, unit), Map.of(UNIT, relation(UNIT, ESTATE)));
    assertThat(data.relations()).as("合规矩的关系必须能入库").hasSize(1);
    assertThat(data.relations().get(UNIT).rules()).as("逐值（空表/缺条都会在这里红）").hasSize(1);
  }

  /**
   * ★ 空的 {@code relations} 是**合法状态**（= 全归 {@code residualOwner} 的等价路径，裁定 E9）：`empty()` 与
   * "只给产业不给关系"都必须构造得出来 —— 否则每一份 T2 之前的夹具都得凭空造一条关系。
   */
  @Test
  void anEmptyRelationsTableIsAValidState() {
    assertThat(EconomyData.empty().relations()).isEmpty();
    assertThat(
            EconomyData.empty()
                .withMeta(Optional.of(meta()))
                .withIndustries(
                    Map.of(FARM, industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950)))))
                .withRelations(null)
                .relations())
        .as("null ⇒ 空表（缺键 = 空，同其余组件）")
        .isEmpty();
  }

  /** 一份最小的经济：产业 + unit + 关系表（其余组件留空）。 */
  private static EconomyData economyWithRelations(
      Map<IndustryId, Industry> industries,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations) {
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withIndustries(industries)
        .withProcesses(units)
        .withRelations(relations);
  }

  /** 一个 action 与 operator 自洽的 unit（identity 走唯一拼写点）。 */
  private static ProductionProcess unitWithOperator(ActorRef operator) {
    return new ProductionProcess(
        ProductionUnitId.idOf(FARM, operator), FARM, operator, FARM.value(), 0L, 0L, Map.of());
  }

  /** 一条**非派生**的关系（规则内容与任何 regime 的推导值都不同：777‰ + 一条货币规则）。 */
  private static ProductionRules relation(ProductionUnitId activity, ActorRef operator) {
    return new ProductionRules(
        activity,
        operator,
        null,
        List.of(
            new CompensationRule(
                RuleType.OUTPUT_SHARE,
                new Payee.ToCohort(
                    new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, LANDLORD)),
                Pool.GROSS_OUTPUT,
                Weight.NONE,
                777,
                0L,
                Optional.of(GRAIN),
                Optional.empty(),
                5)),
        operator);
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
            Map.of(AssetKind.LAND, 1000L),
            Map.of(),
            0L,
            0L,
            Map.of(GRAIN, 7L),
            Map.of(AssetKind.SHIP, Map.of(GRAIN, 1L)), // ★ SHIP 不是产能约束，只是分类
            List.of(new ClassSlot(PEASANT, "贫农", 1000)),
            new AllocationRule.Split(700, 300));

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
                    Map.of(),
                    Map.of(),
                    0L,
                    0L,
                    Map.of(GRAIN, 7L),
                    Map.of(),
                    List.of(new ClassSlot(PEASANT, "贫农", 1000)),
                    new AllocationRule.Split(700, 300)))
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
                    Map.of(AssetKind.LAND, 0L),
                    Map.of(),
                    0L,
                    0L,
                    Map.of(GRAIN, 7L),
                    Map.of(),
                    List.of(new ClassSlot(PEASANT, "贫农", 1000)),
                    new AllocationRule.Split(700, 300)))
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

  /**
   * ★★ R3B.2 改写（原 Industry 旧档兼容位 {@code cycleInputUsedMilli} 的负值守卫）：本周期累计投入现在住在 {@code
   * ProductionProcess.cycleInputUsedMilli}（逐商品、不得为负）。判别力：把 unit 的逐商品非负守卫删掉 ⇒ 本用例红。
   */
  @Test
  void rejectsNegativeCycleInputAccumulator() {
    assertThatThrownBy(
            () ->
                new ProductionProcess(UNIT, FARM, ESTATE, FARM.value(), 0L, 0L, Map.of(GRAIN, -1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycleInputUsedMilli");
  }

  /**
   * 槽位上限 1000‰ 的产业，{@code cycleInputPerUnit = cycleInput}。
   *
   * <p>★ **产能表按投入表的键现推**（每键 1）：本夹具测的是"投入表本身的形状" ⇒ 让产能表跟着它走， 不另写一份会漂的键集；{@code cycleInput == null}
   * 时给一个 LAND 键，好让它先过"capacityPerUnit 不得为空"的 守卫、再由 cycleInputPerUnit 的 null 守卫当场抛。
   */
  private static Industry industryWithCycleInput(
      Map<AssetKind, Map<CommodityId, Long>> cycleInput) {
    Map<AssetKind, Long> capacity = new LinkedHashMap<>();
    if (cycleInput != null) {
      for (AssetKind kind : cycleInput.keySet()) {
        capacity.put(kind, 1L);
      }
    }
    if (capacity.isEmpty()) {
      capacity.put(AssetKind.LAND, 1L); // capacityPerUnit 不得为空（R3 的构造期守卫）
    }
    return new Industry(
        FARM,
        "农业",
        new RegimeId("tenant"),
        120L,
        capacity,
        Map.of(),
        0L,
        0L,
        Map.of(GRAIN, 7L),
        cycleInput, // ★ null 由 rejectsNullCycleInputMap 用来测"null ⇒ 拒"
        List.of(new ClassSlot(PEASANT, "贫农", 1000)),
        new AllocationRule.Split(700, 300));
  }

  /** 无债务的阶层行（参与率 800）；`id` 必须与它在 `classes` 里的键一致。 */
  private static HouseholdEconomy classRowWithoutDebts(HouseholdId id, CohortKey view) {
    return new HouseholdEconomy(
        id, view, 120L, 60000L, 800, 50L, List.of(), Map.of(GRAIN, 40L), Map.of(GRAIN, 30L), 0L);
  }

  /** 行内引用一份债务（其两端由调用方保证）。 */
  private static HouseholdEconomy classRowWithDebtRef(DebtContractId debtId) {
    return new HouseholdEconomy(
        PEASANT_HOUSE,
        PEASANT_KEY,
        120L,
        60000L,
        800,
        50L,
        List.of(debtId),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L),
        0L);
  }

  // ── 夹具 ──

  /**
   * ★ 已迁移的 meta：{@code rulesVersion = pre-modern-v1}（{@link
   * EconomyMeta#RULES_VERSION_PRE_MODERN_V1}）⇒ {@code EconomyData} 构造期不会对"classes 非空 + memberships
   * 空"的构造不变量夹具跑自动旧档迁移 （{@code LegacyHouseholdMigration.needed} 的第一条）。本文件测的是**构造期守卫**，不是迁移器；不这样标注的话，
   * 迁移器会先补出 memberships/relations，守卫还没走到夹具形状就被改写。
   */
  private static EconomyMeta meta() {
    return new EconomyMeta(
        "m1", 0L, OptionalLong.empty(), EconomyMeta.RULES_VERSION_PRE_MODERN_V1, Optional.empty());
  }

  private static Industry industryWithSlots(List<ClassSlot> slots) {
    return industryWithCycleDays(120L, slots);
  }

  private static Industry industryWithCycleDays(long cycleDays) {
    return industryWithCycleDays(cycleDays, List.of(new ClassSlot(PEASANT, "贫农", 1000)));
  }

  private static Industry industryWithCycleDays(long cycleDays, List<ClassSlot> slots) {
    return new Industry(
        FARM,
        "农业",
        new RegimeId("tenant"),
        cycleDays,
        Map.of(AssetKind.LAND, 1000L),
        Map.of(),
        0L,
        0L,
        Map.of(GRAIN, 7L),
        Map.of(),
        slots,
        new AllocationRule.Split(700, 300));
  }

  private static HouseholdEconomy classRow(HouseholdId id, CohortKey view) {
    return new HouseholdEconomy(
        id, view, 120L, 60000L, 800, 50L, List.of(D1), Map.of(GRAIN, 40L), Map.of(GRAIN, 30L), 0L);
  }

  private static HouseholdEconomy classRowWithParticipation(int participationPerMille) {
    return new HouseholdEconomy(
        PEASANT_HOUSE,
        PEASANT_KEY,
        120L,
        60000L,
        participationPerMille,
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L),
        0L);
  }

  private static HouseholdEconomy classRowWithPopulation(long population) {
    return new HouseholdEconomy(
        PEASANT_HOUSE,
        PEASANT_KEY,
        population,
        60000L,
        800,
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L),
        0L);
  }

  private static HouseholdEconomy classRowWithLabor(long laborMilli) {
    return new HouseholdEconomy(
        PEASANT_HOUSE,
        PEASANT_KEY,
        120L,
        laborMilli,
        800,
        50L,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L),
        0L);
  }

  private static HouseholdEconomy classRowWithMoney(long money) {
    return new HouseholdEconomy(
        PEASANT_HOUSE,
        PEASANT_KEY,
        120L,
        60000L,
        800,
        money,
        List.of(D1),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L),
        0L);
  }

  // ★★ 2026-09-27（H1/K1）：改前这里有一个 classRowWithGoods(Map) 助手，喂给上面那条"负数库存被拒"。
  //   被喂的那个字段已整个删除 ⇒ 助手一并删除（留一个没人调用的助手 = 本仓反对的"看起来在、其实没人读"）。

  private static DebtContract debtWithPrincipal(long principal) {
    return grainContract(D1, GRAIN_TERMS, principal);
  }

  /** 贫农→地主的连续粮债夹具：身份 key == 值内 id 由 {@code DebtContractId.idOf} 两处共用同一输入。 */
  private static DebtContract grainContract(DebtContractId id, DebtTerms terms, long principal) {
    return new DebtContract(
        id,
        PEASANT_HOUSE,
        LANDLORD_HOUSE,
        DebtUnit.commodity(GRAIN),
        terms,
        principal,
        0L,
        OptionalLong.empty(),
        OptionalLong.empty(),
        DebtStatus.NORMAL);
  }

  // ── R2：劳动供给与配额的三条结构判据（第三阶段设计稿 §四）────────────────────────────
  //
  // ★ 为什么这三条必须**构造期判**：劳动是"可分配但不能凭空重复"的资源（本轮的目标原话），
  //   而"同一批人被两个产业各算一次满额"正是设计稿 §一.2 实测出的空洞 —— 判在构造期 ⇒
  //   任何一条路径（命令、旧档读入、夹具、将来的协调器）都造不出"配额超过可支配劳动"的状态。

  /**
   * ★★ **本阶段最重要的不变量（P2-A A4 口径）**：{@code Σ 同一家户的配额 ≤ 该家户每 tick 时间预算} （{@code
   * HouseholdEconomy.laborMilli}；第二权威 {@code LaborSupply} 已删除）。
   *
   * <p>★ 判别力：把这条判据删掉（或把 {@code >} 写成 {@code >=}... 后者不红，此处只谈删）⇒ 本条转绿 ⇒ 红。 ★
   * 对照条在下面：**恰好用满**（取等号）必须放行 —— 否则这条判据会退化成"多加一条配额就拒"的粗暴规则。
   */
  @Test
  void rejectsAllocationsThatExceedTheHouseholdsTimeBudget() {
    assertThatThrownBy(
            () ->
                economyWith(
                    100_000L,
                    Map.of(
                        ALLOC_A,
                        allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ORGANIZATION, 60_000L),
                        // ★ 60,000 + 60,000 = 120,000 > 家户预算 100,000 ⇒ 同一户的时间被算两次满额
                        ALLOC_B,
                        allocation(ALLOC_B, LOT, FARM.value(), ActorKind.ORGANIZATION, 60_000L))))
        .as("Σ 配额超过家户时间预算必须构造期拒（计划 §13.5 的那条不变量）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过它的每 tick 时间预算");
  }

  /** ★ 对照：两产业各 60,000 + 40,000 = 100,000 = 预算 ⇒ **恰好用满，放行**（配额本来就该能取满）。 */
  @Test
  void acceptsAllocationsThatExactlyUseUpTheHouseholdsTimeBudget() {
    EconomyData data =
        economyWith(
            100_000L,
            Map.of(
                ALLOC_A,
                allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ORGANIZATION, 60_000L),
                ALLOC_B,
                allocation(ALLOC_B, LOT, OTHER_FARM.value(), ActorKind.ORGANIZATION, 40_000L)));

    assertThat(data.allocations()).as("同一家户供给两个生产活动：结构上成立").hasSize(2);
  }

  /** ★★ **配额必须属于一个已存在的家户**（P2-A 起没有供给表 ⇒ 悬空家户不再有第二处可查）。 */
  @Test
  void rejectsAnAllocationWhoseHouseholdIsNotInClasses() {
    HouseholdId unknown = new HouseholdId("hh-not-seeded");
    HouseholdLaborCommitment orphan =
        new HouseholdLaborCommitment(
            ALLOC_A,
            LOT,
            unknown,
            new ActorRef(ActorKind.ORGANIZATION, FARM.value()),
            "farm",
            1L,
            1L);
    assertThatThrownBy(() -> economyWith(100_000L, Map.of(ALLOC_A, orphan)))
        .as("配额的家户必须在 classes 里（否则这份劳动没有归属）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("家户必须是已存在的家户");
  }

  /**
   * ★★ **非产业型主体不得与产业 id 撞名**（结算按 actor id 把配额归给产业）—— P2-A 起 {@code ESTATE}/{@code WORKSHOP}
   * 退役，允许命中产业 id 的只有 {@code ORGANIZATION}/{@code HOUSEHOLD}。
   *
   * <p>★ 判别力：拼错 kinds 或把撞名判据删掉 ⇒ 这条静默转绿；下面的对照条证明"允许的两档不会被一律拒"。
   */
  @Test
  void rejectsNonIndustryKindsWhoseIdCollidesWithAnIndustry() {
    assertThatThrownBy(
            () ->
                economyWith(
                    100_000L,
                    Map.of(
                        ALLOC_A, allocation(ALLOC_A, LOT, FARM.value(), ActorKind.PEOPLE_LOT, 1L))))
        .as("PEOPLE_LOT 的 id 撞上产业 id ⇒ 它的配额会被静默算进那个产业")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非产业型主体的 id 不得与任何产业 id 相同");

    // ★ 对照：允许的两档（ORGANIZATION / HOUSEHOLD）命中产业 id 时放行。
    EconomyData organization =
        economyWith(
            100_000L,
            Map.of(ALLOC_A, allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ORGANIZATION, 1L)));
    assertThat(organization.allocations()).as("ORGANIZATION 可以经营一个产业").hasSize(1);
  }

  /** ★★ **R3：家户可以"拥有"一个生产过程**（农村家庭纺织）⇒ 它的 actor id 命名一个产业时**放行**。 */
  @Test
  void acceptsAHouseholdActorOwningAnIndustry() {
    EconomyData data =
        economyWith(
            100_000L,
            Map.of(ALLOC_A, allocation(ALLOC_A, LOT, FARM.value(), ActorKind.HOUSEHOLD, 40_000L)));

    assertThat(data.allocations()).as("家户的生产过程（家户织布）放行").hasSize(1);
  }

  /** ★ 家户（非产业型）的 id 与产业 id 不同 ⇒ 放行：它进守恒与读口，但**不占任何产业的劳动投入**。 */
  @Test
  void acceptsAHouseholdActorWhoseIdIsNotAnIndustry() {
    EconomyData data =
        economyWith(
            100_000L,
            Map.of(ALLOC_A, allocation(ALLOC_A, LOT, "0_0", ActorKind.HOUSEHOLD, 40_000L)));

    assertThat(data.allocations()).hasSize(1);
  }

  /** ★ 配额表的键必须与行内身份一致（与 {@code classes}/{@code flows} 同款的不变量）。 */
  @Test
  void rejectsLaborAllocationKeysThatDoNotMatchTheirRows() {
    assertThatThrownBy(
            () ->
                economyWith(
                    100_000L,
                    Map.of(
                        new LaborAllocationId("alloc-别的"),
                        allocation(ALLOC_A, LOT, FARM.value(), ActorKind.ORGANIZATION, 1L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LaborAllocation.id");
  }

  // ── R2 的夹具（本类自足）──────────────────────────────────────────────────────────

  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");
  private static final LaborAllocationId ALLOC_A = new LaborAllocationId("alloc-a");
  private static final LaborAllocationId ALLOC_B = new LaborAllocationId("alloc-b");
  private static final IndustryId OTHER_FARM = new IndustryId("farm@0_0");

  /**
   * 一份最小的经济：两个产业（{@code farm} 与 {@code farm@0_0}）+ 一个家户行（每 tick 时间预算 = 参数）+ 给定的配额表。
   *
   * <p>★ 家户行是必需的：P2-A 起配额必须属于一个已存在的家户，且上限 = 它的 {@code HouseholdEconomy.laborMilli}； 构造期不会先跑 {@code
   * LegacyHouseholdMigration} 改写夹具（配额已带真实家户）。
   */
  private static EconomyData economyWith(
      long householdLaborMilli, Map<LaborAllocationId, HouseholdLaborCommitment> allocations) {
    return EconomyData.empty()
        .withMeta(Optional.of(meta()))
        .withIndustries(
            Map.of(
                FARM,
                industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950))),
                OTHER_FARM,
                industryWithSlots(List.of(new ClassSlot(PEASANT, "贫农", 950)))))
        .withHouseholdEconomies(
            Map.of(
                PEASANT_HOUSE, classRowWithLabor(PEASANT_HOUSE, PEASANT_KEY, householdLaborMilli)))
        .withLaborCommitments(allocations);
  }

  /** 与 {@link #classRowWithoutDebts} 同形，只把每 tick 时间预算参数化（P2-A 的配额上限）。 */
  private static HouseholdEconomy classRowWithLabor(
      HouseholdId id, CohortKey view, long laborMilli) {
    return new HouseholdEconomy(
        id,
        view,
        120L,
        laborMilli,
        800,
        50L,
        List.of(),
        Map.of(GRAIN, 40L),
        Map.of(GRAIN, 30L),
        0L);
  }

  /** 一条配额（第 1 周期、家户 = 本夹具的 {@code PEASANT_HOUSE}、活动名 {@code farm}）。 */
  private static HouseholdLaborCommitment allocation(
      LaborAllocationId id, PeopleLotId group, String actorId, ActorKind kind, long laborMilli) {
    return new HouseholdLaborCommitment(
        id, group, PEASANT_HOUSE, new ActorRef(kind, actorId), "farm", laborMilli, 1L);
  }
}
