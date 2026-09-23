package io.mosire.simos.social;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationSeries;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 社会状态（M3 spec §3.1 + 城市节点）：每个 hex 一条人口序列 + 城市节点表。
 *
 * <p>★★ **{@code populations} 装的是该格的农村/村镇人口（rural）**——不是该格的"总人口"。城市人口在 {@link
 * SocialCity#population()} 上（**城镇部分**）。该 hex 的"总人口"是**派生量**（该格农村 + 落在这个格上的各城市之和），**不落盘**：
 * 落盘只会多出一份会漂移的冗余（城市增删/改人口时都要同步它），而且城乡口径再也分不开。后来者要算总量，请**现算**，别找地方存。
 *
 * <p>★ {@code cities} 是城市节点表（键是 {@link CityId}）——它的**落点**由 {@link SocialCity#at()}
 * 给出，故"某格有哪些城市"是从本表 **派生**的（不另存索引）。
 *
 * <p>★ **两个 map 都保序不可变**：{@code LinkedHashMap} + {@code unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——它的迭代序不是内容的纯函数（M2 实测），字节级往返因此不成立。
 */
public record SocialData(
    Map<HexCoord, PopulationSeries> populations, Map<CityId, SocialCity> cities) {

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
  }

  /** 往返用例的起点。 */
  public static SocialData empty() {
    return new SocialData(Map.of(), Map.of());
  }

  /** 一个组件一个 with（照 M2 的形制）。 */
  public SocialData withPopulations(Map<HexCoord, PopulationSeries> value) {
    return new SocialData(value, cities);
  }

  /** 一个组件一个 with（照 M2 的形制）。 */
  public SocialData withCities(Map<CityId, SocialCity> value) {
    return new SocialData(populations, value);
  }
}
