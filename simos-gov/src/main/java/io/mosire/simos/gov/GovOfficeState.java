package io.mosire.simos.gov;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个 GOV 单位在某一 tick 的行政读数（阶段 10a；Z2 去上限并扩两维效率，设计书 §3 / §4.1 / C3）。
 *
 * <p>★★ <b>只落“事实”，不落任何力量/效率的推导</b>：{@code lastAssessed*}/{@code lastPaid*}/{@code lastShortfall*}
 * 记的是当日结算账（供审计与下一 tick 决策人读）；下面的 per-mille 字段是已经算好的读数。行政力公式、需求公式、人员供给都不在本类型里 ——那归 {@link
 * GovDemand}/{@link GovEfficiency}/{@link GovDaily} 纯函数（用户裁定 1/2：行政力与战斗力分开算，unit 侧不算力量， gov 侧只落每
 * tick 读数）。
 *
 * <p>★★ <b>Z2 读数形状（C3 去上限）</b>：
 *
 * <ul>
 *   <li>{@link #securityCoveragePerMille()} / {@link
 *       #paperworkCoveragePerMille()}：两维<b>满足率</b>（‰；沿用旧字段名 {@code coverage} 的语义槽位）。新二维修正公式下<b>可超过
 *       1000‰</b>（超编开方后仍可能高于需求），只判 ≥ 0，<b>不封顶</b>；
 *   <li>{@link #securityEfficiencyPerMille()} / {@link #paperworkEfficiencyPerMille()}（Z2
 *       新增）：两维<b>最终效率</b>（‰； 已乘供给静态/动态修正），只判 ≥ 0，不封顶；
 *   <li>{@link #efficiencyPerMille()}：<b>总行政效率</b>（‰）= 两维最终效率相乘 ÷ 1000，只判 ≥ 0，不封顶；
 *   <li>{@link #bonusPerMille()}：<b>legacy 兼容组件</b>（旧 11a 的超编加成读数，0..100）。Z2 的新状态恒写 0；字段保留只为
 *       旧档可读、以及不让 Z6 测试构造点全改（旧 12 参构造器仍可用，新字段默认 0）。
 * </ul>
 *
 * <p>★ <b>六张表</b>：商品侧（{@code *Goods}）与货币侧（{@code *Money}）各自“评估 / 实付 / 缺口”三张表。量纲 = 最小计量单位（毫粮、
 * 毫布、毫银…）；值 ≥ 0。缺口表与评估表同键集不是本类型强制的不变量——它们是<b>读数快照</b>，由结算方按自己的口径写；本类型只保证 每张表自身的形状合法。
 *
 * <p>★ <b>保序不可变</b>：六张表都用 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap}
 * 冻结（<b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立）。冻结写在赋值处是 SpotBugs 的 {@code
 * EI_EXPOSE_REP} 唯一认得的形态（与 {@code GovernmentFormation} / {@code ActorData} 同一条纪律）。
 *
 * <p>★ <b>构造期校验</b>：{@code unitId} 非 null；{@code tick ≥ 0}；六张表非 null、键/值非 null、值 ≥ 0；六个 per-mille
 * 字段都只要求 {@code ≥ 0}（Z2 拆掉 1000/1100/100 全部上限）。越界一律当场抛 {@link IllegalArgumentException}，不静默钳制。
 *
 * @param unitId 这条读数属于哪个 GOV 单位（非 null；{@link GovState} 的键必须与它一致）
 * @param tick 读数所在 tick（≥ 0；日制底座 1 tick = 1 天）
 * @param lastAssessedGoods 本 tick 评估的商品需求（商品 → 毫；键/值非 null、值 ≥ 0、保序不可变）
 * @param lastPaidGoods 本 tick 实际支付的商品（同口径）
 * @param lastShortfallGoods 本 tick 未满足的商品缺口（同口径；不静默漏记）
 * @param lastAssessedMoney 本 tick 评估的货币需求（币种 → 毫；同口径）
 * @param lastPaidMoney 本 tick 实际支付的货币（同口径）
 * @param lastShortfallMoney 本 tick 未满足的货币缺口（同口径）
 * @param securityCoveragePerMille 治安满足率（‰；≥ 0，不封顶）
 * @param paperworkCoveragePerMille 公文满足率（‰；≥ 0，不封顶）
 * @param efficiencyPerMille 总行政效率（‰；≥ 0，不封顶）
 * @param bonusPerMille 旧 11a 超编加成（legacy；Z2 新状态恒 0）
 * @param securityEfficiencyPerMille 治安最终效率（‰；≥ 0，不封顶）
 * @param paperworkEfficiencyPerMille 公文最终效率（‰；≥ 0，不封顶）
 */
public record GovOfficeState(
    UnitId unitId,
    long tick,
    Map<CommodityId, Long> lastAssessedGoods,
    Map<CommodityId, Long> lastPaidGoods,
    Map<CommodityId, Long> lastShortfallGoods,
    Map<CurrencyId, Long> lastAssessedMoney,
    Map<CurrencyId, Long> lastPaidMoney,
    Map<CurrencyId, Long> lastShortfallMoney,
    long securityCoveragePerMille,
    long paperworkCoveragePerMille,
    long efficiencyPerMille,
    long bonusPerMille,
    long securityEfficiencyPerMille,
    long paperworkEfficiencyPerMille) {

  public GovOfficeState {
    if (unitId == null) {
      throw new IllegalArgumentException("unitId 不得为 null");
    }
    if (tick < 0L) {
      throw new IllegalArgumentException("tick 必须 ≥ 0: " + tick);
    }
    // ★ 六张表都在赋值处冻结（helper 只做拷贝 + 逐键值校验，返回普通 LinkedHashMap）。
    lastAssessedGoods =
        Collections.unmodifiableMap(copyGoods(lastAssessedGoods, "lastAssessedGoods"));
    lastPaidGoods = Collections.unmodifiableMap(copyGoods(lastPaidGoods, "lastPaidGoods"));
    lastShortfallGoods =
        Collections.unmodifiableMap(copyGoods(lastShortfallGoods, "lastShortfallGoods"));
    lastAssessedMoney =
        Collections.unmodifiableMap(copyMoney(lastAssessedMoney, "lastAssessedMoney"));
    lastPaidMoney = Collections.unmodifiableMap(copyMoney(lastPaidMoney, "lastPaidMoney"));
    lastShortfallMoney =
        Collections.unmodifiableMap(copyMoney(lastShortfallMoney, "lastShortfallMoney"));
    requireNonNegative(securityCoveragePerMille, "securityCoveragePerMille");
    requireNonNegative(paperworkCoveragePerMille, "paperworkCoveragePerMille");
    requireNonNegative(efficiencyPerMille, "efficiencyPerMille");
    requireNonNegative(bonusPerMille, "bonusPerMille");
    requireNonNegative(securityEfficiencyPerMille, "securityEfficiencyPerMille");
    requireNonNegative(paperworkEfficiencyPerMille, "paperworkEfficiencyPerMille");
  }

  /**
   * ★ <b>旧 12 参构造器（Z2 兼容）</b>：文件/测试里 Z2 之前的构造点不用改；两个新维效率字段取 0（“尚未分维读数”）。新调用点应使用 canonical 14
   * 参构造器并显式填两维效率。
   */
  public GovOfficeState(
      UnitId unitId,
      long tick,
      Map<CommodityId, Long> lastAssessedGoods,
      Map<CommodityId, Long> lastPaidGoods,
      Map<CommodityId, Long> lastShortfallGoods,
      Map<CurrencyId, Long> lastAssessedMoney,
      Map<CurrencyId, Long> lastPaidMoney,
      Map<CurrencyId, Long> lastShortfallMoney,
      long securityCoveragePerMille,
      long paperworkCoveragePerMille,
      long efficiencyPerMille,
      long bonusPerMille) {
    this(
        unitId,
        tick,
        lastAssessedGoods,
        lastPaidGoods,
        lastShortfallGoods,
        lastAssessedMoney,
        lastPaidMoney,
        lastShortfallMoney,
        securityCoveragePerMille,
        paperworkCoveragePerMille,
        efficiencyPerMille,
        bonusPerMille,
        0L,
        0L);
  }

  /**
   * ★ <b>空读数便捷工厂</b>：六张表全空、六个 per-mille 全 0。
   *
   * <p>★ 0 在这里的语义是“<b>尚无读数</b>”，不是“需求为零所以全覆盖”：{@code coverage = 1000} 是公式在“该维需求为 0” 且供给 > 0
   * 时的取值（设计书 §3），不能拿来当“还没有结算过”的初值——那会把“没数据”伪装成“需求已被满足”。
   */
  public static GovOfficeState empty(UnitId unitId, long tick) {
    return new GovOfficeState(
        unitId, tick, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0L, 0L, 0L, 0L,
        0L, 0L);
  }

  /** 商品侧三张表共用的拷贝：非 null、键/值非 null、值 ≥ 0；返回可继续由赋值处冻结的普通表。 */
  private static Map<CommodityId, Long> copyGoods(Map<CommodityId, Long> map, String field) {
    if (map == null) {
      throw new IllegalArgumentException(field + " 不得为 null（无该项用 Map.of()）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(field + " 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            field + " 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** 货币侧三张表共用的拷贝：口径与 {@link #copyGoods} 逐字相同（同一族的东西不许有两套写法）。 */
  private static Map<CurrencyId, Long> copyMoney(Map<CurrencyId, Long> map, String field) {
    if (map == null) {
      throw new IllegalArgumentException(field + " 不得为 null（无该项用 Map.of()）");
    }
    Map<CurrencyId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(field + " 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            field + " 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** Z2 去上限：六个 per-mille 字段只判 ≥ 0（C3：不再有 1000/1100/100 上界）。 */
  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException(field + " 必须 ≥ 0（Z2 起不封顶）: " + value);
    }
  }
}
