package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.sd.spi.DecisionSignature;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code sd.IssueDirective} 窄工具（spec §八.3，N9）：决策人出令的**唯一**写面。
 *
 * <p>★ **资源声明 = sd 域的自己那一块**（T10）：出令是**决策行为**，不是"直接改地图/单位数据"（用户 2026-09-22 原话
 * 「决策人不能直接改地图等数据」——下指令是它的本职，spec §2.2 把这条放进决策人白名单就是这条意思）。故它
 * **不复用**基类缺省的三命名空间粗断言：那条在受限调用者身上会**整调拒掉**（粗断言撞细围栏，T5-T8 实测发现 1）。
 */
public final class IssueDirectiveTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.IssueDirective";

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
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  public IssueDirectiveTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
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
    return "决策人出令：固定 sd.IssueDirective，载荷 {directiveId, decisionMakerId, tick, target?, intentInfo, commands[], effects[]?}";
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
        DecisionCallerFactory.decisionMakerIdOf(context.identity()),
        ToolSupport.optionalText(context.arguments(), "payloadJson", "{}"));
  }
}
