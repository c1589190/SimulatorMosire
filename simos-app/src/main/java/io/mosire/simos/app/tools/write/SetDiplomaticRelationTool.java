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
 * {@code sd.SetDiplomaticRelation} 决策人窄工具（D5 / D-003 / R6）：**决策人写外交关系边的唯一入口**。
 *
 * <p>载荷 = 命令 {@code sd.SetDiplomaticRelation} 的形状 {@code {from,to,kind?,text,tick?}}；upsert
 * 有向边，重复调用更新自然语言。
 *
 * <p>★★ <b>不许冒名</b>：载荷 {@code from} 必须等于调用者的 Nation 归属（身份取自 {@link ToolContext#identity()}，与 {@code
 * IssueDirectiveTool} 的 {@code signatureViolation} 同口径）——资源断言管"够不够得着"、管不到"以谁的名义"，故署名 单独判（见 {@link
 * DiplomaticWriteSignature}）。归属不是 Nation（Army/Gov）⇒ 具名拒。
 *
 * <p>★ **资源声明 = sd 域的自己那一块**（同 {@code IssueDirectiveTool}）：本工具只写 sd 组件，且调用者只能是决策人自己的决策域 {@code
 * sd:decision-maker/<自己>}（{@code decisionWriteResources}）；缺省策略取 {@code READ_ONLY} 使"未表态 sd"者
 * fail-closed。
 *
 * <p>★ <b>ToolSpec 敏感 + 既有审批链</b>（基类）：决策人链路 = {@code AutoApproveGate → ConfirmGate → 待批}，GM
 * 需在审批面点头。
 */
public final class SetDiplomaticRelationTool extends AbstractNarrowWriteTool {

  /** 工具名 = 命令类型（与 {@code IssueDirectiveTool}/{@code SubmitVerdictTool} 同制）。 */
  public static final String NAME = "sd.SetDiplomaticRelation";

  /** 本工具只碰 sd 命名空间；缺省策略取 {@code READ_ONLY}（未表态者不得**写**）——理由见 {@code IssueDirectiveTool}。 */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final QueryService query;

  public SetDiplomaticRelationTool(
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
    return "决策人外交关系写入 branch="
        + args.get("branch")
        + " expected="
        + args.get("expectedRevision")
        + "（from 必须是调用者 Nation）";
  }

  @Override
  public String description() {
    return "决策人写入/更新一条有向外交关系边（固定 sd.SetDiplomaticRelation）："
        + "载荷 {from,to,kind?,text,tick?}；from 必须是调用者自己的 Nation（不许冒名），to 必须是已存在的 Nation；"
        + "kind 自由文本可空、text 自然语言（谈判状态记这里）、tick 缺省=世界当前 tick 且不得记在未来。"
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
    return DiplomaticWriteSignature.violation(context, query, false);
  }
}
