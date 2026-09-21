package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code map.SetTerrain} 窄工具（M1，spec §八.3）：**GM 改地形**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（{@link io.mosire.simos.app.tools.SimosToolSource.Role#GM}，故 {@code
 * EXTERNAL_WITH_GM} 复合口也含它）：命令类型固定，模型只能给载荷；"选什么命令"不再是可自由发挥的面。标 sensitive ⇒ GM Agent 经 MCP
 * 调用时走**审批门链**。
 *
 * <p>★ **前置即错**：地形词表校验在域层（{@code TerrainCatalog}），词表外 key 经 {@code ToolSupport.fold} 变成可读的 {@code
 * REJECTED}；工具层不重复校验（重复的那份能被 {@code simos.command.submit} 绕过 ⇒ 是装饰）。
 */
public final class MapSetTerrainTool extends AbstractNarrowWriteTool {

  public static final String NAME = "map.SetTerrain";

  public MapSetTerrainTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return "unit.RenameUnit";
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改地形 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 改地形：固定 map.SetTerrain，载荷 {hexes[{q,r}…], terrain}";
  }
}
