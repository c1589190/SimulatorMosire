package io.mosire.simos.unit.move;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 移动物化的结果（M3 spec §4.5）：**当前在哪、正在走哪一段、还欠多少毫 MP**。
 *
 * <p>★ 三个字段与 {@link MovementStatus} 的搭配是**冻结语义**，构造期即拒自相矛盾的组合： `IN_TRANSIT` ⇒ `nextHex` present 且
 * `remaining > 0`；`ARRIVED` / `NEED_REPLAN` ⇒ 两者皆空。
 */
public record MovementState(
    HexCoord currentHex,
    Optional<HexCoord> nextHex,
    OptionalLong remainingEdgeCostMillis,
    MovementStatus status) {

  public MovementState {
    Objects.requireNonNull(currentHex, "currentHex");
    Objects.requireNonNull(nextHex, "nextHex");
    Objects.requireNonNull(remainingEdgeCostMillis, "remainingEdgeCostMillis");
    Objects.requireNonNull(status, "status");
    if (status == MovementStatus.IN_TRANSIT) {
      if (nextHex.isEmpty()
          || remainingEdgeCostMillis.isEmpty()
          || remainingEdgeCostMillis.getAsLong() <= 0) {
        throw new IllegalArgumentException(
            "IN_TRANSIT 必须有下一格且未付清的余量严格 > 0: " + remainingEdgeCostMillis);
      }
    } else if (nextHex.isPresent() || remainingEdgeCostMillis.isPresent()) {
      throw new IllegalArgumentException(status + " 不得带 nextHex 或余量");
    }
  }
}
