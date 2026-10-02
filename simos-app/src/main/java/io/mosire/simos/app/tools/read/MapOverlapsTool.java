package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code simos.map.overlaps}（用户 2026-10-02 报的缺口）：<b>区域重合检测</b>——GM 检查任意区域之间的重合， 尤其是同一 tag（如 {@code
 * Nation}）下的区域是否存在部分重合 / 完全重合 / 包含关系。
 *
 * <p>★ <b>只读、GM 专用</b>（{@link GmOnlyRead}）：区域几何是全图信息，决策人不给——避免越过视野泄露全图区域形状； 资源的声明只看 map 域（{@link
 * ToolSupport#MAP_READ}）。
 *
 * <p>★ <b>算法是 hex → 区域成员的倒排，不是 n² 区域对枚举</b>：先把候选区域的成员格收进 {@code Map<HexCoord,
 * List<regionId>>}，再对每个成员数 ≥2 的格做 i&lt;j 两两组合累加交集格数；候选区域按 {@code RegionId.value()} 字典序处理 ⇒
 * 每个成员列表天然字典序，{@link Pair} 因此可以规范成 {@code a < b}。
 *
 * <p>★ <b>输出确定性</b>：行按 {@code overlapHexCount} 降序 → {@code aRegionId} 升序 → {@code bRegionId}
 * 升序；{@code includeHexes=true} 时交集格按 q 升序、再 r 升序；单对最多 {@value #MAX_HEXES_PER_PAIR} 格， 超出只截断清单并置
 * {@code overlapHexesTruncated=true}（{@code overlapHexCount} 仍是真值）。
 */
public final class MapOverlapsTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.overlaps";

  /** 单对交集格清单的上限：超出只截断清单、不改 {@code overlapHexCount}。 */
  public static final int MAX_HEXES_PER_PAIR = 1024;

  /** {@code limit} 的缺省值。 */
  public static final int DEFAULT_LIMIT = 50;

  /** {@code limit} 的硬上限；超过明确拒（不静默截断）。 */
  public static final int MAX_LIMIT = 500;

  private final QueryService query;
  private final String mapId;

  public MapOverlapsTool(QueryService query, String mapId) {
    this.query = query;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "区域重合检测（只读，GM 专用）：两两 hex 交集、同 tag 过滤（如 tag=Nation）、完全重合/包含判定与重叠格数";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>(ToolSupport.targetProps());
    props.put("regionId", ToolSupport.prop("string", "只看涉及该区域的重合对（可选）"));
    props.put("tag", ToolSupport.prop("string", "只看两侧区域 meta.tag 逐字都等于该值的对（可选，如 Nation）"));
    props.put("sameTagOnly", ToolSupport.prop("boolean", "只看两侧 tag 非空且逐字相等的对（缺省 false）"));
    props.put("minOverlapHexes", ToolSupport.prop("integer", "交集格数下界，必须 ≥1（缺省 1；达到下界才输出）"));
    props.put(
        "wholeOnly", ToolSupport.prop("boolean", "只输出 relation != partial 的对（完全重合/包含；缺省 false）"));
    props.put(
        "includeHexes",
        ToolSupport.prop(
            "boolean", "每对附带交集格清单（按 q 升序、再 r 升序；单对最多 " + MAX_HEXES_PER_PAIR + " 格，超出截断；缺省 false）"));
    props.put(
        "limit",
        ToolSupport.prop(
            "integer",
            "返回对上限，1.." + MAX_LIMIT + "（缺省 " + DEFAULT_LIMIT + "；超上限即 BAD_REQUEST，不静默截断）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.MAP_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String regionIdText = ToolSupport.optionalText(args, "regionId", null);
      String tag = ToolSupport.optionalText(args, "tag", null);
      boolean sameTagOnly = ToolSupport.optionalBoolean(args, "sameTagOnly").orElse(false);
      Long rawMin = ToolSupport.optionalLong(args, "minOverlapHexes");
      long minOverlapHexes = rawMin == null ? 1L : rawMin;
      if (minOverlapHexes < 1L) {
        throw new IllegalArgumentException("minOverlapHexes 必须 ≥1: " + minOverlapHexes);
      }
      boolean wholeOnly = ToolSupport.optionalBoolean(args, "wholeOnly").orElse(false);
      boolean includeHexes = ToolSupport.optionalBoolean(args, "includeHexes").orElse(false);
      Long rawLimit = ToolSupport.optionalLong(args, "limit");
      long limit = rawLimit == null ? DEFAULT_LIMIT : rawLimit;
      if (limit < 1L) {
        throw new IllegalArgumentException("limit 必须 ≥1: " + limit);
      }
      if (limit > MAX_LIMIT) {
        throw new IllegalArgumentException("limit 上限为 " + MAX_LIMIT + ": " + limit);
      }
      var target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      SimulationState state = query.stateAt(target);
      GameMap map = ToolSupport.gameMap(state);

      RegionId selectedRegionId = null;
      if (regionIdText != null) {
        selectedRegionId = RegionId.parse(regionIdText);
        Region selected = map.regions().get(selectedRegionId);
        if (selected == null || !ToolSupport.regionVisible(context, mapId, selectedRegionId)) {
          return ToolResult.error("NOT_FOUND", "区域不存在: " + regionIdText);
        }
      }

      List<Region> candidates = new ArrayList<>();
      for (Region region : map.regions().values()) {
        String regionTag = region.meta().tag();
        if (tag != null && !tag.equals(regionTag)) {
          continue;
        }
        if (sameTagOnly && (regionTag == null || regionTag.isBlank())) {
          continue;
        }
        candidates.add(region);
      }
      candidates.sort(Comparator.comparing(region -> region.id().value()));

      Map<String, Region> regionById = new HashMap<>();
      Map<HexCoord, List<String>> memberships = new HashMap<>();
      for (Region region : candidates) {
        String id = region.id().value();
        regionById.put(id, region);
        for (HexCoord hex : region.hexes()) {
          memberships.computeIfAbsent(hex, ignored -> new ArrayList<>()).add(id);
        }
      }

      Map<Pair, Integer> overlapCounts = new HashMap<>();
      Map<Pair, List<HexCoord>> overlapHexes =
          includeHexes ? new HashMap<Pair, List<HexCoord>>() : Map.of();
      for (Map.Entry<HexCoord, List<String>> entry : memberships.entrySet()) {
        List<String> ids = entry.getValue();
        for (int i = 0; i < ids.size() - 1; i++) {
          for (int j = i + 1; j < ids.size(); j++) {
            Pair pair = new Pair(ids.get(i), ids.get(j));
            overlapCounts.merge(pair, 1, Integer::sum);
            if (includeHexes) {
              overlapHexes.computeIfAbsent(pair, ignored -> new ArrayList<>()).add(entry.getKey());
            }
          }
        }
      }
      if (includeHexes) {
        for (List<HexCoord> hexes : overlapHexes.values()) {
          hexes.sort(Comparator.naturalOrder());
        }
      }

      List<Pair> surviving = new ArrayList<>();
      for (Map.Entry<Pair, Integer> entry : overlapCounts.entrySet()) {
        Pair pair = entry.getKey();
        int overlapHexCount = entry.getValue();
        if (overlapHexCount < minOverlapHexes) {
          continue;
        }
        if (selectedRegionId != null
            && !pair.a().equals(selectedRegionId.value())
            && !pair.b().equals(selectedRegionId.value())) {
          continue;
        }
        Region a = regionOf(regionById, pair.a());
        Region b = regionOf(regionById, pair.b());
        if (tag != null && (!tag.equals(a.meta().tag()) || !tag.equals(b.meta().tag()))) {
          continue;
        }
        if (sameTagOnly && !sameTagMatches(a, b)) {
          continue;
        }
        if (wholeOnly && "partial".equals(relation(overlapHexCount, a, b))) {
          continue;
        }
        surviving.add(pair);
      }
      surviving.sort(
          Comparator.comparingInt((Pair pair) -> overlapCounts.get(pair))
              .reversed()
              .thenComparing(Pair::a)
              .thenComparing(Pair::b));

      Set<Pair> survivingSet = new HashSet<>(surviving);
      int overlappingHexCount = 0;
      for (List<String> ids : memberships.values()) {
        if (ids.size() < 2) {
          continue;
        }
        boolean touchesSurviving = false;
        for (int i = 0; i < ids.size() - 1 && !touchesSurviving; i++) {
          for (int j = i + 1; j < ids.size(); j++) {
            if (survivingSet.contains(new Pair(ids.get(i), ids.get(j)))) {
              touchesSurviving = true;
              break;
            }
          }
        }
        if (touchesSurviving) {
          overlappingHexCount++;
        }
      }

      int equalPairCount = 0;
      int containmentPairCount = 0;
      int partialPairCount = 0;
      int sameTagPairCount = 0;
      Set<String> involvedRegionIds = new HashSet<>();
      for (Pair pair : surviving) {
        Region a = regionOf(regionById, pair.a());
        Region b = regionOf(regionById, pair.b());
        String relation = relation(overlapCounts.get(pair), a, b);
        switch (relation) {
          case "equal" -> equalPairCount++;
          case "aContainsB", "bContainsA" -> containmentPairCount++;
          case "partial" -> partialPairCount++;
          default -> throw new IllegalStateException("未知 relation: " + relation);
        }
        if (sameTagMatches(a, b)) {
          sameTagPairCount++;
        }
        involvedRegionIds.add(pair.a());
        involvedRegionIds.add(pair.b());
      }

      int returnedPairCount = (int) Math.min(limit, surviving.size());
      boolean truncated = surviving.size() > returnedPairCount;
      List<Map<String, Object>> rows = new ArrayList<>(returnedPairCount);
      for (int i = 0; i < returnedPairCount; i++) {
        Pair pair = surviving.get(i);
        rows.add(
            overlapRow(
                pair,
                regionOf(regionById, pair.a()),
                regionOf(regionById, pair.b()),
                overlapCounts.get(pair),
                overlapHexes,
                includeHexes));
      }

      Map<String, Object> filters =
          filtersView(
              regionIdText, tag, sameTagOnly, minOverlapHexes, wholeOnly, includeHexes, limit);
      Map<String, Object> view = new LinkedHashMap<>();
      var ref = state.meta().ref();
      view.put("mapId", mapId);
      view.put("branch", ref.branch().value());
      view.put("revision", ref.revision().value());
      view.put("regionCount", map.regions().size());
      view.put("candidateRegionCount", candidates.size());
      view.put("pairCount", surviving.size());
      view.put("returnedPairCount", returnedPairCount);
      view.put("truncated", truncated);
      view.put("involvedRegionCount", involvedRegionIds.size());
      view.put("overlappingHexCount", overlappingHexCount);
      view.put("equalPairCount", equalPairCount);
      view.put("containmentPairCount", containmentPairCount);
      view.put("partialPairCount", partialPairCount);
      view.put("sameTagPairCount", sameTagPairCount);
      view.put("filters", filters);
      view.put("overlaps", rows);
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  private static Region regionOf(Map<String, Region> regionById, String id) {
    Region region = regionById.get(id);
    if (region == null) {
      throw new IllegalStateException("内部错误：成员表引用了候选外的区域 " + id);
    }
    return region;
  }

  /** relation 定义：{@code equal} / {@code aContainsB} / {@code bContainsA} / {@code partial}。 */
  private static String relation(int overlapHexCount, Region a, Region b) {
    int aSize = a.hexes().size();
    int bSize = b.hexes().size();
    if (overlapHexCount == aSize && overlapHexCount == bSize) {
      return "equal";
    }
    if (overlapHexCount == bSize && aSize > bSize) {
      return "aContainsB";
    }
    if (overlapHexCount == aSize && bSize > aSize) {
      return "bContainsA";
    }
    return "partial";
  }

  /** 两侧 tag 都非空且逐字相等（行上的 {@code sameTag} 与 {@code sameTagOnly} 共用这一处口径）。 */
  private static boolean sameTagMatches(Region a, Region b) {
    String aTag = a.meta().tag();
    String bTag = b.meta().tag();
    return aTag != null && !aTag.isBlank() && bTag != null && !bTag.isBlank() && aTag.equals(bTag);
  }

  private static Map<String, Object> overlapRow(
      Pair pair,
      Region a,
      Region b,
      int overlapHexCount,
      Map<Pair, List<HexCoord>> overlapHexes,
      boolean includeHexes) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("aRegionId", a.id().value());
    row.put("aName", a.name());
    row.put("aTag", a.meta().tag());
    row.put("aHexCount", a.hexes().size());
    row.put("bRegionId", b.id().value());
    row.put("bName", b.name());
    row.put("bTag", b.meta().tag());
    row.put("bHexCount", b.hexes().size());
    row.put("overlapHexCount", overlapHexCount);
    row.put("relation", relation(overlapHexCount, a, b));
    row.put("sameTag", sameTagMatches(a, b));
    if (includeHexes) {
      List<HexCoord> hexes = overlapHexes.getOrDefault(pair, List.of());
      boolean hexesTruncated = hexes.size() > MAX_HEXES_PER_PAIR;
      List<Map<String, Object>> hexViews = new ArrayList<>();
      for (int i = 0; i < Math.min(hexes.size(), MAX_HEXES_PER_PAIR); i++) {
        hexViews.add(ToolSupport.hexCoord(hexes.get(i)));
      }
      row.put("overlapHexes", hexViews);
      row.put("overlapHexesTruncated", hexesTruncated);
    }
    return row;
  }

  private static Map<String, Object> filtersView(
      String regionId,
      String tag,
      boolean sameTagOnly,
      long minOverlapHexes,
      boolean wholeOnly,
      boolean includeHexes,
      long limit) {
    Map<String, Object> filters = new LinkedHashMap<>();
    if (regionId != null) {
      filters.put("regionId", regionId);
    }
    if (tag != null) {
      filters.put("tag", tag);
    }
    filters.put("sameTagOnly", sameTagOnly);
    filters.put("minOverlapHexes", minOverlapHexes);
    filters.put("wholeOnly", wholeOnly);
    filters.put("includeHexes", includeHexes);
    filters.put("limit", limit);
    return filters;
  }

  /** 规范化区域对：构造后恒有 {@code a < b}（调用点按字典序造列表，本处置换只是把口径钉在类型上）。 */
  private record Pair(String a, String b) {

    private Pair {
      Objects.requireNonNull(a, "a");
      Objects.requireNonNull(b, "b");
      if (a.compareTo(b) > 0) {
        String swap = a;
        a = b;
        b = swap;
      }
    }
  }
}
