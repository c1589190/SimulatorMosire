package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.sd.set-diplomatic-relation}（GM 桶窄写；D-003 / R6）：固定 {@code sd.SetDiplomaticRelation} 的
 * GM 封装。
 *
 * <p>载荷 = {@code {from,to,kind?,text,tick?}}（命令 handler 的形状）：upsert 一条有向外交关系边；再调一次同 (from,to) 就是
 * **更新自然语言（谈判状态）**。
 *
 * <p>★ **只在 GM 桶**（{@code SimosToolSource.addGmWrites}）：GM 允许为任意 Nation 对写边（不要求"以谁的名义"）；
 * 决策人侧的"不许冒名"版本是另一条同类工具（名称 = 命令类型，见 {@code SetDiplomaticRelationTool}）。 ★ 工具名不是命令类型 ⇒ 不进 catalog /
 * {@code PAYLOAD_HINTS}；命令类型本体已注册并登记载荷提示。 ★ 写面只声明 {@code sd} 命名空间（handler 只产 {@link
 * io.mosire.simos.sd.change.SdChangeSet}），GM 侧 sd 是 unlimited。
 */
public final class SdSetDiplomaticRelationTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一；与命令类型 {@code sd.SetDiplomaticRelation} 不同名）。 */
  public static final String NAME = "simos.sd.set-diplomatic-relation";

  /** 本工具钉死的命令类型（与 handler 的 {@code type()} 同字面）。 */
  private static final String COMMAND_TYPE = "sd.SetDiplomaticRelation";

  public SdSetDiplomaticRelationTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
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
    return "外交关系写入 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 写入/更新一条有向外交关系边：固定 sd.SetDiplomaticRelation，"
        + "载荷 {from,to,kind?,text,tick?}（from/to 必须是已存在的 Nation 且不得相同；kind 自由文本可空；"
        + "text 非空白自然语言、谈判状态记这里；tick 缺省=世界当前 tick、不得记在未来）。"
        + "同 (from,to) 再调一次 = 更新该边，不新增第二条。";
  }

  @Override
  public ResourceManifest resources() {
    return SD_NAMESPACE_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return sdNamespaceWriteResources();
  }
}
