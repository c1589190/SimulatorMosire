package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.SdQueryService;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.DecisionMakerId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code simos.sd.decision-maker}（工具面 M4）：**单个决策人详情**。
 *
 * <p>与 {@link DecisionMakersTool} 同一缺口的两半（列表 / 详情）；未知 id ⇒ {@code NOT_FOUND}（**不是空对象**： 与 GUI 的 404
 * 同口径，不把"查无"折成"存在但空"）。
 *
 * <p>★ **形状与 GUI 同源**：{@link ApiViews#decisionMaker(SdQueryService.DecisionMakerInfo)}。 ★ **只在 GM
 * 桶**（{@link GmOnlyRead}），理由同类表（见该类注）。
 */
public final class DecisionMakerTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.decision-maker";

  private final SdQueryService sd;

  public DecisionMakerTool(QueryService query) {
    this.sd = new SdQueryService(query);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "单个决策人详情：{id,affiliation,rootUnit,allowedTools,providerId,viewScope 计数,…}";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("decisionMakerId", ToolSupport.prop("string", "决策人 id"));
    return ToolSupport.schema(props, List.of("decisionMakerId"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String id = ToolSupport.requiredText(args, "decisionMakerId");
      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      Optional<SdQueryService.DecisionMakerInfo> info =
          sd.decisionMaker(new DecisionMakerId(id), target);
      if (info.isEmpty()) {
        return ToolResult.error("NOT_FOUND", "决策人不存在: " + id);
      }
      return ToolSupport.ok(ApiViews.decisionMaker(info.get()));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
