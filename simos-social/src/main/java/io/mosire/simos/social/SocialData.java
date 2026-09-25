package io.mosire.simos.social;

import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationHeadline;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.PopulationSource;
import io.mosire.simos.social.population.Sex;
import io.mosire.simos.social.population.UrbanRural;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 社会状态（M3 spec §3.1 + 城市节点 + 第三阶段设计稿 §三的**人口实体**）：每个 hex 一条人口序列 + 城市节点表 + 人口批次表。
 *
 * <p>★★ **{@code populations} 装的是该格的农村/村镇人口（rural）**——不是该格的"总人口"。该 hex 的"总人口"是**派生量**，
 * **不落盘**：落盘只会多出一份会漂移的冗余（城市增删/改人口时都要同步它），而且城乡口径再也分不开。后来者要算总量，请**现算**，别找地方存。
 *
 * <p>★★ **R1 起，"人口的真值源"是 {@code groups}**（第三阶段设计稿 §二/§三）：{@link PopulationGroup} 是人的实体 （年龄逐日精度 + 性别
 * + 现居格），{@code populations}（农村序列）与 {@code cities}（城市节点）是**旧账**， 二者都由同一份创世计划种下、逐格对得上（{@code 验收：Σ
 * group == 原 rural+urban}），而**规模量一律现算**：
 *
 * <ul>
 *   <li>{@link #populationAt(HexCoord)} —— 该格人口（农村 + 城镇）= Σ 该格各批次；
 *   <li>{@link #urbanPopulationAt(CityId)} —— 某城的城镇人口 = 该城各批次之和（**不再是 {@code SocialCity} 的字段**）。
 * </ul>
 *
 * <p>★ {@code cities} 是城市节点表（键是 {@link CityId}）——它的**落点**由 {@link SocialCity#at()}
 * 给出，故"某格有哪些城市"是从本表 **派生**的（不另存索引）。
 *
 * <p>★ **三个 map 都保序不可变**：{@code LinkedHashMap} + {@code unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——它的迭代序不是内容的纯函数（M2 实测），字节级往返因此不成立。
 */
public record SocialData(
    Map<HexCoord, PopulationSeries> populations,
    Map<CityId, SocialCity> cities,
    Map<PeopleLotId, PopulationGroup> groups) {

  public SocialData {
    if (populations == null) {
      throw new IllegalArgumentException("populations 不得为 null");
    }
    // ★ **老档兼容**（旧字节没有这个键，如 `worlds/v17levant.json` 与升级前落盘的每条 social revision）：
    //   缺省 = 空表，**此处不抛** —— 抛了等于"整个世界打不开"（先例：SdInfoEntry 的 affiliations/adjudicationStatus）。
    //   方向是 fail-closed：旧档里没有城市，读回来就是没有城市。
    if (cities == null) {
      cities = Map.of();
    }
    // ★ **R1 唯一保留的那一行兼容**（用户 2026-09-26：「旧档重建也没关系……唯一保留的一行是新组件的 null ⇒ 空表」，
    //   否则随包的 `worlds/v17levant.json` 打不开——那是**创世文件**）：缺省 = 空表。
    //   方向同样是 fail-closed：旧档里没有批次，读回来就是没有批次。
    if (groups == null) {
      groups = Map.of();
    }
    Map<HexCoord, PopulationSeries> populationsCopy = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, PopulationSeries> entry : populations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("populations 的键与值都不得为 null");
      }
      populationsCopy.put(entry.getKey(), entry.getValue());
    }
    populations =
        Collections.unmodifiableMap(populationsCopy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
    Map<CityId, SocialCity> citiesCopy = new LinkedHashMap<>();
    for (Map.Entry<CityId, SocialCity> entry : cities.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("cities 的键与值都不得为 null");
      }
      citiesCopy.put(entry.getKey(), entry.getValue());
    }
    cities = Collections.unmodifiableMap(citiesCopy); // ★ 冻在赋值处，同上
    Map<PeopleLotId, PopulationGroup> groupsCopy = new LinkedHashMap<>();
    for (Map.Entry<PeopleLotId, PopulationGroup> entry : groups.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("groups 的键与值都不得为 null");
      }
      // ★★ **跨组件校验**（设计稿 §十.7「加，并立成 R1 的验收判据」）：批次必须落在**有农村序列的格**上。
      //   理由不是洁癖：`populations` 与 `groups` 是同一份人口的两笔账，若批次落在一个"没人认领"的格上，
      //   它就既不属于任何农村序列、也没有任何读口能看见它 —— 「统一人口账」当场变成口号。
      //   方向 fail-closed：宁可在构造期拒，也不留一批无主的人口。旧档（groups 空）天然通过。
      PopulationGroup group = entry.getValue();
      if (!populationsCopy.containsKey(group.residence())) {
        throw new IllegalArgumentException(
            "人口批次 "
                + entry.getKey()
                + " 落在没有 populations 序列的格 "
                + group.residence()
                + " 上（跨组件校验，设计稿 §十.7）");
      }
      groupsCopy.put(entry.getKey(), group);
    }
    groups = Collections.unmodifiableMap(groupsCopy); // ★ 冻在赋值处，同上
  }

  /** 往返用例的起点。 */
  public static SocialData empty() {
    return new SocialData(Map.of(), Map.of(), Map.of());
  }

  /** 一个组件一个 with（照 M2 的形制）。 */
  public SocialData withPopulations(Map<HexCoord, PopulationSeries> value) {
    return new SocialData(value, cities, groups);
  }

  /** 一个组件一个 with（照 M2 的形制）。 */
  public SocialData withCities(Map<CityId, SocialCity> value) {
    return new SocialData(populations, value, groups);
  }

  /** 一个组件一个 with（照 M2 的形制）。 */
  public SocialData withGroups(Map<PeopleLotId, PopulationGroup> value) {
    return new SocialData(populations, cities, value);
  }

  // ── 派生量（★ 一律现算，别找地方存 —— 见类注）────────────────────────────────────────

  /**
   * ★★ **该格有没有批次**（R2 的 T0：读口口径的判据）：不是"人数是否为 0" —— 创世给**零人口的格**也落 {@code count=0} 的批次 （见 {@code
   * PopulationSeeder} 的类注），那是"有批次、且为 0"，读口该报 {@code 0} 而**不是**回退旧序列。
   *
   * <p>★ 于是本方法判的是"这一格的人口账**归哪一套**"：有批次 ⇒ 批次是唯一真值源；没有 ⇒ 只剩旧序列（随包 bootstrap 世界 {@code
   * worlds/v17levant.json} 与升级前落盘的每条 revision 就是这一形态）。
   */
  public boolean hasGroupsAt(HexCoord residence) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    for (PopulationGroup group : groups.values()) {
      if (residence.equals(group.residence())) {
        return true;
      }
    }
    return false;
  }

  /** 该格的批次（保序：与 {@link #groups()} 的插入序同序 —— 创世落盘序是确定性的）。 */
  public List<PopulationGroup> groupsAt(HexCoord residence) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    List<PopulationGroup> out = new ArrayList<>();
    for (PopulationGroup group : groups.values()) {
      if (residence.equals(group.residence())) {
        out.add(group);
      }
    }
    return List.copyOf(out);
  }

  /**
   * ★★ **该格人口的读口口径**（R2 的 T0，控制器已裁定）：**有批次 ⇒ 批次求和（真值源）；无批次 ⇒ 回退旧序列**。
   *
   * <p>★★ **为什么不是"一律用批次"**：批次是设计稿 §二 定的真值源，但随包的 bootstrap 世界（{@code
   * worlds/v17levant.json}）**只有旧序列** —— 一律读批次会让"世界还没初始化"看起来像"这一格没人"（0 与"没有数据"在界面上长得一模一样）。
   * 故回退是**口径的一部分**，而"用的是哪一个"必须**读得出来**（{@link PopulationHeadline#source()}）—— 否则两个口径的数字共用一个名字，正是
   * R1.5 留下的"同一资源两个形状"。
   *
   * <p>★ **唯一拼写点**：GUI 的 {@code GET /api/social/population}、MCP 的 {@code simos.social.population} 与
   * {@code PopulationFacet}（{@code /api/facets}）都调本方法 —— 四张面孔一个口径（R1.5 的教训：facet 仍报农村序列）。
   *
   * @param series 该格的农村人口序列（**回退**时读它）；不得为 null
   * @param at 回退时的取值时刻
   */
  public PopulationHeadline headlinePopulationAt(
      HexCoord residence, PopulationSeries series, SimosTimestamp at) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    if (series == null) {
      throw new IllegalArgumentException("series 不得为 null（回退旧序列时要读它）");
    }
    if (at == null) {
      throw new IllegalArgumentException("at 不得为 null");
    }
    if (hasGroupsAt(residence)) {
      return new PopulationHeadline(populationAt(residence), PopulationSource.BATCHES);
    }
    return new PopulationHeadline(series.valueAt(at), PopulationSource.LEGACY_SERIES);
  }

  /**
   * 该格的**人口总量**（现算）：Σ 落在该格的各 {@link PopulationGroup} 的 {@code count}（农村 + 城镇）。
   *
   * <p>★ 它是 R1 之后"该格有多少人"的**唯一**算法。旧算法（{@code populations} 序列的现算值 + 落在该格的各城人口）在 R1
   * 里**与之逐格相等**（创世构造性一致），但那个算法已经不再被任何生产代码使用。
   */
  public long populationAt(HexCoord residence) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    long total = 0L;
    for (PopulationGroup group : groups.values()) {
      if (residence.equals(group.residence())) {
        total += group.count();
      }
    }
    return total;
  }

  /**
   * 某城的**城镇人口**（现算）：该城各批次的 {@code count} 之和 —— {@code SocialCity} 上**不再有这个字段** （R1 的
   * T5：降级为派生量，{@link SocialCity} 只留 id/name/at/region/props）。
   *
   * <p>★ 归属由 {@link PopulationLots#urbanPrefix(CityId)} 的**前缀**给出（{@code urban:&lt;cityId&gt;:}）——
   * 有多少个性别/年龄细分都不影响；故本方法**不需要**读 {@code cities} 的落点、也不需要 {@code tick} （批次的人数是**不随时间变**的量；随时间变的是年龄）。
   *
   * <p>★ **城必须在本表里**：查一个不存在的城 ⇒ 抛（fail-closed）。静默返回 0 会让"id 拼错"看起来像"这座城没人"。
   */
  public long urbanPopulationAt(CityId city) {
    if (city == null) {
      throw new IllegalArgumentException("city 不得为 null");
    }
    if (!cities.containsKey(city)) {
      throw new IllegalArgumentException("城市不存在: " + city + "（拒绝静默返回 0）");
    }
    String prefix = PopulationLots.urbanPrefix(city);
    long total = 0L;
    for (Map.Entry<PeopleLotId, PopulationGroup> entry : groups.entrySet()) {
      if (entry.getKey().value().startsWith(prefix)) {
        total += entry.getValue().count();
      }
    }
    return total;
  }

  // ── R1.5：三个"按格切一刀"的派生量（★ 同样是现算，且**不加任何字段** —— 见设计稿 §三）──────────

  /**
   * 该格的**年龄结构**（现算）：{@link AgeBracket} 三档的人数，**键恒为全部三档**（没有人也是 0，不是缺键）。
   *
   * <p>★★ **人数取自批次，档位取自"锚点 + 时间差"的现算**（{@link PopulationGroup#ageDaysAt(long)} ⇒ {@link
   * AgeBracket#of(long)}）：本方法**不读**、也不许有任何"当前档位"字段 —— 存了它，"变老"就要每天改状态，档位也变成第二份真相 （设计稿 §三
   * 明令："任何'档间转移'都不需要——因为没有档"）。
   *
   * <p>★ 于是同一份批次在**不同 {@code nowTick} 上给出不同的年龄结构**——这正是"读侧就能看见年龄在走"的判据 （{@code SocialDataTest}
   * 里有一条跨档点的用例钉它）。
   *
   * <p>★ 次序 = {@link AgeBracket} 词表序（{@code 0-14 → 15-59 → 60+}），与 {@link #sexRatioAt} 同款：读口的键序
   * 是内容的纯函数，不随 map 插入序抖。
   *
   * @param nowTick 查询时刻（世界日）；档位由它现算
   * @throws IllegalArgumentException {@code residence} 为 null，或某批次的年龄在该时刻为负（往回推到了"还没出生"之前—— {@link
   *     AgeBracket#of(long)} 对负年龄 fail-closed，不静默归档）
   */
  public Map<AgeBracket, Long> ageStructureAt(HexCoord residence, long nowTick) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    Map<AgeBracket, Long> structure = new LinkedHashMap<>();
    for (AgeBracket bracket : AgeBracket.values()) {
      structure.put(bracket, 0L);
    }
    for (PopulationGroup group : groups.values()) {
      if (residence.equals(group.residence())) {
        AgeBracket bracket = AgeBracket.of(group.ageDaysAt(nowTick));
        structure.put(bracket, structure.get(bracket) + group.count());
      }
    }
    return Collections.unmodifiableMap(structure);
  }

  /**
   * 该格的**性别构成**（现算）：{@code MALE} / {@code FEMALE} 的人数，**键恒为两个性别**（没有人也是 0，不是缺键）。
   *
   * <p>★ 它**不需要 {@code nowTick}**：批次的人数是**不随时间变**的量（随时间变的是年龄，见 {@link #ageStructureAt(HexCoord,
   * long)}）——这与 {@link #urbanPopulationAt(CityId)} 同理。
   */
  public Map<Sex, Long> sexRatioAt(HexCoord residence) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    Map<Sex, Long> ratio = new LinkedHashMap<>();
    for (Sex sex : Sex.values()) {
      ratio.put(sex, 0L);
    }
    for (PopulationGroup group : groups.values()) {
      if (residence.equals(group.residence())) {
        ratio.put(group.sex(), ratio.get(group.sex()) + group.count());
      }
    }
    return Collections.unmodifiableMap(ratio);
  }

  /**
   * 该格的**城乡构成**（现算）：城镇 / 农村的人数（{@link UrbanRural}）。{@link UrbanRural#total()} 就是"该格 social
   * 侧的人口总量"——**R1.5 把它与经济侧逐行求和并排放进读口**，"两侧人口一致"才读得出来。
   *
   * <p>★ 城乡**由 lot id 的前缀判**（{@link PopulationLots#isUrban(PopulationGroup)} 的**唯一**拼写点），不从落点推、
   * 也不加"城乡"字段（设计稿 §二 的明令：{@code PopulationGroup} 绝不装标签）。
   */
  public UrbanRural urbanRuralAt(HexCoord residence) {
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
    }
    long urban = 0L;
    long rural = 0L;
    for (PopulationGroup group : groups.values()) {
      if (residence.equals(group.residence())) {
        if (PopulationLots.isUrban(group)) {
          urban += group.count();
        } else {
          rural += group.count();
        }
      }
    }
    return new UrbanRural(urban, rural);
  }
}
