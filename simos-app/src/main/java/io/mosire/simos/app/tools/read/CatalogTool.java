package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.command.catalog}（spec §7.1 读工具）：列出**已注册命令类型**及其载荷字段提示。
 *
 * <p>★ **判据②（R5）的载体**：清单与 {@code Shell} 实际注册的 handler **同源**（构造期注入），故 catalog 列出的每个 type 都能经 {@code
 * simos.command.submit} 到达。工具面不另立一份"支持的类型"表。
 *
 * <p>★ **不给 Core 加新面**（spec §7.1 原文）：Core 不暴露 {@code CommandRegistry.types()}，清单由 app 持有。
 */
public final class CatalogTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.command.catalog";

  /**
   * 每个已注册 type 的载荷字段提示（spec §四表；仅给人/模型看，不参与执行）。
   *
   * <p>★ **必须与注册面逐条对齐**（T10-j）：本表是"声明式清单不随注册面自动延伸"的第三个静默面——取值曾用 {@code getOrDefault(type, "")}
   * 兜底，缺项**不报错**、只回空串（看起来有值、实际是空）。⇒ 改为**构造期强制**（见 {@link #CatalogTool}）：任何已注册 type 在本表缺项即抛，缺项不再静默。
   */
  private static final Map<String, String> PAYLOAD_HINTS =
      Map.ofEntries(
          Map.entry("unit.RenameUnit", "id, name"),
          Map.entry(
              "unit.CreateUnit",
              "id, name, position{q,r}, member, equipment, speed, mobilityPerMille, parent?"),
          Map.entry("unit.ReparentUnit", "id, parent?（null=清根）"),
          Map.entry("unit.SetStrength", "id, member, equipment"),
          Map.entry("unit.PlaceAt", "id, hex{q,r}?（null=撤销位置）"),
          Map.entry("unit.PlanRoute", "id, waypoints[{q,r}...]"),
          Map.entry("unit.CancelRoute", "id"),
          Map.entry("unit.DisbandUnit", "id"),
          Map.entry("unit.SetStatus", "id, status(MOVING|RESTING|ENGAGED)"),
          Map.entry("unit.AttachUnit", "id, parent"),
          Map.entry("unit.DetachUnit", "id"),
          Map.entry("unit.ReparentSubtree", "rootId, parent"),
          Map.entry("unit.SetFormationOffset", "id, dq?, dr?"),
          Map.entry("unit.SplitFormation", "rootId, subUnitIds[字符串...]"),
          Map.entry("unit.MergeFormation", "childId, parentId"),
          Map.entry("unit.PlanSparseRoute", "id, waypoints[{q,r}...]（非相邻，逐段展开）"),
          Map.entry("unit.SetRejoinTarget", "id, target?（null=清回归意图）"),
          Map.entry("unit.CreateCommandChain", "chainId, name, commander, members[字符串...]"),
          Map.entry("unit.UpdateCommandChain", "chainId, name?, commander?, members?"),
          Map.entry("unit.ApplyCasualties", "id, personnel(负增量), equipment{键:负增量}"),
          Map.entry("map.SetTerrain", "hexes[{q,r}...], terrain"),
          Map.entry("map.CreateRegion", "regionId, name, hexes[{q,r}...], meta?"),
          Map.entry("map.UpdateRegion", "regionId, hexes?, meta?"),
          Map.entry("map.DeleteRegion", "regionId"),
          Map.entry("map.SetEdge", "kind, edges[字符串...], mode"),
          Map.entry(
              "map.RegisterPathwayGroup", "id, name, color, description?, visible?, properties?"),
          Map.entry("map.RandomizeRegion", "hexes[{q,r}...], seed"),
          Map.entry("sd.CreateNation", "nationId, name, homeRegionId, adminBudgetPerTick"),
          Map.entry("sd.CreateArmy", "armyId, nationId, rootUnitId, name"),
          Map.entry(
              "sd.CreateDecisionMaker", "id, affiliation{kind,id}, allowedTools[字符串...], cadence"),
          Map.entry(
              "sd.PutInfo",
              "address, key, value, note?, id?（同类型内唯一）, tags[决策人 id…]?,"
                  + " tick?（缺省=世界当前 tick；记在未来 ⇒ 拒）"),
          Map.entry("sd.CreateCombat", "combatId, name, participants[字符串...]"),
          Map.entry(
              "sd.AddCombatStage",
              "combatId, stage{stageId,name,participants?,entry,exit,minDurationTicks?,"
                  + "maxDurationTicks?,outcomes{options[{id,label,weight,casualties?}]}},"
                  + " combatStateId?(首阶段必填), hex{q,r}?(首阶段必填；非首阶段时两者都会被忽略)"),
          Map.entry(
              "sd.SetStageOutcomeTable",
              "combatId, stageId, outcomes{options[{id,label,weight,casualties?}]}"),
          Map.entry("sd.CommitCombatOutcome", "combatId, stageId, selectedOutcomeId"),
          Map.entry(
              "sd.RecordCasualties",
              "combatId, stageId, deltas[{unit,personnel,equipment,lossClass(PERMANENT|RECOVERABLE)}]"),
          Map.entry(
              "sd.RegisterEffect",
              "effectId, kind(SCHEDULED|ON_CALL|BE_PREPARED|BRANCH|SEQUEL), trigger, action, createdTick?"),
          Map.entry("sd.CancelEffect", "effectId"),
          Map.entry(
              "sd.IssueDirective",
              "directiveId, decisionMakerId, tick, target?, intentInfo, commands[{type,payloadJson}],"
                  + " effects[字符串...]?"),
          Map.entry(
              "sd.SubmitVerdict",
              "verdictId, breakpoint(D1|D3|D6), subject(sd:combat.*), payload(JSON 文本),"
                  + " meta{model,promptVersion,inputBriefDigest}"),
          Map.entry(
              "sd.SetDecisionMakerAccess",
              "decisionMakerId, allowedTools[]?, accessLimit{命名空间:[前缀…]}?, redactedFields[]?,"
                  + "adjudicationDisclosure(FULL|PERCEPTION_ONLY|WITHHELD)?"
                  + "（★ 四个可选字段：缺省 = 不改动，显式给 = 整份替换；"
                  + "accessLimit 是**额外限制**，与范围函数求交 ⇒ 只能收紧）"),
          Map.entry(
              "sd.ResetDecisionMakerConversation", "decisionMakerId（会话世代 +1：该决策人下一轮从空上下文重开；旧会话不删）"),
          Map.entry("sd.StartDecision", "decisionMakerId, note?"),
          Map.entry("sd.RunDecision", "decisionMakerId"),
          Map.entry("sd.SetDecisionMakerProvider", "decisionMakerId, providerId"));

  private final List<String> types;

  /**
   * @param commandTypes 已注册命令类型（与 {@code Shell} 注册的 handler 同源）；本类只读它
   * @throws IllegalArgumentException 有已注册 type 未登记载荷提示（缺项不静默——见 {@link #PAYLOAD_HINTS}）
   */
  public CatalogTool(Set<String> commandTypes) {
    List<String> sorted = new ArrayList<>(commandTypes);
    Collections.sort(sorted);
    List<String> missing = new ArrayList<>();
    for (String type : sorted) {
      if (!PAYLOAD_HINTS.containsKey(type)) {
        missing.add(type);
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("已注册命令类型未登记载荷提示（PAYLOAD_HINTS）: " + missing);
    }
    this.types = List.copyOf(sorted);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "列出本世界已注册的全部命令类型及其载荷字段提示（simos.command.submit 的 type/payloadJson 依据）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return Map.of("type", "object", "properties", Map.of());
  }

  @Override
  public ToolResult execute(ToolContext context) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("types", types);
    Map<String, Object> hints = new LinkedHashMap<>();
    for (String type : types) {
      // 构造期已断言本表覆盖全部 type（T10-j），此处不再兜底成空串（缺项不静默）。
      hints.put(type, PAYLOAD_HINTS.get(type));
    }
    view.put("payloadHints", hints);
    return ToolSupport.ok(view);
  }
}
