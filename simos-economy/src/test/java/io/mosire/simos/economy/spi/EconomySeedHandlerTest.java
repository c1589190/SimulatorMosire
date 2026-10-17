package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.asset.AssetRightKind;
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
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.spi.ProductionSeedHandler;
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
          // ★★ D-032 B5（§13.15.3 裁定十九）：economy 载荷的 `entries[].industries[]` 键**已退役**
          //   （定义权威在 production 的 `production.Seed industries[]`）⇒ 本夹具改用新键 `plans[]`
          //   （节点只承载"该格有哪些方案"与 `relation` 来源；定义字段留在节点里不解析，供 `seededDefinitions`
          //   原样转交给 production.Seed —— 两处夹具同源，避免"两份定义漂移"）。
          + "\"plans\":[{\"id\":\"farm@0_0\",\"name\":\"农业\",\"regime\":\"feudal\","
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
  /**
   * ★★ <b>D-032 T5（夹具换源）：{@code units[]} 段</b> —— 实例/关系的宿主。
   *
   * <p>economy 载荷只声明"这一格有哪些 unit（{@code industry/operator/modeKey}）与各自的 {@code relation}"， 实例本体仍由
   * {@code production.Seed} 的 {@code instances[]} 落账，两处必须**逐值一致**才收下 （{@code EconomyPayloads} 的
   * authoritative 核对）。★ 旧形状（{@code entries[].industries[]} 自带实例字段 ⇒ 合成 unit）已按 B5 裁定十九整体退役。
   */
  private static final String UNITS_SEGMENT =
      "\"units\":[{\"industry\":\"farm@0_0\","
          + "\"operator\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"}}],";

  /**
   * 租佃档（{@code tenant}）的 unit 形态：{@code operator} 显式给成该档的**推导经营者** （{@code RegimeOperators}：tenant
   * ⇒ {@code HOUSEHOLD:farm@0_0}）—— 见 {@link
   * #aPayloadWithoutRelationDerivesTheDefaultFromItsRegime}（那里要逐值比对该推导值）。
   */
  private static final String TENANT_UNITS_SEGMENT =
      "\"units\":[{\"industry\":\"farm@0_0\","
          + "\"operator\":{\"kind\":\"HOUSEHOLD\",\"id\":\"farm@0_0\"}}],";

  /** 在 entry 级插入 {@link #UNITS_SEGMENT}（放在 {@code classes} 之前，保序）。 */
  private static String withUnits(String payload) {
    return payload.replace("\"classes\":[", UNITS_SEGMENT + "\"classes\":[");
  }

  /**
   * ★★ <b>带劳动表的同一份载荷</b>（D-032 T5 换源后的形状）。
   *
   * <p>三处与旧稿不同，逐条给依据（都不是放宽断言）：
   *
   * <ol>
   *   <li>{@code units[]} 段：实例/关系的宿主（旧稿的 {@code industries[]} 旧形状字段已不解析）；
   *   <li>每条配额**显式给 {@code household}**：B2c（§13.12.5）起该键必填，缺键当场具名拒（旧档 pending 占位
   *       与"按人口自动拆成两条"的迁移器都已退役 —— Alpha 不兼容旧档）；
   *   <li>两条配额按两行人口 450 : 50 分摊 250,000 ⇒ **225,000 / 25,000**（原先是迁移器算出来的，现在是载荷 显式声明的值）。id 用规范拼写
   *       {@code alloc-<unit 段>-<批次>-<家户>}（{@code HouseholdLaborCommitment.idOf}）。
   * </ol>
   */
  private static final String PAYLOAD_WITH_LABOR =
      withUnits(PAYLOAD)
          .replace(
              "\"participationPerMille\":100}]}]}",
              "\"participationPerMille\":100}],"
                  + "\"laborSupply\":[{\"group\":\"rural:0_0:MALE:1\",\"period\":1,"
                  + "\"grossLaborMilli\":290000,\"servedLaborMilli\":0,\"committedLaborMilli\":0}],"
                  + "\"allocations\":["
                  + "{\"id\":\"alloc-farm@0_0-rural:0_0:MALE:1-hh-0_0-rural-poor_peasant\","
                  + "\"group\":\"rural:0_0:MALE:1\",\"household\":\"hh-0_0-rural-poor_peasant\","
                  + "\"actor\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"},"
                  + "\"activity\":\"farm\",\"laborMilli\":225000,\"period\":1},"
                  + "{\"id\":\"alloc-farm@0_0-rural:0_0:MALE:1-hh-0_0-rural-landlord\","
                  + "\"group\":\"rural:0_0:MALE:1\",\"household\":\"hh-0_0-rural-landlord\","
                  + "\"actor\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"},"
                  + "\"activity\":\"farm\",\"laborMilli\":25000,\"period\":1}]}]}");

  /**
   * ★★ `WageFirst`（资本主义工业）在 v1 没有结算实现 ⇒ 必须**播种期**拒（v2 spec §八.4）。
   *
   * <p>判别力：v1 允许它入库，直到某个收获日才炸（T4 之前是 `EconomySettlement.harvest` 抛
   * `UnsupportedOperationException`）—— 那个异常穿出协调器的 `simulateWorld`， 让整条 `AdvanceTime` revision
   * 失败（既不是 `Rejected` 也不是降级）。★ T4 起 `harvest` 不再读 `AllocationRule` ⇒ 本守卫是这条口径**唯一**的落点。
   *
   * <p>★ 为什么拒在**载荷**这一层而不是 `IndustryDefinitionView` 构造期：构造期拒会让 `WageFirst` 这个 **状态形状**（spec §五
   * 的第四种制度）不可表达，连带 `EconomyCodecTest` 的 `wage_first` 多态往返夹具无法构造 ⇒ 丢一条 JSON 分支的覆盖。播种期拒已堵住命令路径，且不砍覆盖。
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
    // ★★★ D-032 T1：判据**换源**（§14.3 第 3 条）—— `allocation` 的解析点随产业定义一起迁到
    //   `production.Seed industries[]`（economy 载荷不再自带产业定义，B5 裁定十九）⇒ 断言点从
    //   `EconomyPayloads.toData` 改为 `production.Seed`；断言的仍是"v1 不支持的分配函数在**播种期**拒"。
    HandlerOutcome outcome =
        PRODUCTION_SEED.handle(state(EconomyData.empty(), T7), seedPayloadOf(payload));
    assertThat(outcome)
        .as("v1 不支持的分配函数必须在播种期拒（v2 spec §八.4）")
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("Split");
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
    // ★★ D-032 T5（a 桶换源）：配额**逐家户显式声明**（{@code household} 必填，B2c §13.12.5）。
    //   旧稿断言的是"缺 household ⇒ S1 迁移按两行人口自动拆成两条"——那个迁移器已按 Alpha 不兼容旧档整段退役
    //   ⇒ 拆分的**数值事实**（450:50 ⇒ 225,000/25,000）改由载荷显式声明后逐值断言；退役掉的那半边换成下面
    //   第一条负向判据（缺键 ⇒ 具名拒），判据没有丢。
    EconomyData after = apply(PAYLOAD_WITH_LABOR, EconomyData.empty(), T7);

    PeopleLotId lot = new PeopleLotId("rural:0_0:MALE:1");
    // ★ P2-A A4：LaborSupply（每批次供给表）已删除 —— 劳动预算的唯一权威是 HouseholdEconomy.laborMilli
    //   （每 tick 由 Social 家户成员重算）；本用例保留配额那半边的逐值断言。
    assertThat(after.classes()).as("创世仍落阶层行（家户时间预算的载体）").isNotEmpty();
    assertThat(after.allocations()).as("载荷逐家户声明两条配额（450:50 分摊 250,000）").hasSize(2);
    HouseholdLaborCommitment peasant =
        after.allocations().get(HouseholdLaborCommitment.idOf(FARM, lot, PEASANT_HOUSEHOLD));
    assertThat(peasant).as("id 的唯一拼写点 = alloc-<产业>-<批次>-<家户>").isNotNull();
    assertThat(peasant.household()).as("家户是迁移按格与居住类型定位出来的真实家户").isEqualTo(PEASANT_HOUSEHOLD);
    assertThat(peasant.group()).isEqualTo(lot);
    assertThat(peasant.actor()).isEqualTo(ESTATE_FARM);
    assertThat(peasant.activity())
        .as("R3B.2：activity 对齐到 unit id（不再喂 farm 这个旧标签）")
        .isEqualTo(FARM_UNIT.value());
    assertThat(peasant.laborMilli())
        .as("250,000 × 450 ÷ 500 = 225,000（载荷显式声明）")
        .isEqualTo(225_000L);
    assertThat(peasant.period()).isEqualTo(1L);

    HouseholdLaborCommitment landlord =
        after.allocations().get(HouseholdLaborCommitment.idOf(FARM, lot, LANDLORD_HOUSEHOLD));
    assertThat(landlord).as("第二条拆给地主行").isNotNull();
    assertThat(landlord.household()).isEqualTo(LANDLORD_HOUSEHOLD);
    assertThat(landlord.actor()).isEqualTo(ESTATE_FARM);
    assertThat(landlord.activity()).isEqualTo(FARM_UNIT.value());
    assertThat(landlord.laborMilli()).as("250,000 × 50 ÷ 500 = 25,000（载荷显式声明）").isEqualTo(25_000L);
    assertThat(landlord.period()).isEqualTo(1L);

    // ★★ 负向（退役能力的替代判据）：配额缺 household ⇒ **命令边界具名拒**，不再造 legacy-pending 占位。
    String withoutHousehold =
        PAYLOAD_WITH_LABOR.replace("\"household\":\"hh-0_0-rural-poor_peasant\",", "");
    assertThat(withoutHousehold).as("替换必须真的发生").isNotEqualTo(PAYLOAD_WITH_LABOR);
    HandlerOutcome missing =
        HANDLER.handle(
            state(EconomyData.empty(), T7, seededDefinitions(PAYLOAD_WITH_LABOR)),
            withoutHousehold);
    assertThat(missing).as("缺必填键 ⇒ Rejected（不是静默补占位）").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) missing).reason())
        .contains("缺少必填字段 household")
        .contains("alloc-farm@0_0-rural:0_0:MALE:1-hh-0_0-rural-poor_peasant");
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

    assertThat(seededDefinitions(PAYLOAD).industries())
        .as("产业照旧落盘（定义由 production.Seed 落账）")
        .hasSize(1);
    assertThat(after.allocations()).isEmpty();
  }

  /** ★ **配额之和超过可用劳动 ⇒ 命令边界拒**（"同一批人的劳动不得被两个产业各算一次满额"）。 */
  @Test
  void rejectsAQuotaThatExceedsTheAvailableLabor() {
    // ★ D-032 T5：夹具换成显式两行后，把**第一条**抬到 290,001 ⇒ 承诺合计 315,001 > 家户时间预算 290,000。
    String payload = PAYLOAD_WITH_LABOR.replace("\"laborMilli\":225000", "\"laborMilli\":290001");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD_WITH_LABOR);

    // ★ D-032 T5：载荷现在带 units[]（实例/relation 的宿主）⇒ 装配必须显式注入 production 切片（B5 裁定十九）。
    HandlerOutcome outcome =
        HANDLER.handle(state(EconomyData.empty(), T7, seededDefinitions(payload)), payload);

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
   * ★★ <b>载荷里的悬空引用必须留在命令边界</b>：配额 {@code activity} 看起来是 unit id、但该 unit 不在载荷里 ⇒
   * 命令边界**具名拒**，不得把异常穿出边界、也不得静默收下（收下 ⇒ 运行期"喂给一个不存在的生产"）。
   *
   * <p>★★ <b>D-032 T5 换源（逐条依据，不是放宽）</b>：旧稿测的是"旧形状载荷（缺 {@code household}/{@code memberships}）触发
   * {@code LegacyHouseholdMigration}，其失败折成 {@code Rejected}"。该迁移器已按 **Alpha 不兼容旧档** 整体退役（{@code
   * simos-economy/.../migrate/} 下只剩 {@code DebtReferenceReconciler} / {@code LegacyClassStructure}
   * / {@code ProductionRoleResolver}），旧稿断言的 reason 原文 {@code "旧档迁移失败"} 在 {@code src/main}
   * 里**零命中**。⇒ 判据换成同一诉求（"载荷引用错误 ⇒ 命令边界具名拒"） 在当前形状里的落点：{@code
   * EconomyPayloads.canonicalAllocationActivity} 的悬空 unit 分支 （{@code "劳动配额的 activity 看起来是 unit id
   * 但该 unit 不在载荷里"}）。
   */
  @Test
  void anAllocationNamingAMissingUnitIsRejectedInsteadOfLeaking() {
    String payload =
        PAYLOAD_WITH_LABOR.replace(
            "\"activity\":\"farm\",\"laborMilli\":225000",
            "\"activity\":\"unit-farm@9_9-ORGANIZATION-farm@9_9\",\"laborMilli\":225000");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD_WITH_LABOR);

    HandlerOutcome outcome =
        HANDLER.handle(
            state(EconomyData.empty(), T7, seededDefinitions(PAYLOAD_WITH_LABOR)), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("拒因是悬空 unit 引用的具名理由（而不是把异常穿出命令边界）")
        .contains("该 unit 不在载荷里")
        .contains("unit-farm@9_9-ORGANIZATION-farm@9_9");
  }

  /**
   * ★ 追加第二国时，两张新表**按格一并追加**（与产业/阶层行同一套判重口径）。
   *
   * <p>★ S1 起每国的旧配额会被自动迁移按本格两行人口拆成 2 条 ⇒ 两国合计 4 条；供给仍是每国 1 条。
   */
  @Test
  void appendsTheLaterNationsLaborTables() {
    EconomyData first = apply(PAYLOAD_WITH_LABOR, EconomyData.empty(), T7);
    // ★ D-032 T5：配额已逐家户显式声明 ⇒ 换格时**家户段也要一并换**（否则第二条国的配额会挂到第一国的家户上，
    //   四次追加就变成两次覆盖）。
    String later =
        PAYLOAD_WITH_LABOR
            .replace("\"q\":0,\"r\":0", "\"q\":1,\"r\":0")
            .replace("farm@0_0", "farm@1_0")
            .replace("rural:0_0:MALE:1", "rural:1_0:MALE:1")
            .replace("hh-0_0-", "hh-1_0-")
            .replace("alloc-farm@0_0-", "alloc-farm@1_0-");

    EconomyData both = apply(later, first, SimosTimestamp.of(9));

    assertThat(both.allocations()).as("每国 2 条（载荷逐家户声明）⇒ 两国 4 条").hasSize(4);
    assertThat(seededDefinitions(PAYLOAD_WITH_LABOR, later).industries()).hasSize(2);
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
    // ★★ D-032 T5（a 桶换源）：旧形状的 `plans[].capacity` ⇒ 整额 OWNED 份额的**合成路径已按 B5 裁定十九删除**
    //   ⇒ 实物份额改由 entry 级 `assetShares[]` **显式声明**（新键；旧键 useRights 走一对一代际翻译）。
    //   量到的仍是同一件事：本格该产业 1,000,000 千分亩 LAND（旧稿是 900,000 + 100,000 两行相加的合成结果）。
    String payload =
        PAYLOAD.replace(
            "\"classes\":[",
            "\"assetShares\":[{\"industry\":\"farm@0_0\","
                + "\"owner\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"},"
                + "\"operator\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"},"
                + "\"asset\":\"LAND\",\"quantity\":1000000,\"kind\":\"OWNED\"}],"
                + "\"classes\":[");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    EconomyData after = apply(payload, EconomyData.empty(), T7);

    EconomyMeta meta = after.meta().orElseThrow();
    assertThat(meta.mapId()).isEqualTo("Map1");
    assertThat(meta.activatedDay()).as("激活日 = 世界当前 tick").isEqualTo(7L);
    assertThat(meta.lastClosedCycle()).isEqualTo(OptionalLong.empty());
    // ★★ P2-A 口径迁移：旧档迁移的触发条件不再包含 memberships（该组件已删）——本载荷没有待迁移配额，
    //   故 meta 保持载荷原值、不打迁移落款。旧断言（自动升到 pre-modern-v1）已随该触发条件退役。
    assertThat(meta.rulesVersion()).as("无待迁移配额 ⇒ meta 原样").isEqualTo("aggregate-v1");
    assertThat(meta.migrationSource()).isEmpty();

    ProductionData definitions = seededDefinitions(payload);
    IndustryDefinitionView industry = definitions.industries().get(FARM);
    assertThat(definitions.industries()).hasSize(1);
    assertThat(industry.name()).isEqualTo("农业");
    assertThat(industry.regime().value()).isEqualTo("feudal");
    // ★★★ D-032 T1（测试 owner）：本区原有三条读数 "旧载荷的 operator/capacity 兼容位 ⇒ 合成一个默认 unit /
    //   unit.operator = regime 推导值" 已**删除**。依据（非"为了绿"）：B5（§13.15.3 裁定十九）把
    //   "economy 载荷自带产业定义 + 旧形状实例字段 ⇒ 合成 unit" 这一整条路径**整体退役**
    //   （`EconomyPayloads.java:119-133 / 366-374 / 427-433`；旧形状实例字段不再解析）；
    //   实例的唯一来源是 `production.Seed` 的 `instances[]`（`ProductionSeedHandler.java:91-100`）。
    //   ⇒ "economy 载荷不再产出实例"本身就是新判据：本处正面断言它（而不是留一条恒真的旧断言）。
    assertThat(seededDefinitions(payload).instances())
        .as("★ 新判据：economy 载荷（无 units[] 段）不再合成任何生产实例 —— 实例权威在 production.Seed")
        .isEmpty();
    assertThat(industry.cycleDays()).isEqualTo(120L);
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
    //   IndustryDefinitionView.capacity；B.2 起 IndustryDefinitionView.capacity 也只是旧档兼容位（生产模型不再读它）⇒
    // **实物产能的唯一真源是
    //   OwnershipStake 的 quantity**。出处：EconomyData 构造期注释（约 582-604 行"OwnershipStake 是独立的实物资产总账"）
    //   与 ProductionProcess 类注"OwnershipStake —— 实物总账：唯一的 quantity 真相"。
    //   量到的仍是同一件事（两行的 900,000 + 100,000 = 1,000,000 千分亩进来了），只是落点又换了一次。
    OwnershipStake land =
        after.assetShares().values().stream()
            .filter(share -> share.industry().equals(FARM) && share.asset() == AssetKind.LAND)
            .findFirst()
            .orElseThrow();
    assertThat(land.quantity()).as("载荷显式声明 1,000,000 千分亩 LAND").isEqualTo(1_000_000L);
    assertThat(land.owner()).as("份额的 owner 逐值落盘").isEqualTo(ESTATE_FARM);
    assertThat(land.operator()).isEqualTo(ESTATE_FARM);
    assertThat(land.kind()).isEqualTo(AssetRightKind.OWNED);
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

  // ★★★ D-032 T1（测试 owner）：本区原有一条 `seedsAnExplicitOperatorValueForValue`
  //   （"载荷显式给 operator ⇒ 逐值落盘，且不等于 regime 推导值"）已**整方法删除**。依据（非"为了绿"）：
  //   ① 它的落点 `EconomyData.units()` 已随迁移删除（I1：实例权威在 production）；
  //   ② 更根本的是 B5（§13.15.3 裁定十九）：economy 载荷的产业节点**不再解析** `operator` 这个旧实例字段
  //      （`EconomyPayloads.java:119-133` 明写"旧形状实例字段不再解析"）⇒ "economy 载荷的显式 operator"
  //      这个被测能力**在新形状里不存在**；显式 operator 现在的入口是 `production.Seed` 的 `instances[]`
  //      （`ProductionSeedHandler.java:123-140` 逐项解析 operator）。
  //   ③ ⇒ 该判据已在 production 侧**重钉**（T2 交付：`ProductionSeedInstancesTest`，用非默认 operator 的
  //      `instances[]` 载荷 + "不等于 regime 推导值"的判别力）。若 T2 未交付，本文件必须在缺陷/未做清单里
  //      具名列出这条覆盖缺口。

  /** ★ 已激活后**按格追加**：同一库连播两国，两批的格都在、人口/库存合计 = 两批之和，且 meta 不覆盖。 */
  @Test
  void appendsNewHexesOfALaterNation() {
    EconomyData first = apply(PAYLOAD, EconomyData.empty(), T7);

    EconomyData both = apply(LATER_NATION_PAYLOAD, first, SimosTimestamp.of(9));

    assertThat(seededDefinitions(PAYLOAD, LATER_NATION_PAYLOAD).industries())
        .as("两批的产业都在")
        .containsKeys(FARM, FARM2);
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
   * ★★ **R4-B.4：{@code view} 不再受 {@code IndustryDefinitionView.slots} 约束**（旧守卫已被生产代码删除）—— 本用例从
   * "悬空槽位 ⇒ 拒"**改判**为"照常落盘、并以稳定家户身份可寻址"。
   *
   * <p>出处（逐字）：{@code EconomyData} 构造期注释"R4-B.4（R3 决策单 §0.1/§1.5，R3B.4）：**view 不再受
   * IndustryDefinitionView.slots 约束**。旧守卫（view 必须命中该格产业的 slots 且 participationPerMille ≤ 该 slot 的
   * laborParticipationPerMille）已删除；IndustryDefinitionView.slots / ClassSlot 只作为生产方式内部的角色/劳动配置。
   * HouseholdEconomy 自己的 [0,1000] 参与率守卫仍在"。
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

    // ★★★ D-032 T5：**两条腿都要**（旧稿只断 production.Seed 那条，而 `PAYLOAD.replace("\"slot\":\"landlord\"")`
    //   改的是 entry 级 `classes[].slot` —— 那个字段根本不在 `plans[]` 里 ⇒ 断言的载荷与被改的字段错位，
    //   production.Seed 当然收下（这是夹具错位，不是判据失效）。
    //   ① economy 侧：`classes[].slot` 仍由 economy.Seed 解析（`EconomyPayloads` 走 `SocialClassId.parse`）
    //      ⇒ 词表外当场具名拒并列出合法值；
    HandlerOutcome economyOutcome = HANDLER.handle(state(EconomyData.empty(), T7), payload);
    assertThat(economyOutcome).as("词表外的阶层必须当场拒").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) economyOutcome).reason())
        .contains("ghost")
        .contains("poor_peasant")
        .contains("landlord");
    //   ② production 侧：`plans[].slots[].id` 随定义迁到 `production.Seed industries[]`（B5 裁定十九）
    //      ⇒ 同一条词表守卫在那边也要成立（改的是定义节点里的 slots 元素，不是 classes 行）。
    String ghostSlotInDefinition =
        PAYLOAD.replace(
            "{\"id\":\"landlord\",\"name\":\"地主\"", "{\"id\":\"ghost\",\"name\":\"地主\"");
    assertThat(ghostSlotInDefinition).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    HandlerOutcome outcome =
        PRODUCTION_SEED.handle(
            state(EconomyData.empty(), T7), seedPayloadOf(ghostSlotInDefinition));
    assertThat(outcome).as("定义里的词表外阶层也必须当场拒").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .contains("ghost")
        .contains("poor_peasant")
        .contains("landlord");
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
            + "{\"q\":1,\"r\":2,\"plans\":[]},{\"q\":-3,\"r\":4,\"plans\":[]}]}";

    assertThat(HANDLER.targetPaths("Map1", twoEntries)).containsExactly("1_2", "-3_4");
  }

  // ── 一次性投入槽与投入累加器（v2 spec §3.3；R3B.2 起累加器住 unit）────────────────────

  /** ★★ 缺键 ⇒ 空 map（旧载荷兼容：命令路径不许因为多了一个字段就把老生成器挡在门外）。 */
  @Test
  void legacyPayloadWithoutCycleInputStillSeedsEmptyAccumulator() {
    EconomyData after = apply(PAYLOAD, EconomyData.empty(), T7);

    IndustryDefinitionView industry = seededDefinitions(PAYLOAD).industries().get(FARM);
    assertThat(industry.cycleInputPerUnit()).as("旧载荷没提一次性投入 ⇒ 空 map（不是 null、不拒）").isEmpty();
    // ★★★ D-032 T1：原断言 "after.units().get(FARM_UNIT).cycleInputUsedMilli() 为空" 已删除 ——
    //   ① `EconomyData.units()` 已随迁移删除；② 更重要的是 economy 载荷的产业节点**不再解析**旧实例字段
    //   `cycleInputUsedMilli`（B5 裁定十九 / `EconomyPayloads.java:119-133`）⇒ 该断言在新形状下没有主语。
    //   累加器的真正归属是 production 实例（`ProductionProcess.cycleInputUsedMilli`，由 `production.Seed`
    //   的 `instances[]` 落账）—— 见 T2 的 production 侧实例播种用例。
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
    IndustryDefinitionView industry = seededDefinitions(payload).industries().get(FARM);

    assertThat(industry.cycleInputPerUnit())
        .as("每种生产资料一路，值为**商品表**（R3 换型的那一维）")
        .containsOnlyKeys(AssetKind.LAND, AssetKind.CATTLE);
    assertThat(industry.cycleInputPerUnit().get(AssetKind.LAND))
        .as("同一种生产资料下可以挂多个商品")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 1200L, WOOD_COMMODITY, 3L));
    assertThat(industry.inputPerUnit())
        .as("★ 每 1 单位规模的投入 = 各路的合计（派生视图）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 1201L, WOOD_COMMODITY, 3L));
    // ★★★ D-032 T1：原断言 "after.units().get(FARM_UNIT).cycleInputUsedMilli() == {grain:5000,fiber:7}"
    //   已删除（同上一条：`EconomyData.units()` 与载荷侧的旧实例字段解析都已退役）。
    //   ★ 上面**保留**的两条（`industry.cycleInputPerUnit()` 的商品维度 + 派生 `inputPerUnit()` 合计）
    //   就是本用例真正的判别力所在（"值侧退回标量 ⇒ 载荷解析当场拒"那条变异仍然生效）。
  }

  /**
   * ★ 逐值校验：负的一次性投入 ⇒ 拒（{@code IndustryDefinitionView} 的构造期守卫，经 handler 的 catch 折成 Rejected）。
   *
   * <p>★★ <b>D-032 T5 换源（同 wageFirst 那条的处置）</b>：{@code cycleInputPerUnit} 是**定义字段**，解析点已随产业定义 迁到
   * {@code production.Seed} 的 {@code industries[]}（B5 裁定十九）—— economy 载荷的节点同名字段**不再解析** （解析它 =
   * 留第二份定义权威）。⇒ 断言点改为 {@code PRODUCTION_SEED} 的具名拒；断言的仍是"负投入当场拒、理由点名该字段"。
   */
  @Test
  void rejectsNegativeCycleInput() {
    String payload =
        PAYLOAD.replace(
            "\"dailyInputPerUnit\":{}",
            "\"dailyInputPerUnit\":{},\"cycleInputPerUnit\":{\"LAND\":{\"grain\":-1}}");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);

    HandlerOutcome outcome =
        PRODUCTION_SEED.handle(state(EconomyData.empty(), T7), seedPayloadOf(payload));

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
    // ★ D-032 T5：unit 的 operator 显式给成 tenant 档的**推导经营者** HOUSEHOLD:farm@0_0
    //   （实例的 operator 现在由 production 落账、economy 侧不再持有实例，故这里必须显式对齐两处拼写）。
    String payload =
        PAYLOAD_WITH_LABOR
            .replace("\"regime\":\"feudal\"", "\"regime\":\"tenant\"")
            .replace(UNITS_SEGMENT, TENANT_UNITS_SEGMENT);
    assertThat(payload).as("替换必须真的发生（否则本用例测的是 feudal 那条路）").isNotEqualTo(PAYLOAD);

    // ★★ D-032 B5：economy 载荷不再自带定义/实例 ⇒ 装配显式注入 production 切片；本用例把它取到手上，
    //   好让"关系里的 operator == 实例的 operator"这条跨表判据仍能逐值比较（实例侧读数在那里）。
    ProductionData production = seededDefinitions(payload);
    EconomyData after = apply(payload, EconomyData.empty(), T7, production);

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
    // ★★ D-032 T1：跨表一致性的另一端（unit.operator）**已不在 economy** ⇒ 读数换成注入的 production 当刻投影
    //   （`apply(...)` 用的就是同一份 `seededDefinitions(payload)`；这里显式取同一份，判据仍是"两处拼写同值"）。
    assertThat(after.relations().get(unitId).operator())
        .as("关系里的 operator 与 production 投影里对应实例的 operator 逐值一致")
        .isEqualTo(production.instances().get(unitId).operator());
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
   * <p>★ R3B.2 起一致性比较的另一端是 {@code ProductionProcess.operator}（不再是 {@code
   * IndustryDefinitionView.operator}， 后者已退成纯模板的旧兼容位）。
   */
  @Test
  void rejectsARelationWhoseOperatorDisagreesWithTheUnit() {
    String payload =
        withUnits(PAYLOAD)
            .replace(
                "\"regime\":\"feudal\",",
                "\"regime\":\"feudal\",\"relation\":{\"operator\":{\"kind\":\"HOUSEHOLD\",\"id\":\"house-9\"},"
                    + "\"rules\":[]},");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);

    HandlerOutcome outcome =
        HANDLER.handle(state(EconomyData.empty(), T7, seededDefinitions(payload)), payload);

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
        withUnits(PAYLOAD)
            .replace(
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
        withUnits(PAYLOAD)
            .replace(
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
        withUnits(PAYLOAD)
            .replace(
                "\"regime\":\"feudal\",",
                "\"regime\":\"feudal\",\"relation\":{\"rules\":[{\"type\":\"OUTPUT_SHARE\","
                    + "\"basis\":\"GROSS_OUTPUT\",\"ratePerMille\":100,\"fixedAmount\":0,"
                    + "\"priority\":1,\"commodity\":\"grain\"}]},");
    String bothRecipients =
        withUnits(PAYLOAD)
            .replace(
                "\"regime\":\"feudal\",",
                "\"regime\":\"feudal\",\"relation\":{\"rules\":[{\"type\":\"OUTPUT_SHARE\","
                    + "\"recipient\":{\"cohort\":\"0_0|rural|landlord\",\"actor\":{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\"}},"
                    + "\"basis\":\"GROSS_OUTPUT\",\"ratePerMille\":100,\"fixedAmount\":0,"
                    + "\"priority\":1,\"commodity\":\"grain\"}]},");
    String badType =
        withUnits(PAYLOAD)
            .replace(
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
    // ★ D-032 T5：载荷带 units[] ⇒ 必须注入 production 切片（否则先撞"unit 指名的产业不在权威里"这条装配性拒）。
    HandlerOutcome outcome =
        HANDLER.handle(state(EconomyData.empty(), T7, seededDefinitions(payload)), payload);
    assertThat(outcome).as("坏关系载荷必须在命令边界被拒（不是抛到边界之外）").isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static EconomyData apply(String payload, EconomyData base, SimosTimestamp at) {
    // ★★ D-032 B5：economy.Seed 的定义权威来自 production ⇒ 本夹具把同一份载荷的 `plans[]` 节点先交给
    //   `production.Seed` 落定义，再拿它当 economy.Seed 的 production 切片（两处同源，定义只有一份真相）。
    return apply(payload, base, at, seededDefinitions(payload));
  }

  private static EconomyData apply(
      String payload, EconomyData base, SimosTimestamp at, ProductionData production) {
    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied) HANDLER.handle(state(base, at, production), payload);
    return EconomyChangeSet.apply((EconomyChangeSet) applied.changeSet(), base);
  }

  private static SimulationState state(EconomyData data, SimosTimestamp timestamp) {
    return state(data, timestamp, ProductionData.empty());
  }

  /**
   * ★★ D-032 B5（§13.15.3 裁定十九）：economy 载荷**不再自带产业定义与实例** ⇒ 每次装配都必须显式注入 production
   * 当刻切片（定义权威给产业模板、实例投影给 {@code units[]} 的引用校验）。缺切片 = **装配故障**（不是拒绝路径）。
   */
  private static SimulationState state(
      EconomyData data, SimosTimestamp timestamp, ProductionData production) {
    return new SimulationState(
        new StateMeta(REF, timestamp),
        Map.of(
            "economy", new EconomySnapshot(REF, timestamp, data),
            "production", new ProductionSnapshot(REF, timestamp, production)),
        InMemoryInfoSystem.empty());
  }

  private static final ProductionSeedHandler PRODUCTION_SEED = new ProductionSeedHandler();

  /**
   * ★ P1：定义权威迁入 production ⇒ 把同一份 {@code entries[].industries} 载荷交给 {@code production.Seed}
   * 落定义，再断言定义字段（既有定义覆盖不再从 economy 状态读）。
   */
  private static JsonNode parseJson(String json) {
    try {
      return SimosObjectMapper.create().readTree(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * ★★ D-032 B5：economy 夹具载荷的第一格 `plans[]` 节点 ⇒ {@code production.Seed} 的 `industries[]` 载荷
   * （两处夹具**同源** ⇒ 定义只有一份真相，不会漂移）。
   */
  private static String seedPayloadOf(String payloadJson) {
    JsonNode entry = parseJson(payloadJson).path("entries").path(0);
    ObjectNode seed = SimosObjectMapper.create().createObjectNode();
    seed.set("industries", entry.path("plans"));
    // ★★ D-032 T5：economy 载荷的 `units[]` ⇒ `production.Seed` 的 `instances[]`（两处夹具同源：实例本体只有
    //   production 一份真相，economy 侧那份必须与它逐值一致才被收下）。
    ArrayNode instances = seed.putArray("instances");
    for (JsonNode unit : entry.path("units")) {
      IndustryId industry = IndustryId.parse(unit.path("industry").asText());
      JsonNode operatorNode = unit.path("operator");
      ActorRef operator =
          new ActorRef(
              ActorKind.parse(operatorNode.path("kind").asText()),
              operatorNode.path("id").asText());
      ObjectNode instance = instances.addObject();
      instance.put(
          "id",
          unit.hasNonNull("id")
              ? unit.get("id").asText()
              : ProductionUnitId.idOf(industry, operator).value());
      instance.put("plan", industry.value());
      ObjectNode operatorOut = instance.putObject("operator");
      operatorOut.put("kind", operator.kind().name());
      operatorOut.put("id", operator.id());
      instance.put(
          "modeKey", unit.hasNonNull("modeKey") ? unit.get("modeKey").asText() : industry.value());
      instance.put("progressDays", unit.path("progressDays").asLong(0L));
    }
    return seed.toString();
  }

  private static ProductionData seededDefinitions(String... payloadJsons) {
    ProductionData data = ProductionData.empty();
    for (String payloadJson : payloadJsons) {
      ObjectNode seed = (ObjectNode) parseJson(seedPayloadOf(payloadJson));
      SimulationState state =
          new SimulationState(
              new StateMeta(REF, T7),
              Map.of("production", new ProductionSnapshot(REF, T7, data)),
              InMemoryInfoSystem.empty());
      HandlerOutcome.Applied applied =
          (HandlerOutcome.Applied) PRODUCTION_SEED.handle(state, seed.toString());
      data = ProductionChangeSet.apply((ProductionChangeSet) applied.changeSet(), data);
    }
    return data;
  }
}
