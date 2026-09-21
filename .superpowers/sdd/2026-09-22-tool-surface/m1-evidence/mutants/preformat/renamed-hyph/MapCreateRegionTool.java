package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code map.CreateRegion} 窄工具（M1，spec §八.3）：**GM 建区域**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故 {@code EXTERNAL_WITH_GM} 复合口也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **重叠是正常状态**（M8-U1）：新建区域与既有区域相交**不报错**。`regionId` 已存在 ⇒ 域层 fail-closed
 * 拒绝（不静默覆盖），理由原文经 {@code ToolSupport.fold} 到达调用方。
 */
public final class MapCreateRegionTool extends AbstractNarrowWriteTool {

  public static final String NAME = "map.CreateRegion";

  public MapCreateRegionTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "建区域 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 建区域：固定 map.CreateRegion，载荷 {regionId, name, hexes[{q,r}…], meta?}";
  }
}
