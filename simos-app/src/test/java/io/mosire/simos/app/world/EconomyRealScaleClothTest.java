package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.time.OwnershipBooks;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.spi.EconomySeedHandler;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ **R3（T4/T5）：城乡各有一个非土地生产，且它们在**真档量级**上真的产出东西**。
 *
 * <p>★★ **为什么必须在真档量级上验**（与 {@code EconomyRealScaleSeedBottleneckTest} 同一条理由）：5 格夹具是 1,000 人/格的，
 * 而**织机数、作坊数、纤维量**都是按人口/亩数派生的（{@code EconomySeeder} 的场景参数）⇒ 小夹具上的数字证明不了真档的任何事。
 * 本文件用**真播种器载荷**（{@link EconomySeeder#payload} → 真 {@link EconomySeedHandler}）造一格 14,806 人的平原格 +
 * 一座城， 再跑真结算**一年**（360 天 = 3 个周期）。
 *
 * <pre>
 * 农村那一池（14,806 人）+ 一座城（1,777 人）在本格产生 farm/weave/craft 三个产业。
 * R4-B3a 起每个产业拆成主 unit + 家户副 unit；本夹具实测：
 *   纺织产能 740 台（AssetShare TOOL），初始纤维 20,700,000（农村 18,600,000 + 城镇 2,100,000）
 *   第 1 周期 H6 争用配给：织机 18,911,111、作坊 1,788,889（Σ == 池）
 *   织机规模 = min(织机 740, 劳动 629, 纤维 630) = 629 ⇒ 第 1 周期布净产 18,303,900
 *   第 1 周期工具争用配给为 0 ⇒ 城市作坊从第 2 周期起开产；工具账 70,000 → 121,250 → 192,500
 *   一年后布库存 37,429,869；家户布 23,082,749；家户工具 0
 * </pre>
 *
 * <p>★★ <b>历史留痕（R3 → R4）："一年只跑得起一个周期"已被 R4 取代</b> —— 旧单 unit 口径下"把农田纤维搬到织机"是跨行实物转移的活， 故第 2 周期起停工；R4
 * 起农田纤维经关系规则回到本格家户账、H6 池按需求比例逐周期配给，纺织与作坊**逐周期继续**。 旧数字（18,042,000 / 20,079,000 /
 * 169,750）不再作为当前读数，见各用例里的逐值重钉。
 *
 * <p>★ **"最紧约束"在真档上真的被走到**：R4-B3a 拆分后纺织三路为 织机 740、劳动 629、纤维 630 ⇒ 实际规模取劳动那一路； 把产能写成"土地"⇒ 规模 0 ⇒ 布恒
 * 0，只有真正按 {@code min} 归一才给得出当前数字。
 */
class EconomyRealScaleClothTest {

  /** 真档每格人口（11,830,000 ÷ 799，与 {@code EconomyRealScaleSeedBottleneckTest} 同口径）。 */
  private static final long POPULATION_PER_HEX = 11_830_000L / 799L;

  /** 该格的城市人口（城市占总人口约 12%，取真档报告里的量级）。 */
  private static final long CITY_POPULATION = 1_777L;

  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final String MAP_ID = "econ-cloth";

  private static final IndustryId FARM = IndustryHexKeys.id(EconomySeeder.FARM, 0, 0);
  private static final IndustryId WEAVE = IndustryHexKeys.id(EconomySeeder.WEAVE, 0, 0);
  private static final IndustryId CRAFT = IndustryHexKeys.id(EconomySeeder.CRAFT, 0, 0);

  /**
   * ★★ <b>一年的天数 = 360（三个 120 天的生产周期），不是 365</b> —— M0 收尾实测更正的一处**夹具 bug**。
   *
   * <p>原值是 {@code 365L}，而本仓的"一年"读数一律在第 360 天取（{@code CYCLE_DAYS = 120} 的第 3 个关账日； 一年期真档推进也是 tick
   * 120/240/360）。{@code 365} 会落到<b>第 4 个周期的第 5 天</b> —— 而周期的第一天就把<b>现扣投入</b>划走（{@code
   * drawCycleInputs}）⇒ 缸里的**纤维被当天取空**。
   *
   * <p>★★ <b>这一处年度长度错位正是"纤维库存 0 vs 18,042,000"那条悬案的唯一成因</b>（M0 收尾实测，逐账户 dump）： <b>下表是 M0 单 unit
   * 口径的留痕</b>；R4-B3a 多 unit 拆分后的当前逐值见各用例（年末家户纤维 23,297,720、 全部账户 23,490,720）。
   *
   * <pre>
   * 时点          家户纤维账   说明
   * 播种态(第 0 天)  20,700,000   创世给的两池（农村 18,600,000 + 城镇 2,100,000）全在家户账上
   * 第 1 天          0           两池当天被取空（尚未收获）
   * 第 120/240/360 天 18,042,000  该周期农田净产（关账日入账，下一周期还没开始）
   * 第 365 天       0           第 4 周期第 5 天 ⇒ 新周期第一天已把料划走
   * </pre>
   *
   * ⇒ 当年那句"一次推 0→360 = 0 而分段推 = 18,042,000，故余额依路径而变"是**两个错叠在一起**： ① 路径无关（M0.1 已逐值证明 360 == 120×3）；②
   * 那个 0 是**在第 365 天量的**（时点错），不是路径造成的。
   */
  private static final long YEAR_DAYS = 360L;

  /**
   * 一格平原（真档人口）+ 一座城（1,777 人），**真播种器载荷**经真 handler 落成状态。
   *
   * <p>★ 城市人口**不从农村人口里扣**：{@code SettlementPlan} 的两池是分开的（与 {@code PopulationSeeder} 的语义一致）。
   */
  private static EconomyData seeded() {
    return seededPlanFixture().economy();
  }

  /**
   * ★★ <b>H1：同一个 plan 交出的家户账本</b>（真路径 = {@code economy.Seed} + {@code actor.Seed} 同批； 本夹具只走 handler
   * ⇒ 账本在这里从**同一份** plan 的 {@code householdStocks} 建）。
   */
  private static ActorData seededBooks() {
    return seededPlanFixture().books();
  }

  /** 一次播种的两个产物（economy + actor），避免两处各算一遍 plan。 */
  private record Seeded(EconomyData economy, ActorData books) {}

  private static Seeded seededPlanFixture() {
    SettlementPlan plan =
        new SettlementPlan(
            Map.of(HEX, POPULATION_PER_HEX),
            List.of(
                new PlannedCity(
                    "c-0_0",
                    "c-0_0",
                    HEX,
                    PlannedCity.TIER_TOWN,
                    CITY_POPULATION,
                    1,
                    0.0,
                    1.0,
                    1.0,
                    "test")),
            Map.of(),
            250L,
            0L);
    String payload =
        EconomySeeder.payload(MAP_ID, PopulationSeeder.groups(plan, 0L), at -> "plains");
    SimulationState emptyState =
        new SimulationState(
            stateMeta(), snapshots(EconomyData.empty()), InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(emptyState, payload);
    assertThat(outcome)
        .as("真播种器产出的载荷必须被真 handler 接受：%s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    EconomyData economy =
        EconomyChangeSet.apply(
            (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), EconomyData.empty());
    // ★ H1：家户账本由**同一份 plan** 的开缸库存建（真路径里这是同批的第二条命令 actor.Seed）。
    EconomySeeder.Seed seeding =
        EconomySeeder.plan(MAP_ID, PopulationSeeder.groups(plan, 0L), at -> "plains");
    // ★★ H4：账本 = 商品 + 货币（两者同源，都出自这一份 plan）。
    // ★★ H5（⑤）：经营主体的开缸账与家户同源（同一次 plan）—— 见 EconomyTestWorld 的同款注释。
    ActorData books =
        HouseholdSeeder.books(
            seeding.householdLocations(),
            seeding.householdStocks(),
            seeding.householdMoney(),
            seeding.operators());
    return new Seeded(economy, books);
  }

  // ── ① 农村：家庭纺织拿到非零配额，且真的织出布 ────────────────────────────────────────

  /**
   * ★★ **本轮最容易踩的那个坑，逐值钉在这里**（brief 点名，与 V3 的"播种器留白"同款）：若创世把农村批次 **1000‰ 全给农业**，
   * 家庭纺织就是"有配额、没活干"的惰性状态 ⇒ 报表里看不见布。
   *
   * <p>判据 = ① 农村批次有一条**非零**的纺织配额；② 推一年后该格的 {@code CLOTH} 库存 **&gt; 0**。
   */
  @Test
  void theRuralHouseholdGetsANonZeroWeavingQuotaAndActuallyWeaves() {
    EconomyData shared = seeded();

    long weaveQuota = quotaOf(shared, WEAVE);
    assertThat(weaveQuota).as("★ 判据 ①：农村批次给家庭纺织的配额必须**非零**（否则织机有配额、没原料也没活干）").isPositive();
    assertThat(weaveQuota)
        .as("M1.8（9521bd00）：逐批预算封顶后纺织配额 = 590,865（R4-B3a 拆成主/副 unit 后总量不变）")
        .isEqualTo(590_865L)
        .isLessThan(ruralDailyLabor(shared) * EconomySeeder.WEAVE_SHARE_PER_MILLE / 1000L);

    EconomyOwnershipFixture.Result afterOneCycle =
        EconomyOwnershipFixture.advance(
            shared, seededBooks(), MAP_ID, 0L, EconomySeeder.CYCLE_DAYS);
    long clothAfterOneCycle = clothOf(afterOneCycle.actor(), afterOneCycle.economy());
    assertThat(clothAfterOneCycle)
        .as("★ 判据 ②：一个周期后**家户账**就有布（R4-B3a 多 unit 拆分后的实测落点）")
        .isEqualTo(13_540_215L)
        .isPositive();

    EconomyOwnershipFixture.Result afterOneYear =
        EconomyOwnershipFixture.advance(shared, seededBooks(), MAP_ID, 0L, YEAR_DAYS);
    assertThat(clothOf(afterOneYear.actor(), afterOneYear.economy()))
        .as("★ 判据 ②（原文）：推一年后该格**家户账**的 CLOTH 库存 = 23,082,749")
        .isEqualTo(23_082_749L)
        .isPositive();
    assertThat(produced(afterOneYear, CRAFT, EconomyTestWorld.CLOTH))
        .as("年末该格全部账户合计的布 = 37,429,869（家户 23,082,749 + 两个 operator 账 14,347,120）")
        .isEqualTo(37_429_869L);
    // ★★ **R4-B3a 多 unit 口径重钉（2026-09-30，本批收尾）**：一个产业不再只有一本 operator 账 ——
    //   主 unit 的 operator 是聚合主体（ESTATE / WORKSHOP / 产业型 HOUSEHOLD），副 unit 的 operator 是家户
    //   ⇒ 商品的落点分在"聚合 operator 账"与"家户账"两处。下面读数全部按**当前 unit 拆分后的实际落点**重钉，
    //   旧单 unit 字面量随 R4-B3a 作废（不是放宽）。
    assertThat(fiberOf(afterOneCycle.actor(), afterOneCycle.economy()))
        .as("第 1 周期末家户账纤维（实测；纤维先被现扣投入取走，收获后再按关系回到家户与 operator 两族）")
        .isEqualTo(16_530_360L);
    assertThat(heldByOperator(afterOneCycle, CRAFT, EconomyTestWorld.FIBER))
        .as("第 1 周期末**作坊聚合 operator** 的纤维账（实测 1,500,000：主 unit 自己名下的周转料）")
        .isEqualTo(1_500_000L);
    // ★★ 口径重钉（2026-09-30，随 E1 生产者账读己写语义收口）：按"初始 20,700,000 − 本期投入 20,700,000
    //   + 农田净产 18,030,360"这条守恒式，期末账户合计正好 **18,030,360**（家户 16,530,360 + 作坊
    //   operator 周转料 1,500,000）；旧观测到的 30,659,760 与差额 12,629,400 已随关系结算修正作废。
    assertThat(produced(afterOneCycle, FARM, EconomyTestWorld.FIBER))
        .as("第 1 周期末两族账户合计的纤维（实测；守恒式已对齐 18,030,360）")
        .isEqualTo(18_030_360L);
    assertThat(produced(afterOneYear, FARM, EconomyTestWorld.FIBER))
        .as("年末两族账户合计的纤维（实测；不再等于旧单 unit 口径的 19,905,000）")
        .isEqualTo(23_490_720L);
    assertThat(fiberOf(afterOneYear.actor(), afterOneYear.economy()))
        .as("年末**家户账**那一份（实测）")
        .isEqualTo(23_297_720L);
    assertThat(heldByOperator(afterOneYear, WEAVE, EconomyTestWorld.FIBER))
        .as("年末织机聚合 operator 账上的纤维（实测 193,000）")
        .isEqualTo(193_000L);
    assertThat(heldByOperator(afterOneYear, CRAFT, EconomyTestWorld.FIBER))
        .as("年末作坊聚合 operator 账上的纤维（实测 0：本期周转料已在周期内投入/转出）")
        .isZero();
    assertThat(fiberOf(afterOneYear.actor(), afterOneYear.economy()))
        .as("年末家户账纤维 > 第 1 周期末（纤维逐周期回落到家户账）")
        .isGreaterThan(fiberOf(afterOneCycle.actor(), afterOneCycle.economy()));
    // ★★ **R4（T0）取代了 R3 那条"第 2 周期起停工"的如实记**：本格每个周期都能从农田取到新一期的纤维 ⇒
    //   纺织**持续**，布库存逐周期增长（下一条与 {@link #weavingContinuesEveryCycleBecauseTheFieldsFeedTheLooms}
    // 一起钉死）。
    assertThat(clothOf(afterOneYear.actor(), afterOneYear.economy()))
        .as("★ R4（T0）：布库存**不再**只靠第 1 个周期的那一份 —— 推一年（3 个周期）拿到的是三份")
        .isGreaterThan(clothAfterOneCycle);
  }

  /**
   * ★★ **非 LAND 生产成立 + 规模由最紧约束决定**（spec §五/§六 给 R3 定的判据）：纺织的三路瓶颈里**没有一寸土地**。
   *
   * <pre>
   * 织机 = 农村人口 ÷ {@link EconomySeeder#RURAL_CAPITA_PER_LOOM}(20) = 740
   * 劳动 = R4-B3a 拆分后主/副 unit 合计：名义纺织配额 590,865 ⇒ 590；周期第一天缺口重排后逐 unit 实际累加 **629,000** ⇒ 629
   * 纤维 = 同格争用配给给纺织 **18,911,111** ÷ (每台一周期 30,000) = **630**（不是最紧）
   * ⇒ 规模 = min(740, 629, 630) = **629** ⇒ 布毛产 = 629 × 30 × 1000 = 18,870,000（净 18,303,900）
   * </pre>
   *
   * <p>★★ <b>R4-B3a 多 unit 拆分后"三路各不相同"已消失</b>（如实记）：织机 740 不再是规模；劳动 629 与纤维 630 只差 1，最紧的是**劳动**。旧单
   * unit 口径的 626/626 随拆分作废（不是放宽）。判别力仍在：把纺织产能写成"土地"⇒ 规模 0 ⇒ 布恒 0；只看织机（740）⇒ 布会多出 3,330,000 毫；不扣纤维投入
   * ⇒ 纤维守恒式不平（见端到端用例）。
   */
  @Test
  void weavingScaleComesFromLoomsLaborAndFiberNotFromLand() {
    EconomyData shared = seeded();
    Industry weave = shared.industries().get(WEAVE);

    assertThat(weave.capacityPerUnit())
        .as("★ 纺织的产能锚是**织机**（TOOL），不是土地")
        .containsOnlyKeys(AssetKind.TOOL);
    assertThat(capacityOf(shared, WEAVE, AssetKind.TOOL))
        .as("★★ R3B.2：产能总量真值在 AssetShare（不再读旧兼容位 Industry.capacity）")
        .isPositive();
    assertThat(assetKindsOf(shared, WEAVE))
        .as("★★ 纺织只有 TOOL 份额，一寸土地都没有")
        .containsOnly(AssetKind.TOOL);
    // ★★ H0.2：纤维并入**农村四行**（旧版住在"纺织四行"上）—— 那是同一批人的同一本账；
    //   而"纺织那四行一寸土地都没有"这条判据**没有对象了**（那四行已不存在，行上也不再有任何生产资料）。
    long looms = capacityOf(shared, WEAVE, AssetKind.TOOL);
    // ★ H1：纤维从**家户账本**读（行里已经没有 goods 这一栏）。
    ActorData books = seededBooks();
    long fiber = 0L;
    for (ClassRow row : shared.classes().values()) {
      if (!row.view().residence().equals(ResidenceKind.RURAL)) {
        continue;
      }
      fiber += householdGoods(books, row, EconomyTestWorld.FIBER);
    }
    assertThat(looms).as("织机数 = 农村人口 ÷ RURAL_CAPITA_PER_LOOM").isEqualTo(740L);
    assertThat(fiber)
        .as("初始纤维 = 本格**农田一个周期的纤维副产**（估计来源；搬到织机上是 V8 的活）")
        // ★ H0.3（K3）起这份量由**产业产能那一处**算：3,100 亩 × 每亩 6 单位 × 1000 毫/单位
        //   （旧版是"把千分亩按行切、各行折亩再取整" ⇒ Σ 3,098 亩 ⇒ 18,588,000；两者差 12,000 毫 = 2 亩的余数）。
        .isEqualTo(
            EconomySeeder.MU_PER_HEX
                * EconomySeeder.FIBER_OUTPUT_PER_MU
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT)
        .isEqualTo(18_600_000L);

    long fiberCap =
        fiber / (EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE * EconomySeeder.FIBER_MILLI_PER_CLOTH);
    // ★★ R4-B3a 之后的**三条实际路径**（独立复算，不抄实际）：
    //   ① 名义配额 590,865（逐批预算封顶；拆成主/副 unit 后总量不变）⇒ 590 台；
    //   ② 周期第一天按缺口信号重排后，纺织全部 unit 实际累加 **629,000** 千分劳动 ⇒ 629 台；
    //   ③ 同格争用按需求比例配给给纺织 **18,911,111** 纤维 ⇒ 630 台（比劳动宽 1）。
    //   织机那一路仍是 740，**不咬合**；⇒ 收获规模 = 629（劳动那一路最紧，不是"纤维单独最紧"）。
    EconomyOwnershipFixture.Result dayOne =
        EconomyOwnershipFixture.advance(shared, books, MAP_ID, 0L, 1L);
    long quotaLaborCap = weaveLabor(shared) / EconomySeeder.LABOR_MILLI_PER_LOOM;
    long settledLaborCap =
        unitCycleLaborOf(dayOne.economy(), WEAVE) / EconomySeeder.LABOR_MILLI_PER_LOOM;
    long rationedFiberCap =
        unitInputOf(dayOne.economy(), WEAVE, EconomyTestWorld.FIBER)
            / (EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE * EconomySeeder.FIBER_MILLI_PER_CLOTH);
    assertThat(fiberCap).as("★ 「只按自己缸」那个口径仍是算术事实（18,600,000 ÷ 30,000）").isEqualTo(620L);
    assertThat(quotaLaborCap).as("M1.8：名义纺织配额 590,865 ⇒ 590 台").isEqualTo(590L);
    assertThat(settledLaborCap)
        .as("★ R4-B3a：第 1 天实际累加的纺织劳动 629,000（主/副 unit 合计）⇒ 629 台")
        .isEqualTo(629L);
    assertThat(rationedFiberCap).as("★ H6 争用配给给纺织 18,911,111 纤维 ⇒ 630 台").isEqualTo(630L);
    assertThat(Math.min(settledLaborCap, rationedFiberCap))
        .as("实际规模 = min(劳动 629, 纤维 630) = 629 —— 劳动最紧")
        .isEqualTo(629L)
        .isLessThan(looms);
    assertThat(rationedFiberCap).isGreaterThan(settledLaborCap);

    EconomyOwnershipFixture.Result afterOneCycle =
        EconomyOwnershipFixture.advance(shared, books, MAP_ID, 0L, EconomySeeder.CYCLE_DAYS);
    // ★★ T4：布的**净产**按全部 unit 的规模合计读（主 unit 700‰ 归家户 + 副 unit 自留都已在库存里）。
    assertThat(produced(afterOneCycle, WEAVE, EconomyTestWorld.CLOTH))
        .as("★ 本格第 1 周期产出的布 = 实际规模 629 台 × 30 × 1000 × 0.97（作坊这一路因工具投入争用为 0，见下个用例）")
        .isEqualTo(
            Math.min(settledLaborCap, rationedFiberCap)
                * EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE
                * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
                * 970L
                / 1000L)
        .isEqualTo(18_303_900L);
    assertThat(fiberCap).as("★ 「只按自己缸」那个口径仍是算术事实（18,600,000 ÷ 30,000）").isEqualTo(620L);
  }

  /** 该产业名下全部配额之和（= 结算每天累加进 {@code cycleLaborMilli} 的那个数）。 */
  private static long quotaOf(EconomyData data, IndustryId industry) {
    long sum = 0L;
    for (LaborAllocation allocation : data.allocations().values()) {
      ProductionUnit unit = data.units().get(new ProductionUnitId(allocation.activity()));
      if (unit != null && unit.industry().equals(industry)) {
        sum += allocation.laborMilli();
      }
    }
    return sum;
  }

  /** 家庭纺织那一池的日劳动。 */
  private static long weaveLabor(EconomyData data) {
    return quotaOf(data, WEAVE);
  }

  // ── ② 城市：靠自己的产品（布与工具）生产，与土地无关 ──────────────────────────────────

  /**
   * ★★ **城市作坊：非 LAND 生产 + 城市能产出自己的产品**（T5 的两个目的）。
   *
   * <pre>
   * 作坊 = 城市人口 1,777 ÷ {@link EconomySeeder#URBAN_CAPITA_PER_WORKSHOP}(50) = **35** 座
   * 劳动/原料在 R4-B3a 后拆成主 unit + 家户副 unit；实测工具账：第 1 周期末 70,000（创世）→ 第 2 周期末 121,250
   * → 第 3 周期末 192,500（逐周期增长 ⇒ 城市作坊确实在产；不是"不占地 ⇒ 恒产 0"）
   * 城市家户的布关系入账：第 2/3 周期各 93,517（第 1 周期为 0，见下）
   * </pre>
   *
   * <p>★★ <b>R4-B3a 如实记</b>：工具投入在"作坊主 + 各匠户副 unit"之间是同一种中间品 —— 当前 {@code rationContestedInputs}
   * 只把家户账并进争用池（不含聚合 operator 自有账），故第 1 周期工具配给额为 0、 作坊没有劳动配额；第 2 周期起才以当期计得的主 unit
   * 劳动开产。下面钉的是**当前实测**，不是旧单 unit 口径的 169,750 恒值。
   *
   * <p>★ 判别力：v1 的手工业"不占地 ⇒ 恒产 0"（spec §一.4 实测的病态）⇒ 本条红。
   */
  @Test
  void theCityWorkshopProducesItsOwnGoodsWithoutAnyLand() {
    EconomyData shared = seeded();
    assertThat(shared.industries()).as("有城的格 = 农业 + 家庭纺织 + 城市作坊").containsKeys(FARM, WEAVE, CRAFT);

    ActorData books = seededBooks();
    // ★★ 本用例全部读数走**串联推进**（0→120→240→360），与真协调器的日常形态一致。
    EconomyOwnershipFixture.Result afterOneCycle =
        EconomyOwnershipFixture.advance(shared, books, MAP_ID, 0L, EconomySeeder.CYCLE_DAYS);
    EconomyOwnershipFixture.Result afterTwoCycles =
        EconomyOwnershipFixture.advance(
            afterOneCycle.economy(),
            afterOneCycle.actor(),
            MAP_ID,
            EconomySeeder.CYCLE_DAYS,
            EconomySeeder.CYCLE_DAYS * 2L);
    EconomyOwnershipFixture.Result afterThreeCycles =
        EconomyOwnershipFixture.advance(
            afterTwoCycles.economy(),
            afterTwoCycles.actor(),
            MAP_ID,
            EconomySeeder.CYCLE_DAYS * 2L,
            YEAR_DAYS);
    long workshops = CITY_POPULATION / EconomySeeder.URBAN_CAPITA_PER_WORKSHOP;
    assertThat(workshops).as("★ 城里的作坊数锚（35 座 = AssetShare[WORKSHOP] 总量）").isEqualTo(35L);
    assertThat(capacityOf(shared, CRAFT, AssetKind.WORKSHOP)).isEqualTo(35L);
    assertThat(assetKindsOf(shared, CRAFT))
        .as("★ 作坊的产能锚只有 WORKSHOP，一寸土地都没有")
        .containsOnly(AssetKind.WORKSHOP);

    // ★★ **R4-B3a 口径重钉（2026-09-30）**：第 1 周期工具争用的配给额为 0（当前争用池只含家户账），
    //   作坊自第 2 周期起开产；城市家户的**布关系入账**第 2/3 周期各 **93,517**（旧单 unit 的
    //   115,963 / 104,740 随拆分与 E1 劳动重排作废，不是放宽）。
    assertThat(weaveClothIncome(afterOneCycle.economy(), CRAFT))
        .as("★ 第 1 周期城市家户没有布入账（工具争用 ⇒ 作坊没有劳动配额，如实钉住）")
        .isZero();
    assertThat(heldByOperator(afterOneCycle, CRAFT, EconomyTestWorld.TOOL))
        .as("★ 第 1 周期作坊聚合 operator 的工具账仍是创世 70,000 ⇒ 确实没有产出")
        .isEqualTo(70_000L);
    assertThat(weaveClothIncome(afterTwoCycles.economy(), CRAFT))
        .as("★ 第 2 个周期的城市家户布入账 93,517（实测）")
        .isEqualTo(93_517L);
    assertThat(weaveClothIncome(afterThreeCycles.economy(), CRAFT))
        .as("★ 第 3 个周期的城市家户布入账 93,517（实测；不再逐周期衰减）")
        .isEqualTo(93_517L);
    assertThat(clothOf(afterThreeCycles.actor(), afterThreeCycles.economy()))
        .as("年末家户账上的布（实测 23,082,749）")
        .isEqualTo(23_082_749L);
    assertThat(produced(afterThreeCycles, CRAFT, EconomyTestWorld.CLOTH))
        .as("年末该格全部账户的布存量（实测 37,429,869）")
        .isEqualTo(37_429_869L);
    assertThat(clothOf(afterThreeCycles.actor(), afterThreeCycles.economy()))
        .as("★ 一年后的布 = 第 1 周期的布 + 后两个周期**持续**织出来的那两份（T0 之后不再停工）")
        .isGreaterThan(clothOf(afterOneCycle.actor(), afterOneCycle.economy()));
    // ★ T4：工具是城市作坊**独有**的产品，故它的账是"作坊有没有在生产"的干净读数。
    long toolsAfterTwoCycles = heldByOperator(afterTwoCycles, CRAFT, EconomyTestWorld.TOOL);
    long toolsAfterThreeCycles = heldByOperator(afterThreeCycles, CRAFT, EconomyTestWorld.TOOL);
    assertThat(toolsAfterThreeCycles)
        .as("★ 工具是城市作坊的第二种产品，且**逐周期还在增**（城市作坊确实在产）")
        .isGreaterThan(toolsAfterTwoCycles);
    assertThat(toolsAfterTwoCycles)
        .as("★ 第 2 个周期末的作坊聚合 operator 工具账（串联推进；实测值）")
        .isEqualTo(121_250L);
    assertThat(toolsAfterThreeCycles).as("★ 第 3 个关账日（第 360 天）的工具账（串联推进；实测值）").isEqualTo(192_500L);
    assertThat(toolsOf(afterThreeCycles.actor(), afterThreeCycles.economy()))
        .as("家户账上一件工具都没有（工具产出只落在作坊聚合 operator 账上）")
        .isZero();
    assertThat(toolsOf(books, shared)).as("非平凡：创世时一件工具都没有").isZero();
  }

  /**
   * ★★ **R4（T0）：纺织**每个周期**都在织 —— 农田把新一期的纤维交给了同格的织机**（R3 遗留的收口）。
   *
   * <pre>
   * 第 1 周期：池 = 农村 18,600,000 + 城镇 2,100,000 = 20,700,000
   *           同格争用配给（R4-B3a 多 unit）：织机 **18,911,111**、作坊 **1,788,889**（Σ == 池）
   *           ⇒ 织机规模 = min(织机 740, 劳动 629, 纤维 630) = **629** ⇒ 布净产 18,303,900
   * 第 2 周期：农田纤维进项让织机继续吃到料；织机关系入账 7,002,329；家户布 13,540,215 → 20,151,558
   * 第 3 周期：织机继续（关系入账 4,710,657）；家户布 → 23,082,749、全部账户合计 37,429,869；CLOTH consumed 5,090,628 > 0
   * </pre>
   *
   * <p>★★ **R4-B3a 口径重钉（2026-09-30）**：多 unit 拆分让所有逐值都与旧单 unit 的 18,793,421/1,906,579、 20,020,800
   * 不同（见 {@link #contestedFibreIsRationedByNeedSoTheWorkshopNeverStalls} 的逐值断言）；
   * 本用例只钉"每周期都有布、且库存逐周期增长"这条机构判断。
   *
   * <p>★★ **判别力（逐条对着一种坏实现）**：
   *
   * <ul>
   *   <li>**不做同格取材 / 不开池**（R3 的旧形态）⇒ 第 2/3 周期的布毛产是 **0** ⇒ 两条断言一起红；
   *   <li>**开池但不配给**（先到先得）⇒ 织机把池子取光 ⇒ 作坊第 2 周期 0 ⇒ 末尾那条红；
   *   <li>**取材不记供方的 consumed**（单侧扣减）⇒ 逐商品的守恒式当场不平 ⇒ 端到端那条守恒用例红。
   * </ul>
   */
  @Test
  void weavingContinuesEveryCycleBecauseTheFieldsFeedTheLooms() {
    EconomyData shared = seeded();
    // ★★ R4-B3a（多 unit）：农田产能拆成主 unit + 四个家户副 unit；每 unit 的规模 = ⌊usableAssets ÷ 1,000⌋
    //   ⇒ 各 unit 亩数分别向下取整，合计 = 2,170 + 46 + 325 + 418 + 139 = **3,098 亩**（不再是整块 3,100 亩）。
    //   净产 = 3,098 × 6 × 1000 × 0.97 = **18,030,360** 毫纤维。★ "缺料则缩产"仍由 economy 的
    //   EconomySowingTest（佃农缸空 ⇒ 0 亩）守着。
    long farmNetFiber =
        3_098L
            * EconomySeeder.FIBER_OUTPUT_PER_MU
            * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
            * 970L
            / 1000L;
    long loomNeedPerCycle =
        740L * EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE * EconomySeeder.FIBER_MILLI_PER_CLOTH;

    // ★★ **逐周期串联推进**（T5）：operator 的账是**存量**（它跨周期累积）⇒ 要量"第 N 个周期产了多少"，
    //   必须拿相邻两个时点**做差**（行侧的流水本来就是本期口径，operator 那侧不是）。
    EconomyOwnershipFixture.Result cycle1 =
        EconomyOwnershipFixture.advance(shared, seededBooks(), MAP_ID, 0L, 120L);
    EconomyOwnershipFixture.Result cycle2 =
        EconomyOwnershipFixture.advance(cycle1.economy(), cycle1.actor(), MAP_ID, 120L, 240L);
    // ★ 第 3 个周期的**关账日**是第 360 天（不是 365）—— 关账日读得到整周期的量，次日归零（§八.5）。
    EconomyOwnershipFixture.Result cycle3 =
        EconomyOwnershipFixture.advance(cycle2.economy(), cycle2.actor(), MAP_ID, 240L, 360L);

    // ★★ T4/M2：读的是**两族账户的库存合计**（市场成交只在两族之间搬）—— 见 `produced` 的类注。
    // ★★ **R4-B3a 口径重钉（2026-09-30）**：同格争用按需求比例配给给纺织 **18,911,111** 纤维
    //   ⇒ 织机规模 = min(织机 740, 劳动 629, 纤维 630) = **629** 台 ⇒ 第 1 周期净产
    //   629 × 30 × 1000 × 0.97 = **18,303,900**（旧单 unit 的 20,020,800 作废）。
    assertThat(produced(cycle1, WEAVE, EconomyTestWorld.CLOTH))
        .as("第 1 周期：纺织实际规模 629 台 ⇒ 净产 18,303,900（作坊这一路因工具争用为 0）")
        .isEqualTo(18_303_900L);
    // ★★ **每个周期的织机关系入账都 > 0**（真实值随纤维池与劳动重排变化，不再逐周期重复）：
    assertThat(weaveClothIncome(cycle2.economy(), WEAVE))
        .as("★ 第 2 周期：纺织的**关系入账** = 7,002,329 > 0 ⇒ 织机没有停工")
        .isEqualTo(7_002_329L)
        .isPositive();
    assertThat(weaveClothIncome(cycle3.economy(), WEAVE))
        .as("★ 第 3 周期：纺织的**关系入账** = 4,710,657 > 0 ⇒ 织机仍在织")
        .isEqualTo(4_710_657L)
        .isPositive();
    // ★★ R4-B3a：农田拆成 5 个 unit、逐 unit 向下取整 ⇒ 合计 3,098 亩；算式：3,098 × 6 × 1000 × 0.97。
    assertThat(farmNetFiber).as("农田一个周期的纤维净产（逐 unit 取整后 = 3,098 亩）").isEqualTo(18_030_360L);
    assertThat(loomNeedPerCycle).as("织机满负荷一个周期要多少纤维（缺口那一侧）").isEqualTo(22_200_000L);

    // ★ 布库存**逐周期增长**（这是 brief 给 R4 的真档判据 ③ 在本夹具上的形态；真档上由
    //   WorldgenInitializeToolTest 的 R4 用例逐值钉住）。
    assertThat(clothOf(cycle2.actor(), cycle2.economy()))
        .as("第 2 周期末的布 > 第 1 周期末")
        .isGreaterThan(clothOf(cycle1.actor(), cycle1.economy()));
    assertThat(clothOf(cycle3.actor(), cycle3.economy()))
        .as("第 3 周期末的布 > 第 2 周期末")
        .isGreaterThan(clothOf(cycle2.actor(), cycle2.economy()));
    // ★★ **布真的被消费**（R4 的 T2）：三个周期里布那一维的缺口与消费都读得出来。
    assertThat(flowConsumed(cycle3.economy(), EconomyTestWorld.CLOTH))
        .as("★ 判据（真档可见性 ④）：CLOTH 的 consumed 非零")
        .isPositive();
  }

  /**
   * ★★★ <b>H6-lite 判据：同格争用的中间品按需求比例配给 ⇒ 作坊<b>不再永久停工</b></b>（改前：第 2 个周期起布入账恒 0）。
   *
   * <p>★★ <b>它修的是什么（实测，不是推测）</b>：本夹具一格里有 **farm + weave + craft** 三个产业（农村 14,806 人 + 一座城 1,777 人）。
   * R4-B3a 起每个产业拆成"主 unit + 家户副 unit"：织机按 relation 取农村家户的账（农田纤维副产每周期都有进项）， 作坊按 relation 取作坊聚合
   * operator 账 + 城镇家户账（H5 创世周转料）。同格纤维由 H6 池子按需求比例配给。
   *
   * <pre>
   * 第 1 周期：池 = 农村 18,600,000 + 城镇 2,100,000 = 20,700,000
   *           R4-B3a 多 unit 配给：织机 **18,911,111**、作坊 **1,788,889**（Σ == 池）
   * 第 2 周期：织机 **16,530,360**、作坊 **1,500,000**（作坊聚合 operator 自有周转料进池；Σ = 18,030,360）
   * 第 3 周期：城市家户布入账 93,517（与第 2 周期同值）；织机仍在织（家户布入账 4,710,657）
   * </pre>
   *
   * <p>★★ <b>R4-B3a 口径重钉（2026-09-30）</b>：旧单 unit 的 18,793,421/1,906,579、15,367,870/1,874,130、 P₂ =
   * 17,242,000 都随多 unit 拆分作废；下面每条都按当前 unit 拆分与 H6 配给的**实测值**重钉。
   *
   * <p>★ <b>判别力（逐条对着一种坏实现）</b>：
   *
   * <ul>
   *   <li>**删掉池里"别人名下"那一层取料**（只有 relation 自己名下）⇒ 作坊第 2 周期 0 ⇒ 红（这正是改前的病）；
   *   <li>**只开池、不配给**（先到先得）⇒ 织机把池子取光（它排在前）⇒ 作坊 0 ⇒ 红；
   *   <li>**配给算错**（平均分、或按产能分）⇒ 两条**逐值**断言红；
   *   <li>**配给不给池子算规模**（可供量仍只算自己名下）⇒ 作坊的规模恒 0、连"想要多少"都是 0 ⇒ 红；
   *   <li>**池子把"没有任何 relation 指名的产业"也放进来** ⇒ economy 侧的 {@code EconomySowingTest} 那条红（"没有人供给它"被抹掉）。
   * </ul>
   */
  @Test
  void contestedFibreIsRationedByNeedSoTheWorkshopNeverStalls() {
    EconomyData shared = seeded();
    ActorData books0 = seededBooks();
    EconomyOwnershipFixture.Result c1 =
        EconomyOwnershipFixture.advance(shared, books0, MAP_ID, 0L, 120L);
    EconomyOwnershipFixture.Result c2 =
        EconomyOwnershipFixture.advance(c1.economy(), c1.actor(), MAP_ID, 120L, 240L);
    EconomyOwnershipFixture.Result c3 =
        EconomyOwnershipFixture.advance(c2.economy(), c2.actor(), MAP_ID, 240L, 360L);

    // ① 作坊不再停工：城镇四行的布入账逐周期为正（第 1 周期因工具争用为 0，由上一个用例钉住）。
    assertThat(weaveClothIncome(c2.economy(), CRAFT))
        .as("★ 第 2 周期作坊**产出了布**（关系入账 93,517）")
        .isEqualTo(93_517L)
        .isPositive();
    assertThat(weaveClothIncome(c3.economy(), CRAFT))
        .as("★ 第 3 周期同样在产（93,517；不是「只多撑一个周期」）")
        .isEqualTo(93_517L)
        .isPositive();
    // ② 织机照旧在织（争用没有把老的产业挤死）。
    assertThat(weaveClothIncome(c2.economy(), WEAVE))
        .as("★ 第 2 周期织机照旧产出（关系入账 7,002,329；配给 = 两家都活，不是「保作坊、饿织机」）")
        .isEqualTo(7_002_329L)
        .isPositive();

    // ③ ★★ 逐值：两条腿**按需求比例**分池，Σ == 池（周期第一天扣料；关账日清零 ⇒ 读周期的第 119 天）。
    assertThat(inputsAt(shared, books0, 0L, 119L, WEAVE).get(FIBER_ID))
        .as("第 1 周期织机拿到的纤维（R4-B3a 多 unit 配给实测值）")
        .isEqualTo(18_911_111L);
    assertThat(inputsAt(shared, books0, 0L, 119L, CRAFT).get(FIBER_ID))
        .as("第 1 周期作坊拿到的纤维（R4-B3a 多 unit 配给实测值）")
        .isEqualTo(1_788_889L);
    assertThat(inputsAt(c1.economy(), c1.actor(), 120L, 239L, WEAVE).get(FIBER_ID))
        .as("第 2 周期织机拿到的纤维（实测 16,530,360）")
        .isEqualTo(16_530_360L);
    assertThat(inputsAt(c1.economy(), c1.actor(), 120L, 239L, CRAFT).get(FIBER_ID))
        .as("第 2 周期作坊拿到的纤维（实测 1,500,000 = 作坊聚合 operator 的创世周转料进池）")
        .isEqualTo(1_500_000L);
    assertThat(18_911_111L + 1_788_889L)
        .as("★ 守恒：两条腿之和 == 第 1 周期的池（农村 18,600,000 + 城镇 2,100,000）—— 池里一滴都不剩")
        .isEqualTo(18_600_000L + 2_100_000L);
    assertThat(16_530_360L + 1_500_000L)
        .as("★ 守恒：第 2 周期两条腿之和 == 当前同格池（织机 16,530,360 + 作坊 operator 1,500,000 = 农田本期净产 18,030,360）")
        .isEqualTo(18_030_360L);

    // ④ ★ "无争用 ⇒ 逐值不变"的另一半：种子只有农业要（从不争用）⇒ 逐 unit 取整后合计。
    assertThat(inputsAt(shared, books0, 0L, 119L, FARM).get(EconomyTestWorld.GRAIN))
        .as("★ 农业的种子从不争用 ⇒ 按当前 unit 规模合计（主 2,170 + 副 46/325/418/139 亩 × 8,000 毫/亩）")
        .isEqualTo(24_784_000L);
  }

  /** 商品 id：纤维（本判据的主角）。 */
  private static final CommodityId FIBER_ID = EconomyTestWorld.FIBER;

  /** 从 {@code from} 推到 {@code to}，读该产业**全部 unit** 本周期实际扣到的料（逐商品求和）。 */
  private static Map<CommodityId, Long> inputsAt(
      EconomyData data, ActorData books, long from, long to, IndustryId industry) {
    EconomyData after = EconomyOwnershipFixture.advance(data, books, MAP_ID, from, to).economy();
    Map<CommodityId, Long> totals = new LinkedHashMap<>();
    for (ProductionUnit unit : after.units().values()) {
      if (!unit.industry().equals(industry)) {
        continue;
      }
      for (Map.Entry<CommodityId, Long> entry : unit.cycleInputUsedMilli().entrySet()) {
        totals.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    return totals;
  }

  /**
   * ★★ <b>"这一格账面上有多少某商品"</b>（家户族 + 经营者族；账户键都按产业所在格）。
   *
   * <p>★★ <b>M2 收尾改口径（如实记）</b>：旧实现 = "行侧**本期关系入账** + operator 存量" —— 它假定产出一到手就不再换手。 M2
   * 起经营者入市，布/粮会被市场从 operator 账搬到别的家户账上（实测第 1 周期的布差 **27,287**） ⇒ 旧读法把"卖掉的布"
   * 读成"没产出来"。本实现改读**两族账户的库存合计**（市场成交只在两族之间搬、不进不出）⇒ 在初始库存为 0 的商品上， 它仍是"净产"的精确读数、且与市场无关。★
   * 商品被显式消费时会同时减少两族库存，那一条由守恒用例另钉。
   */
  private static long produced(
      EconomyOwnershipFixture.Result result, IndustryId industry, CommodityId commodity) {
    HexCoord location = hexOf(industry);
    long held = 0L;
    // ★★ R3B.2：账户不再只属于"家户"或"经营者"两种互斥视图 —— 同一格的全部账户（家户 +
    //   经营者 + 家户型 operator 的同一本账）按 (owner, location) 唯一键一次性求和，避免重复计数。
    for (Map.Entry<GoodsAccountKey, GoodsAccount> entry : result.actor().accounts().entrySet()) {
      if (!entry.getKey().location().equals(location)) {
        continue;
      }
      held += entry.getValue().balances().getOrDefault(commodity, 0L);
    }
    return held;
  }

  /** ★ <b>相邻两个时点之间</b>该格**产出的某商品**（两族账户库存合计的差 —— 市场只在两族之间搬）。 */
  private static long producedBetween(
      EconomyOwnershipFixture.Result before,
      EconomyOwnershipFixture.Result after,
      IndustryId industry,
      CommodityId commodity) {
    return produced(after, industry, commodity) - produced(before, industry, commodity);
  }

  /**
   * 该产业的**聚合 operator**（家户 actor 以外的 unit 经营者：ESTATE / WORKSHOP / 产业型 HOUSEHOLD）账上某商品的余额。
   *
   * <p>★ R4-B3a 起一个产业拆成"主 unit + 家户副 unit"：副 unit 的货物就住在**家户账**里（由 {@code fiberOf}/{@code clothOf}
   * 读），不能在这里再计一次 —— 否则同一本账会被读两遍。本方法只读非家户 operator。
   */
  private static long heldByOperator(
      EconomyOwnershipFixture.Result result, IndustryId industry, CommodityId commodity) {
    EconomyData data = result.economy();
    HexCoord location = hexOf(industry);
    Set<ActorRef> householdActors = new LinkedHashSet<>();
    for (HouseholdId household : data.classes().keySet()) {
      householdActors.add(HouseholdActors.of(household));
    }
    Map<GoodsAccountKey, Boolean> seen = new LinkedHashMap<>();
    long held = 0L;
    for (ProductionUnit unit : data.units().values()) {
      if (!unit.industry().equals(industry) || householdActors.contains(unit.operator())) {
        continue;
      }
      GoodsAccountKey key = new GoodsAccountKey(unit.operator(), location);
      if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
        continue;
      }
      GoodsAccount account = result.actor().accounts().get(key);
      held += account == null ? 0L : account.balances().getOrDefault(commodity, 0L);
    }
    return held;
  }

  /**
   * 某产业对应的那组家户本周期**布**的入账（流水所得里布那一维）。
   *
   * <p>★ H0.2：行里没有产业 ⇒ 按**该产业所在格的居住类型**取（农村家户既种地又织布 ⇒ 农业与纺织读的是同一组四行）。
   */
  private static long weaveClothIncome(EconomyData data, IndustryId industry) {
    HexCoord location = hexOf(industry);
    ResidenceKind residence =
        industry.value().startsWith(EconomySeeder.CRAFT + "@")
            ? ResidenceKind.URBAN
            : ResidenceKind.RURAL;
    long total = 0L;
    for (Map.Entry<HouseholdId, ClassRow> entry : data.classes().entrySet()) {
      ClassRow row = entry.getValue();
      if (!row.view().hex().equals(location) || !row.view().residence().equals(residence)) {
        continue;
      }
      io.mosire.simos.economy.model.FlowRow flow = data.flows().get(entry.getKey());
      if (flow != null) {
        total += flow.income().getOrDefault(EconomyTestWorld.CLOTH, 0L);
      }
    }
    return total;
  }

  /** 全格 Σ 行本周期某商品的消费（流水口径）。 */
  private static long flowConsumed(
      EconomyData data, io.mosire.simos.economy.api.id.CommodityId commodity) {
    return data.flows().values().stream()
        .mapToLong(flow -> flow.consumed().getOrDefault(commodity, 0L))
        .sum();
  }

  // ── 读数 ────────────────────────────────────────────────────────────────────────────

  /** 该格 Σ 家户某商品库存（H1：从 actor 侧的账本读；行里没有 goods 这一栏）。 */
  private static long goodsOf(ActorData books, EconomyData data, CommodityId commodity) {
    return data.classes().values().stream()
        .mapToLong(row -> householdGoods(books, row, commodity))
        .sum();
  }

  private static long clothOf(ActorData books, EconomyData data) {
    return goodsOf(books, data, EconomyTestWorld.CLOTH);
  }

  /** 某个家户账上某商品的余额（H1）：账户键经 {@link OwnershipBooks#accountKeyOf} 拼（不复述格式）；缺席 ⇒ 0。 */
  private static long householdGoods(ActorData books, ClassRow row, CommodityId commodity) {
    GoodsAccount account =
        books.accounts().get(OwnershipBooks.accountKeyOf(row.id(), row.view().hex()));
    return account == null ? 0L : account.balances().getOrDefault(commodity, 0L);
  }

  /**
   * 某**居住类型那组家户**的 Σ 某商品库存（H0.2：行里没有产业 ⇒ 改按 (格, 居住类型) 取； 农村行同时是"农业的行"与"纺织的行" ⇒
   * 农业与纺织共用一本账，读数也只有一个落点）。
   */
  private static long goodsOf(
      ActorData books, EconomyData data, ResidenceKind residence, CommodityId commodity) {
    return data.classes().values().stream()
        .filter(row -> row.view().residence().equals(residence))
        .mapToLong(row -> householdGoods(books, row, commodity))
        .sum();
  }

  private static long toolsOf(ActorData books, EconomyData data) {
    return goodsOf(books, data, EconomyTestWorld.TOOL);
  }

  private static long fiberOf(ActorData books, EconomyData data) {
    return goodsOf(books, data, EconomyTestWorld.FIBER);
  }

  /** 该格**农村那一池**（农村四行）的当日劳动（折算后的可用劳动）。 */
  private static long ruralDailyLabor(EconomyData data) {
    long sum = 0L;
    for (ClassRow row : data.classes().values()) {
      if (row.view().residence().equals(ResidenceKind.RURAL)) {
        sum += row.participationAdjustedLaborMilli();
      }
    }
    return sum;
  }

  /** 某产业名下全部 unit 的本周期累计劳动（千分劳动·日）。 */
  private static long unitCycleLaborOf(EconomyData data, IndustryId industry) {
    long sum = 0L;
    for (ProductionUnit unit : data.units().values()) {
      if (unit.industry().equals(industry)) {
        sum += unit.cycleLaborMilli();
      }
    }
    return sum;
  }

  /** 某产业名下全部 unit 的本周期实际扣到的某商品投入（毫单位）。 */
  private static long unitInputOf(EconomyData data, IndustryId industry, CommodityId commodity) {
    long sum = 0L;
    for (ProductionUnit unit : data.units().values()) {
      if (unit.industry().equals(industry)) {
        sum += unit.cycleInputUsedMilli().getOrDefault(commodity, 0L);
      }
    }
    return sum;
  }

  /** 某产业名下某一资产的总量（★★ R3B.2：唯一真值是 AssetShare；Industry.capacity 只是旧档兼容位）。 */
  private static long capacityOf(EconomyData data, IndustryId industry, AssetKind asset) {
    long sum = 0L;
    for (AssetShare share : data.assetShares().values()) {
      if (share.industry().equals(industry) && share.asset() == asset) {
        sum += share.quantity();
      }
    }
    return sum;
  }

  /** 某产业名下出现过的资产种类（用于"纺织一寸土地都没有"的判据）。 */
  private static Set<AssetKind> assetKindsOf(EconomyData data, IndustryId industry) {
    Set<AssetKind> kinds = new LinkedHashSet<>();
    for (AssetShare share : data.assetShares().values()) {
      if (share.industry().equals(industry)) {
        kinds.add(share.asset());
      }
    }
    return kinds;
  }

  private static HexCoord hexOf(IndustryId industry) {
    return IndustryHexKeys.hexKeyOf(industry)
        .map(HexCoord::parse)
        .orElseThrow(() -> new IllegalArgumentException("产业 id 里没有格键: " + industry));
  }

  private static StateMeta stateMeta() {
    return new StateMeta(
        new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(0));
  }

  private static Map<String, Snapshot> snapshots(EconomyData data) {
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put(
        "economy",
        new EconomySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)), SimosTimestamp.of(0), data));
    return modules;
  }
}
