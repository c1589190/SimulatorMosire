package io.mosire.simos.unit;

import io.mosire.simos.map.hex.HexCoord;
import java.util.List;

/**
 * 路线（M3 spec §4.5）：路径点 + 展开后的格序列。**两者的首尾必须一致**，且 {@code path} 逐格连续。
 *
 * <p>★★ **编制 v2 之后的第二次裁定（2026-09-24，用户原话「巡逻环线肯定是要支持的」）**：{@code path} **允许回到已经
 * 走过的格**——巡逻一圈回原地（`(1,1)→(1,2)→(1,3)→(1,2)→(1,1)`）是一种正当的意图，旧的不变量"path 不得有重复格"
 * 把它连同行军的"回撤"一起挡在门外（旧口径：判定 R4 "跨段回头 ⇒ 拒"）。现在只保证三件事：
 *
 * <ol>
 *   <li>{@code path} 的首格 == {@code waypoints} 的首格、末格 == {@code waypoints} 的末格；
 *   <li>{@code waypoints} 是 {@code path} 的**子序列**（路径点确实都在路上、且按序）；
 *   <li>相邻格**逐格相邻**（{@code path} 是一条真的走法，不是跳点）。
 * </ol>
 *
 * <p>★ **"走完即止"**：环线表达的是一**圈**巡逻，不是永久巡逻——走完 {@code path} 就抵达（{@code Movement} 被清）。
 * 要"一直绕下去"是另一件事（需要新的行程语义），本裁定**不含**它。
 */
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
  }
}
