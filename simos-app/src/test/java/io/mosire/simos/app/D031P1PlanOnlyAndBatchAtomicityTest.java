package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.tools.write.RegionClearDataTool;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>D-031 P1 §5 V5 收口补测：{@code planOnly} 不写 + region clear/seed 的端到端批原子性</b>（2026-10-16 续单）。
 *
 * <p>★★ <b>为什么补这一条</b>：控制方复核指出设计书 §5 V5 原文点名了「region clear/seed 边界、planOnly 不写」，而 S1 段把 planOnly
 * 记成了"未做"——**判据内的条目不能当"未完成项"收尾**。
 *
 * <p>★★ <b>两条判据各自的判据面</b>：
 *
 * <ul>
 *   <li><b>planOnly 不写</b>：{@code RegionClearDataTool} 的 {@code preview=true}（类注原文「preview
 *       一个字节都不写」；其批内 {@code production.ClearRegionDefinitions} 的类注亦写「planOnly 由 GM 命令层保证不写」）。断三件事：①
 *       预览返回的<b>计划</b> （{@code commands} 类型序 + {@code hitCounts}）与真实提交<b>逐值相同</b>；② 预览后 <b>head /
 *       {@code revisions} 行数 / {@code SimulationState.modules()} 全部切片</b>逐值不变；③ 提交后重跑预览 ⇒
 *       无活可干（证明计划被真的执行）。
 *   <li><b>region clear / seed 的端到端原子性</b>：走<b>真 {@code CoreSimos.submitBatch}</b>（不是 pure handler
 *       层）。中途拒 ⇒ {@link BatchResult.Rejected} + <b>零 revision + 零切片变化</b>；每侧都配一条<b>正对照</b>（去掉坏命令 ⇒
 *       恰一条 revision）， 免得"整批拒绝"的断言恒真。
 * </ul>
 *
 * <p>★ <b>本类住在 {@code io.mosire.simos.app}</b>（不是 {@code app.d031}）：真 GM 工具链的唯一入口 {@code
 * Shell.gmCaller()} 是包内可见，与 {@code LevyRegionToolTest} 同址。
 */
class D031P1PlanOnlyAndBatchAtomicityTest {

  private static final BranchId MAIN = new BranchId("main");

  /** 工具结果视图是 JSON 文本（{@code ToolSupport.ok} 编码）⇒ 用共享 mapper 读回。 */
  private static final ObjectMapper JSON = SimosObjectMapper.create();

  private static final String REASON = "D-031 P1 V5 planOnly/原子性补测";

  /** 首都省（1 格）：标准小世界里**可被真的清空**的那个区。 */
  private static final String CAPITAL_PROVINCE = SmallWorld.CAPITAL_PROVINCE_ID;

  @TempDir Path tempDir;

  /** 当前世界库的 store 目录（{@link #world(String)} 记录，供 {@code revisions} 行数读回）。 */
  private Path storeDir;

  // ── ① planOnly（preview）不写：计划与真实提交一致 + 零 revision / 零切片变化 ──────────────

  @Test
  void regionClearPreviewReturnsTheSamePlanAsTheSubmissionAndThePlanIsReallyExecuted()
      throws Exception {
    try (GovZ6WorldFixture.World world = world("planonly")) {
      long headBefore = world.head();
      long revisionsBefore = revisionRowCount();
      Map<String, Snapshot> modulesBefore = new LinkedHashMap<>(world.state().modules());

      // ── 预览（= planOnly 路径）：一个字节都不写 ──
      ToolResult preview = regionClear(world, CAPITAL_PROVINCE, true, null);
      assertThat(preview.success()).as("预览必须成功: %s", preview.message()).isTrue();
      JsonNode previewView = JSON.readTree(preview.message());
      assertThat(previewView.get("preview").asBoolean()).isTrue();
      assertThat(previewView.get("submitted").asBoolean()).as("预览必须 submitted=false").isFalse();

      List<String> plannedCommands = commandTypes(previewView);
      assertThat(plannedCommands)
          .as("★★ 计划非空且类型序固定（否则后面'计划 == 提交'是空转）")
          .containsExactly(
              "social.ClearRegion",
              "actor.ClearRegion",
              "economy.ClearRegion",
              "production.ClearRegionDefinitions",
              "ga.PutInfo");

      JsonNode hitCounts = previewView.get("hitCounts");
      assertThat(previewView.get("hexCount").asLong()).as("首都省恰 1 格（具体读数）").isEqualTo(1L);
      assertThat(hitCounts.get("social").asLong()).as("social 域命中条数非零").isPositive();
      assertThat(hitCounts.get("economy").asLong()).as("economy 域命中条数非零").isPositive();

      // ── 零 revision + 零切片变化（三条具体读数）──
      assertThat(world.head()).as("预览不得推 head").isEqualTo(headBefore);
      assertThat(revisionRowCount()).as("预览不得留 revision 行").isEqualTo(revisionsBefore);
      assertThat(world.state().modules())
          .as("★★ 预览后【全部切片】逐值不变（不是只看 head）")
          .isEqualTo(modulesBefore);

      // ── 真实提交：返回同一份计划，且真的落一条 revision ──
      ToolResult applied = regionClear(world, CAPITAL_PROVINCE, false, headBefore);
      assertThat(applied.success()).as("提交必须成功: %s", applied.message()).isTrue();
      JsonNode appliedView = JSON.readTree(applied.message());
      assertThat(appliedView.get("preview").asBoolean()).isFalse();
      assertThat(appliedView.get("submitted").asBoolean()).isTrue();
      assertThat(commandTypes(appliedView))
          .as("★★ 预览返回的计划 == 真实提交的计划（类型与顺序逐字相同）")
          .isEqualTo(plannedCommands);
      assertThat(appliedView.get("hitCounts")).as("同一份命中读数（预览说的条数就是提交按着做的那份）").isEqualTo(hitCounts);
      assertThat(appliedView.get("submission").get("result").asText()).isEqualTo("committed");
      assertThat(appliedView.get("submission").get("ref").get("revision").asLong())
          .as("提交落在 headBefore + 1")
          .isEqualTo(headBefore + 1L);

      long headAfter = world.head();
      assertThat(headAfter).as("★ 一批 = 恰一条 revision").isEqualTo(headBefore + 1);
      assertThat(revisionRowCount()).as("★ revisions 行数恰 +1（不是多条）").isEqualTo(revisionsBefore + 1);
      assertThat(world.state().modules())
          .as("提交后状态确实变了（否则'计划被真的执行'没证据）")
          .isNotEqualTo(modulesBefore);

      // ── 计划被真的执行：重跑预览 ⇒ 无活可干 + 该格定义真的没了 ──
      ToolResult afterPreview = regionClear(world, CAPITAL_PROVINCE, true, null);
      assertThat(afterPreview.success()).as("%s", afterPreview.message()).isTrue();
      JsonNode afterView = JSON.readTree(afterPreview.message());
      assertThat(afterView.get("commands")).as("★★ 清空后重跑预览：计划为空（预览说的那些域命中都被真清了）").isEmpty();
      assertThat(afterView.get("message").asText()).as("具名'没有需要清空的数据'").contains("没有需要清空的数据");
      assertThat(capitalHexIndustries(world.state()))
          .as("★ 具体读数：首都格的产业定义已被 production.ClearRegionDefinitions 清掉")
          .isEmpty();
      assertThat(world.head()).as("这一次重跑预览同样不写").isEqualTo(headAfter);
    }
  }

  @Test
  void regionClearOnTheShippedSmallWorldIsRejectedAtomicallyWithNamedReasonAndZeroRevision()
      throws Exception {
    // ★★ 第二条路径（真读数、不是构造的坏载荷）：标准创世小世界的 `small-world` 区**有活可干**（预览排出 5 条命令），
    //    但真提交被 `social.ClearRegion` 具名拒绝 ⇒ 整批回滚：零 revision、零切片变化。
    //    这是 "region clear 中途拒绝 ⇒ 整批不落库" 在真总线上的收口，也是本段发现的一条真实边界（见 findings §7）。
    try (GovZ6WorldFixture.World world = world("planonly-small-world")) {
      long headBefore = world.head();
      long revisionsBefore = revisionRowCount();
      Map<String, Snapshot> modulesBefore = new LinkedHashMap<>(world.state().modules());

      ToolResult preview = regionClear(world, SmallWorld.REGION_ID, true, null);
      assertThat(preview.success()).as("%s", preview.message()).isTrue();
      JsonNode previewView = JSON.readTree(preview.message());
      assertThat(previewView.get("hexCount").asLong())
          .as("small-world 区 = 19 格减去首都格 = 18 格")
          .isEqualTo(SmallWorld.HEX_COUNT - 1L);
      assertThat(commandTypes(previewView)).as("预览认为有活可干（计划非空 ⇒ 后面的'提交却拒'有判别力）").hasSize(5);
      assertThat(world.head()).as("预览不写").isEqualTo(headBefore);
      assertThat(world.state().modules()).isEqualTo(modulesBefore);

      ToolResult applied = regionClear(world, SmallWorld.REGION_ID, false, headBefore);
      JsonNode appliedView = JSON.readTree(applied.message());
      assertThat(appliedView.get("submitted").asBoolean()).as("确实尝试提交了").isTrue();
      assertThat(appliedView.get("submission").get("result").asText())
          .as("★ 整批被拒（不是静默成功）")
          .isEqualTo("rejected");
      JsonNode commands = appliedView.get("submission").get("commands");
      assertThat(commands.get(0).get("type").asText()).isEqualTo("social.ClearRegion");
      assertThat(commands.get(0).get("reason").asText())
          .as("★★ 拒因具名到具体家户与具体成员批次（可复核的最小复现读数）")
          .contains("hh-unit:gov-central")
          .contains("不在 groups 里");
      assertThat(commands.get(1).get("reason").asText()).as("同批其余命令被标为'随整批复原'").contains("随整批复原");

      assertThat(world.head()).as("★★ 中途拒绝 ⇒ 零 revision").isEqualTo(headBefore);
      assertThat(revisionRowCount()).as("revisions 行数不变").isEqualTo(revisionsBefore);
      assertThat(world.state().modules()).as("★★ 零切片变化（全部切片逐值不变）").isEqualTo(modulesBefore);
    }
  }

  @Test
  void regionClearPreviewOnAStaleBaseStillWritesNothing() throws Exception {
    // ★ 第三条路径：先把 head 推到 2，再用**存在但过期**的 expectedRevision=1 预览 ⇒ 预检报风险，但同样一个字节都不写。
    try (GovZ6WorldFixture.World world = world("planonly-stale")) {
      long genesisHead = world.head();
      // 先真落一条 revision（首都省清空）制造"有历史"的世界。
      ToolResult bumped = regionClear(world, CAPITAL_PROVINCE, false, genesisHead);
      assertThat(bumped.success()).as("%s", bumped.message()).isTrue();
      long headBefore = world.head();
      assertThat(headBefore).as("head 已推进").isEqualTo(genesisHead + 1);

      long revisionsBefore = revisionRowCount();
      Map<String, Snapshot> modulesBefore = new LinkedHashMap<>(world.state().modules());

      ToolResult preview = regionClear(world, CAPITAL_PROVINCE, true, genesisHead);
      assertThat(preview.success()).as("%s", preview.message()).isTrue();
      JsonNode view = JSON.readTree(preview.message());
      assertThat(view.get("preview").asBoolean()).isTrue();
      assertThat(view.get("conflictPreflight").get("headMatchesExpected").asBoolean())
          .as("预检必须报出'提交基准与 head 不一致'（具体读数，不是静默）")
          .isFalse();
      assertThat(view.get("conflictPreflight").get("wouldConflict").asBoolean()).isTrue();
      assertThat(view.get("conflictPreflight").get("headRevision").asLong())
          .as("预检报的真实 head")
          .isEqualTo(headBefore);

      assertThat(world.head()).isEqualTo(headBefore);
      assertThat(revisionRowCount()).isEqualTo(revisionsBefore);
      assertThat(world.state().modules()).as("全部切片逐值不变").isEqualTo(modulesBefore);
    }
  }

  // ── ② region clear 端到端原子性（真 submitBatch）────────────────────────────────────

  @Test
  void clearBatchCommitsExactlyOneRevisionAndIsRejectedAtomicallyWhenTheLastCommandIsMalformed()
      throws Exception {
    try (GovZ6WorldFixture.World world = world("clear-batch")) {
      CoreSimos core = world.core();
      long head0 = world.head();
      long revisions0 = revisionRowCount();

      // ── 正对照：两条合法命令 ⇒ 恰一条 revision（证明批量路径本身是通的）──
      //   ★★★ D-032 T6（a 桶夹具欠账 · 命中"N19 三段同批"的不变量）：**两条命令必须指同一批格**。
      //   旧稿给 economy 的是 `small-world`（18 格，不含首都格 0_0）、给 production 的是 `["0_0"]`
      //   —— 于是 production 段退役了 0_0 上的实例，而 economy 段只摘了那 18 格的 relation ⇒ 0_0 的
      //   relation（`unit-farm@0_0-HOUSEHOLD-hh-0_0-rural-landlord`）**悬空** ⇒ 跨片校验器当场具名拒
      //   `economy.production-references 的关系指名的生产实例不存在`（本用例 1 红即此形态）。
      //   ⇒ 改指**同一格**：economy 清 `capital-province`（= 恰含 0_0 的省级辖区）、production 清 `["0_0"]`。
      //   ★ 断言一字未改（仍要求恰一条 revision、正对照 Committed）。★ 这条同时是本用例对
      //     "清区三段必须同批且同格"（N19/G4）的**正向载体**：实例退役 + relation 摘除 + 定义删除同批完成。
      BatchResult committed =
          core.submitBatch(
              List.of(
                  envelope(
                      head0, "economy.ClearRegion", "{\"regionId\":\"" + CAPITAL_PROVINCE + "\"}"),
                  envelope(head0, "production.ClearRegionDefinitions", "{\"hexes\":[\"0_0\"]}")));
      assertThat(committed)
          .as("正对照必须提交成功: %s", committed)
          .isInstanceOf(BatchResult.Committed.class);
      long head1 = world.head();
      long revisions1 = revisionRowCount();
      assertThat(head1).as("两条命令 = 恰一条 revision").isEqualTo(head0 + 1);
      assertThat(revisions1).as("revisions 行数恰 +1").isEqualTo(revisions0 + 1);

      Map<String, Snapshot> modulesAtHead1 = new LinkedHashMap<>(world.stateAt(head1).modules());

      // ── 原子性：第 2 条非法（hexes 不是数组）⇒ 整批拒绝、零 revision、零切片变化 ──
      BatchResult rejected =
          core.submitBatch(
              List.of(
                  // ★ 同上：两条命令指同一批格（`capital-province` ↔ `["0_0"]`），否则第 1 条自己就先被
                  //   跨片校验拒掉，第 2 条"坏载荷"的判别力就没了（本用例要证的是**载荷错**让整批回滚）。
                  envelope(
                      head1, "economy.ClearRegion", "{\"regionId\":\"" + CAPITAL_PROVINCE + "\"}"),
                  envelope(head1, "production.ClearRegionDefinitions", "{\"hexes\":\"0_0\"}")));

      assertThat(rejected).as("★ 中途拒绝 ⇒ 整批 Rejected").isInstanceOf(BatchResult.Rejected.class);
      List<CommandOutcome> outcomes = ((BatchResult.Rejected) rejected).outcomes();
      assertThat(outcomes).as("逐条结局齐全（一批两条）").hasSize(2);
      assertThat(outcomes.get(1).rejected()).as("★ 第 2 条是拒的那一条").isTrue();
      assertThat(((CommandResult.Rejected) outcomes.get(1).result()).reason())
          .as("拒因是真实的载荷错，不是一句通用话")
          .contains("hexes");
      assertThat(outcomes.get(0).committed()).as("★ 第 1 条（合法）也**没有**提交——整批回滚后它同样不落").isFalse();
      assertThat(((CommandResult.Rejected) outcomes.get(0).result()).reason())
          .as("且它的结局是具名的'随整批复原'")
          .contains("随整批复原");

      assertThat(world.head()).as("★★ 整批拒绝 ⇒ 零 revision").isEqualTo(head1);
      assertThat(revisionRowCount()).as("revisions 行数不变").isEqualTo(revisions1);
      assertThat(world.stateAt(head1).modules())
          .as("★★ 零切片变化（economy/production/social… 全部逐值不变）")
          .isEqualTo(modulesAtHead1);
    }
  }

  @Test
  void clearBatchIsRejectedAtomicallyWhenTheBadCommandComesFirst() throws Exception {
    // ★ 换一个方向：坏命令在前、好命令在后 ⇒ 同样整批不落（顺序不改变原子性）。
    try (GovZ6WorldFixture.World world = world("clear-batch-2")) {
      CoreSimos core = world.core();
      long head0 = world.head();
      long revisions0 = revisionRowCount();
      Map<String, Snapshot> modules0 = new LinkedHashMap<>(world.state().modules());

      BatchResult rejected =
          core.submitBatch(
              List.of(
                  envelope(head0, "economy.ClearRegion", "{\"regionId\":\"no-such-region\"}"),
                  envelope(head0, "production.ClearRegionDefinitions", "{\"hexes\":[\"0_0\"]}")));

      assertThat(rejected).isInstanceOf(BatchResult.Rejected.class);
      List<CommandOutcome> outcomes = ((BatchResult.Rejected) rejected).outcomes();
      assertThat(outcomes.get(0).rejected()).isTrue();
      assertThat(((CommandResult.Rejected) outcomes.get(0).result()).reason())
          .as("具名拒因（那个 region 不在 map 里）")
          .contains("no-such-region");
      assertThat(outcomes.get(1).committed()).as("第 2 条合法也不落").isFalse();

      assertThat(world.head()).isEqualTo(head0);
      assertThat(revisionRowCount()).isEqualTo(revisions0);
      assertThat(world.state().modules()).isEqualTo(modules0);
    }
  }

  // ── ③ region seed 端到端原子性（真 submitBatch）+ 批次级候选态校验的整批回滚 ──────────────

  @Test
  void seedBatchIsRejectedAtomicallyWhenALaterConflictingSeedEntryIsRejected() throws Exception {
    try (GovZ6WorldFixture.World world = world("seed-batch")) {
      CoreSimos core = world.core();
      long head0 = world.head();
      long revisions0 = revisionRowCount();
      ProductionData productionBefore = productionOf(world.state());
      String existingIndustryId = productionBefore.industries().keySet().iterator().next().value();

      // 第 1 条合法（全新 id）、第 2 条与既有定义同 id 不同值 ⇒ 冲突拒绝。
      String goodSeed = "{\"industries\":[" + industryJson("d031v5atomic@0_0") + "]}";
      String conflictingSeed =
          "{\"industries\":[" + industryJson(existingIndustryId, 9_999L) + "]}";

      BatchResult rejected =
          core.submitBatch(
              List.of(
                  envelope(head0, "production.Seed", goodSeed),
                  envelope(head0, "production.Seed", conflictingSeed)));

      assertThat(rejected).as("★ 中途冲突拒绝 ⇒ 整批 Rejected").isInstanceOf(BatchResult.Rejected.class);
      List<CommandOutcome> outcomes = ((BatchResult.Rejected) rejected).outcomes();
      assertThat(outcomes.get(1).rejected()).isTrue();
      assertThat(((CommandResult.Rejected) outcomes.get(1).result()).reason())
          .contains("与既有定义冲突")
          .contains(existingIndustryId);
      assertThat(outcomes.get(0).committed()).as("第 1 条合法也不许落（整批回滚）").isFalse();

      assertThat(world.head()).as("★★ 零 revision").isEqualTo(head0);
      assertThat(revisionRowCount()).as("revisions 行数不变").isEqualTo(revisions0);
      ProductionData productionAfter = productionOf(world.state());
      assertThat(productionAfter).as("★★ 零切片变化：production 片逐值不变").isEqualTo(productionBefore);
      assertThat(productionAfter.industries())
          .as("★ 具体读数：第 1 条要播的那个 id 不在定义表里")
          .doesNotContainKey(new IndustryId("d031v5atomic@0_0"));

      // ── 正对照：去掉坏命令 ⇒ 恰一条 revision，且新产业真的在 ──
      BatchResult committed =
          core.submitBatch(List.of(envelope(head0, "production.Seed", goodSeed)));
      assertThat(committed).isInstanceOf(BatchResult.Committed.class);
      assertThat(world.head()).as("一条命令 = 一条 revision").isEqualTo(head0 + 1);
      assertThat(productionOf(world.state()).industries())
          .as("正对照确实把新产业播进去了（证明上一条的'不在'不是恒真）")
          .containsKey(new IndustryId("d031v5atomic@0_0"));
    }
  }

  @Test
  void batchLevelCandidateValidationRejectsTheWholeBatchWhenAModeNamesAMissingStructure()
      throws Exception {
    // ★★ §9.3 的落库前统一校验：handler 故意**不**前置查 classStructureId（批内前向引用）⇒ 单条缺结构由批次级
    //    validator 具名拒绝，且整批零 revision。这是"无效 mode↔阶层结构"在真总线上的收口。
    try (GovZ6WorldFixture.World world = world("mode-structure")) {
      CoreSimos core = world.core();
      long head0 = world.head();
      long revisions0 = revisionRowCount();
      Map<String, Snapshot> modules0 = new LinkedHashMap<>(world.state().modules());

      BatchResult rejected =
          core.submitBatch(
              List.of(
                  envelope(
                      head0,
                      "production.UpsertProductionMode",
                      "{\"id\":\"d031v5ghost@0_0\",\"name\":\"幽灵结构\",\"version\":1,"
                          + "\"classStructureId\":\"cs-does-not-exist\"}")));

      assertThat(rejected)
          .as("★ handler 放行、批次级校验器必须拒（这正是 §9.3 的两段式）")
          .isInstanceOf(BatchResult.Rejected.class);
      List<CommandOutcome> outcomes = ((BatchResult.Rejected) rejected).outcomes();
      assertThat(outcomes.get(0).rejected()).isTrue();
      assertThat(((CommandResult.Rejected) outcomes.get(0).result()).reason())
          .as("拒因具名到那个不存在的结构 id")
          .contains("cs-does-not-exist");

      assertThat(world.head()).as("★★ 零 revision").isEqualTo(head0);
      assertThat(revisionRowCount()).isEqualTo(revisions0);
      assertThat(productionOf(world.state()).modes())
          .as("★★ 零切片变化：幽灵 mode 没落库")
          .doesNotContainKey(new ProductionModeId("d031v5ghost@0_0"));
      assertThat(world.state().modules()).isEqualTo(modules0);

      // ── 正对照：同一 mode 指一个**真实存在**的结构 ⇒ 提交成功、恰一条 revision ──
      String realStructure =
          productionOf(world.state()).modes().values().iterator().next().classStructureId().value();
      BatchResult committed =
          core.submitBatch(
              List.of(
                  envelope(
                      head0,
                      "production.UpsertProductionMode",
                      "{\"id\":\"d031v5real@0_0\",\"name\":\"真结构\",\"version\":1,"
                          + "\"classStructureId\":\""
                          + realStructure
                          + "\"}")));
      assertThat(committed).as("正对照: %s", committed).isInstanceOf(BatchResult.Committed.class);
      assertThat(world.head()).isEqualTo(head0 + 1);
      assertThat(productionOf(world.state()).modes())
          .as("正对照确实落了那条 mode")
          .containsKey(new ProductionModeId("d031v5real@0_0"));
    }
  }

  // ── 夹具与读口 ──────────────────────────────────────────────────────────────────────

  private GovZ6WorldFixture.World world(String label) {
    storeDir = tempDir.resolve(label);
    return GovZ6WorldFixture.smallWorldGenesis(storeDir, "d031-p1-" + label);
  }

  /**
   * 经真 GM 工具链跑一次 {@code simos.region.clearData}（GM 面 ⇒ 自动批准门，与 {@code LevyRegionToolTest} 同一条链）。
   */
  private static ToolResult regionClear(
      GovZ6WorldFixture.World world, String regionId, boolean preview, Long expectedRevision) {
    Shell shell = world.shell();
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("regionId", regionId);
    arguments.put("reason", REASON);
    arguments.put("preview", preview);
    if (expectedRevision != null) {
      arguments.put("expectedRevision", expectedRevision);
    }
    ToolContext gm = Shell.gmCaller();
    ToolContext context =
        new ToolContext(gm.caller(), gm.permissions(), gm.config(), arguments, gm.identity());
    return shell
        .gmToolAuthorizer()
        .execute(shell.toolRegistry(), RegionClearDataTool.NAME, context);
  }

  /** 结果视图里的计划：{@code commands} 的类型序（预览与提交共用这一处读取）。 */
  private static List<String> commandTypes(JsonNode view) {
    List<String> types = new ArrayList<>();
    for (JsonNode command : view.get("commands")) {
      types.add(command.asText());
    }
    return types;
  }

  /** 首都格（0,0）上的产业定义 id 集合（"那一格的定义真的被清掉了"的具体读数）。 */
  private static List<String> capitalHexIndustries(SimulationState state) {
    List<String> ids = new ArrayList<>();
    for (IndustryId id : productionOf(state).industries().keySet()) {
      if (IndustryHexKeys.hexKeyOf(id).filter("0_0"::equals).isPresent()) {
        ids.add(id.value());
      }
    }
    return ids;
  }

  private static ProductionData productionOf(SimulationState state) {
    return ((ProductionSnapshot) state.module("production").orElseThrow()).state();
  }

  /** {@code revisions} 表的真实行数（不是"head 没动"的转述）。 */
  private long revisionRowCount() {
    try (SqliteStore store = SqliteStore.open(storeDir.resolve(CoreSimos.DB_FILE_NAME))) {
      return store.inTransaction(
          connection -> {
            try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM revisions")) {
              return rows.next() ? rows.getLong(1) : 0L;
            }
          });
    }
  }

  private static CommandEnvelope envelope(long expectedRevision, String type, String payload) {
    return new CommandEnvelope(
        "cmd-d031v5-" + type + "-" + expectedRevision + "-" + Math.abs(payload.hashCode()),
        "corr-d031v5-" + expectedRevision,
        "gm:d031-p1-test",
        MAIN,
        new RevisionId(expectedRevision),
        type,
        payload);
  }

  private static String industryJson(String id) {
    return industryJson(id, 7L);
  }

  private static String industryJson(String id, long output) {
    return "{\"id\":\""
        + id
        + "\",\"name\":\"D031 V5 产业\",\"regime\":\"tenant\",\"cycleDays\":1,"
        + "\"capacityPerUnit\":{\"CATTLE\":1},\"dailyInputPerUnit\":{},\"dailyLaborPerUnit\":0,"
        + "\"laborPerUnit\":1000,\"outputPerUnit\":{\"grain\":"
        + output
        + "},\"moneyOutputPerUnit\":{},\"cycleInputPerUnit\":{},"
        + "\"slots\":[{\"id\":\"poor_peasant\",\"name\":\"贫农\",\"laborParticipationPerMille\":1000}],"
        + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":1000,\"laborWeightPerMille\":0}}";
  }
}
