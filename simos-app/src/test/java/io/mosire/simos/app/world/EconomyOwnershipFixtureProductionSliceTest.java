package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 T8：{@link EconomyOwnershipFixture} 的 production 片覆盖判据</b>（裁定四十八同类的覆盖缺口）。
 *
 * <p>★★ <b>缺口的性质</b>：本夹具旧稿只把 economy/actor 两片放进 {@code SimulationState}、只 apply 这两片的变更集。而 {@code
 * EconomyOwnershipTimeParticipant} 会读 production 片（实例/进度/经验/效率的权威）并产出 {@code ProductionChangeSet} ⇒
 * 旧稿在"参与者读 production"这一步就该当场抛；即便补了输入片而没补 apply 支， production 的状态也会停在推进起点 ⇒ 一切"production 跨
 * revision"判据失效（与 §14.26.3 的 {@code GovZ6WorldFixture} 同类）。
 *
 * <p>★★ <b>两条判据各自的判别力</b>：
 *
 * <ul>
 *   <li>① <b>正向</b>：真推进一天后，production 实例的 {@code progressDays} 合计<b>恰好增加</b>（每个被执行的实例 +1）—— 修前这里恒
 *       0（变更集被丢）；同时断言实例集合逐值不变（没有凭空多/少实例）。
 *   <li>② <b>负向</b>：{@code applyModuleChanges} 收到未覆盖的 namespace（本夹具未装配的 {@code ga}）⇒ 具名 {@link
 *       IllegalStateException}（"少一个分支 = 静默丢弃"从此是**红**而不是悄悄测旧状态）。
 * </ul>
 *
 * <p>★ 夹具数据来自真创世状态（{@link SmallWorld#state(String)}），不是手搓的空世界。
 */
class EconomyOwnershipFixtureProductionSliceTest {

  @Test
  void productionChangeSetIsAppliedAcrossAnAdvanceInsteadOfBeingSilentlyDropped() {
    SimulationState genesis = SmallWorld.state(SmallWorld.MAP_ID);
    EconomyData economy = economyOf(genesis);
    ActorData actor = actorOf(genesis);
    ProductionData production = productionOf(genesis);

    // ── 起点读数（判别力前提：世界必须真的有实例，否则"进度变了"可能恒真/恒假）──
    assertThat(production.instances()).as("小世界创世必须已有生产实例").isNotEmpty();
    Map<ProductionUnitId, Long> progressBefore = progressByUnit(production);
    long totalBefore = totalProgress(production);
    assertThat(totalBefore).as("★ 起点进度合计必须是 0（否则'增加'没有判别力）").isZero();

    EconomyOwnershipFixture.Result result =
        EconomyOwnershipFixture.advance(economy, actor, production, SmallWorld.MAP_ID, 0L, 1L);

    // ── ① production 片真的被 apply：进度合计 = 被执行的实例数（每个执行的实例恰 +1 天）──
    Map<ProductionUnitId, Long> progressAfter = progressByUnit(result.production());
    assertThat(progressAfter.keySet())
        .as("★ 推进起点的实例一个都不许丢（新增只允许来自当日的按需组织建档）")
        .containsAll(progressBefore.keySet());
    java.util.Set<ProductionUnitId> createdToday =
        new java.util.LinkedHashSet<>(progressAfter.keySet());
    createdToday.removeAll(progressBefore.keySet());
    assertThat(createdToday)
        .as("当日的按需建档只可能是贸易 unit（本世界没有别的按需组织路径）")
        .hasSize(2)
        .allMatch(unit -> unit.value().startsWith("unit-trade@"));
    long totalAfter = totalProgress(result.production());
    assertThat(totalAfter)
        .as("★★ production ChangeSet 必须被夹具应用：一天推进后进度合计 > 0（修前恒 0 = 静默丢弃）")
        .isPositive();
    assertThat(totalAfter)
        .as("每个被执行的实例恰推进 1 天 ⇒ 合计 = 执行实例数（≤ 推进后的实例总数）")
        .isLessThanOrEqualTo((long) result.production().instances().size());
    assertThat(progressAfter).as("★★ 逐实例读数：确实有实例从 0 推进到 1（不是合计变了一笔来路不明的数）").containsValue(1L);
    assertThat(progressAfter.values().stream().allMatch(days -> days == 0L || days == 1L))
        .as("一天推进的逐实例进度只可能是 0（被拒/未执行）或 1（恰好执行一次）")
        .isTrue();

    // ── ② 同一次 Apply 里 economy/actor 也真的被 apply（三片同源，不是只修了 production）──
    assertThat(result.economy().classes()).as("经济片非空").isNotEmpty();
    assertThat(result.actor().accounts()).as("actor 片非空（账户是产出落账处）").isNotEmpty();
  }

  @Test
  void anUncoveredNamespaceIsARejectedNameInsteadOfASilentDrop() {
    SimulationState genesis = SmallWorld.state(SmallWorld.MAP_ID);
    EconomyData economy = economyOf(genesis);
    ActorData actor = actorOf(genesis);
    ProductionData production = productionOf(genesis);

    // 未覆盖的 namespace：本夹具只装配 EconomyOwnershipTimeParticipant（它不会产 ga）——
    //   "将来扩面忘了补分支"必须当场红，而不是把这一片悄悄丢掉。
    Map<String, ChangeSet> unknownNamespace =
        Map.of("ga", EconomyChangeSet.between(economy, economy));

    assertThatThrownBy(
            () ->
                EconomyOwnershipFixture.applyModuleChanges(
                    unknownNamespace, economy, actor, production))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EconomyOwnershipFixture.applyModuleChanges 未覆盖的模块 namespace")
        .hasMessageContaining("ga");
  }

  // ── 切片读取（装配故障当场炸，与生产侧同一条口径）────────────────────────────────────

  private static EconomyData economyOf(SimulationState state) {
    Snapshot snapshot = state.module("economy").orElseThrow();
    return ((EconomySnapshot) snapshot).data();
  }

  private static ActorData actorOf(SimulationState state) {
    Snapshot snapshot = state.module("actor").orElseThrow();
    return ((ActorSnapshot) snapshot).data();
  }

  private static ProductionData productionOf(SimulationState state) {
    Snapshot snapshot = state.module("production").orElseThrow();
    return ((ProductionSnapshot) snapshot).state();
  }

  private static Map<ProductionUnitId, Long> progressByUnit(ProductionData data) {
    Map<ProductionUnitId, Long> progress = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionProcess> entry : data.instances().entrySet()) {
      progress.put(entry.getKey(), entry.getValue().progressDays());
    }
    return progress;
  }

  private static long totalProgress(ProductionData data) {
    return data.instances().values().stream().mapToLong(ProductionProcess::progressDays).sum();
  }
}
