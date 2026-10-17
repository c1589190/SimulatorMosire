package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import java.util.Objects;

/**
 * ★★ <b>D-032 D4 资产占用持续期的唯一 economy 配置</b>（D4 §4.5.1/§4.5.2）。
 *
 * <p>本类是 economy 侧唯一决定“某资产种类按周期锁还是按 tick 锁”的拼写点；production 只提供 {@code capacityPerUnit}/{@code
 * cycleDays}/{@code progressDays}，不保存第二份 horizon 运行时副本。
 *
 * <pre>
 * LAND / CATTLE / WORKSHOP / MACHINE / LARGE_TOOL → CYCLE  [cycleStart, cycleStart + cycleDays)
 * SMALL_TOOL / SHIP                              → TICK   [today, today + 1)
 * </pre>
 *
 * <p>★ <b>fail-closed</b>：未登记/非法 {@link AssetKind} 一律 {@code
 * asset-occupancy-horizon-unsupported:<kind>}；周期起点、周期天数或半开区间非法一律 {@code
 * asset-occupancy-horizon-invalid}。本类不猜默认值、不把未知 kind 映射成任一已登记值。
 */
public final class AssetOccupancyHorizonPolicy {

  /** 资产占用的两种首期 horizon。 */
  public enum Horizon {
    CYCLE,
    TICK
  }

  /** 半开占用区间 {@code [fromTick, toTickExclusive)}。 */
  public record Interval(long fromTick, long toTickExclusive) {
    public Interval {
      if (fromTick < 0L) {
        throw new IllegalArgumentException(
            "asset-occupancy-horizon-invalid: fromTick 不得为负: " + fromTick);
      }
      if (fromTick >= toTickExclusive) {
        throw new IllegalArgumentException(
            "asset-occupancy-horizon-invalid: 区间必须满足 fromTick < toTickExclusive: "
                + fromTick
                + " >= "
                + toTickExclusive);
      }
    }
  }

  private AssetOccupancyHorizonPolicy() {}

  /** 某资产种类的唯一 horizon 策略。未知/非法值具名 fail-closed。 */
  public static Horizon horizonOf(AssetKind kind) {
    if (kind == null) {
      throw new IllegalArgumentException("asset-occupancy-horizon-unsupported:null");
    }
    return switch (kind) {
      case LAND, CATTLE, WORKSHOP, MACHINE, LARGE_TOOL -> Horizon.CYCLE;
      case SMALL_TOOL, SHIP -> Horizon.TICK;
      default ->
          throw new IllegalArgumentException("asset-occupancy-horizon-unsupported:" + kind.name());
    };
  }

  /**
   * 按唯一策略给出半开占用区间。
   *
   * @param kind 资产种类
   * @param today 当前世界日（整数 tick）
   * @param cycleStart 本周期起点；{@code today - unit.progressDays()}（新实例 = today）
   * @param cycleDays 方案周期天数（生产定义权威；CYCLE 必须 ≥ 1）
   */
  public static Interval intervalOf(AssetKind kind, long today, long cycleStart, long cycleDays) {
    Objects.requireNonNull(kind, "kind 不得为 null（未知 kind 必须走具名拒绝）");
    if (today < 0L) {
      throw new IllegalArgumentException("asset-occupancy-horizon-invalid: today 不得为负: " + today);
    }
    if (cycleStart < 0L) {
      throw new IllegalArgumentException(
          "asset-occupancy-horizon-invalid: cycleStart 不得为负: " + cycleStart);
    }
    if (cycleStart > today) {
      throw new IllegalArgumentException(
          "asset-occupancy-horizon-invalid: cycleStart 不得晚于 today: " + cycleStart + " > " + today);
    }
    return switch (horizonOf(kind)) {
      case TICK -> {
        try {
          yield new Interval(today, Math.addExact(today, 1L));
        } catch (ArithmeticException overflow) {
          throw new IllegalArgumentException(
              "asset-occupancy-horizon-invalid: TICK 区间溢出 today=" + today, overflow);
        }
      }
      case CYCLE -> {
        if (cycleDays < 1L) {
          throw new IllegalArgumentException(
              "asset-occupancy-horizon-invalid: CYCLE 的 cycleDays 必须 ≥ 1: " + cycleDays);
        }
        long toTickExclusive;
        try {
          toTickExclusive = Math.addExact(cycleStart, cycleDays);
        } catch (ArithmeticException overflow) {
          throw new IllegalArgumentException(
              "asset-occupancy-horizon-invalid: CYCLE 区间溢出 cycleStart="
                  + cycleStart
                  + " cycleDays="
                  + cycleDays,
              overflow);
        }
        if (toTickExclusive <= today) {
          throw new IllegalArgumentException(
              "asset-occupancy-horizon-invalid: CYCLE 区间不覆盖 today: ["
                  + cycleStart
                  + ","
                  + toTickExclusive
                  + ") today="
                  + today);
        }
        yield new Interval(cycleStart, toTickExclusive);
      }
    };
  }
}
