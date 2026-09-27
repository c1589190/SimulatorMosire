package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ **R2（T3）：当日劳动的唯一来源 = 劳动分配表**（第三阶段设计稿 §四）。
 *
 * <p>★★ **本类的夹具刻意让"两个数"分开**（这是本类存在的全部理由）：产业的行里有自己的 {@code laborMilli ×
 * participationPerMille}（那是**阶层之间**怎么分的依据），而配额表说的是**这个产业总共投了多少**。
 * 改口径之前两者在真档逐值相等，夹具若也相等，那么"**没改**、还在用行算"的实现照样绿（假绿）。 故本类的行值（{@code 58,000} / {@code
 * 5,800}）与配额值（{@code 40,000} / {@code 65,000}）**刻意不同** ⇒ 谁没把 {@code laborToday} 接到配额表上，逐值断言**当场红**。
 *
 * <pre>
 * 批次 A（农村，可用 100,000）：产业 farm 40,000 + 产业 craft 10,000 + 家户 20,000   ← 同一批次三条配额
 * 批次 B（城镇，可用  60,000）：产业 craft 55,000
 * ⇒ farm  当日劳动 = 40,000（行算会给 58,000 ⇒ 红）
 * ⇒ craft 当日劳动 = 10,000 + 55,000 = 65,000（行算会给 5,800 ⇒ 红；只取"每批次第一条配额"会给 55,000 ⇒ 红）
 * ⇒ 家户 20,000 **不进任何产业**（本轮不产布，但它照进守恒与读口）
 * </pre>
 */
class EconomyLaborAllocationTest {

  /** 本夹具的格（H0：家户键 = 格 + 居住类型 + 阶层；这里只有一格）。 */
  private static final HexCoord HEX = new HexCoord(0, 0);

  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final IndustryId CRAFT = new IndustryId("craft@0_0");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");

  private static final PeopleLotId RURAL = new PeopleLotId("rural:0_0:MALE:1");
  private static final PeopleLotId URBAN = new PeopleLotId("urban:c-0_0:FEMALE:1");
  private static final String HOUSEHOLD = "0_0";

  private static final LaborAllocationId A_FARM = new LaborAllocationId("alloc-a-farm");
  private static final LaborAllocationId A_CRAFT = new LaborAllocationId("alloc-a-craft");
  private static final LaborAllocationId A_HOUSEHOLD = new LaborAllocationId("alloc-a-household");
  private static final LaborAllocationId B_CRAFT = new LaborAllocationId("alloc-b-craft");

  private static final long FIRST_PERIOD = 1L;

  /**
   * ★★ **当日劳动 = 该产业名下全部配额之和**（逐值，手算见类注）。
   *
   * <p>★ 判别力（三条，各挡一种坏实现）：
   *
   * <ul>
   *   <li>"还从行算"（{@code Σ 行 laborMilli × 投入率}）⇒ farm 读到 58,000、craft 读到 5,800 ⇒ 红；
   *   <li>"每批次只取第一条配额" ⇒ craft 读到 55,000（漏掉 A 给 craft 的那 10,000）⇒ 红；
   *   <li>"家户的配额也算进某个产业" ⇒ 有产业的数会多出 20,000 ⇒ 红。
   * </ul>
   */
  @Test
  void dailyLaborComesFromTheAllocationsNotFromTheClassRows() {
    EconomyFixtures.World world = fixture();
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);

    assertThat(next.industries().get(FARM).cycleLaborMilli())
        .as("farm 的配额之和 = 40,000（**不是**行算的 58,000）")
        .isEqualTo(40_000L);
    assertThat(next.industries().get(CRAFT).cycleLaborMilli())
        .as("craft 的配额之和 = 10,000（批次 A）+ 55,000（批次 B）—— '每批次只看第一条' 会读到 55,000")
        .isEqualTo(65_000L);
  }

  /** ★ 多日推进：配额是**每天**的投入量 ⇒ N 天的累计 = N × 当日（与改口径前的口径一字不差）。 */
  @Test
  void theDailyQuotaAccumulatesOncePerSettledDay() {
    EconomyFixtures.World world = fixture();
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 3L);

    assertThat(next.industries().get(FARM).cycleLaborMilli())
        .as("3 天 × 40,000")
        .isEqualTo(120_000L);
    assertThat(next.industries().get(CRAFT).cycleLaborMilli())
        .as("3 天 × 65,000")
        .isEqualTo(195_000L);
  }

  /** ★ **配额表不被结算改动**（它是命令层发的）：结算前后逐值相同（守恒之外的一条"只读"判据）。 */
  @Test
  void settlementDoesNotTouchTheAllocationTable() {
    EconomyFixtures.World world = fixture();
    EconomyData base = world.data();
    EconomyData next = EconomyFixtures.advance(base, world.goods(), 0L, 2L);

    assertThat(next.allocations()).as("配额原样带过").isEqualTo(base.allocations());
    assertThat(next.laborSupply()).as("供给原样带过").isEqualTo(base.laborSupply());
  }

  /**
   * ★ **家户的配额不喂任何产业**：把它挪到另一个主体名下，"有产业的当日劳动"不变、但**守恒式的左边**跟着变 （构造期守卫看的是全表 ⇒ 少算它就会放行一份重复配额）。
   *
   * <p>判别力：把过滤写成"凡配额都算进某个产业"（例如拿 actor 的 kind 之外的字段瞎猜）⇒ 本条与上一条一起红。
   */
  @Test
  void householdQuotasDoNotFeedAnyIndustry() {
    EconomyFixtures.World world = fixture();
    EconomyData next = EconomyFixtures.advance(world.data(), world.goods(), 0L, 1L);

    long industryLabor =
        next.industries().get(FARM).cycleLaborMilli()
            + next.industries().get(CRAFT).cycleLaborMilli();
    long allocatedTotal =
        next.allocations().values().stream().mapToLong(LaborAllocation::laborMilli).sum();

    assertThat(industryLabor).as("两个产业的投入 105,000").isEqualTo(105_000L);
    assertThat(allocatedTotal).as("配额总量 125,000（含家户的 20,000）").isEqualTo(125_000L);
    assertThat(allocatedTotal - industryLabor).as("差 = 家户那一条").isEqualTo(20_000L);
  }

  // ── 夹具 ───────────────────────────────────────────────────────────────────────────

  /**
   * 一格两产业 + 两批次 + 一条家户配额（见类注的算式）。
   *
   * <p>★ 行里的 {@code laborMilli} 刻意取"行算与配额不同"的值：farm 的行 = 58,000（贫农 100 人 × 580‰ × 1000‰）， craft 的行
   * = 5,800。它们现在**只喂分配权重**（"产出在阶层之间怎么分"），不再是"这个产业投了多少劳动"。
   */
  private static EconomyFixtures.World fixture() {
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    industries.put(FARM, industry(FARM, "农业", "feudal", new AllocationRule.Split(700, 300)));
    industries.put(CRAFT, industry(CRAFT, "手工业", "handicraft", new AllocationRule.Split(400, 600)));

    Map<CohortKey, ClassRow> classes = new LinkedHashMap<>();
    classes.put(
        new CohortKey(HEX, ResidenceKind.RURAL, PEASANT),
        row(new CohortKey(HEX, ResidenceKind.RURAL, PEASANT), 100L, 58_000L, 1000));
    classes.put(
        new CohortKey(HEX, ResidenceKind.URBAN, LANDLORD),
        row(new CohortKey(HEX, ResidenceKind.URBAN, LANDLORD), 10L, 5_800L, 1000));

    Map<PeopleLotId, LaborSupply> supply = new LinkedHashMap<>();
    supply.put(RURAL, new LaborSupply(RURAL, FIRST_PERIOD, 100_000L, 0L, 0L));
    supply.put(URBAN, new LaborSupply(URBAN, FIRST_PERIOD, 60_000L, 0L, 0L));

    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
    allocations.put(A_FARM, allocation(A_FARM, RURAL, FARM, ActorKind.ESTATE, "farm", 40_000L));
    allocations.put(
        A_CRAFT, allocation(A_CRAFT, RURAL, CRAFT, ActorKind.WORKSHOP, "craft", 10_000L));
    allocations.put(
        A_HOUSEHOLD,
        new LaborAllocation(
            A_HOUSEHOLD,
            RURAL,
            new ActorRef(ActorKind.HOUSEHOLD, HOUSEHOLD),
            "weaving",
            20_000L,
            FIRST_PERIOD));
    allocations.put(
        B_CRAFT, allocation(B_CRAFT, URBAN, CRAFT, ActorKind.WORKSHOP, "craft", 55_000L));

    // ★★ H1（K1）：家户的商品库存在**会话工作副本**里 —— 本夹具不量库存，但两个家户都有人口
    //   ⇒ 必须各给一张（空）账，否则日结算的 fail-closed 守卫当场抛（"有人口却没有账"）。
    Map<CohortKey, Map<CommodityId, Long>> goods = EconomyFixtures.householdGoods();
    EconomyFixtures.hold(
        goods, new CohortKey(HEX, ResidenceKind.RURAL, PEASANT), EconomySettlement.GRAIN, 0L);
    EconomyFixtures.hold(
        goods, new CohortKey(HEX, ResidenceKind.URBAN, LANDLORD), EconomySettlement.GRAIN, 0L);
    return new EconomyFixtures.World(
        new EconomyData(
            Optional.of(
                new EconomyMeta("m1", 0L, OptionalLong.empty(), "aggregate-v1", Optional.empty())),
            industries,
            classes,
            Map.of(),
            Map.of(),
            supply,
            allocations,
            Map.of(),
            Map.of(),
            Map.of()), // ★ T2：生产关系表（本文件只谈劳动配额 ⇒ 空表）
        goods);
  }

  private static LaborAllocation allocation(
      LaborAllocationId id,
      PeopleLotId group,
      IndustryId industry,
      ActorKind kind,
      String activity,
      long laborMilli) {
    return new LaborAllocation(
        id, group, new ActorRef(kind, industry.value()), activity, laborMilli, FIRST_PERIOD);
  }

  private static ClassRow row(CohortKey key, long population, long laborMilli, int participation) {
    return new ClassRow(
        key,
        population,
        laborMilli,
        participation, // ★ 不占地：本类只谈"投入了多少劳动"，不谈产出
        0L,
        List.of(),
        Map.of(),
        Map.of(),
        0L);
  }

  private static Industry industry(IndustryId id, String name, String regime, AllocationRule rule) {
    return new Industry(
        id,
        name,
        new RegimeId(regime),
        120L,
        0L,
        // ★ R3：产能锚非空即可（本类只谈"投入了多少劳动"，规模与产出都不是判据）。
        Map.of(AssetKind.LAND, 1_000L),
        // ★★ K3：本格该产业的产能总量（改前 = Σ各行的 meansOfProduction）
        Map.of(AssetKind.LAND, 0L),
        Map.of(),
        0L,
        0L,
        Map.of(),
        Map.of(),
        List.of(new ClassSlot(PEASANT, "贫农", 1000), new ClassSlot(LANDLORD, "地主", 1000)),
        rule,
        0L,
        Map.of(),
        // ★ 通用夹具的 operator = **派生**，按**它自己的 regime 参数**（两个调用点分别传 `feudal` / `handicraft`，
        //   两档都已登记；本类只谈劳动，故不写字面量）。
        RegimeOperators.defaultOperator(new RegimeId(regime), id));
  }
}
