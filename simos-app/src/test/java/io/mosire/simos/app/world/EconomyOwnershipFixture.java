package io.mosire.simos.app.world;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.app.time.EconomyOwnershipTimeParticipant;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>真协调器推进夹具</b>（S1 阶段 4+5 Task 5）：把"推 N 天"写成 <b>{@link EconomyOwnershipTimeParticipant} 的提案 +
 * 真变更集 apply</b> —— 这正是 Core 做的事（见 {@link PopulationEconomyFixture#advanced} 的同款写法）。
 *
 * <p>★★ <b>为什么本文件必须有</b>（裁定 E7）：产出自本阶段起<b>不再写进阶层行</b> —— 它变成产权条目，落到 {@code ActorData.accounts}
 * 上。而"多日静态入口" {@code EconomySettlement.settle} 已 <b>fail-closed</b> （它没有产权落账口）⇒ 只看 economy
 * 一片的推进路径<b>再也拿不到完整账</b>。凡是要读"产出落到谁账上"的用例， 都必须走这里。
 *
 * <p>★ <b>包内可见</b>（{@code final class} + 私有构造）：它只服务 {@code io.mosire.simos.app.world} 下的用例。
 */
final class EconomyOwnershipFixture {

  private EconomyOwnershipFixture() {}

  /** 推进的结果：两片各自的终态（原子地取自**同一份**提案）。 */
  record Result(EconomyData economy, ActorData actor) {}

  /**
   * 用真协调器从 {@code fromTick + 1} 逐日推到 {@code toTick}（§十一：日循环在参与者内部）。
   *
   * @param economy 结算前的经济状态（必须已在 {@code fromTick} 那一刻）
   * @param actor 落账前的 actor 状态
   */
  static Result advance(
      EconomyData economy, ActorData actor, String mapId, long fromTick, long toTick) {
    SimulationState state =
        new SimulationState(
            new StateMeta(REF, SimosTimestamp.of(fromTick)),
            Map.of(
                "economy", snap(economy, fromTick),
                "actor", actorSnap(actor, fromTick)),
            InMemoryInfoSystem.empty());
    WorldTimeProposal proposal =
        new EconomyOwnershipTimeParticipant(mapId)
            .simulateWorld(
                state,
                new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick))));
    EconomyData nextEconomy =
        EconomyChangeSet.apply((EconomyChangeSet) proposal.moduleChanges().get("economy"), economy);
    ActorData nextActor =
        ActorChangeSet.apply((ActorChangeSet) proposal.moduleChanges().get("actor"), actor);
    return new Result(nextEconomy, nextActor);
  }

  /**
   * 从第 0 天推 {@code days} 天，**只交回经济侧**（本包用例大多只看行侧量：需求 / 债务 / 劳动）。
   *
   * <p>★ 走的仍是**真协调器**（产权条目照落 actor 账、家户账按绝对值落回）—— 只是那些账不在本方法的返回值里。这比绕开协调器 （只跑 {@code
   * EconomyDayStepper}）更可信：**每一条断言量的世界都是"完整账"那个世界**。
   *
   * <p>★★ <b>H1 起 {@code books} 是必填的</b>（裁定 K1/D3-C）：商品库存的唯一真源是 actor 侧的 {@code
   * HouseholdInventory}，日结算的消费与投入都要读它的会话副本 ⇒ 家户 actor 缺席时协调器**当场抛** （旧版的 {@code NO_BOOKS}
   * 常量因此删除：它代表的世界已经不存在了，留着只会把一个必炸的入参摆在手边）。
   */
  static EconomyData advanceEconomy(EconomyData base, ActorData books, String mapId, long days) {
    return advance(base, books, mapId, 0L, days).economy();
  }

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));

  private static Snapshot snap(EconomyData data, long tick) {
    return new EconomySnapshot(REF, SimosTimestamp.of(tick), data);
  }

  private static Snapshot actorSnap(ActorData data, long tick) {
    return new ActorSnapshot(REF, SimosTimestamp.of(tick), data);
  }
}
