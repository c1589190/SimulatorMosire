package io.mosire.simos.app.gov;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 省份划分建议器（P3）：对**一个已存在的 Region**做只读的递归主轴二分，产出一组候选省界。
 *
 * <p>★★ <b>本类只出建议，零写入</b>：不创建 Region / Unit / DecisionMaker，不落 revision —— 实际创省由调用方 （GM
 * Agent）拿本结果自行用现有 GM 工具执行。{@link #divide} 是纯函数：输出只由 {@link GameMap} / {@link RegionId} / {@link
 * Params} 决定，同一输入逐字段可复现。
 *
 * <p>★ <b>确定性纪律</b>：不依赖任何 {@code Map}/{@code Set} 的迭代序 —— 每一处都先按 {@link HexCoord} 自然序 （先 q 后
 * r）或固定轴枚举序定序。h 集合进建议前一律排序；轴切分的候选按 {@code (投影值, q, r)} 全序； 候选切点按 {@code (两侧大小差, k)} 全序；BFS 回退用
 * {@link TreeSet} 取自然序最小格为种子。故建议 {@code hexes} 与 {@code suggestionHash} 都是输入的纯函数。
 *
 * <p>★ <b>算法</b>（与只读 Python 原型同口径）：
 *
 * <ol>
 *   <li>子集 {@code |S| <= max} ⇒ 直接成省（大小不在 {@code [min, max]} 内时带 warning）；
 *   <li>否则按固定轴序 {@code q → r → s=q+r → d=q-r} 找第一个「可切分轴」：把子集按 {@code (投影值, q, r)} 排序后枚举切点
 *       k（优先两侧大小差最小），第一个两侧都非空且各自六邻接连通的 k 即选中； 某轴枚举完仍无可切点 ⇒ 试下一轴；
 *   <li>若选中的切分产生 {@code < min/2} 的碎片（即 {@code 2*side < min}），整个子集**保持单省**并加 warning （不再递归、也不回退
 *       BFS）；
 *   <li>四个轴都切不了（极怪形状 / 不连通）⇒ 确定性 BFS 区域生长：每次取剩余格自然序最小者为种子，沿六邻接 在剩余集里长到容量 {@code max} 为新省，并加
 *       warning；
 *   <li>仍失败（防御性分支）⇒ 子集保持单省 + warning。
 * </ol>
 *
 * <p>★ <b>首都圈</b>：{@code capitalHex} 给出且 {@code HexGrid.withinRadius(capital, radius) ∩ Region}
 * 非空时， 首都圈单列一省（大小超出 {@code [min, max]} 也保留、带 warning），剩余格再走上面的递归；首都圈为空则忽略。
 *
 * <p>★ <b>中心</b>：普通省取该省全部 hex 的 cube 坐标均值最近的实际 hex（并列取自然序最小）；首都圈中心恒为 {@code capitalHex}。
 *
 * <p>★ <b>重叠</b>：{@code overlappingRegions} 列出除目标 Region 外所有与目标 hex 集相交的 Region（id/name/tag/
 * 相交格数），只作信息，**不影响建议** —— 区域重叠 ≠ 省籍，是否 override 由调用方显式决定。
 */
public final class ProvinceDivider {

  /** 本建议器使用的算法标识（输出与 suggestionHash 都钉这一个字面量）。 */
  public static final String ALGORITHM = "recursive-axis-bisection";

  /** 缺省每省 hex 下限。 */
  public static final int DEFAULT_MIN_HEX_PER_PROVINCE = 20;

  /** 缺省每省 hex 上限。 */
  public static final int DEFAULT_MAX_HEX_PER_PROVINCE = 50;

  /** 缺省首都圈半径（只在给出 capitalHex 时有意义）。 */
  public static final int DEFAULT_CAPITAL_DISTRICT_RADIUS = 1;

  private ProvinceDivider() {}

  /**
   * 划分参数（工具层解析后传入）。
   *
   * @param minHexPerProvince 每省 hex 下限，必须 ≥1
   * @param maxHexPerProvince 每省 hex 上限，必须 ≥ {@code minHexPerProvince}
   * @param capitalHex 首都格；{@code null} = 不单列首都圈
   * @param capitalDistrictRadius 首都圈半径，必须 ≥0（{@code capitalHex == null} 时不参与计算）
   * @param namingPrefix 建议省名的前缀（缺省由调用方取目标 Region 名）
   */
  public record Params(
      int minHexPerProvince,
      int maxHexPerProvince,
      HexCoord capitalHex,
      int capitalDistrictRadius,
      String namingPrefix) {

    public Params {
      if (minHexPerProvince < 1) {
        throw new IllegalArgumentException("minHexPerProvince 必须 ≥1: " + minHexPerProvince);
      }
      if (maxHexPerProvince < minHexPerProvince) {
        throw new IllegalArgumentException(
            "maxHexPerProvince 必须 ≥ minHexPerProvince: max="
                + maxHexPerProvince
                + ", min="
                + minHexPerProvince);
      }
      if (capitalDistrictRadius < 0) {
        throw new IllegalArgumentException("capitalDistrictRadius 不能为负: " + capitalDistrictRadius);
      }
      if (namingPrefix == null || namingPrefix.isBlank()) {
        throw new IllegalArgumentException("namingPrefix 不得为空白");
      }
    }

    /** 缺省参数（min=20 / max=50 / 无首都圈 / 半径 1）。 */
    public static Params defaults(String namingPrefix) {
      return new Params(
          DEFAULT_MIN_HEX_PER_PROVINCE,
          DEFAULT_MAX_HEX_PER_PROVINCE,
          null,
          DEFAULT_CAPITAL_DISTRICT_RADIUS,
          namingPrefix);
    }
  }

  /**
   * 一条建议省。
   *
   * @param suggestedRegionId 建议 id：{@code sanitize(目标 regionId) + "__P" + 两位序号（从 01 起）}
   * @param name 建议省名：{@code namingPrefix + "·" + 两位序号}；首都圈为 {@code namingPrefix + "·首都"}
   * @param center 省中心（首都圈 = capitalHex；其余 = cube 均值最近的实际 hex，并列取自然序最小）
   * @param hexes 成员格，**自然序**（先 q 后 r）
   * @param contiguous 省 hex 集在六邻接下是否全可达
   * @param warnings 该省的具名问题（大小带外 / 碎片 / BFS 回退 / 不连通）
   */
  public record Province(
      String suggestedRegionId,
      String name,
      HexCoord center,
      List<HexCoord> hexes,
      boolean contiguous,
      List<String> warnings) {

    public Province {
      Objects.requireNonNull(suggestedRegionId, "suggestedRegionId");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(center, "center");
      Objects.requireNonNull(hexes, "hexes");
      Objects.requireNonNull(warnings, "warnings");
      hexes = naturalSorted(hexes);
      warnings = List.copyOf(warnings);
    }

    /** 成员格数（派生值，不单独存）。 */
    public int hexCount() {
      return hexes.size();
    }
  }

  /**
   * 首都圈（若存在）。
   *
   * @param center 恒为目标 Region 的 capitalHex
   * @param hexes 成员格，自然序
   */
  public record CapitalDistrict(HexCoord center, List<HexCoord> hexes) {

    public CapitalDistrict {
      Objects.requireNonNull(center, "center");
      Objects.requireNonNull(hexes, "hexes");
      hexes = naturalSorted(hexes);
    }

    /** 成员格数（派生值，不单独存）。 */
    public int hexCount() {
      return hexes.size();
    }
  }

  /**
   * 与目标 Region 相交的其它 Region（只列信息）。
   *
   * @param tag 该 Region 的 {@code RegionMeta.tag()}，可为 null
   * @param overlapHexCount 与目标 Region hex 集的相交格数，恒 ≥1
   */
  public record Overlap(String regionId, String name, String tag, int overlapHexCount) {

    public Overlap {
      Objects.requireNonNull(regionId, "regionId");
      Objects.requireNonNull(name, "name");
      if (overlapHexCount < 1) {
        throw new IllegalArgumentException("overlapHexCount 必须 ≥1: " + overlapHexCount);
      }
    }
  }

  /**
   * 一次划分的完整建议（JSON 形状由工具壳渲染）。
   *
   * @param capitalDistrict {@code null} = 无首都圈（未给 capitalHex 或半径内 ∩ Region 为空）
   * @param suggestionHash 建议内容的 SHA-256 十六进制（不含本字段自身；纯函数钉子）
   */
  public record Suggestion(
      String regionId,
      String regionName,
      int hexCount,
      int minHexPerProvince,
      int maxHexPerProvince,
      List<Province> provinces,
      CapitalDistrict capitalDistrict,
      List<Overlap> overlappingRegions,
      List<String> warnings,
      String suggestionHash) {

    public Suggestion {
      Objects.requireNonNull(regionId, "regionId");
      Objects.requireNonNull(regionName, "regionName");
      Objects.requireNonNull(provinces, "provinces");
      Objects.requireNonNull(overlappingRegions, "overlappingRegions");
      Objects.requireNonNull(warnings, "warnings");
      Objects.requireNonNull(suggestionHash, "suggestionHash");
      provinces = List.copyOf(provinces);
      overlappingRegions = List.copyOf(overlappingRegions);
      warnings = List.copyOf(warnings);
    }

    /** 建议省数（派生值，不单独存）。 */
    public int suggestedProvinceCount() {
      return provinces.size();
    }
  }

  /**
   * 对 {@code map.regions()[regionId]} 出建议省界。
   *
   * @throws IllegalArgumentException 区域不在 {@code map.regions()} 内，或 capitalHex 不在目标 Region 内
   */
  public static Suggestion divide(GameMap map, RegionId regionId, Params params) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(regionId, "regionId");
    Objects.requireNonNull(params, "params");
    Region region = map.regions().get(regionId);
    if (region == null) {
      throw new IllegalArgumentException("区域不在当前 GameMap.regions() 内: " + regionId);
    }
    if (params.capitalHex() != null && !region.contains(params.capitalHex())) {
      throw new IllegalArgumentException("capitalHex 不在目标 Region 的 hex 集内: " + params.capitalHex());
    }

    List<Overlap> overlaps = overlappingRegions(map, regionId, region.hexes());
    List<HexCoord> target = naturalSorted(region.hexes());
    if (target.isEmpty()) {
      List<String> warnings = List.of("目标 Region 没有任何 hex，未生成任何建议省");
      return new Suggestion(
          region.id().value(),
          region.name(),
          0,
          params.minHexPerProvince(),
          params.maxHexPerProvince(),
          List.of(),
          null,
          overlaps,
          warnings,
          suggestionHash(region, params, List.of(), null, overlaps, warnings));
    }

    Stats stats = new Stats();
    Draft capitalDraft = capitalDistrictDraft(region, params);
    List<HexCoord> remaining = new ArrayList<>(target);
    if (capitalDraft != null) {
      // 首都圈单列一个区（不进 provinces 列表）：从剩余格里剔除，剩余格保持自然序。
      Set<HexCoord> district = new HashSet<>(capitalDraft.hexes());
      remaining.removeIf(district::contains);
      if (capitalDraft.hexes().size() < params.minHexPerProvince()
          || capitalDraft.hexes().size() > params.maxHexPerProvince()) {
        stats.capitalOutOfBand = true;
      }
    }

    List<Draft> drafts = new ArrayList<>(divideRecursive(remaining, stats, params));
    List<Province> provinces = new ArrayList<>(drafts.size());
    int number = 0;
    for (Draft draft : drafts) {
      number++;
      String suffix = String.format(Locale.ROOT, "%02d", number);
      String name = params.namingPrefix() + "·" + suffix;
      HexCoord center = centroid(draft.hexes());
      List<String> warnings = new ArrayList<>(draft.warnings());
      int reached = connectedReached(draft.hexes());
      boolean contiguous = reached == draft.hexes().size();
      if (!contiguous) {
        warnings.add("省 hex 集不连通：有 " + (draft.hexes().size() - reached) + " 格无法从自然序首格到达");
      }
      if (draft.hexes().size() < params.minHexPerProvince()
          || draft.hexes().size() > params.maxHexPerProvince()) {
        stats.bandViolations++;
      }
      if (!contiguous) {
        stats.nonContiguous++;
      }
      provinces.add(
          new Province(
              sanitize(region.id().value()) + "__P" + suffix,
              name,
              center,
              draft.hexes(),
              contiguous,
              warnings));
    }

    CapitalDistrict district =
        capitalDraft == null
            ? null
            : new CapitalDistrict(params.capitalHex(), capitalDraft.hexes());

    List<String> warnings = new ArrayList<>();
    if (stats.capitalOutOfBand) {
      warnings.add(
          "首都圈大小 "
              + capitalDraft.hexes().size()
              + " 不在 ["
              + params.minHexPerProvince()
              + ","
              + params.maxHexPerProvince()
              + "] 内，仍单列一省");
    }
    if (stats.fallbackSubsets > 0) {
      warnings.add(stats.fallbackSubsets + " 个子集无法主轴二分，已改用确定性 BFS 区域生长回退切分");
    }
    if (stats.bandViolations > 0) {
      warnings.add(
          stats.bandViolations
              + " 个建议省的大小不在 ["
              + params.minHexPerProvince()
              + ","
              + params.maxHexPerProvince()
              + "] 内（见各省 warnings）");
    }
    if (stats.nonContiguous > 0) {
      warnings.add(stats.nonContiguous + " 个建议省 hex 集不连通");
    }

    return new Suggestion(
        region.id().value(),
        region.name(),
        region.hexes().size(),
        params.minHexPerProvince(),
        params.maxHexPerProvince(),
        provinces,
        district,
        overlaps,
        List.copyOf(warnings),
        suggestionHash(region, params, provinces, district, overlaps, warnings));
  }

  // ── 递归主轴二分 ──────────────────────────────────────────────────────────────────────────

  /** 递归切分：返回的每个 Draft 就是一条建议省（顺序 = 左半、右半）。 */
  private static List<Draft> divideRecursive(List<HexCoord> cells, Stats stats, Params params) {
    if (cells.isEmpty()) {
      return List.of();
    }
    if (cells.size() <= params.maxHexPerProvince()) {
      List<String> warnings = new ArrayList<>();
      addBandWarning(warnings, cells.size(), params, false);
      return List.of(new Draft(cells, warnings, false));
    }

    CutResult result = chooseCut(cells, params);
    if (result.cut() != null) {
      List<Draft> out = new ArrayList<>();
      out.addAll(divideRecursive(result.cut().left(), stats, params));
      out.addAll(divideRecursive(result.cut().right(), stats, params));
      return out;
    }
    if (result.fragment()) {
      // 规格：切分会产生 < min/2 的碎片 ⇒ 保持单省 + warning（不再回退 BFS）。
      List<String> warnings = new ArrayList<>();
      warnings.add(
          "主轴切分会得到小于 minHexPerProvince/2（" + params.minHexPerProvince() + "/2）的碎片，该子集保持为单省");
      addBandWarning(warnings, cells.size(), params, false);
      return List.of(new Draft(cells, warnings, false));
    }

    List<Draft> fallback = bfsFallback(cells, params);
    if (fallback.isEmpty()) {
      // 防御性分支：cells 非空时 bfsFallback 至少产出一条；真到这说明实现被改坏，不静默成空省。
      List<String> warnings = new ArrayList<>();
      warnings.add("子集无法主轴二分且 BFS 回退未产出任何省，保持为单省");
      addBandWarning(warnings, cells.size(), params, false);
      return List.of(new Draft(cells, warnings, false));
    }
    stats.fallbackSubsets++;
    return fallback;
  }

  /** 选轴与切点：固定轴序下第一个「两侧非空且各自连通」的切点；碎片切分立即返回 fragment。 */
  private static CutResult chooseCut(List<HexCoord> cells, Params params) {
    for (Axis axis : Axis.values()) {
      List<HexCoord> ordered = new ArrayList<>(cells);
      ordered.sort(axis.order());
      int n = ordered.size();
      List<Integer> cuts = new ArrayList<>(n - 1);
      for (int k = 1; k < n; k++) {
        cuts.add(k);
      }
      cuts.sort(
          Comparator.<Integer>comparingInt(k -> Math.abs(2 * k - n)).thenComparingInt(k -> k));
      for (int k : cuts) {
        List<HexCoord> left = naturalSorted(ordered.subList(0, k));
        List<HexCoord> right = naturalSorted(ordered.subList(k, n));
        if (!isContiguous(left) || !isContiguous(right)) {
          continue;
        }
        if (isFragment(left, params) || isFragment(right, params)) {
          return new CutResult(null, true);
        }
        return new CutResult(new Cut(left, right), false);
      }
    }
    return new CutResult(null, false);
  }

  /** 确定性 BFS 区域生长回退：每次取剩余格自然序最小者为种子，沿六邻接长到容量 max。 */
  private static List<Draft> bfsFallback(List<HexCoord> cells, Params params) {
    TreeSet<HexCoord> remaining = new TreeSet<>(cells);
    List<Draft> out = new ArrayList<>();
    while (!remaining.isEmpty()) {
      TreeSet<HexCoord> frontier = new TreeSet<>();
      frontier.add(remaining.first());
      List<HexCoord> chunk = new ArrayList<>();
      while (chunk.size() < params.maxHexPerProvince() && !frontier.isEmpty()) {
        HexCoord current = frontier.pollFirst();
        if (!remaining.remove(current)) {
          continue;
        }
        chunk.add(current);
        for (HexCoord neighbor : current.neighbors()) {
          if (remaining.contains(neighbor)) {
            frontier.add(neighbor);
          }
        }
      }
      chunk.sort(null);
      List<String> warnings = new ArrayList<>();
      warnings.add("子集无法由主轴二分切分，该省由确定性 BFS 区域生长回退得到");
      addBandWarning(warnings, chunk.size(), params, false);
      out.add(new Draft(chunk, warnings, false));
    }
    return out;
  }

  /** {@code 2*side < min} 即 {@code side < min/2}（整数语义，long 比较、不引入浮点也不溢出）。 */
  private static boolean isFragment(List<HexCoord> side, Params params) {
    return (long) side.size() * 2 < params.minHexPerProvince();
  }

  /** 首都圈草案：未给 capitalHex 或半径内 ∩ Region 为空 ⇒ null（忽略首都圈）。 */
  private static Draft capitalDistrictDraft(Region region, Params params) {
    if (params.capitalHex() == null) {
      return null;
    }
    // ★ 先把半径夹到"目标 Region 内最远格的距离"：对 Region ∩ 球而言语义逐格不变（dist ≤ radius 的判定不变），
    //   却避免模型给一个天文数字半径时 withinRadius 去枚举一个天文数字的球。
    int maxDistance = 0;
    for (HexCoord hex : region.hexes()) {
      maxDistance = Math.max(maxDistance, params.capitalHex().distanceTo(hex));
    }
    int radius = Math.min(params.capitalDistrictRadius(), maxDistance);
    Set<HexCoord> ring = HexGrid.withinRadius(params.capitalHex(), radius);
    List<HexCoord> district = new ArrayList<>();
    for (HexCoord hex : naturalSorted(region.hexes())) {
      if (ring.contains(hex)) {
        district.add(hex);
      }
    }
    if (district.isEmpty()) {
      return null;
    }
    List<String> warnings = new ArrayList<>();
    addBandWarning(warnings, district.size(), params, true);
    return new Draft(district, warnings, true);
  }

  /** 大小带 warning（仅当越界时加；不静默违反 min/max）。 */
  private static void addBandWarning(
      List<String> warnings, int size, Params params, boolean capital) {
    String who = capital ? "首都圈" : "省";
    if (size < params.minHexPerProvince()) {
      warnings.add(who + "大小 " + size + " 小于 minHexPerProvince=" + params.minHexPerProvince());
    } else if (size > params.maxHexPerProvince()) {
      warnings.add(who + "大小 " + size + " 大于 maxHexPerProvince=" + params.maxHexPerProvince());
    }
  }

  // ── 几何与派生量 ──────────────────────────────────────────────────────────────────────────

  /** 自然序（先 q 后 r）的不可变副本。 */
  private static List<HexCoord> naturalSorted(Collection<HexCoord> cells) {
    List<HexCoord> sorted = new ArrayList<>(cells);
    Collections.sort(sorted);
    return List.copyOf(sorted);
  }

  /** 六邻接连通性：从自然序首格 BFS，可达格数 == 集合大小。 */
  private static boolean isContiguous(List<HexCoord> cells) {
    return connectedReached(cells) == cells.size();
  }

  /** 六邻接连通的可达格数（不连通时用于 warning 里报差多少格）。 */
  private static int connectedReached(List<HexCoord> cells) {
    if (cells.isEmpty()) {
      return 0;
    }
    Set<HexCoord> membership = new HashSet<>(cells);
    Set<HexCoord> seen = new HashSet<>();
    Deque<HexCoord> stack = new ArrayDeque<>();
    HexCoord start = Collections.min(cells);
    stack.push(start);
    seen.add(start);
    int reached = 0;
    while (!stack.isEmpty()) {
      HexCoord current = stack.pop();
      reached++;
      for (HexCoord neighbor : current.neighbors()) {
        if (membership.contains(neighbor) && seen.add(neighbor)) {
          stack.push(neighbor);
        }
      }
    }
    return reached;
  }

  /**
   * 省中心：cube 坐标均值最近的实际 hex；并列取自然序最小。
   *
   * <p>★ 用 {@code long} 交叉相乘做精确比较（{@code |h.q*n - Σq|, …} 的最大值），不引入浮点，避免并列判定漂移。
   */
  private static HexCoord centroid(List<HexCoord> cells) {
    int n = cells.size();
    long sumQ = 0L;
    long sumR = 0L;
    long sumS = 0L;
    for (HexCoord hex : cells) {
      sumQ += hex.q();
      sumR += hex.r();
      sumS += hex.s();
    }
    HexCoord best = cells.get(0);
    long bestDistance = Long.MAX_VALUE;
    for (HexCoord hex : cells) {
      long dq = Math.abs((long) hex.q() * n - sumQ);
      long dr = Math.abs((long) hex.r() * n - sumR);
      long ds = Math.abs((long) hex.s() * n - sumS);
      long distance = Math.max(dq, Math.max(dr, ds));
      if (distance < bestDistance) {
        bestDistance = distance;
        best = hex;
      }
    }
    return best;
  }

  /** 除目标 Region 自身外，所有与目标 hex 集相交的 Region（按 regionId 字典序，确定性）。 */
  private static List<Overlap> overlappingRegions(
      GameMap map, RegionId targetId, Set<HexCoord> targetHexes) {
    List<Overlap> overlaps = new ArrayList<>();
    for (Region other : map.regions().values()) {
      if (other.id().equals(targetId)) {
        continue;
      }
      int count = 0;
      for (HexCoord hex : other.hexes()) {
        if (targetHexes.contains(hex)) {
          count++;
        }
      }
      if (count > 0) {
        overlaps.add(new Overlap(other.id().value(), other.name(), other.meta().tag(), count));
      }
    }
    overlaps.sort(Comparator.comparing(Overlap::regionId));
    return List.copyOf(overlaps);
  }

  /**
   * 建议 id 前缀的 sanitize：字母/数字（含 CJK）/下划线/连字符/点保留，其余字符逐个替换为下划线。
   *
   * <p>目标 regionId 非空白由 {@link RegionId} 保证，故结果恒非空。
   */
  public static String sanitize(String regionId) {
    Objects.requireNonNull(regionId, "regionId");
    StringBuilder out = new StringBuilder(regionId.length());
    for (int i = 0; i < regionId.length(); ) {
      int codePoint = regionId.codePointAt(i);
      if (Character.isLetterOrDigit(codePoint)
          || codePoint == '_'
          || codePoint == '-'
          || codePoint == '.') {
        out.appendCodePoint(codePoint);
      } else {
        out.append('_');
      }
      i += Character.charCount(codePoint);
    }
    return out.isEmpty() ? "region" : out.toString();
  }

  // ── suggestionHash ────────────────────────────────────────────────────────────────────────

  /** 建议内容的规范化文本的 SHA-256（16 进制小写）。 */
  private static String suggestionHash(
      Region region,
      Params params,
      List<Province> provinces,
      CapitalDistrict district,
      List<Overlap> overlaps,
      List<String> warnings) {
    StringBuilder canonical = new StringBuilder(4096);
    text(canonical, "algorithm", ALGORITHM);
    text(canonical, "regionId", region.id().value());
    text(canonical, "regionName", region.name());
    number(canonical, "hexCount", region.hexes().size());
    number(canonical, "min", params.minHexPerProvince());
    number(canonical, "max", params.maxHexPerProvince());
    for (Province province : provinces) {
      canonical.append("province{");
      text(canonical, "id", province.suggestedRegionId());
      text(canonical, "name", province.name());
      number(canonical, "centerQ", province.center().q());
      number(canonical, "centerR", province.center().r());
      number(canonical, "hexCount", province.hexes().size());
      for (HexCoord hex : province.hexes()) {
        number(canonical, "hq", hex.q());
        number(canonical, "hr", hex.r());
      }
      text(canonical, "contiguous", Boolean.toString(province.contiguous()));
      for (String warning : province.warnings()) {
        text(canonical, "warning", warning);
      }
      canonical.append('}');
    }
    if (district != null) {
      canonical.append("capitalDistrict{");
      number(canonical, "centerQ", district.center().q());
      number(canonical, "centerR", district.center().r());
      number(canonical, "hexCount", district.hexes().size());
      for (HexCoord hex : district.hexes()) {
        number(canonical, "hq", hex.q());
        number(canonical, "hr", hex.r());
      }
      canonical.append('}');
    }
    for (Overlap overlap : overlaps) {
      canonical.append("overlap{");
      text(canonical, "regionId", overlap.regionId());
      text(canonical, "name", overlap.name());
      text(canonical, "tag", overlap.tag());
      number(canonical, "overlapHexCount", overlap.overlapHexCount());
      canonical.append('}');
    }
    for (String warning : warnings) {
      text(canonical, "warning", warning);
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // 标准 JVM 必然带 SHA-256；缺失说明运行环境坏了，响亮抛。
      throw new IllegalStateException("JVM 缺少 SHA-256", e);
    }
  }

  /** 长度前缀式文本字段（{@code key=len:value;}）：值里有任何分隔符都不会歧义，且与迭代序无关。 */
  private static void text(StringBuilder canonical, String key, String value) {
    canonical.append(key).append('=');
    if (value == null) {
      canonical.append("null");
    } else {
      canonical.append(value.length()).append(':').append(value);
    }
    canonical.append(';');
  }

  private static void number(StringBuilder canonical, String key, long value) {
    canonical.append(key).append('=').append(value).append(';');
  }

  // ── 私有小件 ─────────────────────────────────────────────────────────────────────────────

  /** 四个固定轴（枚举序即规格的 q → r → s → d）。 */
  private enum Axis {
    Q {
      @Override
      int project(HexCoord hex) {
        return hex.q();
      }
    },
    R {
      @Override
      int project(HexCoord hex) {
        return hex.r();
      }
    },
    S {
      @Override
      int project(HexCoord hex) {
        return hex.s();
      }
    },
    D {
      @Override
      int project(HexCoord hex) {
        return hex.q() - hex.r();
      }
    };

    abstract int project(HexCoord hex);

    /** 全序：{@code (投影值, q, r)}（q/r 是同一子集内的唯一身份，故是严格全序）。 */
    Comparator<HexCoord> order() {
      return Comparator.comparingInt(this::project)
          .thenComparingInt(HexCoord::q)
          .thenComparingInt(HexCoord::r);
    }
  }

  private record Cut(List<HexCoord> left, List<HexCoord> right) {}

  private record CutResult(Cut cut, boolean fragment) {}

  /** 递归期的中间省草案；{@code capital} 标记首都圈（中心与命名规则不同）。 */
  private record Draft(List<HexCoord> hexes, List<String> warnings, boolean capital) {

    private Draft {
      hexes = naturalSorted(hexes);
      warnings = List.copyOf(warnings);
    }
  }

  /** 顶层诊断计数（只影响 warnings，不影响几何）。 */
  private static final class Stats {
    private int fallbackSubsets;
    private int bandViolations;
    private int nonContiguous;
    private boolean capitalOutOfBand;
  }
}
