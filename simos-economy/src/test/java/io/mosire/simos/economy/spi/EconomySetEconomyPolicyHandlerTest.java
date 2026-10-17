package io.mosire.simos.economy.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>判据 V7 / N16（铸币权威，D-032 I12）——economy 侧那条腿</b>：铸币产出率的政策键 {@code
 * MINT_MILLI_PER_FURNACE_CYCLE} 自 B2 批起**不再是读取点**（{@code MintSettlement} 不再读它， 产出率的唯一权威 =
 * production 产业定义的 {@code moneyOutputPerUnit}）⇒ 写它必须**具名拒**， 而不是"写进去、无人读"的静默失效。
 *
 * <p>★★ <b>本条判据为什么必须有</b>：静默失效是本仓最禁止的形态之一（表里有值、行为不变 ⇒ GM 以为改了、 世界没变）。判据要的是三件事同时成立：① 具名理由（不是通用拒绝）；②
 * 理由**指路**到真正的写面 （{@code production.UpsertIndustry} 的 {@code moneyOutputPerUnit}）；③
 * **零状态变化**（政策表一字不动）。
 *
 * <p>★ <b>V7/N16 的另外两条腿的载体（本条不重复造）</b>：
 *
 * <ul>
 *   <li>"产出率由 production 定义生效"⇒ {@code time/MintProductionSettlementTest}（{@code moneyOutputPerUnit
 *       = {silver:500}} ⇒ {@code scale 1 × 500 = 500 毫}；逐值 500/1000）；
 *   <li>"发行资格 / 上限 / 记录 / 入账仍按既有玩法"⇒ 同文件的 {@code
 *       mintingACurrencyTheGovernmentCannotIssueFailsClosed}（资格）、 {@code
 *       thePolicyKnobIsTheOnlyCapAndTheExcessIsRejectedByName}（上限 {MINT_LIMIT_REJECTED}）、 {@code
 *       mintIsAProductionProcessWithAuditAndNoMoneyFromNowhere}（发行记录 + 国库入账）。
 * </ul>
 *
 * <p>★ <b>正向对照</b>在下面第二条用例里：**别的**政策键照旧可写 —— 否则"具名拒"可能只是"这台 handler 一律拒"。
 */
class EconomySetEconomyPolicyHandlerTest {

  private static final EconomySetEconomyPolicyHandler HANDLER =
      new EconomySetEconomyPolicyHandler();
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1L));
  private static final SimosTimestamp T7 = SimosTimestamp.of(7L);

  @Test
  void mintRatePolicyKeyIsNamedRejectedWithRedirectAndZeroStateChange() {
    EconomyData base = activated();

    HandlerOutcome outcome =
        HANDLER.handle(
            state(base), "{\"key\":\"MINT_MILLI_PER_FURNACE_CYCLE\",\"valueMilli\":700}");

    assertThat(outcome)
        .as("★ 铸币产出率的政策键已迁 production ⇒ 必须具名拒（不是静默收下、也不是通用拒绝）")
        .isInstanceOf(HandlerOutcome.Rejected.class);
    String reason = ((HandlerOutcome.Rejected) outcome).reason();
    assertThat(reason)
        .as("① 具名理由（唯一拼写点 = EconomySetEconomyPolicyHandler.MINT_RATE_MOVED_REASON）")
        .contains(EconomySetEconomyPolicyHandler.MINT_RATE_MOVED_REASON)
        // ★ 拒因原文不含键名（键名在 ECONOMY_POLICY_MISROUTED 那条 INFO 的 key 字段里）⇒ 这里断"这条理由
        //   指名的是铸币产出率这件事"，而不是断一个原文里没有的串。
        .contains("铸币产出率")
        .as("② 指路到真写面")
        .contains("production.UpsertIndustry")
        .contains("moneyOutputPerUnit")
        .as("③ 说清「写它不会改变任何行为」这一事实（静默失效的反面）")
        .contains("不再是读取点");

    // ④ 零状态变化：政策表逐值不动（不是"拒了但偷偷写了一半"）
    EconomyData after = EconomyData.empty().withMeta(Optional.of(meta()));
    assertThat(after.meta().orElseThrow().economyPolicies())
        .as("具名拒 ⇒ 政策表一字不动（缺键 = 全部走具名缺省）")
        .isEmpty();
    assertThat(after.meta().orElseThrow().policyOf(EconomyPolicyKey.MINT_MILLI_PER_FURNACE_CYCLE))
        .as("读口仍是具名缺省 500 —— 而不是刚被写进去的 700")
        .isEqualTo(EconomyPolicyKey.MINT_MILLI_PER_FURNACE_CYCLE.defaultValue())
        .isEqualTo(500L)
        .isNotEqualTo(700L);
  }

  @Test
  void anotherPolicyKeyIsStillWritableSoTheRejectionIsSpecificToTheMintRate() {
    EconomyData base = activated();

    HandlerOutcome outcome =
        HANDLER.handle(state(base), "{\"key\":\"SLIDE_ROUND_DAYS\",\"valueMilli\":9}");

    assertThat(outcome)
        .as("★ 正向对照：别的政策键照旧可写（证明上一条不是「一律拒」）")
        .isInstanceOf(HandlerOutcome.Applied.class);
    EconomyData after =
        io.mosire.simos.economy.change.EconomyChangeSet.apply(
            (io.mosire.simos.economy.change.EconomyChangeSet)
                ((HandlerOutcome.Applied) outcome).changeSet(),
            base);
    assertThat(after.meta().orElseThrow().policyOf(EconomyPolicyKey.SLIDE_ROUND_DAYS))
        .as("写入逐值生效（9，不是缺省 5）")
        .isEqualTo(9L)
        .isNotEqualTo(EconomyPolicyKey.SLIDE_ROUND_DAYS.defaultValue());
  }

  private static EconomyData activated() {
    return EconomyData.empty().withMeta(Optional.of(meta()));
  }

  private static EconomyMeta meta() {
    return new EconomyMeta(
        "policy-mint-authority",
        7L,
        OptionalLong.empty(),
        EconomyMeta.RULES_VERSION_PRE_MODERN_V1,
        Optional.empty());
  }

  private static SimulationState state(EconomyData data) {
    return new SimulationState(
        new StateMeta(REF, T7),
        Map.of("economy", new EconomySnapshot(REF, T7, data)),
        InMemoryInfoSystem.empty());
  }
}
