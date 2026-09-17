package io.mosire.simos.social.population;

import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TemporalSeries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BinaryOperator;

/**
 * 人口序列：**积分型**时态序列（M3 spec §3.2）。
 *
 * <p>与 {@link SegmentedSeries} 的"分段常量"不同，人口在段内**连续变化**：段内单利 `Δp = round(p × rate × Δt)`、跨段复利（每段的基数
 * = 上一段末的人口）、事件在其时刻立刻改变基数。 故 {@link #segments()} 只声明 anchor 这一件分段常量，增长率的分段货真价实地活在 {@link #growth()}
 * 里。
 *
 * <p>★ **不缓存**（C1）：每次 {@link #valueAt} 从 anchor 现算，复杂度 O(段数 + 事件数)。
 *
 * <p>★ 本类型**不物化 per-tick 网格**：tick 网格是"当前段 + 查询点"的纯函数，不是数据结构。
 */
public record PopulationSeries(
    Segment<Long> anchor, SegmentedSeries<Double> growth, List<Event<Long>> events)
    implements TemporalSeries<Long> {

  /** 人口 `ADD` 的加法（M1 纪律：模块级 `static final`，各写各的 lambda 会让往返以"序列不相等"假红）。 */
  public static final BinaryOperator<Long> ADDITION = Long::sum;

  public PopulationSeries {
    if (anchor == null) {
      throw new IllegalArgumentException("anchor 不得为 null");
    }
    if (growth == null) {
      throw new IllegalArgumentException("growth 不得为 null");
    }
    if (events == null) {
      throw new IllegalArgumentException("events 不得为 null");
    }
    List<Event<Long>> frozenEvents = new ArrayList<>(events.size());
    for (Event<Long> event : events) {
      if (event == null) {
        throw new IllegalArgumentException("events 不得含 null");
      }
      frozenEvents.add(event);
    }
    events = Collections.unmodifiableList(frozenEvents);
    if (growth.segments().get(0).from().compareTo(anchor.from()) > 0) {
      throw new IllegalArgumentException(
          "growth 的首段晚于 anchor：anchor 到首段之间无速率可依（不许向前瞎猜，spec §3.2 第 2 条）");
    }
    if (!growth.events().isEmpty()) {
      throw new IllegalArgumentException(
          "growth 不得带事件：速率的变更一律用追加段表达（否则段内积分会按左端点取速率、静默算错，spec §3.2 第 3 条）");
    }
    requireNonDecreasing(events);
  }

  /**
   * ★ **取代说明（计划期）**：spec §3.3 把"事件非递减"记在 {@code SegmentedSeries} 名下——但本类型的事件是**裸 {@code List}**，不经过
   * {@code SegmentedSeries}，那条校验根本不会发生。责任改由本构造器承担，**约束不变**（{@code at} 早于现末尾 ⇒ 抛）。
   */
  private static void requireNonDecreasing(List<Event<Long>> events) {
    for (int i = 1; i < events.size(); i++) {
      if (events.get(i).at().compareTo(events.get(i - 1).at()) < 0) {
        throw new IllegalArgumentException(
            "events 必须按 at 非递减（同刻允许——那正是「同刻多事件」的表达）：第 " + i + " 个事件回退了");
      }
    }
  }

  /** 追加一个增长率段（不改人口、不动 anchor 与 events）。同刻重复由严格升序校验抛。 */
  public PopulationSeries withGrowthSegment(SimosTimestamp at, double rate) {
    List<Segment<Double>> segments = new ArrayList<>(growth.segments());
    segments.add(new Segment<>(at, rate));
    return new PopulationSeries(anchor, new SegmentedSeries<>(segments, List.of(), null), events);
  }

  /** 把事件追加到列表末尾（列表序即施加序）。`at` 早于现末尾 ⇒ 抛。 */
  public PopulationSeries withEvent(Event<Long> event) {
    if (event == null) {
      throw new IllegalArgumentException("event 不得为 null");
    }
    List<Event<Long>> next = new ArrayList<>(events);
    next.add(event);
    return new PopulationSeries(anchor, growth, next);
  }

  /**
   * spec §3.2 的五步算法，逐字实现：
   *
   * <ol>
   *   <li>{@code t < anchor.from()} ⇒ 恒定延拓，返回 anchor 值；
   *   <li>切分点 = anchor 起 ∪ (anchor, t] 内的 growth 段边界 ∪ [anchor, t] 内的事件时刻 ∪ {t}，去重升序；
   *   <li>逐区间 {@code p += Math.round(p × growth.valueAt(区间左端) × 宽度)}；
   *   <li>每个切分点处**先切段（上一步已用新速率）再施事件**，同刻多事件按列表序（ADD 累加 / SET 覆盖）；
   *   <li>返回 {@code p}（不夹取）。
   * </ol>
   */
  @Override
  public Long valueAt(SimosTimestamp t) {
    Objects.requireNonNull(t, "t");
    SimosTimestamp start = anchor.from();
    if (t.compareTo(start) < 0) {
      return anchor.value();
    }
    List<SimosTimestamp> cuts = cutPoints(t);
    long population = anchor.value();
    population = applyEventsAt(population, start); // anchor 那一瞬的事件（若有）
    for (int i = 0; i + 1 < cuts.size(); i++) {
      SimosTimestamp from = cuts.get(i);
      SimosTimestamp to = cuts.get(i + 1);
      double rate = growth.valueAt(from);
      long width = to.tick() - from.tick();
      if (rate != 0.0 && width > 0) {
        population += Math.round(population * rate * width);
      }
      population = applyEventsAt(population, to);
    }
    return population;
  }

  /** 切分点：anchor 起、growth 段边界、事件时刻、查询点本身；去重（按 `compareTo`）升序。 */
  private List<SimosTimestamp> cutPoints(SimosTimestamp t) {
    List<SimosTimestamp> cuts = new ArrayList<>();
    SimosTimestamp start = anchor.from();
    cuts.add(start);
    for (Segment<Double> segment : growth.segments()) {
      if (segment.from().compareTo(start) > 0 && segment.from().compareTo(t) <= 0) {
        cuts.add(segment.from());
      }
    }
    for (Event<Long> event : events) {
      if (event.at().compareTo(start) >= 0 && event.at().compareTo(t) <= 0) {
        cuts.add(event.at());
      }
    }
    cuts.add(t);
    cuts.sort(SimosTimestamp::compareTo);
    List<SimosTimestamp> deduped = new ArrayList<>(cuts.size());
    for (SimosTimestamp cut : cuts) {
      if (deduped.isEmpty() || deduped.get(deduped.size() - 1).compareTo(cut) != 0) {
        deduped.add(cut);
      }
    }
    return deduped;
  }

  /** `at` 时刻的事件，按列表插入序施加。 */
  private long applyEventsAt(long population, SimosTimestamp at) {
    long result = population;
    for (Event<Long> event : events) {
      if (event.at().compareTo(at) != 0) {
        continue;
      }
      result =
          event.mode() == EventMode.ADD ? ADDITION.apply(result, event.value()) : event.value();
    }
    return result;
  }

  /** ★ 本序列唯一的"分段常量"就是 anchor（spec 偏离 1）：人口在段内连续变化，不是分段常量。 */
  @Override
  public List<Segment<Long>> segments() {
    return List.of(anchor);
  }
}
