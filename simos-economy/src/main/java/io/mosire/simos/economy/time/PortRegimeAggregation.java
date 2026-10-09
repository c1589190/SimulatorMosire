package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.PortContactSurface;
import io.mosire.simos.economy.api.market.PortDirection;
import io.mosire.simos.economy.api.market.ZonePortRegime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ★★ <b>市场区口岸执行规律（2026-10-09 口岸设计书 §4.3；2026-10-10 §12 加方向维）：总效率（加权平均）+ 规则 OR</b> ——
 * 全仓<b>唯一拼写点</b>（契约是 {@code economy-api} 的 {@link ZonePortRegime} / {@link
 * PortContactSurface}，本类只放公式）。
 *
 * <pre>
 * 逐接触面 k：enforcement_k = ⌊s_k × e_k ÷ 1000⌋            （‰；不封顶）
 *             openness_k    = clamp(1000 − enforcement_k, 0, 1000)   （‰）
 * 市场区 Z 对类 c 的<b>某一方向 d</b>（{@link PortDirection}；s_k 取该方向的入口/出口限制，§12.2）：
 *   【规则】允许 ⇔ ∃k: s_k = 0            ← OR，不是 min
 *   【总效率】E_Z(c, d) = ⌊Σ(w_k × openness_k) ÷ Σ(w_k)⌋   ← 按能力（暴露边条数）加权平均
 *   Σ(w_k) = 0（无接触面）⇒ E = 1000 且 noContactSurface = true（"没有邻居 ⇒ 没有可管的口岸"）
 * </pre>
 *
 * <p>★★ <b>跨区过境的"两道闸"不在这里</b>：本类只算<b>一侧</b>的 E；一票货要过境必须 {@code E_源(EXIT) × E_目的(ENTRY) ÷ 1e6}
 * 两道闸依次都过 ⇒ 唯一拼写点是 {@link PortThrottle}（§12.2/§12.3）。
 *
 * <p>★★ <b>两个刻意的判断（都写在账本"关键判断"里）</b>：
 *
 * <ol>
 *   <li><b>openness 钳到 [0,1000]</b>：{@code e} 是<b>不封顶</b>的（用户 2026-10-23「都不封顶」；超编开方与供给静态修正都可能让口岸效率
 *       &gt; 1000‰），于是 {@code s×e÷1000} 可能超过 1000 ⇒ 若不钳，openness 会变成负数（"比全闭还闭"没有意义）。钳的是<b>开放度</b>这个
 *       比例量的定义域，<b>不是</b>给效率封顶 —— {@code s=0} 时 opennes 恒 1000，与钳制无关 ⇒ I-P8 不受影响。
 *   <li><b>Σw = 0 ⇒ E = 1000 且"允许"</b>：没有接触面 = 没有邻居 = 没有可管的口岸。设计书明写"取 1000（全开）并<b>具名记录</b>"； 此时 OR
 *       规则在空集上按"无所谓可管"读作放行（与"没有口岸 ⇒ 无从设限"一致，不是"∃ 在空集上恒假"的字面陷阱）。
 * </ol>
 *
 * <p>★ <b>为什么不在这里做 min/max</b>：用户 2026-10-09「额不是取min，实际上，商品可能从多路涌来，所以还是得算一个总效率」—— 9 条路里 1 条放开 + 8
 * 条全禁 ⇒ {@code E = 1000/9 ≈ 111‰}（不是 0、也不是 1000）；每条半开 ⇒ {@code E = 500‰}。
 *
 * <p>★ <b>纯函数 + 确定性</b>：不写状态、不用随机数、不读时钟；同一输入逐值相同（{@link PortContactSurface} 列表顺序即输出顺序）。
 *
 * <p>★★ <b>"走私"那一档已判死并已删净（设计书 §11：没管住就是流入，按正常供给算；P-T1c 落删）</b>：不记走私量、不算走私成本、不开走私账，
 * 成本楔子<b>不进任何算式</b>。本类现在只剩"总效率（加权平均）+ 规则 OR"这一件事；节流走 {@link PortThrottle}。
 */
public final class PortRegimeAggregation {

  /** 千分制：{@code 1000‰ = 1.0}（与 {@code GovRules.PER_MILLE} 同值；本模块不依赖 gov，故就地声明）。 */
  public static final long PER_MILLE = 1000L;

  private PortRegimeAggregation() {}

  /**
   * ★★ <b>算一个市场区对一类在<b>某一方向</b>上的执行规律</b>（唯一算式）。
   *
   * @param zoneId 市场区身份裸值；不得为空白
   * @param classKey 类身份裸值（商品或币种）；不得为空白
   * @param direction 方向（{@link PortDirection#ENTRY}：本区是目的地，用入口规则；{@link PortDirection#EXIT}：本区是
   *     来源地，用出口规则）；不得为 null
   * @param surfaces 该区对这一类的接触面（每个管辖政府一段；三不管那批边归一个具名 key； <b>已按 {@code direction} 取好该方向的限制强度
   *     s</b>）；不得为 null、不得含 null
   * @throws IllegalArgumentException 入参形状坏（编程错误）
   * @throws ArithmeticException 权重/乘积溢出（调用方折成具名契约 ERROR，不静默截断）
   */
  public static ZonePortRegime aggregate(
      String zoneId, String classKey, PortDirection direction, List<PortContactSurface> surfaces) {
    Objects.requireNonNull(direction, "direction（入口/出口各算一份读数，不给合并读数）");
    Objects.requireNonNull(surfaces, "surfaces（没有接触面给 List.of()）");
    List<ZonePortRegime.SurfaceReading> readings = new ArrayList<>(surfaces.size());
    long totalWeight = 0L;
    long weightedOpenness = 0L;
    boolean anyUnrestricted = false;
    for (PortContactSurface surface : surfaces) {
      Objects.requireNonNull(surface, "surfaces 不得含 null");
      long enforcement =
          Math.floorDiv(
              Math.multiplyExact(surface.restrictionPerMille(), surface.portEfficiencyPerMille()),
              PER_MILLE);
      long openness = Math.max(0L, Math.min(PER_MILLE, PER_MILLE - enforcement));
      readings.add(
          new ZonePortRegime.SurfaceReading(
              surface.contactKey(),
              surface.exposedEdgeCount(),
              surface.restrictionPerMille(),
              surface.portEfficiencyPerMille(),
              enforcement,
              openness));
      totalWeight = Math.addExact(totalWeight, surface.exposedEdgeCount());
      weightedOpenness =
          Math.addExact(weightedOpenness, Math.multiplyExact(surface.exposedEdgeCount(), openness));
      anyUnrestricted = anyUnrestricted || surface.unrestricted();
    }
    if (totalWeight == 0L) {
      // ★ 具名口径：没有邻居 ⇒ 没有可管的口岸 ⇒ 全开（设计书 §4.3）。
      return new ZonePortRegime(zoneId, classKey, direction, true, PER_MILLE, 0L, true, readings);
    }
    long efficiency = Math.floorDiv(weightedOpenness, totalWeight);
    return new ZonePortRegime(
        zoneId, classKey, direction, anyUnrestricted, efficiency, totalWeight, false, readings);
  }

  /** 商品类的便利入口（类键 = {@link CommodityId} 裸值；方向见 {@link PortDirection}）。 */
  public static ZonePortRegime aggregateCommodity(
      String zoneId,
      CommodityId commodity,
      PortDirection direction,
      List<PortContactSurface> surfaces) {
    Objects.requireNonNull(commodity, "commodity");
    return aggregate(zoneId, commodity.value(), direction, surfaces);
  }

  /** 币种类的便利入口（类键 = {@link CurrencyId} 裸值；方向见 {@link PortDirection}）。 */
  public static ZonePortRegime aggregateCurrency(
      String zoneId,
      CurrencyId currency,
      PortDirection direction,
      List<PortContactSurface> surfaces) {
    Objects.requireNonNull(currency, "currency");
    return aggregate(zoneId, currency.value(), direction, surfaces);
  }
}
