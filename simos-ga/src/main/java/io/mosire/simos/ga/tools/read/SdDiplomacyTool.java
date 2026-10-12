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
 * {@code simos.sd.diplomacy}（D5 / D-003 / R6）：**外交关系边只读面**。
 *
 * <p>每个 Nation 对其他 Nation 一条**有向边**（{@code from → to}），内容 = 自然语言（含谈判状态）。形状复用 {@code
 * ApiViews.diplomaticRelations}——与 GUI / 后续读口同源，本类只装配 {@code {"relations":[…]}}。
 *
 * <p>★ **四桶共享读**（不标 {@code GmOnlyRead}）：R6 明写"关系文本默认四桶共享读"；关系文本是**世界级自然语言**，不含视野数据 ⇒
 * 不做逐格过滤，也**不**因此给中央 DM 任何地图/单位视野（D-002）。
 *
 * <p>★ <b>过滤</b>：{@code nation?} 只看 from 或 to 等于该 Nation 的边；缺省 = 全部。过滤参数只按形状解析（未知 Nation ⇒
 * 空结果），不做存在性查询——存在性约束是写命令的语义（{@code gov.SetDiplomaticRelation}）。
 */
public final class SdDiplomacyTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.diplomacy";

  /** 本工具读 sd 切片（世界级共享文本，不做逐格视野过滤）。 */
  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final ToolReadPort reads;
  private final GaToolViews views;

  public SdDiplomacyTool(ToolReadPort reads, GaToolViews views) {
    this.reads = reads;
    this.views = views;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "外交关系边清单（有向 from→to；D-003 自然语言语义）："
        + "{relations:[{from,to,kind,text,updatedTick}]}；kind 可为 null；text 自然语言，谈判状态就记在它里面。"
        + "可选 nation 只看该 Nation 的边（from 或 to 命中）；世界级文本，四桶共享读。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSchema.targetProps());
    props.put("nation", ToolSchema.prop("string", "只看该 Nation 的关系边（缺省 = 全部）"));
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
      String nation = ToolSchema.optionalText(args, "nation", null);
      if (nation != null && nation.isBlank()) {
        // ★ 与迁移前逐字同口径：NationId.parse 对空白串抛 IllegalArgumentException ⇒ 同一句 BAD_REQUEST。
        throw new IllegalArgumentException("NationId 不得为空白: " + nation);
      }
      SimulationState state = GaToolReads.state(reads, args);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("relations", views.diplomaticRelations(state, nation));
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
