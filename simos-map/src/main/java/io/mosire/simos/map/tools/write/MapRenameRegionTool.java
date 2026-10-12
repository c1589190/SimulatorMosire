package io.mosire.simos.map.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code map.RenameRegion} 窄工具（R4，行政区划修复计划 §1.4）：**GM 改区域名**的唯一窄写面。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}，紧邻 {@code MapUpdateRegionTool}）：命令类型固定，
 * 模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ <b>载荷</b>：{@code {regionId, name}}，两者都必填且非空白；{@code regionId} 必须已是当前地图里的 Region （改名不是
 * upsert），否则域层具名 {@code REJECTED}。改名只动 name，Region 的内容/边界不变。
 *
 * <p>★ <b>GM-only 命令</b>：它提交的 {@code map.RenameRegion} handler 不打 {@code DmUsableCommand} ⇒ 决策人令 /
 * {@code RegisterEffect} / 决策人 catalog 三条路径都到不了；GM 直接提交与这条窄工具照常可用。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-map}，对 {@code CoreSimos}
 * 的直连换成构造期注入的 {@link ToolWritePort}（命令类型、载荷、拒绝码、资源声明、审批门逐字不变）。
 */
public final class MapRenameRegionTool extends AbstractNarrowWriteTool implements SimosTool {

  /** 工具名（与命令类型同名，同一个拼写点）。 */
  public static final String NAME = "map.RenameRegion";

  public MapRenameRegionTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改区域名 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 改区域名：固定 map.RenameRegion，载荷 {regionId(必填，必须已存在), name(必填非空白)}；"
        + "只改显示名，不动 Region 的 hex 内容与边界；regionId 查无 ⇒ 具名拒。GM-only：决策人不能嵌入令。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "payloadJson",
        ToolSchema.prop(
            "string",
            "命令载荷 JSON 文本：{regionId(必填，必须是当前地图里已存在的 Region)," + " name(必填非空白)}；只改显示名，不动内容与边界"));
    props.put("branch", ToolSchema.prop("string", "分支名"));
    props.put("expectedRevision", ToolSchema.prop("integer", "期望的 base revision（乐观并发）"));
    return ToolSchema.schema(props, List.of("branch", "expectedRevision"));
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
