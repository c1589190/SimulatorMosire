package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.Sex;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link LevyRegionPlan} 纯推导（收尾期 T2，按计划 §6.0 / §6.1 逐条；阶段 11b 起含 cloth 维度）：单位 / 管辖 / 区域三道存在性、四项全
 * 0、单命令上限（含 cap=0）、国库落点、粮钱布人各自的瀑布与不足整条拒、requested=0 维度整段跳过、同状态同参数逐字段确定。
 *
 * <p>★ 判别力：happy 路径把**每个来源的 (owner,格,额)** 与**国库落点**逐值钉住；拒因断言带 requested / available / 缺口 / cap 数字。★
 * 本类不碰 {@code ToolContext} / {@code CoreSimos}（那是 {@code LevyRegionToolTest} 的端到端面）。
 */
class LevyRegionPlanTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final RegionId NATION = new RegionId("r-nation");

  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);
  private static final CommodityId CLOTH = new CommodityId(PilotModel.CLOTH);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private static final ActorRef HH1 = new ActorRef(ActorKind.HOUSEHOLD, "hh-1");
  private static final ActorRef HH2 = new ActorRef(ActorKind.HOUSEHOLD, "hh-2");
  private static final ActorRef HH_ZERO = new ActorRef(ActorKind.HOUSEHOLD, "hh-zero");
  private static final ActorRef HH_OUT = new ActorRef(ActorKind.HOUSEHOLD, "hh-out");
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "e-1");

  // ── 拒因：单位 / 管辖 / 区域 / 上限 / 位置 ──────────────────────────────────────────────

  @Test
  void rejectsUnknownUnit() {
    assertThatThrownBy(() -> plan(baseUnit(), "u-404", "r-nation", 1L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在: u-404");
  }

  @Test
  void rejectsUnitWithoutJurisdiction() {
    Unit unit = unitWithPosition(H11, Optional.empty());

    assertThatThrownBy(() -> plan(unit, "u-1", "r-nation", 1L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有 jurisdiction")
        .hasMessageContaining("unit.SetJurisdiction");
  }

  @Test
  void rejectsRegionOutsideJurisdiction() {
    Unit unit =
        unitWithPosition(
            H11, Optional.of(jurisdiction(Map.of(new RegionId("r-other"), 0L), 10L, 10L, 10L)));

    assertThatThrownBy(() -> plan(unit, "u-1", "r-nation", 1L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("管辖不含区域 r-nation")
        .hasMessageContaining("unit.SetJurisdiction");
  }

  @Test
  void rejectsRegionMissingFromMap() {
    Unit unit =
        unitWithPosition(
            H11, Optional.of(jurisdiction(Map.of(new RegionId("r-ghost"), 0L), 10L, 10L, 10L)));

    assertThatThrownBy(() -> plan(unit, "u-1", "r-ghost", 1L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("地图里没有区域: r-ghost")
        .hasMessageContaining("map.CreateRegion");
  }

  @Test
  void rejectsAllZeroRequest() {
    assertThatThrownBy(() -> plan(baseUnit(), "u-1", "r-nation", 0L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("四项全为 0");
  }

  @Test
  void rejectsNegativeAmounts() {
    assertThatThrownBy(() -> plan(baseUnit(), "u-1", "r-nation", -1L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("grain 不得为负");
    assertThatThrownBy(() -> plan(baseUnit(), "u-1", "r-nation", 0L, 0L, -1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cloth 不得为负");
    assertThatThrownBy(() -> plan(baseUnit(), "u-1", "r-nation", 0L, 0L, 0L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("manpower 不得为负");
  }

  /** ★ 单命令上限：逐维各判一次；cap=0 = 该类无额度，requested &gt; 0 即拒。 */
  @Test
  void rejectsEachDimensionOverItsSingleCommandCapIncludingZeroCap() {
    Unit unit =
        unitWithPosition(H11, Optional.of(jurisdiction(Map.of(NATION, 100L), 100L, 0L, 5L)));

    assertThatThrownBy(() -> plan(unit, "u-1", "r-nation", 101L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("粮 requested=101 超过 levyGrainCapPerCommand=100");
    assertThatThrownBy(() -> plan(unit, "u-1", "r-nation", 0L, 1L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("钱 requested=1 超过 levyMoneyCapPerCommand=0");
    assertThatThrownBy(() -> plan(unit, "u-1", "r-nation", 0L, 0L, 0L, 6L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人力 requested=6 超过 levyManpowerCapPerCommand=5");
  }

  @Test
  void rejectsUnitWithoutAnEffectivePosition() {
    Unit unit = unitWithPosition(Optional.empty(), Optional.of(defaultJurisdiction()));

    assertThatThrownBy(() -> plan(unit, "u-1", "r-nation", 1L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("当刻没有有效位置")
        .hasMessageContaining("unit.PlaceAt");
  }

  // ── 拒因：三个维度各自的不足（整条拒 + 缺口）──────────────────────────────────────────

  @Test
  void rejectsEachDimensionWhenItsSourcesAreInsufficient() {
    assertThatThrownBy(() -> plan(baseUnit(), "u-1", "r-nation", 121L, 0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("粮总量不足")
        .hasMessageContaining("requested=121")
        .hasMessageContaining("available=120")
        .hasMessageContaining("缺口=1");
    assertThatThrownBy(() -> plan(baseUnit(), "u-1", "r-nation", 0L, 131L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("钱总量不足")
        .hasMessageContaining("requested=131")
        .hasMessageContaining("available=130")
        .hasMessageContaining("缺口=1");
    assertThatThrownBy(() -> plan(baseUnit(), "u-1", "r-nation", 0L, 0L, 0L, 51L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人力总量不足")
        .hasMessageContaining("requested=51")
        .hasMessageContaining("available=50")
        .hasMessageContaining("缺口=1");
  }

  // ── cloth（阶段 11b）：只受可用量约束，逐值 + 无上限 + 不足整条拒 ─────────────────────────

  /** ★ 只抽布（粮 / 钱 / 人 = 0）：cloth 维度逐值，其余三维修道为 {@link LevyRegionPlan.Dimension#skipped()}。 */
  @Test
  void clothOnlyPlanAllocatesByAvailabilityWaterfallAndSkipsTheOtherDimensions() {
    LevyRegionPlan.Plan plan = plan(baseUnit(), "u-1", "r-nation", 0L, 0L, 70L, 0L);

    assertThat(plan.cloth().requested()).as("cloth requested = 70").isEqualTo(70L);
    assertThat(plan.cloth().available())
        .as("布可支配 = (60−10) + (70−20) = 100；区外 hh-out、ESTATE、可用 0 的 hh-zero 都不进合计")
        .isEqualTo(100L);
    assertThat(plan.cloth().sources())
        .as("布瀑布：两户可用量同为 50，按账键升序 hh-1 先扣 50、hh-2 再扣 20")
        .containsExactly(
            new LevyRegionPlan.AccountSource(HH1, H11, 50L),
            new LevyRegionPlan.AccountSource(HH2, H12, 20L));

    assertThat(plan.grain().requested()).as("grain=0 ⇒ 整维跳过：requested=0").isZero();
    assertThat(plan.grain().available()).as("grain=0 ⇒ available=0（未求值）").isZero();
    assertThat(plan.grain().sources()).as("grain=0 ⇒ 不扫描来源").isEmpty();
    assertThat(plan.grain()).as("grain 维度 = 规范空维度").isEqualTo(LevyRegionPlan.Dimension.skipped());
    assertThat(plan.money().requested()).as("money=0 ⇒ 整维跳过：requested=0").isZero();
    assertThat(plan.money().available()).as("money=0 ⇒ available=0（未求值）").isZero();
    assertThat(plan.money().sources()).as("money=0 ⇒ 不扫描来源").isEmpty();
    assertThat(plan.money()).as("money 维度 = 规范空维度").isEqualTo(LevyRegionPlan.Dimension.skipped());
    assertThat(plan.manpower().requested()).as("manpower=0 ⇒ 整维跳过：requested=0").isZero();
    assertThat(plan.manpower().available()).as("manpower=0 ⇒ available=0（未求值）").isZero();
    assertThat(plan.manpower().sources()).as("manpower=0 ⇒ 不扫描批次").isEmpty();
    assertThat(plan.manpower())
        .as("manpower 维度 = 规范空维度")
        .isEqualTo(LevyRegionPlan.Manpower.skipped());

    assertThat(plan.hasAccountMovements()).as("只有 cloth > 0 ⇒ 需要 AdjustAccounts").isTrue();
    assertThat(plan.hasManpower()).as("manpower=0 ⇒ 没有 SeedGroups").isFalse();
    assertThat(plan.treasuryLocation()).as("国库落点仍是单位当刻有效位置").isEqualTo(H11);
  }

  /** ★ cloth 可用量 100：请求 101 ⇒ 整条拒（不部分、不截断），拒因带 requested / available / 缺口逐值。 */
  @Test
  void rejectsClothWhenHouseholdAvailabilityIsShortByOne() {
    assertThatThrownBy(() -> plan(baseUnit(), "u-1", "r-nation", 0L, 0L, 101L, 0L))
        .as("cloth 请求 = 可用量 100 + 1 ⇒ 整条拒（带 requested / available / 缺口）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("布总量不足")
        .hasMessageContaining("requested=101")
        .hasMessageContaining("available=100")
        .hasMessageContaining("缺口=1");
  }

  /**
   * ★ cloth **没有第四条单命令上限**：三条 cap 都 = 1，仍可成功申请 100（= 全部可用量）；请求 +1 时按可用量拒（不是按 cap 拒）。对照粮请求 2 > cap
   * 1：粮仍按 cap 拒 —— 上限只作用于粮 / 钱 / 人。
   */
  @Test
  void clothHasNoSingleCommandCapAndOnlyAvailabilityBoundsIt() {
    Unit unit = unitWithPosition(H11, Optional.of(jurisdiction(Map.of(NATION, 100L), 1L, 1L, 1L)));

    LevyRegionPlan.Plan plan = plan(unit, "u-1", "r-nation", 0L, 0L, 100L, 0L);

    assertThat(plan.cloth().requested()).as("cloth 请求 100 ≫ 三条 cap=1，仍成功").isEqualTo(100L);
    assertThat(plan.cloth().available()).as("cloth 的 available 只由家户可用量决定，与 cap 无关").isEqualTo(100L);
    assertThat(plan.cloth().sources())
        .as("请求 = 全部可用量 ⇒ 两户各扣满 50")
        .containsExactly(
            new LevyRegionPlan.AccountSource(HH1, H11, 50L),
            new LevyRegionPlan.AccountSource(HH2, H12, 50L));
    assertThat(plan.hasAccountMovements()).as("只有 cloth > 0 ⇒ 需要 AdjustAccounts").isTrue();

    assertThatThrownBy(() -> plan(unit, "u-1", "r-nation", 2L, 0L, 0L, 0L))
        .as("对照：粮请求 2 仍被 levyGrainCapPerCommand=1 拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("粮 requested=2 超过 levyGrainCapPerCommand=1");

    assertThatThrownBy(() -> plan(unit, "u-1", "r-nation", 0L, 0L, 101L, 0L))
        .as("对照：cloth 超可用量 1 ⇒ 仍整条拒（拒因是可用量，不是 cap）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("布总量不足")
        .hasMessageContaining("requested=101")
        .hasMessageContaining("available=100")
        .hasMessageContaining("缺口=1");
  }

  // ── happy：来源逐值 + 国库落点 ───────────────────────────────────────────────────────

  /** ★ 三项各自的来源（owner / 格 / 额）与国库落点逐值：粮 = 80+40、钱 = 80+20、人 = 30+10。 */
  @Test
  void happyPlanCarriesEverySourceAndTheTreasuryLocation() {
    LevyRegionPlan.Plan plan = plan(baseUnit(), "u-1", "r-nation", 120L, 100L, 0L, 40L);

    assertThat(plan.unitId()).isEqualTo("u-1");
    assertThat(plan.regionId()).isEqualTo("r-nation");
    assertThat(plan.tick()).as("tick 取状态时刻（人力的年龄现算与 INFO 记录都用它）").isEqualTo(7L);
    assertThat(plan.treasuryLocation()).as("国库落点 = 单位当刻有效位置（不是单位 id、不是区域）").isEqualTo(H11);

    assertThat(plan.grain().requested()).isEqualTo(120L);
    assertThat(plan.grain().available())
        .as("家户粮可支配 = (100−20) + 40；ESTATE / 区外 / 可用 0 都不进合计")
        .isEqualTo(120L);
    assertThat(plan.grain().sources())
        .as("粮瀑布：hh-1（可用 80）先扣满，再 hh-2（可用 40）")
        .containsExactly(
            new LevyRegionPlan.AccountSource(HH1, H11, 80L),
            new LevyRegionPlan.AccountSource(HH2, H12, 40L));

    assertThat(plan.money().requested()).isEqualTo(100L);
    assertThat(plan.money().available()).as("钱可支配合计 = 50 + 80").isEqualTo(130L);
    assertThat(plan.money().sources())
        .as("钱瀑布按可用量降序：hh-2（80）先，再 hh-1（50 里只扣 20）")
        .containsExactly(
            new LevyRegionPlan.AccountSource(HH2, H12, 80L),
            new LevyRegionPlan.AccountSource(HH1, H11, 20L));

    assertThat(plan.manpower().requested()).isEqualTo(40L);
    assertThat(plan.manpower().available())
        .as("人力合计 = MALE + 成年：30 + 20（女性 / 未成年 / 老年 / 区外 / 空批都不算）")
        .isEqualTo(50L);
    assertThat(plan.manpower().sources())
        .extracting(source -> source.group().id().value())
        .containsExactly("g1", "g2");
    assertThat(plan.manpower().sources())
        .extracting(LevyRegionPlan.GroupSource::taken)
        .containsExactly(30L, 10L);

    // ★ 阶段 11b：cloth=0 的维度整段跳过（不扫描、不产生来源、available 记 0 = 未求值）。
    assertThat(plan.cloth().requested()).isZero();
    assertThat(plan.cloth().available()).as("cloth 0 = 未求值").isZero();
    assertThat(plan.cloth().sources()).isEmpty();

    assertThat(plan.hasAccountMovements()).isTrue();
    assertThat(plan.hasManpower()).isTrue();
  }

  /** ★ requested = 0 的维度**整段跳过**：不扫描、不产生来源条目、不建账（结果 = 规范空维度）。 */
  @Test
  void zeroRequestedDimensionsAreSkippedEntirely() {
    LevyRegionPlan.Plan grainOnly = plan(baseUnit(), "u-1", "r-nation", 120L, 0L, 0L, 0L);

    assertThat(grainOnly.money().requested()).isZero();
    assertThat(grainOnly.money().available()).as("0 = 未求值（不是『恰好没有来源』）").isZero();
    assertThat(grainOnly.money().sources()).isEmpty();
    assertThat(grainOnly.cloth().requested()).isZero();
    assertThat(grainOnly.cloth().available()).isZero();
    assertThat(grainOnly.cloth().sources()).isEmpty();
    assertThat(grainOnly.manpower().requested()).isZero();
    assertThat(grainOnly.manpower().available()).isZero();
    assertThat(grainOnly.manpower().sources()).isEmpty();
    assertThat(grainOnly.hasManpower()).as("人力 0 ⇒ 批次里没有 SeedGroups").isFalse();
    assertThat(grainOnly.hasAccountMovements()).isTrue();

    LevyRegionPlan.Plan manpowerOnly = plan(baseUnit(), "u-1", "r-nation", 0L, 0L, 0L, 40L);
    assertThat(manpowerOnly.grain().requested()).isZero();
    assertThat(manpowerOnly.grain().sources()).isEmpty();
    assertThat(manpowerOnly.money().requested()).isZero();
    assertThat(manpowerOnly.money().sources()).isEmpty();
    assertThat(manpowerOnly.cloth().requested()).isZero();
    assertThat(manpowerOnly.cloth().sources()).isEmpty();
    assertThat(manpowerOnly.cloth()).isEqualTo(LevyRegionPlan.Dimension.skipped());
    assertThat(manpowerOnly.grain()).isEqualTo(LevyRegionPlan.Dimension.skipped());
    assertThat(manpowerOnly.manpower().sources()).hasSize(2);
    assertThat(manpowerOnly.hasAccountMovements()).as("粮/钱/布全 0 ⇒ 批次里没有 AdjustAccounts").isFalse();
  }

  /** ★ 确定性：同状态同参数两次 plan **逐字段**相等（不是只比整体 equals）。 */
  @Test
  void planIsDeterministicFieldByField() {
    LevyRegionPlan.Plan first = plan(baseUnit(), "u-1", "r-nation", 120L, 100L, 0L, 40L);
    LevyRegionPlan.Plan second = plan(baseUnit(), "u-1", "r-nation", 120L, 100L, 0L, 40L);

    assertThat(second.unitId()).isEqualTo(first.unitId());
    assertThat(second.regionId()).isEqualTo(first.regionId());
    assertThat(second.tick()).isEqualTo(first.tick());
    assertThat(second.treasuryLocation()).isEqualTo(first.treasuryLocation());
    assertThat(second.grain()).as("grain 维度逐字段").isEqualTo(first.grain());
    assertThat(second.money()).as("money 维度逐字段").isEqualTo(first.money());
    assertThat(second.cloth()).as("cloth 维度逐字段").isEqualTo(first.cloth());
    assertThat(second.manpower()).as("manpower 维度逐字段").isEqualTo(first.manpower());
    assertThat(second).isEqualTo(first);
    assertThat(second.grain().sources())
        .as("来源表按内容全序，不是 Map 迭代序的产物")
        .containsExactlyElementsOf(first.grain().sources());
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static LevyRegionPlan.Plan plan(
      Unit unit,
      String unitId,
      String regionId,
      long grain,
      long money,
      long cloth,
      long manpower) {
    return LevyRegionPlan.plan(state(unit), unitId, regionId, grain, money, cloth, manpower);
  }

  private static Unit baseUnit() {
    return unitWithPosition(H11, Optional.of(defaultJurisdiction()));
  }

  private static Jurisdiction defaultJurisdiction() {
    return jurisdiction(Map.of(NATION, 100L), 1000L, 1000L, 1000L);
  }

  private static Jurisdiction jurisdiction(
      Map<RegionId, Long> rates, long grainCap, long moneyCap, long manpowerCap) {
    return new Jurisdiction(rates, grainCap, moneyCap, manpowerCap, 0L);
  }

  private static Unit unitWithPosition(HexCoord position, Optional<Jurisdiction> jurisdiction) {
    return unitWithPosition(Optional.of(position), jurisdiction);
  }

  private static Unit unitWithPosition(
      Optional<HexCoord> position, Optional<Jurisdiction> jurisdiction) {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null),
        100,
        Map.of("步枪", 50),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        jurisdiction);
  }

  private static SimulationState state(Unit unit) {
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, unit)));
    return new SimulationState(
        new StateMeta(REF, T7),
        Map.of(
            "map", new MapSnapshot(REF, T7, map()),
            "unit", new UnitSnapshot(REF, T7, units),
            "social", new SocialSnapshot(REF, T7, social()),
            "actor", new ActorSnapshot(REF, T7, actors())),
        InMemoryInfoSystem.empty());
  }

  /** 三格地图 + 一个覆盖 (1,1)/(1,2) 的区域。 */
  private static GameMap map() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(NATION, Region.of(NATION, "国家区域", Set.of(H11, H12), RegionMeta.empty()));
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static ActorData actors() {
    return ActorData.empty()
        .withAccount(account(HH1, H11, 100L, 20L, 50L, 0L, 60L, 10L))
        .withAccount(account(HH2, H12, 40L, 0L, 80L, 0L, 70L, 20L))
        .withAccount(account(ESTATE, H11, 1000L, 0L, 1000L, 0L, 1000L, 0L))
        .withAccount(account(HH_OUT, H13, 1000L, 0L, 1000L, 0L, 1000L, 0L))
        .withAccount(account(HH_ZERO, H11, 10L, 10L, 0L, 0L, 0L, 0L));
  }

  private static GoodsAccount account(
      ActorRef owner,
      HexCoord at,
      long grain,
      long frozenGrain,
      long silver,
      long frozenSilver,
      long cloth,
      long frozenCloth) {
    Map<CommodityId, Long> balances = new LinkedHashMap<>();
    balances.put(GRAIN, grain);
    balances.put(CLOTH, cloth);
    Map<CommodityId, Long> frozenBalances = new LinkedHashMap<>();
    if (frozenGrain != 0L) {
      frozenBalances.put(GRAIN, frozenGrain);
    }
    if (frozenCloth != 0L) {
      frozenBalances.put(CLOTH, frozenCloth);
    }
    return new GoodsAccount(
        new GoodsAccountKey(owner, at),
        balances,
        Map.of(SILVER, silver),
        frozenBalances,
        frozenSilver == 0L ? Map.of() : Map.of(SILVER, frozenSilver));
  }

  private static SocialData social() {
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(lot("g1"), group("g1", H11, Sex.MALE, 30L, 20L * 365L));
    groups.put(lot("g2"), group("g2", H12, Sex.MALE, 20L, 30L * 365L));
    groups.put(lot("g-female"), group("g-female", H11, Sex.FEMALE, 1000L, 20L * 365L));
    groups.put(lot("g-child"), group("g-child", H11, Sex.MALE, 100L, 10L * 365L));
    groups.put(lot("g-elder"), group("g-elder", H12, Sex.MALE, 7L, 60L * 365L));
    groups.put(lot("g-zero"), group("g-zero", H11, Sex.MALE, 0L, 20L * 365L));
    groups.put(lot("g-out"), group("g-out", H13, Sex.MALE, 1000L, 20L * 365L));
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    for (HexCoord at : List.of(H11, H12, H13)) {
      populations.put(at, populationSeries());
    }
    return new SocialData(populations, Map.of(), groups);
  }

  private static PeopleLotId lot(String id) {
    return new PeopleLotId(id);
  }

  private static PopulationGroup group(
      String id, HexCoord at, Sex sex, long count, long ageAtAnchorDays) {
    return new PopulationGroup(lot(id), at, sex, count, ageAtAnchorDays, 0L);
  }

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 1000L),
        new SegmentedSeries<>(List.of(new Segment<>(T0, 0.0)), List.of(), null),
        List.of());
  }
}
