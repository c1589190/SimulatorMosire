package io.mosire.simos.gov;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个 GOV 单位在某一 tick 的行政读数（阶段 10a，计划 §2.2 / §3）。
 *
 * <p>★★ <b>只落"事实"，不落任何力量/效率的推导</b>：{@code lastAssessed*}/{@code lastPaid*}/{@code lastShortfall*}
 * 记的是当日结算账（供审计与下一 tick 决策人读），四个 per-mille 是已经算好的覆盖率/效率/加成读数。行政力公式、 需求公式、人员供给都不在本类型里——那归阶段 11 的
 * {@code GovDemand}/{@code GovEfficiency}/{@code GovDaily} 纯函数（用户裁定 1/2：行政力与战斗力分开算，unit 侧不算力量，gov
 * 侧只落每 tick 读数）。
 *
 * <p>★ <b>三对账</b>：商品侧（{@code *Goods}）与货币侧（{@code *Money}）各自"评估 / 实付 / 缺口"三张表。量纲 =
 * 最小计量单位（毫粮、毫布、毫银…）；值 ≥ 0。缺口表与评估表同键集不是本类型强制的不变量——它们是**读数快照**， 由结算方按自己的口径写；本类型只保证每张表自身的形状合法。
 *
 * <p>★ <b>保序不可变</b>：六张表都用 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap}
 * 冻结（<b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立）。冻结写在赋值处是 SpotBugs 的 {@code
 * EI_EXPOSE_REP} 唯一认得的形态（与 {@code GovernmentFormation} / {@code ActorData} 同一条纪律）。
 *
 * <p>★ <b>构造期校验</b>：{@code unitId} 非 null；{@code tick ≥ 0}；六张表非 null、键/值非 null、值 ≥ 0； {@code
 * securityCoveragePerMille}/{@code paperworkCoveragePerMille ∈ [0,1000]}；{@code efficiencyPerMille
 * ∈ [0,1100]}（覆盖 1000‰ × 最高 +10% 加成）；{@code bonusPerMille ∈ [0,100]}。越界一律当场抛 {@link
 * IllegalArgumentException}，不静默钳制。
 *
 * @param unitId 这条读数属于哪个 GOV 单位（非 null；{@link GovState} 的键必须与它一致）
 * @param tick 读数所在 tick（≥ 0；日制底座 1 tick = 1 天）
 * @param lastAssessedGoods 本 tick 评估的商品需求（商品 → 毫；键/值非 null、值 ≥ 0、保序不可变）
 * @param lastPaidGoods 本 tick 实际支付的商品（同口径）
 * @param lastShortfallGoods 本 tick 未满足的商品缺口（同口径；不静默漏记）
 * @param lastAssessedMoney 本 tick 评估的货币需求（币种 → 毫；同口径）
 * @param lastPaidMoney 本 tick 实际支付的货币（同口径）
 * @param lastShortfallMoney 本 tick 未满足的货币缺口（同口径）
 * @param securityCoveragePerMille 治安覆盖率（‰；0..1000，1000 = 需求被全额覆盖）
 * @param paperworkCoveragePerMille 文书覆盖率（‰；0..1000，同口径）
 * @param efficiencyPerMille 行政效率（‰；coverage × (1 + bonus)，上限 1100）
 * @param bonusPerMille 超编加成（‰；0..100，仅 coverage = 1000 时可为正）
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
    long bonusPerMille) {

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
    requirePerMilleRange(securityCoveragePerMille, 0L, 1000L, "securityCoveragePerMille");
    requirePerMilleRange(paperworkCoveragePerMille, 0L, 1000L, "paperworkCoveragePerMille");
    requirePerMilleRange(efficiencyPerMille, 0L, 1100L, "efficiencyPerMille");
    requirePerMilleRange(bonusPerMille, 0L, 100L, "bonusPerMille");
  }

  /**
   * ★ <b>空读数便捷工厂</b>：六张表全空、四个 per-mille 全 0。
   *
   * <p>★ 0 在这里的语义是"<b>尚无读数</b>"，不是"需求为零所以全覆盖"：{@code coverage = 1000} 是公式在"该格需求为 0" 时的取值（计划
   * §3），不能拿来当"还没有结算过"的初值——那会把"没数据"伪装成"需求已被满足"。
   */
  public static GovOfficeState empty(UnitId unitId, long tick) {
    return new GovOfficeState(
        unitId, tick, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0L, 0L, 0L, 0L);
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

  private static void requirePerMilleRange(long value, long min, long max, String field) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(field + " 必须 ∈ [" + min + "," + max + "]: " + value);
    }
  }
}
