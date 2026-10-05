package io.mosire.simos.app.world;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ **创世期的人口播种器**（R1 的 T4；S2 起同时产出家户；**P2-A 起家户粒度 = (格, 居住类型, 阶层)**）：
 * 把一次世界生成的 {@link SettlementPlan} 翻成**一份** {@link Seeding} —— 批次 + 家户 + 逐批次位置/归属。这份结果
 * **同一份**喂给两条命令/两处计算：
 *
 * <pre>
 * social.SeedGroups   ← 同一份（落人口与家户：人口/位置的唯一真值源）
 * economy.Seed        ← 同一份（EconomySeeder 直接读这份家户集与成员份额，不再另造经济家户 id）
 * </pre>
 *
 * <p>★★ <b>P2-A 的粒度收敛（A1/A3）</b>：改前家户 = (位置 × 农村/城镇) 一戸，经济侧却按 {@code (格, 居住类型, 阶层)} 另造
 * {@code hh-<hex>-<residence>-<stratum>} 的家户 id ⇒ 同一个家户在两侧有两个身份。现在 Social 就用
 * {@link HouseholdIds#ofSeed} 造家户：**一个 {@code (hex, 居住类型, 阶层)} 一户**，每个池 4 个常规户 + 可选 1 个流民户；
 * EconomySeeder 只读这份家户集与逐户人数（不重算），两侧身份逐字相同。
 *
 * <p>★★ **批次按"居住 × 阶层 × 性别 × 年龄档"分组**：每个池每个阶层每性别每档一条 ⇒ 一个池最多
 * {@code 4 阶层 × 6} 条常规批次（+ 流民 6 条）。年龄档的三个占比 {@link EconomySeeder#AGE_SHARE_PER_MILLE} 与阶层占比
 * {@link EconomySeeder#CLASS_SHARE_PER_MILLE} 都是**同一个 D4 preset**；代表性年龄取**档中点**。
 *
 * <p>★ **零人口的格/池也落批次与家户**：只要该格在"农村序列或城市"的并集里，就按居住类型各落一组阶层家户（人口 0 时批次
 * count=0）—— 于是"有经济状态的格 ⇔ 有家户的格"成为全域成立；count=0 是合法状态。
 *
 * <p>★ **不做**：出生/死亡/迁移（R4/后续）、劳动分配（R2）。
 */
public final class PopulationSeeder {

  /** 性别比例（‰，创世 preset）：{@code MALE} 与 {@code FEMALE} 各半；残差按 {@code splitByShares} 的最大余数法归前者。 */
  public static final Map<Sex, Integer> SEX_SHARE_PER_MILLE =
      Map.of(Sex.MALE, 500, Sex.FEMALE, 500);

  /**
   * 代表年龄字面量的折算天数（**创世 preset 的采样口径**，不代表历法年）。C4b 起年龄档本身按整历法年判定，本常量只用于生成下面那三个
   * 字面量；不要拿它去当"一年"的通用换算。
   */
  private static final long DAYS_PER_YEAR = 365L;

  /**
   * 各档**代表性年龄**（天，与 {@link EconomySeeder#AGE_SHARE_PER_MILLE} 同序：0-14 / 15-59 / 60+）： 取档中点 7 岁 / 37
   * 岁 / 75 岁。
   *
   * <p>★ **代表性年龄必须落在自己那一档里**——否则 {@link EconomySeeder#ageBracketOf} 折算出来的劳动系数会是另一档的。
   *
   * <p>★ **C4b 起档界按整历法年**；实测这三个字面量仍分别落 CHILD / ADULT / ELDER（{@code 7×365} 天 ≈ 6 岁 363 天、 {@code
   * 37×365} 天 ≈ 36 岁 356 天、 {@code 75×365} 天 ≈ 74 岁 347 天），故本表**未改**（用户裁决：代表年龄先别改，落到别档才当场调）。
   */
  static final long[] AGE_REPRESENTATIVE_DAYS = {
    7L * DAYS_PER_YEAR, 37L * DAYS_PER_YEAR, 75L * DAYS_PER_YEAR
  };

  /**
   * 各档在**批次 id** 里的细分短名（与上表同序）：{@code PopulationGroup} 的 map 键必须互异，而同一格同一性别的人按年龄分三批 ⇒ 用档序号区分。
   *
   * <p>★ 它是**调用方（app）的词**：social 不解释它，只管"前缀归属"（见 {@link PopulationLots}）。
   */
  static final String[] COHORT_TAGS = {"0", "1", "2"};

  /** 流民批次在 id 里的阶层短名（与常规阶层槽位 {@code s0..s3} 不冲突；见 {@link EconomySeeder#DISPLACED_SLOT}）。 */
  static final String DISPLACED_TAG = "d";

  private PopulationSeeder() {}

  /**
   * 把计划翻成**批次 + 家户 + 逐批次位置**（**保序、可复现**：格按 {@code (q,r)}、居住类型按 RURAL→URBAN、
   * 阶层按占比表序、性别按 {@link Sex} 词表序、档按占比表序）。
   *
   * @param anchorTick 锚点（世界日）：批次记"锚点时刻的年龄"，故这一步必须由调用方一次定死（创世 = 世界当前日）
   */
  public static Seeding seed(SettlementPlan plan, long anchorTick) {
    Map<HexCoord, Long> ruralByHex = new LinkedHashMap<>(plan.ruralPopulation());
    Map<HexCoord, Long> urbanByHex = new LinkedHashMap<>();
    Map<HexCoord, CityId> cityIdByHex = new LinkedHashMap<>();
    List<PlannedCity> cities = new ArrayList<>(plan.cities());
    cities.sort(
        Comparator.comparingInt((PlannedCity city) -> city.at().q())
            .thenComparingInt(city -> city.at().r())
            .thenComparing(PlannedCity::id));
    for (PlannedCity city : cities) {
      urbanByHex.merge(city.at(), city.population(), Math::addExact);
      cityIdByHex.putIfAbsent(city.at(), CityId.parse(city.id()));
    }

    // ★ 格集 = 农村序列的格 ∪ 城市的格（两侧都落家户，形状与经济 entry 的格集一一对应）。
    List<HexCoord> hexes = new ArrayList<>(ruralByHex.keySet());
    for (HexCoord hex : urbanByHex.keySet()) {
      if (!ruralByHex.containsKey(hex)) {
        hexes.add(hex);
      }
    }
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));

    List<PopulationGroup> groups = new ArrayList<>(hexes.size() * 48);
    List<Household> households = new ArrayList<>(hexes.size() * 10);
    Map<PeopleLotId, HexCoord> locations = new LinkedHashMap<>();
    Map<PeopleLotId, HouseholdId> householdOfLot = new LinkedHashMap<>();
    Map<CohortKey, HouseholdId> householdByCohort = new LinkedHashMap<>();
    Map<HouseholdId, Long> populationByHousehold = new LinkedHashMap<>();

    for (HexCoord hex : hexes) {
      appendPool(
          hex,
          ResidenceKind.RURAL,
          ruralByHex.getOrDefault(hex, 0L),
          anchorTick,
          null,
          groups,
          households,
          locations,
          householdOfLot,
          householdByCohort,
          populationByHousehold);
      appendPool(
          hex,
          ResidenceKind.URBAN,
          urbanByHex.getOrDefault(hex, 0L),
          anchorTick,
          cityIdByHex.get(hex),
          groups,
          households,
          locations,
          householdOfLot,
          householdByCohort,
          populationByHousehold);
    }
    // ★★ P2-A §13.7：中央/地方政府恰一个政府家户（国库 = 它的账户）。它的身份与落点在这里与普通家户同源，
    //   EconomySeeder 只读（不再自己挑格、另拼 id）。落点 = 人口最多的格（并列取 (q,r) 字典序最小）。
    HexCoord governmentAt = governmentHex(hexes, ruralByHex, urbanByHex);
    HouseholdId governmentHousehold =
        HouseholdIds.ofSeed(governmentAt, ResidenceKind.URBAN, SocialClassId.OFFICIAL);
    households.add(
        new Household(
            governmentHousehold,
            new HouseholdLocation.Hex(governmentAt),
            new HouseholdProfile(governmentHousehold.value(), null, Map.of()),
            Map.of(),
            new HouseholdVitalRates(List.of())));
    putCohort(
        householdByCohort,
        new CohortKey(governmentAt, ResidenceKind.URBAN, SocialClassId.OFFICIAL),
        governmentHousehold);
    populationByHousehold.put(governmentHousehold, 0L);
    return new Seeding(
        groups,
        households,
        locations,
        householdOfLot,
        householdByCohort,
        populationByHousehold,
        governmentHousehold);
  }

  /** 政府家户的落点：人口（农村 + 城镇）最多的格，并列取 (q,r) 字典序最小；无人格 ⇒ 抛（与 EconomySeeder 同口径）。 */
  private static HexCoord governmentHex(
      List<HexCoord> hexes, Map<HexCoord, Long> ruralByHex, Map<HexCoord, Long> urbanByHex) {
    if (hexes.isEmpty()) {
      throw new IllegalStateException("GOV 家户需要至少一个格，但播种的格集为空");
    }
    HexCoord best = null;
    long bestPopulation = -1L;
    for (HexCoord hex : hexes) {
      long population = ruralByHex.getOrDefault(hex, 0L) + urbanByHex.getOrDefault(hex, 0L);
      if (population > bestPopulation) {
        best = hex;
        bestPopulation = population;
      }
    }
    return best;
  }

  /** 兼容便捷入口：只要批次（位置在 {@link #seed} 的返回里；生产路径必须用 {@link #seed}）。 */
  public static List<PopulationGroup> groups(SettlementPlan plan, long anchorTick) {
    return seed(plan, anchorTick).groups();
  }

  /** 批次归属的居住类型（命名约定，不是状态字段）。 */
  private enum PopulationKind {
    RURAL,
    URBAN
  }

  /** 一个池（格 × 居住类型）：按阶层切成 4 个常规户（+ 可选流民户），每户落"性别 × 年龄档"批次。 */
  private static void appendPool(
      HexCoord hex,
      ResidenceKind residence,
      long poolPopulation,
      long anchorTick,
      CityId city,
      List<PopulationGroup> groups,
      List<Household> households,
      Map<PeopleLotId, HexCoord> locations,
      Map<PeopleLotId, HouseholdId> householdOfLot,
      Map<CohortKey, HouseholdId> householdByCohort,
      Map<HouseholdId, Long> populationByHousehold) {
    long[] people = EconomySeeder.splitByShares(poolPopulation, EconomySeeder.CLASS_SHARE_PER_MILLE);
    long displaced = EconomySeeder.displacedSeedPopulation(poolPopulation);
    if (displaced > 0L) {
      if (displaced > people[0]) {
        throw new IllegalStateException(
            "流民人口超过最贫一档人口（流民比例与阶层比例漂开）：displaced=" + displaced + "，poorest=" + people[0]);
      }
      people[0] -= displaced;
    }
    PopulationKind kind = residence == ResidenceKind.RURAL ? PopulationKind.RURAL : PopulationKind.URBAN;
    for (int slot = 0; slot < EconomySeeder.CLASS_IDS.length; slot++) {
      SocialClassId stratum = new SocialClassId(EconomySeeder.CLASS_IDS[slot]);
      HouseholdId id = HouseholdIds.ofSeed(hex, residence, stratum);
      List<PopulationGroup> lots =
          lotsAt(hex, kind, city, people[slot], anchorTick, "s" + slot);
      groups.addAll(lots);
      households.add(
          household(id, new HouseholdLocation.Hex(hex), lots, locations, householdOfLot));
      putCohort(householdByCohort, new CohortKey(hex, residence, stratum), id);
      populationByHousehold.put(id, people[slot]);
    }
    if (displaced > 0L) {
      HouseholdId id =
          HouseholdIds.ofSeedRole(hex, residence, SocialClassId.LANDLESS_LABORER, "displaced");
      List<PopulationGroup> lots =
          lotsAt(hex, kind, city, displaced, anchorTick, DISPLACED_TAG);
      groups.addAll(lots);
      households.add(
          household(id, new HouseholdLocation.Hex(hex), lots, locations, householdOfLot));
      putCohort(
          householdByCohort, new CohortKey(hex, residence, SocialClassId.LANDLESS_LABORER), id);
      populationByHousehold.put(id, displaced);
    }
  }

  /** 同一个 {@code (格, 居住, 阶层)} 只许有一个家户（P2-A 的唯一身份口径），撞车 ⇒ 当场炸（不静默覆盖）。 */
  private static void putCohort(
      Map<CohortKey, HouseholdId> householdByCohort, CohortKey cohort, HouseholdId household) {
    HouseholdId previous = householdByCohort.put(cohort, household);
    if (previous != null) {
      throw new IllegalStateException("同一个 (格, 居住类型, 阶层) 出现两个家户: " + cohort + " → " + previous + " / " + household);
    }
  }

  /** 一个池里一个阶层的批次：每性别 × 每年龄档一条（农村用 hex 命名，城镇用 city 命名）。 */
  private static List<PopulationGroup> lotsAt(
      HexCoord at,
      PopulationKind kind,
      CityId city,
      long population,
      long anchorTick,
      String stratumTag) {
    if (kind == PopulationKind.URBAN && city == null) {
      // ★ 农村格没有城市 ⇒ 该格的城镇家户是合法零人口空壳（无批次）；不造假的 city 前缀批次。
      return List.of();
    }
    List<PopulationGroup> lots = new ArrayList<>(Sex.values().length * COHORT_TAGS.length);
    long[] bySex = splitBySex(population);
    int sexIndex = 0;
    for (Sex sex : Sex.values()) {
      long[] byAge =
          EconomySeeder.splitByShares(bySex[sexIndex++], EconomySeeder.AGE_SHARE_PER_MILLE);
      for (int bracket = 0; bracket < byAge.length; bracket++) {
        String cohort = stratumTag + "-" + COHORT_TAGS[bracket];
        PeopleLotId id =
            kind == PopulationKind.RURAL
                ? PopulationLots.rural(at, sex, cohort)
                : PopulationLots.urban(city, sex, cohort);
        lots.add(
            new PopulationGroup(
                id, sex, byAge[bracket], AGE_REPRESENTATIVE_DAYS[bracket], anchorTick));
      }
    }
    return List.copyOf(lots);
  }

  /** 造家户并登记逐批次位置/归属（members = 该阶层的全部批次，份额 = 批次人数：一个池内每批次只归一户）。 */
  private static Household household(
      HouseholdId id,
      HouseholdLocation location,
      List<PopulationGroup> lots,
      Map<PeopleLotId, HexCoord> locations,
      Map<PeopleLotId, HouseholdId> householdOfLot) {
    Map<PeopleLotId, Long> members = new LinkedHashMap<>();
    for (PopulationGroup group : lots) {
      members.put(group.id(), group.count());
      locations.put(group.id(), location instanceof HouseholdLocation.Hex hex ? hex.hex() : null);
      HouseholdId previous = householdOfLot.put(group.id(), id);
      if (previous != null) {
        throw new IllegalStateException("批次 " + group.id() + " 同时落在两个家户: " + previous + " / " + id);
      }
    }
    return new Household(
        id, location, new HouseholdProfile(id.value(), null, Map.of()), members, new HouseholdVitalRates(List.of()));
  }

  /**
   * 按性别切（‰ 表见 {@link #SEX_SHARE_PER_MILLE}）：分母恒 1000‰、残差按最大余数法 ⇒ **Σ 结果 == total**（一个人不丢）。
   *
   * <p>★ 复用 {@link EconomySeeder#splitByShares}：切分口径在本仓只有一处实现。
   */
  private static long[] splitBySex(long total) {
    int[] shares = new int[Sex.values().length];
    for (int i = 0; i < shares.length; i++) {
      Integer share = SEX_SHARE_PER_MILLE.get(Sex.values()[i]);
      if (share == null) {
        throw new IllegalStateException("性别比例表缺 " + Sex.values()[i] + " 一档（拒绝臆造）");
      }
      shares[i] = share;
    }
    return EconomySeeder.splitByShares(total, shares);
  }

  /**
   * {@code social.SeedGroups} 的载荷（S2 的形状）：{@code
   * {entries:[{id,q,r,sex,count,ageDays,anchorTick,stress,household}…], households:[{id,q,r,name}…]}}。
   *
   * <p>★ {@code anchorTick} **逐条显式给**（不用"缺省 = 世界当前时刻"）：这一份列表同时喂给经济侧，
   * 两侧的年龄必须指向**同一个锚点**。
   */
  public static String payload(Seeding seeding) {
    Objects.requireNonNull(seeding, "seeding");
    List<Map<String, Object>> entries = new ArrayList<>(seeding.groups().size());
    for (PopulationGroup group : seeding.groups()) {
      HexCoord at = seeding.locationOf(group.id());
      HouseholdId household = seeding.householdOf(group.id());
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", group.id().value());
      entry.put("q", at.q());
      entry.put("r", at.r());
      entry.put("sex", group.sex().name());
      entry.put("count", group.count());
      entry.put("ageDays", group.ageAtAnchorDays());
      entry.put("anchorTick", group.anchorTick());
      entry.put("stress", group.physiologicalStress());
      entry.put("household", household.value());
      entries.add(entry);
    }
    List<Map<String, Object>> householdNodes = new ArrayList<>(seeding.households().size());
    for (Household household : seeding.households()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", household.id().value());
      HexCoord at = seeding.locationOfHousehold(household.id());
      node.put("q", at.q());
      node.put("r", at.r());
      node.put("name", household.profile().name());
      householdNodes.add(node);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entries", entries);
    payload.put("households", householdNodes);
    return ToolSupport.json(payload);
  }

  /**
   * 一次播种的完整产物：批次 + 家户 + 逐批次位置 + 逐批次所属家户 + 逐 (格, 居住类型, 阶层) 的家户身份 + 逐户人数。
   * **保序不可变**；构造期自检"每个批次恰有一个家户、每个家户的成员都在批次表里、逐户份额守恒"。
   *
   * <p>★★ <b>P2-A：EconomySeeder 只读这份产物</b>——家户身份（{@link #householdByCohort}）与人数（{@link
   * #populationByHousehold}）的真值源在这里（Social 侧），经济侧不再另造 id、不再二次切分。
   */
  public record Seeding(
      List<PopulationGroup> groups,
      List<Household> households,
      Map<PeopleLotId, HexCoord> locations,
      Map<PeopleLotId, HouseholdId> householdByLot,
      Map<CohortKey, HouseholdId> householdByCohort,
      Map<HouseholdId, Long> populationByHousehold,
      HouseholdId governmentHousehold) {

    public Seeding {
      groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
      households = List.copyOf(Objects.requireNonNull(households, "households"));
      Objects.requireNonNull(governmentHousehold, "governmentHousehold");
      locations =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(locations, "locations")));
      householdByLot =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(householdByLot, "householdByLot")));
      householdByCohort =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(householdByCohort, "householdByCohort")));
      populationByHousehold =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(
                  Objects.requireNonNull(populationByHousehold, "populationByHousehold")));
      Map<PeopleLotId, PopulationGroup> byId = new LinkedHashMap<>();
      Map<PeopleLotId, HouseholdId> owner = new LinkedHashMap<>();
      Map<HouseholdId, Household> byHousehold = new LinkedHashMap<>();
      for (Household household : households) {
        if (byHousehold.put(household.id(), household) != null) {
          throw new IllegalArgumentException("播种家户 id 重复: " + household.id());
        }
      }
      for (PopulationGroup group : groups) {
        if (!locations.containsKey(group.id())) {
          throw new IllegalArgumentException("播种批次缺位置: " + group.id());
        }
        if (byId.put(group.id(), group) != null) {
          throw new IllegalArgumentException("播种批次 id 重复: " + group.id());
        }
      }
      for (Household household : households) {
        for (Map.Entry<PeopleLotId, Long> member : household.members().entrySet()) {
          PopulationGroup group = byId.get(member.getKey());
          if (group == null) {
            throw new IllegalArgumentException("家户 " + household.id() + " 的成员不在批次表里: " + member.getKey());
          }
          if (member.getValue() != group.count()) {
            throw new IllegalArgumentException(
                "播种家户份额必须等于批次人数（首版每批次归一户）: lot="
                    + member.getKey()
                    + " share="
                    + member.getValue()
                    + " count="
                    + group.count());
          }
          HouseholdId previous = owner.put(member.getKey(), household.id());
          if (previous != null) {
            throw new IllegalArgumentException(
                "播种批次 " + member.getKey() + " 同时属于 " + previous + " 与 " + household.id());
          }
        }
      }
      for (PopulationGroup group : groups) {
        if (!owner.containsKey(group.id())) {
          throw new IllegalArgumentException("播种批次没有家户: " + group.id());
        }
        HouseholdId declared = householdByLot.get(group.id());
        if (declared == null || !declared.equals(owner.get(group.id()))) {
          throw new IllegalArgumentException(
              "播种批次的归属表与家户 members 不一致: " + group.id() + " declared=" + declared + " owner=" + owner.get(group.id()));
        }
      }
      for (HouseholdId household : byHousehold.keySet()) {
        Long population = populationByHousehold.get(household);
        if (population == null) {
          throw new IllegalArgumentException("播种家户缺人数: " + household);
        }
        long actual = 0L;
        for (long share : byHousehold.get(household).members().values()) {
          actual = Math.addExact(actual, share);
        }
        if (actual != population) {
          throw new IllegalArgumentException(
              "播种家户的人数表与成员份额不一致: " + household + " population=" + population + " Σshare=" + actual);
        }
      }
    }

    public HexCoord locationOf(PeopleLotId lot) {
      HexCoord at = locations.get(lot);
      if (at == null) {
        throw new IllegalArgumentException("播种批次没有位置: " + lot);
      }
      return at;
    }

    public HouseholdId householdOf(PeopleLotId lot) {
      HouseholdId household = householdByLot.get(lot);
      if (household == null) {
        throw new IllegalArgumentException("播种批次没有家户: " + lot);
      }
      return household;
    }

    /** 该 (格, 居住类型, 阶层) 的家户；没有（如零人口池没有流民户）⇒ 空。 */
    public Optional<HouseholdId> householdOfCohort(CohortKey cohort) {
      Objects.requireNonNull(cohort, "cohort");
      return Optional.ofNullable(householdByCohort.get(cohort));
    }

    /** 该家户的人数（members 份额之和）；不存在 ⇒ 0。 */
    public long populationOf(HouseholdId household) {
      Objects.requireNonNull(household, "household");
      return populationByHousehold.getOrDefault(household, 0L);
    }

    /** 该家户的位置格（播种期家户恒在 HEX 上）。 */
    public HexCoord locationOfHousehold(HouseholdId household) {
      for (Household candidate : households) {
        if (candidate.id().equals(household)) {
          if (candidate.location() instanceof HouseholdLocation.Hex hex) {
            return hex.hex();
          }
          throw new IllegalStateException("播种期家户不在 HEX 上: " + household);
        }
      }
      throw new IllegalArgumentException("播种家户不存在: " + household);
    }
  }
}
