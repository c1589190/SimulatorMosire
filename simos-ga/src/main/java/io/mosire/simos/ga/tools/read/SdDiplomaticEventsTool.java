package io.mosire.simos.ga.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.ga.tools.GaToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.sd.diplomatic-events}（D5 / D-005 / R6）：**外交事件只读面**——多国谈判逐 tick 记录参与国与自然语言内容。
 *
 * <p>形状复用 {@code ApiViews.diplomaticEvents}（GUI / 后续读口同源），本类只装配 {@code {"events":[…]}}。
 *
 * <p>★ **四桶共享读**（不标 {@code GmOnlyRead}）：R6 的"关系默认共享读"同样覆盖事件记录；内容是世界级自然语言，不含视野数据、 不做逐格过滤（D-002）。
 *
 * <p>★ <b>过滤</b>：{@code tick?} 只列该世界日的事件；{@code participant?} 只列参与国含该 Nation 的事件；两者都给 = 交集。
 * 过滤参数只按形状解析（未知 Nation / 无命中 tick ⇒ 空结果），不做存在性查询。
 *
 * <p>★★ <b>2026-10-12 批次 3c：过滤与取表搬进 {@link GaToolViews} 的实现（app）</b>——{@code diplomaticEvents} 表与
 * {@code NationId} 都随外交搬进 {@code simos-gov}，而 {@code simos-ga} 不得依赖 {@code gov} ⇒
 * 本类只把参数形状解析成"不透明国家引用 （{@code String}）+ tick"，筛选与排序由实现做（那里仍是"形状只有一处"）。★ 参数校验口径一字未改：{@code
 * NationId.parse} 原本只拒空白， 这里因此只判非空白。
 */
public final class SdDiplomaticEventsTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.diplomatic-events";

  /** 本工具读 sd 切片（世界级共享文本，不做逐格视野过滤）。 */
  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final ToolReadPort reads;
  private final GaToolViews views;

  public SdDiplomaticEventsTool(ToolReadPort reads, GaToolViews views) {
    this.reads = reads;
    this.views = views;
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
    Map<String, Object> props = new LinkedHashMap<>(ToolSchema.targetProps());
    props.put("tick", ToolSchema.prop("integer", "只看该世界日的事件（缺省 = 全部）"));
    props.put("participant", ToolSchema.prop("string", "只看参与国含该 Nation 的事件（缺省 = 全部）"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return SD_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Long tick = ToolSchema.optionalLong(args, "tick");
      String participant = ToolSchema.optionalText(args, "participant", null);
      if (participant != null && participant.isBlank()) {
        // ★ 与迁移前逐字同口径：NationId.parse 对空白串抛 IllegalArgumentException ⇒ 同一句 BAD_REQUEST。
        throw new IllegalArgumentException("NationId 不得为空白: " + participant);
      }
      SimulationState state = GaToolReads.state(reads, args);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("events", views.diplomaticEvents(state, tick, participant));
      return ToolResults.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
