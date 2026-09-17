package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import java.util.LinkedHashSet;
import java.util.List;

/** 路线（M3 spec §4.5）：路径点 + 展开后的格序列。**两者的首尾必须一致**，且 {@code path} 是逐格连续的简单路径。 */
public record Route(List<HexCoord> waypoints, List<HexCoord> path) {

  public Route {
    if (waypoints == null || path == null) {
      throw new IllegalArgumentException("waypoints 与 path 都不得为 null");
    }
    waypoints = List.copyOf(waypoints);
    path = List.copyOf(path);
    if (waypoints.size() < 2) {
      throw new IllegalArgumentException("waypoints 至少两个（总纲：多个路径点，最低两个）");
    }
    if (path.size() < 2) {
      throw new IllegalArgumentException("path 至少两格");
    }
    if (!path.get(0).equals(waypoints.get(0))
        || !path.get(path.size() - 1).equals(waypoints.get(waypoints.size() - 1))) {
      throw new IllegalArgumentException("path 的首尾必须等于 waypoints 的首尾");
    }
    int index = 0;
    for (HexCoord hex : path) {
      if (index < waypoints.size() && waypoints.get(index).equals(hex)) {
        index++;
      }
    }
    if (index != waypoints.size()) {
      throw new IllegalArgumentException("waypoints 必须是 path 的子序列");
    }
    for (int i = 1; i < path.size(); i++) {
      if (path.get(i - 1).distanceTo(path.get(i)) != 1) {
        throw new IllegalArgumentException("path 相邻格必须相邻：" + path.get(i - 1) + " → " + path.get(i));
      }
    }
    if (new LinkedHashSet<>(path).size() != path.size()) {
      throw new IllegalArgumentException("path 不得有重复格（A* 产物天然是简单路径）");
    }
  }
}
