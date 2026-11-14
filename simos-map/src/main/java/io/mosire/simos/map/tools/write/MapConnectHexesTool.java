package io.mosire.simos.map.tools.write;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code map.ConnectHexes} 的结构化窄工具：GM 按有序格链直接增/删既有 {@code EdgeRef/EdgeTags} 上的某个 kind。
 *
 * <p>★ <b>只做点名边</b>：{@code merge} 只给链上相邻两格的边补该 kind（保留该边该 kind 的 props），{@code remove} 只摘这些边上的该
 * kind；其他 kind、其他边、地形/土地/通路实体一律不动。不整图 replace、不寻路、不猜缺口。
 *
 * <p>★ <b>整链先校验后一次提交</b>：2..12001 点、端点存在、相邻（MapWrap aware）、无自环/重复无向边；闭环允许起点再出现。
 */
public final class MapConnectHexesTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "map.ConnectHexes";

  public MapConnectHexesTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "直接改连通性 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 按有序格链直接增/删河流/道路等连通性：kind（当前地图已注册通路组）、hexes=[{q,r}...]（2..12001 点，"
        + "相邻链）、op=merge|remove。merge 只补点名边该 kind 并保留 props；remove 只摘点名边该 kind；"
        + "其他边/kind/组件一律保留。跨格跳跃、自环、重复无向边整批拒绝。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("branch", ToolSchema.prop("string", "分支名"));
    props.put("expectedRevision", ToolSchema.prop("integer", "期望的 base revision（乐观并发）"));
    props.put("kind", ToolSchema.prop("string", "连通性类型（当前地图已注册通路组，如 river/road）"));
    Map<String, Object> item = ToolSchema.prop("object", "链上的一个格");
    Map<String, Object> itemProps = new LinkedHashMap<>();
    itemProps.put("q", ToolSchema.prop("integer", "六角列坐标 q"));
    itemProps.put("r", ToolSchema.prop("integer", "六角行坐标 r"));
    item.put("properties", itemProps);
    item.put("required", List.of("q", "r"));
    Map<String, Object> hexes = ToolSchema.prop("array", "按顺序连接的格链；2..12001 点，相邻两格成一条边");
    hexes.put("items", item);
    props.put("hexes", hexes);
    props.put("op", ToolSchema.prop("string", "merge（只加不删）| remove（只摘点名边该 kind）"));
    return ToolSchema.schema(props, List.of("branch", "expectedRevision", "kind", "hexes", "op"));
  }

  @Override
  protected String payloadJson(ToolContext context) {
    Map<String, Object> args = context.arguments();
    Object kind = args.get("kind");
    Object hexes = args.get("hexes");
    Object op = args.get("op");
    if (!(kind instanceof String) || !(hexes instanceof List<?>) || !(op instanceof String)) {
      throw new IllegalArgumentException("参数必须给 kind（字符串）、hexes（[{q,r}...] 数组）、op（merge|remove）");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("kind", kind);
    payload.put("hexes", hexes);
    payload.put("op", op);
    return payloadJsonOf(payload);
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
