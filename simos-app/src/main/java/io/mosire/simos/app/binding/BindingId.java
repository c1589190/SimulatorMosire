package io.mosire.simos.app.binding;

import java.util.Objects;
import java.util.UUID;

/**
 * 绑定的稳定身份（spec §6）：非空白即合法，生成规则归本类。
 *
 * <p>★ 与 {@code SubjectId} 不同，绑定**不是世界状态**——它不进 revision、不落盘，故 id 不需要跨进程可推导；用 {@link UUID}
 * 区分同一次运行里的多条绑定即可。
 */
public record BindingId(String value) {

  public BindingId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("BindingId 不得为空白");
    }
  }

  /** 新 id（随机 UUID）。 */
  public static BindingId generate() {
    return new BindingId(UUID.randomUUID().toString());
  }
}
