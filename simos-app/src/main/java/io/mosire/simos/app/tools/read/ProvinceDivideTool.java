package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gov.ProvinceDivider;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.state.SimulationState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.province.divide}（P3）：**只读**省份划分建议。
 *
 * <p>★★ <b>本工具零写入</b>：只读目标 Region 与地图，跑 {@link ProvinceDivider} 的纯函数递归主轴二分，输出建议省界； <b>不创建任何 Region
 * / Unit / DecisionMaker，不落 revision</b>。实际创省由 GM Agent 拿本建议自行调用现有 GM 工具完成
 * ——「只出建议、不写状态」是用户裁定（省份划分器只做建议，落盘由 Agent 显式执行）。
 *
 * <p>★ <b>桶归属</b>：标 {@link GmOnlyRead} ⇒ 只进 GM/MCP 桶，不给决策人（划分建议是 GM 的规划动作）。资源的声明只看 map 域（{@link
 * ToolSupport#MAP_READ}）。
 *
 * <p>★ <b>确定性</b>：同一 {@code (regionId, 参数, branch, revision)} 两次调用逐字段相同；{@code hexes} 按自然序， {@code
 * suggestionHash} 是建议内容的 SHA-256。
 *
 * <p>★ <b>相交 Region</b>：{@code overlappingRegions} 列出除目标 Region 外与它相交的全部 Region（id/name/tag/相交格数）。
 * 区域重叠 ≠ 省籍 —— 是否 override 由调用方显式决定，本工具只列信息、不影响建议。
 */
public final class ProvinceDivideTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.province.divide";

  private final QueryService query;
  private final String mapId;

  public ProvinceDivideTool(QueryService query, String mapId) {
    this.query = query;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "省份划分建议（只读，不写任何状态）：对已存在的 Region 做递归主轴二分，输出建议省界"
        + "（id/name/center/hexes/hexCount/contiguous/warnings）、可选首都圈、相交 Region 清单与 suggestionHash";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("regionId", ToolSupport.prop("string", "目标区域 id（必须存在于当前地图的 regions()）"));
    props.put(
        "maxHexPerProvince",
        ToolSupport.prop(
            "integer", "每省 hex 上限（缺省 " + ProvinceDivider.DEFAULT_MAX_HEX_PER_PROVINCE + "）"));
    props.put(
        "minHexPerProvince",
        ToolSupport.prop(
            "integer",
            "每省 hex 下限（缺省 "
                + ProvinceDivider.DEFAULT_MIN_HEX_PER_PROVINCE
                + "；必须 ≥1 且 ≤ maxHexPerProvince）"));
    Map<String, Object> capitalHex = new LinkedHashMap<>();
    capitalHex.put("type", "object");
    capitalHex.put("description", "可选首都格；给出时必须落在目标 Region 的 hex 集内");
    Map<String, Object> capitalHexProps = new LinkedHashMap<>();
    capitalHexProps.put("q", ToolSupport.prop("integer", "六角列坐标 q"));
    capitalHexProps.put("r", ToolSupport.prop("integer", "六角行坐标 r"));
    capitalHex.put("properties", capitalHexProps);
    capitalHex.put("required", List.of("q", "r"));
    props.put("capitalHex", capitalHex);
    props.put(
        "capitalDistrictRadius",
        ToolSupport.prop(
            "integer",
            "首都圈半径（缺省 "
                + ProvinceDivider.DEFAULT_CAPITAL_DISTRICT_RADIUS
                + "；只在给出 capitalHex 时使用；半径内 ∩ Region 为空则忽略首都圈）"));
    props.put("namingPrefix", ToolSupport.prop("string", "建议省名前缀（缺省 = 目标 Region 名）"));
    return ToolSupport.schema(props, List.of("regionId"));
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.MAP_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String id = ToolSupport.requiredText(args, "regionId");
      int max =
          intOrDefault(args, "maxHexPerProvince", ProvinceDivider.DEFAULT_MAX_HEX_PER_PROVINCE);
      int min =
          intOrDefault(args, "minHexPerProvince", ProvinceDivider.DEFAULT_MIN_HEX_PER_PROVINCE);
      if (min < 1) {
        throw new IllegalArgumentException("minHexPerProvince 必须 ≥1: " + min);
      }
      if (max < min) {
        throw new IllegalArgumentException(
            "maxHexPerProvince 必须 ≥ minHexPerProvince: max=" + max + ", min=" + min);
      }
      HexCoord capital = optionalHex(args, "capitalHex");
      int radius = ProvinceDivider.DEFAULT_CAPITAL_DISTRICT_RADIUS;
      if (capital != null) {
        radius = intOrDefault(args, "capitalDistrictRadius", radius);
        if (radius < 0) {
          throw new IllegalArgumentException("capitalDistrictRadius 不能为负: " + radius);
        }
      }
      String namingPrefix = ToolSupport.optionalText(args, "namingPrefix", null);

      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      SimulationState state = query.stateAt(target);
      GameMap map = ApiViews.gameMap(state);
      RegionId regionId = RegionId.parse(id);
      Region region = map.regions().get(regionId);
      if (region == null || !ToolSupport.regionVisible(context, mapId, regionId)) {
        return ToolResult.error("NOT_FOUND", "区域不存在: " + id);
      }

      ProvinceDivider.Params params =
          new ProvinceDivider.Params(
              min, max, capital, radius, namingPrefix == null ? region.name() : namingPrefix);
      return ToolSupport.ok(view(ProvinceDivider.divide(map, regionId, params)));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** 类型化建议 → JSON 视图（字段顺序固定；{@code capitalDistrict} 缺席时不出现该键）。 */
  private static Map<String, Object> view(ProvinceDivider.Suggestion suggestion) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("regionId", suggestion.regionId());
    out.put("regionName", suggestion.regionName());
    out.put("hexCount", suggestion.hexCount());
    out.put("algorithm", ProvinceDivider.ALGORITHM);
    out.put("minHexPerProvince", suggestion.minHexPerProvince());
    out.put("maxHexPerProvince", suggestion.maxHexPerProvince());
    out.put("suggestedProvinceCount", suggestion.suggestedProvinceCount());
    List<Map<String, Object>> provinces = new ArrayList<>(suggestion.provinces().size());
    for (ProvinceDivider.Province province : suggestion.provinces()) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("suggestedRegionId", province.suggestedRegionId());
      view.put("name", province.name());
      view.put("center", hexView(province.center()));
      view.put("hexes", hexViews(province.hexes()));
      view.put("hexCount", province.hexCount());
      view.put("contiguous", province.contiguous());
      view.put("warnings", List.copyOf(province.warnings()));
      provinces.add(view);
    }
    out.put("provinces", provinces);
    if (suggestion.capitalDistrict() != null) {
      ProvinceDivider.CapitalDistrict district = suggestion.capitalDistrict();
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("center", hexView(district.center()));
      view.put("hexes", hexViews(district.hexes()));
      view.put("hexCount", district.hexCount());
      out.put("capitalDistrict", view);
    }
    List<Map<String, Object>> overlaps = new ArrayList<>(suggestion.overlappingRegions().size());
    for (ProvinceDivider.Overlap overlap : suggestion.overlappingRegions()) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("regionId", overlap.regionId());
      view.put("name", overlap.name());
      view.put("tag", overlap.tag());
      view.put("overlapHexCount", overlap.overlapHexCount());
      overlaps.add(view);
    }
    out.put("overlappingRegions", overlaps);
    out.put("warnings", List.copyOf(suggestion.warnings()));
    out.put("suggestionHash", suggestion.suggestionHash());
    return out;
  }

  private static List<Map<String, Object>> hexViews(List<HexCoord> hexes) {
    List<Map<String, Object>> views = new ArrayList<>(hexes.size());
    for (HexCoord hex : hexes) {
      views.add(hexView(hex));
    }
    return views;
  }

  /** {@code {"q":…,"r":…}}（与 GUI 视图同形；身份仍是 HexCoord 类型，这个串只在 JSON 边界）。 */
  private static Map<String, Object> hexView(HexCoord hex) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("q", hex.q());
    view.put("r", hex.r());
    return view;
  }

  /** 可选 int 参数：先按 long 解析，再显式判 int 范围（不静默截断）。 */
  private static int intOrDefault(Map<String, Object> args, String name, int fallback) {
    Long value = ToolSupport.optionalLong(args, name);
    if (value == null) {
      return fallback;
    }
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("参数 " + name + " 超出 int 范围: " + value);
    }
    return value.intValue();
  }

  /** 可选的 {@code {"q":整数,"r":整数}} 对象；缺席/null ⇒ null。 */
  private static HexCoord optionalHex(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> object)) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是 {\"q\":整数,\"r\":整数} 对象");
    }
    return new HexCoord(
        requiredComponent(object.get("q"), name, "q"),
        requiredComponent(object.get("r"), name, "r"));
  }

  /** 一个坐标分量：必须是整数（允许 Integer/Long 等整型 Number；浮点必须无小数部分），且落在 int 范围内。 */
  private static int requiredComponent(Object value, String field, String part) {
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException("参数 " + field + " 的 " + part + " 必须是整数");
    }
    if (number instanceof Double || number instanceof Float) {
      if (hasFraction(number.doubleValue())) {
        throw new IllegalArgumentException("参数 " + field + " 的 " + part + " 必须是整数: " + value);
      }
    }
    long asLong = number.longValue();
    if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("参数 " + field + " 的 " + part + " 超出 int 范围: " + value);
    }
    return (int) asLong;
  }

  /**
   * 浮点数是否有小数部分（NaN / 无穷 ⇒ 也算"不是整数"）。用 {@link BigDecimal} 精确判定，不写浮点相等/取模比较 （SpotBugs 的 {@code
   * FE_FLOATING_POINT_EQUALITY} 靶子）。
   */
  private static boolean hasFraction(double value) {
    if (!Double.isFinite(value)) {
      return true;
    }
    return BigDecimal.valueOf(value).stripTrailingZeros().scale() > 0;
  }
}
