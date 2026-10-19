package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.model.WageAnchorReadout;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * ★★★ <b>U20 工资锚的只读读口 / 确定性视图</b>（D-032 P4 §6.4）。
 *
 * <p>★★ <b>缺锚 = empty，0 = 合法</b>：{@link #anchorFor} 只返回 {@code Optional.empty()} 表示"没有这个锚"； {@code
 * wagePerLaborMilli = 0} 的锚会原样返回。{@link #require} 把缺锚变成具名拒绝 {@code
 * wage-anchor-missing:<key>}，调用方不得用 0 兜底。
 *
 * <p>★ <b>确定性</b>：构造时按 {@link WageAnchorKey#toString()} 稳定排序（{@link TreeMap}）， {@link #snapshot()}
 * / {@link #readouts()} 的迭代序与输入 Map 的插入序无关。
 *
 * <p>★ 本类不直接写会话/账户；{@link #applyObservations} 是纯函数，返回新的完整锚表，由日结算管线在 M8 完成后写回唯一锚账。
 */
public final class WageAnchorBook {

  private final Map<WageAnchorKey, ProductionWageAnchor> anchors;

  /** 从当刻日视图冻结一份只读锚表（确定性序）。 */
  public WageAnchorBook(EconomyDayView view) {
    this(Objects.requireNonNull(view, "view").productionWageAnchors());
  }

  /** 显式以锚表构造；键/值非空、确定性排序、不可变。 */
  public WageAnchorBook(Map<WageAnchorKey, ProductionWageAnchor> anchors) {
    this.anchors = validate(anchors);
  }

  /**
   * 校验并冻结锚表：键/值非 null；按 {@link WageAnchorKey#toString()} 排序。
   *
   * @throws IllegalArgumentException 键或值为 null
   */
  public static Map<WageAnchorKey, ProductionWageAnchor> validate(
      Map<WageAnchorKey, ProductionWageAnchor> anchors) {
    Map<WageAnchorKey, ProductionWageAnchor> copy =
        new TreeMap<>(Comparator.comparing(WageAnchorKey::toString));
    if (anchors != null) {
      for (Map.Entry<WageAnchorKey, ProductionWageAnchor> entry : anchors.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("WageAnchorBook 的键与值都不得为 null: " + entry.getKey());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(copy));
  }

  /** 该键的锚；缺锚 = {@code Optional.empty()}（不是 0）。 */
  public Optional<ProductionWageAnchor> anchorFor(WageAnchorKey key) {
    Objects.requireNonNull(key, "key");
    return Optional.ofNullable(anchors.get(key));
  }

  /** 该键的锚；缺锚 ⇒ 具名 {@code wage-anchor-missing:<key>}。 */
  public ProductionWageAnchor require(WageAnchorKey key) {
    return anchorFor(key)
        .orElseThrow(() -> new IllegalArgumentException("wage-anchor-missing:" + key));
  }

  /** 只读、确定性序的锚快照。 */
  public Map<WageAnchorKey, ProductionWageAnchor> snapshot() {
    return anchors;
  }

  /**
   * ★★★ <b>D-032 P4c2：市场日 M8 结束后观察锚并生成下一 tick 的锚表</b>（纯函数，不写会话/账户）。
   *
   * <p>★★ <b>只观察 target &gt; 0 的锚</b>：target=0（含缺 target）不重置 streak、不改任何字段。 {@code matched <
   * target} ⇒ 缺工分支；{@code matched >= target} ⇒ 招满分支；两分支都复用 {@link ProductionWageBook#adjustPrice}
   * 的同一 momentum/minStep/延迟降价公式。
   *
   * <p>★★ <b>非市场日逐值不变</b>：{@code marketDay=false} 直接返回原锚表的确定性只读副本；不写 streak、不调价。
   *
   * <p>★★ <b>原子边界</b>：本方法只算完整的下一张锚 map；任何 key 缺失/值为负在返回前抛具名契约故障，调用方不得半写。
   *
   * @param anchors 当前锚快照（M8 开始时锁定）
   * @param targetLaborMilliByAnchor 本 tick M3 WageDemand 按锚聚合的 target（同锚多 unit 求和）
   * @param matchedLaborMilliByAnchor 本 tick M8 实际劳动按锚聚合的 matched（缺键 = 0）
   * @param day 世界日
   * @param marketDay 本日是否市场轮；false ⇒ 不调锚
   * @param policy U20 调价参数（唯一公式见 {@link ProductionWageBook#adjustPrice}）
   * @return 新的完整锚表（确定性序、不可变）
   */
  public static Map<WageAnchorKey, ProductionWageAnchor> applyObservations(
      Map<WageAnchorKey, ProductionWageAnchor> anchors,
      Map<WageAnchorKey, Long> targetLaborMilliByAnchor,
      Map<WageAnchorKey, Long> matchedLaborMilliByAnchor,
      long day,
      boolean marketDay,
      ProductionWageBook.WagePricingPolicy policy) {
    Objects.requireNonNull(anchors, "anchors");
    Objects.requireNonNull(targetLaborMilliByAnchor, "targetLaborMilliByAnchor");
    Objects.requireNonNull(matchedLaborMilliByAnchor, "matchedLaborMilliByAnchor");
    Objects.requireNonNull(policy, "policy");
    if (day < 0L) {
      throw new IllegalArgumentException("WageAnchorBook.applyObservations day 不得为负: " + day);
    }
    if (!marketDay) {
      return validate(anchors);
    }

    List<WageAnchorKey> targets = new ArrayList<>(targetLaborMilliByAnchor.keySet());
    targets.sort(Comparator.comparing(WageAnchorKey::toString));
    LinkedHashMap<WageAnchorKey, ProductionWageAnchor> next = new LinkedHashMap<>(anchors);
    int observed = 0;
    int adjusted = 0;
    long totalTarget = 0L;
    long totalMatched = 0L;
    for (WageAnchorKey key : targets) {
      Objects.requireNonNull(key, "targetLaborMilliByAnchor 不得含 null 键");
      Long targetBoxed = targetLaborMilliByAnchor.get(key);
      if (targetBoxed == null || targetBoxed < 0L) {
        throw contractFault("wage-anchor-target-invalid", key + " target=" + targetBoxed);
      }
      long target = targetBoxed;
      if (target == 0L) {
        continue; // ★ target=0 不重置 streak、不观察、不调价
      }
      ProductionWageAnchor current = next.get(key);
      if (current == null) {
        throw contractFault("wage-anchor-missing", key.toString());
      }
      Long matchedBoxed = matchedLaborMilliByAnchor.get(key);
      long matched = matchedBoxed == null ? 0L : matchedBoxed;
      if (matched < 0L) {
        throw contractFault("wage-anchor-matched-invalid", key + " matched=" + matched);
      }
      ProductionWageBook.PriceAdjustment adjustment =
          ProductionWageBook.adjustPrice(
              current.wagePerLaborMilli(),
              current.shortageStreak(),
              current.satisfiedStreak(),
              current.lastAdjustedTick(),
              current.lastAdjustmentDelta(),
              target,
              matched,
              policy,
              day);
      ProductionWageAnchor updated =
          new ProductionWageAnchor(
              current.currency(),
              adjustment.wagePerLaborMilli(),
              adjustment.shortageStreak(),
              adjustment.satisfiedStreak(),
              adjustment.lastAdjustedTick(),
              matched,
              target,
              adjustment.lastAdjustmentDelta());
      next.put(key, updated);
      observed++;
      totalTarget = Math.addExact(totalTarget, target);
      totalMatched = Math.addExact(totalMatched, matched);
      if (adjustment.changed()) {
        adjusted++;
      }

      if (EconomyLog.trace().isTraceEnabled()) {
        EventLog.channel(EconomyLog.trace())
            .trace(
                LogEvent.of(
                    "WAGE_ANCHOR_OBSERVED",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "marketZone",
                    key.marketZone().value(),
                    "industry",
                    key.industry().value(),
                    "role",
                    key.role().value(),
                    "targetLaborMilli",
                    target,
                    "matchedLaborMilli",
                    matched,
                    "oldWage",
                    current.wagePerLaborMilli(),
                    "newWage",
                    updated.wagePerLaborMilli(),
                    "shortageStreak",
                    updated.shortageStreak(),
                    "satisfiedStreak",
                    updated.satisfiedStreak(),
                    "lastAdjustmentDelta",
                    updated.lastAdjustmentDelta()));
      }
      if (EconomyLog.settlement().isDebugEnabled()) {
        EventLog.channel(EconomyLog.settlement())
            .debug(
                LogEvent.of(
                    adjustment.changed() ? "WAGE_ANCHOR_ADJUSTED" : "WAGE_ANCHOR_OBSERVED",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "anchor",
                    key.toString(),
                    "targetLaborMilli",
                    target,
                    "matchedLaborMilli",
                    matched,
                    "wagePerLaborMilli",
                    updated.wagePerLaborMilli(),
                    "shortageStreak",
                    updated.shortageStreak(),
                    "satisfiedStreak",
                    updated.satisfiedStreak(),
                    "lastAdjustedTick",
                    updated.lastAdjustedTick(),
                    "lastAdjustmentDelta",
                    updated.lastAdjustmentDelta()));
      }
    }

    if (observed > 0) {
      EventLog.channel(EconomyLog.settlement())
          .info(
              LogEvent.of(
                  "WAGE_ANCHOR_OBSERVED",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "anchorsObserved",
                  observed,
                  "anchorsAdjusted",
                  adjusted,
                  "totalTargetLaborMilli",
                  totalTarget,
                  "totalMatchedLaborMilli",
                  totalMatched));
      if (adjusted > 0) {
        EventLog.channel(EconomyLog.settlement())
            .info(
                LogEvent.of(
                    "WAGE_ANCHOR_ADJUSTED",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    day,
                    "anchorsAdjusted",
                    adjusted,
                    "anchorsObserved",
                    observed));
      }
    }
    return validate(next);
  }

  private static IllegalStateException contractFault(String word, String detail) {
    EventLog.channel(EconomyLog.settlement())
        .error(
            LogEvent.of(
                "WAGE_ANCHOR_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "failure",
                word,
                "detail",
                detail == null ? "-" : detail));
    return new IllegalStateException(word + ": " + detail);
  }

  /** 只读、确定性序的锚级读数。 */
  public List<WageAnchorReadout> readouts() {
    List<WageAnchorReadout> result = new ArrayList<>(anchors.size());
    for (Map.Entry<WageAnchorKey, ProductionWageAnchor> entry : anchors.entrySet()) {
      result.add(WageAnchorReadout.of(entry.getKey(), entry.getValue()));
    }
    return Collections.unmodifiableList(result);
  }
}
