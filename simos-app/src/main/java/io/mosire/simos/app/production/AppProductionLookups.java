package io.mosire.simos.app.production;

import io.mosire.simos.economy.time.AssetOccupancyLookup;
import io.mosire.simos.economy.time.ParticipantAttributeLookup;
import io.mosire.simos.economy.time.ProductionWageQuoteLookup;
import io.mosire.simos.economy.time.UnavailableAssetOccupancyLookup;
import io.mosire.simos.economy.time.UnavailableParticipantAttributeLookup;
import io.mosire.simos.economy.time.UnavailableProductionWageQuoteLookup;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;

/**
 * ★★★ <b>D-032 C3：组合根对生产只读依赖的**唯一装配点**（当前全部是 fail-closed 的 Unavailable 适配器）</b>。
 *
 * <p>★★ <b>为什么需要这一个类</b>：D2/D4-C 把三类跨切片读口冻结成 economy 的接口 ——
 *
 * <ul>
 *   <li>D4-C {@link AssetOccupancyLookup}（资产占用；D4-I 才落真实实现）；
 *   <li>D2 §6.5 {@link ProductionWageQuoteLookup}（工资报价；W2a 才落真实实现）；
 *   <li>D2 §6.2/§7.3 {@link ParticipantAttributeLookup}（参与户属性桥；后续由真实社会属性来源替换）。
 * </ul>
 *
 * <p>组合根必须**显式**把当刻装配注入 {@code EconomyDayStepper}（不得传 null、不得让"缺省"悄悄承担语义）。 本批（C3）三类都还没有真实实现 ⇒
 * 统一注入对应的 {@code Unavailable*} 适配器：它们的返回值是 {@link java.util.Optional#empty()}，调用方必须读成具名业务拒绝（{@code
 * asset-occupancy-unavailable} / {@code wage-quote-missing:<role>} / 角色资格缺事实），<b>绝不当成零</b>。
 *
 * <p>★★ <b>替换点唯一</b>：D4-I 落 {@code AssetOccupancyLookupImpl}、W2a 落真实工资报价后，只改本类的三个常量；
 * 所有装配点（日推进两条路径、只读视图）自动跟随，不会出现"一条路径接了、另一条没接"的分叉。
 *
 * <p>★ <b>无状态、只读</b>：三个适配器都是无状态 final 类；本类不持有任何切片引用、不写任何状态。
 */
public final class AppProductionLookups {

  /** D4-C 资产占用读口（D4-I 前 = 永远 empty）。 */
  public static final AssetOccupancyLookup ASSET_OCCUPANCY = new UnavailableAssetOccupancyLookup();

  /** D2 §6.5 工资报价读口（W2a 前 = 永远 empty ⇒ wage-quote-missing，不按 0）。 */
  public static final ProductionWageQuoteLookup WAGE_QUOTES =
      new UnavailableProductionWageQuoteLookup();

  /** D2 §6.2/§7.3 参与户属性桥（真实属性来源接入前 = 永远 empty ⇒ 需要属性的角色条件 fail-closed）。 */
  public static final ParticipantAttributeLookup PARTICIPANT_ATTRIBUTES =
      new UnavailableParticipantAttributeLookup();

  private AppProductionLookups() {}

  /**
   * 当刻权威 revision（D4-C 快照校验的 {@code expectedRevision}）。
   *
   * <p>★ 取 state 自己的 {@code meta.ref.revision}（唯一拼写点），不新造 revision 类型、不用时钟近似。
   */
  public static RevisionId expectedRevision(SimulationState state) {
    Objects.requireNonNull(state, "state");
    return state.meta().ref().revision();
  }
}
