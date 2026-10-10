package io.mosire.simos.app.household;

import io.mosire.simos.economy.time.EconomyHouseholdGap;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.population.FamineMortalityInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★★ <b>W3（2026-10-11）：经济腿"逐户缺口"事实 → Social 饥荒死亡率输入的纯函数桥</b>（住在 {@code simos-app}： 唯一同时认识 economy
 * 与 social 的地方）。
 *
 * <p>★★ <b>它为什么必须在这里</b>：模块边界不许可 {@code simos-economy} 认识 social（enforcer 禁 import），也不许可 {@code
 * simos-social} 认识经济实现（只能用 {@code economy-api} 契约）。⇒ 两条腿的翻译只能落在组合根 —— 与 P0 的 {@code
 * MigrationSocialBridge} 同形制（同一份先例、同一个"经济只推事实 + app 同 revision 落另一域"的形态）。
 *
 * <pre>
 * 输入（经济腿瞬态 outbox，逐日现算逐日取走）
 *   List&lt;EconomyHouseholdGap&gt;: household / population / rationGapMilli / rationNeedMilli
 *                                 / productionCostGapByCommodity
 * 输出（Social 的入参，缺省中性可空）
 *   FamineMortalityInput.shortages: household → Shortage(rationGapMilli, rationNeedMilli,
 *                                                          productionCostGapByCommodity)
 * </pre>
 *
 * <p>★ <b>本桥不做任何折算</b>：它只做"形状翻译 + 去重/非空校验 + 计数"，**口径（断顿比例→死亡率）在 Social** （{@code
 * FamineMortality}）—— 这正是用户 0–8 第 0 条"让社会模块单独核算死亡率"的执行形态。
 *
 * <p>★ <b>为什么是纯函数</b>：同一份事实列表 ⇒ 同一份输出（"同日重放同结果"）；同一家户在同一批里重复出现 ⇒ 具名拒
 * （重复会把两次缺口叠成一次双倍断顿）。桥内不读时钟、不读状态、不写日志、不改任何 Social 组件。
 *
 * <p>★ <b>不改持久形状</b>：输出只进 {@code HouseholdBook.settleOneTick(...)} 的当 tick 入参（与 {@code
 * CalendarClock} 同制）⇒ 无新 {@code SocialData} 组件、无 Codec/ChangeSet 改动（<b>铁律 5 不触发</b>）。
 */
public final class FamineMortalityBridge {

  private FamineMortalityBridge() {}

  /**
   * 一次翻译的读数（可审计：报了几户、哪一支非 0、总量多少）。
   *
   * @param input 交给 Social 的饥荒输入（空列表 ⇒ {@link FamineMortalityInput#none()} 的等价物：空表）
   * @param households 报来的家户数（= 事实条目数；同一户重复出现是具名拒绝，不会在这里被合并）
   * @param householdsWithRationGap 口粮缺口那一支 &gt; 0 的家户数
   * @param householdsWithProductionCostGap 生产花费缺口非空的家户数（当日开市才有）
   * @param rationGapTotalMilli 口粮缺口合计（毫粮）
   * @param rationNeedTotalMilli 当日粮需求合计（毫粮；与分子同窗口）
   * @param productionCostGapTotalMilli 生产花费未满足量合计（毫；跨商品求和，**只作读数**——跨商品相加不是一个有意义的量，
   *     这里求和只为"日志里能一眼看出量级"，判定一律逐商品）
   */
  public record Report(
      FamineMortalityInput input,
      long households,
      long householdsWithRationGap,
      long householdsWithProductionCostGap,
      long rationGapTotalMilli,
      long rationNeedTotalMilli,
      long productionCostGapTotalMilli) {

    public Report {
      Objects.requireNonNull(input, "input");
    }
  }

  /**
   * 把经济腿的逐户缺口翻成 Social 的饥荒输入（保序 = 入参顺序；空列表 ⇒ 空表 = 缺省中性）。
   *
   * @param gaps 经济腿当日 outbox（{@code EconomyDayStepper.drainPendingHouseholdGaps()} 的返回值）；不得为
   *     null、不得含 null
   * @return 输入 + 具名读数
   * @throws IllegalArgumentException 含 null，或同一家户重复出现
   */
  public static Report apply(List<EconomyHouseholdGap> gaps) {
    Objects.requireNonNull(gaps, "gaps");
    if (gaps.isEmpty()) {
      return new Report(FamineMortalityInput.none(), 0L, 0L, 0L, 0L, 0L, 0L);
    }
    LinkedHashMap<HouseholdId, FamineMortalityInput.Shortage> shortages = new LinkedHashMap<>();
    Set<HouseholdId> seen = new LinkedHashSet<>();
    long householdsWithRationGap = 0L;
    long householdsWithProductionCostGap = 0L;
    long rationGapTotalMilli = 0L;
    long rationNeedTotalMilli = 0L;
    long productionCostGapTotalMilli = 0L;
    for (EconomyHouseholdGap gap : gaps) {
      EconomyHouseholdGap checked = Objects.requireNonNull(gap, "gap");
      if (!seen.add(checked.household())) {
        throw new IllegalArgumentException(
            "FamineMortalityBridge.apply 同一家户重复报缺口（会把两次缺口叠成一次双倍断顿）: " + checked.household());
      }
      shortages.put(
          checked.household(),
          new FamineMortalityInput.Shortage(
              checked.rationGapMilli(),
              checked.rationNeedMilli(),
              checked.productionCostGapByCommodity()));
      if (checked.hasRationGap()) {
        householdsWithRationGap++;
        rationGapTotalMilli = Math.addExact(rationGapTotalMilli, checked.rationGapMilli());
      }
      if (checked.hasProductionCostGap()) {
        householdsWithProductionCostGap++;
        for (long value : checked.productionCostGapByCommodity().values()) {
          productionCostGapTotalMilli = Math.addExact(productionCostGapTotalMilli, value);
        }
      }
      rationNeedTotalMilli = Math.addExact(rationNeedTotalMilli, checked.rationNeedMilli());
    }
    return new Report(
        new FamineMortalityInput(shortages),
        gaps.size(),
        householdsWithRationGap,
        householdsWithProductionCostGap,
        rationGapTotalMilli,
        rationNeedTotalMilli,
        productionCostGapTotalMilli);
  }

  /** ★ 逐户 TRACE 明细的可读摘要（与 {@link EconomyHouseholdGap#shortText()} 同源，供 app 侧一行一条地打）。 */
  public static List<String> detailLines(List<EconomyHouseholdGap> gaps) {
    Objects.requireNonNull(gaps, "gaps");
    List<String> lines = new ArrayList<>(gaps.size());
    for (EconomyHouseholdGap gap : gaps) {
      lines.add(Objects.requireNonNull(gap, "gap").shortText());
    }
    return lines;
  }

  /** ★ 只读投影：逐户（家户 → 口粮缺口）—— 供"逐户一致"核对脚本/读口使用（不参与任何算式）。 */
  public static Map<HouseholdId, Long> rationGapByHousehold(List<EconomyHouseholdGap> gaps) {
    Objects.requireNonNull(gaps, "gaps");
    LinkedHashMap<HouseholdId, Long> byHousehold = new LinkedHashMap<>();
    for (EconomyHouseholdGap gap : gaps) {
      if (gap.hasRationGap()) {
        byHousehold.merge(gap.household(), gap.rationGapMilli(), Math::addExact);
      }
    }
    return byHousehold;
  }
}
