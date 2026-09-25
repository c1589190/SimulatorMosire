package io.mosire.simos.app.world;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.gen.PlannedCity;
import io.mosire.simos.social.gen.SettlementPlan;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.Sex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ **创世期的人口播种器**（R1 的 T4）：把一次世界生成的 {@link SettlementPlan} 翻成**一份** {@link PopulationGroup} 列表 ——
 * 这份列表是**同一份**喂给两条命令的东西：
 *
 * <pre>
 * social.SeedGroups   ← 同一条列表（落人口：人口的真值源）
 * economy.Seed        ← 同一条列表（{@link EconomySeeder#payload} 按格聚合 Σ count 来切阶层份额）
 * </pre>
 *
 * <p>★★ **"Σ group == 经济侧总人口"因此是构造性成立的**：两侧都在读同一份列表，不需要运行期读 {@code social} 切片（那需要一个跨切片协调器，属后续轮次；R1
 * 的接缝就是"app 一次算出、同一份喂两条命令"）。
 *
 * <p>★★ **批次按"居住 × 性别 × 年龄档"分组**（设计稿 §十.6 的原话：个体差异靠组内分布，「{@code PopulationGroup} 按 **年龄 × 性别 × 居住**
 * 分组已足够」）：每格每性别每档一条 ⇒ 每格 6 条（农村/城镇各自 6 条）。年龄档的三个占比 {@link EconomySeeder#AGE_SHARE_PER_MILLE} 是**同一个
 * D4 preset**（它同时是经济侧劳动折算的年龄构成）， 代表性年龄取**档中点**，故"创世期的年龄分布"只有一处真相。
 *
 * <p>★ **两个创世 preset**（都是"判断结果"，R2 才按观察调值，与 D4 三档同款降级）：
 *
 * <ul>
 *   <li>{@link #SEX_SHARE_PER_MILLE}：性别比例，两性各半（性别比例没有任何文档依据 ⇒ 不臆造偏向）；
 *   <li>{@link #AGE_REPRESENTATIVE_DAYS}：各档代表性年龄（档中点）。
 * </ul>
 *
 * <p>★ **零人口的格也落批次**：只要该格有农村人口序列（哪怕 0 人），就落 6 条 count=0 的批次。这不是凑数 —— 它让"有序列的格 ⇔
 * 有批次的格"成为**全域成立**的对应（设计稿 §十.7 的跨组件校验才有意义）， 也让经济侧"该格有没有人口"这个 事实仍然**只**来自批次（{@link EconomySeeder}
 * 的格集 = 批次落点的集合）。count=0 是合法状态（见 {@link PopulationGroup}）。
 *
 * <p>★ **不做**：出生/死亡/迁移（R4）、劳动分配（R2）、迁移的批次拆分与合并（属性不同的批次不许悄悄合并）。
 */
public final class PopulationSeeder {

  /** 性别比例（‰，创世 preset）：{@code MALE} 与 {@code FEMALE} 各半；残差按 {@code splitByShares} 的最大余数法归前者。 */
  public static final Map<Sex, Integer> SEX_SHARE_PER_MILLE =
      Map.of(Sex.MALE, 500, Sex.FEMALE, 500);

  /** 一年的天数（年龄用天表示；本仓日制，不引入闰年——创世 preset 不需要它）。 */
  private static final long DAYS_PER_YEAR = 365L;

  /**
   * 各档**代表性年龄**（天，与 {@link EconomySeeder#AGE_SHARE_PER_MILLE} 同序：0-14 / 15-59 / 60+）： 取档中点 7 岁 / 37
   * 岁 / 75 岁。
   *
   * <p>★ **代表性年龄必须落在自己那一档里**——否则 {@link EconomySeeder#ageBracketOf} 折算出来的劳动系数会是另一档的。 这条由 {@code
   * PopulationSeederTest} 逐档钉住（两张表的跨表一致性，本仓最忌"注释声称一致、其实不一致"）。
   */
  static final long[] AGE_REPRESENTATIVE_DAYS = {
    7L * DAYS_PER_YEAR, 37L * DAYS_PER_YEAR, 75L * DAYS_PER_YEAR
  };

  /**
   * 各档在**批次 id** 里的细分短名（与上表同序）：{@code PopulationGroup} 的 map 键必须互异，而同一格同一性别的人按年龄分三批 ⇒ 用档序号区分。
   *
   * <p>★ 它是**调用方（app）的词**：social 不解释它，只管"前缀归属"（见 {@link PopulationLots}）。 用序号而不是 {@code 0_14}
   * 这类区间名：区间的解释权在 {@link EconomySeeder#AGE_SHARE_PER_MILLE} 一处， 不要把同一件事拼两遍。
   */
  static final String[] COHORT_TAGS = {"0", "1", "2"};

  private PopulationSeeder() {}

  /**
   * 把计划翻成批次列表（**保序、可复现**：格按 {@code (q,r)}、性别按 {@link Sex} 词表序、档按占比表序）。
   *
   * @param anchorTick 锚点（世界日）：批次记"锚点时刻的年龄"，故这一步必须由调用方一次定死（创世 = 世界当前日）
   */
  public static List<PopulationGroup> groups(SettlementPlan plan, long anchorTick) {
    List<HexCoord> ruralHexes = new ArrayList<>(plan.ruralPopulation().keySet());
    ruralHexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    List<PlannedCity> cities = new ArrayList<>(plan.cities());
    cities.sort(
        Comparator.comparingInt((PlannedCity city) -> city.at().q())
            .thenComparingInt(city -> city.at().r())
            .thenComparing(PlannedCity::id));

    List<PopulationGroup> groups = new ArrayList<>(ruralHexes.size() * 6 + cities.size() * 6);
    for (HexCoord hex : ruralHexes) {
      // ★ 农村人口**逐格取自计划**（同格有城时，城的人口单独成批次，两者不重不漏）。
      appendHexLots(groups, hex, plan.ruralPopulation().getOrDefault(hex, 0L), anchorTick);
    }
    for (PlannedCity city : cities) {
      CityId cityId = CityId.parse(city.id());
      appendCityLots(groups, cityId, city.at(), city.population(), anchorTick);
    }
    return List.copyOf(groups);
  }

  /** 农村批次：{@link PopulationLots#rural} 命名，落点 = 该格。 */
  private static void appendHexLots(
      List<PopulationGroup> groups, HexCoord hex, long population, long anchorTick) {
    long[] bySex = splitBySex(population);
    int sexIndex = 0;
    for (Sex sex : Sex.values()) {
      long[] byAge =
          EconomySeeder.splitByShares(bySex[sexIndex++], EconomySeeder.AGE_SHARE_PER_MILLE);
      for (int bracket = 0; bracket < byAge.length; bracket++) {
        PeopleLotId id = PopulationLots.rural(hex, sex, COHORT_TAGS[bracket]);
        groups.add(
            new PopulationGroup(
                id, hex, sex, byAge[bracket], AGE_REPRESENTATIVE_DAYS[bracket], anchorTick));
      }
    }
  }

  /** 城镇批次：{@link PopulationLots#urban} 命名（身份 = 城的 {@link CityId}），落点 = 该城所在格。 */
  private static void appendCityLots(
      List<PopulationGroup> groups, CityId city, HexCoord at, long population, long anchorTick) {
    long[] bySex = splitBySex(population);
    int sexIndex = 0;
    for (Sex sex : Sex.values()) {
      long[] byAge =
          EconomySeeder.splitByShares(bySex[sexIndex++], EconomySeeder.AGE_SHARE_PER_MILLE);
      for (int bracket = 0; bracket < byAge.length; bracket++) {
        PeopleLotId id = PopulationLots.urban(city, sex, COHORT_TAGS[bracket]);
        groups.add(
            new PopulationGroup(
                id, at, sex, byAge[bracket], AGE_REPRESENTATIVE_DAYS[bracket], anchorTick));
      }
    }
  }

  /**
   * 按性别切（‰ 表见 {@link #SEX_SHARE_PER_MILLE}）：分母恒 1000‰、残差按最大余数法 ⇒ **Σ 结果 == total**（一个人不丢）。
   *
   * <p>★ 复用 {@link EconomySeeder#splitByShares}：切分口径在本仓只有一处实现（"分配口径统一是可执行的事实"）。
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
   * {@code social.SeedGroups} 的载荷（T3 的形状）：{@code
   * {entries:[{id,q,r,sex,count,ageDays,anchorTick}…]}}。
   *
   * <p>★ {@code anchorTick} **逐条显式给**（不用"缺省 = 世界当前时刻"）：这一份列表同时喂给经济侧，
   * 两侧的年龄必须指向**同一个锚点**；让命令层各自取当前时刻会把"同一份列表"变成"两处各自解释"。
   */
  public static String payload(List<PopulationGroup> groups) {
    List<Map<String, Object>> entries = new ArrayList<>(groups.size());
    for (PopulationGroup group : groups) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", group.id().value());
      entry.put("q", group.residence().q());
      entry.put("r", group.residence().r());
      entry.put("sex", group.sex().name());
      entry.put("count", group.count());
      entry.put("ageDays", group.ageAtAnchorDays());
      entry.put("anchorTick", group.anchorTick());
      entries.add(entry);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entries", entries);
    return ToolSupport.json(payload);
  }
}
