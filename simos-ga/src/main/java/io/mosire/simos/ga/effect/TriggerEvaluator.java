package io.mosire.simos.ga.effect;

import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/**
 * 条件求值（spec §三.3 N1 / §三.6 R6）：**纯函数**，数据驱动，不硬编码 Java 分支。
 *
 * <p>★ v1 语义：{@code AfterTicks(n)} = 相对参照点 {@code sinceTick} 再经过 n **天**（效果用其 {@code
 * createdTick}，阶段用 0）；{@code AtOrAfterTick(t)} = 世界日 t 到达或已过；{@code UnitAtHex} 走 {@link
 * UnitState#effectivePosition}。
 *
 * <p>★ **时间量纲（2026-09-24 日制裁定）**：{@code tick} 的单位就是**天**（全局 1 tick = 1 天）⇒ 经过的 tick 数即**日数**、绝对
 * tick 即**世界日**。
 *
 * <p>★★ <b>2026-10-12 批次 3d-2：两个战斗耦合档随旧战斗族退役（如实记）</b>——{@code ThresholdKills}（求值读 {@code
 * SdState.lossRecords}）与 {@code OutcomeSelected}（读 {@code SdState.combatStates}）连同那两张表一起删除；理由与
 * "为什么不给另一条路"见 {@link Trigger} 的类注。★ 直接后果：<b>本方法不再需要 {@code SdState} 参数</b>（那两档是它唯一的读者） ⇒ 签名由 5 参收成
 * 4 参，四条存活档（{@code AtOrAfterTick} / {@code AfterTicks} / {@code UnitAtHex} / {@code And} / {@code
 * Or}）的语义一字未改。★ 顺带删掉的 {@code cumulativeKills(SdState)} 也一并消失——它全仓只有本类的 {@code ThresholdKills} 一个读者。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本类自 {@code io.mosire.simos.sd.time} <b>整类迁入本包</b></b>（与 {@link
 * Trigger} 同一个家）——{@code Trigger} 住 ga 之后，留在 sd 的求值器会立刻造出 {@code sd → ga} 的禁边。★ 它是 {@code ga →
 * unit} 那条边的第一个读者（{@code UnitAtHex} 走 {@link UnitState#effectivePosition}）；<b>四条存活档的语义
 * 与签名一字未改</b>（仍是纯函数：{@code (trigger, at, units, sinceTick)}）。
 */
public final class TriggerEvaluator {

  private TriggerEvaluator() {}

  public static boolean evaluate(
      Trigger trigger, SimosTimestamp at, UnitState units, long sinceTick) {
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(units, "units");
    return switch (trigger) {
      case Trigger.AtOrAfterTick t -> at.tick() >= t.tick();
      case Trigger.AfterTicks t -> at.tick() - sinceTick >= t.ticks();
      case Trigger.UnitAtHex t ->
          units.effectivePosition(t.unit(), at).filter(t.hex()::equals).isPresent();
      case Trigger.And t -> {
        for (Trigger child : t.all()) {
          if (!evaluate(child, at, units, sinceTick)) {
            yield false;
          }
        }
        yield true;
      }
      case Trigger.Or t -> {
        for (Trigger child : t.any()) {
          if (evaluate(child, at, units, sinceTick)) {
            yield true;
          }
        }
        yield false;
      }
    };
  }
}
