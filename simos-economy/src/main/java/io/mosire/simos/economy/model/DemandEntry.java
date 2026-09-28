package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>一条需求</b>（R4-E2）：说清"谁、在哪个范围、要什么商品、每周期多少、什么时候有效"。
 *
 * <p>★★ <b>范围是显式维度</b>（{@link DemandScope}）：{@link DemandScope#HOUSEHOLD} = 指名一个家户（GM/内生需求可直接归户）；
 * {@link DemandScope#HEX} = 指名一格，由订单路径按本格家户人口用 {@code ProportionalSplit} 摊到户（GM 注入常用这一档）。
 * 两者**互斥**：HOUSEHOLD 必须给 {@code household}、{@code hex} 必须空；HEX 必须给 {@code hex}、{@code household}
 * 必须空 —— 否则"这条需求是谁的"就有两处可能不一致的拼写。
 *
 * <p>★★ <b>时间语义</b>：{@code createdDay > day} 不生效；{@code expiresDay >= 0 && expiresDay < day} 不生效；
 * {@code expiresDay < 0} = 永久。{@code quantityPerCycle} 是**每周期**的量（不是每天）：市场每轮都按"当日有效目标" 现算，{@link
 * DemandKind#ONE_OFF} 在一个有效窗口内每轮也按整份目标量处理（跨轮剩余递减**不在本片**，见 E2 报告）。
 *
 * <p>★★ <b>不变量（构造期判死，不静默归一）</b>：
 *
 * <ul>
 *   <li>{@code quantityPerCycle > 0}（0 不是"没有需求"，是一条假记录）；{@code createdDay >= 0}；
 *   <li>{@code priority >= 0}（订单预算按它升序切分）；
 *   <li>scope ↔ household/hex 的互斥与必填（见上）；
 *   <li>{@code source} 必填（谁注入的：GM 命令、E4 内生消费……）。
 * </ul>
 *
 * <p>★ <b>量纲</b>：{@code quantityPerCycle} 的单位随 {@link DemandUnit} —— {@link DemandUnit#TOTAL} =
 * 每周期总量（毫单位）； {@link DemandUnit#PER_CAPITA} = 每人每周期量（毫单位/人），由订单路径乘人口。
 *
 * @param id 稳定身份；不得为 null
 * @param scope 需求范围；不得为 null
 * @param household HOUSEHOLD 范围的家户；HEX 范围必须为空
 * @param hex HEX 范围的格；HOUSEHOLD 范围必须为空
 * @param commodity 商品；不得为 null
 * @param kind 周期性/一次性；不得为 null（本片两者在订单路径同口径，差别留 E3/E4）
 * @param unit 总量/人均；不得为 null
 * @param quantityPerCycle 每周期量；必须 &gt; 0
 * @param createdDay 生效起始日（含）；必须 ≥ 0
 * @param expiresDay 最后一个生效日（含）；&lt; 0 = 永久
 * @param priority 优先级（小者先分预算）；必须 ≥ 0
 * @param source 注入来源标签；不得为 null
 */
public record DemandEntry(
    DemandId id,
    DemandScope scope,
    Optional<HouseholdId> household,
    Optional<HexCoord> hex,
    CommodityId commodity,
    DemandKind kind,
    DemandUnit unit,
    long quantityPerCycle,
    long createdDay,
    long expiresDay,
    int priority,
    String source) {

  /** 需求范围：直接归户，还是按格摊到户。 */
  public enum DemandScope {
    HOUSEHOLD,
    HEX
  }

  /** 需求种类：周期性（长期目标）还是一次性（单次目标）。本片订单路径同口径，递减/消账留 E3/E4。 */
  public enum DemandKind {
    RECURRING,
    ONE_OFF
  }

  /** 计量口径：{@code TOTAL} = 每周期总量；{@code PER_CAPITA} = 每人每周期量。 */
  public enum DemandUnit {
    TOTAL,
    PER_CAPITA
  }

  public DemandEntry {
    Objects.requireNonNull(id, "DemandEntry.id 不得为 null");
    Objects.requireNonNull(scope, "DemandEntry.scope 不得为 null");
    Objects.requireNonNull(household, "DemandEntry.household 不得为 null（没有家户请用 Optional.empty()）");
    Objects.requireNonNull(hex, "DemandEntry.hex 不得为 null（没有格请用 Optional.empty()）");
    Objects.requireNonNull(commodity, "DemandEntry.commodity 不得为 null");
    Objects.requireNonNull(kind, "DemandEntry.kind 不得为 null");
    Objects.requireNonNull(unit, "DemandEntry.unit 不得为 null");
    Objects.requireNonNull(source, "DemandEntry.source 不得为 null");
    if (scope == DemandScope.HOUSEHOLD) {
      if (household.isEmpty()) {
        throw new IllegalArgumentException("HOUSEHOLD 范围的需求必须给 household: " + id.value());
      }
      if (hex.isPresent()) {
        throw new IllegalArgumentException("HOUSEHOLD 范围的需求不得给 hex（同一件事不许两处拼写）: " + id.value());
      }
    } else {
      if (hex.isEmpty()) {
        throw new IllegalArgumentException("HEX 范围的需求必须给 hex: " + id.value());
      }
      if (household.isPresent()) {
        throw new IllegalArgumentException("HEX 范围的需求不得给 household（同一件事不许两处拼写）: " + id.value());
      }
    }
    if (quantityPerCycle <= 0L) {
      throw new IllegalArgumentException(
          "DemandEntry.quantityPerCycle 必须 > 0: " + quantityPerCycle);
    }
    if (createdDay < 0L) {
      throw new IllegalArgumentException("DemandEntry.createdDay 不得为负: " + createdDay);
    }
    if (priority < 0) {
      throw new IllegalArgumentException("DemandEntry.priority 不得为负: " + priority);
    }
  }

  /** ★ day 当天这条需求是否生效（created 含当日、expires 不含当日；过期日 &lt; 0 = 永久）。 */
  public boolean effectiveOn(long day) {
    if (createdDay > day) {
      return false;
    }
    return expiresDay < 0L || expiresDay >= day;
  }
}
