package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>D-1b：一条 lane 的"因运力未获服务的量"的净额记账簿</b>（逐轮瞬态：不进 {@code EconomyData}、不进变更集、不落盘）。
 *
 * <p>★★ <b>要解决的问题</b>：同一条 lane 的<b>同一份</b>未服务量会在多个落点、多个时刻被反复观察到，各记一份就会让该槽的 截断读数超过它真正未服务的量 ——
 * 把<b>已服务</b>的那一份也从需求/供给统计里剔掉（V-20 的方向对、幅度错）。实测两例：
 *
 * <pre>
 * ① 区内：买 2,905 / 卖 4,000、运力 1,000（成交 1,000、未服务 1,905）⇒ 承运分配不足的落点被叫了两次
 *    ⇒ 买槽截断 3,810 &gt; 挂单 2,905 ⇒ demand 被剔光、supply 只剩 190 ⇒ 价格 9,500（应为 9,823）
 * ② 跨区：同一条 lane 的跨区路线有 4 个运力窗口，每个窗口再试一次 ⇒ 截断 9,525（两侧都被剔光）
 *    ⇒ demand = supply = 0 ⇒ z = 0 ⇒ 价格**原地不动**（9,500/10,500 的区域信号完全消失）
 * </pre>
 *
 * <p>★★ <b>两级水位</b>（键都由 {@link #laneKey} 拼）：
 *
 * <pre>
 * ① 逐笔配对水位 = 车道键 + "|" + 买槽序 + "&gt;" + 卖槽序
 *    —— 同一对买卖槽被反复试配（这一笔没配到 ⇒ 下一个运力窗口/下一次试配再看一遍）观察到的是**同一份**量；
 *       而同一车道上**另一对**槽位观察到的是它们各自的那一份 ⇒ 各记各的（只用车道键会把后者的量也并掉 = 漏记）。
 * ② 整条车道水位 = 车道键
 *    —— "整条拦下"（{@link MarketSettlement#blockLaneWithoutCapacity}）与"路线窗口预算用尽"（{@code matchRoute} 尾部）
 *       这两处记的是这条车道两侧余量的较小者，与具体是哪一对槽位无关 ⇒ 整条只记一次。
 * </pre>
 *
 * <p>★ <b>级间的相互覆盖</b>：逐笔认领要看"更粗的车道级水位"（整条拦下会把这份量记在<b>所有</b>还有剩余的槽位上，
 * 配对级不该再记第二遍）；整条车道认领要看"逐笔认领之和"（配对级已经把这条 lane 的那份量记掉了时，车道级只剩增量）。 ⇒
 * 两级水位在<b>两个方向</b>上都取"已记过多少"的最大值，重复量因此只可能被记一次。
 *
 * <p>★★ <b>fail-closed（去重只削重复，不削事实）</b>：{@code unservedMilli} 只能来自<b>本轮的观察</b>（本笔真正没被承运的量 /
 * 本车道两侧余量的较小者），不许用推算量 ⇒ 同一次观察记一次、另一次<b>不同</b>的观察照记；截断量因此恒 ≤ 该 lane 实际未服务量， 既不会把已服务的量放回
 * demand，也不会漏记真正的未服务量（实测：两买槽各 2,905、卖 4,000、运力 1,000 ⇒ 逐槽各记 1,000 / 2,000 = 合计 3,000 = 这条 lane
 * 真正的未服务量）。
 *
 * <p>★ <b>确定性（I7）</b>：只有"按键取值 + 按键写值"，值与写入次序无关（{@code LinkedHashMap} 保序，本类不迭代它）； 不读时钟 / 随机 / 哈希序。★
 * <b>线程</b>：只由<b>协调器单线程</b>路径触碰（有运力池 ⇒ {@code matchWithinRegionsSerial}）； worker 副本各持一份全新的空簿（它只走
 * {@code route == null} 的同格意向 ⇒ 到不了运力截断），交回的是槽位终态而不是本簿。
 */
final class LaneUnservedBook {

  /** 键 → 这个键上"已经记过"的量（毫商品）。 */
  private final Map<String, Long> recorded = new LinkedHashMap<>();

  /** 车道键 → 该车道**逐笔配对**已记的量之和（整条车道级的认领要看它，避免与配对级重复）。 */
  private final Map<String, Long> pairRecordedSum = new LinkedHashMap<>();

  /**
   * ★★ <b>一条车道的稳定键</b>（发货格 → 收货格 × 商品）—— {@link MarketSettlement.MarketContext#routes}
   * 与净额记账共用这一处拼写。
   */
  static String laneKey(HexCoord from, HexCoord to, CommodityId commodity) {
    return from + "->" + to + "#" + commodity.value();
  }

  /**
   * ★★ <b>逐笔配对落点认领</b>（承运分配不足时的唯一入口）。
   *
   * @param laneUnservedMilli 本次观察：这一笔真正没被承运的量（毫商品）
   * @return 本次真正记进买卖两侧的量（0 = 这份量已经记过 ⇒ 不重复计入）
   */
  long claimPair(String laneKey, int buyOrderIndex, int sellOrderIndex, long laneUnservedMilli) {
    if (laneUnservedMilli <= 0L) {
      return 0L;
    }
    String pairKey = pairKey(laneKey, buyOrderIndex, sellOrderIndex);
    long alreadyRecorded =
        Math.max(recorded.getOrDefault(laneKey, 0L), recorded.getOrDefault(pairKey, 0L));
    long delta = laneUnservedMilli - alreadyRecorded;
    if (delta <= 0L) {
      return 0L;
    }
    recorded.put(pairKey, laneUnservedMilli);
    pairRecordedSum.merge(laneKey, delta, Math::addExact);
    return delta;
  }

  /**
   * ★★ <b>整条车道落点认领</b>（整条拦下 / 路线窗口预算用尽两处的唯一入口）。
   *
   * @param laneUnservedMilli 本次观察：这条车道两侧余量的较小者（毫商品）
   * @return 本次真正记进买卖两侧的量（0 = 这份量已经记过 ⇒ 不重复计入）
   */
  long claimLane(String laneKey, long laneUnservedMilli) {
    if (laneUnservedMilli <= 0L) {
      return 0L;
    }
    long alreadyRecorded =
        Math.max(recorded.getOrDefault(laneKey, 0L), pairRecordedSum.getOrDefault(laneKey, 0L));
    long delta = laneUnservedMilli - alreadyRecorded;
    if (delta <= 0L) {
      return 0L;
    }
    recorded.put(laneKey, laneUnservedMilli);
    return delta;
  }

  /**
   * ★★ <b>一次请求（一对买卖槽）的稳定键</b>（唯一定义点）—— 槽侧认领（{@link #claimPair}）与池侧读数去重 （{@link
   * LaneUnservedObservationBook}，由 {@code MarketSettlement.executeTrade} 拼好传进池）共用这一处拼写：
   * 两边的"同一份量"必须是同一个键，否则读数与判据会各说各话。★ 只放宽可见性，串本身一字未动。
   */
  static String pairKey(String laneKey, int buyOrderIndex, int sellOrderIndex) {
    return laneKey + "|" + buyOrderIndex + ">" + sellOrderIndex;
  }
}
