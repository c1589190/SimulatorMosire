package io.mosire.simos.economy.time;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * ★★ <b>§4.3.4 的"唯一排序器"</b>：{@code 收益率 desc → activityId asc → 稳定 tieId asc}。
 *
 * <p>★★ <b>为什么必须是唯一一份</b>：生产与套利是同一个问题（用户 §1.6），若两个宿主各写一份比较器，某天其中一份改了 tie-break，同一存档的重放次序就会漂开（而"排序 =
 * 内容的纯函数"是不变量 I7/E6 的前提）。故：
 *
 * <ul>
 *   <li>键的<b>唯一拼写点</b> = {@link RankKey} + {@link #compareKeys}；
 *   <li>既有生产排队簿 {@code LaborQueueBook.offerOrder()} 与套利活动都<b>委托</b>到这里（生产那一路的键由 {@code
 *       netPerLaborScaled / rankModeKey / unitId} 投影而来，与改前逐值相同）；
 *   <li>排序用 {@link #rank}：先把键算一遍再排（不是每次比较都现算），因此不引入<u>比较次数相关</u>的不确定性。
 * </ul>
 *
 * <p>★★ <b>禁止 HashMap 迭代序</b>（§4.3.5）：{@link #rank} 只对入参 list 做<b>稳定</b>排序，同键的两项保持入参相对序 ——
 * 调用方因此必须按稳定 id 顺序<b>构建</b> list（各调用点都在注释里写明了自己的构建序）。
 *
 * <p>★ <b>刻度</b>：{@value #PER_LABOR_SCALE} —— 与既有 {@code LaborQueueBook.PER_LABOR_SCALE}
 * 同值同义（百万分之一毫钱 / 单位劳动）。两处常量必须一致，故 {@link #PER_LABOR_SCALE} 是本包内的引用点，{@code LaborQueueBook}
 * 的值不再另写一份算式。
 */
final class ActivitySelector {

  /**
   * 单位劳动净收益的刻度：{@code ⌊net × PER_LABOR_SCALE ÷ labor⌋}。
   *
   * <p>★ 与 {@code LaborQueueBook.PER_LABOR_SCALE} 同值（{@code 1_000_000}）—— 生产与套利的收益率必须在同一把尺上
   * 才可比；两处若漂开，"最划算的先做"就会变成"谁的刻度大谁先做"。
   */
  static final long PER_LABOR_SCALE = 1_000_000L;

  /**
   * 一个活动的排序键。
   *
   * @param yieldScaled 收益率（每单位劳动的净收益，{@link #PER_LABOR_SCALE} 刻度；越大越划算）
   * @param activityId 活动稳定 id（同率时的第一 tie-break；升序）
   * @param tieId 更细的稳定 tie-break（生产 = unit id；套利 = 商品 id，见各调用点；升序）
   */
  record RankKey(long yieldScaled, String activityId, String tieId) {

    RankKey {
      Objects.requireNonNull(activityId, "ActivitySelector.RankKey.activityId 不得为 null");
      Objects.requireNonNull(tieId, "ActivitySelector.RankKey.tieId 不得为 null");
    }
  }

  private ActivitySelector() {}

  /**
   * ★★ <b>键的唯一比较式</b>：收益率降序 → 活动 id 升序 → tieId 升序。
   *
   * <p>★ 三级全定 ⇒ 不存在"同键两项"（活动 id + tieId 在同一家户内唯一），故排序结果与排序算法的稳定性无关。
   */
  static int compareKeys(RankKey left, RankKey right) {
    int byYield = Long.compare(right.yieldScaled(), left.yieldScaled());
    if (byYield != 0) {
      return byYield;
    }
    int byActivity = left.activityId().compareTo(right.activityId());
    if (byActivity != 0) {
      return byActivity;
    }
    return left.tieId().compareTo(right.tieId());
  }

  /**
   * ★★ <b>按唯一排序器重排一份活动清单</b>（纯函数：不改入参 list；返回新 list）。
   *
   * @param items 待排项（调用方保证构建序稳定 —— 排序只在键相等时才依赖它，而三级键已全定）
   * @param keyOf 逐项的排序键投影（每个项只算一次，避免"比较时现算"引入的不确定副作用）
   */
  static <T> List<T> rank(List<T> items, Function<T, RankKey> keyOf) {
    Objects.requireNonNull(items, "ActivitySelector.rank 的 items 不得为 null");
    Objects.requireNonNull(keyOf, "ActivitySelector.rank 的 keyOf 不得为 null");
    List<RankKey> keys = new ArrayList<>(items.size());
    for (T item : items) {
      keys.add(Objects.requireNonNull(keyOf.apply(item), "ActivitySelector 的排序键不得为 null"));
    }
    List<Integer> order = new ArrayList<>(items.size());
    for (int index = 0; index < items.size(); index++) {
      order.add(index);
    }
    order.sort(
        (left, right) -> {
          int byKey = compareKeys(keys.get(left), keys.get(right));
          return byKey != 0 ? byKey : Integer.compare(left, right);
        });
    List<T> ranked = new ArrayList<>(items.size());
    for (int index : order) {
      ranked.add(items.get(index));
    }
    return ranked;
  }
}
