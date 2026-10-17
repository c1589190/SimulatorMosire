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
 * {@code map.SetHexArea} 窄工具（2026-10-12 批次 2）：<b>GM 改单格可用地面积</b>的唯一窄写面（用户原话「工具两者都要，走工单， Map
 * 额外实现工具」= 增量与全量都要、走既有命令路径、由 map 模块自己实现工具）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}，紧邻 {@code
 * MapSetTerrainTool}）：命令类型固定，模型只能给载荷。 标 sensitive ⇒ 走审批门链。它提交的 {@code map.SetHexArea} handler **不打
 * {@code DmUsableCommand}** ⇒ 决策人令 / {@code RegisterEffect} / 决策人 catalog 三条路径都到不了（用户原话：「map
 * 层级的命令…肯定不该给 DM 用」）。
 *
 * <p>★ <b>载荷</b>：{@code {hex:{q,r}, key|"customKey", op, value}} —— 内置字段走 {@code key}（enum 标签
 * {@code urban} / {@code rural}），**自定义字段**走 {@code customKey}（String，类型上就标注了它是什么），两者恰给一个；{@code op}
 * 为 {@code set}（全量赋值，{@code value} = 新的绝对面积毫亩 ≥ 0）或 {@code add}（增量
 * ±毫亩，基数是**当前读出值**：没赋过值的格从该地形的默认可用面积起算）。 图外 hex、负面积、增量落到负数、两个键都给/都不给 ⇒ 域层具名 {@code REJECTED}（零
 * revision）。
 */
public final class MapSetHexAreaTool extends AbstractNarrowWriteTool implements SimosTool {

  /** 工具名（与命令类型同名，同一个拼写点；它同时进 catalog 的载荷提示表）。 */
  public static final String NAME = "map.SetHexArea";

  public MapSetHexAreaTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改格可用地面积 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 改单格可用地面积：固定 map.SetHexArea，载荷 {hex{q,r}(必填), "
        + "key(内置字段标签 urban|rural) 或 customKey(自定义字段名，字符串)（两者恰给一个）, op(set|add), "
        + "value(set: 新绝对面积毫亩 ≥0；add: 有符号增量毫亩，基数是当前读出值、结果不得为负)}；"
        + "一次只改一格一个字段；图外 hex / 负面积 ⇒ 具名拒（零 revision）。"
        + "GM-only：决策人不能嵌入令。读回时没赋过值的字段按该地形的默认可用面积显示（以 rural 呈现）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "payloadJson",
        ToolSchema.prop(
            "string",
            "命令载荷 JSON 文本：{hex:{q,r}(必填，必须在图上), key(内置字段标签 urban|rural) 或"
                + " customKey(自定义字段名，非空白字符串)（恰给一个）, op(set|add), value(整数毫亩：set ⇒ ≥0 的绝对面积；"
                + "add ⇒ ±增量，结果不得为负)}"));
    props.put("branch", ToolSchema.prop("string", "分支名"));
    props.put("expectedRevision", ToolSchema.prop("integer", "期望的 base revision（乐观并发）"));
    return ToolSchema.schema(props, List.of("branch", "expectedRevision"));
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
