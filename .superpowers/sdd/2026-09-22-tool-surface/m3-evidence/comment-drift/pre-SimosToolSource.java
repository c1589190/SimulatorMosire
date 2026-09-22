package io.mosire.simos.app.tools;

import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.read.BranchListTool;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.MapHexTool;
import io.mosire.simos.app.tools.read.MapOverviewTool;
import io.mosire.simos.app.tools.read.PopulationTool;
import io.mosire.simos.app.tools.read.StateFacetsTool;
import io.mosire.simos.app.tools.read.StateResolveTool;
import io.mosire.simos.app.tools.read.UnitGetTool;
import io.mosire.simos.app.tools.read.UnitListTool;
import io.mosire.simos.app.tools.write.AdvanceTool;
import io.mosire.simos.app.tools.write.CommandSubmitTool;
import io.mosire.simos.app.tools.write.ForkTool;
import io.mosire.simos.app.tools.write.IssueDirectiveTool;
import io.mosire.simos.app.tools.write.MapCreateRegionTool;
import io.mosire.simos.app.tools.write.MapDeleteRegionTool;
import io.mosire.simos.app.tools.write.MapRandomizeRegionTool;
import io.mosire.simos.app.tools.write.MapRegisterPathwayGroupTool;
import io.mosire.simos.app.tools.write.MapSetEdgeTool;
import io.mosire.simos.app.tools.write.MapSetTerrainTool;
import io.mosire.simos.app.tools.write.MapUpdateRegionTool;
import io.mosire.simos.app.tools.write.SdAddCombatStageTool;
import io.mosire.simos.app.tools.write.SdCancelEffectTool;
import io.mosire.simos.app.tools.write.SdCommitCombatOutcomeTool;
import io.mosire.simos.app.tools.write.SdCreateArmyTool;
import io.mosire.simos.app.tools.write.SdCreateCombatTool;
import io.mosire.simos.app.tools.write.SdCreateDecisionMakerTool;
import io.mosire.simos.app.tools.write.SdCreateNationTool;
import io.mosire.simos.app.tools.write.SdPutInfoTool;
import io.mosire.simos.app.tools.write.SdRecordCasualtiesTool;
import io.mosire.simos.app.tools.write.SdRegisterEffectTool;
import io.mosire.simos.app.tools.write.SdSetDecisionMakerProviderTool;
import io.mosire.simos.app.tools.write.SdSetStageOutcomeTableTool;
import io.mosire.simos.app.tools.write.SetViewScopeTool;
import io.mosire.simos.app.tools.write.StartDecisionTool;
import io.mosire.simos.app.tools.write.SubmitVerdictTool;
import io.mosire.simos.app.tools.write.UnitApplyCasualtiesTool;
import io.mosire.simos.app.tools.write.UnitAttachTool;
import io.mosire.simos.app.tools.write.UnitCancelRouteTool;
import io.mosire.simos.app.tools.write.UnitCreateCommandChainTool;
import io.mosire.simos.app.tools.write.UnitCreateTool;
import io.mosire.simos.app.tools.write.UnitDetachTool;
import io.mosire.simos.app.tools.write.UnitDisbandTool;
import io.mosire.simos.app.tools.write.UnitMergeFormationTool;
import io.mosire.simos.app.tools.write.UnitPlaceAtTool;
import io.mosire.simos.app.tools.write.UnitPlanRouteTool;
import io.mosire.simos.app.tools.write.UnitPlanSparseRouteTool;
import io.mosire.simos.app.tools.write.UnitRenameTool;
import io.mosire.simos.app.tools.write.UnitReparentSubtreeTool;
import io.mosire.simos.app.tools.write.UnitReparentTool;
import io.mosire.simos.app.tools.write.UnitSetFormationOffsetTool;
import io.mosire.simos.app.tools.write.UnitSetRejoinTargetTool;
import io.mosire.simos.app.tools.write.UnitSetStatusTool;
import io.mosire.simos.app.tools.write.UnitSetStrengthTool;
import io.mosire.simos.app.tools.write.UnitSplitFormationTool;
import io.mosire.simos.app.tools.write.UnitUpdateCommandChainTool;
import io.mosire.simos.core.CoreSimos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Simos 工具供给源（M5 T5，spec §7.1）：**9 读 + 各桶自己的写面**（{@link Role}）。
 *
 * <p>★ **它是工具清单的唯一出处**：{@code Shell} 建源后经 {@code McpSourceBridge.bind} 把这份快照同步进 {@link
 * io.mosire.agentlib.tool.ToolRegistry}，T7 再把该注册表交给 {@code
 * AgentToMcpServer}。源本身**不向任何全局状态注册**（AgentLib 的 {@code ToolSource} 契约）。
 *
 * <p>★ **{@code onChange} 是 no-op**：本源的集合是构造期定死的静态集（没有插件式的动态增删口子），故订阅即时成立、无需变更通知。若将来工具集
 * 变成动态的，替换这里的空实现即可（契约要求返回可关闭句柄）。
 *
 * <p>★ **写工具的身份与资源**：{@code initiator} 由 {@code Shell} 从 {@link
 * io.mosire.simos.app.ShellConfig#mcpInitiator()} 注入（spec §九，R4 的断言对象）；{@code mapId} 用于 hex 工具的
 * canonical 地址与资源断言（{@code GameMap} 无 id，见 M2/M3 挂起项）。
 */
public final class SimosToolSource implements ToolSource {

  /** 供给源标识（同一运行时内唯一；审计与按源卸载依赖它）。 */
  public static final String ID = "simos";

  /** 工具面按角色分载（spec §八.3，N9/N11；T4 起现有运行时口 = {@link #EXTERNAL_WITH_GM}）。 */
  public enum Role {
    /** 外部 MCP 客户端：保留现状（有通用写），spec §八.3 列为挂起。 */
    EXTERNAL,
    /** GM：配权 + 窄工具，**无通用写**（N11）。 */
    GM,
    /**
     * 决策 Agent：仅窄工具（`sd.IssueDirective` / `sd.SubmitVerdict`，用户裁定 D-1 起**再含 unit
     * 域全部窄写**），**无通用写**（N9）。
     */
    DECISION_AGENT,
    /**
     * 现有 MCP 口（T4，**D2="加"**，spec §二.5）：**EXTERNAL ∪ GM** —— 9 读共享 + 通用写（submit/advance/fork）+ GM
     * 窄写（IssueDirective/SubmitVerdict/**含** SetViewScope/StartDecision + **map 域与 unit 域的窄写**）。
     *
     * <p>★ **与 SDSimos 裁定 N9 的冲突在此端口显式记账**：N9 的原意是「专用窄工具，不给决策 Agent 通用 `simos.command.submit`」；本口
     * 保留通用写是**用户裁定 D2 的取舍、不是缺陷**（其持有者可绕过窄工具直接提交任意命令）。N9 在**决策人口**（{@link #DECISION_AGENT} 桶）与
     * `DecisionMaker.allowedTools` 白名单上**照旧有效**。
     */
    EXTERNAL_WITH_GM
  }

  private final List<AgentTool> tools;

  /**
   * 外部 MCP 桶（与既有行为逐条相同：3 写 + 9 读）。
   *
   * @param core 唯一写入口（写工具经它提交）
   * @param query 只读门面（读工具经它读状态）
   * @param initiator 写命令的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（构造 canonical hex 地址与资源断言）
   * @param commandTypes 已注册命令类型（与 {@code Shell} 注册的 handler 同源，catalog 读它）
   */
  public SimosToolSource(
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      Set<String> commandTypes) {
    this(core, query, initiator, mapId, commandTypes, Role.EXTERNAL);
  }

  /** 按角色装配工具面：读工具四桶共享；写面各自不同（{@link Role#EXTERNAL_WITH_GM} 是外部写 ∪ GM 窄写的复合面）。 */
  public SimosToolSource(
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      Set<String> commandTypes,
      Role role) {
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(commandTypes, "commandTypes");
    Objects.requireNonNull(role, "role");
    List<AgentTool> built = new ArrayList<>(readTools(core, query, mapId, commandTypes));
    switch (role) {
      case EXTERNAL -> addExternalWrites(built, core, initiator, mapId);
      case GM -> addGmWrites(built, core, initiator, mapId);
      case DECISION_AGENT -> addDecisionAgentWrites(built, core, initiator, mapId);
      case EXTERNAL_WITH_GM -> {
        addExternalWrites(built, core, initiator, mapId);
        addGmWrites(built, core, initiator, mapId);
      }
    }
    this.tools = List.copyOf(built);
  }

  /** EXTERNAL 桶的通用写（spec §八.3）：submit / advance / fork。 */
  private static void addExternalWrites(
      List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
    built.add(new CommandSubmitTool(core, initiator, mapId));
    built.add(new AdvanceTool(core, initiator, mapId));
    built.add(new ForkTool(core, initiator));
  }

  /**
   * GM 窄写（N11）：sd 域两条决策窄工具 + 配权工具（**含** `sd.SetViewScope`）+ 「开始决策」（T10）+ **map 域与 unit 域的窄写**。
   *
   * <p>★ **窄写工具进的是 GM 桶**（不是通用写、也不是决策桶）：它们的命令类型在工具里固定死，模型只能给载荷 ——与 {@code simos.command.submit}
   * 的"自选 type"相对（spec §八.3）。{@link Role#EXTERNAL_WITH_GM} 复合口因此也含这些。
   *
   * <p>★ **M3（spec §八.3）：sd 域余下的写命令也在此追加**——命令类型固定、与 map/unit 窄写同形。★ **新窄写一律加在这里**， 加完**不必回来改本注**
   * （**本注刻意不写条数**：数字是漂移源，M2 已把本文件与 {@code Shell} 的旧计数去掉）。
   */
  private static void addGmWrites(
      List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
    built.add(new IssueDirectiveTool(core, initiator, mapId));
    built.add(new SubmitVerdictTool(core, initiator, mapId));
    built.add(new SetViewScopeTool(core, initiator, mapId));
    built.add(new StartDecisionTool(core, initiator, mapId));
    built.add(new MapSetTerrainTool(core, initiator, mapId));
    built.add(new MapSetEdgeTool(core, initiator, mapId));
    built.add(new MapCreateRegionTool(core, initiator, mapId));
    built.add(new MapUpdateRegionTool(core, initiator, mapId));
    built.add(new MapDeleteRegionTool(core, initiator, mapId));
    built.add(new MapRandomizeRegionTool(core, initiator, mapId));
    built.add(new MapRegisterPathwayGroupTool(core, initiator, mapId));
    // M2（spec §八.3）：unit 域 20 条窄写 —— 用户裁定 D-1，同一批**也挂进决策人桶**（见 addDecisionAgentWrites）。
    built.add(new UnitRenameTool(core, initiator, mapId));
    built.add(new UnitCreateTool(core, initiator, mapId));
    built.add(new UnitReparentTool(core, initiator, mapId));
    built.add(new UnitSetStrengthTool(core, initiator, mapId));
    built.add(new UnitPlaceAtTool(core, initiator, mapId));
    built.add(new UnitPlanRouteTool(core, initiator, mapId));
    built.add(new UnitCancelRouteTool(core, initiator, mapId));
    built.add(new UnitDisbandTool(core, initiator, mapId));
    built.add(new UnitSetStatusTool(core, initiator, mapId));
    built.add(new UnitAttachTool(core, initiator, mapId));
    built.add(new UnitDetachTool(core, initiator, mapId));
    built.add(new UnitReparentSubtreeTool(core, initiator, mapId));
    built.add(new UnitSetFormationOffsetTool(core, initiator, mapId));
    built.add(new UnitSplitFormationTool(core, initiator, mapId));
    built.add(new UnitMergeFormationTool(core, initiator, mapId));
    built.add(new UnitPlanSparseRouteTool(core, initiator, mapId));
    built.add(new UnitSetRejoinTargetTool(core, initiator, mapId));
    built.add(new UnitCreateCommandChainTool(core, initiator, mapId));
    built.add(new UnitUpdateCommandChainTool(core, initiator, mapId));
    built.add(new UnitApplyCasualtiesTool(core, initiator, mapId));
    // M3（spec §八.3）：sd 域余下的写命令 —— ★ **只在 GM 桶**（决策人桶有意不含这批）。
    built.add(new SdCreateNationTool(core, initiator, mapId));
    built.add(new SdCreateArmyTool(core, initiator, mapId));
    built.add(new SdCreateDecisionMakerTool(core, initiator, mapId));
    built.add(new SdPutInfoTool(core, initiator, mapId));
    built.add(new SdCreateCombatTool(core, initiator, mapId));
    built.add(new SdAddCombatStageTool(core, initiator, mapId));
    built.add(new SdSetStageOutcomeTableTool(core, initiator, mapId));
    built.add(new SdCommitCombatOutcomeTool(core, initiator, mapId));
    built.add(new SdRecordCasualtiesTool(core, initiator, mapId));
    built.add(new SdRegisterEffectTool(core, initiator, mapId));
    built.add(new SdCancelEffectTool(core, initiator, mapId));
    built.add(new SdSetDecisionMakerProviderTool(core, initiator, mapId));
  }

  /**
   * 决策 Agent 窄写（N9）：**无** `sd.SetViewScope`、**无**通用写。★ 用户裁定 D-1：unit 域 20 条窄写与 GM
   * 桶**同批**（它们是窄工具、命令类型固定，与 N9「不给通用写」不冲突）⇒ 两处**各自逐条**列出，便于按桶裁剪。
   */
  private static void addDecisionAgentWrites(
      List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
    built.add(new IssueDirectiveTool(core, initiator, mapId));
    built.add(new SubmitVerdictTool(core, initiator, mapId));
    // M2（spec §八.3）：unit 域 20 条窄写（与 addGmWrites 同一批）。
    built.add(new UnitRenameTool(core, initiator, mapId));
    built.add(new UnitCreateTool(core, initiator, mapId));
    built.add(new UnitReparentTool(core, initiator, mapId));
    built.add(new UnitSetStrengthTool(core, initiator, mapId));
    built.add(new UnitPlaceAtTool(core, initiator, mapId));
    built.add(new UnitPlanRouteTool(core, initiator, mapId));
    built.add(new UnitCancelRouteTool(core, initiator, mapId));
    built.add(new UnitDisbandTool(core, initiator, mapId));
    built.add(new UnitSetStatusTool(core, initiator, mapId));
    built.add(new UnitAttachTool(core, initiator, mapId));
    built.add(new UnitDetachTool(core, initiator, mapId));
    built.add(new UnitReparentSubtreeTool(core, initiator, mapId));
    built.add(new UnitSetFormationOffsetTool(core, initiator, mapId));
    built.add(new UnitSplitFormationTool(core, initiator, mapId));
    built.add(new UnitMergeFormationTool(core, initiator, mapId));
    built.add(new UnitPlanSparseRouteTool(core, initiator, mapId));
    built.add(new UnitSetRejoinTargetTool(core, initiator, mapId));
    built.add(new UnitCreateCommandChainTool(core, initiator, mapId));
    built.add(new UnitUpdateCommandChainTool(core, initiator, mapId));
    built.add(new UnitApplyCasualtiesTool(core, initiator, mapId));
  }

  private static List<AgentTool> readTools(
      CoreSimos core, QueryService query, String mapId, Set<String> commandTypes) {
    return List.of(
        new CatalogTool(commandTypes),
        new StateResolveTool(query, mapId),
        new StateFacetsTool(query, mapId),
        new BranchListTool(core),
        new MapOverviewTool(query, mapId),
        new MapHexTool(query, mapId),
        new UnitListTool(query),
        new UnitGetTool(query),
        new PopulationTool(query));
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public List<AgentTool> listTools() {
    return tools;
  }

  /** 静态工具集：订阅即时成立、永不变更，故返回空句柄（契约要求非 null）。 */
  @Override
  public AutoCloseable onChange(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    return () -> {};
  }
}
