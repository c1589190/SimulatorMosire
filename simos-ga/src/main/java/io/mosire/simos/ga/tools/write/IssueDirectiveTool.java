package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.sd.spi.DecisionSignature;
import io.mosire.simos.sd.spi.DirectiveIntentInfo;
import io.mosire.simos.sd.spi.PutInfoHandler;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code ga.IssueDirective} 窄工具（spec §八.3，N9）：决策人出令的**唯一**写面。
 *
 * <p>★ **资源声明 = sd 域的自己那一块**（T10）：出令是**决策行为**，不是"直接改地图/单位数据"（用户 2026-09-22 原话
 * 「决策人不能直接改地图/单位数据」——下指令是它的本职，spec §2.2 把这条放进决策人白名单就是这条意思）。故它
 * **不复用**基类缺省的三命名空间粗断言：那条在受限调用者身上会**整调拒掉**（粗断言撞细围栏，T5-T8 实测发现 1）。
 *
 * <p>★★ <b>本工具一次提交<b>两条</b>命令（同批 = 同一条 revision）</b>（2026-10-12 批次 3b′，见 {@link
 * AbstractNarrowWriteTool#companionRequests}）：① 主命令 {@code ga.IssueDirective}（令落 {@code
 * GaState.directives}）；② 配套命令 {@code sd.PutInfo}（出令的**执行原文**条目落 sd INFO 覆盖层 {@code
 * sd:directive.<id>}）。<b>为什么必须两条</b>：一条命令 = 一个命名空间（{@code CommandBus.namespaceOf} 取类型前缀）⇒ 令搬进
 * {@code ga} 之后，同一个 handler 再也写不了仍住 sd 的 {@code info} 表；而执行原文的落点本批不搬（{@code info} 属批次 3b）。配套载荷由
 * {@link DirectiveIntentInfo} 从**同一份出令载荷**派生（地址/键/来源引用的唯一拼写点在那里），故字段知识没有第二份。
 */
public final class IssueDirectiveTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "ga.IssueDirective";

  /**
   * 本工具只碰 sd 命名空间（前置闸据此判"够不够得着这个工具的声明面"）。
   *
   * <p>★★ **缺省策略取 {@code READ_ONLY}，不是 {@code UNRESTRICTED}**——这是 T10 变异轮实测出的一个**真漏洞**： {@code
   * UNRESTRICTED} 下，"调用者**没表态** sd"会回落到"放行"（spec §5.2 第 3 条：空 = 不表态 = 放行）， 于是**忘了给决策人配 sd
   * 前缀**（或配错）时，它反而拿到 sd 的**全量写权**——失效方向是"放宽"，正是本轮要消灭的形态。 取 {@code READ_ONLY}
   * 后，未表态者在**写**操作上被拒（本工具只有写），失效方向变成 fail-closed。
   *
   * <p>★ 不用 {@code DENY}：AgentLib 明确不建议把 {@code DENY} 当"工具硬约束"用（见 {@code ResourcePolicy} 类注——
   * 那会把"谁能碰数据"搬进插件源码，而正确做法是调用者侧配 {@code ResourceScope.none()}）。{@code READ_ONLY} 既拿到同一条 fail-closed
   * 结论、又不越过那条边界。
   */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  public IssueDirectiveTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "决策出令 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "决策人出令：固定 ga.IssueDirective，载荷 {directiveId, decisionMakerId, tick, target?, intentInfo,"
        + " commands[], effects[]?}（同批另落一条 sd.PutInfo 记执行原文）";
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  /** ★ 写自己的决策域（{@code sd:decision-maker/<自己>}）；非决策人身份（GM）回落基类缺省，见基类 javadoc。 */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return decisionWriteResources(context);
  }

  /**
   * ★★ **不许冒名**（T11B）：载荷里的 {@code decisionMakerId} 必须与**调用者身份**一致。
   *
   * <p>资源那一层管不到这件事——决策人 A 的可达面就是 {@code sd:decision-maker/A} 这一条前缀，而"署名 B"的载荷**照样通过**它
   * （断言取自身份、不取自载荷，见 {@link #decisionWriteResources}），落盘后就是一条冒名的 directive。 规则本体住在 sd 域（{@link
   * DecisionSignature}），这里只是把它接上身份。
   */
  @Override
  protected Optional<String> signatureViolation(ToolContext context) {
    return DecisionSignature.violation(
        identity().decisionMakerIdOf(context.identity()),
        ToolSchema.optionalText(context.arguments(), "payloadJson", "{}"));
  }

  /**
   * ★★ <b>配套命令：出令执行原文条目</b>（同批第二条，见类注）。
   *
   * <p>★ 载荷由 {@link DirectiveIntentInfo#putInfoPayloadJson} 从**同一份出令载荷**派生（地址 {@code
   * sd:directive.<id>}、 key {@code intent}、value {@code intentInfo}、tags 决策人、tick、{@code
   * sourceDirective}）。派生不出（载荷缺字段 / id 拼不出合法地址）⇒
   * <b>不追加</b>：主命令照落并自己给出具名拒因（不在这里改判据，也不让一条本该能落的令因配套条目而整批被拒）。
   *
   * <p>★ <b>顺序 = 主命令在前</b>（{@link AbstractNarrowWriteTool#submit} 固定把主命令放批内第 0 位）：拒因与结果视图都以主命令为准。
   */
  @Override
  protected List<ToolWritePort.Request> companionRequests(ToolContext context, String payloadJson) {
    return DirectiveIntentInfo.putInfoPayloadJson(payloadJson)
        .map(
            putInfoPayload ->
                List.of(new ToolWritePort.Request(PutInfoHandler.TYPE, putInfoPayload)))
        .orElseGet(List::of);
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
