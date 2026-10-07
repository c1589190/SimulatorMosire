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
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.actor.spi.RemitGovTreasuryHandler;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.gov.transferTreasury}（Z3c-2，F2 前置 G1）：国库注资 / 政府间转账的 GM 窄工具</b> —— 包装 {@code
 * actor.RemitGovTreasury}：源国库（或任意家户账户）扣 grain/cloth/money，目标 GOV 国库加；一条命令 = 一条 revision、整条原子。
 *
 * <p>★★ <b>源二选一</b>：{@code fromGovId}（政府间转账，源 = 该 GOV 的国库 {@code hh-gov-<id>}）或 {@code
 * fromHousehold}（国库注资：任意已有账户的家户，如 {@code hh-gov-world-silver} 或私营大户）；两者必须恰给一个。 目标恒为 {@code toGovId}
 * 的国库。
 *
 * <p>★★ <b>只做显式转账</b>：不自动注资、不自动调预算/税、不改任何政策；金额不足/源账不存在由域层 handler 具名拒，工具 preview 先把源账户可支配量摆出来（账不存在
 * ⇒ null，不填 0）。
 *
 * <p>★ <b>只在 GM 桶</b>（决策人路径走既有 {@code simos.gov.pay}），工具名不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS （它提交的
 * {@code actor.RemitGovTreasury} 已由 handler 注册）。
 */
public final class GovTransferTreasuryTool implements AgentTool {

  /** 工具名（全局唯一；设计书 §5 的 {@code simos.gov.transferTreasury}）。 */
  public static final String NAME = "simos.gov.transferTreasury";

  /** 本工具提交的唯一命令类型。 */
  private static final String COMMAND_TYPE = RemitGovTreasuryHandler.TYPE;

  /** 粮（{@link EconomyCommodities#GRAIN} 唯一字面量来源）。 */
  private static final CommodityId GRAIN = EconomyCommodities.GRAIN;

  /** 布。 */
  private static final CommodityId CLOTH = EconomyCommodities.CLOTH;

  /** 只写 actor 命名空间（GM 侧 unlimited）。 */
  private static final ResourceManifest ACTOR_WRITE =
      ResourceManifest.of(ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.ACTOR_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  public GovTransferTreasuryTool(CoreSimos core, QueryService query, String initiator) {
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
    return "GM 国库注资 / 政府间转账（actor.RemitGovTreasury 窄封装，一条命令 = 一条 revision、整条原子）："
        + "从 fromGovId 的国库（或显式 fromHousehold 的账户）扣 grain/cloth/money，加到 toGovId 的国库；"
        + "参数 {toGovId(必填), fromGovId?（政府间转账，与 fromHousehold 恰给一个）, fromHousehold?（注资源家户，"
        + "必须已有账户）, grain?/cloth?/money?(缺省 0；负数拒、三项全 0 拒), reason(必填非空白，审计留痕), "
        + "preview?(缺省 true=只读预览), branch?, expectedRevision?(preview=false 必填)}。"
        + "★ 只做显式转账：不自动注资、不调预算/税；金额/余额/冻结/原子由域层判。只在 GM 桶。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "toGovId",
        ToolSupport.prop("string", "目标 GOV 单位 id（必填；必须存在且带 GovernmentFormation、有当刻有效位置）"));
    props.put(
        "fromGovId", ToolSupport.prop("string", "源 GOV 单位 id（可选；政府间转账用；与 fromHousehold 恰给一个）"));
    props.put(
        "fromHousehold", ToolSupport.prop("string", "源家户 id（可选；国库注资用，必须已有账户；与 fromGovId 恰给一个）"));
    props.put("grain", ToolSupport.prop("integer", "转账粮（可选，缺省 0；不得为负；三项至少一个 > 0）"));
    props.put("cloth", ToolSupport.prop("integer", "转账布（可选，缺省 0；不得为负；至少一个 > 0）"));
    props.put("money", ToolSupport.prop("integer", "转账银（毫银；可选，缺省 0；不得为负；至少一个 > 0）"));
    props.put("reason", ToolSupport.prop("string", "转账原因（必填非空白；进命令载荷与结果）"));
    props.put(
        "preview", ToolSupport.prop("boolean", "true（缺省）= 只读预览；false = 提交 actor.RemitGovTreasury"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("toGovId", "reason"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ACTOR_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "国库转账 to="
            + args.get("toGovId")
            + " fromGov="
            + args.getOrDefault("fromGovId", "-")
            + " fromHousehold="
            + args.getOrDefault("fromHousehold", "-")
            + " grain="
            + args.getOrDefault("grain", 0)
            + " cloth="
            + args.getOrDefault("cloth", 0)
            + " money="
            + args.getOrDefault("money", 0)
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String toGovId = ToolSupport.requiredText(args, "toGovId");
      String fromGovId = ToolSupport.optionalText(args, "fromGovId", null);
      String fromHouseholdId = ToolSupport.optionalText(args, "fromHousehold", null);
      if ((fromGovId == null) == (fromHouseholdId == null)) {
        throw new IllegalArgumentException(
            "fromGovId 与 fromHousehold 必须恰给一个（政府间转账给 fromGovId；国库注资给 fromHousehold）");
      }
      long grain = optionalAmount(args, "grain");
      long cloth = optionalAmount(args, "cloth");
      long money = optionalAmount(args, "money");
      String amountViolation = amountViolation(grain, cloth, money);
      if (amountViolation != null) {
        throw new IllegalArgumentException(amountViolation);
      }
      String reason = ToolSupport.requiredText(args, "reason");
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      SimulationState state = GovToolSupport.stateAt(query, preview, expectedRevisionArg, branch);
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;

      TreasuryLocation to = resolveGovTreasury(state, toGovId, "toGovId");
      SourceLocation from =
          fromGovId != null
              ? resolveGovSource(state, fromGovId)
              : resolveHouseholdSource(state, fromHouseholdId);
      if (from.householdId().equals(to.householdId())) {
        throw new IllegalArgumentException(
            "源与目标账户不得相同: " + from.householdId() + "（同一本账自己转给自己没有动作）");
      }
      Map<String, Object> payload =
          commandPayload(from.householdId(), to.householdId(), grain, cloth, money, reason);
      String payloadJson = ToolSupport.json(payload);
      Map<String, Object> view =
          view(state, from, to, grain, cloth, money, reason, payloadJson, preview);
      if (preview) {
        return ToolSupport.ok(view);
      }
      return GovToolSupport.submitOne(
          core,
          view,
          initiator,
          UUID.randomUUID().toString(),
          COMMAND_TYPE,
          payloadJson,
          branch,
          expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "国库转账失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 可选金额（缺省 0）。 */
  private static long optionalAmount(Map<String, Object> args, String name) {
    Long value = ToolSupport.optionalLong(args, name);
    return value == null ? 0L : value;
  }

  /** 金额边界（负数 / 全 0）：违例理由；合法 ⇒ null。 */
  private static String amountViolation(long grain, long cloth, long money) {
    if (grain < 0L || cloth < 0L || money < 0L) {
      return "grain/cloth/money 都不得为负: grain=" + grain + ", cloth=" + cloth + ", money=" + money;
    }
    if (grain == 0L && cloth == 0L && money == 0L) {
      return "grain/cloth/money 至少一个必须 > 0（至少转账一种资源）";
    }
    return null;
  }

  /** 目标/源 GOV 的国库落点：单位存在 + GovernmentFormation + 当刻有效位置 + 政府家户。 */
  private static TreasuryLocation resolveGovTreasury(
      SimulationState state, String rawUnitId, String field) {
    UnitId unitId = UnitId.parse(rawUnitId);
    UnitState units = ToolSupport.unitState(state);
    Unit unit = units.units().get(unitId);
    if (unit == null) {
      throw new IllegalArgumentException("参数 " + field + " 指定的单位不存在: " + rawUnitId);
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
      throw new IllegalArgumentException(
          "参数 " + field + " 指定的单位没有 GovernmentFormation，不能作为 GOV: " + rawUnitId);
    }
    HexCoord at =
        units
            .effectivePosition(unitId, state.meta().timestamp())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "参数 " + field + " 指定的 GOV 单位没有当刻有效位置（国库落点未知）: " + rawUnitId));
    return new TreasuryLocation(
        rawUnitId,
        at,
        GovernmentHouseholdResolver.requireGovernmentHousehold(unit, rawUnitId).value());
  }

  /** GOV 源（政府间转账）：口径与目标同源，单位必须存在/带编制/有位置。 */
  private static SourceLocation resolveGovSource(SimulationState state, String rawUnitId) {
    TreasuryLocation treasury = resolveGovTreasury(state, rawUnitId, "fromGovId");
    return new SourceLocation(treasury.unitId(), treasury.at(), treasury.householdId());
  }

  /** 家户源（国库注资）：家户必须存在；HEX 位置给坐标（UNIT 户无格 ⇒ null）；账户缺失留给域层具名拒。 */
  private static SourceLocation resolveHouseholdSource(
      SimulationState state, String rawHouseholdId) {
    HouseholdId householdId = HouseholdId.parse(rawHouseholdId);
    var household = ToolSupport.socialData(state).households().get(householdId);
    if (household == null) {
      throw new IllegalArgumentException("源家户不存在: " + rawHouseholdId);
    }
    HexCoord at = household.location() instanceof HouseholdLocation.Hex hex ? hex.hex() : null;
    return new SourceLocation(null, at, householdId.value());
  }

  /** 一条 {@code actor.RemitGovTreasury} 的载荷（字段名与 handler 解析契约一致）。 */
  private static Map<String, Object> commandPayload(
      String fromHousehold, String toHousehold, long grain, long cloth, long money, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("fromHousehold", fromHousehold);
    payload.put("toHousehold", toHousehold);
    payload.put("grain", grain);
    payload.put("cloth", cloth);
    payload.put("money", money);
    payload.put("reason", reason);
    return payload;
  }

  private static Map<String, Object> view(
      SimulationState state,
      SourceLocation from,
      TreasuryLocation to,
      long grain,
      long cloth,
      long money,
      String reason,
      String payloadJson,
      boolean preview) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", false);
    view.put("from", sourceView(state, from));
    view.put("to", toView(to));
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("grain", grain);
    requested.put("cloth", cloth);
    requested.put("money", money);
    view.put("requested", requested);
    view.put("reason", reason);
    view.put("payableAuditable", true);
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", COMMAND_TYPE);
    command.put("payloadJson", payloadJson);
    view.put("commandsPreview", List.of(command));
    return view;
  }

  /** 源视图：{@code {unitId?, householdId, q?, r?, available}}（账不存在 ⇒ available:null，不填 0）。 */
  private static Map<String, Object> sourceView(SimulationState state, SourceLocation from) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("unitId", from.unitId());
    view.put("householdId", from.householdId());
    view.put("q", from.at() == null ? null : from.at().q());
    view.put("r", from.at() == null ? null : from.at().r());
    view.put("available", availableOf(state, from.householdId()));
    return view;
  }

  /** 目标视图：{@code {unitId,q,r,treasuryHousehold,available}}。 */
  private static Map<String, Object> toView(TreasuryLocation to) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("unitId", to.unitId());
    view.put("q", to.at().q());
    view.put("r", to.at().r());
    view.put("householdId", to.householdId());
    return view;
  }

  /** 账户可支配量（{@code {grain,cloth,money}}）；账不存在 ⇒ null（不填 0）。 */
  private static Map<String, Object> availableOf(SimulationState state, String householdId) {
    ActorData actors = ApiViews.actorData(state);
    HouseholdInventory inventory =
        actors.accounts().get(new HouseholdAccountKey(HouseholdId.parse(householdId)));
    if (inventory == null) {
      return null;
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("grain", AvailableStock.available(inventory, GRAIN));
    view.put("cloth", AvailableStock.available(inventory, CLOTH));
    view.put("money", AvailableStock.available(inventory, MoneyVocabulary.SILVER_CURRENCY));
    return view;
  }

  /** 国库落点。 */
  private record TreasuryLocation(String unitId, HexCoord at, String householdId) {}

  /** 源落点：GOV 国库（{@code unitId} 非空）或任意家户账户（{@code unitId == null}，坐标可空）。 */
  private record SourceLocation(String unitId, HexCoord at, String householdId) {}
}
