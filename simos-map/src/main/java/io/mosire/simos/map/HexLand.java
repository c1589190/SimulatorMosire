package io.mosire.simos.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单 hex 的土地三档树：{@code land type → area components}。
 *
 * <p>{@code byType} 按 {@link LandTypeId#wireValue()} 规范升序冻结；每个 area 的 type 必须与 map key 相等。
 *
 * @param byType 土地类型 → 该类型面积成分（保序不可变）
 */
public record HexLand(Map<LandTypeId, LandTypeArea> byType) {

  public HexLand {
    if (byType == null) {
      throw new IllegalArgumentException("HexLand.byType 不得为 null；空土地用 HexLand.empty()");
    }
    for (Map.Entry<LandTypeId, LandTypeArea> entry : byType.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("HexLand.byType 的键与值都不得为 null");
      }
      if (!entry.getKey().equals(entry.getValue().type())) {
        throw new IllegalArgumentException(
            "HexLand.byType 的 key 与 area.type 不一致: key="
                + entry.getKey()
                + " type="
                + entry.getValue().type());
      }
    }
    var ordered = new ArrayList<>(byType.entrySet());
    ordered.sort(Comparator.comparing(entry -> entry.getKey().wireValue()));
    var frozen = new LinkedHashMap<LandTypeId, LandTypeArea>();
    for (Map.Entry<LandTypeId, LandTypeArea> entry : ordered) {
      frozen.put(entry.getKey(), entry.getValue());
    }
    byType = Collections.unmodifiableMap(frozen);
  }

  /** 空土地。 */
  public static HexLand empty() {
    return new HexLand(Collections.emptyMap());
  }

  /** 该土地类型总量（毫亩）；没有该 type 时 0。 */
  public long totalMilliMu(LandTypeId type) {
    LandTypeArea area = byType.get(type);
    return area == null ? 0L : area.totalMilliMu();
  }
}
