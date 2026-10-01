package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.sd.diplomacy}（D5 / D-003 / R6）：**外交关系边只读面**。
 *
 * <p>每个 Nation 对其他 Nation 一条**有向边**（{@code from → to}），内容 = 自然语言（含谈判状态）。形状复用 {@link
 * ApiViews#diplomaticRelations(java.util.Map, NationId)}——与 GUI / 后续读口同源，本类只装配 {@code
 * {"relations":[…]}}。
 *
 * <p>★ **四桶共享读**（不标 {@code GmOnlyRead}）：R6 明写"关系文本默认四桶共享读"；关系文本是**世界级自然语言**，不含视野数据 ⇒
 * 不做逐格过滤，也**不**因此给中央 DM 任何地图/单位视野（D-002）。
 *
 * <p>★ <b>过滤</b>：{@code nation?} 只看 from 或 to 等于该 Nation 的边；缺省 = 全部。过滤参数只按形状解析（未知 Nation ⇒
 * 空结果），不做存在性查询——存在性约束是写命令的语义（{@code sd.SetDiplomaticRelation}）。
 */
public final class SdDiplomacyTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.sd.diplomacy";

  /** 本工具读 sd 切片（世界级共享文本，不做逐格视野过滤）。 */
  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final QueryService query;

  public SdDiplomacyTool(QueryService query) {
    this.query = query;
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
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("nation", ToolSupport.prop("string", "只看该 Nation 的关系边（缺省 = 全部）"));
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
      String rawNation = ToolSupport.optionalText(args, "nation", null);
      NationId nation = rawNation == null ? null : NationId.parse(rawNation);
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      SdState sd = ToolSupport.sdState(state);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("relations", ApiViews.diplomaticRelations(sd.diplomaticRelations(), nation));
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
