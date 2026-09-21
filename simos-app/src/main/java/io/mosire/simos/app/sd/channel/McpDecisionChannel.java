package io.mosire.simos.app.sd.channel;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.sd.channel.ActorId;
import java.util.Set;
import java.util.function.Supplier;

/** MCP 经工具面提交决策（spec §十三.3，D5）：新增渠道 = app 层加一个实现 + 装配一行，领域代码零改动。 */
public final class McpDecisionChannel extends AbstractCoreDecisionChannel {

  public static final String CHANNEL_ID = "mcp";

  private static final String INITIATOR = "agent:mcp";

  public McpDecisionChannel(CoreSimos core, Supplier<Set<ActorId>> representableActors) {
    super(core, representableActors, INITIATOR);
  }

  @Override
  public String channelId() {
    return CHANNEL_ID;
  }
}
