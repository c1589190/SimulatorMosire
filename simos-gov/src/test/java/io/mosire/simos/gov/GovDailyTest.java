package io.mosire.simos.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.Sex;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
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
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link GovDaily#settle} 的逐值判据（阶段 11a，计划 §2.2 / §3）：
 *
 * <ul>
 *   <li>资源顺序固定 grain → cloth → money（oracle 调用序 = 评估序，0 需求不发）；
 *   <li>全额/部分/零支付 ⇒ dues 的 assessed/paid/shortfall 逐值；缺口 ⇒ 每 office 至多一条 ADMIN_SUPPLY，evidence =
 *       三资源合计；
 *   <li>覆盖率不足 ⇒ ADMIN_SECURITY / ADMIN_PAPERWORK 各自独立，evidence 带 coverage/supply/demand；
 *   <li>无位置 ⇒ dues/signals 空、六表空、efficiency/tick 仍更新；状态损坏（缺单位/缺 GovFormation）⇒
 *       IllegalStateException；oracle 越界 ⇒ IllegalArgumentException；
 *   <li>确定性：同输入两次 Outcome 逐字段相等；{@link GovState#empty()} ⇒ changed=false；
 *   <li>布料折日：{@code clothNeed = totalStaff × floor(clothPerStaffPerCycle/365)}，逐值钉 floor。
 * </ul>
 *
 * <p>★ 判别力：每个用例都断到具体数字/键序/调用序；不用“非 null、不抛”代替语义断言。
 */
class GovDailyTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0L);
  private static final HexCoord H1 = new HexCoord(1, 1);
  private static final HexCoord H2 = new HexCoord(2, 2);
  private static final UnitId U1 = new UnitId("gov-1");
  private static final RegionId R1 = new RegionId("r-1");
  private static final CommodityId GRAIN = CommodityId.parse(EconomyVocabulary.GRAIN_COMMODITY_ID);
  private static final CommodityId CLOTH = CommodityId.parse(EconomyVocabulary.CLOTH_COMMODITY_ID);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  // ── 全额支付：顺序与逐值 ────────────────────────────────────────────────────────────

  @Test
  void fullPaymentPaysGrainThenClothThenMoneyAndWritesAllSixTables() {
    GovFormation gov = gov(staff(1L, 1L, 1L), policy(10L, 500L, 3L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    GovState base = state(GovOfficeState.empty(U1, 0L));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome = GovDaily.settle(base, units, map(), SocialData.empty(), 7L, oracle);

    // totalStaff=3 ⇒ grain 30；cloth = 3×floor(500/365)=3×1=3（若误写成 3×500/365 会是 4）；money 9。
    assertThat(oracle.calls())
        .as("oracle 调用序 = 资源评估序 grain → cloth → money，金额逐值")
        .containsExactly(
            new Call(U1, H1, "grain", 30L),
            new Call(U1, H1, "cloth", 3L),
            new Call(U1, H1, "silver", 9L));
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
    assertThat(office.securityCoveragePerMille()).as("无管辖 ⇒ 无需求 ⇒ 全覆盖").isEqualTo(1000L);
    assertThat(office.paperworkCoveragePerMille()).isEqualTo(1000L);
    assertThat(office.efficiencyPerMille()).isEqualTo(1000L);
    assertThat(office.bonusPerMille()).isZero();
  }

  @Test
  void zeroNeedResourceIsNotSentToOracle() {
    GovFormation gov = gov(staff(2L, 0L, 0L), policy(10L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome =
        GovDaily.settle(
            state(GovOfficeState.empty(U1, 0L)), units, map(), SocialData.empty(), 1L, oracle);

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
    GovFormation gov = gov(staff(1L, 1L, 0L), policy(10L, 365L, 5L, 0L));
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
        GovDaily.settle(
            state(GovOfficeState.empty(U1, 0L)), units, map(), SocialData.empty(), 3L, oracle);

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
    GovFormation gov = gov(staff(1L, 1L, 0L), policy(10L, 365L, 5L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> 0L);

    GovDaily.Outcome outcome =
        GovDaily.settle(
            state(GovOfficeState.empty(U1, 0L)), units, map(), SocialData.empty(), 2L, oracle);

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

  // ── 覆盖率不足：两类信号各自独立 ────────────────────────────────────────────────────

  @Test
  void securityAndPaperworkShortfallsEmitIndependentSignalsWithCoverageEvidence() {
    // 人口 100000、非城市 ⇒ 需求 (200,100)；staff YAMEN=100、SCRIBE=POST=0 ⇒ coverage 500/0。
    GovFormation gov = gov(staff(100L, 0L, 0L), policy(0L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.of(jurisdiction(R1))));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome =
        GovDaily.settle(
            state(GovOfficeState.empty(U1, 0L)),
            units,
            map(region(R1, H1)),
            socialWithPopulation(100_000L),
            4L,
            oracle);

    assertThat(oracle.calls()).as("policy 全 0 ⇒ 不发付款调用").isEmpty();
    assertThat(outcome.signals())
        .extracting(GovDaily.SignalDraft::kind)
        .as("两类覆盖不足各自一条，顺序 supply → security → paperwork")
        .containsExactly(GovDaily.KIND_ADMIN_SECURITY, GovDaily.KIND_ADMIN_PAPERWORK);

    GovDaily.SignalDraft security = outcome.signals().get(0);
    assertThat(security.hex()).isEqualTo(H1);
    assertThat(security.severity()).isEqualTo(1L);
    assertThat(security.evidence())
        .as("治安 evidence：coverage=floor(100×1000/200)=500、supply=100、demand=200")
        .containsExactly(
            entry("coveragePerMille", 500L), entry("supply", 100L), entry("demand", 200L));
    assertThat(security.reason()).contains("治安覆盖率 500‰");

    GovDaily.SignalDraft paperwork = outcome.signals().get(1);
    assertThat(paperwork.evidence())
        .as("文书 evidence：coverage=0、supply=0、demand=100")
        .containsExactly(entry("coveragePerMille", 0L), entry("supply", 0L), entry("demand", 100L));
    assertThat(paperwork.reason()).contains("文书覆盖率 0‰");

    GovOfficeState office = outcome.next().offices().get(U1);
    assertThat(office.securityCoveragePerMille()).isEqualTo(500L);
    assertThat(office.paperworkCoveragePerMille()).isZero();
    assertThat(office.efficiencyPerMille()).as("coverageMin=0 ⇒ 效率 0").isZero();
  }

  @Test
  void oneSidedCoverageShortfallEmitsOnlyItsOwnSignal() {
    // 治安满、文书 0 ⇒ 只发 ADMIN_PAPERWORK。
    GovFormation paperworkShort = gov(staff(200L, 0L, 0L), policy(0L, 0L, 0L, 0L));
    GovDaily.Outcome onlyPaper =
        GovDaily.settle(
            state(GovOfficeState.empty(U1, 0L)),
            units(govUnit(paperworkShort, Optional.of(H1), Optional.of(jurisdiction(R1)))),
            map(region(R1, H1)),
            socialWithPopulation(100_000L),
            1L,
            new RecordingOracle((resource, requested) -> requested));
    assertThat(onlyPaper.signals())
        .extracting(GovDaily.SignalDraft::kind)
        .containsExactly(GovDaily.KIND_ADMIN_PAPERWORK);

    // 治安 100（500‰）、文书 100（1000‰）⇒ 只发 ADMIN_SECURITY。
    GovFormation securityShort = gov(staff(100L, 100L, 0L), policy(0L, 0L, 0L, 0L));
    GovDaily.Outcome onlySecurity =
        GovDaily.settle(
            state(GovOfficeState.empty(U1, 0L)),
            units(govUnit(securityShort, Optional.of(H1), Optional.of(jurisdiction(R1)))),
            map(region(R1, H1)),
            socialWithPopulation(100_000L),
            1L,
            new RecordingOracle((resource, requested) -> requested));
    assertThat(onlySecurity.signals())
        .extracting(GovDaily.SignalDraft::kind)
        .containsExactly(GovDaily.KIND_ADMIN_SECURITY);
  }

  // ── 无位置 ─────────────────────────────────────────────────────────────────────────

  @Test
  void noSeatSkipsDuesAndSignalsButStillUpdatesEfficiencyAndTick() {
    GovFormation gov = gov(staff(0L, 0L, 0L), policy(100L, 365L, 100L, 0L));
    UnitState units = units(govUnit(gov, Optional.empty(), Optional.of(jurisdiction(R1))));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome =
        GovDaily.settle(
            state(GovOfficeState.empty(U1, 0L)),
            units,
            map(region(R1, H1)),
            socialWithPopulation(100_000L),
            5L,
            oracle);

    assertThat(oracle.calls()).as("无位置不评估、不付款").isEmpty();
    assertThat(outcome.dues()).isEmpty();
    assertThat(outcome.signals()).as("无位置不发明 ADMIN_NO_SEAT 之类 kind").isEmpty();
    assertThat(outcome.changed()).isTrue();

    GovOfficeState office = outcome.next().offices().get(U1);
    assertThat(office.tick()).as("efficiency 与 tick 仍更新").isEqualTo(5L);
    assertThat(office.securityCoveragePerMille()).isZero();
    assertThat(office.paperworkCoveragePerMille()).isZero();
    assertThat(office.efficiencyPerMille()).isZero();
    assertThat(office.lastAssessedGoods()).as("六表清空 = 本日没有结算事实").isEmpty();
    assertThat(office.lastPaidGoods()).isEmpty();
    assertThat(office.lastShortfallGoods()).isEmpty();
    assertThat(office.lastAssessedMoney()).isEmpty();
    assertThat(office.lastPaidMoney()).isEmpty();
    assertThat(office.lastShortfallMoney()).isEmpty();
  }

  // ── 状态损坏 / oracle 契约 ─────────────────────────────────────────────────────────

  @Test
  void officeReferencingMissingUnitThrowsIllegalState() {
    GovState base = state(GovOfficeState.empty(U1, 0L));
    UnitState emptyUnits = UnitState.empty();

    assertThatThrownBy(
            () ->
                GovDaily.settle(
                    base,
                    emptyUnits,
                    map(),
                    SocialData.empty(),
                    0L,
                    new RecordingOracle((resource, requested) -> requested)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不存在的单位")
        .hasMessageContaining("gov-1");
  }

  @Test
  void unitWithoutGovFormationThrowsIllegalState() {
    Unit plain = unitWithModule(Optional.empty(), Optional.of(H1), Optional.empty());
    GovState base = state(GovOfficeState.empty(U1, 0L));

    assertThatThrownBy(
            () ->
                GovDaily.settle(
                    base,
                    units(plain),
                    map(),
                    SocialData.empty(),
                    0L,
                    new RecordingOracle((resource, requested) -> requested)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少 GovFormation")
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
                    map(),
                    SocialData.empty(),
                    0L,
                    new RecordingOracle((resource, requested) -> requested)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少 GovFormation");
  }

  @Test
  void oracleReturningMoreThanRequestedThrows() {
    GovFormation gov = gov(staff(1L, 0L, 0L), policy(1L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));

    assertThatThrownBy(
            () ->
                GovDaily.settle(
                    state(GovOfficeState.empty(U1, 0L)),
                    units,
                    map(),
                    SocialData.empty(),
                    0L,
                    new RecordingOracle((resource, requested) -> requested + 1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PaymentOracle 违反契约")
        .hasMessageContaining("requested=1")
        .hasMessageContaining("实付=2");
  }

  @Test
  void oracleReturningNegativeThrows() {
    GovFormation gov = gov(staff(1L, 0L, 0L), policy(1L, 0L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));

    assertThatThrownBy(
            () ->
                GovDaily.settle(
                    state(GovOfficeState.empty(U1, 0L)),
                    units,
                    map(),
                    SocialData.empty(),
                    0L,
                    new RecordingOracle((resource, requested) -> -1L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PaymentOracle 违反契约")
        .hasMessageContaining("实付=-1");
  }

  // ── 确定性 / 空状态 / cloth 折日 ───────────────────────────────────────────────────

  @Test
  void sameInputTwoOutcomesAreEqualFieldByField() {
    GovFormation gov = gov(staff(2L, 1L, 1L), policy(10L, 365L, 2L, 0L));
    Unit plain = govUnit(gov, Optional.of(H1), Optional.empty());
    GovState base = state(GovOfficeState.empty(U1, 0L));
    RecordingOracle first =
        new RecordingOracle((resource, requested) -> Math.max(0L, requested - 1L));
    RecordingOracle second =
        new RecordingOracle((resource, requested) -> Math.max(0L, requested - 1L));

    GovDaily.Outcome a = GovDaily.settle(base, units(plain), map(), SocialData.empty(), 6L, first);
    GovDaily.Outcome b = GovDaily.settle(base, units(plain), map(), SocialData.empty(), 6L, second);

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
            map(),
            SocialData.empty(),
            0L,
            oracle);

    assertThat(outcome.next()).isEqualTo(GovState.empty());
    assertThat(outcome.dues()).isEmpty();
    assertThat(outcome.signals()).isEmpty();
    assertThat(outcome.changed()).as("GovState.empty() ⇒ 无变化").isFalse();
    assertThat(oracle.calls()).isEmpty();
  }

  @Test
  void clothNeedFloorsPerStaffBeforeMultiplying() {
    // totalStaff=3、clothPerStaffPerCycle=500 ⇒ 每人每日 floor(500/365)=1，日需求 = 3。
    // 若误算成 3×500/365 = 4，本用例红。
    GovFormation gov = gov(staff(1L, 1L, 1L), policy(0L, 500L, 0L, 0L));
    UnitState units = units(govUnit(gov, Optional.of(H1), Optional.empty()));
    RecordingOracle oracle = new RecordingOracle((resource, requested) -> requested);

    GovDaily.Outcome outcome =
        GovDaily.settle(
            state(GovOfficeState.empty(U1, 0L)), units, map(), SocialData.empty(), 2L, oracle);

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

    RecordingOracle(PaymentOutcome outcome) {
      this.outcome = outcome;
    }

    List<Call> calls() {
      return List.copyOf(calls);
    }

    @Override
    public long pay(UnitId unitId, HexCoord at, GovDaily.GovResource resource, long requested) {
      calls.add(new Call(unitId, at, resource.name(), requested));
      return outcome.pay(resource, requested);
    }
  }

  private static GovState state(GovOfficeState office) {
    return new GovState(Map.of(U1, office));
  }

  private static UnitState units(Unit unit) {
    return new UnitState(new LinkedHashMap<>(Map.of(U1, unit)));
  }

  private static Unit govUnit(
      GovFormation gov, Optional<HexCoord> position, Optional<Jurisdiction> jurisdiction) {
    return unitWithModule(Optional.of(gov), position, jurisdiction);
  }

  private static Unit unitWithModule(
      Optional<UnitModule> module,
      Optional<HexCoord> position,
      Optional<Jurisdiction> jurisdiction) {
    return new Unit(
        U1,
        "gov-unit",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        1,
        Map.of(),
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
        module);
  }

  private static GovFormation gov(Map<StaffRole, Long> staff, OfficePolicy policy) {
    return new GovFormation(staff, policy, Optional.empty(), GovLevel.CENTRAL);
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

  private static Jurisdiction jurisdiction(RegionId region) {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(region, 100L);
    return new Jurisdiction(rates, 0L, 0L, 0L, 0L);
  }

  private static Region region(RegionId id, HexCoord hex) {
    return Region.of(id, id.value(), Set.of(hex), RegionMeta.empty());
  }

  private static GameMap map(Region... regions) {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H1, new HexCell(0.5));
    hexes.put(H2, new HexCell(0.5));
    Map<RegionId, Region> regionMap = new LinkedHashMap<>();
    for (Region region : regions) {
      regionMap.put(region.id(), region);
    }
    TerrainType desert = TerrainCatalog.of("desert");
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regionMap,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static SocialData socialWithPopulation(long count) {
    PeopleLotId lot = new PeopleLotId("lot-1");
    PopulationGroup group = new PopulationGroup(lot, H1, Sex.MALE, count, 20L * 365L, 0L);
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    populations.put(
        H1,
        new PopulationSeries(
            new Segment<>(T0, 1000L),
            new SegmentedSeries<>(List.of(new Segment<>(T0, 0.0)), List.of(), null),
            List.of()));
    return new SocialData(populations, Map.of(), Map.of(lot, group));
  }
}
