package io.mosire.simos.social;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.population.PopulationSeries;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 社会状态（M3 spec §3.1）：当前只有"每个 hex 一条人口序列"。
 *
 * <p>★ {@code populations} **保序不可变**：{@code LinkedHashMap} + {@code unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——它的迭代序不是内容的纯函数（M2 实测），字节级往返因此不成立。
 */
public record SocialData(Map<HexCoord, PopulationSeries> populations) {

  public SocialData {
    if (populations == null) {
      throw new IllegalArgumentException("populations 不得为 null");
    }
    Map<HexCoord, PopulationSeries> copy = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, PopulationSeries> entry : populations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("populations 的键与值都不得为 null");
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    populations = Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
  }

  /** 往返用例的起点。 */
  public static SocialData empty() {
    return new SocialData(Map.of());
  }

  /** 一个组件一个 with（照 M2 的形制）。 */
  public SocialData withPopulations(Map<HexCoord, PopulationSeries> value) {
    return new SocialData(value);
  }
}
