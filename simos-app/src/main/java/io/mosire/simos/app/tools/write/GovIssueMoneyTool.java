package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.spi.EconomyRecordMoneyIssuanceHandler;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.gov.issueMoney}（A1 2026-10-08；约束设计书 §3.1-3）</b>：<b>DM 定发多少</b> ——
 * 把一笔新铸的钱记进<b>自己所属 GOV 的国库</b>，并在发行审计里留下一条具名记录。
 *
 * <p>★★ <b>一条命令 = 一条 revision，一份批 = 两条命令</b>（这是本工具唯一复杂的地方，理由必须写清）：
 *
 * <pre>
 * ① actor.AdjustAccounts        国库家户账 money[currency] += amountMilli   ← 余额腿（钱真的到了国库）
 * ② economy.RecordMoneyIssuance 一条 MoneyIssuanceRecord（FISCAL_ISSUE）    ← 审计腿（这笔钱从哪来）
 * 一批 = 一条 revision = 原子（{@code CoreSimos#submitBatch}）⇒ 两条腿不可能只落一条。
 * </pre>
 *
 * <p>为什么不能是一条命令：账户余额住在 {@code actor} 切片、发行审计住在 {@code economy} 切片，而一条命令只写一个命名空间 （铁律
 * 3/4：领域模块只拥有自己的数据）。这与 {@code GovCreateOfficeTool}（{@code actor.EnsureHouseholdAccount} + {@code
 * economy.RegisterGovernment}）是同一形制。
 *
 * <p>★★ <b>权限不得放大（M6）</b>：只有<b>该币种的发行 GOV</b>能发它 —— ① 目标 GOV 由身份派生（决策人给别的 GOV ⇒ 具名拒）② handler 判
 * {@code Government.issuable} ③ GM 审批链。三层都过才落得下一条 revision。
 *
 * <p>★★ <b>它不是铸币生产方式</b>（用户 §1.1「铸币暂缓」仍有效）：本工具是"DM 定发多少"的行政发行 + 审计， 不接产业产出、不做成色/铸熔；周期铸币仍归 {@code
 * Government.seignioragePerCycle} 与 {@code GovernmentSeigniorage} 那条既有路径。
 *
 * <p>★ 资源声明：{@code actor}（断言自己国库所在格的账路径，身份派生）+ {@code economy}（世界级审计表）。 决策人的 {@code actor}
 * 可达面天然含"自己 + superiorGov 的国库格"（{@link io.mosire.simos.app.access.GovScope}）⇒ 自己那一笔必过、别人的必拒；GM 侧两面都
 * unlimited。
 */
public final class GovIssueMoneyTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.issueMoney";

  /** 余额腿的命令类型（与 handler 的拼写点同源）。 */
  private static final String ADJUST_TYPE = "actor.AdjustAccounts";

  /** 审计腿的命令类型（与 handler 的 {@code TYPE} 同源）。 */
  private static final String ISSUANCE_TYPE = EconomyRecordMoneyIssuanceHandler.TYPE;

  /** 两面：actor 的国库账（身份派生）+ economy 的发行审计表。 */
  private static final ResourceManifest MONEY_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（{@code preview=false} 时提交一批 = 一条 revision）
   * @param query 只读入口（preview / apply 共用同一份 base 状态解析）
   * @param initiator 落盘时的发起者（{@code <kind>:<id>} 形态）
   */
  public GovIssueMoneyTool(CoreSimos core, QueryService query, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "DM 给自己所属 GOV 发行一笔新钱（国库余额 +amountMilli，并落一条 MoneyIssuance 审计；"
        + "一批两条命令 = 一条 revision，原子）：参数 "
        + "{govUnitId?(GM 必填；决策人省略=自己的 GOV，给了必须等于自己的), currency(必填：必须在该 GOV 的 issuable 里), "
        + "amountMilli(必填，> 0), kind?(缺省 FISCAL_ISSUE；也可 INITIAL_ENDOWMENT), reason?, preview?(缺省 true=只读), "
        + "branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填)}。"
        + "★ 需 GM 在审批面点头。★ 不是铸币生产方式（不做成色/铸熔/产业产出）；发给别国或不发行的币种 ⇒ 具名拒。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "govUnitId",
        ToolSupport.prop("string", "发行主体 GOV 单位 id（GM 必填；决策人省略 = 自己的 GOV，给出别的 GOV ⇒ 越权拒）"));
    props.put("currency", ToolSupport.prop("string", "币种 id（必填；必须在该 GOV 的 issuable 里）"));
    props.put("amountMilli", ToolSupport.prop("integer", "发行金额（最小币值；必填；> 0；方向由 kind 表达，不用负号）"));
    props.put(
        "kind",
        ToolSupport.prop("string", "发行类别（可选）：FISCAL_ISSUE（缺省，运行期发行）| INITIAL_ENDOWMENT（创世注资）"));
    props.put("reason", ToolSupport.prop("string", "审计原因（可选；给出则必须非空白；缺省 = gm:命令类型）"));
    props.put("preview", ToolSupport.prop("boolean", "true（缺省）= 只读预览；false = 提交一批（= 一条 revision）"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("currency", "amountMilli"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：决策人链路走 AutoApproveGate → ConfirmGate → 待批（需 GM 点头）。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return MONEY_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "DM 发行货币 currency="
            + args.get("currency")
            + " amountMilli="
            + args.get("amountMilli")
            + " kind="
            + args.getOrDefault("kind", MoneyIssuanceKind.FISCAL_ISSUE.name())
            + " govUnitId="
            + args.getOrDefault("govUnitId", "(身份派生)")
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH)
            + "（只能自己的 GOV，需 GM 审批）",
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String currencyText = ToolSupport.requiredText(args, "currency");
      long amountMilli = ToolSupport.requiredLong(args, "amountMilli");
      if (amountMilli <= 0L) {
        throw new IllegalArgumentException(
            "参数 amountMilli 必须 > 0（方向由 kind 表达，不用负号）: " + amountMilli);
      }
      MoneyIssuanceKind kind = kindArg(args);
      String reason = ToolSupport.optionalText(args, "reason", null);
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      SimulationState state = GovToolSupport.stateAt(query, preview, expectedRevisionArg, branch);
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      GovToolSupport.GovTarget target =
          GovToolSupport.resolveGov(
              context, state, ToolSupport.optionalText(args, "govUnitId", null));
      if (target.at().isEmpty()) {
        // ★ 国库落点未知 ⇒ 余额腿没有格路径可断言（与 GovPayTool 的付款人同一条具名拒）。
        throw new GovToolSupport.GovRejectedException(
            "调用者所属 GOV 没有当刻有效位置（国库落点未知）: " + target.govId().value());
      }
      ToolSupport.requireAll(
          context,
          Operation.WRITE,
          List.of(
              ResourceId.of(
                  ToolSupport.ACTOR_NAMESPACE,
                  ResourcePaths.actor(target.at().get().q(), target.at().get().r())),
              ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*")));

      EconomyData data = economyData(state);
      CurrencyId currency = new CurrencyId(currencyText);
      GovernmentId governmentId = GovernmentIds.ofUnit(target.govId().value());
      Government government = data.governments().get(governmentId);
      if (government == null) {
        // ★ 身份/归属层面的缺口（改参数改不了）⇒ REJECTED，与"参数形状错"分开。
        throw new GovToolSupport.GovRejectedException(
            "GOV 单位未登记为政府（先 economy.RegisterGovernment）: " + governmentId.value());
      }
      if (!data.currencies().containsKey(currency)) {
        throw new IllegalArgumentException("币种 " + currencyText + " 在本世界的词表里没有定义");
      }
      if (!government.issuable().contains(currency)) {
        // ★ M6：越权发行 —— 说不出"这是我发的钱"就不许发（权限判据 ⇒ REJECTED，不是 BAD_REQUEST）。
        throw new GovToolSupport.GovRejectedException(
            "政府 "
                + governmentId.value()
                + " 不发行币种 "
                + currencyText
                + "（issuable="
                + government.issuable()
                + "；只有发行政府能发它）");
      }

      String batchId = UUID.randomUUID().toString();
      List<CommandEnvelope> batch =
          List.of(
              GovToolSupport.envelope(
                  initiator,
                  batchId,
                  branch,
                  new RevisionId(expectedRevision),
                  ADJUST_TYPE,
                  adjustPayload(target, currencyText, amountMilli)),
              GovToolSupport.envelope(
                  initiator,
                  batchId,
                  branch,
                  new RevisionId(expectedRevision),
                  ISSUANCE_TYPE,
                  issuancePayload(target, currencyText, amountMilli, kind, reason)));
      Map<String, Object> view =
          view(preview, target, currencyText, amountMilli, kind, reason, data, batch);
      if (preview) {
        return ToolSupport.ok(view);
      }
      return GovToolSupport.submitBatch(core, view, batchId, batch);
    } catch (GovToolSupport.GovRejectedException e) {
      return ToolResult.error("REJECTED", e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 资源拒因原样逃到 ToolCallAuthorizer 边界（不折成 TOOL_ERROR）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "发行货币失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** {@code kind} 参数（缺省 FISCAL_ISSUE；回笼不在本批）。 */
  private static MoneyIssuanceKind kindArg(Map<String, Object> args) {
    String text = ToolSupport.optionalText(args, "kind", MoneyIssuanceKind.FISCAL_ISSUE.name());
    MoneyIssuanceKind kind;
    try {
      kind = MoneyIssuanceKind.valueOf(text.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "参数 kind 只认 "
              + MoneyIssuanceKind.FISCAL_ISSUE.name()
              + " | "
              + MoneyIssuanceKind.INITIAL_ENDOWMENT.name()
              + ": "
              + text);
    }
    if (kind == MoneyIssuanceKind.WITHDRAWAL) {
      throw new IllegalArgumentException("本批只做发行/创世注资，不做回笼（WITHDRAWAL 属后续批次）");
    }
    return kind;
  }

  /** 余额腿：国库家户账的纯正增量（缺账由该命令建账）。 */
  private static String adjustPayload(
      GovToolSupport.GovTarget target, String currency, long amountMilli) {
    Map<String, Object> money = new LinkedHashMap<>();
    money.put(currency, amountMilli);
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("household", target.governmentHousehold().value());
    entry.put("money", money);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("entries", List.of(entry));
    return ToolSupport.json(payload);
  }

  /** 审计腿：一条 {@code MoneyIssuanceRecord}（kind 由参数给；id 由 handler 按基态确定性派生）。 */
  private static String issuancePayload(
      GovToolSupport.GovTarget target,
      String currency,
      long amountMilli,
      MoneyIssuanceKind kind,
      String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("govUnitId", target.govId().value());
    payload.put("currency", currency);
    payload.put("amountMilli", amountMilli);
    payload.put("kind", kind.name());
    if (reason != null) {
      payload.put("reason", reason);
    }
    return ToolSupport.json(payload);
  }

  /** preview / apply 共用的视图：两面（余额腿 + 审计腿）+ 发行前的余额读数。 */
  private static Map<String, Object> view(
      boolean preview,
      GovToolSupport.GovTarget target,
      String currency,
      long amountMilli,
      MoneyIssuanceKind kind,
      String reason,
      EconomyData data,
      List<CommandEnvelope> batch) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", false);
    view.put("govUnitId", target.govId().value());
    view.put("govDerivedFromIdentity", target.decisionMaker());
    view.put("treasuryHousehold", target.governmentHousehold().value());
    view.put("currency", currency);
    view.put("amountMilli", amountMilli);
    view.put("kind", kind.name());
    view.put("reason", reason);
    view.put("issuanceRecordsBefore", data.moneyIssuances().size());
    view.put("at", target.at().map(ToolSupport::hexCoord).orElse(null));
    List<Map<String, Object>> commands = new ArrayList<>(GovToolSupport.commandsPreview(batch));
    view.put("commandsPreview", List.copyOf(commands));
    return view;
  }

  /** economy 切片（缺切片/类型不符 = 装配故障，不当成"没有这套制度"）。 */
  private static EconomyData economyData(SimulationState state) {
    Snapshot snapshot =
        state
            .module("economy")
            .orElseThrow(() -> new IllegalStateException("state 里没有 economy 切片（装配故障）"));
    if (snapshot instanceof EconomySnapshot economySnapshot) {
      return economySnapshot.data();
    }
    throw new IllegalStateException(
        "state 的 economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
  }
}
