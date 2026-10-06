package io.mosire.simos.economy.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionEfficiencyModifier;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

  /**
   * ★★ <b>P0（2026-10-10）：本会话的迁移人口 outbox</b>（瞬态；保序 = move 执行序）。
   *
   * <p>★★ <b>为什么不进 {@link EconomyData}/变更集/{@code Codec}</b>：人口权威在 Social； {@code
   * ModeMigrationSettlement} 只把"这一天经济腿搬了多少人、从谁到谁"记在这里，由 {@code simos-app} 的 人口—经济协调器在 {@code
   * stepper.step(day)} 之后取走、翻译成 Social 工单，再把 Social 真值 delta 写回经济行。 与 {@link #debtWriteOffs()}
   * 同制：进程内本会话发生额，用完即弃；重启后由同一 plan 重放产生同一批条目。
   */
  private final List<EconomyPopulationTransfer> populationTransfers = new ArrayList<>();

  /**
   * ★★ <b>Z1：本 tick 的修正参数注入集</b>（瞬态；键 = unit id，保序 = 注入列表序）。
   *
   * <p>★★ <b>为什么不进 {@link EconomyData}/变更集/{@code Codec}</b>：机制给出的值必须是<b>已持久化状态的纯函数</b>，
   * 本批不提供持久化修正表（§5.2）。{@code EconomyDayStepper.updateProductionModifiers} 在当日结算前<b>替换</b>本集合； 结算逐
   * tick 消费（周期末按天平均），消费后由 Z2 的结算路径 {@link #clearProductionModifiers()} 清空 —— 机制要连续影响就必须逐 tick 注入。
   */
  private final LinkedHashMap<ProductionUnitId, ProductionEfficiencyModifier> productionModifiers =
      new LinkedHashMap<>();

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
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "flows() 是会话内就地更新的流水累加器（类注：协调器单线程、用完即弃），返回拷贝会切断\"跨日累计\"语义；只读出口是 flowsView()")
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

  /** ★★ P0：记一条迁移人口事实（非 null；保序追加）。 */
  public void recordPopulationTransfer(EconomyPopulationTransfer transfer) {
    populationTransfers.add(Objects.requireNonNull(transfer, "transfer"));
  }

  /** ★★ P0：本次推进已记但尚未被 App 取走的迁移人口 outbox（只读视图，保序 = 执行序）。 */
  public List<EconomyPopulationTransfer> pendingPopulationTransfers() {
    return java.util.Collections.unmodifiableList(populationTransfers);
  }

  /**
   * ★★ P0：App 每日取走并清空 outbox（返回保序快照）。
   *
   * <p>★ 空 ⇒ {@link List#of()}（共享空单例）；非空 ⇒ {@link List#copyOf} 的不可变快照，之后本会话记录清零， 保证"同一条事实只被翻译一次"。
   */
  public List<EconomyPopulationTransfer> drainPendingPopulationTransfers() {
    if (populationTransfers.isEmpty()) {
      return List.of();
    }
    List<EconomyPopulationTransfer> drained = List.copyOf(populationTransfers);
    populationTransfers.clear();
    return drained;
  }

  /**
   * ★★ Z1：本 tick 修正注入集的<b>只读视图</b>（键序 = 注入序）—— 只服务 {@code EconomyDayStepper} 的"每日一条"日志与 app/Z3
   * 只读读数；写口只有 {@link #replaceProductionModifiers(Map)} 与 {@link #clearProductionModifiers()}。
   */
  public Map<ProductionUnitId, ProductionEfficiencyModifier> productionModifiersView() {
    return java.util.Collections.unmodifiableMap(productionModifiers);
  }

  /**
   * ★★ Z1：本 tick 修正注入集的<b>可变视图</b>（包内写口）—— 只服务 {@code EconomyDayStepper} 的替换与 Z2 结算的逐 tick
   * 读取/日末清空。★ 与 {@link #debtWriteOffs()} 同制：不对外公开可变引用。
   */
  LinkedHashMap<ProductionUnitId, ProductionEfficiencyModifier> productionModifiers() {
    return productionModifiers;
  }

  /** ★★ Z1：替换本 tick 的注入集（调用方已判重复/未知 unit；本方法只做"换成这一份"）。 */
  void replaceProductionModifiers(
      Map<ProductionUnitId, ProductionEfficiencyModifier> replacements) {
    productionModifiers.clear();
    productionModifiers.putAll(replacements);
  }

  /** ★★ Z1：当日结算消费后清空注入集（Z2 在日末调用）—— 未再注入的下一日回到 1000‰ 中性。 */
  void clearProductionModifiers() {
    productionModifiers.clear();
  }
}
