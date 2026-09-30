package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@link RaiseUnitPlan} 纯推导（收尾期 T4b）：参数 / 引用逐条具名拒、三项来源瀑布与不足缺口、四片载荷逐值、命令类型与顺序、★
 * 不丢失（SeedGroups 的 {@code ageDays}/{@code anchorTick}/{@code stress} 保真）、同状态同参数确定性。
 *
 * <p>★ 判别力：来源表把**每个来源的 (owner/批次, 格, 额/扣后人数)** 逐值钉住；保真断言取**非 0 stress / 非 0 anchorTick**——
 * 生产若把压力静默清零或把锚点写成当前 tick，当场红；国库落点从 {@code actor.AdjustAccounts} 载荷读回，不是另抄一份视图。
 *
 * <p>★ {@code tools} 键是工具边界的拒绝（{@link RaiseUnitTool#execute} 的命名字面），不由纯推导承载；真 Shell 行为见 {@code
 * RaiseUnitToolTest}。本类不碰 {@code ToolContext}/{@code CoreSimos}。
 */
class RaiseUnitPlanTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U_PARENT = new UnitId("u-parent");
  private static final UnitId U_ELSEWHERE = new UnitId("u-elsewhere");
  private static final RegionId NATION = new RegionId("r-nation");

  private static final ActorRef HH1 = new ActorRef(ActorKind.HOUSEHOLD, "house@1_1");
  private static final ActorRef HH2 = new ActorRef(ActorKind.HOUSEHOLD, "house@1_2");
  private static final ActorRef HH_ZERO = new ActorRef(ActorKind.HOUSEHOLD, "house-zero");
  private static final ActorRef HH_OUT = new ActorRef(ActorKind.HOUSEHOLD, "house@1_3");
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "e-1");

  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private static final ObjectMapper JSON = new ObjectMapper();

  // ── 逐条拒：newUnitId / region / at / parent ────────────────────────────────────────

  @Test
  void rejectsExistingNewUnitId() {
    assertThatThrownBy(
            () ->
                plan(
                    U_PARENT.value(),
                    "r-nation",
                    H11,
                    40L,
                    120L,
                    100L,
                    4,
                    700,
                    equipment(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位 id 已存在")
        .hasMessageContaining(U_PARENT.value());
  }

  @Test
  void rejectsRegionMissingFromMap() {
    assertThatThrownBy(
            () ->
                plan(
                    "u-new",
                    "r-ghost",
                    H11,
                    40L,
                    120L,
                    100L,
                    4,
                    700,
                    equipment(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("地图里没有区域: r-ghost")
        .hasMessageContaining("map.CreateRegion");
  }

  @Test
  void rejectsAtOutsideRegion() {
    assertThatThrownBy(
            () ->
                plan(
                    "u-new",
                    "r-nation",
                    H13,
                    40L,
                    120L,
                    100L,
                    4,
                    700,
                    equipment(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at (1,3)")
        .hasMessageContaining("不在区域 r-nation")
        .hasMessageContaining("不默认、不猜中心");
  }

  @Test
  void rejectsMissingParent() {
    assertThatThrownBy(
            () ->
                plan(
                    "u-new",
                    "r-nation",
                    H11,
                    40L,
                    120L,
                    100L,
                    4,
                    700,
                    equipment(),
                    "u-404"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("父单位不存在: u-404")
        .hasMessageContaining("parent 必须指向既有单位");
  }

  @Test
  void rejectsParentOnDifferentHex() {
    assertThatThrownBy(
            () ->
                plan(
                    "u-new",
                    "r-nation",
                    H11,
                    40L,
                    120L,
                    100L,
                    4,
                    700,
                    equipment(),
                    U_ELSEWHERE.value()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("父单位 u-elsewhere")
        .hasMessageContaining("当刻有效位置 (1,2)")
        .hasMessageContaining("新单位落点 (1,1)")
        .hasMessageContaining("不同格");
  }

  // ── 逐条拒：数值与装备 ───────────────────────────────────────────────────────────────

  @Test
  void rejectsManpowerBelowOneAndAboveInt() {
    assertThatThrownBy(() -> plan(0L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("manpower 必须 ≥ 1: 0");
    assertThatThrownBy(() -> plan(-1L, 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("manpower 必须 ≥ 1: -1");

    long aboveInt = (long) Integer.MAX_VALUE + 1L;
    assertThatThrownBy(
            () ->
                plan(
                    "u-new",
                    "r-nation",
                    H11,
                    aboveInt,
                    0L,
                    0L,
                    4,
                    700,
                    equipment(),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("超过 unit.CreateUnit 的 member")
        .hasMessageContaining(String.valueOf(aboveInt));
  }

  @Test
  void rejectsNegativeGrainAndMoney() {
    assertThatThrownBy(() -> plan(40L, -1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("grain 不得为负: -1");
    assertThatThrownBy(() -> plan(40L, 0L, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("money 不得为负: -1");
  }

  @Test
  void rejectsSpeedAndMobilityBounds() {
    assertThatThrownBy(
            () ->
                plan(
                    "u-new", "r-nation", H11, 40L, 120L, 100L, 0, 700, equipment(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("speed 必须 ≥ 1");

    assertThatThrownBy(
            () ->
                plan(
                    "u-new", "r-nation", H11, 40L, 120L, 100L, 4, 0, equipment(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mobilityPerMille 必须在 [1,1000]")
        .hasMessageContaining(": 0");
    assertThatThrownBy(
            () ->
                plan(
                    "u-new", "r-nation", H11, 40L, 120L, 100L, 4, 1001, equipment(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mobilityPerMille 必须在 [1,1000]")
        .hasMessageContaining(": 1001");
  }

  @Test
  void rejectsBlankEquipmentKeyAndNegativeValue() {
    Map<String, Integer> blankKey = new LinkedHashMap<>();
    blankKey.put(" ", 1);
    assertThatThrownBy(
            () ->
                plan(
                    "u-new", "r-nation", H11, 40L, 120L, 100L, 4, 700, blankKey, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("equipment 的键不得空白");

    Map<String, Integer> negative = new LinkedHashMap<>();
    negative.put("rifle", -1);
    assertThatThrownBy(
            () ->
                plan(
                    "u-new", "r-nation", H11, 40L, 120L, 100L, 4, 700, negative, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("equipment 的值必须 ≥ 0")
        .hasMessageContaining("rifle=-1");
  }

  // ── 逐条拒：三个维度各自不足（带 requested / available / 缺口）────────────────────────

  @Test
  void rejectsEachDimensionWhenItsSourcesAreInsufficient() {
    assertThatThrownBy(() -> plan(40L, 121L, 100L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("粮总量不足")
        .hasMessageContaining("requested=121")
        .hasMessageContaining("available=120")
        .hasMessageContaining("缺口=1");

    assertThatThrownBy(() -> plan(40L, 120L, 131L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("钱总量不足")
        .hasMessageContaining("requested=131")
        .hasMessageContaining("available=130")
        .hasMessageContaining("缺口=1");

    assertThatThrownBy(() -> plan(51L, 120L, 100L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人力总量不足")
        .hasMessageContaining("requested=51")
        .hasMessageContaining("available=50")
        .hasMessageContaining("缺口=1");
  }

  // ── happy：三项来源逐值 + 来源扣后 + 国库落点 + equipment + commands ────────────────

  @Test
  void happyPlanCarriesEverySourceTreasuryAndPayloadField() throws Exception {
    RaiseUnitPlan.Plan plan = plan(40L, 120L, 100L);

    assertThat(plan.unitId()).isEqualTo("u-new");
    assertThat(plan.name()).isEqualTo("新军");
    assertThat(plan.regionId()).isEqualTo("r-nation");
    assertThat(plan.at()).isEqualTo(H11);
    assertThat(plan.tick()).as("tick 取状态时刻（INFO 与批次年龄现算都用它）").isEqualTo(7L);
    assertThat(plan.member()).as("member = 实抽人力（不是请求值再抄一份）").isEqualTo(40);
    assertThat(plan.speed()).isEqualTo(4);
    assertThat(plan.mobilityPerMille()).isEqualTo(700);
    assertThat(plan.equipment()).isEqualTo(equipment());
    assertThat(plan.parent()).isEmpty();
    assertThat(plan.commandTypes())
        .containsExactly(
            "unit.CreateUnit",
            "actor.AdjustAccounts",
            "social.SeedGroups",
            "sd.PutInfo");

    // 粮：hh1 可支配 100−20=80 先扣满，再 hh2 的 40；ESTATE / 区外 / 可用 0 不进来源表。
    assertThat(plan.grain().requested()).isEqualTo(120L);
    assertThat(plan.grain().available()).isEqualTo(120L);
    assertThat(plan.grain().sources())
        .containsExactly(
            new RegionAllocations.AccountSource(HH1, H11, 80L),
            new RegionAllocations.AccountSource(HH2, H12, 40L));

    // 钱：hh2 可支配 80 先，再 hh1 的 50 里扣 20。
    assertThat(plan.money().requested()).isEqualTo(100L);
    assertThat(plan.money().available()).isEqualTo(130L);
    assertThat(plan.money().sources())
        .containsExactly(
            new RegionAllocations.AccountSource(HH2, H12, 80L),
            new RegionAllocations.AccountSource(HH1, H11, 20L));

    // 人力：g1(30) 先扣满后 count=0，再 g2 扣 10；女性 / 未成年 / 老年 / 区外 / 空批都不算。
    assertThat(plan.manpower().requested()).isEqualTo(40L);
    assertThat(plan.manpower().available()).isEqualTo(50L);
    assertThat(plan.manpower().sources())
        .extracting(source -> source.group().id().value())
        .containsExactly("g1", "g2");
    assertThat(plan.manpower().sources())
        .extracting(RegionAllocations.GroupSource::taken)
        .containsExactly(30L, 10L);
    assertThat(plan.manpower().sources())
        .extracting(RegionAllocations.GroupSource::countAfter)
        .containsExactly(0L, 10L);
    assertThat(plan.manpower().sources().get(0).countAfter())
        .as("扣后 count 可为 0（合法空批，不能避开）")
        .isZero();

    JsonNode create = JSON.readTree(plan.createUnitPayloadJson());
    assertThat(create.get("id").asText()).isEqualTo("u-new");
    assertThat(create.get("name").asText()).isEqualTo("新军");
    assertThat(create.get("position").get("q").asInt()).isEqualTo(1);
    assertThat(create.get("position").get("r").asInt()).isEqualTo(1);
    assertThat(create.get("member").asInt()).isEqualTo(40);
    assertThat(create.get("equipment").get("rifle").asInt()).isEqualTo(12);
    assertThat(create.get("equipment").get("shield").asInt()).isEqualTo(3);
    assertThat(create.get("speed").asInt()).isEqualTo(4);
    assertThat(create.get("mobilityPerMille").asInt()).isEqualTo(700);
    assertThat(create.has("parent")).as("无 parent ⇒ 载荷不得出现该键").isFalse();
    assertThat(create.has("jurisdiction")).as("新单位尚无管辖 ⇒ 载荷不发该字段").isFalse();
    assertThat(create.has("status")).as("CreateUnit 的 status 有缺省，本工具不发明").isFalse();

    // actor.AdjustAccounts：粮来源序 → 仅钱来源（此处同两户）→ 国库恒最后；同 (owner,格) 合并成一条。
    JsonNode entries =
        JSON.readTree(plan.adjustAccountsPayloadJson()).get("entries");
    assertThat(entries).hasSize(3);
    assertHouseholdAdjustment(entries.get(0), HH1, H11, -80L, -20L);
    assertHouseholdAdjustment(entries.get(1), HH2, H12, -40L, -80L);
    assertTreasuryAdjustment(entries.get(2), "u-new", H11, 120L, 100L);

    // social.SeedGroups：整组覆盖必须带 ageDays/anchorTick/stress 保真，count = 扣后。
    JsonNode seeds = JSON.readTree(plan.seedGroupsPayloadJson()).get("entries");
    assertThat(seeds).hasSize(2);
    assertSeedEntry(seeds.get(0), "g1", H11, 0L, 20L * 365L, 0L, 4L);
    assertSeedEntry(seeds.get(1), "g2", H12, 10L, 30L * 365L, 5L, 11L);

    JsonNode info = JSON.readTree(plan.infoValueJson("组军测试"));
    assertThat(info.get("unitId").asText()).isEqualTo("u-new");
    assertThat(info.get("regionId").asText()).isEqualTo("r-nation");
    assertThat(info.get("at").get("q").asInt()).isEqualTo(1);
    assertThat(info.get("at").get("r").asInt()).isEqualTo(1);
    assertThat(info.get("manpower").asLong()).isEqualTo(40L);
    assertThat(info.get("grain").asLong()).isEqualTo(120L);
    assertThat(info.get("money").asLong()).isEqualTo(100L);
    assertThat(info.get("sourceCounts").get("grain").asInt()).isEqualTo(2);
    assertThat(info.get("sourceCounts").get("money").asInt()).isEqualTo(2);
    assertThat(info.get("sourceCounts").get("manpower").asInt()).isEqualTo(2);
    assertThat(info.get("reason").asText()).isEqualTo("组军测试");
    assertThat(plan.infoNote("组军测试"))
        .contains("u-new")
        .contains("region=r-nation")
        .contains("人力 40")
        .contains("粮 120")
        .contains("钱 100")
        .contains("组军测试");
  }

  @Test
  void parentOnSameHexIsCarriedIntoCreatePayload() throws Exception {
    RaiseUnitPlan.Plan plan =
        plan(
            "u-new",
            "r-nation",
            H11,
            40L,
            120L,
            100L,
            4,
            700,
            equipment(),
            U_PARENT.value());

    assertThat(plan.parent()).contains(U_PARENT.value());
    JsonNode create = JSON.readTree(plan.createUnitPayloadJson());
    assertThat(create.get("parent").asText()).isEqualTo(U_PARENT.value());
  }

  @Test
  void commandsSkipAdjustAccountsWhenGrainAndMoneyAreZero() {
    RaiseUnitPlan.Plan manpowerOnly = plan(40L, 0L, 0L);

    assertThat(manpowerOnly.commandTypes())
        .as("纯人力批：无粮/钱 ⇒ 不落 actor.AdjustAccounts")
        .containsExactly("unit.CreateUnit", "social.SeedGroups", "sd.PutInfo");
    assertThat(manpowerOnly.hasGrainOrMoney()).isFalse();
    assertThat(manpowerOnly.grain())
        .as("requested=0 = 整段跳过（不是『恰好没有来源』）")
        .isEqualTo(RegionAllocations.AccountAllocation.skipped());
    assertThat(manpowerOnly.money()).isEqualTo(RegionAllocations.AccountAllocation.skipped());
    assertThat(manpowerOnly.manpower().sources()).hasSize(2);
  }

  // ── 确定性与不丢失 ───────────────────────────────────────────────────────────────────

  @Test
  void planIsDeterministicFieldByFieldAndPayloadByPayload() {
    RaiseUnitPlan.Plan first = plan(40L, 120L, 100L);
    RaiseUnitPlan.Plan second = plan(40L, 120L, 100L);

    assertThat(second.unitId()).isEqualTo(first.unitId());
    assertThat(second.name()).isEqualTo(first.name());
    assertThat(second.regionId()).isEqualTo(first.regionId());
    assertThat(second.at()).isEqualTo(first.at());
    assertThat(second.tick()).isEqualTo(first.tick());
    assertThat(second.member()).isEqualTo(first.member());
    assertThat(second.grain()).isEqualTo(first.grain());
    assertThat(second.money()).isEqualTo(first.money());
    assertThat(second.manpower()).isEqualTo(first.manpower());
    assertThat(second.speed()).isEqualTo(first.speed());
    assertThat(second.mobilityPerMille()).isEqualTo(first.mobilityPerMille());
    assertThat(second.equipment()).isEqualTo(first.equipment());
    assertThat(second.parent()).isEqualTo(first.parent());
    assertThat(second).isEqualTo(first);
    assertThat(second.commandTypes()).containsExactlyElementsOf(first.commandTypes());
    assertThat(second.createUnitPayloadJson()).isEqualTo(first.createUnitPayloadJson());
    assertThat(second.adjustAccountsPayloadJson())
        .isEqualTo(first.adjustAccountsPayloadJson());
    assertThat(second.seedGroupsPayloadJson()).isEqualTo(first.seedGroupsPayloadJson());
    assertThat(second.infoValueJson("组军测试")).isEqualTo(first.infoValueJson("组军测试"));
    assertThat(second.grain().sources()).containsExactlyElementsOf(first.grain().sources());
    assertThat(second.manpower().sources())
        .containsExactlyElementsOf(first.manpower().sources());
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────────

  /** 默认合法参数：at=H11、region=r-nation、speed=4、mobility=700、equipment 两键、无 parent。 */
  private static RaiseUnitPlan.Plan plan(long manpower, long grain, long money) {
    return plan(
        "u-new",
        "r-nation",
        H11,
        manpower,
        grain,
        money,
        4,
        700,
        equipment(),
        null);
  }

  private static RaiseUnitPlan.Plan plan(
      String newUnitId,
      String regionId,
      HexCoord at,
      long manpower,
      long grain,
      long money,
      int speed,
      int mobilityPerMille,
      Map<String, Integer> equipment,
      String parent) {
    return RaiseUnitPlan.plan(
        state(),
        newUnitId,
        "新军",
        regionId,
        at,
        manpower,
        grain,
        money,
        speed,
        mobilityPerMille,
        equipment,
        Optional.ofNullable(parent));
  }

  private static Map<String, Integer> equipment() {
    Map<String, Integer> equipment = new LinkedHashMap<>();
    equipment.put("rifle", 12);
    equipment.put("shield", 3);
    return equipment;
  }

  private static SimulationState state() {
    UnitState units =
        new UnitState(
            new LinkedHashMap<>(
                Map.of(
                    U_PARENT, unit(U_PARENT, H11),
                    U_ELSEWHERE, unit(U_ELSEWHERE, H12))));
    return new SimulationState(
        new StateMeta(REF, T7),
        Map.of(
            "map", new MapSnapshot(REF, T7, map()),
            "unit", new UnitSnapshot(REF, T7, units),
            "social", new SocialSnapshot(REF, T7, social()),
            "actor", new ActorSnapshot(REF, T7, actors())),
        InMemoryInfoSystem.empty());
  }

  /** 三格地图 + 一个覆盖 (1,1)/(1,2) 的区域；H13 在区域外（at 拒的样本）。 */
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
        .withAccount(account(HH1, H11, 100L, 20L, 50L))
        .withAccount(account(HH2, H12, 40L, 0L, 80L))
        .withAccount(account(HH_ZERO, H11, 10L, 10L, 0L))
        .withAccount(account(ESTATE, H11, 1000L, 0L, 1000L))
        .withAccount(account(HH_OUT, H13, 1000L, 0L, 1000L));
  }

  private static GoodsAccount account(
      ActorRef owner, HexCoord at, long grain, long frozenGrain, long silver) {
    return new GoodsAccount(
        new GoodsAccountKey(owner, at),
        Map.of(GRAIN, grain),
        Map.of(SILVER, silver),
        frozenGrain == 0L ? Map.of() : Map.of(GRAIN, frozenGrain),
        Map.of());
  }

  private static SocialData social() {
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(lot("g1"), group("g1", H11, Sex.MALE, 30L, 20L * 365L, 0L, 4L));
    groups.put(lot("g2"), group("g2", H12, Sex.MALE, 20L, 30L * 365L, 5L, 11L));
    groups.put(lot("g-female"), group("g-female", H11, Sex.FEMALE, 1000L, 20L * 365L, 0L, 1L));
    groups.put(lot("g-child"), group("g-child", H11, Sex.MALE, 100L, 10L * 365L, 0L, 2L));
    groups.put(lot("g-elder"), group("g-elder", H12, Sex.MALE, 7L, 60L * 365L, 0L, 3L));
    groups.put(lot("g-zero"), group("g-zero", H11, Sex.MALE, 0L, 20L * 365L, 0L, 5L));
    groups.put(lot("g-out"), group("g-out", H13, Sex.MALE, 1000L, 20L * 365L, 0L, 6L));
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
      String id,
      HexCoord at,
      Sex sex,
      long count,
      long ageAtAnchorDays,
      long anchorTick,
      long stress) {
    return new PopulationGroup(lot(id), at, sex, count, ageAtAnchorDays, anchorTick, stress);
  }

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 1000L),
        new SegmentedSeries<>(List.of(new Segment<>(T0, 0.0)), List.of(), null),
        List.of());
  }

  private static Unit unit(UnitId id, HexCoord at) {
    return new Unit(
        id,
        "第 " + id.value() + " 连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(at))), List.of(), null),
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
        Optional.empty());
  }

  private static void assertHouseholdAdjustment(
      JsonNode entry, ActorRef owner, HexCoord at, long grainDelta, long moneyDelta) {
    assertThat(entry.get("owner").get("kind").asText()).isEqualTo("HOUSEHOLD");
    assertThat(entry.get("owner").get("id").asText()).isEqualTo(owner.id());
    assertThat(entry.get("q").asInt()).isEqualTo(at.q());
    assertThat(entry.get("r").asInt()).isEqualTo(at.r());
    assertThat(entry.get("goods").get("grain").asLong())
        .as("家户粮 = 有符号负增量")
        .isEqualTo(grainDelta);
    assertThat(entry.get("money").get("silver").asLong())
        .as("家户钱 = 有符号负增量")
        .isEqualTo(moneyDelta);
  }

  private static void assertTreasuryAdjustment(
      JsonNode entry, String unitId, HexCoord at, long grain, long money) {
    assertThat(entry.get("owner").get("kind").asText()).isEqualTo("UNIT");
    assertThat(entry.get("owner").get("id").asText()).isEqualTo(unitId);
    assertThat(entry.get("q").asInt()).as("国库落点 = at").isEqualTo(at.q());
    assertThat(entry.get("r").asInt()).as("国库落点 = at").isEqualTo(at.r());
    assertThat(entry.get("goods").get("grain").asLong()).isEqualTo(grain);
    assertThat(entry.get("money").get("silver").asLong()).isEqualTo(money);
  }

  private static void assertSeedEntry(
      JsonNode entry,
      String id,
      HexCoord at,
      long countAfter,
      long ageDays,
      long anchorTick,
      long stress) {
    assertThat(entry.get("id").asText()).isEqualTo(id);
    assertThat(entry.get("q").asInt()).isEqualTo(at.q());
    assertThat(entry.get("r").asInt()).isEqualTo(at.r());
    assertThat(entry.get("sex").asText()).isEqualTo(Sex.MALE.name());
    assertThat(entry.get("count").asLong()).as("整组覆盖为扣后 count").isEqualTo(countAfter);
    assertThat(entry.get("ageDays").asLong()).as("锚点年龄保真").isEqualTo(ageDays);
    assertThat(entry.get("anchorTick").asLong()).as("锚点 tick 保真").isEqualTo(anchorTick);
    assertThat(entry.get("stress").asLong()).as("生理压力保真（非 0）").isEqualTo(stress);
  }
}
