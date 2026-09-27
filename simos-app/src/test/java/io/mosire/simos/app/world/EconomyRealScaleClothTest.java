package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.app.time.OwnershipBooks;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ **R3（T4/T5）：城乡各有一个非土地生产，且它们在**真档量级**上真的产出东西**。
 *
 * <p>★★ **为什么必须在真档量级上验**（与 {@code EconomyRealScaleSeedBottleneckTest} 同一条理由）：5 格夹具是 1,000 人/格的，
 * 而**织机数、作坊数、纤维量**都是按人口/亩数派生的（{@code EconomySeeder} 的场景参数）⇒ 小夹具上的数字证明不了真档的任何事。
 * 本文件用**真播种器载荷**（{@link EconomySeeder#payload} → 真 {@link EconomySeedHandler}）造一格 14,806 人的平原格 +
 * 一座城， 再跑真结算**一年**（365 天 = 3 个周期）。
 *
 * <pre>
 * 农村那一池（14,806 人）：
 *   毛劳动 = 14,806 × 580‰ = 8,587,480；该池日劳动 = Σ(行劳动 × 槽位投入率) = **7,385,372**
 *     （450/350/150/50 → 6,663/5,182/2,221/740 人 × 580 × 950/900/750/100‰ —— 与改口径前逐值相同）
 *   R3 拆成：农业 900‰ = 6,646,835；家庭纺织 100‰ = **738,537**
 *   三路瓶颈：织机 = 14,806 ÷ 20 = **740**；劳动 = 738,537 ÷ 1000 = **738**；
 *             纤维 = 本格农田一个周期的纤维副产 = 3,100 亩 × 6 × 1000 = 18,600,000 毫 ⇒ ÷ 30,000 = **620**（最紧）
 *   ⇒ 纺织规模 = min(740, 738, 620) = **620** ⇒ 布毛产 620 × 30 × 1000 = 18,600,000（净 18,042,000）
 * 城市那一池（1,777 人）：作坊 = 1,777 ÷ 50 = **35** 座；劳动可开 886 座（不是瓶颈）；原料够 35 座
 *   ⇒ 布毛产 35 × 60 × 1000 = 2,100,000（净 2,037,000）、工具毛产 35 × 5 × 1000 = 175,000（净 169,750）
 * 一年后的布库存 = 18,042,000 + 2,037,000 = **20,079,000**
 * </pre>
 *
 * <p>★★ **一年只跑得起一个周期**（**如实记，不是 bug**）：织机与作坊吃的那份原料是**创世一次性给的**（= 本格农田一个周期的
 * 纤维副产），而"把农田的纤维搬到织机上"是**跨行的实物转移** —— 那正是 spec §六 V8（统一转移）的活（brief 明说"不建议本轮做跨行实物转移"）。 ⇒ 第 2
 * 个周期起织机与作坊都没有原料、停工；而农田自己产的纤维照常累积在农业行里（读口看得见）。本文件把这条**逐值钉住**。
 *
 * <p>★ **本轮"最紧约束"在真档上真的被走到了**：纺织那一路的最紧者是**纤维**（620 &lt; 织机 740 &lt; 劳动 738 之上）—— 三路各不相同、量级接近，只有真正按
 * {@code min} 归一才给得出这个数。
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
   * <p>★★ <b>这一处年度长度错位正是"纤维库存 0 vs 18,042,000"那条悬案的唯一成因</b>（M0 收尾实测，逐账户 dump）：
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
            seeding.householdStocks(), seeding.householdMoney(), seeding.operators());
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

    long weaveQuota = 0L;
    for (LaborAllocation allocation : shared.allocations().values()) {
      if (allocation.actor().id().equals(WEAVE.value())) {
        weaveQuota += allocation.laborMilli();
      }
    }
    assertThat(weaveQuota).as("★ 判据 ①：农村批次给家庭纺织的配额必须**非零**（否则织机有配额、没原料也没活干）").isPositive();
    assertThat(weaveQuota)
        .as("M1.8（9521bd00）：逐批预算封顶后纺织配额 = 590,865（名义 100‰ 是 738,581；差 147,716 留在预算里）")
        .isEqualTo(590_865L)
        .isLessThan(ruralDailyLabor(shared) * EconomySeeder.WEAVE_SHARE_PER_MILLE / 1000L);

    EconomyOwnershipFixture.Result afterOneCycle =
        EconomyOwnershipFixture.advance(
            shared, seededBooks(), MAP_ID, 0L, EconomySeeder.CYCLE_DAYS);
    long clothAfterOneCycle = clothOf(afterOneCycle.actor(), afterOneCycle.economy());
    assertThat(clothAfterOneCycle).as("★ 判据 ②：一个周期就有布（规模由最紧约束决定 ⇒ 织机/劳动/纤维三路都参与）").isPositive();

    EconomyOwnershipFixture.Result afterOneYear =
        EconomyOwnershipFixture.advance(shared, seededBooks(), MAP_ID, 0L, YEAR_DAYS);
    assertThat(clothOf(afterOneYear.actor(), afterOneYear.economy()))
        .as("★ 判据 ②（原文）：推一年后该格的 CLOTH 库存 > 0")
        .isPositive();
    // ★★ **M2 口径重钉（2026-09-27，本批收尾）**：关账日区域市场让**经营主体入市** —— 作坊 operator 在
    //   第 120 天的市场上用工资周转金的余款预买下一周期的料：4,800 毫银（创世工资周转金）付掉本期 4,000 毫工资后
    //   剩 800 毫银，纤维挂牌价 1 毫/单位 ⇒ 买 800 单位 = **800,000 毫纤维**（实测：作坊 operator 账 +800,000，
    //   家户账 18,042,000 − 800,000 = 17,242,000）。
    //   ⇒ "年末缸里纤维 == 该周期农田净产"这条旧读法在 M2 下**不成立**：纤维存量此后 = 初始 + 各周期净产 − 各周期取材，
    //     而每次取材额不再恰好等于上一周期净产（市场预买改变了池子）。
    //   ★ 权威算式（逐值；净产同上，取材额 = H6 池子，即当时家户账上的纤维）：
    //     初始 20,700,000（第 0 天农村 18,600,000 + 城镇 2,100,000）
    //     + 3 个周期净产 3 × 18,042,000（第 120/240/360 天入账）
    //     − 三次周期取材 20,700,000 + 17,242,000 + 16,979,000
    //     = **19,905,000**（两族账户合计；纤维只被"投入"消耗，市场只在账户之间搬）。
    //   ★ 历史留痕：M0 收尾量到的"年末家户账 = 18,042,000"是 **M2 市场入市之前**的读数；下面钉的是 M2 的新逐值。
    assertThat(fiberOf(afterOneCycle.actor(), afterOneCycle.economy()))
        .as("M2：家户账 = 农田净产 18,042,000 − 市场预买进作坊 operator 的 800,000")
        .isEqualTo(17_242_000L);
    assertThat(heldByOperator(afterOneCycle, CRAFT, EconomyTestWorld.FIBER))
        .as("M2：作坊 operator 的市场预买（工资余款 800 毫银 ÷ 纤维价 1 毫/单位 = 800 单位）")
        .isEqualTo(800_000L);
    assertThat(produced(afterOneCycle, FARM, EconomyTestWorld.FIBER))
        .as("两族账户合计 = 本周期农田净产 3,100 × 6 × 1000 × 0.97 = 18,042,000（市场只在两族之间搬）")
        .isEqualTo(18_042_000L);
    assertThat(produced(afterOneYear, FARM, EconomyTestWorld.FIBER))
        .as(
            "年末两族合计 = 20,700,000 + 3 × 18,042,000 − (20,700,000 + 17,242,000 + 16,979,000) = 19,905,000")
        .isEqualTo(19_905_000L);
    assertThat(fiberOf(afterOneYear.actor(), afterOneYear.economy()))
        .as("年末**家户账**那一份（市场在关账日也在搬）：实测 19,114,000")
        .isEqualTo(19_114_000L);
    assertThat(heldByOperator(afterOneYear, WEAVE, EconomyTestWorld.FIBER))
        .as("年末织机 operator 账上的纤维（实测）")
        .isEqualTo(615_000L);
    assertThat(heldByOperator(afterOneYear, CRAFT, EconomyTestWorld.FIBER))
        .as("年末作坊 operator 账上的纤维（实测）")
        .isEqualTo(176_000L);
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
   * 劳动 = M1.8 逐批预算封顶后的名义配额 590,865 ⇒ 590；周期第一天按缺口信号补到 **626,000** ⇒ 626
   * 纤维 = 同格争用配给给纺织 18,793,421 ÷ (每台一周期 30,000) = **626**（与劳动并列最紧）
   * ⇒ 规模 = 626 ⇒ 布净产 = 626 × 30 × 1000 × 0.97 = 18,216,600（另加作坊 31 座 × … = 1,804,200）
   * </pre>
   *
   * <p>★★ <b>M1.8（9521bd00）改变了"三路各不相同"这一形态</b>（如实记）：名义配额 590,865 被 H5 ③ 的缺口重排补到 626,000，与配给纤维同为 626
   * ⇒ 最紧的两路并列。★ 判别力仍在：把纺织产能写成"土地"⇒ 规模 0 ⇒ 布恒 0； 只看织机（740）⇒ 布会多出 3,300,600 毫；不扣纤维投入 ⇒
   * 纤维的逐商品守恒式不平（见端到端用例）。
   */
  @Test
  void weavingScaleComesFromLoomsLaborAndFiberNotFromLand() {
    EconomyData shared = seeded();
    Industry weave = shared.industries().get(WEAVE);

    assertThat(weave.capacityPerUnit())
        .as("★ 纺织的产能锚是**织机**（TOOL），不是土地")
        .containsOnlyKeys(AssetKind.TOOL);
    assertThat(weave.capacity())
        .as("★★ H0.3（K3）：织机是**纺织产业的产能总量**（旧版散在四行里、Σ 才是总数）")
        .containsOnlyKeys(AssetKind.TOOL)
        .doesNotContainKey(AssetKind.LAND);
    // ★★ H0.2：纤维并入**农村四行**（旧版住在"纺织四行"上）—— 那是同一批人的同一本账；
    //   而"纺织那四行一寸土地都没有"这条判据**没有对象了**（那四行已不存在，行上也不再有任何生产资料）。
    long looms = weave.capacity().getOrDefault(AssetKind.TOOL, 0L);
    // ★ H1：纤维从**家户账本**读（行里已经没有 goods 这一栏）。
    ActorData books = seededBooks();
    long fiber = 0L;
    for (CohortKey key : shared.classes().keySet()) {
      if (!key.residence().equals(ResidenceKind.RURAL)) {
        continue;
      }
      fiber += householdGoods(books, key, EconomyTestWorld.FIBER);
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
    // ★★ M1.8（9521bd00）+ H5 ③ 之后的**两条实际路径**（独立复算，不抄实际）：
    //   ① 原配额 = 590,865（逐批预算封顶）；周期第一天按缺口信号从农业未吸收的配额补到 **626,000**
    //      ⇒ 实际劳动那一路 = ⌊626,000 ÷ 1,000⌋ = **626**；
    //   ② 同格争用配给给纺织 18,793,421 纤维 ⇒ 纤维那一路 = ⌊18,793,421 ÷ 30,000⌋ = **626**。
    //   织机那一路仍是 740，**不咬合**；⇒ 收获规模 = 626（劳动/纤维并列最紧，不是"纤维单独最紧"）。
    EconomyOwnershipFixture.Result dayOne =
        EconomyOwnershipFixture.advance(shared, books, MAP_ID, 0L, 1L);
    long quotaLaborCap = weaveLabor(shared) / EconomySeeder.LABOR_MILLI_PER_LOOM;
    long settledLaborCap =
        dayOne.economy().industries().get(WEAVE).cycleLaborMilli()
            / EconomySeeder.LABOR_MILLI_PER_LOOM;
    long rationedFiberCap =
        dayOne
                .economy()
                .industries()
                .get(WEAVE)
                .cycleInputUsedMilli()
                .getOrDefault(EconomyTestWorld.FIBER, 0L)
            / (EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE * EconomySeeder.FIBER_MILLI_PER_CLOTH);
    assertThat(fiberCap).as("★ 改前「只按自己缸」那个口径仍是算术事实（18,600,000 ÷ 30,000）").isEqualTo(620L);
    assertThat(quotaLaborCap).as("M1.8：名义纺织配额 590,865 ⇒ 590 台").isEqualTo(590L);
    assertThat(settledLaborCap)
        .as("★ H5 ③：第 1 天实际累加的纺织劳动 626,000（补了农业未吸收的配额）⇒ 626 台")
        .isEqualTo(626L);
    assertThat(rationedFiberCap).as("★ H6 争用配给给纺织 18,793,421 纤维 ⇒ 626 台").isEqualTo(626L);
    assertThat(Math.min(settledLaborCap, rationedFiberCap))
        .as("两条实际路径并列最紧（626）—— 三路各不相同才证明 min 真的在取的口径已被 M1.8 改变，如实记")
        .isEqualTo(626L)
        .isLessThan(looms);

    EconomyOwnershipFixture.Result afterOneCycle =
        EconomyOwnershipFixture.advance(shared, books, MAP_ID, 0L, EconomySeeder.CYCLE_DAYS);
    // ★★ T4：布的**净产**要两处合读（行里收到 700‰ 的分成 + operator 账上留 300‰）——
    //   `weave@hex|*` 四行人口为 0 ⇒ 布落同格 agriculture 行（I4.3），故只读 weave 行会得到 0。
    assertThat(produced(afterOneCycle, WEAVE, EconomyTestWorld.CLOTH))
        .as(
            "★ 本格第 1 周期产出的布 = 纺织（配给到 18,793,421 纤维 ⇒ 626 台 × 30 × 1000 × 0.97）+ 作坊（1,906,579 ⇒ 31 座）")
        .isEqualTo(
            626L
                    * EconomySeeder.CLOTH_PER_LOOM_PER_CYCLE
                    * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
                    * 970L
                    / 1000L
                + 31L
                    * EconomySeeder.CLOTH_PER_WORKSHOP_PER_CYCLE
                    * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
                    * 970L
                    / 1000L)
        .isEqualTo(18_216_600L + 1_804_200L)
        .isEqualTo(20_020_800L);
    // ★★ **H6-lite 起上面这两个"台/座"数的来历变了**（如实记）：改前织机与作坊各按**自己缸里**的纤维定规模
    //   （620 台 / 35 座，Σ 用 18,600,000 + 2,100,000 = 20,700,000）；H6 起同格争用的纤维**先按需求比例配给**
    //   （18,793,421 / 1,906,579，两条腿之和恰为池子 20,700,000），规模再各自由**配给额**折出：
    //   织机 ⌊18,793,421 ÷ 30,000⌋ = **626**、作坊 ⌊1,906,579 ÷ 60,000⌋ = **31**。
    //   ⇒ 织机多织（620 → 626）、作坊少做（35 → 31），**两边都活** —— 这一条与末尾那条争用用例互为佐证。
    assertThat(fiberCap).as("★ 改前「只按自己缸」那个口径仍是算术事实（18,600,000 ÷ 30,000）").isEqualTo(620L);
  }

  /** 该产业名下全部配额之和（= 结算每天累加进 {@code cycleLaborMilli} 的那个数）。 */
  private static long quotaOf(EconomyData data, IndustryId industry) {
    long sum = 0L;
    for (LaborAllocation allocation : data.allocations().values()) {
      if (allocation.actor().id().equals(industry.value())) {
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
   * 劳动 = 城市那一池的日劳动 ÷ 1000 = 886,356 ÷ 1000 = 886 座（**不是瓶颈**）
   * 原料 = 35 座 × 一个周期的用量（纤维 2,100,000 毫、铁 350,000 毫）⇒ 原料那两路 = 35（与作坊同为瓶颈）
   * 规模 = min(35, 886, 35, 35) = **35**（**作坊**是最紧那一路 —— 那条路上没有土地）
   * ⇒ 布净产 35 × 60 × 1000 × 0.97 = 2,037,000；工具净产 35 × 5 × 1000 × 0.97 = 169,750
   * ⇒ 一年后的布 = 农村 18,042,000 + 城市 2,037,000 = **20,079,000**；工具 = **169,750**
   * </pre>
   *
   * <p>★ 判别力：v1 的手工业"不占地 ⇒ 恒产 0"（spec §一.4 实测的病态）⇒ 本条红。
   */
  @Test
  void theCityWorkshopProducesItsOwnGoodsWithoutAnyLand() {
    EconomyData shared = seeded();
    assertThat(shared.industries()).as("有城的格 = 农业 + 家庭纺织 + 城市作坊").containsKeys(FARM, WEAVE, CRAFT);

    ActorData books = seededBooks();
    // ★★ **口径（H6 收口时实测踩到）：本用例全部读数走**串联推进**（0→120→240→360），不走"整年一次推"。**
    //   实测两条路径在**同一 tick** 上给出不同的流水相位：`advance(shared, books, 0, 240)` 的作坊布入账是 **0**，
    //   而 `advance(0→120)` 再 `advance(120→240)` 是 **115,963**（纤维取材两条路径都取到 1,882,176）。
    //   ⇒ 读数混用两条路径 = 同一份代码在同一 tick 上得到两个数；本用例因此只用一个口径（串联 = 真协调器的日常形态）。
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
    assertThat(workshops).as("★ 城里的作坊数锚（改前它是最紧那一路的最低界，H6 起被纤维配给额取代）").isEqualTo(35L);

    // ★★ **M2 口径重钉（2026-09-27）**：作坊的规模由**市场预买 + H6 争用配给**共同决定，不再逐周期恒 31 座：
    //   第 1 周期纤维配额 1,906,579 ⇒ ⌊1,906,579 ÷ 60,000⌋ = 31 座（布入账 115,963）；
    //   第 2 周期纤维配额 1,874,130 ⇒ 31 座（同值）；
    //   第 3 周期（第 240 天市场之后）：家户池 P₃ = 16,979,000，织机 operator 自有 1,700,449、作坊 operator 自有
    //   162,551；H6 需求 = 织机 min(740, ⌊18,679,449/30,000⌋=622) × 30,000 = 18,660,000 ＋ 作坊
    //   min(35, ⌊17,141,551/60,000⌋=285) × 60,000 = 2,100,000；Σ = 20,760,000 > P₃ ⇒ 按需最大余数配给：
    //   织机 15,261,471、作坊 1,717,529 ⇒ 第 3 周期规模 = ⌊1,717,529 ÷ 60,000⌋ = **28 座**。
    //   ⇒ 关系入账按规模线性缩放：⌊115,963 × 28 ÷ 31⌋ = **104,740**（实测同值）。
    assertThat(weaveClothIncome(afterTwoCycles.economy(), CRAFT))
        .as("★ 作坊**第 2 个周期**的布入账 115,963（纤维配额 1,874,130 ⇒ 31 座）")
        .isEqualTo(115_963L);
    assertThat(weaveClothIncome(afterThreeCycles.economy(), CRAFT))
        .as("★ 第 3 周期（240→360）的作坊**本期流水** = 104,740（纤维配额 1,717,529 ⇒ 28 座，比第 2 周期少 3 座）")
        .isEqualTo(104_740L);
    // ★★ M2 起"全格布存量"必须读**两族账户**（市场只在两族之间搬布）：家户 20,255,103 + 织机 operator
    //   17,512,482 + 作坊 operator 4,787,245 = **42,554,830**；只读家户账会把它读小（旧值 21,054,434 是
    //   M2 之前的家户账读数）。
    assertThat(clothOf(afterThreeCycles.actor(), afterThreeCycles.economy()))
        .as("M2：家户账上的布（市场成交后的读数；实测 20,255,103）")
        .isEqualTo(20_255_103L);
    assertThat(produced(afterThreeCycles, CRAFT, EconomyTestWorld.CLOTH))
        .as("M2：两族账户合计的布存量（与市场成交无关）= 20,255,103 + 17,512,482 + 4,787,245")
        .isEqualTo(42_554_830L);
    assertThat(clothOf(afterThreeCycles.actor(), afterThreeCycles.economy()))
        .as("★ 一年后的布 = 第 1 周期的布 + 后两个周期**持续**织出来的那两份（T0 之后不再停工）")
        .isGreaterThan(clothOf(afterOneCycle.actor(), afterOneCycle.economy()));
    // ★ T4：工具**没有规则付给 cohort** ⇒ 它整份留在**城市作坊 operator** 的账上（行里一件不进）。
    // ★★ **M2 口径重钉（2026-09-27）**：工具账逐周期在涨，但第 3 周期的增幅由 28 座（不是 31 座）决定：
    //   第 1 周期末 70,000（创世）+ 31 × 5 × 1000 × 0.97 − 70,000 = 150,350；
    //   第 2 周期末 150,350 + 31 × 4,850 − 70,000 = **230,700**；
    //   第 3 周期末 230,700 + 28 × 4,850 − 70,000 = **296,500**
    //   （工具投入 70,000 = 产能 35 座 × 每座 2,000 毫，逐周期由作坊自己的工具账出，不随纤维配额缩）。
    long toolsAfterTwoCycles = heldByOperator(afterTwoCycles, CRAFT, EconomyTestWorld.TOOL);
    long toolsAfterThreeCycles = heldByOperator(afterThreeCycles, CRAFT, EconomyTestWorld.TOOL);
    assertThat(toolsAfterThreeCycles)
        .as("★ 工具是城市作坊的第二种产品，且**逐周期还在增**（改前第 2 周期起停工 ⇒ 恒 169,750）")
        .isGreaterThan(toolsAfterTwoCycles);
    assertThat(toolsAfterTwoCycles).as("★ 第 2 个周期末的工具账（串联推进；实测值）").isEqualTo(230_700L);
    assertThat(toolsAfterThreeCycles).as("★ 第 3 个关账日（第 360 天）的工具账（串联推进；实测值）").isEqualTo(296_500L);
    assertThat(toolsOf(afterThreeCycles.actor(), afterThreeCycles.economy()))
        .as("家户账上一件工具都没有（没有规则付给 cohort）")
        .isZero();
    assertThat(toolsOf(books, shared)).as("非平凡：创世时一件工具都没有").isZero();
    // ★★ **H6-lite 起这一段旧叙述已被实测推翻（如实记，留痕不篡改）**：此处原写"作坊第 2 个周期起产量为 0
    //   —— 它的铁只有创世那一份"。实测（本用例上面的三条逐值断言）：作坊第 2/3 周期**照样在产**
    //   （布入账 115,963 / 104,740、工具账逐周期 +80,350 / +65,800）—— 它每周期从市场与池子里拿到料，
    //   并不存在"第 2 周期起停摆"。
  }

  /**
   * ★★ **R4（T0）：纺织**每个周期**都在织 —— 农田把新一期的纤维交给了同格的织机**（R3 遗留的收口）。
   *
   * <pre>
   * 第 1 周期：池 = 农村缸 18,600,000（创世那份）+ 城镇缸 2,100,000
   *           需求 = 织机 min(740, 池÷30,000) × 30,000 = 20,700,000 ＋ 作坊 35 × 60,000 = 2,100,000
   *           Σ需求 22,800,000 > 池 ⇒ 按需求比例：织机 **18,793,421**、作坊 **1,906,579**（Σ == 池）
   *           ⇒ 纤维路 ⌊18,793,421 ÷ 30,000⌋ = **626** ⇒ 布毛产 626 × 30,000 = 20,700,000
   * 第 2 周期：池 = 农田第 120 天收获的纤维**净产** = 3,100 亩 × 6 × 1000 × 0.97 = 18,042,000
   *           需求 = 织机 min(740, 601) × 30,000 = 18,030,000 ＋ 作坊 2,100,000
   *           Σ需求 20,130,000 > 池 ⇒ 织机 **16,159,824**、作坊 **1,882,176**（Σ == 池）
   *           ⇒ 纤维路 ⌊16,159,824 ÷ 30,000⌋ = **538** ⇒ 布毛产 538 × 30,000 = 16,140,000
   * 第 3 周期：第 2 周期的**逐值重复**（同一状态 ⇒ 同一条路径）
   * 作坊：**每个周期都拿到自己那份配给**（31 座 × 60,000 = 1,860,000 上下）⇒ 不再第 2 周期停工
   * </pre>
   *
   * <p>★★ **H6-lite 起上面这套数字是"池子 + 需求比例"的闭式**（改前是"各看自己缸"的 620 / 601 口径，已删）。
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
    // ★ H3（口径变化）：投入改由 relation.inputSupplier 按**产能折出的规模一次取足** ⇒ 本夹具种子够满种，
    //   收获规模 = **产能 3,100 亩**（改前按逐行人口份额分摊、逐行⌊⌋ ⇒ 只到 3,098 亩）
    //   ⇒ 净产 = 3,100 × 6 × 1000 × 0.97 = 18,042,000 毫纤维。★ "缺料则缩产"仍由 economy 的
    //   EconomySowingTest（佃农缸空 ⇒ 0 亩）守着。
    long farmNetFiber =
        3_100L
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

    // ★★ T4/M2：读的是**净产**（家户 + operator 两族账户的库存合计；市场成交只在两族之间搬）—— 见 `produced` 的类注。
    // ★★ **H6-lite 起这条的逐值变了（如实记，并撤回下面那段"没有闭式"的旧叙述）**：同格争用的纤维按需求比例
    //   配给（第 1 周期 18,793,421 / 1,906,579）⇒ 织机 ⌊18,793,421 ÷ 30,000⌋ = **626** 台、
    //   作坊 ⌊1,906,579 ÷ 60,000⌋ = **31** 座 ⇒ 净产 = 626×30×1000×0.97 + 31×60×1000×0.97
    //   = 18,216,600 + 1,804,200 = **20,020,800**（H6 前是 620/35 ⇒ 20,079,000）。
    assertThat(produced(cycle1, WEAVE, EconomyTestWorld.CLOTH))
        .as("第 1 周期：纤维配给 18,793,421（织机 626 台）+ 1,906,579（作坊 31 座）⇒ 净产 20,020,800")
        .isEqualTo(18_216_600L + 1_804_200L)
        .isEqualTo(20_020_800L);
    // ★★ **H6-lite 起这两条也拿到了闭式**（此前如实记为"没有闭式"，见下面的更正）：
    //   第 2/3 周期池子 = 农田净产 18,042,000，按需求（18,030,000 : 2,100,000）比例配给
    //   （16,159,824 / 1,882,176，Σ == 池）⇒ 织机 ⌊16,159,824 ÷ 30,000⌋ = **538** 台、
    //   作坊 ⌊1,882,176 ÷ 60,000⌋ = **31** 座 ⇒ 关系入账 = 538×30×1000×0.70 = **11,298,000**
    //   （作坊 = 31×60×1000×0.70 = **1,302,000**）。★ 实测值见末尾那条争用用例的逐周期取材断言。
    //   ⇒ **改前的两处"未达成/没有闭式"如实记在此处撤回**（口径变了：改前第 2 周期织机只织出约 3.66M，
    //     因为作坊那份纤维是创世一次性的、而下限口径也不开池；H6 起两家每周期都按比例拿到料）。
    // ★★ **度量口径的 M2 收尾更正（如实记）**：`produced` 已改成"两族账户库存合计"（市场不变量，见 helper 的类注）。
    //   历史留痕：旧读法（行侧**本期入账** + operator **存量**）在第 2 周期量到过 `producedBetween = −1,665,975`（非单调），
    //   那是它把 operator 付出去的份额读成"负产出"所致 ⇒ 旧负值不再适用于新读法。
    //   ⇒ brief 判据 ③（织机不停工）仍由**更直接的两条**承担：本周期纺织的**关系入账 > 0**（下面两条），
    //     以及紧随其后的"布库存逐周期增长"（那两条一字未动）。
    assertThat(weaveClothIncome(cycle2.economy(), WEAVE))
        .as("★ 第 2 周期：纺织的**关系入账** > 0 ⇒ 织机没有停工")
        .isPositive();
    assertThat(weaveClothIncome(cycle3.economy(), WEAVE))
        .as("★ 第 3 周期：同样没有停工（第 3 周期是第 2 周期的逐值重复）")
        .isPositive();
    // ★ H3（口径变化，如实记）：投入调拨改由 relation.inputSupplier 按**产能折出的规模一次取足** ⇒
    //   本夹具的种子够满种 ⇒ 农田规模 = **产能 3,100 亩**（改前按"逐行人口份额"分摊、逐行⌊⌋ ⇒ 只到 3,098 亩）。
    //   算式：3,100 × 6 × 1000 × 0.97 = 18,042,000。
    //   ★ "缺料则缩产"那条机构仍由 economy 的 EconomySowingTest（佃农缸空 ⇒ 0 亩）守着。
    assertThat(farmNetFiber).as("农田一个周期的纤维净产（取材量的上限；H3 起 = 满种）").isEqualTo(18_042_000L);
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
   * <p>★★ <b>它修的是什么（实测，不是推测）</b>：本夹具一格里有 **farm + weave + craft** 三个产业（农村 14,806 人 + 一座城 1,777 人），
   * 织机按 relation 取的是**农村**家户的账（农田的纤维副产正落在那里、每周期都有进项），而作坊按 relation 取的是**城镇**家户的账 ——
   * 那份纤维是**创世一次性**的（= 它一个周期的用量），**再没有进项** ⇒ 第 2 个周期起作坊规模 0。 ★ H5
   * 台账里"同格织机把纤维抢走"那句话是**错的**（控制方实测更正）：改前城镇缸由 2,100,000 → 0、农村缸一个周期都没被多取过。
   *
   * <pre>
   * 第 1 周期：池 = 农村 18,600,000 + 城镇 2,100,000 = 20,700,000
   *           需求 = 织机 min(740, 池÷30,000=690) × 30,000 = 20,700,000 ＋ 作坊 35 × 60,000 = 2,100,000
   *           Σ需求 22,800,000 &gt; 池 ⇒ 按需求比例：织机 20,700,000×20.7÷22.8 = **18,793,421**、作坊 **1,906,579**（Σ == 池）
   * 第 2 周期（M2 口径重钉）：第 120 天市场把 800,000 毫纤维从家户池预买进作坊 operator（工资余款 800 毫银 ÷ 纤维价 1）
   *           ⇒ 家户池 P₂ = 18,042,000 − 800,000 = **17,242,000**
   *           需求 = 织机 min(740, 574) × 30,000 = 17,220,000 ＋ 作坊 min(35, (800,000+17,242,000)÷60,000) × 60,000 = 2,100,000
   *           Σ需求 19,320,000 &gt; P₂ ⇒ 按需求比例（最大余数）：织机 **15,367,870**、作坊 **1,874,130**（Σ == P₂）
   * </pre>
   *
   * <p>★★ <b>M2 口径重钉（2026-09-27）</b>：旧 pre block 写"第 2 周期池 = 18,042,000、织机 16,159,824 / 作坊
   * 1,882,176"是 **M2 市场入市之前**的读数；经营主体入市后，第 120 天的预买先把 800,000 移出家户池 ⇒ 两条腿的逐值都必须按 P₂ = 17,242,000
   * 重算（下条断言的算式里写明）。
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

    // ① 作坊不再停工：城镇四行的布入账逐周期为正（改前第 2/3 周期恒 0）。
    assertThat(weaveClothIncome(c2.economy(), CRAFT))
        .as("★ 第 2 周期作坊**产出了布**（改前这一条恒 0 —— 纤维那一路断在「城镇缸没有进项」）")
        .isPositive();
    assertThat(weaveClothIncome(c3.economy(), CRAFT)).as("★ 第 3 周期同样在产（不是「只多撑一个周期」）").isPositive();
    // ② 织机照旧在织（争用没有把老的产业挤死）。
    assertThat(weaveClothIncome(c2.economy(), WEAVE))
        .as("★ 第 2 周期织机照旧产出（配给 = 两家都活，不是「保作坊、饿织机」）")
        .isPositive();

    // ③ ★★ 逐值：两条腿**按需求比例**分池，Σ == 池（周期第一天扣料；关账日清零 ⇒ 读周期的第 119 天）。
    assertThat(inputsAt(shared, books0, 0L, 119L, WEAVE).get(FIBER_ID))
        .as("第 1 周期织机拿到的纤维 = 20,700,000 × 20,700,000 ÷ 22,800,000")
        .isEqualTo(18_793_421L);
    assertThat(inputsAt(shared, books0, 0L, 119L, CRAFT).get(FIBER_ID))
        .as("第 1 周期作坊拿到的纤维 = 20,700,000 × 2,100,000 ÷ 22,800,000")
        .isEqualTo(1_906_579L);
    assertThat(inputsAt(c1.economy(), c1.actor(), 120L, 239L, WEAVE).get(FIBER_ID))
        .as("第 2 周期织机 = 17,242,000 × 17,220,000 ÷ 19,320,000 ⇒ 最大余数补 1")
        .isEqualTo(15_367_870L);
    assertThat(inputsAt(c1.economy(), c1.actor(), 120L, 239L, CRAFT).get(FIBER_ID))
        .as("第 2 周期作坊 = P₂ − 织机配给（P₂ = 18,042,000 − 市场预买的 800,000）")
        .isEqualTo(1_874_130L);
    assertThat(18_793_421L + 1_906_579L)
        .as("★ 守恒：两条腿之和 == 第 1 周期的池（农村 18,600,000 + 城镇 2,100,000）—— 池里一滴都不剩")
        .isEqualTo(18_600_000L + 2_100_000L);
    assertThat(15_367_870L + 1_874_130L)
        .as(
            "★ 守恒：第 2 周期两条腿之和 == 家户池 P₂ = 18,042,000 − 800,000（市场预买的那 800,000 在第 120 天已落到作坊 operator 账）")
        .isEqualTo(18_042_000L - 800_000L);

    // ④ ★ "无争用 ⇒ 逐值不变"的另一半：种子只有农业要（从不争用）⇒ 满种那一条一个字没变。
    assertThat(inputsAt(shared, books0, 0L, 119L, FARM).get(EconomyTestWorld.GRAIN))
        .as("★ 农业的种子从不争用 ⇒ 口径不变（3,100 亩 × 8,000 毫/亩）")
        .isEqualTo(24_800_000L);
  }

  /** 商品 id：纤维（本判据的主角）。 */
  private static final CommodityId FIBER_ID = EconomyTestWorld.FIBER;

  /** 从 {@code from} 推到 {@code to}，读该产业本周期**实际扣到的料**（{@code cycleInputUsedMilli}）。 */
  private static Map<CommodityId, Long> inputsAt(
      EconomyData data, ActorData books, long from, long to, IndustryId industry) {
    return EconomyOwnershipFixture.advance(data, books, MAP_ID, from, to)
        .economy()
        .industries()
        .get(industry)
        .cycleInputUsedMilli();
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
    EconomyData data = result.economy();
    String hex = IndustryHexKeys.hexKeyOf(industry).orElseThrow();
    long held = 0L;
    for (CohortKey key : data.classes().keySet()) {
      if (!IndustryHexKeys.hexKey(key.hex().q(), key.hex().r()).equals(hex)) {
        continue;
      }
      io.mosire.simos.actor.model.GoodsAccount account =
          result.actor().accounts().get(OwnershipBooks.accountKeyOf(key));
      held += account == null ? 0L : account.balances().getOrDefault(commodity, 0L);
    }
    // ★ 本格**每一个**经营主体账上留的那一份都要算进来（同格可能有农业 + 纺织 + 作坊三个 operator）。
    for (Industry industry2 : data.industries().values()) {
      if (!IndustryHexKeys.hexKeyOf(industry2.id()).filter(hex::equals).isPresent()) {
        continue;
      }
      io.mosire.simos.actor.model.GoodsAccount account =
          result
              .actor()
              .accounts()
              .get(
                  new io.mosire.simos.actor.model.GoodsAccountKey(
                      industry2.operator(), io.mosire.simos.map.hex.HexCoord.parse(hex)));
      held += account == null ? 0L : account.balances().getOrDefault(commodity, 0L);
    }
    return held;
  }

  /** ★ <b>相邻两个时点之间</b>该格**产出的某商品**（行侧取流水差 —— 它本来就是本期口径；operator 侧取**账本差** —— 它是存量，跨周期累积）。 */
  private static long producedBetween(
      EconomyOwnershipFixture.Result before,
      EconomyOwnershipFixture.Result after,
      IndustryId industry,
      CommodityId commodity) {
    return produced(after, industry, commodity) - produced(before, industry, commodity);
  }

  /** 该格的 {@code operator} 账上某商品的余额（T4 起产出的落点）。 */
  private static long heldByOperator(
      EconomyOwnershipFixture.Result result, IndustryId industry, CommodityId commodity) {
    EconomyData data = result.economy();
    String hex = IndustryHexKeys.hexKeyOf(industry).orElseThrow();
    io.mosire.simos.actor.model.GoodsAccount account =
        result
            .actor()
            .accounts()
            .get(
                new io.mosire.simos.actor.model.GoodsAccountKey(
                    data.industries().get(industry).operator(),
                    io.mosire.simos.map.hex.HexCoord.parse(hex)));
    return account == null ? 0L : account.balances().getOrDefault(commodity, 0L);
  }

  /**
   * 某产业对应的那组家户本周期**布**的入账（流水所得里布那一维）。
   *
   * <p>★ H0.2：行里没有产业 ⇒ 按**该产业所在格的居住类型**取（农村家户既种地又织布 ⇒ 农业与纺织读的是同一组四行）。
   */
  private static long weaveClothIncome(EconomyData data, IndustryId industry) {
    String hex = IndustryHexKeys.hexKeyOf(industry).orElseThrow();
    int q = Integer.parseInt(hex.substring(0, hex.indexOf('_')));
    int r = Integer.parseInt(hex.substring(hex.indexOf('_') + 1));
    ResidenceKind residence =
        industry.value().startsWith(EconomySeeder.CRAFT + "@")
            ? ResidenceKind.URBAN
            : ResidenceKind.RURAL;
    long total = 0L;
    for (Map.Entry<CohortKey, ClassRow> entry : data.classes().entrySet()) {
      if (!entry.getKey().hex().equals(new HexCoord(q, r))
          || !entry.getKey().residence().equals(residence)) {
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
    return data.classes().keySet().stream()
        .mapToLong(key -> householdGoods(books, key, commodity))
        .sum();
  }

  private static long clothOf(ActorData books, EconomyData data) {
    return goodsOf(books, data, EconomyTestWorld.CLOTH);
  }

  /** 某个家户账上某商品的余额（H1）：账户键经 {@link OwnershipBooks#accountKeyOf} 拼（不复述格式）；缺席 ⇒ 0。 */
  private static long householdGoods(ActorData books, CohortKey key, CommodityId commodity) {
    GoodsAccount account = books.accounts().get(OwnershipBooks.accountKeyOf(key));
    return account == null ? 0L : account.balances().getOrDefault(commodity, 0L);
  }

  /**
   * 某**居住类型那组家户**的 Σ 某商品库存（H0.2：行里没有产业 ⇒ 改按 (格, 居住类型) 取； 农村行同时是"农业的行"与"纺织的行" ⇒
   * 农业与纺织共用一本账，读数也只有一个落点）。
   */
  private static long goodsOf(
      ActorData books, EconomyData data, ResidenceKind residence, CommodityId commodity) {
    return data.classes().keySet().stream()
        .filter(key -> key.residence().equals(residence))
        .mapToLong(key -> householdGoods(books, key, commodity))
        .sum();
  }

  private static long toolsOf(ActorData books, EconomyData data) {
    return goodsOf(books, data, EconomyTestWorld.TOOL);
  }

  private static long fiberOf(ActorData books, EconomyData data) {
    return goodsOf(books, data, EconomyTestWorld.FIBER);
  }

  /** 该格**农村那一池**（农村四行）的当日劳动（改口径前那条算式：Σ 行 laborMilli × 槽位投入率 ÷ 1000）。 */
  private static long ruralDailyLabor(EconomyData data) {
    long sum = 0L;
    for (Map.Entry<CohortKey, ClassRow> entry : data.classes().entrySet()) {
      if (entry.getKey().residence().equals(ResidenceKind.RURAL)) {
        sum += entry.getValue().laborMilli() * entry.getValue().participationPerMille() / 1000L;
      }
    }
    return sum;
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
