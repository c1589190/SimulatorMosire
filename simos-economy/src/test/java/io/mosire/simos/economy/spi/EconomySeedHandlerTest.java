package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.actor.ActorKind;
import io.mosire.simos.economy.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * {@code economy.Seed} 命令边界（R2a）：正例逐值 + 已激活后按格追加 + 重复格拒绝 + 悬空槽位拒绝 + 负值拒绝 + 目标路径。
 *
 * <p>夹具是**真 {@link SimulationState} + 只有 economy 切片**（不打 DB），形态照 social 侧 {@code
 * SetPopulationHandlerTest}。
 */
class EconomySeedHandlerTest {

  private static final EconomySeedHandler HANDLER = new EconomySeedHandler();
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final IndustryId FARM2 = new IndustryId("farm@1_0");

  /** R3 的商品词（与载荷里的字面量同字面量；用途见 {@code parsesCycleInputWithCommodityDimension…}）。 */
  private static final CommodityId GRAIN = new CommodityId("grain");

  private static final CommodityId FIBER_COMMODITY = new CommodityId("fiber");
  private static final CommodityId WOOD_COMMODITY = new CommodityId("wood");

  /** 一段最小合法载荷：一格、一个农业产业（封建租佃）、两个槽位两条阶层行。 */
  private static final String PAYLOAD =
      "{\"mapId\":\"Map1\",\"rulesVersion\":\"aggregate-v1\",\"entries\":[{\"q\":0,\"r\":0,"
          + "\"industries\":[{\"id\":\"farm@0_0\",\"name\":\"农业\",\"regime\":\"feudal\","
          + "\"cycleDays\":120,\"progressDays\":0,"
          // ★ R3（V7）：配方的产能锚与劳动那一路（缺 capacityPerUnit ⇒ 构造期拒 ⇒ 整条命令被 Rejected）
          + "\"capacityPerUnit\":{\"LAND\":1000},\"laborPerUnit\":143,"
          + "\"dailyInputPerUnit\":{},\"dailyLaborPerUnit\":0,"
          + "\"outputPerUnit\":{\"grain\":7},"
          + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":700,\"laborWeightPerMille\":300},"
          + "\"slots\":[{\"id\":\"peasant\",\"name\":\"贫农\",\"laborParticipationPerMille\":950},"
          + "{\"id\":\"landlord\",\"name\":\"地主\",\"laborParticipationPerMille\":100}],"
          + "\"classes\":[{\"slot\":\"peasant\",\"population\":450,\"laborMilli\":261000,"
          + "\"participationPerMille\":950,\"meansOfProduction\":{\"LAND\":900000},"
          + "\"goods\":{\"grain\":2241000},\"money\":0,\"debts\":[],\"naturalNeeds\":{\"grain\":37350},"
          + "\"effectiveDemand\":{}},"
          + "{\"slot\":\"landlord\",\"population\":50,\"laborMilli\":29000,"
          + "\"participationPerMille\":100,\"meansOfProduction\":{\"LAND\":100000},"
          + "\"goods\":{\"grain\":249000}}]}]}]}";

  /** 第二国的载荷：与 {@link #PAYLOAD} 同形、但落在**另一格**（{@code 1_0}）——验证"已激活后按格追加"。 */
  private static final String LATER_NATION_PAYLOAD =
      PAYLOAD.replace("\"q\":0,\"r\":0", "\"q\":1,\"r\":0").replace("farm@0_0", "farm@1_0");

  /**
   * ★★ **R2：带劳动表的同一份载荷**（一条供给 + 一条配额；配额 250,000 ≤ 可用 290,000）。
   *
   * <p>★ 它是"逐格声明"的形态（两张新表都挂在 {@code entries[]} 的那一格下，与 {@code industries} 同款）： 格是命令目标与权限的粒度。
   */
  private static final String PAYLOAD_WITH_LABOR =
      PAYLOAD.replace(
          "\"goods\":{\"grain\":249000}}]}]}]}",
          "\"goods\":{\"grain\":249000}}]}],"
              + "\"laborSupply\":[{\"group\":\"rural:0_0:MALE:1\",\"period\":1,"
              + "\"grossLaborMilli\":290000,\"servedLaborMilli\":0,\"committedLaborMilli\":0}],"
              + "\"allocations\":[{\"id\":\"alloc-farm@0_0-rural:0_0:MALE:1\","
              + "\"group\":\"rural:0_0:MALE:1\","
              + "\"actor\":{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\"},"
              + "\"activity\":\"farm\",\"laborMilli\":250000,\"period\":1}]}]}");

  /**
   * ★★ `WageFirst`（资本主义工业）在 v1 没有结算实现 ⇒ 必须**播种期**拒（v2 spec §八.4）。
   *
   * <p>判别力：v1 允许它入库，直到某个收获日才在 `EconomySettlement.harvest` 里抛 `UnsupportedOperationException` ——
   * 那个异常穿出 `EconomyTimeParticipant.simulateWorld`， 让整条 `AdvanceTime` revision 失败（既不是 `Rejected`
   * 也不是降级）。
   *
   * <p>★ 为什么拒在**载荷**这一层而不是 `Industry` 构造期：构造期拒会让 `WageFirst` 这个 **状态形状**（spec §五 的第四种制度）不可表达，连带
   * `EconomyCodecTest` 的 `wage_first` 多态往返夹具无法构造 ⇒ 丢一条 JSON 分支的覆盖。播种期拒已堵住命令路径，且不砍覆盖。
   */
  @Test
  void wageFirstAllocationIsRejectedAtSeedTime() {
    String payload =
        PAYLOAD.replace(
            "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":700,"
                + "\"laborWeightPerMille\":300}",
            "\"allocation\":{\"@class\":\"wage_first\",\"wagePerLaborMilli\":1000,"
                + "\"ownerResidual\":{\"grain\":30}}");
    assertThat(payload).as("替换必须真的发生（否则本用例测的是 split 那条路）").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> EconomyPayloads.toData(EconomyPayloads.parse(payload), T7))
        .as("v1 不支持的分配函数必须在播种期拒（v2 spec §八.4）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Split");
  }

  @Test
  void typeIsEconomySeed() {
    assertThat(HANDLER.type()).isEqualTo("economy.Seed");
  }

  // ── R2：劳动供给与配额（第三阶段设计稿 §四）────────────────────────────────────────

  /** ★ 正例：两张新表**逐值**落盘（供给的毛额/两项扣除、配额的 actor/活动/量/周期）。 */
  @Test
  void seedsLaborSupplyAndAllocationsValueForValue() {
    EconomyData after = apply(PAYLOAD_WITH_LABOR, EconomyData.empty(), T7);

    PeopleLotId lot = new PeopleLotId("rural:0_0:MALE:1");
    assertThat(after.laborSupply()).containsOnlyKeys(lot);
    LaborSupply supply = after.laborSupply().get(lot);
    assertThat(supply.period()).as("创世 = 第 1 周期").isEqualTo(1L);
    assertThat(supply.grossLaborMilli()).isEqualTo(290_000L);
    assertThat(supply.servedLaborMilli()).as("本轮恒 0，但字段在").isZero();
    assertThat(supply.committedLaborMilli()).isZero();
    assertThat(supply.availableLabor()).as("毛额 − 已服役 − 已承诺").isEqualTo(290_000L);

    assertThat(after.allocations()).hasSize(1);
    LaborAllocation allocation =
        after.allocations().get(new LaborAllocationId("alloc-farm@0_0-rural:0_0:MALE:1"));
    assertThat(allocation.group()).isEqualTo(lot);
    assertThat(allocation.actor()).isEqualTo(new ActorRef(ActorKind.ESTATE, "farm@0_0"));
    assertThat(allocation.activity()).isEqualTo("farm");
    assertThat(allocation.laborMilli()).isEqualTo(250_000L);
    assertThat(allocation.period()).isEqualTo(1L);
  }

  /**
   * ★ **旧载荷（没有这两张表）照旧能播**：缺省 = 空表（与 {@code classes} 同款）。
   *
   * <p>★ 这不是"静默兜底"：没有配额的产业当日劳动为 0 —— 那是新口径的直接后果（劳动是**分配**来的）， 而真档路径由 {@code EconomySeeder}
   * 恒给全（{@code EconomySeederTest} 逐格钉住它的载荷里有非空配额）。
   */
  @Test
  void legacyPayloadWithoutLaborTablesSeedsEmptyOnes() {
    EconomyData after = apply(PAYLOAD, EconomyData.empty(), T7);

    assertThat(after.industries()).as("产业照旧落盘").hasSize(1);
    assertThat(after.laborSupply()).isEmpty();
    assertThat(after.allocations()).isEmpty();
  }

  /** ★ **没有供给的配额 ⇒ 命令边界拒**（没有供给的配额没有上限 —— 那等于把不变量留成后门）。 */
  @Test
  void rejectsAQuotaWithoutItsSupply() {
    String payload =
        PAYLOAD_WITH_LABOR.replace(
            "\"laborSupply\":[{\"group\":\"rural:0_0:MALE:1\",\"period\":1,"
                + "\"grossLaborMilli\":290000,\"servedLaborMilli\":0,\"committedLaborMilli\":0}],",
            "\"laborSupply\":[],");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD_WITH_LABOR);

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("拒因点名那条配额与那个批次")
        .contains("没有劳动供给记录")
        .contains("rural:0_0:MALE:1");
  }

  /** ★ **配额之和超过可用劳动 ⇒ 命令边界拒**（"同一批人的劳动不得被两个产业各算一次满额"）。 */
  @Test
  void rejectsAQuotaThatExceedsTheAvailableLabor() {
    String payload = PAYLOAD_WITH_LABOR.replace("\"laborMilli\":250000", "\"laborMilli\":290001");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD_WITH_LABOR);

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("超过其可用劳动");
  }

  /** ★ **词表外的 actor 种类 ⇒ 命令边界拒**（并列出合法值：静默收下会让"写错主体种类"变成运行时幽灵）。 */
  @Test
  void rejectsAnActorKindOutsideTheVocabulary() {
    String payload = PAYLOAD_WITH_LABOR.replace("\"kind\":\"ESTATE\"", "\"kind\":\"MANOR\"");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD_WITH_LABOR);

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("拒因列出词表")
        .contains("未知 ActorKind")
        .contains("ESTATE");
  }

  /** ★ actor 的 id 不是已存在的产业 ⇒ 构造期拒（拼错产业 id 会让当日劳动静默变 0）。 */
  @Test
  void rejectsAnActorThatIsNotAnExistingIndustry() {
    String payload = PAYLOAD_WITH_LABOR.replace("\"id\":\"farm@0_0\"}", "\"id\":\"farm@0_1\"}");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD_WITH_LABOR);

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("对应关系不成立");
  }

  /** ★ 追加第二国时，两张新表**按格一并追加**（与产业/阶层行同一套判重口径）。 */
  @Test
  void appendsTheLaterNationsLaborTables() {
    EconomyData first = apply(PAYLOAD_WITH_LABOR, EconomyData.empty(), T7);
    String later =
        PAYLOAD_WITH_LABOR
            .replace("\"q\":0,\"r\":0", "\"q\":1,\"r\":0")
            .replace("farm@0_0", "farm@1_0")
            .replace("rural:0_0:MALE:1", "rural:1_0:MALE:1")
            .replace("alloc-farm@0_0-", "alloc-farm@1_0-");

    EconomyData both = apply(later, first, SimosTimestamp.of(9));

    assertThat(both.allocations()).as("两国的配额都在").hasSize(2);
    assertThat(both.laborSupply()).as("两国的供给都在").hasSize(2);
    assertThat(both.industries()).hasSize(2);
  }

  /** 正例：与 §3 的 record 字段**逐值**对应（元信息 / 产业 / 阶层行）。 */
  @Test
  void seedsEveryFieldValueForValue() {
    EconomyData after = apply(PAYLOAD, EconomyData.empty(), T7);

    EconomyMeta meta = after.meta().orElseThrow();
    assertThat(meta.mapId()).isEqualTo("Map1");
    assertThat(meta.rulesVersion()).isEqualTo("aggregate-v1");
    assertThat(meta.activatedDay()).as("激活日 = 世界当前 tick").isEqualTo(7L);
    assertThat(meta.lastClosedCycle()).isEqualTo(OptionalLong.empty());
    assertThat(meta.migrationSource()).isEqualTo(Optional.empty());

    Industry industry = after.industries().get(FARM);
    assertThat(after.industries()).hasSize(1);
    assertThat(industry.name()).isEqualTo("农业");
    assertThat(industry.regime().value()).isEqualTo("feudal");
    assertThat(industry.cycleDays()).isEqualTo(120L);
    assertThat(industry.progressDays()).isZero();
    assertThat(industry.outputPerUnit()).containsEntry(new CommodityId("grain"), 7L);
    assertThat(industry.dailyInputPerUnit()).isEmpty();
    assertThat(industry.dailyLaborPerUnit()).isZero();
    assertThat(industry.slots())
        .extracting(slot -> slot.id().value())
        .containsExactly("peasant", "landlord");

    ClassKey peasant = new ClassKey(FARM, new ClassSlotId("peasant"));
    ClassRow row = after.classes().get(peasant);
    assertThat(after.classes()).hasSize(2);
    assertThat(row.population()).isEqualTo(450L);
    assertThat(row.laborMilli()).isEqualTo(261_000L);
    assertThat(row.participationPerMille()).isEqualTo(950);
    assertThat(row.meansOfProduction()).containsEntry(AssetKind.LAND, 900_000L);
    assertThat(row.goods()).containsEntry(new CommodityId("grain"), 2_241_000L);
    assertThat(row.money()).isZero();
    assertThat(row.debts()).as("本轮无债务").isEmpty();
    assertThat(row.naturalNeeds()).containsEntry(new CommodityId("grain"), 37_350L);
    assertThat(row.effectiveDemand()).isEmpty();
    assertThat(after.debts()).as("债务表本轮恒空").isEmpty();
    assertThat(after.flows()).as("周期流水留待 R3a").isEmpty();
    // 缺省字段（地主行没给 debts/naturalNeeds/effectiveDemand/money）⇒ 空表 / 0，不是 null。
    ClassRow landlord = after.classes().get(new ClassKey(FARM, new ClassSlotId("landlord")));
    assertThat(landlord.money()).isZero();
    assertThat(landlord.debts()).isEmpty();
    assertThat(landlord.naturalNeeds()).isEmpty();
    assertThat(landlord.effectiveDemand()).isEmpty();
  }

  /** ★ 已激活后**按格追加**：同一库连播两国，两批的格都在、人口/库存合计 = 两批之和，且 meta 不覆盖。 */
  @Test
  void appendsNewHexesOfALaterNation() {
    EconomyData first = apply(PAYLOAD, EconomyData.empty(), T7);

    EconomyData both = apply(LATER_NATION_PAYLOAD, first, SimosTimestamp.of(9));

    assertThat(both.industries()).as("两批的产业都在").containsKeys(FARM, FARM2);
    assertThat(both.classes()).as("两批各 2 行").hasSize(4);
    assertThat(both.classes().values().stream().mapToLong(ClassRow::population).sum())
        .as("两批人口合计 = 500 + 500")
        .isEqualTo(1_000L);
    assertThat(
            both.classes().values().stream()
                .mapToLong(row -> row.goods().getOrDefault(new CommodityId("grain"), 0L))
                .sum())
        .as("两批库存合计 = 2,490,000 + 2,490,000")
        .isEqualTo(4_980_000L);
    assertThat(both.meta().orElseThrow().activatedDay()).as("meta 不覆盖：保留首次播种的激活日").isEqualTo(7L);
  }

  /** ★ 已激活后**重复格**⇒ 拒，且拒因**点名该格坐标**（不静默覆盖既有经济状态）。 */
  @Test
  void rejectsDuplicateHexAndNamesIt() {
    EconomyData first = apply(PAYLOAD, EconomyData.empty(), T7);

    HandlerOutcome outcome = HANDLER.handle(state(first, T7), PAYLOAD);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).as("拒因点名重复的那一格坐标").contains("0_0");
  }

  /** ★ 悬空槽位：阶层行引用了该产业 slots 里没有的槽位 ⇒ 拒（EconomyData 的构造期守卫）。 */
  @Test
  void rejectsClassRowForASlotTheIndustryDoesNotAllow() {
    String payload = PAYLOAD.replace("\"slot\":\"landlord\"", "\"slot\":\"ghost\"");

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("槽位");
  }

  /** ★ 逐值校验：负人口 ⇒ 拒（`ClassRow` 的构造期守卫）。 */
  @Test
  void rejectsNegativePopulation() {
    String payload = PAYLOAD.replace("\"population\":450", "\"population\":-450");

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("population");
  }

  /** 非空债务数组 ⇒ 拒（§十 明确"不做债务"；免得落下一批指向空债务表的悬空引用）。 */
  @Test
  void rejectsDebtsBecauseThisRoundDoesNotModelThem() {
    String payload = PAYLOAD.replace("\"debts\":[]", "\"debts\":[\"debt-1\"]");

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("债务");
  }

  /** 环载荷（不是 JSON / 不是对象）⇒ 拒，不抛到命令边界之外。 */
  @Test
  void rejectsMalformedPayload() {
    assertThat(HANDLER.handle(state(EconomyData.empty(), T7), "not json"))
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(HANDLER.handle(state(EconomyData.empty(), T7), "[]"))
        .isInstanceOf(HandlerOutcome.Rejected.class);
  }

  /** 目标资源：载荷里**每一个**格各一条 {@code <q>_<r>}（GM 代执行时的越权判据）。 */
  @Test
  void targetPathsAreOnePerEntryHex() {
    String twoEntries =
        "{\"mapId\":\"Map1\",\"rulesVersion\":\"v\",\"entries\":["
            + "{\"q\":1,\"r\":2,\"industries\":[]},{\"q\":-3,\"r\":4,\"industries\":[]}]}";

    assertThat(HANDLER.targetPaths("Map1", twoEntries)).containsExactly("1_2", "-3_4");
  }

  // ── 一次性投入槽与种子累加器（v2 spec §3.3）────────────────────────────────────────

  /** ★★ 缺键 ⇒ 空 map / 0（旧载荷兼容：命令路径不许因为多了一个字段就把老生成器挡在门外）。 */
  @Test
  void legacyPayloadWithoutCycleInputStillSeedsEmptyAndZero() {
    EconomyData after = apply(PAYLOAD, EconomyData.empty(), T7);

    Industry industry = after.industries().get(FARM);
    assertThat(industry.cycleInputPerUnit()).as("旧载荷没提一次性投入 ⇒ 空 map（不是 null、不拒）").isEmpty();
    assertThat(industry.cycleSeedUsedMilli()).as("旧载荷没提种子累加器 ⇒ 0").isZero();
  }

  /**
   * ★★ **R3 换型后的两个字段逐值过载荷**：投入表的值侧带**商品维度**（`{"LAND":{"grain":1200,"wood":3}}`）， 累加器是**按商品**的表。
   *
   * <p>判别力：把值侧退回标量（`{"LAND":1200}`）⇒ 载荷解析当场拒（"必须是商品表"）⇒ 本用例红。
   */
  @Test
  void parsesCycleInputWithCommodityDimensionAndTheInputAccumulator() {
    String payload =
        PAYLOAD
            .replace(
                "\"capacityPerUnit\":{\"LAND\":1000}",
                "\"capacityPerUnit\":{\"LAND\":1000,\"CATTLE\":1}")
            .replace(
                "\"dailyInputPerUnit\":{}",
                "\"dailyInputPerUnit\":{},"
                    + "\"cycleInputPerUnit\":{\"LAND\":{\"grain\":1200,\"wood\":3},\"CATTLE\":{\"grain\":1}},"
                    + "\"cycleInputUsedMilli\":{\"grain\":5000,\"fiber\":7}");
    assertThat(payload).as("替换必须真的发生（否则本用例测的是缺键那条路）").isNotEqualTo(PAYLOAD);

    Industry industry = apply(payload, EconomyData.empty(), T7).industries().get(FARM);

    assertThat(industry.cycleInputPerUnit())
        .as("每种生产资料一路，值为**商品表**（R3 换型的那一维）")
        .containsOnlyKeys(AssetKind.LAND, AssetKind.CATTLE);
    assertThat(industry.cycleInputPerUnit().get(AssetKind.LAND))
        .as("同一种生产资料下可以挂多个商品")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 1200L, WOOD_COMMODITY, 3L));
    assertThat(industry.inputPerUnit())
        .as("★ 每 1 单位规模的投入 = 各路的合计（派生视图）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 1201L, WOOD_COMMODITY, 3L));
    assertThat(industry.cycleInputUsedMilli())
        .as("★ 本周期实际扣到的投入（按商品）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 5_000L, FIBER_COMMODITY, 7L));
    assertThat(industry.cycleSeedUsedMilli()).as("种子只是它在粮上的投影").isEqualTo(5_000L);
  }

  /** ★ 逐值校验：负的一次性投入 ⇒ 拒（`Industry` 的构造期守卫，经 handler 的 catch 折成 Rejected）。 */
  @Test
  void rejectsNegativeCycleInput() {
    String payload =
        PAYLOAD.replace(
            "\"dailyInputPerUnit\":{}",
            "\"dailyInputPerUnit\":{},\"cycleInputPerUnit\":{\"LAND\":{\"grain\":-1}}");

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("cycleInputPerUnit");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static EconomyData apply(String payload, EconomyData base, SimosTimestamp at) {
    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied) HANDLER.handle(state(base, at), payload);
    return EconomyChangeSet.apply((EconomyChangeSet) applied.changeSet(), base);
  }

  private static SimulationState state(EconomyData data, SimosTimestamp timestamp) {
    return new SimulationState(
        new StateMeta(REF, timestamp),
        Map.of("economy", new EconomySnapshot(REF, timestamp, data)),
        InMemoryInfoSystem.empty());
  }
}
