package io.mosire.simos.map.tools.write;

import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;

/**
 * {@code map.CreateRegion} 窄工具（M1，spec §八.3）：**GM 建区域**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故运行时 MCP 口（= GM 组）也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **重叠是正常状态**（M8-U1）：新建区域与既有区域相交**不报错**。`regionId` 已存在 ⇒ 域层 fail-closed 拒绝（不静默覆盖），理由原文经 {@code
 * ToolResults.fold} 到达调用方。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-map}，对 {@code CoreSimos}
 * 的直连换成构造期注入的 {@link ToolWritePort}（命令类型、载荷、拒绝码、资源声明、审批门逐字不变）。
 */
public final class MapCreateRegionTool extends AbstractNarrowWriteTool {

  public static final String NAME = "map.CreateRegion";

  public MapCreateRegionTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
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
