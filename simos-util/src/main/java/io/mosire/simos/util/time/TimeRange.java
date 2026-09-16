package io.mosire.simos.util.time;

import java.util.Objects;
import java.util.Optional;

/**
 * 有效区间：**左闭右开** `[from, to)`；`to` 缺省表示无上界（spec §十-D5）。
 *
 * <p>`to` 必须严格晚于 `from`——空区间是配置错误，不给它静默存在的机会。
 */
public record TimeRange(SimosTimestamp from, Optional<SimosTimestamp> to) {

  public TimeRange {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    to.ifPresent(
        end -> {
          if (end.compareTo(from) <= 0) {
            throw new IllegalArgumentException(
                "TimeRange 的 to 必须晚于 from（左闭右开区间不得为空）：" + from + " .. " + end);
          }
        });
  }

  /** 自 `from` 起、无上界的区间。 */
  public static TimeRange since(SimosTimestamp from) {
    return new TimeRange(from, Optional.empty());
  }

  /** `t` 是否落在 `[from, to)` 内（同刻判定用 {@code compareTo}，见 {@link SimosTimestamp}）。 */
  public boolean contains(SimosTimestamp t) {
    if (t.compareTo(from) < 0) {
      return false;
    }
    return to.map(end -> t.compareTo(end) < 0).orElse(true);
  }
}
