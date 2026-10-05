package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.testing.SocialHouseholdFixture;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.calendar.CalendarDate;
import io.mosire.simos.calendar.JulianCalendar;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;

/**
 * {@link RegionAllocations} 两份瀑布（收尾期 T2）：家户账瀑布 + 人力瀑布。
 *
 * <p>★ 断言逐值：来源表**顺序与每户扣减额**、可支配合计、过滤掉的三类来源（非 HOUSEHOLD / 区外 / available ≤ 0）、人力口径（MALE + 当前 tick
 * 现算的 ADULT 档）、count 降序 / id 升序、扣后 count 可为 0、不足 ⇒ 整条拒并带 requested / available / 缺口。
 *
 * <p>★ 排序全序用 **6 个同量键**钉住（AGENTS.md：3 键用 {@code Map.copyOf} 的假绿率过高，4~6 键才咬得住）； 每户插入序刻意与期望序相反 ⇒ 实现若拿
 * Map 迭代序当瀑布序，键序断言当场红。
 */
class RegionAllocationsTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final Map<PeopleLotId, HexCoord> GROUP_LOCATIONS = new LinkedHashMap<>();

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H_OUT = new HexCoord(9, 9);

  private static final CommodityId GRAIN = new CommodityId("grain");

  private static final Region REGION =
      Region.of(
          new RegionId("r-alloc"), "分摊区", Set.of(H11, H12), new RegionMeta(null, null, null, null));

  /** 家户账瀑布：可支配 = {@link AvailableStock#available(GoodsAccount, CommodityId)}（唯一算法的对应重载）。 */
  private static final ToLongFunction<GoodsAccount> GRAIN_AVAILABLE =
      account -> AvailableStock.available(account, GRAIN);

  // ── 家户账瀑布 ───────────────────────────────────────────────────────────────────────

  /**
   * ★ 过滤三类不合资格来源（P2-A 口径：账户主体只有家户）：Social 里查不到的家户 / 区外 / available ≤ 0；
   * 合格来源的 available 合计**只算被留下的那些**。
   */
  @Test
  void householdWaterfallFiltersUnknownRegionAndNonPositiveAvailable() {
    ActorData actors =
        ActorData.empty()
            .withAccount(account(household("h-in"), H11, 50L, 0L))
            .withAccount(account(household("h-dangling"), H11, 100L, 0L))
            .withAccount(account(household("h-out"), H_OUT, 100L, 0L))
            .withAccount(account(household("h-zero"), H11, 10L, 10L));
    SocialData social =
        accountSocial(
            Map.of(
                "h-in", H11,
                "h-out", H_OUT,
                "h-zero", H11));

    RegionAllocations.AccountAllocation result =
        RegionAllocations.allocateAccounts(actors, social, REGION, "粮", 50L, GRAIN_AVAILABLE);

    assertThat(result.requested()).isEqualTo(50L);
    assertThat(result.available())
        .as("只有 h-in 合格 ⇒ 合计 50（悬空家户 / 区外 / 可用 0 都不得进合计）")
        .isEqualTo(50L);
    assertThat(result.sources())
        .as("过滤后只剩 h-in，逐户扣减 = 请求量（它够）")
        .containsExactly(new RegionAllocations.AccountSource(household("h-in"), H11, 50L));
  }

  /**
   * ★ 排序全序 + 逐户扣满：6 户同量并列（available = 50/50/30/30/10/10，插入序与期望序相反）。
   *
   * <p>三个请求量一起看：180（全瀑布，逐值钉顺序与每户扣额）、100（只够前两户，第三户不进来源表）、90（第二户被**部分**扣 40）。
   */
  @Test
  void householdWaterfallOrdersByAvailableDescendingThenKeyAscendingAndTakesPerAccount() {
    ActorData actors = ActorData.empty();
    // 插入序 = h6, h5, …, h1：与期望的键升序完全相反。
    for (int i = 6; i >= 1; i--) {
      actors = actors.withAccount(account(household("h" + i), H11, availableById(i), 0L));
    }

    RegionAllocations.AccountAllocation full =
        RegionAllocations.allocateAccounts(actors, accountSocialFromAccounts(actors), REGION, "粮", 180L, GRAIN_AVAILABLE);
    assertThat(full.sources())
        .as("available 降序、同量按账键（owner id）升序；插入序是反的")
        .containsExactly(
            new RegionAllocations.AccountSource(household("h1"), H11, 50L),
            new RegionAllocations.AccountSource(household("h2"), H11, 50L),
            new RegionAllocations.AccountSource(household("h3"), H11, 30L),
            new RegionAllocations.AccountSource(household("h4"), H11, 30L),
            new RegionAllocations.AccountSource(household("h5"), H11, 10L),
            new RegionAllocations.AccountSource(household("h6"), H11, 10L));
    assertThat(full.available()).as("合计 = 全部合格来源之和").isEqualTo(180L);
    assertThat(full.requested()).isEqualTo(180L);

    RegionAllocations.AccountAllocation two =
        RegionAllocations.allocateAccounts(actors, accountSocialFromAccounts(actors), REGION, "粮", 100L, GRAIN_AVAILABLE);
    assertThat(two.sources())
        .as("逐户扣满：前两户各扣 50 后就够了，第三户不产生来源条目")
        .containsExactly(
            new RegionAllocations.AccountSource(household("h1"), H11, 50L),
            new RegionAllocations.AccountSource(household("h2"), H11, 50L));
    assertThat(two.available()).as("available 报的是**来源总量**（不是请求量）").isEqualTo(180L);

    RegionAllocations.AccountAllocation partial =
        RegionAllocations.allocateAccounts(actors, accountSocialFromAccounts(actors), REGION, "粮", 90L, GRAIN_AVAILABLE);
    assertThat(partial.sources())
        .as("第二户被部分扣 40（逐户扣满，不按比例）")
        .containsExactly(
            new RegionAllocations.AccountSource(household("h1"), H11, 50L),
            new RegionAllocations.AccountSource(household("h2"), H11, 40L));
  }

  /** ★ 不足 ⇒ **整条拒**，拒因带 requested / available / 缺口；边界上差 1 也拒。 */
  @Test
  void householdWaterfallRejectsWholeRequestWhenTotalIsInsufficient() {
    ActorData actors =
        ActorData.empty()
            .withAccount(account(household("h1"), H11, 100L, 0L))
            .withAccount(account(household("h2"), H12, 80L, 0L));

    assertThatThrownBy(
            () -> RegionAllocations.allocateAccounts(actors, accountSocialFromAccounts(actors), REGION, "粮", 1000L, GRAIN_AVAILABLE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("粮总量不足")
        .hasMessageContaining("requested=1000")
        .hasMessageContaining("available=180")
        .hasMessageContaining("缺口=820");

    assertThatThrownBy(
            () -> RegionAllocations.allocateAccounts(actors, accountSocialFromAccounts(actors), REGION, "粮", 181L, GRAIN_AVAILABLE))
        .as("只差 1 也整条拒（不是截断成 180）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("缺口=1");
  }

  /** ★ requested = 0 ⇒ 空结果，且**不扫描来源**（availableOf 的调用计数为 0）。 */
  @Test
  void householdWaterfallSkipsScanningWhenRequestedIsZero() {
    long[] scans = {0L};
    ToLongFunction<GoodsAccount> counting =
        account -> {
          scans[0]++;
          return AvailableStock.available(account, GRAIN);
        };
    ActorData actors = ActorData.empty().withAccount(account(household("h1"), H11, 50L, 0L));

    RegionAllocations.AccountAllocation result =
        RegionAllocations.allocateAccounts(actors, accountSocialFromAccounts(actors), REGION, "粮", 0L, counting);

    assertThat(result.requested()).isZero();
    assertThat(result.available()).as("0 = 未求值（不是『恰好没有来源』）").isZero();
    assertThat(result.sources()).isEmpty();
    assertThat(scans[0]).as("requested=0 必须整段跳过：一次都不该摸来源").isZero();
  }

  @Test
  void householdWaterfallRejectsNegativeRequested() {
    ActorData actors = ActorData.empty();
    assertThatThrownBy(
            () -> RegionAllocations.allocateAccounts(actors, accountSocialFromAccounts(actors), REGION, "粮", -1L, GRAIN_AVAILABLE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested 不得为负");
  }

  // ── 人力瀑布 ─────────────────────────────────────────────────────────────────────────

  /** ★ MALE + ADULT + 区内 + count > 0：女性 / 未成年 / 老年 / 区外 / 空批都不得进来源表。 */
  @Test
  void manpowerWaterfallFiltersSexBracketRegionAndNonPositiveCount() {
    SocialData social =
        social(
            group("g-adult", H11, Sex.MALE, 30L, 20L * 365L, 0L),
            group("g-female", H11, Sex.FEMALE, 1000L, 20L * 365L, 0L),
            group("g-child", H11, Sex.MALE, 100L, 10L * 365L, 0L),
            group("g-elder", H11, Sex.MALE, 100L, 70L * 365L, 0L),
            group("g-out", H_OUT, Sex.MALE, 1000L, 20L * 365L, 0L),
            group("g-zero", H11, Sex.MALE, 0L, 20L * 365L, 0L));

    RegionAllocations.ManpowerAllocation result =
        RegionAllocations.allocateManpower(social, REGION, 0L, 30L, CalendarClock.julianDefault());

    assertThat(result.requested()).isEqualTo(30L);
    assertThat(result.available()).as("只有 g-adult 合格 ⇒ 合计 30").isEqualTo(30L);
    RegionAllocations.GroupSource source = result.sources().get(0);
    assertThat(source.group().id().value()).isEqualTo("g-adult");
    assertThat(source.taken()).as("抽满：整批 30 人被抽走").isEqualTo(30L);
    assertThat(source.countAfter()).as("扣后 count 可为 0（合法空批）").isZero();
  }

  /**
   * ★ 年龄边界**当前 tick 现算，且 15/60 是整历法年**（不是 15×365 天）：默认锚点下 tick 1 = 1445-01-02， 当天满 15/60
   * 岁者恰入档；晚一天出生者要到 tick 2 = 01-03 才跨档。
   *
   * <p>判别力：夹具若继续按 365 天近似写（15×365 天），在儒略历闰日累计下会错开一天 ⇒ 本用例的来源表逐值红。
   */
  @Test
  void manpowerAgeBracketsAreComputedAtTheCurrentTickInWholeCalendarYears() {
    CalendarClock clock = CalendarClock.julianDefault();
    JulianCalendar julian = JulianCalendar.INSTANCE;
    long anchorDay = clock.dayNumberOfTick(0L); // 儒略 1445-01-01
    // 生日 = 1445-01-02 往回 15 / 60 个历法年 ⇒ tick 1 当天满 15 / 60 岁。
    long birthday15OnTick1 = julian.dayNumberOf(new CalendarDate(1430, 1, 2));
    long birthday60OnTick1 = julian.dayNumberOf(new CalendarDate(1385, 1, 2));
    // 晚一天出生：tick 1 = 01-02 未过生日，tick 2 = 01-03 才满 15 / 60 岁。
    long birthday15OnTick2 = julian.dayNumberOf(new CalendarDate(1430, 1, 3));
    long birthday60OnTick2 = julian.dayNumberOf(new CalendarDate(1385, 1, 3));

    SocialData social =
        social(
            group("g15", H11, Sex.MALE, 20L, anchorDay - birthday15OnTick1, 0L),
            group("g60", H11, Sex.MALE, 1L, anchorDay - birthday60OnTick1, 0L),
            group("g-just15", H11, Sex.MALE, 5L, anchorDay - birthday15OnTick2, 0L),
            group("g-just60", H11, Sex.MALE, 10L, anchorDay - birthday60OnTick2, 0L));

    RegionAllocations.ManpowerAllocation atTick1 =
        RegionAllocations.allocateManpower(social, REGION, 1L, 30L, clock);
    assertThat(atTick1.available())
        .as("tick 1（1445-01-02）：g15（20）+ g-just60（10，59 岁）= 30")
        .isEqualTo(30L);
    assertThat(atTick1.sources())
        .as("tick 1：g15 恰满 15 入 ADULT；g-just60 59 岁仍在 ADULT；g-just15 仍 14、g60 已 60 都不入")
        .extracting(source -> source.group().id().value())
        .containsExactly("g15", "g-just60");

    RegionAllocations.ManpowerAllocation atTick2 =
        RegionAllocations.allocateManpower(social, REGION, 2L, 25L, clock);
    assertThat(atTick2.available())
        .as("tick 2（1445-01-03）：g15（20）+ g-just15（5，满 15）= 25")
        .isEqualTo(25L);
    assertThat(atTick2.sources())
        .as("tick 2：g-just15 满 15 进 ADULT、g-just60 满 60 退 ADULT、g60 仍不入")
        .extracting(source -> source.group().id().value())
        .containsExactly("g15", "g-just15");
  }

  /** ★ 排序全序 + 扣后可为 0：6 个批次 count 降序 / 同量 id 升序（插入序相反），请求恰等于第一批 ⇒ 该批扣后为 0。 */
  @Test
  void manpowerWaterfallOrdersByCountDescendingThenIdAscendingAndCanDrainToZero() {
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    for (int i = 6; i >= 1; i--) {
      groups.put(lot("g" + i), group("g" + i, H11, Sex.MALE, countById(i), 20L * 365L, 0L));
    }
    SocialData social = social(groups);

    RegionAllocations.ManpowerAllocation full =
        RegionAllocations.allocateManpower(social, REGION, 0L, 180L, CalendarClock.julianDefault());
    assertThat(full.sources())
        .as("count 降序、同量 id 升序；插入序是反的")
        .extracting(source -> source.group().id().value())
        .containsExactly("g1", "g2", "g3", "g4", "g5", "g6");
    assertThat(full.sources())
        .extracting(RegionAllocations.GroupSource::taken)
        .containsExactly(50L, 50L, 30L, 30L, 10L, 10L);
    assertThat(full.available()).isEqualTo(180L);

    RegionAllocations.ManpowerAllocation firstOnly =
        RegionAllocations.allocateManpower(social, REGION, 0L, 50L, CalendarClock.julianDefault());
    assertThat(firstOnly.sources()).hasSize(1);
    assertThat(firstOnly.sources().get(0).group().id().value()).isEqualTo("g1");
    assertThat(firstOnly.sources().get(0).taken()).isEqualTo(50L);
    assertThat(firstOnly.sources().get(0).countAfter()).as("整批抽空 ⇒ 扣后 count = 0").isZero();
  }

  /** ★ 不足 ⇒ 整条拒，拒因带 requested / available / 缺口。 */
  @Test
  void manpowerWaterfallRejectsWholeRequestWhenInsufficient() {
    SocialData social =
        social(
            group("g1", H11, Sex.MALE, 30L, 20L * 365L, 0L),
            group("g2", H12, Sex.MALE, 10L, 20L * 365L, 0L));

    assertThatThrownBy(
            () ->
                RegionAllocations.allocateManpower(
                    social, REGION, 0L, 100L, CalendarClock.julianDefault()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人力总量不足")
        .hasMessageContaining("requested=100")
        .hasMessageContaining("available=40")
        .hasMessageContaining("缺口=60");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static ActorRef household(String id) {
    return new ActorRef(ActorKind.HOUSEHOLD, id);
  }

  /** 账本所在格的工作记录（P2-A 后账户键不带格；格只进 SocialData，供瀑布按区筛）。 */
  private static final Map<HouseholdId, HexCoord> ACCOUNT_LOCATIONS = new LinkedHashMap<>();

  /** 一本家户账：只带 grain 余额与 grain 冻结额（本类只测商品一维的分摊）。 */
  private static GoodsAccount account(ActorRef owner, HexCoord at, long grain, long frozenGrain) {
    HouseholdId household = HouseholdActors.householdOf(owner);
    ACCOUNT_LOCATIONS.put(household, at);
    return new GoodsAccount(
        new GoodsAccountKey(household),
        Map.of(GRAIN, grain),
        Map.of(),
        frozenGrain == 0L ? Map.of() : Map.of(GRAIN, frozenGrain),
        Map.of());
  }

  /** 与账本夹具配套的 Social 家户表：家户 id → 落点（瀑布按 Social 的位置过滤）。 */
  private static SocialData accountSocial(Map<String, HexCoord> locations) {
    Map<HouseholdId, Household> households = new LinkedHashMap<>();
    for (Map.Entry<String, HexCoord> entry : locations.entrySet()) {
      HouseholdId id = new HouseholdId(entry.getKey());
      households.put(
          id,
          new Household(
              id,
              new HouseholdLocation.Hex(entry.getValue()),
              new HouseholdProfile(id.value(), null, Map.of()),
              Map.of(),
              new HouseholdVitalRates(List.of())));
    }
    return new SocialData(Map.of(), Map.of(), Map.of(), households, Map.of());
  }

  /** 从本类刚建的账本表反查落点，拼出与它配套的 Social（不在 Social 里的家户 = 悬空，照旧被过滤掉）。 */
  private static SocialData accountSocialFromAccounts(ActorData actors) {
    Map<String, HexCoord> locations = new LinkedHashMap<>();
    for (GoodsAccountKey key : actors.accounts().keySet()) {
      HexCoord at = ACCOUNT_LOCATIONS.get(key.household());
      locations.put(key.household().value(), at == null ? H11 : at);
    }
    return accountSocial(locations);
  }

  private static long availableById(int i) {
    return switch (i) {
      case 1, 2 -> 50L;
      case 3, 4 -> 30L;
      default -> 10L;
    };
  }

  private static long countById(int i) {
    return switch (i) {
      case 1, 2 -> 50L;
      case 3, 4 -> 30L;
      default -> 10L;
    };
  }

  private static PeopleLotId lot(String id) {
    return new PeopleLotId(id);
  }

  private static PopulationGroup group(
      String id, HexCoord at, Sex sex, long count, long ageAtAnchorDays, long anchorTick) {
    PopulationGroup group = new PopulationGroup(lot(id), sex, count, ageAtAnchorDays, anchorTick);
    GROUP_LOCATIONS.put(group.id(), at);
    return group;
  }

  /** 按批次表现算 populations（SocialData 的跨组件校验要求每个批次落点都有农村序列）。 */
  private static SocialData social(PopulationGroup... groups) {
    Map<PeopleLotId, PopulationGroup> byId = new LinkedHashMap<>();
    for (PopulationGroup group : groups) {
      byId.put(group.id(), group);
    }
    return social(byId);
  }

  private static SocialData social(Map<PeopleLotId, PopulationGroup> groups) {
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    Map<PeopleLotId, HexCoord> locations = new LinkedHashMap<>();
    for (PeopleLotId id : groups.keySet()) {
      HexCoord at = GROUP_LOCATIONS.get(id);
      if (at == null) {
        throw new IllegalArgumentException("测试夹具缺批次落点: " + id);
      }
      populations.putIfAbsent(at, populationSeries());
      locations.put(id, at);
    }
    return SocialHouseholdFixture.withHouseholdsAt(populations, Map.of(), groups, locations);
  }

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 1000L),
        new SegmentedSeries<>(List.of(new Segment<>(T0, 0.0)), List.of(), null),
        List.of());
  }
}
