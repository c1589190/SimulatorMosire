package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.model.FlowRow;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>一次推进会话（revision 级）</b>：持有 {@link EconomyStateBuilder}（可变工作表）与本期流水累加器， 只在 {@link #build()}
 * 构造一次完整 {@link EconomyData} —— 这就是 P1.5 的"日结算持有可变工作表、 revision 边界构造一次"。
 *
 * <p>★ <b>与 {@code AccountSession} 的分工</b>：{@code AccountSession} 是<b>账户</b>会话（家户/经营者的 商品/货币/冻结，键 =
 * {@code (ActorRef, HexCoord)}，不进状态树）；本类是<b>领域状态</b>会话（产业/行/债务/… 的工作副本，最终进变更集）。两者同生命周期但职责不同。
 *
 * <p>★★ <b>归属（R1 的硬口径）</b>：本对象与其 {@link EconomyStateBuilder} 工作表都是<b>协调器单线程</b>的可变状态 ——
 * 只能由创建它的那一次推进任务访问；并行 worker 只允许读 {@link AccountSnapshot} 与写自己的 {@link
 * AccountIntentBuffer}，<b>不得</b>持有本对象。
 *
 * <p>★ <b>单线程、用完即弃</b>；{@link #base()} 永不被修改。
 */
public final class EconomySession {

  private final EconomyData base;
  private final EconomyStateBuilder sheet;
  private final LinkedHashMap<HouseholdId, FlowRow> flows;

  /**
   * ★★ <b>P5：本会话的死亡按人口比例删债累加器</b>（瞬态；键 = 债务人，值 = Σ 逐笔 {@code forgive} 的本金差）。
   *
   * <p>★★ <b>为什么不进 {@link EconomyData}/变更集/{@code Codec}</b>：删债不是新的持久组件 —— 合同表里该条的 {@code
   * principal} 已经减少、本金为 0 的条处于 {@code FORGIVEN/SETTLED}，这就是权威事实；本表只是<b>本会话发生额</b> 的进程内累加器，供
   * P9/协调器核对守恒式 {@code 债务 = 发行 − 还款 − 删债（利息另列）}。
   */
  private final LinkedHashMap<HouseholdId, Long> debtWriteOffs = new LinkedHashMap<>();

  public EconomySession(EconomyData base) {
    this.base = Objects.requireNonNull(base, "base");
    this.sheet = new EconomyStateBuilder(base);
    this.flows = new LinkedHashMap<>(base.flows());
  }

  public EconomyData base() {
    return base;
  }

  public EconomyStateBuilder sheet() {
    return sheet;
  }

  /** 本期流水累加器（**就地更新**；一次推进会话内跨日累计，{@link #build()} 时挂上）。 */
  public LinkedHashMap<HouseholdId, FlowRow> flows() {
    return flows;
  }

  /** ★★ <b>唯一一次构造</b>（全量守卫在此照跑一次，见 {@link EconomyStateBuilder#build}）。 */
  public EconomyData build() {
    return sheet.build(flows);
  }

  /**
   * ★ <b>当刻预览</b>（会跑全量守卫、构造一个新 {@code EconomyData}）—— 只服务"读口/测试要看当前值"； 日循环内部**不要**每天调它（那会把 P1.5
   * 的收益抵消）。
   */
  public EconomyData preview() {
    return build();
  }

  /** 只读视图（防调用方替换流水表）。 */
  public Map<HouseholdId, FlowRow> flowsView() {
    return java.util.Collections.unmodifiableMap(flows);
  }

  /**
   * ★ P5：删债累加器的**可变视图**（包内写口：只服务 {@code EconomySettlement.applyPopulationChangeInto}）。
   *
   * <p>★ 不对外公开：公开的是 {@link #debtWriteOffsView()}（只读，{@link EconomyDayStepper} 转发给协调器/P9）。
   */
  LinkedHashMap<HouseholdId, Long> debtWriteOffs() {
    return debtWriteOffs;
  }

  /** ★ P5：删债累加器的**只读视图**（键序 = 首次发生序；值 = 该家户累计删债本金）。 */
  public Map<HouseholdId, Long> debtWriteOffsView() {
    return java.util.Collections.unmodifiableMap(debtWriteOffs);
  }
}
