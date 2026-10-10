package io.mosire.simos.economy.time;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>2026-10-10：池侧"未服务量观察"的净额簿 —— ★ 只服务日志读数（不进判据、不改任何数值）</b> （逐轮瞬态：不进 {@code
 * EconomyData}、不进变更集、不落盘）。
 *
 * <p>★★ <b>要解决的问题</b>：{@link MerchantCapacityPool#select} 的 DEBUG 事件 {@code
 * MERCHANT_CAPACITY_LANE_TRUNCATED} 每次调用只报"本次观察"的 {@code unallocatedMilli}，而<b>同一次请求</b> （同一车道键 +
 * 同一对买卖槽）会在<b>多个运力窗口</b>（{@code MARKET_MAX_TRANSPORT_ROUNDS = 4}）与多次试配上被 反复观察 ——
 * 反复观察到的都是<b>同一份</b>没运走的量，逐行相加就把它算了 2~36 遍（把同一车道上量相同的<b>不同</b>请求合并看， 最高 72 次）。 G3f 真实世界实测（A 装置 103
 * 天，48,901 行，离线重算）：逐行相加 = 73,169,862；按请求去重后 = 24,278,096（把不同请求按"车道 + 请求量"合并的更粗口径 = 21,177,007，见
 * {@code .superpowers/sdd/2026-10-10-g3f-verify-net-unserved/impl-ledger.md} §5-1） ⇒ <b>逐行相加是真实量的
 * 3.0~3.45 倍，不能当"真实未承运量"读</b>。
 *
 * <p>★★ <b>身份（与 D-1b 槽侧认领<b>同键同拼写</b>）</b>：请求键 = {@link LaneUnservedBook#laneKey}(发货格, 收货格, 商品) +
 * {@code "|"} + 买槽序 + {@code ">"} + 卖槽序（{@link LaneUnservedBook#pairKey}）。 同一对槽位被反复试配 ⇒
 * 同一个键（同一份量只记一次）；同一车道上<b>另一对</b>槽位观察到的是它们各自的那一份 ⇒ 各记各的（只用车道键会把后者的量并掉 = 漏记）。
 *
 * <p>★★ <b>口径：能不能当真实量（读日志的人只需要这一句）</b>：
 *
 * <pre>
 * Σ 逐行 unallocatedMilli       ❌ 不可当真实量 —— 同一请求被重复观察，实测放大 3.0~3.45 倍
 * Σ unallocatedNetMilli         ✅ 可当真实量 —— "每个请求各自那份未服务量只算一次"的合计
 * unallocatedObservationIndex   = 该请求的第几次观察（1 起；≥2 ⇒ 本行与前面某行是**同一份**量）
 * </pre>
 *
 * ★ 但它<b>不是</b>"车道上物理没运走的货量"：不同买槽会在同一条车道上各请求一份（两份需求可以互相重叠）⇒ 本簿给的是 <b>各请求各自那份未服务量之和</b>；"物理没运走的货量"看
 * D-1b 的槽侧净额（{@link LaneUnservedBook}）。
 *
 * <p>★ <b>与 D-1b 的 {@link LaneUnservedBook} 各自独立、绝不混用</b>：那一份的水位会写进 {@code
 * capacityTruncatedMilli}（价格统计的输入），本簿的结果<b>只进日志字段</b>（见 {@link
 * MerchantCapacityPool#select}）。混用会把"读数去重"写进判据。
 *
 * <p>★ <b>fail-closed（只削重复，不削事实）</b>：{@code netMilli} 只能来自<b>本轮的观察</b>（本次 {@code select}
 * 真正没分出去的量）；已记水位取"该请求历次观察的最大值" ⇒ 后来观察到更大的一份时只补记<b>增量</b>（既不把同一份记两次，也不漏记真的变大）， 净额因此恒 ≤ 该请求当前真正未服务的量。
 *
 * <p>★ <b>不影响结算</b>：本簿只在 {@code demandLeft > 0} 时被写、结果只被日志读；<b>刻意不用 {@code
 * Math.addExact}</b>（日志读数的溢出不得把异常抛进结算链），纯加法。
 *
 * <p>★ <b>确定性（I7）</b>：只有"按键取值 + 按键写值 + 计数"，不迭代 Map、不读时钟/随机/哈希序 ⇒ 值与写入次序无关。 ★
 * <b>线程</b>：生产路径只由协调器单线程触碰（有运力池 ⇒ 区内撮合退回串行）；无请求身份的 4/5 参旧路径（夹具 / 纯状态读者） 只动计数、不写 Map。
 *
 * <p>★★ <b>A3（2026-10-10）分类：<b>降级为只读</b>（保持；它本来就只是读数）</b>—— 设计书 §3.4 列它为
 * "为私有门槛服务的读数"。<b>代码事实</b>（§四）：
 *
 * <pre>
 * 读它的人 {@code MerchantCapacityPool.select} 的三个 DEBUG 字段（netMilli / observationIndex / requestKeyTracked）
 *          + {@code logRoundSummary} 的 INFO 四栏（raw / net / observations / requests / maxObservations）
 * 判据面   **零**：本类 :40-41 原文"不影响结算：只在 demandLeft > 0 时被写、结果只被日志读"；
 *          A3 复核（grep）确认没有任何 return/if 读它的值。
 * </pre>
 *
 * <p>★ 它解决的是"同一份未服务量被反复观察 ⇒ 逐行相加放大 3.0~3.45 倍"这个**读日志的坑**（类注 :10-16）。 A3
 * 保留它的理由：日志面是用户排查的唯一手段（§一.9），删掉它只会让 {@code MERCHANT_CAPACITY_LANE_TRUNCATED} 的净额读数消失；它**不改一个数值**。
 */
final class LaneUnservedObservationBook {

  /** 请求键 → 该请求<b>历次观察到的最大</b>未服务量（毫商品；已记水位）。 */
  private final Map<String, Long> recordedMax = new LinkedHashMap<>();

  /** 请求键 → 该请求已被观察的次数（第几次观察 = 加一后的值）。 */
  private final Map<String, Integer> observations = new LinkedHashMap<>();

  /** Σ 逐行原始读数（含重复观察；日志汇总字段 {@code unallocatedRawMilli}）。 */
  private long rawTotalMilli;

  /** Σ 净额（每份只算一次；日志汇总字段 {@code unallocatedNetMilli}）。 */
  private long netTotalMilli;

  /** 观察行数（= 真的出现过未服务量的 select 次数）。 */
  private long observationRows;

  /** 单个请求被观察的最高次数（重复结构读数；1 = 一次都没重复）。 */
  private int maxObservations;

  /**
   * ★★ <b>记一次"因运力未获服务"的观察</b>（唯一入口；只服务日志）。
   *
   * @param requestKey 请求身份（见类注；{@code null} = 无身份：本次调用自己成一份，净额 ≡ 原始读数、序号恒 1）
   * @param unallocatedMilli 本次观察：这一笔真正没分出去的量（毫商品；&gt; 0）
   * @return 本次的净额 + 观察序号 + 有没有请求身份
   */
  Observation observe(String requestKey, long unallocatedMilli) {
    observationRows++;
    rawTotalMilli += unallocatedMilli;
    if (requestKey == null) {
      // 无请求身份（4/5 参旧路径：夹具 / 纯状态读者）⇒ 每次调用各自成一份；不写 Map（EMPTY 单例可能被多线程碰到）。
      netTotalMilli += unallocatedMilli;
      return new Observation(unallocatedMilli, 1, false);
    }
    long alreadyRecorded = recordedMax.getOrDefault(requestKey, 0L);
    long netMilli = unallocatedMilli > alreadyRecorded ? unallocatedMilli - alreadyRecorded : 0L;
    int index = observations.merge(requestKey, 1, Integer::sum);
    if (unallocatedMilli > alreadyRecorded) {
      recordedMax.put(requestKey, unallocatedMilli);
    }
    netTotalMilli += netMilli;
    maxObservations = Math.max(maxObservations, index);
    return new Observation(netMilli, index, true);
  }

  /** Σ 逐行原始读数（★ 含重复观察，不可当真实量）。 */
  long rawTotalMilli() {
    return rawTotalMilli;
  }

  /** Σ 净额（★ 每份只算一次，可当真实量）。 */
  long netTotalMilli() {
    return netTotalMilli;
  }

  /** 观察行数（本年这一轮里真的出现过未服务量的 select 次数）。 */
  long observationRows() {
    return observationRows;
  }

  /** 不同的请求身份数（★ 无身份的行不计入 —— 那一路径只用于夹具/纯状态读者）。 */
  long requestCount() {
    return observations.size();
  }

  /** 单个请求被观察的最高次数（1 = 都没重复；&gt; 1 就是"同一份量被反复观察"的倍数上界）。 */
  int maxObservations() {
    return maxObservations;
  }

  /**
   * ★ <b>一次观察的读数结果</b>（只被 {@link MerchantCapacityPool#select} 的日志字段读）。
   *
   * @param netMilli 本次净额（0 = 这份量已经记过 ⇒ 不重复计入）
   * @param observationIndex 该请求的第几次观察（1 起；无身份路径恒 1）
   * @param requestKeyTracked 有没有请求身份（{@code false} ⇒ 本次调用自己成一份）
   */
  record Observation(long netMilli, int observationIndex, boolean requestKeyTracked) {}
}
