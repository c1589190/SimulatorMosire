package io.mosire.simos.social;

import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import java.util.Collections;
import java.util.LinkedHashMap;
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
}
