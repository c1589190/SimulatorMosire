package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code sd.RecordDiplomaticEvent} 决策人窄工具（D5 / D-005 / R6）：**决策人记外交事件**（多国谈判逐 tick 记录）。
 *
 * <p>载荷 = 命令形状 {@code {eventId?,tick?,participants[],text}}。
 *
 * <p>★★ <b>不许替别国记名</b>：{@code participants} 必须包含调用者的 Nation 归属（身份取自 {@link
 * ToolContext#identity()}）；归属不是 Nation ⇒ 具名拒。规则本体与关系边那条共用 {@link DiplomaticWriteSignature}。
 *
 * <p>★ **资源声明 = sd 域的自己那一块**（同 {@code IssueDirectiveTool}）：{@code decisionWriteResources} + {@code
 * READ_ONLY} 缺省策略；ToolSpec 敏感 ⇒ 走决策人链路的既有审批（需 GM 在审批面点头）。
 */
public final class RecordDiplomaticEventTool extends AbstractNarrowWriteTool {

  /** 工具名 = 命令类型（与 {@code IssueDirectiveTool}/{@code SubmitVerdictTool} 同制）。 */
  public static final String NAME = "sd.RecordDiplomaticEvent";

  /** 本工具只碰 sd 命名空间；缺省策略取 {@code READ_ONLY}（未表态者不得**写**）——理由见 {@code IssueDirectiveTool}。 */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final QueryService query;

  public RecordDiplomaticEventTool(
      CoreSimos core, QueryService query, String initiator, String mapId) {
    super(core, initiator, mapId);
    this.query = query;
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "决策人外交事件记录 branch="
        + args.get("branch")
        + " expected="
        + args.get("expectedRevision")
        + "（participants 必须含调用者 Nation）";
  }

  @Override
  public String description() {
    return "决策人追加一条外交事件记录（固定 sd.RecordDiplomaticEvent）："
        + "载荷 {eventId?,tick?,participants[],text}；participants ≥2 且不重复、必须包含调用者自己的 Nation（不许替别国记名）；"
        + "text 自然语言；tick 缺省=世界当前 tick 且不得记在未来；eventId 缺省按 tick 合成。"
        + "需 GM 在审批面点头（敏感工具走既有审批链）。";
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return decisionWriteResources(context);
  }

  @Override
  protected Optional<String> signatureViolation(ToolContext context) {
    return DiplomaticWriteSignature.violation(context, query, true);
  }
}
