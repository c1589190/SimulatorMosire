package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.sd.record-diplomatic-event}（GM 桶窄写；D-005 / R6）：固定 {@code gov.RecordDiplomaticEvent}
 * 的 GM 封装。
 *
 * <p>载荷 = {@code {eventId?,tick?,participants[],text}}：追加一条外交事件记录（多国谈判逐 tick 记录参与国与内容）。
 *
 * <p>★ **只在 GM 桶**：GM 允许为任意参与国集合记录事件；决策人侧的版本要求"参与者含调用者 Nation"（见 {@code
 * RecordDiplomaticEventTool}）。★ 工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}；命令类型本体已注册并登记载荷提示。 ★
 * 写面只声明 {@code sd} 命名空间（handler 只产 sd 变更集）。
 */
public final class SdRecordDiplomaticEventTool extends AbstractNarrowWriteTool
    implements SimosTool {

  /** 工具名（全局唯一；与命令类型 {@code gov.RecordDiplomaticEvent} 不同名）。 */
  public static final String NAME = "simos.sd.record-diplomatic-event";

  /** 本工具钉死的命令类型（与 handler 的 {@code type()} 同字面）。 */
  private static final String COMMAND_TYPE = "gov.RecordDiplomaticEvent";

  public SdRecordDiplomaticEventTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
  }

  @Override
  protected String commandType() {
    return COMMAND_TYPE;
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "外交事件记录 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 追加一条外交事件记录：固定 gov.RecordDiplomaticEvent，"
        + "载荷 {eventId?,tick?,participants[],text}（participants ≥2 且不重复；text 非空白自然语言；"
        + "tick 缺省=世界当前 tick、不得记在未来；eventId 缺省按 tick 合成）。";
  }

  @Override
  public ResourceManifest resources() {
    return SD_NAMESPACE_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return sdNamespaceWriteResources();
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
