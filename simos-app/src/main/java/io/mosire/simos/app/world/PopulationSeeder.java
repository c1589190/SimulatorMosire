package io.mosire.simos.app.world;

import io.mosire.simos.app.tools.ToolSupport;
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

/**
 * ★★ **创世期的人口播种器**（R1 的 T4；S2 起同时产出家户）：把一次世界生成的 {@link SettlementPlan} 翻成**一份**
 * {@link Seeding} —— 批次 + 家户 + 逐批次位置。这份结果**同一份**喂给两条命令/两处计算：
 *
 * <pre>
 * social.SeedGroups   ← 同一份（落人口与家户：人口/位置的唯一真值源）
 * economy.Seed        ← 同一份（{@link EconomySeeder#plan} 按家户落点聚合 Σ count 来切阶层份额）
 * </pre>
 *
 * <p>★★ <b>家户粒度 = (位置 × 农村/城镇)</b>（S2）：每格农村人口成一家户（{@code hh:rural:<q>_<r>}），每座城的城镇人口
 * 成一家户（{@code hh:urban:<cityId>}）。这与经济侧 {@code (格, ResidenceKind)} 的池口径一一对应；"一个家户含多个
 * 年龄/性别批次"正是 memberLots 表要表达的（架构 §4.1）。
 *
 * <p>★★ **批次按"居住 × 性别 × 年龄档"分组**（设计稿 §十.6）：每格每性别每档一条 ⇒ 每格 6 条（农村/城镇各自 6 条）。
 * 年龄档的三个占比 {@link EconomySeeder#AGE_SHARE_PER_MILLE} 是**同一个 D4 preset**；代表性年龄取**档中点**。
 *
 * <p>★ **零人口的格也落批次与家户**：只要该格有农村人口序列（哪怕 0 人），就落 6 条 count=0 的批次并挂进该格家户 ——
 * 于是"有序列的格 ⇔ 有家户的格"成为全域成立；count=0 是合法状态。
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
   * 这条由 {@code PopulationSeederTest} 逐档钉住（两张表的跨表一致性，本仓最忌"注释声称一致、其实不一致"）。
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

  private PopulationSeeder() {}

  /**
   * 把计划翻成**批次 + 家户 + 逐批次位置**（**保序、可复现**：格按 {@code (q,r)}、性别按 {@link Sex} 词表序、档按占比表序）。
   *
   * @param anchorTick 锚点（世界日）：批次记"锚点时刻的年龄"，故这一步必须由调用方一次定死（创世 = 世界当前日）
   */
  public static Seeding seed(SettlementPlan plan, long anchorTick) {
    List<HexCoord> ruralHexes = new ArrayList<>(plan.ruralPopulation().keySet());
    ruralHexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    List<PlannedCity> cities = new ArrayList<>(plan.cities());
    cities.sort(
        Comparator.comparingInt((PlannedCity city) -> city.at().q())
            .thenComparingInt(city -> city.at().r())
            .thenComparing(PlannedCity::id));

    List<PopulationGroup> groups = new ArrayList<>(ruralHexes.size() * 6 + cities.size() * 6);
    List<Household> households = new ArrayList<>(ruralHexes.size() + cities.size());
    Map<PeopleLotId, HexCoord> locations = new LinkedHashMap<>();
    Map<PeopleLotId, HouseholdId> householdOfLot = new LinkedHashMap<>();

    for (HexCoord hex : ruralHexes) {
      HouseholdId householdId = HouseholdId.parse("hh:rural:" + hex);
      List<PopulationGroup> lots =
          lotsAt(hex, PopulationKind.RURAL, plan.ruralPopulation().getOrDefault(hex, 0L), anchorTick, null);
      groups.addAll(lots);
      households.add(household(householdId, new HouseholdLocation.Hex(hex), lots, locations, householdOfLot, hex));
    }
    for (PlannedCity city : cities) {
      CityId cityId = CityId.parse(city.id());
      HouseholdId householdId = HouseholdId.parse("hh:urban:" + cityId.value());
      List<PopulationGroup> lots =
          lotsAt(city.at(), PopulationKind.URBAN, city.population(), anchorTick, cityId);
      groups.addAll(lots);
      households.add(
          household(householdId, new HouseholdLocation.Hex(city.at()), lots, locations, householdOfLot, city.at()));
    }
    return new Seeding(groups, households, locations, householdOfLot);
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

  /** 一个位置的一族批次：每性别 × 每年龄档一条（农村用 hex 命名，城镇用 city 命名）。 */
  private static List<PopulationGroup> lotsAt(
      HexCoord at, PopulationKind kind, long population, long anchorTick, CityId city) {
    List<PopulationGroup> lots = new ArrayList<>(Sex.values().length * COHORT_TAGS.length);
    long[] bySex = splitBySex(population);
    int sexIndex = 0;
    for (Sex sex : Sex.values()) {
      long[] byAge =
          EconomySeeder.splitByShares(bySex[sexIndex++], EconomySeeder.AGE_SHARE_PER_MILLE);
      for (int bracket = 0; bracket < byAge.length; bracket++) {
        PeopleLotId id =
            kind == PopulationKind.RURAL
                ? PopulationLots.rural(at, sex, COHORT_TAGS[bracket])
                : PopulationLots.urban(city, sex, COHORT_TAGS[bracket]);
        lots.add(
            new PopulationGroup(
                id, sex, byAge[bracket], AGE_REPRESENTATIVE_DAYS[bracket], anchorTick));
      }
    }
    return List.copyOf(lots);
  }

  /** 造家户并登记逐批次位置/归属（memberLots = 该位置的全部批次）。 */
  private static Household household(
      HouseholdId id,
      HouseholdLocation location,
      List<PopulationGroup> lots,
      Map<PeopleLotId, HexCoord> locations,
      Map<PeopleLotId, HouseholdId> householdOfLot,
      HexCoord at) {
    List<PeopleLotId> memberLots = new ArrayList<>(lots.size());
    for (PopulationGroup group : lots) {
      memberLots.add(group.id());
      locations.put(group.id(), at);
      HouseholdId previous = householdOfLot.put(group.id(), id);
      if (previous != null) {
        throw new IllegalStateException("批次 " + group.id() + " 同时落在两个家户: " + previous + " / " + id);
      }
    }
    return new Household(
        id, location, new HouseholdProfile(id.value(), null, Map.of()), memberLots, new HouseholdVitalRates(List.of()));
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
      node.put("q", seeding.locationOf(household.memberLots().get(0)).q());
      node.put("r", seeding.locationOf(household.memberLots().get(0)).r());
      node.put("name", household.profile().name());
      householdNodes.add(node);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entries", entries);
    payload.put("households", householdNodes);
    return ToolSupport.json(payload);
  }

  /**
   * 一次播种的完整产物：批次 + 家户 + 逐批次位置 + 逐批次所属家户。**保序不可变**；构造期自检"每个批次恰有一个家户、
   * 每个家户的成员都在批次表里"（与经济/社会两条命令同源的前提）。
   */
  public record Seeding(
      List<PopulationGroup> groups,
      List<Household> households,
      Map<PeopleLotId, HexCoord> locations,
      Map<PeopleLotId, HouseholdId> householdByLot) {

    public Seeding {
      groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
      households = List.copyOf(Objects.requireNonNull(households, "households"));
      locations =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(locations, "locations")));
      householdByLot =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(householdByLot, "householdByLot")));
      Map<PeopleLotId, HouseholdId> owner = new LinkedHashMap<>();
      Map<PeopleLotId, PopulationGroup> byId = new LinkedHashMap<>();
      for (PopulationGroup group : groups) {
        if (!locations.containsKey(group.id())) {
          throw new IllegalArgumentException("播种批次缺位置: " + group.id());
        }
        if (byId.put(group.id(), group) != null) {
          throw new IllegalArgumentException("播种批次 id 重复: " + group.id());
        }
      }
      for (Household household : households) {
        for (PeopleLotId lot : household.memberLots()) {
          if (!byId.containsKey(lot)) {
            throw new IllegalArgumentException("家户 " + household.id() + " 的成员不在批次表里: " + lot);
          }
          HouseholdId previous = owner.put(lot, household.id());
          if (previous != null) {
            throw new IllegalArgumentException("批次 " + lot + " 同时属于 " + previous + " 与 " + household.id());
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
              "播种批次的归属表与家户 memberLots 不一致: " + group.id() + " declared=" + declared + " owner=" + owner.get(group.id()));
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
  }
}
