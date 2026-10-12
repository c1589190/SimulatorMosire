package io.mosire.simos.map.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/**
 * {@code map.SetTerrain} 窄工具（M1，spec §八.3）：**GM 改地形**的唯一窄写面。
 *
 * <p>★ **只在 GM 组**（{@code SimosToolSource.Role#GM}，= 运行时 MCP 口）：命令类型固定，模型只能给载荷； "选什么命令"不再是可自由发挥的面。标
 * sensitive ⇒ GM Agent 经 MCP 调用时走**审批门链**。
 *
 * <p>★ **前置即错**：地形词表校验在域层（{@code TerrainCatalog}），词表外 key 经 {@code ToolResults.fold} 变成可读的 {@code
 * REJECTED}；工具层不重复校验（重复的那份能被 {@code simos.command.submit} 绕过 ⇒ 是装饰）。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-map}，对 {@code CoreSimos}
 * 的直连换成构造期注入的 {@link ToolWritePort}（命令类型、载荷、拒绝码、资源声明、审批门逐字不变）。
 */
public final class MapSetTerrainTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "map.SetTerrain";

  public MapSetTerrainTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改地形 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 改地形：固定 map.SetTerrain，载荷 {hexes[{q,r}…], terrain}";
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
