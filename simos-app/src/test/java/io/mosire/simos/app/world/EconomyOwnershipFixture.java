package io.mosire.simos.app.world;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.app.time.EconomyOwnershipTimeParticipant;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.WorldTimeProposal;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.ChangeSet;
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
 * <p>★★ <b>D-032 T8（裁定四十八同类覆盖缺口）：production 片必须真的进状态、真的被 apply</b>。 本夹具旧稿只把 economy/actor 两片交给参与者、只
 * apply 这两片的变更集 ⇒ 参与者产出的 production ChangeSet 被<b>静默丢弃</b> （实例进度/经验/效率全部停在推进起点）⇒ 本夹具下一切"production
 * 跨 revision"判据失效。 现在三片同进同出；{@link #applyModuleChanges} 的 {@code default} 对任何未覆盖 namespace
 * <b>抛错</b>（缺一个分支 = 静默丢弃，见 §14.26.3 的新纪律）。
 *
 * <p>★ <b>包内可见</b>（{@code final class} + 私有构造）：它只服务 {@code io.mosire.simos.app.world} 下的用例。
 */
final class EconomyOwnershipFixture {

  private EconomyOwnershipFixture() {}

  /** 推进的结果：三片各自的终态（原子地取自**同一份**提案）。 */
  record Result(EconomyData economy, ActorData actor, ProductionData production) {}

  /**
   * 用真协调器从 {@code fromTick + 1} 逐日推到 {@code toTick}（§十一：日循环在参与者内部）。
   *
   * @param economy 结算前的经济状态（必须已在 {@code fromTick} 那一刻）
   * @param actor 落账前的 actor 状态
   * @param production 推进前的 production 状态（★ 实例/经验/效率权威；缺它参与者当场抛装配故障）
   */
  static Result advance(
      EconomyData economy,
      ActorData actor,
      ProductionData production,
      String mapId,
      long fromTick,
      long toTick) {
    SimulationState state =
        new SimulationState(
            new StateMeta(REF, SimosTimestamp.of(fromTick)),
            Map.of(
                "economy", snap(economy, fromTick),
                "actor", actorSnap(actor, fromTick),
                "production", productionSnap(production, fromTick)),
            InMemoryInfoSystem.empty());
    WorldTimeProposal proposal =
        new EconomyOwnershipTimeParticipant(mapId)
            .simulateWorld(
                state,
                new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick))));
    return applyModuleChanges(proposal.moduleChanges(), economy, actor, production);
  }

  /**
   * 把一份提案的逐片变更集施加到三片基态上（★ 生产端只此一处；{@code default} 抛错）。
   *
   * <p><b>本夹具的 namespace 覆盖审计</b>（★ 少一个 = 静默丢弃，§14.26.3）：
   *
   * <table border="1">
   *   <caption>namespace → 是否可能出现 → 处置</caption>
   *   <tr><th>namespace</th><th>产出者</th><th>会出现吗</th><th>处置</th></tr>
   *   <tr><td>{@code economy}</td><td>{@code EconomyOwnershipTimeParticipant} 正常分支与早退分支</td><td>会（恒）</td><td>apply</td></tr>
   *   <tr><td>{@code actor}</td><td>同上</td><td>会（恒）</td><td>apply</td></tr>
   *   <tr><td>{@code production}</td><td>同上（正常分支；早退分支不 put = 一字未动）</td><td>会（正常分支）</td><td>apply（缺 Key ⇒ 保持基态）</td></tr>
   *   <tr><td>{@code ga} / {@code army} / {@code map} / {@code calendar} / {@code culture} / {@code social} / {@code unit} / {@code gov}</td><td>本参与者不产出</td><td>不会</td><td><b>抛 {@link IllegalStateException}</b>：将来若参与者扩面，先补这一支再跑</td></tr>
   * </table>
   *
   * <p>★ 缺 {@code production} 键是**合法**的（参与者早退分支：meta 空/无上界 ⇒ 一字未动），不是丢弃； 而**未知** namespace
   * 永远不是合法状态（本夹具只装配这一个参与者，它不会产出别的片）。
   */
  static Result applyModuleChanges(
      Map<String, ChangeSet> moduleChanges,
      EconomyData economy,
      ActorData actor,
      ProductionData production) {
    EconomyData nextEconomy = null;
    ActorData nextActor = null;
    ProductionData nextProduction = null;
    for (Map.Entry<String, ChangeSet> entry : moduleChanges.entrySet()) {
      switch (entry.getKey()) {
        case "economy":
          nextEconomy = EconomyChangeSet.apply((EconomyChangeSet) entry.getValue(), economy);
          break;
        case "actor":
          nextActor = ActorChangeSet.apply((ActorChangeSet) entry.getValue(), actor);
          break;
        case "production":
          nextProduction =
              ProductionChangeSet.apply((ProductionChangeSet) entry.getValue(), production);
          break;
        default:
          throw new IllegalStateException(
              "EconomyOwnershipFixture.applyModuleChanges 未覆盖的模块 namespace: "
                  + entry.getKey()
                  + "（少一个分支 = 静默丢弃该模块的变更集；参与者扩面时必须先补这一支）");
      }
    }
    if (nextEconomy == null || nextActor == null) {
      throw new IllegalStateException(
          "EconomyOwnershipFixture：提案缺 economy/actor 片（参与者契约要求两片恒在）: " + moduleChanges.keySet());
    }
    return new Result(nextEconomy, nextActor, nextProduction == null ? production : nextProduction);
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
   *
   * <p>★★ <b>D-032 T8 起 {@code production} 也是必填的</b>：实例/进度/经验/效率的唯一权威在 production，参与者会读它并产出它的变更集。
   */
  static EconomyData advanceEconomy(
      EconomyData base, ActorData books, ProductionData production, String mapId, long days) {
    return advance(base, books, production, mapId, 0L, days).economy();
  }

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1));

  private static Snapshot snap(EconomyData data, long tick) {
    return new EconomySnapshot(REF, SimosTimestamp.of(tick), data);
  }

  private static Snapshot actorSnap(ActorData data, long tick) {
    return new ActorSnapshot(REF, SimosTimestamp.of(tick), data);
  }

  private static Snapshot productionSnap(ProductionData data, long tick) {
    return new ProductionSnapshot(REF, SimosTimestamp.of(tick), data);
  }
}
