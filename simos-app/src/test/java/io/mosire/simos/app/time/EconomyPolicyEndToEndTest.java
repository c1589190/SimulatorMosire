package io.mosire.simos.app.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.economy.spi.EconomySetEconomyPolicyHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★★ <b>W10（2026-10-11）政策写口的端到端 repo 测试</b>：<b>命令写入 → 推进 ≥ 1 天 → 读回仍在 → 读点按新值走</b>， 全程走 <b>{@code
 * src/main} 的真路径</b>（真 {@code Shell} + 真创世小世界 + 真命令面 + 真逐日结算），不是状态机单测。
 *
 * <p>★★ <b>它补的是哪条缺口</b>：W10-fix 修了两处"政策写入被静默清空"的真缺陷 —— ① {@code EconomySettlement} 逐日重建 {@code
 * EconomyMeta} 时漏带政策表（政策只在写入的那一个 tick 内成立，下一个 advance 之后就不见了）；② {@code
 * LegacyHouseholdMigration.migrateMeta} 同类漏带。①在 W10-fix 里是用<b>真 MCP</b>验的 （账本见 {@code
 * .superpowers/sdd/2026-10-11-w10-scale-unlimited/}），<b>仓内没有测试</b>；本用例把①钉进回归网。 （②的构造期自动迁移路径另由
 * {@code EconomyPolicyMigrationTest} 覆盖。）
 *
 * <p>★★ <b>为什么用 {@link #WIRED_KEY} 而不是随便挑一个键</b>（本批回代码核的实测结论）：{@code EconomyPolicyKey} 有 9
 * 个键，但运行期<b>只有 4 个</b>真有读取点 —— {@code SLIDE_SPEED_PER_MILLE_PER_RATIO} / {@code
 * SLIDE_COST_PER_LEVEL} / {@code SLIDE_ROUND_DAYS}（{@code ClassSlideSettlement} 的一个市场轮读一次）与 {@code
 * MINT_MILLI_PER_FURNACE_CYCLE}（{@code MintSettlement}）。其余 5 个（{@code FAMINE_MAX_PPM_PER_TICK} /
 * {@code MINT_LABOR_SHARE_PER_MILLE} / {@code LOAN_BASE_RATE_PER_MILLE} / {@code
 * MIGRATION_SPEED_CAP_PER_MILLE} / {@code PROFIT_GAP_SPEED_SLOPE_PER_MILLE}）<b>写口可用、读取点仍读编译期常量</b>
 * —— 那是 <b>W10 实现账本 §④.2/④.3 明示的 backlog（不是新缺口）</b>。⇒ 用那 5 个键写"读点按新值走"会<b>空转</b>
 * （表里有值、行为不变），故本用例只挑有读取点的键。
 *
 * <p>★ <b>口径（如实写）</b>：本用例断言到"<b>结算类读政策值的唯一拼写点</b>"这一层 —— {@code
 * EconomyData.economyPolicyOf(key)}（{@code EconomyDayView} 的类注明写它是唯一拼写点，{@code
 * ClassSlideSettlement} 读这三个滑落键就是调它）。<b>没有</b>构造"某个家户真的滑落一级"的夹具 ⇒
 * "改了政策值之后滑落天数真的按新值算"这条<b>下游数值后果未验</b>（见本批账本"我没做/没验证的"）。
 */
class EconomyPolicyEndToEndTest {

  /** 主分支（与 {@link GovZ6WorldFixture#MAIN} 同字面）。 */
  private static final BranchId MAIN = GovZ6WorldFixture.MAIN;

  /** 写入方（initiator 只进 revision 行；本用例不验权限面）。 */
  private static final String INITIATOR = "agent:policy-e2e";

  /**
   * 本用例的政策键：{@code SLIDE_ROUND_DAYS}（阶层滑落的"市场轮天数"，缺省 5）—— <b>有运行期读取点</b>（{@code
   * ClassSlideSettlement} 每个市场轮读一次）。
   */
  private static final EconomyPolicyKey WIRED_KEY = EconomyPolicyKey.SLIDE_ROUND_DAYS;

  /** 写进去的值（≠ 缺省 ⇒ 读回断言有判别力；= 缺省会被命令面具名拒 {@code unchanged}）。 */
  private static final long WIRED_VALUE = 9L;

  /**
   * 第二个<b>有读取点</b>的键（{@code SLIDE_COST_PER_LEVEL}，缺省 1000）—— 它用来验"写缺省 ⇒ <b>删掉那一行</b>，
   * 而<b>另一个键仍在</b>"（防止"写缺省把整张表清空"这种坏法蒙混过关）。
   */
  private static final EconomyPolicyKey SECOND_KEY = EconomyPolicyKey.SLIDE_COST_PER_LEVEL;

  private static final long SECOND_VALUE = 4_000L;

  /**
   * 推进天数：<b>≥ 1 天</b>就能覆盖"逐日结算逐字段重建 meta"（W10-fix 那一处）；取 6 天把 <b>一个市场轮</b>（绝对日 {@code day % 5 ==
   * 0}）一起跨过 —— 滑落三键正是在市场轮末被读的。
   */
  private static final long DAYS = 6L;

  @TempDir Path tempDir;

  @Test
  @DisplayName("W10 政策写口端到端：命令写入 → 推进 6 天 → 读回仍在 + 读点按新值走 + 写缺省删行")
  void policyWrittenThroughTheCommandFaceSurvivesDailySettlement() {
    try (GovZ6WorldFixture.World world =
        GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve("econ-policy"), "policy-e2e")) {

      // ── 前置：创世已激活经济（政策表的宿主在场），且标准农业创世政策 = work-first ────────────────
      //   ★ 非空转守卫：若 meta 缺失，下面的命令会具名拒 not-activated，本用例会红在提交那一步而不是
      //     悄悄变成"对着空表断言空表"。
      //   ★ RF2c：标准农业生产运行时的创世载荷现在显式写 SURVIVAL_IDLE_POLICY=0（BEST_EFFORT_WORK），
      //     所以基线不再是空表；WIRED_KEY（SLIDE_ROUND_DAYS）仍未被配置。
      EconomyData genesis = economy(world);
      EconomyMeta genesisMeta = genesis.meta().orElseThrow();
      assertThat(genesisMeta.economyPolicies())
          .as("★ RF2c：标准农业创世政策表 = work-first 基线（SURVIVAL_IDLE_POLICY=0）")
          .containsExactly(java.util.Map.entry(EconomyPolicyKey.SURVIVAL_IDLE_POLICY, 0L));
      assertThat(genesis.economyPolicyOf(EconomyPolicyKey.SURVIVAL_IDLE_POLICY))
          .as("★ RF2c：标准世界读口 = BEST_EFFORT_WORK(0)")
          .isZero();
      assertThat(EconomyPolicyKey.SURVIVAL_IDLE_POLICY.defaultValue())
          .as("★ RF2c：全局缺省仍为 PREFER_IDLE_WHEN_SUBSISTENCE_UNMET(1)，不得被 seed 静默改掉")
          .isEqualTo(1L);
      assertThat(genesis.economyPolicyConfigured(WIRED_KEY)).as("创世时该键未被配置过").isFalse();
      assertThat(genesis.economyPolicyOf(WIRED_KEY))
          .as("没有政策时读口 = 该键的具名缺省（= 改造前的常量值）")
          .isEqualTo(WIRED_KEY.defaultValue());

      // ── ① 命令写入（真命令面：Shell 注册的 EconomySetEconomyPolicyHandler.TYPE）────────────────
      long headAfterWrite =
          submit(
              world,
              "cmd-policy-write",
              payload(WIRED_KEY, WIRED_VALUE),
              "写入 %s = %d".formatted(WIRED_KEY.name(), WIRED_VALUE));

      // ── ② 写后即读回（同一 revision 生效，不必推进）────────────────────────────────────────
      EconomyData afterWrite = economy(world);
      assertThat(afterWrite.meta().orElseThrow().policyOf(WIRED_KEY))
          .as("写入后该键的读口 = 写入值")
          .isEqualTo(WIRED_VALUE);
      assertThat(afterWrite.economyPolicyConfigured(WIRED_KEY))
          .as("写入非缺省值 ⇒ 该键在政策表里（economyPolicyConfigured 看得见）")
          .isTrue();
      assertThat(afterWrite.meta().orElseThrow().economyPolicies())
          .as("政策表里 = 创世 work-first 基线 + 刚写入的那一个键（只写一个键，不动别的组件）")
          .containsExactlyInAnyOrderEntriesOf(
              java.util.Map.of(EconomyPolicyKey.SURVIVAL_IDLE_POLICY, 0L, WIRED_KEY, WIRED_VALUE));

      // 第二个键（非缺省）—— 给下一步的"写缺省 ⇒ 删行"留一个"另一个键仍在"的对照。
      submit(
          world,
          "cmd-policy-write-2",
          payload(SECOND_KEY, SECOND_VALUE),
          "写入 %s = %d".formatted(SECOND_KEY.name(), SECOND_VALUE));
      assertThat(economy(world).meta().orElseThrow().economyPolicies())
          .as("两个写入键都在表里（外加创世 work-first 基线）")
          .containsOnlyKeys(EconomyPolicyKey.SURVIVAL_IDLE_POLICY, WIRED_KEY, SECOND_KEY);

      // ── ③ 推进 ≥ 1 天：逐日结算会**逐字段重建** EconomyMeta（W10-fix 修的那一处）──────────────
      long fromTick = tickOf(world);
      world.advance(fromTick, fromTick + DAYS);
      assertThat(tickOf(world)).as("真的推进了 %d 天（没推进 ⇒ 下面每条断言都是空转）", DAYS).isEqualTo(fromTick + DAYS);

      EconomyData afterDays = economy(world);
      assertThat(afterDays.meta().orElseThrow().economyPolicies())
          .as(
              "★★ W10-fix：逐日结算重建 meta 必须**原样带过**政策表 —— 缺这一行 ⇒ 政策只在写入的那一个 tick 内成立，"
                  + "下一个 advance 之后就被静默清空（W10-收口批实测 240/240 个结算日全中）")
          .containsEntry(EconomyPolicyKey.SURVIVAL_IDLE_POLICY, 0L)
          .containsEntry(WIRED_KEY, WIRED_VALUE)
          .containsEntry(SECOND_KEY, SECOND_VALUE);

      // ── ④ 读点按新值走（不是回落编译期常量）──────────────────────────────────────────────
      //   economyPolicyOf 是**结算类读政策值的唯一拼写点**（EconomyDayView 类注；ClassSlideSettlement 的三个
      //   滑落键就是调它）⇒ 它返回新值 ⇒ 读取点每市场轮拿到的是政策表里的值，不是常量。
      assertThat(afterDays.economyPolicyOf(WIRED_KEY))
          .as("★ 读点（唯一拼写点）必须按**新值**走")
          .isEqualTo(WIRED_VALUE)
          .isNotEqualTo(WIRED_KEY.defaultValue());
      assertThat(afterDays.economyPolicyConfigured(WIRED_KEY))
          .as("推进后该键仍在表里（configured 为真 ⇒ 读的不是缺省兜底）")
          .isTrue();
      assertThat(afterDays.meta().orElseThrow().rulesVersion())
          .as("推进只改 lastClosedCycle/政策表以外的字段，rulesVersion 不该被顺手改掉")
          .isEqualTo(genesisMeta.rulesVersion());

      // ── ⑤ "写缺省 ⇒ 删行" 经真命令面也成立，且再推进一天不会被重建回来 ──────────────────────
      submit(
          world,
          "cmd-policy-default",
          payload(SECOND_KEY, SECOND_KEY.defaultValue()),
          "把 %s 写回缺省 %d".formatted(SECOND_KEY.name(), SECOND_KEY.defaultValue()));
      assertThat(economy(world).meta().orElseThrow().economyPolicies())
          .as("写回缺省 ⇒ 该行被删掉（不留等于现值的冗余行）；work-first 基线与另一个键不受影响")
          .doesNotContainKey(SECOND_KEY)
          .containsEntry(EconomyPolicyKey.SURVIVAL_IDLE_POLICY, 0L)
          .containsEntry(WIRED_KEY, WIRED_VALUE);

      world.advance(fromTick + DAYS, fromTick + DAYS + 1L);
      EconomyData afterExtraDay = economy(world);
      assertThat(afterExtraDay.meta().orElseThrow().economyPolicies())
          .as("再推进一天：删掉的行不得被重建回来（缺键 = 缺省，不是往表里补一行）")
          .doesNotContainKey(SECOND_KEY)
          .containsEntry(EconomyPolicyKey.SURVIVAL_IDLE_POLICY, 0L)
          .containsEntry(WIRED_KEY, WIRED_VALUE);
      assertThat(afterExtraDay.economyPolicyOf(SECOND_KEY))
          .as("被删的键读口回到具名缺省")
          .isEqualTo(SECOND_KEY.defaultValue());
      assertThat(headAfterWrite).as("写入确实推了一格 revision").isPositive();
    }
  }

  // ── 命令面助手 ────────────────────────────────────────────────────────────────────────

  /** 提交一条 {@code economy.SetEconomyPolicy} 并断言被 commit（返回新 head）。 */
  private static long submit(
      GovZ6WorldFixture.World world, String commandId, String payloadJson, String what) {
    long head = world.head();
    CommandResult result =
        world
            .core()
            .submit(
                new CommandEnvelope(
                    commandId,
                    "corr-" + commandId,
                    INITIATOR,
                    MAIN,
                    new RevisionId(head),
                    EconomySetEconomyPolicyHandler.TYPE,
                    payloadJson));
    assertThat(result)
        .as("%s 必须提交成功（实得 %s）", what, result)
        .isInstanceOf(CommandResult.Committed.class);
    return world.head();
  }

  /** 政策写口的载荷（形状 = {@code {key, valueMilli}}）。 */
  private static String payload(EconomyPolicyKey key, long valueMilli) {
    return "{\"key\":\"" + key.name() + "\",\"valueMilli\":" + valueMilli + "}";
  }

  private static EconomyData economy(GovZ6WorldFixture.World world) {
    SimulationState state = world.core().replay(new StateRef(MAIN, new RevisionId(world.head())));
    return GovZ6WorldFixture.economySlice(state);
  }

  private static long tickOf(GovZ6WorldFixture.World world) {
    return world
        .core()
        .replay(new StateRef(MAIN, new RevisionId(world.head())))
        .meta()
        .timestamp()
        .tick();
  }
}
