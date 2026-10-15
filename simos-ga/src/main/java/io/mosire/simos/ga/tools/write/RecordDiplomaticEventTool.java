package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.DecisionMakerAffiliationPort;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.gov.recordDiplomaticEvent}（**DM + GM 合并成一条**；D5 / D-005 / R6）：固定 {@code
 * gov.RecordDiplomaticEvent} 的窄封装——追加一条外交事件记录（多国谈判逐 tick 记录参与国与内容）。
 *
 * <p>载荷 = 命令形状 {@code {eventId?,tick?,participants[],text}}。
 *
 * <p>★★ <b>2026-10-15 批 L ②：原来的"两面"合并成一条</b>（设计书 §2 N2「乙 + 合并甲」/ §2.1）。合并前是<b>两个类、两个工具名</b>：
 *
 * <ul>
 *   <li>{@code simos.sd.record-diplomatic-event}（{@code roles()={GM}}，<b>显式点名</b>：participants
 *       由载荷直接给）；
 *   <li>{@code gov.RecordDiplomaticEvent}（{@code roles()={DM}}，<b>身份派生</b>：participants 必须含调用者
 *       Nation）。
 * </ul>
 *
 * ⇒ 现在是一条：{@code roles()={DM, GM}} + {@code gate=Ask(SENSITIVE)} ⇒ 设计书 §3.1 的<b>类2 DM_APPROVAL</b>
 * （"DM/GM 混合就是要交给 GM 审核"）；<b>目标解析按调用者身份分支</b>，形制逐条照本仓既有的 {@code
 * GovToolSupport.resolveGov}（"身份解得出来就从身份解，否则要求显式点名"）——不造第三套：
 *
 * <ul>
 *   <li>调用者是 <b>DM</b>（{@link GaToolIdentity#decisionMakerIdOf} 解得出来）⇒ 身份派生署名（{@link
 *       DiplomaticWriteSignature}）：participants 必须含调用者 Nation，不许替别国记名；归属不是 Nation ⇒ 具名拒；
 *   <li>调用者是 <b>GM</b>（解不出决策人 id）⇒ <b>显式点名</b>：不判署名，participants 就是载荷里的那一份（GM 可为任意参与国集合记事件）。
 * </ul>
 *
 * <p>★ <b>资源声明 = ga 命名空间的自己那一块</b>（同 {@code IssueDirectiveTool}）：{@code decisionWriteResources} +
 * {@code READ_ONLY} 缺省策略（使"未表态 ga"者 fail-closed，失效方向不是放宽）；ToolSpec 敏感 ⇒ **决策人直写**走决策人链路的
 * 既有审批（{@code AutoApproveGate → ConfirmGate → 待批}，需 GM 在审批面点头）。工具名不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}；命令类型本体已注册并登记载荷提示。
 *
 * <p>★ <b>决策人只有直写这一条路径</b>：本工具不在 {@code ProposalCatalog}（{@code simos.ga.packet.propose} 的可提议清单）里 ⇒
 * 没有"提议零审批"那条路（2026-10-15 回代码核实）。
 */
public final class RecordDiplomaticEventTool extends AbstractNarrowWriteTool implements SimosTool {

  /** 工具名（全局唯一；与命令类型 {@code gov.RecordDiplomaticEvent} 不同名）。 */
  public static final String NAME = "simos.gov.recordDiplomaticEvent";

  /** 本工具钉死的命令类型（与 handler 的 {@code type()} 同字面）。 */
  private static final String COMMAND_TYPE = "gov.RecordDiplomaticEvent";

  /**
   * 本工具只碰 ga 命名空间；缺省策略取 {@code READ_ONLY}（未表态者不得**写**）——理由见 {@code IssueDirectiveTool} （失效方向取
   * fail-closed，而不是"忘了配前缀反而拿到全量写权"）。
   */
  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final ToolReadPort reads;

  /**
   * ★★ <b>归属读口</b>（宿主注入，见 {@link DecisionMakerAffiliationPort}；2026-10-12 乙收尾新增）：署名判定问它"调用者以谁的名义"
   * ——归属归模块后，ga 不再认识 {@code Affiliation}，也不枚举任何归属种类。★ 只有 DM 分支用它（GM 分支是显式点名）。
   */
  private final DecisionMakerAffiliationPort affiliations;

  public RecordDiplomaticEventTool(
      ToolWritePort writes,
      ToolReadPort reads,
      GaToolIdentity identity,
      DecisionMakerAffiliationPort affiliations,
      String mapId) {
    super(writes, identity, mapId);
    this.reads = Objects.requireNonNull(reads, "reads");
    this.affiliations = Objects.requireNonNull(affiliations, "affiliations");
  }

  @Override
  protected String commandType() {
    return COMMAND_TYPE;
  }

  /**
   * ★★ <b>必须覆写</b>（基类缺省 = {@link #commandType()}）：本批起**工具名 ≠ 命令类型**（{@code
   * simos.gov.recordDiplomaticEvent} vs {@code
   * gov.RecordDiplomaticEvent}）——不覆写就会静默退回"命令类型当工具名"（正是基类 {@code toolName()} 钩子存在的理由，D1）。
   */
  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "外交事件记录 branch="
        + args.get("branch")
        + " expected="
        + args.get("expectedRevision")
        + "（决策人调用时 participants 必须含调用者 Nation）";
  }

  @Override
  public String description() {
    return "追加一条外交事件记录（固定 gov.RecordDiplomaticEvent）：载荷 {eventId?,tick?,participants[],text}；"
        + "participants ≥2 且不重复；text 自然语言；tick 缺省=世界当前 tick 且不得记在未来；eventId 缺省按 tick 合成。"
        + "★ 目标按调用者身份解析：决策人调用时 participants 必须包含调用者自己的 Nation（不许替别国记名）；"
        + "GM 调用时显式点名，participants 原样生效（可为任意参与国集合记录事件）。"
        + "★ 决策人只有【直写】一条路径：它会停在待批、需 GM 在审批面点头（敏感工具走既有审批链；本工具不在 "
        + "simos.ga.packet.propose 的可提议清单里，没有零审批的提议路径）。";
  }

  @Override
  public ResourceManifest resources() {
    return GA_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return decisionWriteResources(context);
  }

  /**
   * ★★ <b>按调用者身份分支</b>（设计书 §2.1）：DM ⇒ 身份派生署名（不许替别国记名）；GM ⇒ 显式点名，不判署名。
   *
   * <p>★ 分支判据就取自 {@link GaToolIdentity#decisionMakerIdOf}——与 {@code GovToolSupport.resolveGov} 逐字同形
   * （"解得出决策人 id ⇒ 走身份派生；否则走显式点名"），不引入第二处"我是不是 GM"的声明。
   */
  @Override
  protected Optional<String> signatureViolation(ToolContext context) {
    if (identity().decisionMakerIdOf(context.identity()).isEmpty()) {
      // GM：显式点名（设计书 §2.1 的 GM 支）。载荷里的 participants 就是目标本身，没有"以谁的名义"这一维。
      return Optional.empty();
    }
    return DiplomaticWriteSignature.violation(context, reads, identity(), affiliations, true);
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
