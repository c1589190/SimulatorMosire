package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code map.DeleteRegion} 窄工具（M1，spec §八.3）：**GM 删区域**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故运行时 MCP 口（= GM 组）也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **不做静默幂等**：目标区域不存在 ⇒ 域层 fail-closed 拒绝（"删一个不存在的东西"是坏载荷，不是一次无副作用的写）。
 */
public final class MapDeleteRegionTool extends AbstractNarrowWriteTool {

  public static final String NAME = "map.DeleteRegion";

  public MapDeleteRegionTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "删区域 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 删区域：固定 map.DeleteRegion，载荷 {regionId}";
  }
}
