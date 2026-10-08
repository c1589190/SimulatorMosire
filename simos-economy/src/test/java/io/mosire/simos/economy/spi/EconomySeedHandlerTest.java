package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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

  /** 本夹具的格（S1：家户键 = 稳定 {@link HouseholdId}；视图仍是 格 + 居住类型 + 阶层）。 */
  private static final HexCoord HEX = new HexCoord(0, 0);

  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final IndustryId FARM2 = new IndustryId("farm@1_0");

  /** 旧载荷的 default operator（regime=feudal ⇒ ESTATE:farm@0_0，见 {@link RegimeOperators}）。 */
  private static final ActorRef ESTATE_FARM = new ActorRef(ActorKind.ORGANIZATION, "farm@0_0");

  /**
   * ★★ R3B.2：关系表的键与值内 {@code activity} 都是 {@link ProductionUnitId}（唯一拼写点 {@link
   * ProductionUnitId#idOf}）。旧夹具把它写成 {@code IndustryId} —— 关系的身份不再是"产业"而是"生产单元"。
   */
  private static final ProductionUnitId FARM_UNIT = ProductionUnitId.idOf(FARM, ESTATE_FARM);

  /**
   * ★★ S1：{@code classes}/{@code flows} 的键是稳定家户身份 {@link HouseholdId}，不再是视图 {@code CohortKey}。 载荷没有
   * {@code householdId} 键时，{@code EconomyPayloads.classRow} 用 {@link HouseholdId#ofSeed} 生成新档 id
   * （唯一拼写点）—— 本夹具与它逐值一致，故这里也走 {@code ofSeed}。
   */
  private static final HouseholdId PEASANT_HOUSEHOLD =
      HouseholdIds.ofSeed(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT);

  private static final HouseholdId LANDLORD_HOUSEHOLD =
      HouseholdIds.ofSeed(HEX, ResidenceKind.RURAL, SocialClassId.LANDLORD);

  /** R3 的商品词（与载荷里的字面量同字面量；用途见 {@code parsesCycleInputWithCommodityDimension…}）。 */
  private static final CommodityId GRAIN = new CommodityId("grain");

  private static final CommodityId FIBER_COMMODITY = new CommodityId("fiber");
  private static final CommodityId WOOD_COMMODITY = new CommodityId("wood");

  /**
   * 一段最小合法载荷：一格、一个农业产业（封建租佃）、两个槽位两条**家户行**。
   *
   * <p>★★ <b>2026-09-27（H0，裁定 K2/K3）的三处形状变化</b>：① {@code classes} 从产业节点内搬到 <b>entry 级</b>，每行显式带
   * {@code residence}（行 = 家户 = 格 + 居住类型 + 阶层）；② 行上不再有 {@code meansOfProduction}（给了即抛）； ③ 产业多一个
   * {@code capacity}（本格该产业的产能总量 = 改前 Σ各行的 meansOfProduction = 900,000 + 100,000 = 1,000,000 千分亩）。
   */
  private static final String PAYLOAD =
      "{\"mapId\":\"Map1\",\"rulesVersion\":\"aggregate-v1\",\"entries\":[{\"q\":0,\"r\":0,"
          + "\"industries\":[{\"id\":\"farm@0_0\",\"name\":\"农业\",\"regime\":\"feudal\","
          + "\"cycleDays\":120,\"progressDays\":0,"
          // ★ R3（V7）：配方的产能锚与劳动那一路（缺 capacityPerUnit ⇒ 构造期拒 ⇒ 整条命令被 Rejected）
          + "\"capacityPerUnit\":{\"LAND\":1000},"
          // ★★ H0/K3：本格该产业的产能总量（缺键 ⇒ 空表 ⇒ 规模 0；逐值允许 0）
          + "\"capacity\":{\"LAND\":1000000},\"laborPerUnit\":143,"
          + "\"dailyInputPerUnit\":{},\"dailyLaborPerUnit\":0,"
          + "\"outputPerUnit\":{\"grain\":7},"
          + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":700,\"laborWeightPerMille\":300},"
          + "\"slots\":[{\"id\":\"poor_peasant\",\"name\":\"贫农\",\"laborParticipationPerMille\":950},"
          + "{\"id\":\"landlord\",\"name\":\"地主\",\"laborParticipationPerMille\":100}]}],"
          // ★★ H0：家户行挂 **entry 级**，每行显式带 residence（缺键即抛 —— 不许按产业种类猜）
          + "\"classes\":[{\"residence\":\"rural\",\"slot\":\"poor_peasant\",\"population\":450,"
          + "\"laborMilli\":261000,\"participationPerMille\":950,"
          // ★★ H1（K1）：行里**没有 goods 键**了 —— 商品库存住在 actor 切片的 HouseholdInventory 上，
          //    载荷里再给一个会被 fail-closed 拒（见 EconomyPayloads.classRow 的第二条守卫）。
          + "\"money\":0,\"debts\":[],\"naturalNeeds\":{\"grain\":37350},"
          + "\"effectiveDemand\":{}},"
          + "{\"residence\":\"rural\",\"slot\":\"landlord\",\"population\":50,\"laborMilli\":29000,"
          + "\"participationPerMille\":100}]}]}";

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
          "\"participationPerMille\":100}]}]}",
          "\"participationPerMille\":100}],"
              + "\"laborSupply\":[{\"group\":\"rural:0_0:MALE:1\",\"period\":1,"
              + "\"grossLaborMilli\":290000,\"servedLaborMilli\":0,\"committedLaborMilli\":0}],"
              + "\"allocations\":[{\"id\":\"alloc-farm@0_0-rural:0_0:MALE:1\","
              + "\"group\":\"rural:0_0:MALE:1\","
              + "\"actor\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"},"
              + "\"activity\":\"farm\",\"laborMilli\":250000,\"period\":1}]}]}");

  /**
   * ★★ `WageFirst`（资本主义工业）在 v1 没有结算实现 ⇒ 必须**播种期**拒（v2 spec §八.4）。
   *
   * <p>判别力：v1 允许它入库，直到某个收获日才炸（T4 之前是 `EconomySettlement.harvest` 抛
   * `UnsupportedOperationException`）—— 那个异常穿出协调器的 `simulateWorld`， 让整条 `AdvanceTime` revision
   * 失败（既不是 `Rejected` 也不是降级）。★ T4 起 `harvest` 不再读 `AllocationRule` ⇒ 本守卫是这条口径**唯一**的落点。
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

  /**
   * ★ 正例：供给逐值落盘；配额**逐家户**落盘（S1 起键 = {@code HouseholdId}）。
   *
   * <p>★★ <b>S1 自动迁移（本用例必须点名，否则期望值无从解释）</b>：载荷的配额没有 {@code household} 键 ⇒ {@code
   * EconomyPayloads.allocation} 造旧档 pending 占位，{@code EconomyData} 构造期自动跑 {@code
   * LegacyHouseholdMigration.migrate}，**按本格该居住类型的两行人口（450 : 50）把一条配额拆成两条真实家户配额** （225,000 /
   * 25,000）。出处：{@code EconomyData} 构造期注释（"旧 LaborAllocation 没有 household … 在这里一次性补齐"， 约 326-361 行）与
   * {@code LegacyHouseholdMigration.migrate} 的 {@code candidateRows} 分支。
   *
   * <p>★ 旧断言"只剩一条配额、id 是 {@code alloc-farm@0_0-rural:0_0:MALE:1}"随 S1 身份迁移**已被生产代码删除**： 现在每条配额 id 由
   * {@link HouseholdLaborCommitment#idOf(IndustryId, PeopleLotId, HouseholdId)} 唯一拼写（含家户段）。
   */
  @Test
  void seedsAllocationsValueForValue() {
    EconomyData after = apply(PAYLOAD_WITH_LABOR, EconomyData.empty(), T7);

    PeopleLotId lot = new PeopleLotId("rural:0_0:MALE:1");
    // ★ P2-A A4：LaborSupply（每批次供给表）已删除 —— 劳动预算的唯一权威是 HouseholdEconomy.laborMilli
    //   （每 tick 由 Social 家户成员重算）；本用例保留配额那半边的逐值断言。
    assertThat(after.classes()).as("创世仍落阶层行（家户时间预算的载体）").isNotEmpty();
    assertThat(after.allocations()).as("旧配额缺 household ⇒ S1 迁移按两行人口拆成两条").hasSize(2);
    HouseholdLaborCommitment peasant =
        after.allocations().get(HouseholdLaborCommitment.idOf(FARM, lot, PEASANT_HOUSEHOLD));
    assertThat(peasant).as("id 的唯一拼写点 = alloc-<产业>-<批次>-<家户>").isNotNull();
    assertThat(peasant.household()).as("家户是迁移按格与居住类型定位出来的真实家户").isEqualTo(PEASANT_HOUSEHOLD);
    assertThat(peasant.group()).isEqualTo(lot);
    assertThat(peasant.actor()).isEqualTo(ESTATE_FARM);
    assertThat(peasant.activity())
        .as("R3B.2：activity 对齐到 unit id（不再喂 farm 这个旧标签）")
        .isEqualTo(FARM_UNIT.value());
    assertThat(peasant.laborMilli()).as("250,000 × 450 ÷ 500").isEqualTo(225_000L);
    assertThat(peasant.period()).isEqualTo(1L);

    HouseholdLaborCommitment landlord =
        after.allocations().get(HouseholdLaborCommitment.idOf(FARM, lot, LANDLORD_HOUSEHOLD));
    assertThat(landlord).as("第二条拆给地主行").isNotNull();
    assertThat(landlord.household()).isEqualTo(LANDLORD_HOUSEHOLD);
    assertThat(landlord.actor()).isEqualTo(ESTATE_FARM);
    assertThat(landlord.activity()).isEqualTo(FARM_UNIT.value());
    assertThat(landlord.laborMilli()).as("250,000 × 50 ÷ 500").isEqualTo(25_000L);
    assertThat(landlord.period()).isEqualTo(1L);
  }

  /**
   * ★★ **I2.1 的格式护栏**（上移 {@code ActorRef} 之前先钉住既有行为，裁定 R4）：载荷里的 {@code
   * "actor":{"kind":"ESTATE","id":"farm@0_0"}} 这一串**逐值**还原，且**再编码回去仍是同一串**。
   *
   * <p>★ 为什么必须有它：本仓**没有** JSON 黄金夹具，{@code ActorRef} 的线格式只能靠测试钉住 —— 一旦有人给它加 Jackson 注解、把 {@code
   * kind} 写成 ordinal、或换个字段名，线格式就变了，而"上移"这件事本身不许碰它（R1/R4）。 故它是上移**唯一**的格式护栏，且必须**先绿后搬**。
   */
  @Test
  void actorRefPayloadRoundTripsValueForValue() throws Exception {
    JsonNode payload = EconomyPayloads.parse(PAYLOAD_WITH_LABOR);
    JsonNode actorNode = payload.at("/entries/0/allocations/0/actor");
    assertThat(actorNode.isMissingNode()).as("载荷里必须有 actor 节点（否则本用例测的是空气）").isFalse();
    assertThat(actorNode.toString())
        .as("载荷字面：字段名 kind/id + 枚举 name()")
        .isEqualTo("{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"}");

    // ① 载荷 → 领域类型：kind 与 id 逐值还原
    //   ★ S1：旧配额缺 household ⇒ 迁移按家户拆成两条，id 随之取 canonical 拼写（alloc-<产业>-<批次>-<家户>）；
    //     取拆给贫农家户的那一条即可（两条的 actor 是同一串，见上一条用例）。
    EconomyData after = apply(PAYLOAD_WITH_LABOR, EconomyData.empty(), T7);
    ActorRef actor =
        after
            .allocations()
            .get(
                HouseholdLaborCommitment.idOf(
                    FARM, new PeopleLotId("rural:0_0:MALE:1"), PEASANT_HOUSEHOLD))
            .actor();
    assertThat(actor.kind()).as("kind 逐值还原").isEqualTo(ActorKind.ORGANIZATION);
    assertThat(actor.id()).as("id 逐值还原").isEqualTo("farm@0_0");

    // ② 领域类型 → JSON：再编码回去仍是同一串（SimosObjectMapper 就是落盘用的那台 mapper）
    assertThat(SimosObjectMapper.create().writeValueAsString(actor))
        .as("ActorRef 的线格式一字不改")
        .isEqualTo("{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"}");
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
    assertThat(after.allocations()).isEmpty();
  }

  /** ★ **配额之和超过可用劳动 ⇒ 命令边界拒**（"同一批人的劳动不得被两个产业各算一次满额"）。 */
  @Test
  void rejectsAQuotaThatExceedsTheAvailableLabor() {
    String payload = PAYLOAD_WITH_LABOR.replace("\"laborMilli\":250000", "\"laborMilli\":290001");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD_WITH_LABOR);

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("超过它的每 tick 时间预算");
  }

  /** ★ **词表外的 actor 种类 ⇒ 命令边界拒**（并列出合法值：静默收下会让"写错主体种类"变成运行时幽灵）。 */
  @Test
  void rejectsAnActorKindOutsideTheVocabulary() {
    String payload = PAYLOAD_WITH_LABOR.replace("\"kind\":\"ORGANIZATION\"", "\"kind\":\"MANOR\"");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD_WITH_LABOR);

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("拒因列出词表")
        .contains("未知 ActorKind")
        .contains("ORGANIZATION");
  }

  /**
   * ★★ <b>旧形状载荷的迁移失败必须留在命令边界</b>：缺 {@code household}/{@code memberships} 的旧载荷会让 {@code EconomyData}
   * 构造期先跑 {@code LegacyHouseholdMigration}；配额 actor 定位不到产业格时它抛 {@link IllegalStateException}。该失败与
   * {@code IllegalArgumentException} 一样是**载荷语义错误**， 必须成为 {@code Rejected}，不得穿出命令边界。
   */
  @Test
  void oldShapePayloadWithAnUnknownIndustryIsRejectedInsteadOfLeakingMigrationFailure() {
    String payload =
        PAYLOAD_WITH_LABOR.replace(
            "\"actor\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"}",
            "\"actor\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_1\"}");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD_WITH_LABOR);

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("拒因来自旧档迁移的 fail-closed（而不是把异常穿出命令边界）")
        .contains("旧档迁移失败");
  }

  /**
   * ★ 追加第二国时，两张新表**按格一并追加**（与产业/阶层行同一套判重口径）。
   *
   * <p>★ S1 起每国的旧配额会被自动迁移按本格两行人口拆成 2 条 ⇒ 两国合计 4 条；供给仍是每国 1 条。
   */
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

    assertThat(both.allocations()).as("S1 迁移后每国 2 条（两行家户各一）⇒ 两国 4 条").hasSize(4);
    assertThat(both.industries()).hasSize(2);
    assertThat(
            both.allocations().values().stream()
                .mapToLong(HouseholdLaborCommitment::laborMilli)
                .sum())
        .as("两国的承诺劳动合计 = 每国 250,000（拆分行人口不改变总量）")
        .isEqualTo(500_000L);
  }

  /** 正例：与 §3 的 record 字段**逐值**对应（元信息 / 产业模板 / 生产单元 / 阶层行）。 */
  @Test
  void seedsEveryFieldValueForValue() {
    EconomyData after = apply(PAYLOAD, EconomyData.empty(), T7);

    EconomyMeta meta = after.meta().orElseThrow();
    assertThat(meta.mapId()).isEqualTo("Map1");
    assertThat(meta.activatedDay()).as("激活日 = 世界当前 tick").isEqualTo(7L);
    assertThat(meta.lastClosedCycle()).isEqualTo(OptionalLong.empty());
    // ★★ P2-A 口径迁移：旧档迁移的触发条件不再包含 memberships（该组件已删）——本载荷没有待迁移配额，
    //   故 meta 保持载荷原值、不打迁移落款。旧断言（自动升到 pre-modern-v1）已随该触发条件退役。
    assertThat(meta.rulesVersion()).as("无待迁移配额 ⇒ meta 原样").isEqualTo("aggregate-v1");
    assertThat(meta.migrationSource()).isEmpty();

    Industry industry = after.industries().get(FARM);
    assertThat(after.industries()).hasSize(1);
    assertThat(industry.name()).isEqualTo("农业");
    assertThat(industry.regime().value()).isEqualTo("feudal");
    // ★★ R3B.2：Industry 现在是**纯技术模板**，operator 搬到 ProductionProcess。旧载荷缺 operator 键 ⇒
    //   载荷边缘按 regime 推到 unit.operator（唯一拼写点仍是 RegimeOperators.defaultOperator）。
    //   出处：Industry 类注的"三件事各归各位"与 ProductionProcess 类注。
    ProductionProcess unit = after.units().get(FARM_UNIT);
    assertThat(unit).as("旧载荷的 operator/capacity 兼容位合成一个默认 unit").isNotNull();
    assertThat(unit.industry()).isEqualTo(FARM);
    assertThat(unit.operator())
        .as("缺 operator 的旧载荷按 regime 补默认值（落点是 unit，不再是 Industry）")
        .isEqualTo(ESTATE_FARM);
    assertThat(industry.operator()).as("Industry 端的旧兼容位保持中性").isNull();
    assertThat(industry.cycleDays()).isEqualTo(120L);
    assertThat(industry.progressDays()).isZero();
    assertThat(industry.outputPerUnit()).containsEntry(new CommodityId("grain"), 7L);
    assertThat(industry.dailyInputPerUnit()).isEmpty();
    assertThat(industry.dailyLaborPerUnit()).isZero();
    assertThat(industry.slots())
        .extracting(slot -> slot.id().value())
        .containsExactly("poor_peasant", "landlord");

    HouseholdEconomy row = after.classes().get(PEASANT_HOUSEHOLD);
    assertThat(after.classes()).hasSize(2);
    assertThat(row).as("S1：classes 的键 = 稳定家户身份（不再是视图 CohortKey）").isNotNull();
    assertThat(row.id()).isEqualTo(PEASANT_HOUSEHOLD);
    assertThat(row.view())
        .as("视图仍是载荷声明的（格 + 居住类型 + 阶层），只是不再兼任身份")
        .isEqualTo(new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT));
    assertThat(row.population()).isEqualTo(450L);
    assertThat(row.laborMilli()).isEqualTo(261_000L);
    assertThat(row.participationPerMille()).isEqualTo(950);
    // ★★ 2026-09-27（H0/K3）→ R3B.1/B.2：改前断言"行的 meansOfProduction 有 900,000 千分亩"，后来搬到
    //   Industry.capacity；B.2 起 Industry.capacity 也只是旧档兼容位（生产模型不再读它）⇒ **实物产能的唯一真源是
    //   OwnershipStake 的 quantity**。出处：EconomyData 构造期注释（约 582-604 行"OwnershipStake 是独立的实物资产总账"）
    //   与 ProductionProcess 类注"OwnershipStake —— 实物总账：唯一的 quantity 真相"。
    //   量到的仍是同一件事（两行的 900,000 + 100,000 = 1,000,000 千分亩进来了），只是落点又换了一次。
    assertThat(industry.capacity()).as("Industry 端的兼容位已被构造期归一化清成中性").isEmpty();
    OwnershipStake land =
        after.assetShares().values().stream()
            .filter(share -> share.industry().equals(FARM) && share.asset() == AssetKind.LAND)
            .findFirst()
            .orElseThrow();
    assertThat(land.quantity()).as("900,000 + 100,000 千分亩").isEqualTo(1_000_000L);
    assertThat(land.owner()).as("旧载荷的默认 operator 也是这份实物份额的 owner").isEqualTo(ESTATE_FARM);
    assertThat(land.operator()).isEqualTo(ESTATE_FARM);
    assertThat(land.kind()).isEqualTo(OwnershipStake.RightKind.OWNED);
    // ★★ 2026-09-27（H1/K1）：改前这里断言"行里有 2,241,000 毫粮"（载荷的 goods 键 → HouseholdEconomy.goods）。
    //   那个字段已按裁定 D3-C/K1 **整个删除**（家户的商品库存住在 actor 切片的 HouseholdInventory 上），
    //   而载荷里也不再接受 goods 键（给了即抛）⇒ 这条断言的**主语不存在了**：整条删除（不是放宽），如实记在 H1 的变更说明里。
    //   ★ 同一件事的**新落点**不在本模块的载荷里：库存的播种归 app 的 HouseholdSeeder（H1.5），
    //     它的验证在 simos-app 的播种用例里（`WorldgenInitializeToolTest` 那一族）。
    assertThat(row.money()).isZero();
    assertThat(after.debtsOf(PEASANT_HOUSEHOLD)).as("本轮无债务引用").isEmpty();
    assertThat(row.naturalNeeds()).containsEntry(new CommodityId("grain"), 37_350L);
    assertThat(row.effectiveDemand()).isEmpty();
    assertThat(after.debtContracts()).as("债务合同表本轮恒空").isEmpty();
    assertThat(after.flows()).as("周期流水留待 R3a").isEmpty();
    // 缺省字段（地主行没给 naturalNeeds/effectiveDemand/money）⇒ 空表 / 0，不是 null。
    // ★ 2026-10-09 选项 A：载荷里的 debts 键不再进状态（引用是派生表，读口 = debtsOf）⇒ 这里改读读口。
    HouseholdEconomy landlord = after.classes().get(LANDLORD_HOUSEHOLD);
    assertThat(landlord).isNotNull();
    assertThat(landlord.id()).isEqualTo(LANDLORD_HOUSEHOLD);
    assertThat(landlord.view().stratum()).isEqualTo(SocialClassId.LANDLORD);
    assertThat(landlord.money()).isZero();
    assertThat(after.debtsOf(LANDLORD_HOUSEHOLD)).isEmpty();
    assertThat(landlord.naturalNeeds()).isEmpty();
    assertThat(landlord.effectiveDemand()).isEmpty();
  }

  /**
   * ★★ **I3.1「写得进」**：载荷**显式**给了 {@code operator} ⇒ 逐值落盘，**且不等于** regime 的推导值。
   *
   * <p>★ 为什么必须用**非默认**值（制度 {@code feudal}，而主体是 {@code HOUSEHOLD:house-7}）：若断言的只是推导值， "读了没读这个键"就测不出来
   * —— 一个**永远按 regime 推**的坏实现照样全绿（假绿）。它同时是"显式绑定不是标签" 在**载荷层**的证据（裁定 R4）。
   */
  @Test
  void seedsAnExplicitOperatorValueForValue() {
    String payload =
        PAYLOAD.replace(
            "\"regime\":\"feudal\",",
            "\"regime\":\"feudal\",\"operator\":{\"kind\":\"HOUSEHOLD\",\"id\":\"house-7\"},");
    assertThat(payload).as("替换必须真的发生（否则本用例测的是缺键那条路）").isNotEqualTo(PAYLOAD);

    EconomyData after = apply(payload, EconomyData.empty(), T7);
    ActorRef explicit = new ActorRef(ActorKind.HOUSEHOLD, "house-7");
    // ★ R3B.2：显式 operator 决定 unit 的身份（id 的唯一拼写点 idOf(FARM, explicit)）与值内 operator。
    ProductionUnitId unitId = ProductionUnitId.idOf(FARM, explicit);
    ProductionProcess unit = after.units().get(unitId);

    assertThat(unit).as("显式 operator ⇒ 按 (产业, 经营者) 合成的 unit").isNotNull();
    assertThat(unit.operator()).as("显式写下的主体逐值落到 unit.operator").isEqualTo(explicit);
    assertThat(after.relations().get(unitId).operator())
        .as("跨表：关系的 operator 与 unit.operator 逐值一致")
        .isEqualTo(explicit);
    assertThat(unit.operator())
        .as("★ 判别力：它**不等于** regime 推导值（feudal ⇒ ESTATE:farm@0_0）")
        .isNotEqualTo(RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM));
  }

  /** ★ 已激活后**按格追加**：同一库连播两国，两批的格都在、人口/库存合计 = 两批之和，且 meta 不覆盖。 */
  @Test
  void appendsNewHexesOfALaterNation() {
    EconomyData first = apply(PAYLOAD, EconomyData.empty(), T7);

    EconomyData both = apply(LATER_NATION_PAYLOAD, first, SimosTimestamp.of(9));

    assertThat(both.industries()).as("两批的产业都在").containsKeys(FARM, FARM2);
    assertThat(both.classes()).as("两批各 2 行").hasSize(4);
    assertThat(both.classes().values().stream().mapToLong(HouseholdEconomy::population).sum())
        .as("两批人口合计 = 500 + 500")
        .isEqualTo(1_000L);
    // ★★ 2026-09-27（H1/K1）：改前这里断言"两批**库存**合计 = 4,980,000"（读 HouseholdEconomy.goods）。
    //   库存已不在行里、载荷也不再接受 goods 键 ⇒ 这条断言的**主语不存在了**：整条删除（不是放宽）。
    //   ★ 本条剩下的两件事（两批的格都在、人口合计 = 两批之和）与"后来那批的库存进没进账"无关 ——
    //     后者现在由 app 侧的 HouseholdSeeder 负责（H1.5）。
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

  /**
   * ★★ **R4-B.4：{@code view} 不再受 {@code Industry.slots} 约束**（旧守卫已被生产代码删除）—— 本用例从 "悬空槽位 ⇒
   * 拒"**改判**为"照常落盘、并以稳定家户身份可寻址"。
   *
   * <p>出处（逐字）：{@code EconomyData} 构造期注释"R4-B.4（R3 决策单 §0.1/§1.5，R3B.4）：**view 不再受 Industry.slots
   * 约束**。旧守卫（view 必须命中该格产业的 slots 且 participationPerMille ≤ 该 slot 的
   * laborParticipationPerMille）已删除；Industry.slots / ClassSlot 只作为生产方式内部的角色/劳动配置。 HouseholdEconomy
   * 自己的 [0,1000] 参与率守卫仍在"。
   *
   * <p>★ 判别力：{@code middle_peasant} 在全局词表内、但**不在**本产业声明的 slots（只有 poor_peasant / landlord）里 ——
   * 若那条旧守卫被加回来，本用例会抛 ⇒ 红。
   */
  @Test
  void classRowOutsideTheIndustrySlotsIsAcceptedUnderTheCurrentContract() {
    String payload = PAYLOAD.replace("\"slot\":\"landlord\"", "\"slot\":\"middle_peasant\"");
    assertThat(payload).as("替换必须真的发生（否则本用例测的是 landlord 那条正例）").isNotEqualTo(PAYLOAD);

    EconomyData after = apply(payload, EconomyData.empty(), T7);

    HouseholdId middlePeasantHousehold =
        HouseholdIds.ofSeed(HEX, ResidenceKind.RURAL, SocialClassId.MIDDLE_PEASANT);
    assertThat(after.classes()).containsKey(middlePeasantHousehold);
    assertThat(after.classes().get(middlePeasantHousehold).view())
        .as("阶层照进视图；slots 不再是白名单")
        .isEqualTo(new CohortKey(HEX, ResidenceKind.RURAL, SocialClassId.MIDDLE_PEASANT));
  }

  /**
   * ★★ **词表外的阶层在命令边界即拒**（S1 spec §2.6 的 fail-closed；Review Focus ①）。
   *
   * <p>★ 判别力：把 {@code SocialClassId} 的构造器词表校验删掉 ⇒ 这一条红（{@code ghost} 会被静默收下， 直到某天在 {@code
   * EconomyData} 的引用完整性里才炸，甚至根本不炸 —— 若某个产业恰好也声明了同名槽位）。
   */
  @Test
  void rejectsAStratumOutsideTheGlobalVocabulary() {
    String payload = PAYLOAD.replace("\"slot\":\"landlord\"", "\"slot\":\"ghost\"");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);

    assertThatThrownBy(() -> EconomyPayloads.toData(EconomyPayloads.parse(payload), T7))
        .as("词表外的阶层必须当场抛，且消息里列出合法值")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ghost")
        .hasMessageContaining("poor_peasant")
        .hasMessageContaining("landlord");
  }

  /** ★ 逐值校验：负人口 ⇒ 拒（`HouseholdEconomy` 的构造期守卫）。 */
  @Test
  void rejectsNegativePopulation() {
    String payload = PAYLOAD.replace("\"population\":450", "\"population\":-450");

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("population");
  }

  /**
   * 类行 {@code debts} 是**派生引用**：E4 起合同表是唯一权威，构造期 {@code DebtReferenceReconciler} 从合同表重建引用 ⇒
   * 指向不存在合同的旧引用会被重建成空表（不是命令拒绝，也不再留下悬空引用）。
   *
   * <p>★★ <b>2026-10-09 选项 A 的迁移（如实记）</b>：{@code HouseholdEconomy.debts} 这个组件已删，引用住在独立表 {@code
   * EconomyData.householdDebtRefs} 上，唯一读口是 {@code EconomyData.debtsOf(...)} ⇒ 断言的主语从"行的字段"换成"读口"。 ★
   * 载荷里的 {@code debts} 键**仍被形状校验**（{@code EconomyPayloads} 逐项要求非空字符串）但**不进状态**（ 引用只能由合同表派生）⇒
   * 本用例的输入替换仍有意义：它在量"载荷里塞了引用也不会留在状态里"。
   */
  @Test
  void classRowDebtRefsAreRebuiltFromContractTable() {
    String payload = PAYLOAD.replace("\"debts\":[]", "\"debts\":[\"debt-1\"]");

    EconomyData after = apply(payload, EconomyData.empty(), T7);

    assertThat(after.debtContracts()).as("载荷没有合同 ⇒ 合同表为空").isEmpty();
    assertThat(after.classes().keySet())
        .as("旧类行引用只是派生索引：悬空引用被合同表权威重建为空")
        .allSatisfy(household -> assertThat(after.debtsOf(household)).isEmpty());
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

  // ── 一次性投入槽与投入累加器（v2 spec §3.3；R3B.2 起累加器住 unit）────────────────────

  /** ★★ 缺键 ⇒ 空 map（旧载荷兼容：命令路径不许因为多了一个字段就把老生成器挡在门外）。 */
  @Test
  void legacyPayloadWithoutCycleInputStillSeedsEmptyAccumulator() {
    EconomyData after = apply(PAYLOAD, EconomyData.empty(), T7);

    Industry industry = after.industries().get(FARM);
    assertThat(industry.cycleInputPerUnit()).as("旧载荷没提一次性投入 ⇒ 空 map（不是 null、不拒）").isEmpty();
    // ★ R3B.2：本周期实际扣到的投入（按商品）住在 unit；旧载荷没提 ⇒ 空表。
    //   出处：ProductionProcess 类注"cycleInputUsedMilli = 本周期实际扣到的投入（毫单位，按商品）"。
    //   ★ 旧读口 Industry.cycleSeedUsedMilli()（粮上的标量投影）已被生产代码删除，本断言随之换主语。
    assertThat(after.units().get(FARM_UNIT).cycleInputUsedMilli()).as("旧载荷没提投入累加器 ⇒ 空表").isEmpty();
    assertThat(industry.cycleInputUsedMilli()).as("Industry 端的旧兼容位已被构造期归一化清成中性").isEmpty();
  }

  /**
   * ★★ **R3 换型后的两个字段逐值过载荷**：投入表的值侧带**商品维度**（`{"LAND":{"grain":1200,"wood":3}}`）， 累加器是**按商品**的表 ——
   * R3B.2 起累加器落在 unit 上。
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

    EconomyData after = apply(payload, EconomyData.empty(), T7);
    Industry industry = after.industries().get(FARM);

    assertThat(industry.cycleInputPerUnit())
        .as("每种生产资料一路，值为**商品表**（R3 换型的那一维）")
        .containsOnlyKeys(AssetKind.LAND, AssetKind.CATTLE);
    assertThat(industry.cycleInputPerUnit().get(AssetKind.LAND))
        .as("同一种生产资料下可以挂多个商品")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 1200L, WOOD_COMMODITY, 3L));
    assertThat(industry.inputPerUnit())
        .as("★ 每 1 单位规模的投入 = 各路的合计（派生视图）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 1201L, WOOD_COMMODITY, 3L));
    // ★ R3B.2：累加器住 unit（按商品），Industry 端只留中性的旧兼容位。
    //   旧断言 "industry.cycleSeedUsedMilli() == 5000（种子只是粮上的投影）" 随该读口一并删除（生产代码只保留
    //   unit.cycleInputUsedMilli 这一份逐商品真相）。
    assertThat(after.units().get(FARM_UNIT).cycleInputUsedMilli())
        .as("★ 本周期实际扣到的投入（按商品）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 5_000L, FIBER_COMMODITY, 7L));
    assertThat(industry.cycleInputUsedMilli()).as("Industry 端的旧兼容位保持中性").isEmpty();
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

  // ── T2：载荷里的可选项 relation（第 8 个组件；计划 R3）────────────────────────────────

  /**
   * ★★ **缺 {@code relation} 键 ⇒ 在载荷边缘按 {@code regime} 推导**（同 {@code operator} 的 D1 口径）。
   *
   * <p>★★ <b>为什么夹具的制度是 {@code tenant} 而不是 {@code feudal}</b>（本条用例的判别力全在这里）： {@code feudal}
   * 是登记表的**第一档** ⇒ "一律取第一条已登记档"这种坏实现<b>照样绿</b> （等价变异体，本仓踩过五次）。换成 {@code tenant}（第四档）后，那一档的规则（**1
   * 条固定粮租**） 与第一档（**5 条：4 条给养 + 1 条 300‰ 地租**）在**条数、类型、数量**上都可观测地不同。
   *
   * <p>★ 期望值是**逐值字面量**（不调 {@code RegimeRelations}）—— 否则"把出厂值改掉"不会红。
   */
  @Test
  void aPayloadWithoutRelationDerivesTheDefaultFromItsRegime() {
    // ★★ 2026-09-27（H0/R-N1-A）：默认关系的 cohort 受方要带**居住类型**，而它的唯一来源是**劳动配额表**
    //   ⇒ 本夹具必须用**带劳动表**的那一份载荷（`PAYLOAD` 没有配额 ⇒ 说不出这批家户住哪儿 ⇒ 推不出 cohort 规则）。
    //   ★ 改的是**夹具**，不是断言：断言的仍是"缺 relation 键 ⇒ 按 regime 推出那一条固定实物租"。
    String payload = PAYLOAD_WITH_LABOR.replace("\"regime\":\"feudal\"", "\"regime\":\"tenant\"");
    assertThat(payload).as("替换必须真的发生（否则本用例测的是 feudal 那条路）").isNotEqualTo(PAYLOAD);

    EconomyData after = apply(payload, EconomyData.empty(), T7);

    // tenant 档的默认经营者 = HOUSEHOLD:farm@0_0（RegimeOperators）；R3B.2 起关系挂在 (产业, 经营者) 合成的 unit 上。
    ActorRef tenant = new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0");
    ProductionUnitId unitId = ProductionUnitId.idOf(FARM, tenant);

    assertThat(after.relations()).as("每个 unit 一条关系（缺键 ⇒ 推导）").hasSize(1);
    assertThat(after.relations().get(unitId))
        .as("★ 租佃档逐值：一条固定实物租（20,000,000 粮/周期）给 landlord 家户")
        .isEqualTo(
            new ProductionRules(
                unitId,
                tenant,
                null,
                List.of(
                    new CompensationRule(
                        RuleType.FIXED_IN_KIND_RENT,
                        // ★ S1：默认规则的 cohort 视图在 {@code EconomyData} 构造期被一对一归一到
                        //   Payee.ToHousehold（本夹具该视图恰有一行 ⇒ 唯一），见 normalizeRecipients。
                        new Payee.ToHousehold(LANDLORD_HOUSEHOLD),
                        Pool.FIXED_AMOUNT,
                        Weight.NONE,
                        0,
                        20_000_000L,
                        Optional.of(GRAIN),
                        Optional.empty(),
                        10)),
                tenant,
                LaborSource.TENANT));
    // ★ 跨表一致性：关系里的 operator 与 unit.operator 是**同一个值**（两处拼写必须一致，构造期守卫判死）
    assertThat(after.relations().get(unitId).operator())
        .isEqualTo(after.units().get(unitId).operator());
  }

  /**
   * ★★ **给了 {@code relation} 但 {@code operator} 与 unit 的不一致 ⇒ 命令边界拒**（计划 R3 的第 2 条： "谁经营"不许有两处拼写）。
   *
   * <p>★★ <b>为什么拒因里必须点名 {@code relation.operator}</b>（"拒了"这一条断言是不够的）：同一条事实有**两层** 守卫 —— 载荷边缘（{@code
   * EconomyPayloads}）与状态构造期（{@code EconomyData}）。删掉其中任一层，这个载荷 <b>照样</b>被拒（另一层接住）⇒
   * "抛了"这句话<b>分不出</b>是哪一层在守。故本用例钉<b>载荷边缘那一层</b>的消息 （它开头是 {@code relation.operator}，而状态层那句开头是 {@code
   * 关系的 operator}）。 ★★ 这不是推演：**变异体实测**（M4）发现——把载荷那层删掉后本用例曾<b>照样绿</b>（RED 缺席），补上这条判别子串后才当场红。 ★
   * 两层都在是<b>有意</b>的：载荷层给"写错就当场拒"的可读理由，状态层兜住一切别的写入口（命令、旧档、夹具）。
   *
   * <p>★ R3B.2 起一致性比较的另一端是 {@code ProductionProcess.operator}（不再是 {@code Industry.operator}，
   * 后者已退成纯模板的旧兼容位）。
   */
  @Test
  void rejectsARelationWhoseOperatorDisagreesWithTheUnit() {
    String payload =
        PAYLOAD.replace(
            "\"regime\":\"feudal\",",
            "\"regime\":\"feudal\",\"relation\":{\"operator\":{\"kind\":\"HOUSEHOLD\",\"id\":\"house-9\"},"
                + "\"rules\":[]},");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);

    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("★ 拒因点名两处不一致的 operator，且必须是**载荷边缘**那一条消息（见类注）")
        .contains("relation.operator")
        .contains("house-9");
  }

  /**
   * ★★ **显式给的 {@code relation} 逐值落盘**（不是被忽略、也不是被 regime 覆盖）。
   *
   * <p>★ 夹具是**非派生**值（550‰ + {@code middle_peasant} cohort + {@code priority 3}，与 {@code feudal} 档的
   * 推导值 5 条规则毫无共同之处）⇒ "读了没读这个键"与"一律重新推导"两种坏实现都会红。
   */
  @Test
  void anExplicitRelationIsSeededValueForValue() {
    String payload =
        PAYLOAD.replace(
            "\"regime\":\"feudal\",",
            "\"regime\":\"feudal\",\"relation\":{"
                + "\"operator\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"},"
                + "\"residualOwner\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"},"
                + "\"rules\":[{\"type\":\"OUTPUT_SHARE\","
                + "\"recipient\":{\"cohort\":\"0_0|rural|middle_peasant\"},"
                + "\"basis\":\"GROSS_OUTPUT\",\"ratePerMille\":550,\"fixedAmount\":0,"
                + "\"commodity\":\"grain\",\"priority\":3}]},");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);

    ProductionRules relation = apply(payload, EconomyData.empty(), T7).relations().get(FARM_UNIT);

    assertThat(relation)
        .as("★ 逐值落盘（含显式 operator / residualOwner / 一条 550‰ 的规则）")
        .isEqualTo(
            new ProductionRules(
                FARM_UNIT,
                ESTATE_FARM,
                null,
                List.of(
                    new CompensationRule(
                        RuleType.OUTPUT_SHARE,
                        new Payee.ToCohort(
                            new CohortKey(
                                new HexCoord(0, 0),
                                ResidenceKind.RURAL,
                                SocialClassId.MIDDLE_PEASANT)),
                        Pool.GROSS_OUTPUT,
                        Weight.NONE,
                        550,
                        0L,
                        Optional.of(GRAIN),
                        Optional.empty(),
                        3)),
                ESTATE_FARM));
    assertThat(relation)
        .as("★ 前置：夹具确实**不是** feudal 档的推导值（否则本用例测不出「读没读这个键」）")
        .isNotEqualTo(
            RegimeRelations.defaultRelation(
                new RegimeId("feudal"), FARM_UNIT, FARM, ESTATE_FARM, Set.of()));
  }

  /**
   * ★ 关系对象里的两个可选键：**缺 {@code operator} ⇒ 取 unit 的 operator**（于是两处必然一致）、 **缺 {@code residualOwner} ⇒
   * 取 operator**（自留是缺省）；{@code rules} 缺省 ⇒ 空表（全归 residualOwner，E9）。
   */
  @Test
  void relationOperatorAndResidualOwnerDefaultToTheUnitsOperator() {
    String payload =
        PAYLOAD.replace(
            "\"regime\":\"feudal\",", "\"regime\":\"feudal\",\"relation\":{\"rules\":[]},");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);

    ProductionRules relation = apply(payload, EconomyData.empty(), T7).relations().get(FARM_UNIT);

    assertThat(relation.operator())
        .as("缺 operator ⇒ unit 的 operator（feudal 的推导值）")
        .isEqualTo(ESTATE_FARM);
    assertThat(relation.residualOwner())
        .as("缺 residualOwner ⇒ operator（自留是缺省）")
        .isEqualTo(relation.operator());
    assertThat(relation.rules()).as("空 rules 合法（= 全部自留，E9 的等价路径）").isEmpty();
  }

  /** ★ 坏形状（{@code recipient} 两个变体都没给 / 都给了；{@code type} 不在词表）⇒ 命令边界拒，不静默兜底。 */
  @Test
  void rejectsARelationWhoseRuleShapeIsBroken() {
    String noRecipient =
        PAYLOAD.replace(
            "\"regime\":\"feudal\",",
            "\"regime\":\"feudal\",\"relation\":{\"rules\":[{\"type\":\"OUTPUT_SHARE\","
                + "\"basis\":\"GROSS_OUTPUT\",\"ratePerMille\":100,\"fixedAmount\":0,"
                + "\"priority\":1,\"commodity\":\"grain\"}]},");
    String bothRecipients =
        PAYLOAD.replace(
            "\"regime\":\"feudal\",",
            "\"regime\":\"feudal\",\"relation\":{\"rules\":[{\"type\":\"OUTPUT_SHARE\","
                + "\"recipient\":{\"cohort\":\"0_0|rural|landlord\",\"actor\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"}},"
                + "\"basis\":\"GROSS_OUTPUT\",\"ratePerMille\":100,\"fixedAmount\":0,"
                + "\"priority\":1,\"commodity\":\"grain\"}]},");
    String badType =
        PAYLOAD.replace(
            "\"regime\":\"feudal\",",
            "\"regime\":\"feudal\",\"relation\":{\"rules\":[{\"type\":\"SHARE\","
                + "\"recipient\":{\"cohort\":\"0_0|rural|landlord\"},\"basis\":\"GROSS_OUTPUT\","
                + "\"ratePerMille\":100,\"fixedAmount\":0,\"priority\":1,\"commodity\":\"grain\"}]},");
    assertThat(List.of(noRecipient, bothRecipients, badType))
        .as("三个替换必须都真的发生")
        .doesNotContain(PAYLOAD);

    assertThat(reasonOf(noRecipient)).as("recipient 一个都没给 ⇒ 拒").contains("recipient");
    assertThat(reasonOf(bothRecipients)).as("recipient 两个都给了 ⇒ 拒").contains("recipient");
    assertThat(reasonOf(badType))
        .as("词表外的类型 ⇒ 拒并列出合法值")
        .contains("未登记的规则类型")
        .contains("OUTPUT_SHARE");
  }

  private static String reasonOf(String payload) {
    HandlerOutcome outcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);
    assertThat(outcome).as("坏关系载荷必须在命令边界被拒（不是抛到边界之外）").isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
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
