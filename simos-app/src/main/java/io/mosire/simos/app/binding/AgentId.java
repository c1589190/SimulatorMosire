package io.mosire.simos.app.binding;

import java.util.Objects;

/**
 * 决策人的稳定身份（spec §6）：形如 {@code agent:<id>}（C21 的 {@code <kind>:<id>} 形态）。
 *
 * <p>★ 与 {@link BindingId} 同属"运行期标识"，不进世界状态；{@code agent:} 前缀是 C21 口径的落点——MCP 路径的 {@code
 * initiator}（{@code agent:external-mcp}）与绑定里的 AgentId 同形，便于对账。
 */
public record AgentId(String value) {

  private static final String PREFIX = "agent:";

  public AgentId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("AgentId 不得为空白");
    }
    if (!value.startsWith(PREFIX) || value.length() == PREFIX.length()) {
      throw new IllegalArgumentException("AgentId 必须是 agent:<id> 形态（C21）: " + value);
    }
  }
}
