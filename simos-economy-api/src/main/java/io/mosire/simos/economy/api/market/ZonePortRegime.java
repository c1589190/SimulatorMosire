package io.mosire.simos.economy.api.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ★★ <b>一个市场区对"某一类"（某商品 / 某币种）在<b>某一方向</b>上的实际执行规律读数（2026-10-09 口岸设计书 §4.3； 2026-10-10 §12
 * 加方向维；纯值契约，无公式）</b>。
 *
 * <pre>
 * 【规则】允许 ⇔ ∃k: s_k = 0（任一处不设限 ⇒ 放行）              ← OR，不是 min
 * 【总效率】E_Z(c, d) = Σ(w_k × openness_k) ÷ Σ(w_k)             ← 按能力（暴露边条数）加权平均
 * 无接触面（Σw = 0）⇒ E = 1000 且 {@link #noContactSurface()} = true（"没有邻居 ⇒ 没有可管的口岸"）
 * d = {@link PortDirection#ENTRY}（入区，用入口规则）/ {@link PortDirection#EXIT}（出区，用出口规则）
 * </pre>
 *
 * <p>★★ <b>为什么一个区一类会有两份读数</b>：用户 2026-10-10 原话「出入都设规则拦，分别按口岸效率算…… 都需要两边都过才能跨区」⇒ <b>入口侧与出口侧各有自己的 s 与
 * E</b>（§12.2），跨区过境 = 两个方向相乘 （{@code E_源(EXIT) × E_目的(ENTRY) ÷ 1e6}，见 {@link PortDirection}）。★
 * 本类型<b>只装一侧</b>， 两侧的相乘是 {@code PortThrottle} 的事。
 *
 * <p>★★ <b>为什么是加权平均而不是 min/max</b>：用户 2026-10-09 原话「额不是取min，实际上，商品可能从多路涌来，所以还是得算一个总效率」 ——
 * 货从多路同时进来，总效果 = 各路能力按边界长度加权。
 *
 * <p>★★ <b>本类型只装读数，不算数</b>（economy-api 是契约层，"无公式"）：公式的唯一拼写点在 {@code
 * io.mosire.simos.economy.time.PortRegimeAggregation}。
 *
 * @param zoneId 市场区身份裸值（非空白）
 * @param classKey 类身份裸值（商品 id 或币种 id；非空白）
 * @param direction ★ <b>本读数属于哪一侧</b>（{@link PortDirection}）：入口规则/出口规则各算一份（§12 的方向维）， <b>两条方向各有独立的
 *     OR 规则与 E</b>——不是"一个区一个效率"
 * @param allowedByRule 设计书 §4.3 的 <b>OR 规则</b>（本方向）：任一处 {@code s = 0} ⇒ {@code true}；无接触面（{@code Σw
 *     = 0}）⇒ {@code true}（没有口岸 ⇒ 无从设限 ⇒ 放行）
 * @param efficiencyPerMille 市场区总效率 {@code E_Z(c, 方向)}（‰；加权平均；无接触面 ⇒ 1000）
 * @param totalExposedEdges {@code Σw_k}（暴露边条数合计；{@code 0} = 无接触面）
 * @param noContactSurface {@code Σw = 0} 的<b>具名标记</b>（"没有邻居 ⇒ 没有可管的口岸"，必须显式记录、不静默当 1000）
 * @param surfaces 逐接触面读数（保序不可变；{@code Σ} 与 {@link #totalExposedEdges} 一致）
 */
public record ZonePortRegime(
    String zoneId,
    String classKey,
    PortDirection direction,
    boolean allowedByRule,
    long efficiencyPerMille,
    long totalExposedEdges,
    boolean noContactSurface,
    List<SurfaceReading> surfaces) {

  public ZonePortRegime {
    if (zoneId == null || zoneId.isBlank()) {
      throw new IllegalArgumentException("ZonePortRegime.zoneId 不得为空白");
    }
    if (classKey == null || classKey.isBlank()) {
      throw new IllegalArgumentException("ZonePortRegime.classKey 不得为空白");
    }
    if (direction == null) {
      throw new IllegalArgumentException("ZonePortRegime.direction 不得为 null（入口/出口各算一份读数，不给合并读数）");
    }
    requireNonNegative(efficiencyPerMille, "efficiencyPerMille");
    requireNonNegative(totalExposedEdges, "totalExposedEdges");
    if (surfaces == null) {
      throw new IllegalArgumentException("ZonePortRegime.surfaces 不得为 null（没有接触面给 List.of()）");
    }
    List<SurfaceReading> copy = new ArrayList<>(surfaces.size());
    long sum = 0L;
    for (SurfaceReading reading : surfaces) {
      if (reading == null) {
        throw new IllegalArgumentException("ZonePortRegime.surfaces 不得含 null");
      }
      sum = Math.addExact(sum, reading.exposedEdgeCount());
      copy.add(reading);
    }
    if (sum != totalExposedEdges) {
      throw new IllegalArgumentException(
          "ZonePortRegime.totalExposedEdges 必须等于逐接触面权重之和: " + totalExposedEdges + " != " + sum);
    }
    if (noContactSurface != (totalExposedEdges == 0L)) {
      throw new IllegalArgumentException(
          "ZonePortRegime.noContactSurface 必须与 totalExposedEdges == 0 一致（不静默）: "
              + noContactSurface
              + " / "
              + totalExposedEdges);
    }
    surfaces = Collections.unmodifiableList(copy); // ★ 保序冻结（不用 List.copyOf）
  }

  /** 该方向的成交是否被规则允许（设计书 §4.3 的 OR 规则）。 */
  public boolean allowed() {
    return allowedByRule;
  }

  /**
   * ★ <b>管制力度（‰）= 1000 − E</b>（本方向）：{@code E} 是"多路上实际能走多少"的加权平均。
   *
   * <p>★ 与 {@link #allowedByRule} 是<b>两个独立读数</b>：规则是硬闸门（OR），E 是"多路上实际能走多少"的加权平均 —— 极端情形（所有接触面
   * {@code s > 0} 但 {@code e = 0}）下规则判"不许"而 E = 1000（管不住）；两个读数都如实给出， 由调用方决定口径，不在这里偷偷把两者对齐（账本
   * §关键判断记了这个拐角）。
   */
  public long enforcementPerMille() {
    return 1000L - efficiencyPerMille;
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException("ZonePortRegime." + field + " 必须 ≥ 0: " + value);
    }
  }

  /**
   * 单个接触面的逐值读数（{@code w/s/e + 合成出的 enforcement/openness}）——供读数与探针逐段核对， <b>公式的唯一拼写点仍是 {@code
   * PortRegimeAggregation}</b>。
   *
   * @param contactKey 接触面标识（管辖政府的身份裸值 / 三不管 key）
   * @param exposedEdgeCount 暴露边条数 {@code w_k}
   * @param restrictionPerMille 限制强度 {@code s_k}（‰）
   * @param portEfficiencyPerMille 口岸效率 {@code e_k}（‰）
   * @param enforcementPerMille 实际管制力 {@code ⌊s×e÷1000⌋}（‰，不封顶）
   * @param opennessPerMille 开放度 {@code 1000 − enforcement} 钳到 {@code [0,1000]}（‰）
   */
  public record SurfaceReading(
      String contactKey,
      long exposedEdgeCount,
      long restrictionPerMille,
      long portEfficiencyPerMille,
      long enforcementPerMille,
      long opennessPerMille) {

    public SurfaceReading {
      if (contactKey == null || contactKey.isBlank()) {
        throw new IllegalArgumentException("SurfaceReading.contactKey 不得为空白");
      }
      requireNonNegative(exposedEdgeCount, "exposedEdgeCount");
      requireNonNegative(restrictionPerMille, "restrictionPerMille");
      requireNonNegative(portEfficiencyPerMille, "portEfficiencyPerMille");
      requireNonNegative(enforcementPerMille, "enforcementPerMille");
      if (opennessPerMille < 0L || opennessPerMille > 1000L) {
        throw new IllegalArgumentException(
            "SurfaceReading.opennessPerMille 必须落在 [0,1000]（开放度是比例量）: " + opennessPerMille);
      }
      Objects.requireNonNull(contactKey, "contactKey");
    }
  }
}
