package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import java.util.Map;

/**
 * ★★ <b>生产"当刻候选态"的跨模块只读投影口</b>（D-032 B2b / §13.11.2 裁定六）。
 *
 * <p>★★ <b>为什么需要它</b>：{@code units} / {@code outputQuantityOverrides} 两个组件已从 {@code EconomyData} 与
 * {@code EconomyChangeSet} 删除 ⇒ economy 侧那些"跨表引用守卫"（关系/经营者条件/劳动配额/
 * 生产组织必须指名一个真实存在的实例；产出覆盖必须指向真实产业与它的配方产出键）需要一份<b>只读</b>的实例与覆盖视图， 而那份状态归
 * production。本接口就是那个投影面：economy 只经 {@code production.contract} 读它（I4）， 不 import 任何 production
 * 实现包（{@code ProductionData} / {@code ProductionSnapshot} / 引擎）。
 *
 * <p>★★ <b>语义边界</b>：
 *
 * <ul>
 *   <li><b>只读</b>：实现返回的必须是冻结的不可变视图；调用方改不动权威（I1）；
 *   <li><b>当刻值</b>：实现返回"调用这一刻的候选态"，不得跨 revision 陈旧（I13）；
 *   <li>它是<b>读</b>口，不含任何推进/建档能力 —— 写一律走 {@link ProductionRuntime#createInstance} / {@link
 *       ProductionRuntime#removeInstance} / {@link ProductionRuntime#reoperateInstance} 与 {@link
 *       ProductionRuntime#registerPlan}。
 * </ul>
 *
 * <p>★ 与 {@link ProductionDefinitionLookup} 的分工：那个回答"方案/生产方式定义是什么"，本接口回答 "现在有哪些实例、GM
 * 覆盖了哪些产出数量"。两者都由 production 切片的同一份状态实现，可以一起取用。
 */
public interface ProductionStateLookup {

  /**
   * 当刻全部生产实例（键 = 实例稳定身份；不可变视图）。
   *
   * <p>★ 与 {@link ProductionRuntime#units()} 逐值同义 —— 一个是运行期契约口、一个是切片快照口。
   */
  Map<ProductionUnitId, ProductionProcess> instances();

  /**
   * 当刻全部 GM 产出数值覆盖（外层键 = 方案 id，内层键 = 商品；不可变视图）。
   *
   * <p>★ 与 {@link ProductionRuntime#outputQuantityOverrides()} 逐值同义。
   */
  Map<IndustryId, Map<CommodityId, Long>> outputQuantityOverrides();
}
