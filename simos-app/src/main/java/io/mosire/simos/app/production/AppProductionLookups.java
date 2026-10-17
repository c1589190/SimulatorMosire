package io.mosire.simos.app.production;

import io.mosire.simos.economy.EconomyBoundaryView;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyProductionDefinitions;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.AssetOccupancyLookup;
import io.mosire.simos.economy.time.AssetOccupancyLookupImpl;
import io.mosire.simos.economy.time.ParticipantAttributeLookup;
import io.mosire.simos.economy.time.ProductionWageBook;
import io.mosire.simos.economy.time.ProductionWageQuoteLookup;
import io.mosire.simos.economy.time.UnavailableAssetOccupancyLookup;
import io.mosire.simos.economy.time.UnavailableParticipantAttributeLookup;
import io.mosire.simos.economy.time.UnavailableProductionWageQuoteLookup;
import io.mosire.simos.economy.time.UnavailableWagePayableInventoryLookup;
import io.mosire.simos.economy.time.WagePayableInventoryLookup;
import io.mosire.simos.economy.time.WagePayableInventoryLookupImpl;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * ★★★ <b>D-032 组合根对生产只读依赖的唯一装配点</b>（C3 的替换点；D4-Ib2b 起接真实实现）。
 *
 * <p>本类把同一 revision 的四个只读依赖一次装好：
 *
 * <ul>
 *   <li>D4-C {@link AssetOccupancyLookup} ⇒ {@link AssetOccupancyLookupImpl}（EconomyData 第 36 组件 +
 *       production 的实例/share 投影 + {@link RevisionId}）；
 *   <li>W2a {@link ProductionWageQuoteLookup} ⇒ {@link ProductionWageBook}（EconomyData 第 33/34 组件）；
 *   <li>D4-C {@link WagePayableInventoryLookup} ⇒ {@link WagePayableInventoryLookupImpl}（第 37 组件 +
 *       当刻 {@link AccountSession} 的只读快照）；
 *   <li>D2 §6.2/§7.3 {@link ParticipantAttributeLookup} ⇒ 目前仍只有明确不可用适配器。
 * </ul>
 *
 * <p>★★ <b>无状态、只读</b>：本类不持有切片引用、不写状态、不产生变更。装配只在组合根发生，调用方拿到的是当刻 revision 的 只读适配器；不得跨 revision 复用。
 *
 * <p>★ <b>fail-closed</b>：账户会话未提供时，工资可支付库存读口保持明确的 {@code Unavailable} 语义（由调用方注入 {@code
 * UnavailableWagePayableInventoryLookup}）；本类不构造空余额或第二本账户表。
 */
public final class AppProductionLookups {

  /** 一次装配得到的四个只读依赖。 */
  public record Lookups(
      AssetOccupancyLookup assetOccupancy,
      ProductionWageQuoteLookup wageQuotes,
      ParticipantAttributeLookup participantAttributes,
      WagePayableInventoryLookup wagePayableInventory) {

    public Lookups {
      Objects.requireNonNull(assetOccupancy, "assetOccupancy");
      Objects.requireNonNull(wageQuotes, "wageQuotes");
      Objects.requireNonNull(participantAttributes, "participantAttributes");
      Objects.requireNonNull(wagePayableInventory, "wagePayableInventory");
    }
  }

  private AppProductionLookups() {}

  /** 按当刻 state、生产运行时与账户会话装配真实读口。 */
  public static Lookups forState(
      SimulationState state, ProductionRuntime runtime, AccountSession accounts) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(accounts, "accounts（账户权威会话）");
    return forEconomy(
        economyOf(state),
        EconomyProductionDefinitions.of(state),
        runtime,
        accounts,
        expectedRevision(state),
        state.meta().timestamp().tick());
  }

  /** 显式装配（调用方已持有同一 revision 的 EconomyData/定义/运行时/账户会话）。 */
  public static Lookups forEconomy(
      EconomyData economy,
      ProductionDefinitionLookup definitions,
      ProductionRuntime runtime,
      AccountSession accounts,
      RevisionId revision,
      long tick) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(definitions, "definitions");
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(revision, "revision");
    if (tick < 0L) {
      throw new IllegalArgumentException("AppProductionLookups.tick 不得为负: " + tick);
    }
    EconomyBoundaryView view = EconomyBoundaryView.of(economy, definitions);
    return new Lookups(
        new AssetOccupancyLookupImpl(economy, revision, runtime),
        new ProductionWageBook(view),
        unavailableParticipantAttributes(),
        new WagePayableInventoryLookupImpl(
            economy, tick, new AppAccountInventorySnapshotLookup(accounts)));
  }

  /** 四个读口全部明确的 fail-closed 适配器（账户/占用/报价均不可用时使用；不得回退账户余额）。 */
  public static Lookups unavailable() {
    return new Lookups(
        new UnavailableAssetOccupancyLookup(),
        new UnavailableProductionWageQuoteLookup(),
        new UnavailableParticipantAttributeLookup(),
        new UnavailableWagePayableInventoryLookup());
  }

  /** 显式不可用适配器（仅 D4/W2a 未接或装配 fail-closed 的调用方使用）。 */
  public static ParticipantAttributeLookup unavailableParticipantAttributes() {
    return new UnavailableParticipantAttributeLookup();
  }

  /**
   * 当刻权威 revision（D4-C 快照校验的 {@code expectedRevision}）。
   *
   * <p>★ 取 state 自己的 {@code meta.ref.revision}（唯一拼写点），不新造 revision 类型、不用时钟近似。
   */
  public static RevisionId expectedRevision(SimulationState state) {
    Objects.requireNonNull(state, "state");
    return state.meta().ref().revision();
  }

  /** economy 切片（装配故障 ⇒ fail-closed 抛，不降级成空表）。 */
  private static EconomyData economyOf(SimulationState state) {
    Snapshot slice = state.module("economy").orElse(null);
    if (!(slice instanceof EconomySnapshot snapshot)) {
      throw new IllegalStateException(
          "state 里没有 economy 切片（或类型不符）: " + (slice == null ? "null" : slice.getClass().getName()));
    }
    return snapshot.data();
  }
}
