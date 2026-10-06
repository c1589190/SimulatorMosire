package io.mosire.simos.app.tools.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.spi.RemitGovTreasuryHandler;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.AdjudicationStatus;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.spi.DirectiveWhitelist;
import io.mosire.simos.sd.spi.SetDirectiveStatusHandler;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code sd.AdjudicateTick} 窄工具（第 3 波第 2 步）：**GM 把某 tick 里所有决策人的令一起判效果、一次落地**。
 *
 * <p>★★ **用户裁定的四条语义**（本类是它们的唯一落点）：
 *
 * <ol>
 *   <li>一个 tick 内**所有决策人**提交的命令，由 GM **一起**判效果（不是每条令各自落地）；
 *   <li>**GM 显式调这个窄工具**触发——不做 {@code simos.advance} 自动结算；
 *   <li>**一个 tick 落一条 revision，原子**（要么全成、要么全不成）；
 *   <li>按**出令决策人自己的可达面**做资源授权：GM 代执行也**不能越权**；被拒的命令，**拒因作为"实在效果"记进该 tick 的决策结果**。
 * </ol>
 *
 * <p>★★ **第 4 条的口径 = 「范围函数 ∩ accessLimit」，不是"只判 accessLimit"**（第 3 波第 2 步的裁定）：只判 {@code
 * accessLimit} 在默认配置下**等于没判**（{@link io.mosire.simos.sd.model.AccessLimit#empty()} 的语义是"不收紧"）⇒
 * 与本意"不能越权"直接相反。故本类用 {@link DecisionCallerFactory#resourceScopesFor}——它是「范围函数 ∩ {@code
 * narrowTo(accessLimit)}」的**唯一装配点**，也正是**该决策人自己发 {@code sd.IssueDirective} 时被授权用的那一份权威** ⇒
 * **不引入第二份真相**。
 *
 * <p>★★ **"这条命令要动谁"从哪来**：{@link CommandTargets}（新契约）——由**各域 handler 自己实现**，
 * 与它们同处一地（同一模块、共用同一套载荷解析）⇒ 命令的目标只有写它的那个模块知道，也不会有第二份会漂移的表。 **未实现该契约（或返回空）⇒ fail-closed 拒**（"判不了越权" ≠
 * "随便动"）。今天有意不实现的四条见那个契约的类注。
 *
 * <p>★★ **一条 revision 是怎么落成的（本类最绕、也最关键的一段）**：决策结果必须写进**同一批**（写成两条 revision 就破了"一个 tick
 * 一条"），而决策结果的内容**要等批的结局才知道**（哪条被 handler 拒了）。故组批 = **〔过检命令…〕+〔本 tick 每条令一条 {@code
 * sd.SetDirectiveStatus}：状态翻转〕+〔一条 {@code sd.PutInfo}：本次裁决的决策结果〕**，跑一个**收敛循环**：
 *
 * <ul>
 *   <li>{@link BatchResult.Committed} ⇒ 成了。这一条 revision 里**同时**含"被接受的命令""每条令的状态翻转"与"这份决策结果"；
 *   <li>{@link BatchResult.Rejected} ⇒ 从逐位对应的 {@code outcomes} 里挑出**这次才暴露的**被拒命令
 *       （前置校验覆盖不到的，如"单位不存在"），把它们**剔出**、拒因**并进**决策结果，**重试**；
 *   <li>{@link BatchResult.Conflict} ⇒ **原样上报**（带真实 head）：不许部分提交、不许改写 {@code expectedRevision} 蒙过去。
 * </ul>
 *
 * <p>★★ **状态翻转（第 3 波最后一块）**：本 tick **每条参与裁决的令**（见 {@link #directivesAt}：被重写顶掉的 {@code SUPERSEDED}
 * 与被打回的 {@code CANCELLED} **不参与**）在同批里带一条 {@code sd.SetDirectiveStatus}——该令的命令**全部被接受** ⇒ {@code
 * EXECUTED}，**有任一被剔除/被拒** ⇒ {@code CANCELLED}。翻转目标是**本轮步骤表**的函数（命令被剔 ⇒ 下一轮重算成 {@code
 * CANCELLED}），故与"被剔命令不进批"同一次修订收敛。翻转**自己也受转移守卫**（{@code ISSUED → 终态}）：它若被拒（并发 / 前置状态不对），走**同一条**
 * 收敛逻辑——剔出 + 拒因写进决策结果的 {@code flips}，**不静默吞掉**。
 *
 * <p>★ **"这次才暴露的"怎么认**：{@code CommandBus} 的整批拒绝里，**真被拒的**带自己的拒因，**被接受却随整批复原的**带 "整批未提交…"（见 {@code
 * CommandBus#rolledBack}）⇒ 本类按那个**前缀**区分（{@link #ROLLED_BACK_PREFIX}）。
 * 决策结果条目**自己**被拒时不重试——它是本批唯一的"结果载体"，剔了它就等于没记结果 ⇒ **响亮失败**。
 *
 * <p>★ **收敛性与上限**：每轮至少剔除一条（命令或翻转）⇒ 上限取"进批命令数 + 令数 + 2"（{@link #maxAttempts}）， 到顶仍不收敛 ⇒
 * **响亮失败**（不静默、不降级成两条 revision）。
 *
 * <p>★★ **改判语义（2026-09-23 改）**：原来是"一个 tick 一条"的幂等闸（条目 id 由地址派生、写死 {@code #0}，同 tick 再裁就撞 id
 * 被拒）。用户裁定「**只有生效裁决和作废裁决**」之后，改成：一个 tick 可以留**多条**记录，**至多一条生效** （{@code
 * AdjudicationStatus}）——作废把旧的翻成 {@code VOIDED}，重裁自然落成 {@code #1}。★ 因此**不再**由构造保证幂等；
 * 想要"改判"的正确姿势是先前那个 {@code void} 掉。
 *
 * <p>★ **不做**（有意划界）：决策人侧的**读**工具、前端决策结果子页、{@code simos.advance} 自动结算、令的"重开" （已终态不翻回）。
 *
 * <p>★ **批不发事件链**（{@code CommandBus.submitBatch} 的既有事实）：每条命令的身份只在**本次返回**的 {@code outcomes}
 * 里，事件表查不到 ⇒ 本类把逐条结局**写进决策结果条目**，那才是可回放、可审计的那份。
 */
public final class AdjudicateTickTool implements AgentTool {

  /**
   * 工具名（全局唯一）。★ 它**不是**一条已注册的命令类型 ⇒ 不登记进 {@code PAYLOAD_HINTS}（它内部用的 {@code sd.SetDirectiveStatus}
   * 才是命令类型，由 {@code Shell} 注册）。
   */
  public static final String NAME = "sd.AdjudicateTick";

  /**
   * 状态翻转用的命令类型。
   *
   * <p>★ 取自 {@link SetDirectiveStatusHandler#TYPE}（**单一来源**，不另写一个字面量）——它是裁决的内部编排，**不对外**提供窄工具。
   */
  private static final String STATUS_COMMAND_TYPE = SetDirectiveStatusHandler.TYPE;

  /**
   * 决策结果条目在 sd INFO 覆盖层里的 key。
   *
   * <p>★ 地址取 {@code sd:adjudication.<tick>}（**一个 tick 一条**，与"按 tick + 标签查"的读法同源）；key 只是同一地址下
   * 的人类可读标签，取 {@value #RESULT_KEY}。
   */
  public static final String RESULT_KEY = "result";

  /** 决策结果条目的地址前缀（{@code sd:adjudication.<tick>}）。 */
  public static final String RESULT_ADDRESS_PREFIX = "sd:adjudication.";

  /**
   * "被接受却随整批复原"的拒因前缀（{@code CommandBus#rolledBack} 的**唯一**产出）。
   *
   * <p>★ **为什么按前缀认**：{@code CommandOutcome} 只暴露"这条结局是不是 {@code Rejected}"，**没有**"它当时是否已被 handler
   * 接受"这一位；而整批拒绝时这两类都是 {@code Rejected}。取**短前缀**而不是整句——句尾措辞会改，这一段的语义不会。
   */
  private static final String ROLLED_BACK_PREFIX = "整批未提交";

  /** 裁定期解析决策载荷的 JSON 解析器（只判形状；金额语义留给域层 handler，避免第二份口径）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /**
   * 逐命令资源授权用的**缺省策略**（第 3 波第 2 步的口径，见类注）。
   *
   * <p>★ **为什么是五个命名空间各自的 {@code UNRESTRICTED}**：本类判的是"**出令决策人自己的可达面**"，
   * 这里没有"工具缺省策略"可言（决策人并没有在调某个工具）。{@code ResourceAuthorizer} 把"调用者未表态"的命名空间回落到 manifest 的策略 ⇒
   * 这张表就是那个回落值，取 {@code UNRESTRICTED} 即 **= "本层不表态 = 不收紧"** （{@code AccessLimit} 与 {@code
   * ResourceScopeMap} 的类注同口径）。**三个内置范围函数（Gov/Nation/Army）都把 map/social/unit/actor 显式表态** ⇒
   * 真决策人身上走不到这一格；它只服务"将来某个不表态的范围函数"（判定"不表态 vs 显式 none"的用例也走这里）。
   */
  private static final ResourceManifest TARGET_MANIFEST =
      ResourceManifest.of(
          Map.of(
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  /**
   * 范围函数注册表（无状态；与 {@code Shell} 的 {@code DecisionCallerFactory} 用**同一个** {@code defaults()} 构造器）。
   */
  private static final DecisionScopeFunctions SCOPE_FUNCTIONS = DecisionScopeFunctions.defaults();

  /**
   * 本工具自己的资源声明：四个领域命名空间（map/social/unit/actor——批里会出现 {@code actor.RemitGovTreasury} 的 actor 目标声明）+
   * {@code sd}（要写决策结果条目）。GM 侧五个都是 unlimited。
   */
  private static final ResourceManifest ADJUDICATION_WRITE =
      ResourceManifest.of(
          Map.of(
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED,
              ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  private final CoreSimos core;
  private final String initiator;
  private final String mapId;

  /**
   * 决策命令白名单（与 {@code IssueDirectiveHandler} **同一个构造器、同一个输入** {@code commandTypes}）。
   *
   * <p>★ 这里重建一份是**纯函数复算**（{@code new DirectiveWhitelist(commandTypes)} 是同一份注册面的确定性函数），
   * 不是第二份真相；它守的是"**存量令里的命令按今天的白名单仍然合法**"（白名单收窄过就不该继续执行）。
   */
  private final DirectiveWhitelist whitelist;

  /** {@code type → 目标声明}（由**已注册的 handler 清单**派生，见 {@link CommandTargets} 的类注）。 */
  private final Map<String, CommandTargets> commandTargets;

  /**
   * @param core 唯一写入口（本工具走 {@code submitBatch}，不是 {@code submit}）
   * @param initiator 本工具落盘时的发起者（C21 的 {@code <kind>:<id>} 形态）——**决策结果条目**用它； 命令信封各自用**出令决策人**的身份（见
   *     {@link #envelopeFor}）
   * @param mapId 地图称谓（拼目标资源路径的首段）
   * @param commandTypes 已注册命令类型（与 {@code Shell} 注册的 handler 同源，构造白名单）
   * @param commandTargets {@code type → 目标声明}（**必须**由同一个已注册 handler 清单派生 ⇒ 与执行面同源）
   */
  public AdjudicateTickTool(
      CoreSimos core,
      String initiator,
      String mapId,
      Set<String> commandTypes,
      Map<String, CommandTargets> commandTargets) {
    this.core = Objects.requireNonNull(core, "core");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    this.whitelist = new DirectiveWhitelist(Objects.requireNonNull(commandTypes, "commandTypes"));
    this.commandTargets = Map.copyOf(Objects.requireNonNull(commandTargets, "commandTargets"));
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 裁决：把某 tick 里**所有决策人**的令一起判效果，一次落**一条** revision（原子）。"
        + "载荷 {branch, expectedRevision, tick}（tick **必填**，不做缺省取当前 tick）。"
        + "逐条命令先做白名单 + **按出令决策人可达面**的资源授权预检；被拒的进决策结果而不进批。"
        + "同批把每条令翻成 EXECUTED（命令全被接受）/ CANCELLED（有任一被拒）。"
        + "返回 {result, ref, adjudicationId, tick, info{address,id,key}, directives[](含 status), "
        + "commands[]（逐条 {decisionMakerId, directiveId, type, result(applied|rejected), reason, ref}）, "
        + "flips[]（逐条 {directiveId, decisionMakerId, status, result, reason}）}";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("branch", ToolSupport.prop("string", "分支名"));
    props.put("expectedRevision", ToolSupport.prop("integer", "期望的 base revision（乐观并发）"));
    props.put("tick", ToolSupport.prop("integer", "要裁决的 tick（**必填**；不隐式取当前 tick）"));
    return ToolSupport.schema(props, List.of("branch", "expectedRevision", "tick"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ADJUDICATION_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "裁决一整个 tick branch="
            + args.get("branch")
            + " expected="
            + args.get("expectedRevision")
            + " tick="
            + args.get("tick"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    String adjudicationId = UUID.randomUUID().toString();
    try {
      ToolSupport.requireAll(context, Operation.WRITE, writeResources());
      Map<String, Object> args = context.arguments();
      BranchId branch = new BranchId(ToolSupport.requiredText(args, "branch"));
      RevisionId expected = new RevisionId(ToolSupport.requiredLong(args, "expectedRevision"));
      long tick = ToolSupport.requiredLong(args, "tick");
      if (tick < 0) {
        return ToolResult.error("BAD_REQUEST", "tick 必须 ≥ 0: " + tick);
      }
      Optional<RevisionId> head = core.head(branch);
      if (head.isEmpty()) {
        return ToolResult.error("REJECTED", "分支不存在: " + branch.value());
      }
      if (head.get().value() != expected.value()) {
        // ★ 与 submitBatch 的冲突口径一致（零 revision、报**真实 head**）：这里先判是为了**不去重放一份过期世界**。
        return conflict(new StateRef(branch, head.get()), adjudicationId, tick);
      }
      return adjudicate(
          new StateRef(branch, head.get()),
          adjudicationId,
          tick,
          core.replay(new StateRef(branch, head.get())));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与两条决策窄写同一条（T10 发现 2）：资源拒因必须原样逃到 ToolCallAuthorizer 的边界，
      //   折成 TOOL_ERROR 会让调用方看到"命令提交失败"而看不到"换个资源就行"。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "裁决失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 本工具要写的资源：`map:<mapId>` / `unit:*` / `social:*`（三命名空间粗断言）+ `sd:decision-maker`。 */
  private List<ResourceId> writeResources() {
    List<ResourceId> ids = new ArrayList<>(ToolSupport.allWriteResources(mapId));
    ids.add(ResourceId.of(ToolSupport.SD_NAMESPACE, DecisionCallerFactory.DECISION_MAKER_KIND));
    return List.copyOf(ids);
  }

  /** 本工具的白名单（= 注册面派生的**合法决策命令类型**）。★ 用例读回用，不是对外 API。 */
  Set<String> allowedCommandTypes() {
    return whitelist.allowedTypes();
  }

  /** {@code type → 目标声明}（**缺键 = 该类型无目标 ⇒ fail-closed 拒**）。★ 用例读回用，不是对外 API。 */
  Map<String, CommandTargets> commandTargets() {
    return commandTargets;
  }

  // ── 主流程 ────────────────────────────────────────────────────────────────────────────

  private ToolResult adjudicate(
      StateRef base, String adjudicationId, long tick, SimulationState state) {
    SdState sd = ToolSupport.sdState(state);
    List<Directive> directives = directivesAt(sd, tick);
    if (directives.isEmpty()) {
      // ★ **明确拒绝，不静默成功**：空 tick 裁决成"什么都没发生"会让调用方以为结算过了。
      return ToolResult.error("REJECTED", "tick " + tick + " 里没有任何决策人的令，无可裁决");
    }

    List<Step> steps = new ArrayList<>();
    for (Directive directive : directives) {
      DecisionMaker maker = sd.decisionMakers().get(directive.decisionMakerId());
      // 引用完整性由 SdState 的构造期不变量保证；这里只做防御（不编"决策人不存在"之外的语义）。
      ResourceScopeMap fence =
          maker == null
              ? ResourceScopeMap.empty()
              : DecisionCallerFactory.resourceScopesFor(SCOPE_FUNCTIONS, maker, state, mapId);
      for (DirectiveCommand command : directive.commands()) {
        Optional<String> precheck =
            precheckRejection(fence, maker == null, directive, command, state);
        steps.add(
            new Step(
                directive.id(),
                directive.decisionMakerId(),
                command.type(),
                precheck.isEmpty() ? envelopeFor(adjudicationId, base, directive, command) : null,
                precheck.orElse(null)));
      }
    }

    // ★ 本 tick **每条令**一条状态翻转（第 3 波最后一块）：目标在每轮按步骤表重算。
    List<Flip> flips = new ArrayList<>();
    for (Directive directive : directives) {
      flips.add(new Flip(directive.id(), directive.decisionMakerId()));
    }

    List<DecisionMakerId> involved =
        directives.stream().map(Directive::decisionMakerId).distinct().toList();
    long resultRevision = base.revision().value() + 1;
    int accepted = (int) steps.stream().filter(Step::inBatch).count();
    int attempts = maxAttempts(accepted, flips.size());
    for (int attempt = 0; attempt < attempts; attempt++) {
      // batch 与 owners **逐位对应**（owners 不含末尾那条 info）⇒ 结局可按下标定位（不靠信封相等去认）。
      List<CommandEnvelope> batch = new ArrayList<>();
      List<BatchEntry> owners = new ArrayList<>();
      for (Step step : steps) {
        if (step.inBatch()) {
          batch.add(step.envelope);
          owners.add(step);
        }
      }
      for (Flip flip : flips) {
        if (!flip.inBatch()) {
          continue; // 已被剔出（翻转自己被拒）⇒ 不重试，拒因留在决策结果的 flips 里。
        }
        flip.target = targetStatusFor(flip.directiveId, steps);
        batch.add(flipEnvelope(adjudicationId, base, flip));
        owners.add(flip);
      }
      CommandEnvelope info =
          infoEnvelope(adjudicationId, base, tick, involved, resultRevision, steps, flips);
      batch.add(info);

      BatchResult result = core.submitBatch(batch);
      if (result instanceof BatchResult.Committed committed) {
        return committedView(
            committed.ref(), adjudicationId, tick, involved, directives, steps, flips);
      }
      if (result instanceof BatchResult.Conflict conflict) {
        return conflict(conflict.current(), adjudicationId, tick);
      }
      Optional<String> nonConvergent = foldRejections((BatchResult.Rejected) result, owners);
      if (nonConvergent.isPresent()) {
        return ToolResult.error("TOOL_ERROR", nonConvergent.get());
      }
    }
    return ToolResult.error(
        "TOOL_ERROR", "裁决重试超过上限（" + attempts + " 次）仍未收敛：" + "每轮都会剔除至少一条被拒命令/翻转，走到这里说明剔除没有生效");
  }

  /** 收敛上限：每轮至少剔除一条**可剔项**（过检命令或状态翻转）⇒ "进批命令数 + 令数 + 2"必然够（+2 给"第一轮就全过"与"最后一轮只剩结果条目"）。 */
  private static int maxAttempts(int acceptedCommands, int flips) {
    return acceptedCommands + flips + 2;
  }

  /**
   * 一条令在本轮的目标终态：该令的**命令全部在批**（{@code step.inBatch()}）⇒ {@code EXECUTED}，有任一被剔/被拒 ⇒ {@code
   * CANCELLED}。
   *
   * <p>★ **零命令的令**：无命令可执行 ⇒ 空真值 ⇒ {@code EXECUTED}（没有任何东西被拒）。
   */
  private static DirectiveStatus targetStatusFor(DirectiveId directiveId, List<Step> steps) {
    for (Step step : steps) {
      if (step.directiveId.equals(directiveId) && !step.inBatch()) {
        return DirectiveStatus.CANCELLED;
      }
    }
    return DirectiveStatus.EXECUTED;
  }

  /**
   * 该 tick 里**参与裁决**的令：**顺序确定化**——先按 {@code decisionMakerId}，再按 {@code directiveId}（都是字符串序）。
   *
   * <p>★★ **两档被排除**（2026-09-23 用户裁定"令可重写"的连带，缺了它就会**执行旧的 / 被打回的令**）：
   *
   * <ul>
   *   <li>{@link DirectiveStatus#SUPERSEDED}：被同 (决策人, tick) 的**新版**顶掉的旧版——只有最新一版生效；
   *   <li>{@link DirectiveStatus#CANCELLED}：**GM 打回**的令——它已退场，不执行、也不落世界观变更。
   * </ul>
   *
   * <p>★ **其余三档仍参与**（{@code PLANNED}/{@code ISSUED}/{@code EXECUTED}）：{@code EXECUTED}
   * 也参与是**有意**的—— 重复裁决同一个 tick 时，它要撞上"决策结果条目 id 由地址派生"那条**幂等闸**（响亮失败、零 revision），而不是被本方法**静默**
   * 读成"这个 tick 里没有任何令"（后者给出一个**不真实**的结论，还把幂等闸的靶子抽掉）；同理，{@code PLANNED}/{@code EXECUTED}
   * 参与，让"翻转被转移守卫拒 ⇒ 走收敛逻辑"这条防御路径**可被真样本打到**（见 {@code AdjudicateTickToolTest}）。
   *
   * <p>★ 判别力：把本方法改回"只按 tick 取全部令"，重写后**旧版的命令也会进批**（两条都生效）⇒ {@code
   * AdjudicateTickToolTest.onlyTheLatestVersionOfARewrittenDirectiveIsAdjudicated} 当场红。
   */
  private static List<Directive> directivesAt(SdState sd, long tick) {
    List<Directive> out = new ArrayList<>();
    for (Directive directive : sd.directives().values()) {
      if (directive.tick() == tick && participatesInAdjudication(directive.status())) {
        out.add(directive);
      }
    }
    out.sort(
        Comparator.comparing((Directive d) -> d.decisionMakerId().value())
            .thenComparing(d -> d.id().value()));
    return List.copyOf(out);
  }

  /** 该状态是否**参与裁决**（见 {@link #directivesAt}：只排除"被新版顶掉"与"被 GM 打回"两档）。 */
  private static boolean participatesInAdjudication(DirectiveStatus status) {
    return status != DirectiveStatus.SUPERSEDED && status != DirectiveStatus.CANCELLED;
  }

  // ── 前置校验（白名单 + 命令专属层级校验 + 逐条资源授权）────────────────────────────────────

  /**
   * 三类前置校验（不过的记下拒因、**不进批**）：① 白名单；② **命令类型专属的层级/归属校验**（今天只有 {@code actor.RemitGovTreasury} 的"省 →
   * 自己的 {@code superiorGov}"，见 {@link #remitPrecheckRejection}）；③ **按出令决策人可达面**的逐目标资源授权。
   *
   * <p>★ 顺序有意：白名单先（最便宜、且 sd 自指与未注册类型不该走到资源判定），再做命令专属校验，最后解析目标、逐条判——换单位/换坐标的恶意令在②或③被拒。
   */
  private Optional<String> precheckRejection(
      ResourceScopeMap fence,
      boolean makerMissing,
      Directive directive,
      DirectiveCommand command,
      SimulationState state) {
    if (makerMissing) {
      return Optional.of("决策人不存在: " + directive.decisionMakerId().value());
    }
    if (DirectiveWhitelist.isSdSelfReference(command.type())) {
      return Optional.of("决策命令不得自指 sd.*（防无限递归）: " + command.type());
    }
    if (!whitelist.allows(command.type())) {
      return Optional.of("决策命令不在白名单: " + command.type());
    }
    // ★ 命令类型专属校验（放在白名单之后、目标解析之前）：其他命令类型的行为一字不动。
    //   ★★ P2-C：actor.RemitGovTreasury 的家户口径到这里整条判完（归属 + 上级 + 家户引用 + 双方位置的可达面），
    //   不再落回下方"handler 目标为空 ⇒ 拒"的通用分支（handler 在 actor 模块里看不到 unit 位置）。
    if (RemitGovTreasuryHandler.TYPE.equals(command.type())) {
      return remitPrecheckRejection(fence, state, directive, command);
    }
    CommandTargets targets = commandTargets.get(command.type());
    if (targets == null) {
      return Optional.of("该类命令尚无资源目标（未实现 CommandTargets），暂不可裁决: " + command.type());
    }
    // ★★ 2026-10-20：改调跨命名空间契约 targetResources——家户命令的目标可能同时含 social 与 unit；
    //   未覆盖它的 handler 走默认实现（旧 targetPaths 逐条包成"命令类型命名空间 + path"），旧行为不变。
    List<CommandTarget> resources;
    try {
      resources = targets.targetResources(command.type(), state, mapId, command.payloadJson());
    } catch (IllegalArgumentException e) {
      return Optional.of("目标资源判不出来（载荷形状不对）: " + e.getMessage());
    }
    if (resources.isEmpty()) {
      return Optional.of("载荷未给出任何可寻址目标（CommandTargets 返回空）: " + command.type());
    }
    List<String> violations = violations(fence, resources);
    if (violations.isEmpty()) {
      return Optional.empty();
    }
    // ★ 拒因是"实在效果"的一部分 ⇒ 写清是**哪条资源**越界（可读、可核对），不是一句"越权"。
    return Optional.of(
        "超出出令决策人 "
            + directive.decisionMakerId().value()
            + " 的可达面，命令被拒（目标资源）: "
            + String.join("、", violations));
  }

  /**
   * {@code actor.RemitGovTreasury} 的命令类型专属前置校验（R3b 的 P2-C 家户口径重建）：**只能由 GOV 决策人把自己的 政府家户账户，显式上缴给自己
   * {@code superiorGov} 的政府家户账户**；两笔家户引用必须逐字等于双方 GOV 单位按稳定 id 派生的政府家户（{@code
   * hh-gov-<unitId>}），且双方单位当刻有效位置都在出令决策人的 actor 可达面内。
   *
   * <p>★ <b>金额不判</b>：符号/全零/余额/冻结留给域层 {@code actor.RemitGovTreasuryHandler}，本层只判归属与可达面 —— 避免第二份口径。载荷
   * JSON 解析失败 / 字段缺失 / 类型不对 ⇒ 具名拒（不抛到外层 TOOL_ERROR）。
   *
   * <p>★ <b>为什么位置仍要判</b>：家户账户无格，域层 handler 的 {@code targetPaths} 给不出格路径；若在这里直接放行， "省 → 中央"的上缴就完全绕过了
   * {@code GovScope} 的 actor 围栏。本方法用双方 GOV 单位当刻有效位置合成 {@link ResourcePaths#actor(int, int)}
   * 两条目标，走与其余命令同一条 {@link #violations} 判据。 其他命令类型不走本方法，行为一字不动。
   */
  private static Optional<String> remitPrecheckRejection(
      ResourceScopeMap fence,
      SimulationState state,
      Directive directive,
      DirectiveCommand command) {
    DecisionMaker maker =
        ToolSupport.sdState(state).decisionMakers().get(directive.decisionMakerId());
    if (maker == null) {
      return Optional.of("决策人不存在: " + directive.decisionMakerId().value());
    }
    if (!(maker.affiliation() instanceof io.mosire.simos.sd.model.Affiliation.Gov gov)) {
      return Optional.of(
          "只有 GOV 归属的决策人可上缴国库（调用者 "
              + directive.decisionMakerId().value()
              + " 归属: "
              + maker.affiliation()
              + "）");
    }
    UnitState units = ToolSupport.unitState(state);
    Unit fromUnit = units.units().get(gov.govUnit());
    if (fromUnit == null
        || !(fromUnit.module().orElse(null)
            instanceof GovernmentFormation fromGovernmentFormation)) {
      return Optional.of("出令决策人所属 GOV 单位不存在或不是 GOV: " + gov.govUnit().value());
    }
    UnitId superiorId = fromGovernmentFormation.superiorGov().orElse(null);
    if (superiorId == null) {
      return Optional.of("中央 GOV 没有 superiorGov：上缴命令只对地方 GOV 有意义: " + fromUnit.id().value());
    }
    Unit toUnit = units.units().get(superiorId);
    if (toUnit == null
        || !(toUnit.module().orElse(null) instanceof GovernmentFormation toGovernmentFormation)) {
      return Optional.of("上级 GOV 不存在或不是 GOV: " + superiorId.value());
    }
    JsonNode payload;
    try {
      payload = MAPPER.readTree(command.payloadJson());
    } catch (java.io.IOException e) {
      return Optional.of("actor.RemitGovTreasury 载荷不是合法 JSON: " + e.getMessage());
    }
    String fromHousehold = textual(payload, "fromHousehold");
    String toHousehold = textual(payload, "toHousehold");
    if (fromHousehold == null || toHousehold == null) {
      return Optional.of(
          "actor.RemitGovTreasury 载荷缺 fromHousehold/toHousehold（P2-C 家户口径）: "
              + command.payloadJson());
    }
    String expectedFrom;
    String expectedTo;
    try {
      expectedFrom =
          GovernmentHouseholdResolver.requireGovernmentHousehold(fromUnit, fromUnit.id().value())
              .value();
      expectedTo =
          GovernmentHouseholdResolver.requireGovernmentHousehold(toUnit, toUnit.id().value())
              .value();
    } catch (IllegalArgumentException e) {
      return Optional.of("上缴前置校验失败: " + e.getMessage());
    }
    if (!expectedFrom.equals(fromHousehold)) {
      return Optional.of("上缴源必须是自己 GOV 的政府家户账户 " + expectedFrom + "，载荷给的是 " + fromHousehold);
    }
    if (!expectedTo.equals(toHousehold)) {
      return Optional.of(
          "上缴目标必须是 superiorGov "
              + superiorId.value()
              + " 的政府家户账户 "
              + expectedTo
              + "，载荷给的是 "
              + toHousehold);
    }
    HexCoord fromAt = units.effectivePosition(fromUnit.id(), state.meta().timestamp()).orElse(null);
    HexCoord toAt = units.effectivePosition(toUnit.id(), state.meta().timestamp()).orElse(null);
    if (fromAt == null || toAt == null) {
      return Optional.of("源/目标 GOV 单位当刻有效位置不完整（国库围栏落点未知）");
    }
    List<String> paths =
        List.of(
            ResourcePaths.actor(fromAt.q(), fromAt.r()), ResourcePaths.actor(toAt.q(), toAt.r()));
    List<String> violations = violations(fence, "actor", paths);
    if (violations.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        "超出出令决策人 "
            + directive.decisionMakerId().value()
            + " 的可达面，命令被拒（目标资源）: "
            + String.join("、", violations));
  }

  /** 载荷里的必填文本字段；缺失 / 非文本 / 空白 ⇒ null（由调用方折成具名拒因）。 */
  private static String textual(JsonNode payload, String field) {
    if (payload == null || !payload.isObject()) {
      return null;
    }
    JsonNode node = payload.get(field);
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      return null;
    }
    return node.asText();
  }

  /**
   * 逐条判目标是否落在该决策人的可达面内，返回**越界的那几条资源**（空 = 全部放行）。
   *
   * <p>★ **包内可见**：第 3 波第 2 步的用例要在这里钉住"**不表态**（{@code declaredScope} 返 null）与**显式 {@code
   * none()}**"两条**方向相反**的语义——前者按既有语义回落到 {@link #TARGET_MANIFEST}（不收紧），后者必须拒。
   * 端到端路径上今天走不到"不表态"（三个内置范围函数都把 map/social/unit/actor 显式表态），故必须在这一层可测。
   *
   * <p>★ 这是**旧签名**（单命名空间 + 裸路径），保留给既有测试/调用点；实现委托给跨命名空间重载（逐条包成 {@link CommandTarget} 后走同一台 {@link
   * ResourceAuthorizer}）。
   */
  static List<String> violations(ResourceScopeMap fence, String namespace, List<String> paths) {
    List<CommandTarget> targets = new ArrayList<>(paths.size());
    for (String path : paths) {
      targets.add(new CommandTarget(namespace, path));
    }
    return violations(fence, targets);
  }

  /**
   * ★★ **2026-10-20 的跨命名空间重载**（用户裁定）：逐条判 {@link CommandTarget} 是否落在该决策人的可达面内，返回**越界的那几条资源** （空 =
   * 全部放行）。
   *
   * <p>★★ **路径归一化按各自 namespace 做**：决策人常从读口拿到 canonical 地址（如 {@code unit:<裸 id>} / {@code
   * social:<q>_<r>}）并原样塞回命令载荷；而目标声明约定是命名空间内路径。这里对**每个目标**去掉与它自己命名空间同名的前缀 （{@code "unit:"}/{@code
   * "social:"}…），不改其余任何字符——否则 {@code unit:unit:<id>} 会撞不上围栏（2026-10-01 R5 真实决策轮实测）。
   * 家户命令的目标会**跨命名空间**，所以归一化不能再看"命令类型第一段"，只能看目标自己的 namespace。
   */
  static List<String> violations(ResourceScopeMap fence, List<CommandTarget> targets) {
    ResourceAuthorizer authorizer = ResourceAuthorizer.of(permissionSetOf(fence), TARGET_MANIFEST);
    List<String> violations = new ArrayList<>();
    for (CommandTarget target : targets) {
      ResourceId id =
          ResourceId.of(target.namespace(), normalizeTargetPath(target.namespace(), target.path()));
      if (!authorizer.allows(Operation.WRITE, id)) {
        violations.add(id.fullId());
      }
    }
    return List.copyOf(violations);
  }

  /**
   * 决策人常从读口拿到 canonical 地址（如 {@code unit:<裸 id>}）并原样塞回命令载荷；{@code CommandTargets} 返回的 localId
   * 约定是不带命名空间前缀的裸路径。这里只去掉**与目标命名空间同名的前缀**（{@code "unit:"}/{@code "social:"}…）， 不改其余任何字符——否则 {@code
   * unit:unit:<id>} 会撞不上围栏（2026-10-01 R5 真实决策轮实测）。
   */
  private static String normalizeTargetPath(String namespace, String path) {
    String prefix = namespace + ":";
    return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
  }

  /**
   * 把一份**已算好**的可达面装成权限集（{@link ResourceAuthorizer} 只吃 {@code AgentPermissionSet}）。
   *
   * <p>★ 白名单与其余分量在本判定里**不起作用**（判定只看 {@code resourceScopes}）⇒ 这里不另编一份假的工具白名单。
   */
  private static AgentPermissionSet permissionSetOf(ResourceScopeMap fence) {
    return AgentPermissionSet.builder(AccessToken.DEFAULT).resourceScopes(fence).build();
  }

  // ── 信封 ──────────────────────────────────────────────────────────────────────────────

  /**
   * 一条过检命令的信封：{@code type}/{@code payloadJson} 照抄 {@link DirectiveCommand}（R11：逐字节转交）； {@code
   * initiator} 用**出令决策人**的身份（{@code decision-maker:<id>}，与它的 {@code instanceId} 同源）； {@code
   * correlationId} 用**本次裁决的 id**（一条令链一次裁决）。
   */
  private static CommandEnvelope envelopeFor(
      String adjudicationId, StateRef base, Directive directive, DirectiveCommand command) {
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        adjudicationId,
        DecisionCallerFactory.INSTANCE_ID_PREFIX + directive.decisionMakerId().value(),
        base.branch(),
        base.revision(),
        command.type(),
        command.payloadJson());
  }

  /**
   * 一条**状态翻转**命令的信封（{@code sd.SetDirectiveStatus}）：把该令翻到 {@link Flip#target}。
   *
   * <p>★ {@code initiator} 用**本工具的 initiator**（GM 的裁决动作），**不是**出令决策人——翻转是裁决的产物，不是决策人自己下的命令 （尤其
   * {@code CANCELLED} 是"它的命令被拒"的结果，挂它名下会误导）。{@code correlationId} 与同批其余命令一致 = 本次裁决 id。
   */
  private CommandEnvelope flipEnvelope(String adjudicationId, StateRef base, Flip flip) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("directiveId", flip.directiveId.value());
    payload.put("status", flip.target.name());
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        adjudicationId,
        initiator,
        base.branch(),
        base.revision(),
        STATUS_COMMAND_TYPE,
        ToolSupport.json(payload));
  }

  /**
   * 决策结果条目（一条 {@code sd.PutInfo} 命令）——**与命令同批落**（这样它和命令效果在**同一条 revision** 里）。
   *
   * <p>★★ **id 不再显式给**（2026-09-23 改）：原来写死 {@code #0} 是为了让"一个 tick 一条由构造保证"，但那让**作废之后没法重裁** ——作废把
   * {@code #0} 翻成 VOIDED 留在原地，重裁再写 {@code #0} 就撞 id 被拒。现在交给 {@code sd.PutInfo} 的合成 （{@code
   * 地址#该地址下条目数}）⇒ 同 tick 的第二条自然落成 {@code #1}，"至多一条生效"改由 {@link
   * io.mosire.simos.sd.model.AdjudicationStatus}（新条 EFFECTIVE、旧条被作废成 VOIDED）承担。
   *
   * <p>★ {@code value} 是 **JSON 字符串**（{@code SdInfoEntry.value} 是裸 {@code Object}，只有标量往返有保证 ⇒
   * 结构化内容必须自己序列化）。
   */
  private CommandEnvelope infoEnvelope(
      String adjudicationId,
      StateRef base,
      long tick,
      List<DecisionMakerId> involved,
      long resultRevision,
      List<Step> steps,
      List<Flip> flips) {
    Address address = Address.parse(RESULT_ADDRESS_PREFIX + tick);
    String canonical = address.canonical();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", canonical);
    payload.put("key", RESULT_KEY);
    payload.put("value", valueJson(base.branch(), resultRevision, tick, steps, flips));
    // ★ 生效裁决：本条是这一 tick 当前的生效记录（旧的那条在作废时已被翻成 VOIDED）。
    payload.put("adjudicationStatus", AdjudicationStatus.EFFECTIVE.name());
    payload.put("tags", involved.stream().map(DecisionMakerId::value).toList());
    payload.put("tick", tick);
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        adjudicationId,
        initiator,
        base.branch(),
        base.revision(),
        "sd.PutInfo",
        ToolSupport.json(payload));
  }

  /** 决策结果的正文（JSON 字符串）：本 tick **逐条命令**的结局与拒因 + **逐条令**的状态翻转 + 本批落盘的坐标。 */
  private static String valueJson(
      BranchId branch, long resultRevision, long tick, List<Step> steps, List<Flip> flips) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("tick", tick);
    value.put("resultRevision", resultRevision);
    List<Map<String, Object>> commands = new ArrayList<>();
    for (Step step : steps) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("decisionMakerId", step.decisionMakerId.value());
      row.put("directiveId", step.directiveId.value());
      row.put("type", step.type);
      row.put("result", step.inBatch() ? "applied" : "rejected");
      row.put("reason", step.inBatch() ? null : step.reason);
      row.put("ref", step.inBatch() ? branch.value() + "@" + resultRevision : null);
      commands.add(row);
    }
    value.put("commands", commands);
    List<Map<String, Object>> flipRows = new ArrayList<>();
    for (Flip flip : flips) {
      flipRows.add(flipRow(flip, branch, resultRevision));
    }
    value.put("flips", flipRows);
    return ToolSupport.json(value);
  }

  /** 一条翻转在决策结果里的行：目标终态 + 结局（{@code applied}/{@code rejected}）+ 拒因。 */
  private static Map<String, Object> flipRow(Flip flip, BranchId branch, long resultRevision) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("directiveId", flip.directiveId.value());
    row.put("decisionMakerId", flip.decisionMakerId.value());
    row.put("status", flip.target.name());
    row.put("result", flip.inBatch() ? "applied" : "rejected");
    row.put("reason", flip.inBatch() ? null : flip.reason);
    row.put("ref", flip.inBatch() ? branch.value() + "@" + resultRevision : null);
    return row;
  }

  // ── 收敛：从整批拒绝里挑出"这次才暴露的"─────────────────────────────────────────────────

  /**
   * 从整批拒绝里把**这次才暴露的**被拒命令/翻转剔出（并记下拒因），供下一轮重试。
   *
   * <p>★ 返回非空 = **不收敛**（决策结果条目自己被拒 / 一条都没有可剔）：交调用方响亮失败。
   *
   * <p>★ 位置对应是**契约**（{@code BatchResult} 的 outcomes 与入参逐位对应，且 {@code owners} 与"入参除末尾 info 之外"逐位对应）⇒
   * 最后一个位置就是决策结果条目，其余按下标找回自己的 owner。
   */
  private static Optional<String> foldRejections(
      BatchResult.Rejected rejected, List<BatchEntry> owners) {
    List<CommandOutcome> outcomes = rejected.outcomes();
    int infoIndex = outcomes.size() - 1;
    CommandOutcome infoOutcome = outcomes.get(infoIndex);
    Optional<String> infoReason = culpritReason(infoOutcome);
    if (infoReason.isPresent()) {
      // ★ 决策结果条目被拒（如"撞 id" = 这个 tick 已裁决过）⇒ 不许剔掉它重试（剔了就没结果可记）⇒ 响亮失败。
      return Optional.of("决策结果条目被拒（本工具不可重试该条）: " + infoReason.orElseThrow());
    }
    int removed = 0;
    for (int i = 0; i < infoIndex; i++) {
      Optional<String> reason = culpritReason(outcomes.get(i));
      if (reason.isEmpty()) {
        continue;
      }
      owners.get(i).noteRejection("handler 拒绝: " + reason.orElseThrow());
      removed++;
    }
    if (removed == 0) {
      return Optional.of("整批被拒但没有可剔除的命令（拒因不含可定位的单条命令）——不收敛");
    }
    return Optional.empty();
  }

  /**
   * 该结局是否"**该条自己被拒**"：带真拒因 ⇒ 是；"随整批复原"（{@link #ROLLED_BACK_PREFIX}）⇒ 不是；不是 {@code Rejected} ⇒ 也不是。
   */
  private static Optional<String> culpritReason(CommandOutcome outcome) {
    if (outcome.result() instanceof CommandResult.Rejected rejected
        && !rejected.reason().startsWith(ROLLED_BACK_PREFIX)) {
      return Optional.of(rejected.reason());
    }
    return Optional.empty();
  }

  // ── 视图 ──────────────────────────────────────────────────────────────────────────────

  private static ToolResult conflict(StateRef current, String adjudicationId, long tick) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "conflict");
    view.put("current", ToolSupport.stateRef(current));
    view.put("commandId", adjudicationId);
    view.put("correlationId", adjudicationId);
    view.put("tick", tick);
    return ToolResult.error("CONFLICT", ToolSupport.json(view));
  }

  private static ToolResult committedView(
      StateRef ref,
      String adjudicationId,
      long tick,
      List<DecisionMakerId> involved,
      List<Directive> directives,
      List<Step> steps,
      List<Flip> flips) {
    Map<String, Object> view =
        new LinkedHashMap<>(ToolSupport.committedView(ref, adjudicationId, adjudicationId));
    view.put("adjudicationId", adjudicationId);
    view.put("tick", tick);
    view.put("info", infoView(tick));
    List<Map<String, Object>> directiveRows = new ArrayList<>();
    for (Directive directive : directives) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("decisionMakerId", directive.decisionMakerId().value());
      row.put("directiveId", directive.id().value());
      row.put("status", flipOf(flips, directive.id()).target.name());
      directiveRows.add(row);
    }
    view.put("directives", directiveRows);
    view.put("decisionMakers", involved.stream().map(DecisionMakerId::value).toList());
    view.put("commands", commandRows(ref, steps));
    List<Map<String, Object>> flipRows = new ArrayList<>();
    for (Flip flip : flips) {
      flipRows.add(flipRow(flip, ref.branch(), ref.revision().value()));
    }
    view.put("flips", flipRows);
    return ToolResult.ok(ToolSupport.json(view));
  }

  /** 按令 id 找它的翻转（每条令恰好一条）。 */
  private static Flip flipOf(List<Flip> flips, DirectiveId directiveId) {
    for (Flip flip : flips) {
      if (flip.directiveId.equals(directiveId)) {
        return flip;
      }
    }
    throw new IllegalStateException("令 " + directiveId.value() + " 没有对应的状态翻转（编排不自洽）");
  }

  /**
   * 决策结果条目的坐标（地址 + key）——后续"决策人读工具"按 tick + 标签查的另一半钥匙。
   *
   * <p>★★ **不再复算 id**（2026-09-23 改）：条目 id 现在是「该地址下的**第 n 条**」的合成结果，**不是 tick 的纯函数** （作废之后重裁会落
   * {@code #1}）。这里若照旧写死 {@code #0}，回给调用方的就是一个**会漂的错值**——不如不给。
   */
  private static Map<String, Object> infoView(long tick) {
    String canonical = Address.parse(RESULT_ADDRESS_PREFIX + tick).canonical();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("address", canonical);
    view.put("key", RESULT_KEY);
    view.put("idNote", "条目 id = 该地址下的第 n 条（同一 tick 改判会落 #1、#2…），**不是 tick 的纯函数**");
    return view;
  }

  private static List<Map<String, Object>> commandRows(StateRef ref, List<Step> steps) {
    List<Map<String, Object>> out = new ArrayList<>(steps.size());
    for (Step step : steps) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("decisionMakerId", step.decisionMakerId.value());
      row.put("directiveId", step.directiveId.value());
      row.put("type", step.type);
      row.put("result", step.inBatch() ? "applied" : "rejected");
      row.put("reason", step.inBatch() ? null : step.reason);
      row.put("ref", step.inBatch() ? ToolSupport.stateRef(ref) : null);
      out.add(row);
    }
    return out;
  }

  // ── 批内条目：步骤表与翻转 ──────────────────────────────────────────────────────────────

  /** 批内一个可被剔除的条目（过检命令 {@link Step} / 状态翻转 {@link Flip}）——让收敛逻辑对两者**一视同仁**。 */
  private interface BatchEntry {

    /** 该条目**仍在最终那一批**：尚未被任何一轮剔除。 */
    boolean inBatch();

    /** 记下"该条自己被拒"的拒因（下一轮不再进批）。 */
    void noteRejection(String reason);
  }

  /**
   * 本 tick 的一条命令及其结局。
   *
   * <p>★ {@code envelope} 为 null ⇔ 预检就拒了（**从未进批**）；{@code reason} 非 null ⇔ 结局是 rejected
   * （拒因即它，来自前置校验或某轮批的 handler 拒绝）。
   */
  private static final class Step implements BatchEntry {

    private final DirectiveId directiveId;
    private final DecisionMakerId decisionMakerId;
    private final String type;
    private final CommandEnvelope envelope;
    private String reason;

    Step(
        DirectiveId directiveId,
        DecisionMakerId decisionMakerId,
        String type,
        CommandEnvelope envelope,
        String reason) {
      this.directiveId = directiveId;
      this.decisionMakerId = decisionMakerId;
      this.type = type;
      this.envelope = envelope;
      this.reason = reason;
    }

    /**
     * 这条命令**在（或进了）最终那一批**：过检、且尚未被任何一轮剔除。
     *
     * <p>★ 同一个谓词在两处用、语义都成立：① 组批与写决策结果时 = 这条**会**生效；② 提交成功后的视图 = 这条**已**生效 ——能进最终那一批的命令，就是随那条
     * revision 一起生效的那些（批是原子的）。
     */
    @Override
    public boolean inBatch() {
      return envelope != null && reason == null;
    }

    @Override
    public void noteRejection(String reason) {
      this.reason = reason;
    }
  }

  /**
   * 本 tick 一条令的状态翻转（{@code sd.SetDirectiveStatus}）：目标在每轮按步骤表重算，{@code reason} 非 null ⇔ 该翻转**自己被拒**。
   *
   * <p>★ 与 {@link Step} 的差别：Step 的信封在建表时定死（预检拒则 null），Flip 的信封**每轮现拼**（因为目标态可能随命令被剔而变）。
   */
  private static final class Flip implements BatchEntry {

    private final DirectiveId directiveId;
    private final DecisionMakerId decisionMakerId;
    private DirectiveStatus target;
    private String reason;

    Flip(DirectiveId directiveId, DecisionMakerId decisionMakerId) {
      this.directiveId = directiveId;
      this.decisionMakerId = decisionMakerId;
    }

    @Override
    public boolean inBatch() {
      return reason == null;
    }

    @Override
    public void noteRejection(String reason) {
      this.reason = reason;
    }
  }
}
