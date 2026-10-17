package io.mosire.simos.economy;

import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionStateLookup;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * ★★ <b>从 {@link SimulationState} 取 production 只读来源的唯一入口</b>（D-031 P1 spec §3.2）。
 *
 * <p>★★ <b>economy 只 import {@code io.mosire.simos.production.contract}</b>（I4/V11）：这里把 production
 * 切片当作契约接口 {@link ProductionDefinitionLookup}，economy <b>不</b> import 任何 production 内部类 （{@code
 * ProductionData} / {@code ProductionSnapshot} / {@code ProductionRuntimeEngine}）。
 *
 * <p>★★ <b>B1 批（D-032 §13.3 第 3 条 / §13.5）收口：{@code runtimeDataOf} 已删除</b>。旧形态让 economy 读
 * production 的<b>内部状态类型</b>（{@code io.mosire.simos.production.ProductionData}）再把那一份交给组合根 {@code
 * new} 引擎 —— 那正是"economy 越界取生产内部状态"。B1 起：
 *
 * <ul>
 *   <li><b>引擎仍由组合根 {@code new}</b>（实现类住 production 非契约包，economy 没有构造权）；
 *   <li><b>基态也由组合根自己造</b>（{@code ProductionData.empty()}）：实例/经验/解锁/效率/覆盖的 <b>读与写一律走 {@link
 *       io.mosire.simos.production.contract.ProductionRuntime} 自己的口</b> （{@code units()} / {@code
 *       probe()} / {@code executeDay()} / {@code definitions()} / {@code changeSet()}），economy
 *       不再需要、也不许直接看见 {@code ProductionData}。
 * </ul>
 *
 * <p>★ <b>装配故障当场炸</b>：缺 production 切片、或切片不实现契约，都抛 {@link IllegalStateException}
 * ——它们不是业务拒绝，也不许降级成空表。
 */
public final class EconomyProductionDefinitions {

  private EconomyProductionDefinitions() {}

  /** 取当刻 production 定义只读来源；缺切片/类型不符 ⇒ 装配故障（fail-closed）。 */
  public static ProductionDefinitionLookup of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot slice =
        state
            .module("production")
            .orElseThrow(() -> new IllegalStateException("state 里没有 production 切片（装配故障：定义权威必须存在）"));
    if (!(slice instanceof ProductionDefinitionLookup definitions)) {
      throw new IllegalStateException(
          "state 的 production 切片未实现 ProductionDefinitionLookup 契约: " + slice.getClass().getName());
    }
    return definitions;
  }

  /**
   * ★★ <b>B2b：取当刻 production 的"实例 + GM 产出覆盖"只读投影</b> （{@code units} / {@code
   * outputQuantityOverrides} 已从 {@code EconomyData} 删除 ⇒ economy 侧那些跨表引用 守卫改读权威候选态）。
   *
   * <p>★ 装配故障的处理与 {@link #of(SimulationState)} 逐字同制：缺切片、或切片不实现契约 ⇒ {@link IllegalStateException}
   * fail-closed（不降级成空表 —— "读不到"与"一个实例都没有"是两件事）。
   */
  public static ProductionStateLookup stateOf(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot slice =
        state
            .module("production")
            .orElseThrow(
                () -> new IllegalStateException("state 里没有 production 切片（装配故障：实例/覆盖权威必须存在）"));
    if (!(slice instanceof ProductionStateLookup production)) {
      throw new IllegalStateException(
          "state 的 production 切片未实现 ProductionStateLookup 契约: " + slice.getClass().getName());
    }
    return production;
  }
}
