package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★★ <b>D-032 T8：N9 的「批量中途失败零部分提交」独立载体</b>（设计书 §14.4 的 N9 行）。
 *
 * <p>★★ <b>判据</b>：一批命令中途有一条被拒 ⇒ <b>整批不落</b>：零 revision、零切片变化，连前面那条<b>合法</b>命令也一
 * 个字节都不落（不是"部分提交"）。本用例走真 {@code CoreSimos.submitBatch}（既有批量口），命令是 production 的实例写面 {@code
 * production.CreateInstance}。
 *
 * <p>★★ <b>为什么这一对命令有判别力</b>：第 1 条是<b>真的会改 production 切片</b>的合法建实例命令（正对照单独提交 ⇒ 恰一条 revision
 * 且实例真的在）；第 2 条缺必填 {@code unit} ⇒ handler 具名拒。若批量口按"逐条提交"实现，第 1 条会先落 ⇒ head +1、实例表多一条 ⇒
 * 本用例的两条断言（head/revision 与 production 片逐值不变）当场红。
 *
 * <p>★ <b>与 E7 账户幂等的关系</b>：这里的"零部分提交"是<b>命令批</b>的原子性（revision 边界的语义）；账户工作副本的"失败即丢弃" 另在 {@code
 * AccountSession} 的类注与 N17/V8 的载体里（两件事不混）。
 */
class N9BatchZeroPartialCommitTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final String NEW_UNIT = "unit-n9-zero-partial";

  @TempDir Path tempDir;

  private GovZ6WorldFixture.World world(String label) {
    return GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve(label), label);
  }

  @Test
  void aMidBatchRejectionLeavesZeroRevisionAndZeroSliceChangeEvenForTheEarlierValidCommand() {
    try (GovZ6WorldFixture.World world = world("n9-batch")) {
      long head0 = world.head();
      Map<String, Snapshot> modules0 = new LinkedHashMap<>(world.state().modules());
      ProductionData productionBefore = productionOf(world.state());
      IndustryId plan = productionBefore.industries().keySet().iterator().next();

      String valid =
          "{\"plan\":\""
              + plan.value()
              + "\",\"unit\":\""
              + NEW_UNIT
              + "\",\"operator\":{\"kind\":\"HOUSEHOLD\",\"id\":\"hh-gov-world-silver\"},"
              + "\"modeKey\":\"n9-batch\"}";
      // 第 2 条：缺必填 unit ⇒ 具名拒（不是"解出 null 再猜"）。
      String invalid =
          "{\"plan\":\""
              + plan.value()
              + "\",\"operator\":{\"kind\":\"HOUSEHOLD\",\"id\":\"hh-gov-world-silver\"},"
              + "\"modeKey\":\"n9-batch\"}";

      BatchResult rejected =
          world
              .core()
              .submitBatch(
                  List.of(
                      envelope(head0, "production.CreateInstance", valid),
                      envelope(head0, "production.CreateInstance", invalid)));

      assertThat(rejected).as("★ 中途拒绝 ⇒ 整批 Rejected").isInstanceOf(BatchResult.Rejected.class);
      List<CommandOutcome> outcomes = ((BatchResult.Rejected) rejected).outcomes();
      assertThat(outcomes).as("逐条结局齐全（一批两条）").hasSize(2);
      assertThat(outcomes.get(0).committed())
          .as("★★ 第 1 条（合法建实例）也没有提交——整批回滚后它同样不落（零部分提交）")
          .isFalse();
      assertThat(((CommandResult.Rejected) outcomes.get(1).result()).reason())
          .as("第 2 条的拒因具名到缺失字段 unit")
          .contains("unit");

      assertThat(world.head()).as("零 revision").isEqualTo(head0);
      assertThat(world.state().modules())
          .as("★★ 零切片变化：production/economy/actor… 全部逐值不变")
          .isEqualTo(modules0);
      assertThat(productionOf(world.state()).instances())
          .as("★ 具体读数：第 1 条要建的那个实例不在权威实例表里")
          .doesNotContainKey(new ProductionUnitId(NEW_UNIT));

      // ── 正对照：同一条合法命令单独提交 ⇒ 恰一条 revision，且实例真的在（证明上面的"不在"不是恒真）──
      BatchResult committed =
          world.core().submitBatch(List.of(envelope(head0, "production.CreateInstance", valid)));
      assertThat(committed)
          .as("正对照必须提交成功: %s", committed)
          .isInstanceOf(BatchResult.Committed.class);
      assertThat(world.head()).as("一条命令 = 一条 revision").isEqualTo(head0 + 1);
      assertThat(productionOf(world.state()).instances())
          .as("正对照确实把实例写进 production（operator 逐值 = 载荷里的那个）")
          .containsKey(new ProductionUnitId(NEW_UNIT))
          .extractingByKey(new ProductionUnitId(NEW_UNIT))
          .satisfies(
              instance ->
                  assertThat(instance.operator().id())
                      .as("显式 operator 逐值落盘（不是按制度重推出来的）")
                      .isEqualTo("hh-gov-world-silver"));
    }
  }

  private static ProductionData productionOf(io.mosire.simos.util.state.SimulationState state) {
    Snapshot snapshot = state.module("production").orElseThrow();
    return ((ProductionSnapshot) snapshot).state();
  }

  private static CommandEnvelope envelope(long expectedRevision, String type, String payload) {
    return new CommandEnvelope(
        "cmd-n9-" + type + "-" + Math.abs(payload.hashCode()),
        "corr-n9-" + expectedRevision,
        "gm:n9-zero-partial",
        MAIN,
        new RevisionId(expectedRevision),
        type,
        payload);
  }
}
