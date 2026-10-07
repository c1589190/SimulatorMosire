package io.mosire.simos.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link GovDaily#settle} 的逐值判据（阶段 11a + Z3b 单次计算）：
 *
 * <ul>
 *   <li>资源顺序固定 grain → cloth → money（oracle 调用序 = 评估序，0 需求不发）；
 *   <li>全额/部分/零支付 ⇒ dues 的 assessed/paid/shortfall 逐值；缺口 ⇒ 每 office 至多一条 ADMIN_SUPPLY，evidence =
 *       三资源合计；
 *   <li><b>Z3b 单次计算</b>：效率表由 app 算好传入；六个 per-mille 读数与覆盖缺口 evidence 都取自这一份（不按 staff 重算）；缺该 office
 *       的当日结果 ⇒ 具名 {@code IllegalStateException}；状态损坏（缺单位/缺 GovernmentFormation） 的检查在效率查表<b>之前</b>；
 *   <li>覆盖率不足 ⇒ ADMIN_SECURITY / ADMIN_PAPERWORK 各自独立，evidence 带 coverage/supply/demand；
 *   <li>无位置 ⇒ dues/signals 空、六表空、六个 per-mille 与 tick 仍更新；
 *   <li>源状态拷贝纪律：结算只改 offices，{@code administrationPlans}/{@code budgetPolicies} 原样带过；
 *   <li>确定性：同输入两次 Outcome 逐字段相等；{@link GovState#empty()} ⇒ changed=false；
 *   <li>布料折日：{@code clothNeed = totalStaff ×
 *       floor(clothPerStaffPerCycle/daysInYearAtSettlement)}（365/366 必须显式传参），逐值钉 floor。
 * </ul>
 *
 * <p>★ 旧测试侧 {@code settleLegacy} helper 已删除：新签名只消费当日 {@code Map<UnitId, Efficiency>}，不再在 gov
 * 侧重算效率（z3b §10.2）。
 */
class GovDailyTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final HexCoord H1 = new HexCoord(1, 1);
  private static final UnitId U1 = new UnitId("gov-1");
  private static final io.mosire.simos.map.region.RegionId R1 =
      new io.mosire.simos.map.region.RegionId("r-1");
  private static final CommodityId GRAIN = CommodityId.parse(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final CommodityId CLOTH = CommodityId.parse(EconomyVocabulary.CLOTH_COMMODITY_ID);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  // ── 全额支付：顺序与逐值 ────────────────────────────────────────────────────────────

  @Test
  void fullPaymentPaysGrainThenClothThenMoneyAndWritesAllSixTables() {
    GovernmentFormation gov = gov(staff(1L, 1L, 1L), policy(10L, 500L, 3L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    GovState base = state(GovOfficeState.empty(U1, 0L));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome = settle(base, units, 7L, 365L, fullCoverage(), oracle);

    // totalStaff=3 ⇒ grain 30；cloth = 3×floor(500/365)=3×1=3（若误写成 3×500/365 会是 4）；money 9。
    assertThat(oracle.calls())
        .as("oracle 调用序 = 资源评估序 grain → cloth → money，金额逐值")
        .containsExactly(
            new Call(U1, H1, "grain", 30L),
            new Call(U1, H1, "cloth", 3L),
            new Call(U1, H1, "silver", 9L));
    assertThat(oracle.days())
        .as("L3 起 pay 的 day 参 = settle 的 tick（7L），三次调用逐值透传")
        .containsExactly(7L, 7L, 7L);
    assertThat(outcome.dues())
        .as("每 office 三资源各一条 due，评估/实付/缺口逐值")
        .containsExactly(
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(GRAIN), 30L, 30L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(CLOTH), 3L, 3L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Money(SILVER), 9L, 9L, 0L));
    assertThat(outcome.signals()).as("无缺口、无覆盖不足 ⇒ 无信号").isEmpty();
    assertThat(outcome.changed()).as("tick 0 → 7 且六表有事实 ⇒ changed").isTrue();

    GovOfficeState office = outcome.next().offices().get(U1);
    assertThat(office.tick()).isEqualTo(7L);
    assertThat(office.lastAssessedGoods())
        .as("商品评估表 grain → cloth 保序")
        .containsExactly(entry(GRAIN, 30L), entry(CLOTH, 3L));
    assertThat(office.lastPaidGoods()).containsExactly(entry(GRAIN, 30L), entry(CLOTH, 3L));
    assertThat(office.lastShortfallGoods()).containsExactly(entry(GRAIN, 0L), entry(CLOTH, 0L));
    assertThat(office.lastAssessedMoney()).containsExactly(entry(SILVER, 9L));
    assertThat(office.lastPaidMoney()).containsExactly(entry(SILVER, 9L));
    assertThat(office.lastShortfallMoney()).containsExactly(entry(SILVER, 0L));
    assertThat(office.securityCoveragePerMille()).as("六读数取自 app 传入的 Efficiency").isEqualTo(1_000L);
    assertThat(office.paperworkCoveragePerMille()).isEqualTo(1_000L);
    assertThat(office.securityEfficiencyPerMille()).isEqualTo(1_000L);
    assertThat(office.paperworkEfficiencyPerMille()).isEqualTo(1_000L);
    assertThat(office.efficiencyPerMille()).isEqualTo(1_000L);
    assertThat(office.bonusPerMille()).isZero();
  }

  /**
   * ★★ G12 防 120× 回归（2026-10-01）：{@link OfficePolicy#defaults()} 的粮定额是**每人每日** 83 毫粮 （{@code
   * EconomyVocabulary.dailyRationMilli(1,1)}），不是每人每 120 天的 10,000。旧默认值直接进 {@code totalStaff ×
   * grainPerStaffPerTick} ⇒ 1 人编制当日就评估 10,000 毫粮（120×）。
   */
  @Test
  void defaultPolicyChargesDailyRationAndNotThe120DayConstant() {
    GovernmentFormation gov = gov(staff(1L, 0L, 0L), OfficePolicy.defaults());
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome =
        settle(state(GovOfficeState.empty(U1, 0L)), units, 1L, 365L, fullCoverage(), oracle);

    assertThat(oracle.calls())
        .as("默认政策 + 1 名编制：当日粮需求 = 83 毫粮（120 天常量 10000 ⇒ 本断言红）")
        .containsExactly(new Call(U1, H1, "grain", 83L), new Call(U1, H1, "cloth", 2L));
    assertThat(outcome.dues())
        .as("评估量写成 dailyRationMilli(1,1)=83；布按每人每日 floor(1000/365)=2 折")
        .containsExactly(
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(GRAIN), 83L, 83L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(CLOTH), 2L, 2L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Money(SILVER), 0L, 0L, 0L));
    assertThat(OfficePolicy.defaults().grainPerStaffPerTick())
        .as("默认政策常量本身也必须等于 dailyRationMilli(1,1)")
        .isEqualTo(EconomyVocabulary.dailyRationMilli(1L, 1L));
  }

  @Test
  void zeroNeedResourceIsNotSentToOracle() {
    GovernmentFormation gov = gov(staff(2L, 0L, 0L), policy(10L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome =
        settle(state(GovOfficeState.empty(U1, 0L)), units, 1L, 365L, fullCoverage(), oracle);

    assertThat(oracle.calls())
        .as("0 需求不发、不跳号：只有 grain 一次调用")
        .containsExactly(new Call(U1, H1, "grain", 20L));
    assertThat(outcome.dues())
        .as("0 需求资源仍保留 due 的『评估过且为 0』事实")
        .containsExactly(
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(GRAIN), 20L, 20L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(CLOTH), 0L, 0L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Money(SILVER), 0L, 0L, 0L));
  }

  // ── 部分/零支付：缺口与 ADMIN_SUPPLY ───────────────────────────────────────────────

  @Test
  void partialPaymentRecordsPerResourceShortfallAndOneAggregatedSupplySignal() {
    GovernmentFormation gov = gov(staff(1L, 1L, 0L), policy(10L, 365L, 5L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    RecordingOracle oracle =
        new RecordingOracle(
            (resource, requested) ->
                switch (resource.name()) {
                  case "grain" -> requested; // 20/20 全付
                  case "cloth" -> 0L; // 2 全缺
                  default -> 4L; // silver 10 里付 4、缺 6
                });

    GovDaily.Outcome outcome =
        settle(state(GovOfficeState.empty(U1, 0L)), units, 3L, 365L, fullCoverage(), oracle);

    assertThat(outcome.dues())
        .as("dues 的 paid/shortfall 逐资源对账（assessed = paid + shortfall）")
        .containsExactly(
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(GRAIN), 20L, 20L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(CLOTH), 2L, 0L, 2L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Money(SILVER), 10L, 4L, 6L));
    assertThat(outcome.signals()).as("部分支付只发一条 ADMIN_SUPPLY（每 office 每天至多一条）").hasSize(1);
    GovDaily.SignalDraft signal = outcome.signals().get(0);
    assertThat(signal.kind()).isEqualTo(GovDaily.KIND_ADMIN_SUPPLY);
    assertThat(signal.hex()).isEqualTo(H1);
    assertThat(signal.severity()).isEqualTo(1L);
    assertThat(signal.evidence())
        .as("evidence = 三资源合计：assessed=32、paid=24、shortfall=8")
        .containsExactly(entry("assessed", 32L), entry("paid", 24L), entry("shortfall", 8L));
    assertThat(signal.reason())
        .contains("grain 评估 20 实付 20 缺 0")
        .contains("cloth 评估 2 实付 0 缺 2")
        .contains("silver 评估 10 实付 4 缺 6");

    GovOfficeState office = outcome.next().offices().get(U1);
    assertThat(office.lastShortfallGoods()).containsExactly(entry(GRAIN, 0L), entry(CLOTH, 2L));
    assertThat(office.lastShortfallMoney()).containsExactly(entry(SILVER, 6L));
  }

  @Test
  void zeroPaymentStillEmitsExactlyOneSupplySignalWithFullShortfall() {
    GovernmentFormation gov = gov(staff(1L, 1L, 0L), policy(10L, 365L, 5L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> 0L);

    GovDaily.Outcome outcome =
        settle(state(GovOfficeState.empty(U1, 0L)), units, 2L, 365L, fullCoverage(), oracle);

    assertThat(outcome.dues())
        .as("零支付：每资源 paid=0、shortfall=assessed")
        .containsExactly(
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(GRAIN), 20L, 0L, 20L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(CLOTH), 2L, 0L, 2L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Money(SILVER), 10L, 0L, 10L));
    assertThat(outcome.signals()).hasSize(1);
    GovDaily.SignalDraft signal = outcome.signals().get(0);
    assertThat(signal.kind()).isEqualTo(GovDaily.KIND_ADMIN_SUPPLY);
    assertThat(signal.evidence())
        .as("零支付也把三资源缺口合计记进一条 signal，不静默付 0")
        .containsExactly(entry("assessed", 32L), entry("paid", 0L), entry("shortfall", 32L));
    assertThat(outcome.next().offices().get(U1).lastPaidGoods())
        .containsExactly(entry(GRAIN, 0L), entry(CLOTH, 0L));
  }

  // ── 覆盖率不足：两类信号各自独立（evidence 取传入 Efficiency 的计算量）────────────────

  @Test
  void securityAndPaperworkShortfallsEmitIndependentSignalsWithCoverageEvidence() {
    // 关键判别力：staff 是 100/0，但传入 Efficiency 的供给/需求是 111/222 与 0/333——
    // 若 GovDaily 仍从 staff 重算（旧桥口径 100/200），evidence 会当场不对。
    GovernmentFormation gov = gov(staff(100L, 0L, 0L), policy(0L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    GovEfficiency.Efficiency efficiency =
        new GovEfficiency.Efficiency(500L, 0L, 0L, 0L, 500L, 0L, 111L, 0L, 222L, 333L);
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome =
        settle(state(GovOfficeState.empty(U1, 0L)), units, 4L, 365L, efficiency, oracle);

    assertThat(oracle.calls()).as("policy 全 0 ⇒ 不发付款调用").isEmpty();
    assertThat(outcome.signals())
        .extracting(GovDaily.SignalDraft::kind)
        .as("两类覆盖不足各自一条，顺序 supply → security → paperwork")
        .containsExactly(GovDaily.KIND_ADMIN_SECURITY, GovDaily.KIND_ADMIN_PAPERWORK);

    GovDaily.SignalDraft security = outcome.signals().get(0);
    assertThat(security.hex()).isEqualTo(H1);
    assertThat(security.severity()).isEqualTo(1L);
    assertThat(security.evidence())
        .as("evidence 逐值取自传入 Efficiency：coverage=500、supply=111、demand=222")
        .containsExactly(
            entry("coveragePerMille", 500L), entry("supply", 111L), entry("demand", 222L));
    assertThat(security.reason()).contains("治安覆盖率 500‰").contains("供给 111、需求 222");

    GovDaily.SignalDraft paperwork = outcome.signals().get(1);
    assertThat(paperwork.evidence())
        .as("文书 evidence：coverage=0、supply=0、demand=333（与 staff=0 的重算恰好无关）")
        .containsExactly(entry("coveragePerMille", 0L), entry("supply", 0L), entry("demand", 333L));
    assertThat(paperwork.reason()).contains("文书覆盖率 0‰").contains("供给 0、需求 333");

    GovOfficeState office = outcome.next().offices().get(U1);
    assertThat(office.securityCoveragePerMille()).isEqualTo(500L);
    assertThat(office.paperworkCoveragePerMille()).isZero();
    assertThat(office.securityEfficiencyPerMille()).isEqualTo(500L);
    assertThat(office.paperworkEfficiencyPerMille()).isZero();
    assertThat(office.efficiencyPerMille()).as("两维效率相乘 500×0 ⇒ 0").isZero();
  }

  @Test
  void oneSidedCoverageShortfallEmitsOnlyItsOwnSignal() {
    GovEfficiency.Efficiency paperShort =
        new GovEfficiency.Efficiency(1_000L, 0L, 0L, 0L, 1_000L, 0L, 100L, 0L, 100L, 100L);
    GovDaily.Outcome onlyPaper =
        settle(
            state(GovOfficeState.empty(U1, 0L)),
            units(
                govUnit(
                    gov(staff(1L, 0L, 0L), policy(0L, 0L, 0L, 0L)),
                    Optional.of(H1),
                    Optional.empty())),
            1L,
            365L,
            paperShort,
            new RecordingOracle((resource, requested) -> requested));
    assertThat(onlyPaper.signals())
        .extracting(GovDaily.SignalDraft::kind)
        .containsExactly(GovDaily.KIND_ADMIN_PAPERWORK);

    GovEfficiency.Efficiency securityShort =
        new GovEfficiency.Efficiency(500L, 1_000L, 0L, 500L, 500L, 1_000L, 55L, 100L, 110L, 100L);
    GovDaily.Outcome onlySecurity =
        settle(
            state(GovOfficeState.empty(U1, 0L)),
            units(
                govUnit(
                    gov(staff(1L, 0L, 0L), policy(0L, 0L, 0L, 0L)),
                    Optional.of(H1),
                    Optional.empty())),
            1L,
            365L,
            securityShort,
            new RecordingOracle((resource, requested) -> requested));
    assertThat(onlySecurity.signals())
        .extracting(GovDaily.SignalDraft::kind)
        .containsExactly(GovDaily.KIND_ADMIN_SECURITY);
  }

  // ── 无位置 ─────────────────────────────────────────────────────────────────────────

  @Test
  void noSeatSkipsDuesAndSignalsButStillUpdatesEfficiencyAndTick() {
    GovernmentFormation gov = gov(staff(1L, 0L, 0L), policy(100L, 365L, 100L, 0L));
    UnitState units = units(govUnit(gov, Optional.empty(), Optional.of(jurisdiction(R1))));
    GovEfficiency.Efficiency efficiency =
        new GovEfficiency.Efficiency(
            500L, 700L, 0L, 3_960L, 1_800L, 2_200L, 555L, 777L, 1_110L, 1_110L);
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome =
        settle(state(GovOfficeState.empty(U1, 0L)), units, 5L, 365L, efficiency, oracle);

    assertThat(oracle.calls()).as("无位置不评估、不付款").isEmpty();
    assertThat(outcome.dues()).isEmpty();
    assertThat(outcome.signals()).as("无位置不发明 ADMIN_NO_SEAT 之类 kind；覆盖 500/700 也不发覆盖信号").isEmpty();
    assertThat(outcome.changed()).isTrue();

    GovOfficeState office = outcome.next().offices().get(U1);
    assertThat(office.tick()).as("efficiency 与 tick 仍更新").isEqualTo(5L);
    assertThat(office.securityCoveragePerMille()).isEqualTo(500L);
    assertThat(office.paperworkCoveragePerMille()).isEqualTo(700L);
    assertThat(office.securityEfficiencyPerMille()).as(">1000 的维效率原样写入（C3 不封顶）").isEqualTo(1_800L);
    assertThat(office.paperworkEfficiencyPerMille()).isEqualTo(2_200L);
    assertThat(office.efficiencyPerMille()).isEqualTo(3_960L);
    assertThat(office.bonusPerMille()).isZero();
    assertThat(office.lastAssessedGoods()).as("六表清空 = 本日没有结算事实").isEmpty();
    assertThat(office.lastPaidGoods()).isEmpty();
    assertThat(office.lastShortfallGoods()).isEmpty();
    assertThat(office.lastAssessedMoney()).isEmpty();
    assertThat(office.lastPaidMoney()).isEmpty();
    assertThat(office.lastShortfallMoney()).isEmpty();
  }

  // ── 状态损坏 / 单次计算契约 / oracle 契约 ─────────────────────────────────────────

  @Test
  void officeReferencingMissingUnitThrowsIllegalStateBeforeEfficiencyLookup() {
    GovState base = state(GovOfficeState.empty(U1, 0L));
    UnitState emptyUnits = UnitState.empty();

    assertThatThrownBy(
            () ->
                GovDaily.settle(
                    base,
                    emptyUnits,
                    0L,
                    365L,
                    Map.of(),
                    new RecordingOracle((resource, requested) -> requested)))
        .as("传空效率表也必须先报单位缺失（检查序在 efficiency 查表之前）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不存在的单位")
        .hasMessageContaining("gov-1");
  }

  @Test
  void unitWithoutGovernmentFormationThrowsIllegalState() {
    Unit plain = unitWithModule(Optional.empty(), Optional.of(H1), Optional.empty());
    GovState base = state(GovOfficeState.empty(U1, 0L));

    assertThatThrownBy(
            () ->
                GovDaily.settle(
                    base,
                    units(plain),
                    0L,
                    365L,
                    Map.of(),
                    new RecordingOracle((resource, requested) -> requested)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少 GovernmentFormation")
        .hasMessageContaining("gov-1");

    Unit army =
        unitWithModule(
            Optional.of(new ArmyFormation(Optional.empty(), "infantry")),
            Optional.of(H1),
            Optional.empty());
    assertThatThrownBy(
            () ->
                GovDaily.settle(
                    base,
                    units(army),
                    0L,
                    365L,
                    Map.of(),
                    new RecordingOracle((resource, requested) -> requested)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少 GovernmentFormation");
  }

  @Test
  void missingEfficiencyForKnownUnitThrowsIllegalState() {
    GovernmentFormation gov = gov(staff(1L, 0L, 0L), policy(0L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    GovState base = state(GovOfficeState.empty(U1, 0L));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    assertThatThrownBy(() -> GovDaily.settle(base, units, 3L, 365L, Map.of(), oracle))
        .as("Z3b 单次计算契约：app 必须给每个 office 当日结果；缺项不重算、不降级")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少 app 算好的效率读数")
        .hasMessageContaining("Z3b 单次计算契约故障");
    assertThat(oracle.calls()).isEmpty();
  }

  @Test
  void settleRejectsNegativeTickBeforeAnyWork() {
    GovernmentFormation gov = gov(staff(1L, 0L, 0L), policy(1L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    assertThatThrownBy(
            () ->
                settle(
                    state(GovOfficeState.empty(U1, 0L)), units, -1L, 365L, fullCoverage(), oracle))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tick 必须 ≥ 0");
    assertThat(oracle.calls()).isEmpty();
  }

  @Test
  void oracleReturningMoreThanRequestedThrows() {
    GovernmentFormation gov = gov(staff(1L, 0L, 0L), policy(1L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));

    assertThatThrownBy(
            () ->
                settle(
                    state(GovOfficeState.empty(U1, 0L)),
                    units,
                    0L,
                    365L,
                    fullCoverage(),
                    new RecordingOracle((resource, requested) -> requested + 1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PaymentOracle 违反契约")
        .hasMessageContaining("requested=1")
        .hasMessageContaining("实付=2");
  }

  @Test
  void oracleReturningNegativeThrows() {
    GovernmentFormation gov = gov(staff(1L, 0L, 0L), policy(1L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));

    assertThatThrownBy(
            () ->
                settle(
                    state(GovOfficeState.empty(U1, 0L)),
                    units,
                    0L,
                    365L,
                    fullCoverage(),
                    new RecordingOracle((resource, requested) -> -1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PaymentOracle 违反契约")
        .hasMessageContaining("实付=-1");
  }

  // ── 确定性 / 空状态 / 源状态拷贝纪律 / cloth 折日 ───────────────────────────────────

  @Test
  void sameInputTwoOutcomesAreEqualFieldByField() {
    GovernmentFormation gov = gov(staff(2L, 1L, 1L), policy(10L, 365L, 2L, 0L));
    Unit plain = govUnit(gov, Optional.of(H1), Optional.empty());
    GovState base = state(GovOfficeState.empty(U1, 0L));
    GovEfficiency.Efficiency efficiency =
        new GovEfficiency.Efficiency(
            1_200L, 900L, 0L, 1_080L, 1_200L, 900L, 16_000L, 15_000L, 13_333L, 16_666L);
    RecordingOracle first =
        new RecordingOracle((resource, requested) -> Math.max(0L, requested - 1L));
    RecordingOracle second =
        new RecordingOracle((resource, requested) -> Math.max(0L, requested - 1L));

    GovDaily.Outcome a = settle(base, units(plain), 6L, 365L, efficiency, first);
    GovDaily.Outcome b = settle(base, units(plain), 6L, 365L, efficiency, second);

    assertThat(b.next()).as("next 逐字段相等").isEqualTo(a.next());
    assertThat(b.dues()).as("dues 逐元素/逐值相等").isEqualTo(a.dues());
    assertThat(b.signals()).as("signals 逐元素/逐值相等").isEqualTo(a.signals());
    assertThat(b.changed()).isEqualTo(a.changed());
    assertThat(second.calls()).as("oracle 调用序与金额逐值相等").isEqualTo(first.calls());

    GovOfficeState officeA = a.next().offices().get(U1);
    GovOfficeState officeB = b.next().offices().get(U1);
    assertThat(officeB.tick()).isEqualTo(officeA.tick());
    assertThat(officeB.lastAssessedGoods()).isEqualTo(officeA.lastAssessedGoods());
    assertThat(officeB.lastPaidGoods()).isEqualTo(officeA.lastPaidGoods());
    assertThat(officeB.lastShortfallGoods()).isEqualTo(officeA.lastShortfallGoods());
    assertThat(officeB.efficiencyPerMille()).isEqualTo(officeA.efficiencyPerMille());
    assertThat(officeB.securityEfficiencyPerMille())
        .isEqualTo(officeA.securityEfficiencyPerMille());
  }

  @Test
  void emptyGovStateIsUnchangedAndNeverCallsOracle() {
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome =
        GovDaily.settle(
            GovState.empty(),
            units(
                govUnit(
                    gov(staff(1L, 0L, 0L), policy(1L, 0L, 0L, 0L)),
                    Optional.of(H1),
                    Optional.empty())),
            0L,
            365L,
            Map.of(),
            oracle);

    assertThat(outcome.next()).isEqualTo(GovState.empty());
    assertThat(outcome.dues()).isEmpty();
    assertThat(outcome.signals()).isEmpty();
    assertThat(outcome.changed()).as("GovState.empty() ⇒ 无变化").isFalse();
    assertThat(oracle.calls()).isEmpty();
  }

  @Test
  void settlePreservesAdministrationPlansAndBudgetPoliciesCopyDiscipline() {
    GovernmentFormation gov = gov(staff(1L, 0L, 0L), policy(0L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    GovAdministrationPlan plan =
        new GovAdministrationPlan(
            1_600_000L,
            800_000L,
            GovAdministrationPlan.DEFAULT_POST_TIERS,
            1_000L,
            1_000L,
            1_000L,
            1_000L,
            1L);
    GovBudgetPolicy policy =
        new GovBudgetPolicy(
            List.of(new GovBudgetLine(GovBudgetCategory.ADMIN_STIPEND, 0L, Long.MAX_VALUE)),
            new GovOfficialSalaryRule(1L, 2L));
    GovState base =
        new GovState(
            Map.of(U1, GovOfficeState.empty(U1, 0L)), Map.of(U1, plan), Map.of(U1, policy));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome = settle(base, units, 1L, 365L, fullCoverage(), oracle);

    assertThat(outcome.next().administrationPlans())
        .as("§4.1 拷贝纪律：结算只改 offices，两条源状态必须原样带过（漏带 = 静默清空配置）")
        .containsExactly(entry(U1, plan));
    assertThat(outcome.next().budgetPolicies()).containsExactly(entry(U1, policy));
    assertThat(outcome.next().offices().get(U1).tick()).isEqualTo(1L);
  }

  @Test
  void clothNeedFloorsPerStaffBeforeMultiplying() {
    // totalStaff=3、clothPerStaffPerCycle=500 ⇒ 每人每日 floor(500/365)=1，日需求 = 3。
    // 若误算成 3×500/365 = 4，本用例红。
    GovernmentFormation gov = gov(staff(1L, 1L, 1L), policy(0L, 500L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome =
        settle(state(GovOfficeState.empty(U1, 0L)), units, 2L, 365L, fullCoverage(), oracle);

    assertThat(oracle.calls())
        .as("只有 cloth 一次调用，请求量 = 3（floor 口径）")
        .containsExactly(new Call(U1, H1, "cloth", 3L));
    assertThat(outcome.next().offices().get(U1).lastAssessedGoods())
        .containsExactly(entry(GRAIN, 0L), entry(CLOTH, 3L));
    assertThat(outcome.dues())
        .containsExactly(
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(GRAIN), 0L, 0L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(CLOTH), 3L, 3L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Money(SILVER), 0L, 0L, 0L));
  }

  // ── 365/366：布料折日的显式边界（C4a/C4b）──────────────────────────────────────────

  /**
   * ★★ **显式 366 边界（1 名编制）**：{@code clothPerStaffPerCycle=730}，365 天年 ⇒ 每人每日 {@code
   * floor(730/365)=2}； 366 天年 ⇒ {@code floor(730/366)=1}。判别力：把年长写死 365（或忽略传参）⇒ 366 那条红。
   */
  @Test
  void clothNeedUsesTheSettlementYearsDayCountForOneStaff() {
    GovernmentFormation gov = gov(staff(1L, 0L, 0L), policy(0L, 730L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    GovState base = state(GovOfficeState.empty(U1, 0L));

    RecordingOracle commonYearOracle = new RecordingOracle((resource, requested) -> requested);
    GovDaily.Outcome commonYear = settle(base, units, 1L, 365L, fullCoverage(), commonYearOracle);
    assertThat(commonYearOracle.calls())
        .as("730/365 = 2 ⇒ 1 名编制的日需求 = 2")
        .containsExactly(new Call(U1, H1, "cloth", 2L));
    assertThat(commonYear.dues())
        .containsExactly(
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(GRAIN), 0L, 0L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(CLOTH), 2L, 2L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Money(SILVER), 0L, 0L, 0L));

    RecordingOracle leapYearOracle = new RecordingOracle((resource, requested) -> requested);
    GovDaily.Outcome leapYear = settle(base, units, 1L, 366L, fullCoverage(), leapYearOracle);
    assertThat(leapYearOracle.calls())
        .as("730/366 = 1（floor）⇒ 1 名编制的日需求 = 1")
        .containsExactly(new Call(U1, H1, "cloth", 1L));
    assertThat(leapYear.dues())
        .containsExactly(
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(GRAIN), 0L, 0L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Commodity(CLOTH), 1L, 1L, 0L),
            new GovDaily.UpkeepDue(U1, H1, new GovDaily.Money(SILVER), 0L, 0L, 0L));
  }

  /**
   * ★★ **逐位 floor，不是全式除法（3 名编制）**：365 天年 3×floor(730/365)=6；366 天年 3×floor(730/366)=3。 判别力：误写成
   * {@code totalStaff × cloth / daysInYear} ⇒ {@code 3×730/366 = 5} ⇒ 366 那条红。
   */
  @Test
  void clothNeedFloorsPerStaffBeforeMultiplyingInLeapYears() {
    GovernmentFormation gov = gov(staff(1L, 1L, 1L), policy(0L, 730L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    GovState base = state(GovOfficeState.empty(U1, 0L));

    RecordingOracle commonYearOracle = new RecordingOracle((resource, requested) -> requested);
    settle(base, units, 1L, 365L, fullCoverage(), commonYearOracle);
    assertThat(commonYearOracle.calls())
        .as("3×floor(730/365) = 6")
        .containsExactly(new Call(U1, H1, "cloth", 6L));

    RecordingOracle leapYearOracle = new RecordingOracle((resource, requested) -> requested);
    settle(base, units, 1L, 366L, fullCoverage(), leapYearOracle);
    assertThat(leapYearOracle.calls())
        .as("3×floor(730/366) = 3；误写成 3×730/366 = 5 时本断言红")
        .containsExactly(new Call(U1, H1, "cloth", 3L));
  }

  /** ★ **年长护栏**：{@code daysInYearAtSettlement} 只接受 365/366；364、0 在评估前当场抛（拒绝臆造年长）。 */
  @Test
  void settleRejectsDaysInYearOtherThan365Or366() {
    GovernmentFormation gov = gov(staff(1L, 0L, 0L), policy(0L, 730L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    GovState base = state(GovOfficeState.empty(U1, 0L));

    assertThatThrownBy(
            () ->
                settle(
                    base,
                    units,
                    0L,
                    364L,
                    fullCoverage(),
                    new RecordingOracle((resource, requested) -> requested)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("365 或 366");
    assertThatThrownBy(
            () ->
                settle(
                    base,
                    units,
                    0L,
                    0L,
                    fullCoverage(),
                    new RecordingOracle((resource, requested) -> requested)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("365 或 366");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private record Call(UnitId unitId, HexCoord at, String resource, long requested) {}

  @FunctionalInterface
  private interface PaymentOutcome {
    long pay(GovDaily.GovResource resource, long requested);
  }

  /** 记录调用序的假 oracle（纯函数：付款额只由 resource/requested 决定）。 */
  private static final class RecordingOracle implements GovDaily.PaymentOracle {

    private final PaymentOutcome outcome;
    private final List<Call> calls = new ArrayList<>();

    /** ★ L3 起 {@code pay} 追加第 5 参 {@code day}（结算 tick）；并行记录，验证它真被透传。 */
    private final List<Long> days = new ArrayList<>();

    RecordingOracle(PaymentOutcome outcome) {
      this.outcome = outcome;
    }

    List<Call> calls() {
      return List.copyOf(calls);
    }

    List<Long> days() {
      return List.copyOf(days);
    }

    @Override
    public long pay(
        UnitId unitId, HexCoord at, GovDaily.GovResource resource, long requested, long day) {
      calls.add(new Call(unitId, at, resource.name(), requested));
      days.add(day);
      return outcome.pay(resource, requested);
    }
  }

  /** 结算入口：当日效率表只有本夹具里的 U1。 */
  private static GovDaily.Outcome settle(
      GovState govState,
      UnitState units,
      long tick,
      long daysInYearAtSettlement,
      GovEfficiency.Efficiency efficiency,
      GovDaily.PaymentOracle oracle) {
    return GovDaily.settle(
        govState, units, tick, daysInYearAtSettlement, Map.of(U1, efficiency), oracle);
  }

  /** 两维满覆盖（coverage 1000/1000、维效率 1000/1000、总 1000），供与覆盖缺口无关的用例使用。 */
  private static GovEfficiency.Efficiency fullCoverage() {
    return new GovEfficiency.Efficiency(
        1_000L, 1_000L, 0L, 1_000L, 1_000L, 1_000L, 100L, 100L, 100L, 100L);
  }

  private static GovState state(GovOfficeState office) {
    return new GovState(Map.of(U1, office));
  }

  private static UnitState units(Unit unit) {
    return new UnitState(new LinkedHashMap<>(Map.of(U1, unit)));
  }

  private static Unit govUnit(
      GovernmentFormation gov, Optional<HexCoord> position, Optional<Jurisdiction> jurisdiction) {
    return unitWithModule(Optional.of(gov), position, jurisdiction);
  }

  private static Unit unitWithModule(
      Optional<UnitModule> module,
      Optional<HexCoord> position,
      Optional<Jurisdiction> jurisdiction) {
    // ★ S3b/P2-C：带 GovernmentFormation 的单位必须恰含派生的政府家户 hh-gov-<unitId>，
    //   否则 UnitState 构造期具名拒（GOV 家户身份 = 单位 id 的纯函数）。
    List<HouseholdId> households =
        module.orElse(null) instanceof GovernmentFormation
            ? List.of(GovernmentHouseholds.of(U1.value()))
            : List.of();
    return new Unit(
        U1,
        "gov-unit",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        List.of(),
        1,
        1000,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        jurisdiction,
        module,
        Map.of(),
        households);
  }

  private static GovernmentFormation gov(Map<StaffRole, Long> staff, OfficePolicy policy) {
    return new GovernmentFormation(
        staff, Map.of(), policy, Optional.empty(), GovernmentLevel.CENTRAL, Map.of());
  }

  private static Map<StaffRole, Long> staff(long yamen, long scribe, long post) {
    Map<StaffRole, Long> staff = new LinkedHashMap<>();
    staff.put(StaffRole.YAMEN, yamen);
    staff.put(StaffRole.SCRIBE, scribe);
    staff.put(StaffRole.POST, post);
    return staff;
  }

  private static OfficePolicy policy(long grain, long clothCycle, long money, long retirement) {
    return new OfficePolicy(grain, clothCycle, money, retirement, Map.of());
  }

  private static Jurisdiction jurisdiction(io.mosire.simos.map.region.RegionId region) {
    Map<io.mosire.simos.map.region.RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(region, 100L);
    return new Jurisdiction(rates, 0L, 0L, 0L, 0L);
  }
}
