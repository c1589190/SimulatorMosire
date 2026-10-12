package io.mosire.simos.ga.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaToolQueries;
import io.mosire.simos.ga.tools.GaToolReads;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.sd.decision-maker}（工具面 M4）：**单个决策人详情 + 现算可见范围**。
 *
 * <p>与 {@link DecisionMakersTool} 同一缺口的两半（列表 / 详情）；未知 id ⇒ {@code NOT_FOUND}（**不是空对象**： 与 GUI 的 404
 * 同口径，不把"查无"折成"存在但空"）。
 *
 * <p>★★ **2026-09-25 工具面补齐：把 GUI 的 {@code GET /api/sd/decision-makers/{id}/scope}
 * 并进本工具**（用户裁定"优先并入， 别为同一资源再造工具"）。{@code scope} 子对象回答的是 {@code ApiViews.decisionMaker}
 * 答不出的那个问题：决策人**实际能看见什么** （范围函数现算 ∩ GM 的 {@code accessLimit}，随世界状态变——正是 GUI {@code
 * decisionMakerScopeReply} 给的 {@code visible/namespaces/unparsedPrefixes}）。并入后 GM
 * 只用一条工具就能同时核对"我配了什么"与"算出来收到了多少"。
 *
 * <p>★ **并入不另算**：范围取 {@code RedactingQueryService#computedScopeOf}，解码取 {@code DecisionScopeView}，
 * 投影取 {@code ApiViews.decisionScope}——与 GUI 同一条链（本类不自己算范围，也不另拼 scope 的 payload）。
 *
 * <p>★ **未并入的相邻端点**：{@code GET /api/sd/decision-makers/{id}/run-status} **有意不并**——它读的是进程内的 {@code
 * DecisionRunRegistry}（"这一轮跑到哪儿了"，GUI 异步轮询用，进程重启即失），**不是世界状态**；且 MCP 的 {@code sd.RunDecision}
 * 是同步的（调用方等到整轮结束直接拿轨迹），agent 没有轮询需求。把运行时观测面塞进这条世界读工具会 把"不可回放的观测"伪装成"决策人的属性"。详见审计报告。
 *
 * <p>★ **形状与 GUI 同源**：{@code ApiViews.decisionMaker(...)} + {@code scope}。 ★ **只在 GM 桶**（{@link
 * GmOnlyRead}），理由同类表（见该类注）。
 */
public final class DecisionMakerTool implements SimosTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.decision-maker";

  private final GaToolQueries queries;

  public DecisionMakerTool(GaToolQueries queries) {
    this.queries = queries;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "单个决策人详情：{id,affiliation,rootUnit,allowedTools,providerId,viewScope 计数,scope{visible,…},…}"
        + "（scope = 现算可见范围：范围函数 ∩ GM 限制）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSchema.targetProps());
    props.put("decisionMakerId", ToolSchema.prop("string", "决策人 id"));
    return ToolSchema.schema(props, List.of("decisionMakerId"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String id = ToolSchema.requiredText(args, "decisionMakerId");
      // ★ 与 GUI `/api/sd/decision-makers/{id}/scope` 同口径：解不出决策人或算不出范围 ⇒ 同一个 404（不折成空对象）。
      Optional<Map<String, Object>> view =
          queries.decisionMaker(id, GaToolReads.branch(args), GaToolReads.revision(args));
      if (view.isEmpty()) {
        return ToolResult.error("NOT_FOUND", "决策人不存在: " + id);
      }
      return ToolResults.ok(view.get());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
