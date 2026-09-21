package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code map.RegisterPathwayGroup} 窄工具（M1，spec §八.3）：**GM 注册通路组**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故 {@code EXTERNAL_WITH_GM} 复合口也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **它改的是组定义、不是边**：注册后 {@code map.SetEdge{kind:…}} 才认这个词；未注册的 {@code kind} 仍被域层
 * fail-closed 拒绝。{@code color} 必须是 {@code #RRGGBB} 形式（非此形态 ⇒ 域层拒绝，理由原文到达调用方）。
 */
public final class MapRegisterPathwayGroupTool extends AbstractNarrowWriteTool {

  public static final String NAME = "map.RegisterPathwayGroup";

  public MapRegisterPathwayGroupTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "注册通路组 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 注册通路组：固定 map.RegisterPathwayGroup，载荷 {id, name, color（#RRGGBB）, description?, visible?（缺省"
        + " true）, properties?}";
  }
}
