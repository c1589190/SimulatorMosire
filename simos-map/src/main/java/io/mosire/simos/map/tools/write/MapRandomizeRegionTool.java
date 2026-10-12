package io.mosire.simos.map.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/**
 * {@code map.RandomizeRegion} 窄工具（M1，spec §八.3）：**GM 随机化区域地形**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故运行时 MCP 口（= GM 组）也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **{@code seed} 没有默认值**：缺字段 ⇒ 域层 {@code requireLong} 当场拒。静默兜 0 会把"两次随机化不可复现"
 * 从一条被拒的载荷变成一次合法但不可解释的写。空 {@code hexes} 亦拒（域层文案含具体命令名，可判别）。
 *
 * <p>★ **两种地形同样没有默认值**（2026-09-24 用户裁定）：{@code terrainA}/{@code terrainB} 由调用方给（缺任一 ⇒ 拒）。
 * 原先这两种地形写死在域层（{@code plains}/{@code desert}），用户报「不管我在上面点击哪个地形，都只能替换为沙漠和平原」。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-map}，对 {@code CoreSimos}
 * 的直连换成构造期注入的 {@link ToolWritePort}（命令类型、载荷、拒绝码、资源声明、审批门逐字不变）。
 */
public final class MapRandomizeRegionTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "map.RandomizeRegion";

  public MapRandomizeRegionTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "随机化区域 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 随机化区域地形：固定 map.RandomizeRegion，载荷 {hexes[{q,r}…], terrainA, terrainB, seed（三者都无默认）}";
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
