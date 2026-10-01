package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.sd.diplomatic-events}（D5 / D-005 / R6）：**外交事件只读面**——多国谈判逐 tick 记录参与国与自然语言内容。
 *
 * <p>形状复用 {@link ApiViews#diplomaticEvents(java.util.Map)}（GUI / 后续读口同源），本类只装配 {@code
 * {"events":[…]}}。
 *
 * <p>★ **四桶共享读**（不标 {@code GmOnlyRead}）：R6 的"关系默认共享读"同样覆盖事件记录；内容是世界级自然语言，不含视野数据、 不做逐格过滤（D-002）。
 *
 * <p>★ <b>过滤</b>：{@code tick?} 只列该世界日的事件；{@code participant?} 只列参与国含该 Nation 的事件；两者都给 = 交集。
 * 过滤参数只按形状解析（未知 Nation / 无命中 tick ⇒ 空结果），不做存在性查询。
 */
public final class SdDiplomaticEventsTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.diplomatic-events";

  /** 本工具读 sd 切片（世界级共享文本，不做逐格视野过滤）。 */
  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final QueryService query;

  public SdDiplomaticEventsTool(QueryService query) {
    this.query = query;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "外交事件清单（D-005 多国谈判逐 tick 记录）：{events:[{eventId,tick,participants,text}]}；"
        + "participants ≥2 不重复、按记录顺序；text 自然语言。可选 tick 只看该世界日、participant 只看参与国含该 Nation；"
        + "世界级文本，四桶共享读。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("tick", ToolSupport.prop("integer", "只看该世界日的事件（缺省 = 全部）"));
    props.put("participant", ToolSupport.prop("string", "只看参与国含该 Nation 的事件（缺省 = 全部）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return SD_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Long tick = ToolSupport.optionalLong(args, "tick");
      String rawParticipant = ToolSupport.optionalText(args, "participant", null);
      NationId participant = rawParticipant == null ? null : NationId.parse(rawParticipant);
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      SdState sd = ToolSupport.sdState(state);
      Map<DiplomaticEventId, DiplomaticEvent> selected = new LinkedHashMap<>();
      for (Map.Entry<DiplomaticEventId, DiplomaticEvent> entry : sd.diplomaticEvents().entrySet()) {
        DiplomaticEvent event = entry.getValue();
        if (tick != null && event.tick() != tick) {
          continue;
        }
        if (participant != null && !event.participants().contains(participant)) {
          continue;
        }
        selected.put(entry.getKey(), event);
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("events", ApiViews.diplomaticEvents(selected));
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
