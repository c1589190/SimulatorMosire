package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.CombatOutcomeId;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.CombatResolution;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.CombatStageId;
import io.mosire.simos.army.CombatUnitLoss;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.army.spi.ResolveCombatStageHandler;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.AdjustCompositionHandler;
import io.mosire.simos.unit.spi.SetStateDescriptionHandler;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.MutationGuard;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.army.resolveCombat}（阶段 D4 / D-009 补裁 + D-010 + D-012）的真命令总线用例：
 *
 * <ul>
 *   <li><b>守恒</b>：显式 outcome 与投骰路径都把选中结局的损失以 {@code unit.AdjustComposition} 精确应用——逐条读 handler
 *       收到的**真载荷**，与记录里该 outcome 的声明损失逐值相等；单位表 after == before + 声明增量；
 *   <li>批内顺序（守卫逼红读真信封序）：{@code AdjustComposition × N → ResolveCombatStage → SetStateDescription ×
 *       M}；
 *   <li>清链接口径（R3）：还有未判定阶段 ⇒ **不清**；本次判定后全部阶段已判定 ⇒ 同一批显式清除；
 *   <li>投骰可复现：无 seed ⇒ {@code CombatResolution.deriveSeed} 派生（同状态同参数两次 preview 同 seed/同结局）， 选中结局与独立
 *       {@link Random} 预言机一致；
 *   <li>preview 零写入、同阶段重复判定拒、损失指向不存在单位在提交前具名拒。
 * </ul>
 */
class ResolveCombatToolTest {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId OTHER = new UnitId("u-2");
  private static final CombatRecordId C1 = new CombatRecordId("c-1");
  private static final CombatStageId START = new CombatStageId("start");
  private static final CombatStageId S2 = new CombatStageId("s2");

  private static final String ADDRESS = "army:combat.c-1";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  // ── 显式 outcome：守恒 + 清链接 ───────────────────────────────────────────────────────

  @Test
  void explicitOutcomeAppliesDeclaredLossesExactlyAndClearsTheLink() throws Exception {
    try (Fixture fx = singleStageFixture(tempDir.resolve("explicit"))) {
      ToolResult result = fx.call(resolveArgs("c-1", "start", "win", null, 1L, Boolean.FALSE));

      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode view = JSON.readTree(result.message());
      assertThat(view.get("outcomeProvided").asBoolean()).isTrue();
      assertThat(view.get("seedProvided").asBoolean()).isFalse();
      assertThat(view.get("seed").isNull()).as("显式结局不投骰 ⇒ rollSeed 空").isTrue();
      assertThat(view.get("allStagesResolvedAfter").asBoolean()).isTrue();
      assertThat(values(view.get("clearedLinks"))).containsExactly("u-1");

      assertThat(fx.handlerCalls)
          .as("批内真执行序：先逐单位应用损失，再更新记录，最后清链接")
          .containsExactly(
              AdjustCompositionHandler.TYPE,
              ResolveCombatStageHandler.TYPE,
              SetStateDescriptionHandler.TYPE);
      assertThat(fx.head()).isEqualTo(2L);
      assertThat(fx.storedRevisions()).hasSize(2);

      // ★ 守恒：handler 收到的真载荷 == 记录里该 outcome 声明的损失。
      JsonNode applied = JSON.readTree(fx.callPayload(AdjustCompositionHandler.TYPE));
      assertThat(applied.get("id").asText()).isEqualTo("u-1");
      assertThat(deltas(applied.get("manpower"))).containsExactly("步兵=-30");
      assertThat(deltas(applied.get("equipment"))).containsExactly("步枪=-5");

      SimulationState after = fx.stateAt(2L);
      CombatStage stage = fx.record(after, C1).stages().get(0);
      assertThat(stage.resolved()).isTrue();
      assertThat(stage.selectedOutcomeId()).contains(new CombatOutcomeId("win"));
      assertThat(stage.rollSeed()).as("显式结局不投骰 ⇒ rollSeed 空").isEmpty();
      CombatUnitLoss declared = stage.selectedOutcome().orElseThrow().losses().get(0);
      assertThat(deltas(applied.get("manpower")))
          .isEqualTo(
              List.of(
                  declared.manpower().get(0).type() + "=" + declared.manpower().get(0).amount()));
      assertThat(deltas(applied.get("equipment")))
          .isEqualTo(
              List.of(
                  declared.equipment().get(0).type() + "=" + declared.equipment().get(0).amount()));

      Unit unit = fx.unit(after, U1);
      assertThat(entries(unit.manpower())).as("100 + (-30) = 70").containsExactly("步兵=70");
      assertThat(entries(unit.equipment())).as("50 + (-5) = 45").containsExactly("步枪=45");
      assertThat(unit.stateDescriptions())
          .as("全部阶段判定完 ⇒ 同一批清除 combat 链接")
          .doesNotContainKey("combat");
    }
  }

  @Test
  void emptyLossEntriesProduceNoAdjustCompositionCommand() throws Exception {
    try (Fixture fx =
        fixtureWithDeclaredLosses(tempDir.resolve("empty-loss"), List.of(), List.of())) {
      ToolResult result = fx.call(resolveArgs("c-1", "start", "win", null, 1L, Boolean.FALSE));
      assertThat(result.success()).as(result.message()).isTrue();
      assertThat(fx.handlerCalls)
          .as("没有实际变动的单位不生成 unit.AdjustComposition（只更新记录 + 清链接）")
          .containsExactly(ResolveCombatStageHandler.TYPE, SetStateDescriptionHandler.TYPE);
      assertThat(entries(fx.unit(fx.stateAt(2L), U1).manpower())).containsExactly("步兵=100");
    }
  }

  // ── 投骰：派生 seed 可复现 + 选中结局与独立 Random 预言机一致 ────────────────────────────

  @Test
  void rolledOutcomeIsReproducibleAndAppliedFromTheRolledOutcomeOnly() throws Exception {
    List<CombatOutcome> table =
        List.of(
            outcome("o1", "胜", 3, List.of(new CompositionDelta("步兵", -30))),
            outcome("o2", "败", 7, List.of(new CompositionDelta("步兵", -10))));
    try (Fixture fx = fixtureWithOutcomes(tempDir.resolve("roll"), table)) {
      ToolResult first = fx.call(resolveArgs("c-1", "start", null, null, null, null));
      ToolResult second = fx.call(resolveArgs("c-1", "start", null, null, null, null));

      JsonNode a = JSON.readTree(first.message());
      JsonNode b = JSON.readTree(second.message());
      assertThat(first.success()).as(first.message()).isTrue();
      assertThat(a.get("seedProvided").asBoolean()).isFalse();
      long seed = a.get("seed").asLong();
      assertThat(b.get("seed").asLong()).as("同状态同参数两次 preview ⇒ 同派生 seed").isEqualTo(seed);
      assertThat(b.get("outcome").get("id").asText())
          .isEqualTo(a.get("outcome").get("id").asText());
      assertThat(seed)
          .as("派生 seed = 独立复算的 FNV-1a(combatId|stageId|tick|id:weight;…)")
          .isEqualTo(fnv1a64("c-1|start|7|o1:3;o2:7;"));
      assertThat(seed)
          .as("且与域里的唯一判定算法 CombatResolution.deriveSeed 同值（工具没有自搓一套）")
          .isEqualTo(CombatResolution.deriveSeed(C1, START, 7L, table));

      // 独立预言机：按"total=Σweight、nextLong(total) 逐项减 weight"的文档口径复算选中结局。
      long total = table.stream().mapToLong(CombatOutcome::weight).sum();
      long pick = new Random(seed).nextLong(total);
      CombatOutcome expected = null;
      for (CombatOutcome outcome : table) {
        pick -= outcome.weight();
        if (pick < 0L) {
          expected = outcome;
          break;
        }
      }
      assertThat(expected).isNotNull();
      assertThat(a.get("outcome").get("id").asText())
          .as("选中结局必须与独立 Random 预言机一致")
          .isEqualTo(expected.id().value());

      // apply 一轮：应用量 == 记录里选中结局的声明量（守恒）。
      fx.handlerCalls.clear();
      Map<String, Object> settlementArgs =
          resolveArgs("c-1", "start", null, seed, 1L, Boolean.FALSE);
      ToolResult applied = fx.call(settlementArgs);
      assertThat(applied.success()).as(applied.message()).isTrue();
      JsonNode appliedPayload = JSON.readTree(fx.callPayload(AdjustCompositionHandler.TYPE));
      assertThat(deltas(appliedPayload.get("manpower")))
          .containsExactlyElementsOf(declaredAmounts(expected.losses().get(0).manpower()));

      SimulationState after = fx.stateAt(2L);
      CombatStage stage = fx.record(after, C1).stages().get(0);
      assertThat(stage.rollSeed()).contains(seed);
      assertThat(stage.selectedOutcomeId()).contains(expected.id());
      assertThat(entries(fx.unit(after, U1).manpower()))
          .isEqualTo(List.of("步兵=" + (100L + expected.losses().get(0).manpower().get(0).amount())));
    }
  }

  // ── 多阶段：未判完不清链接；判完才清 ─────────────────────────────────────────────────

  @Test
  void multiStageKeepsTheLinkUntilTheLastStageIsResolved() throws Exception {
    try (Fixture fx = twoStageFixture(tempDir.resolve("multi"))) {
      ToolResult first = fx.call(resolveArgs("c-1", "start", "o1", null, 1L, Boolean.FALSE));
      assertThat(first.success()).as(first.message()).isTrue();
      JsonNode firstView = JSON.readTree(first.message());
      assertThat(firstView.get("allStagesResolvedAfter").asBoolean()).as("s2 未判定 ⇒ 不清链接").isFalse();
      assertThat(values(firstView.get("clearedLinks"))).isEmpty();
      assertThat(fx.handlerCalls)
          .as("没有清链接命令")
          .containsExactly(AdjustCompositionHandler.TYPE, ResolveCombatStageHandler.TYPE);
      assertThat(fx.unit(fx.stateAt(2L), U1).stateDescriptions())
          .as("R3：链接保留至显式清除")
          .containsExactly(Map.entry("combat", ADDRESS));

      fx.handlerCalls.clear();
      ToolResult second = fx.call(resolveArgs("c-1", "s2", "o2", null, 2L, Boolean.FALSE));
      assertThat(second.success()).as(second.message()).isTrue();
      JsonNode secondView = JSON.readTree(second.message());
      assertThat(secondView.get("allStagesResolvedAfter").asBoolean()).isTrue();
      assertThat(values(secondView.get("clearedLinks"))).containsExactly("u-1");
      assertThat(fx.handlerCalls)
          .as("最后一阶段判完 ⇒ 同批追加清链接")
          .containsExactly(
              AdjustCompositionHandler.TYPE,
              ResolveCombatStageHandler.TYPE,
              SetStateDescriptionHandler.TYPE);
      assertThat(fx.unit(fx.stateAt(3L), U1).stateDescriptions()).doesNotContainKey("combat");
      assertThat(fx.head()).as("两批 = 两条 revision").isEqualTo(3L);
    }
  }

  // ── 失败具名 / preview ──────────────────────────────────────────────────────────────

  @Test
  void repeatingTheSameStageIsRejectedWithZeroWrites() throws Exception {
    try (Fixture fx = singleStageFixture(tempDir.resolve("repeat"))) {
      assertThat(fx.call(resolveArgs("c-1", "start", "win", null, 1L, Boolean.FALSE)).success())
          .isTrue();
      long headAfterFirst = fx.head();
      int callsAfterFirst = fx.handlerCalls.size();

      ToolResult again =
          fx.call(resolveArgs("c-1", "start", "win", null, headAfterFirst, Boolean.FALSE));
      assertThat(again.success()).isFalse();
      assertThat(again.code()).isEqualTo("BAD_REQUEST");
      assertThat(again.message()).contains("阶段已判定过").contains("start");
      assertThat(fx.head()).as("重复判定 ⇒ 零 revision").isEqualTo(headAfterFirst);
      assertThat(fx.handlerCalls).as("重复判定在提交前就被拦下").hasSize(callsAfterFirst);
    }
  }

  @Test
  void explicitOutcomeInconsistentWithSeedIsRejectedWithZeroWrites() throws Exception {
    List<CombatOutcome> table =
        List.of(
            outcome("o1", "胜", 3, List.of(new CompositionDelta("步兵", -30))),
            outcome("o2", "败", 7, List.of(new CompositionDelta("步兵", -10))));
    try (Fixture fx = fixtureWithOutcomes(tempDir.resolve("inconsistent"), table)) {
      long seed = 12345L;
      long total = table.stream().mapToLong(CombatOutcome::weight).sum();
      long pick = new Random(seed).nextLong(total) - table.get(0).weight();
      String rolledId = pick < 0L ? "o1" : "o2";
      String inconsistentId = rolledId.equals("o1") ? "o2" : "o1";

      ToolResult result =
          fx.call(resolveArgs("c-1", "start", inconsistentId, seed, 1L, Boolean.FALSE));
      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("BAD_REQUEST");
      assertThat(result.message()).contains("显式结局与 seed 不一致");
      assertThat(fx.head()).as("零 revision").isEqualTo(1L);
    }
  }

  @Test
  void lossPointingAtAMissingUnitIsRejectedBeforeAnyWrite() throws Exception {
    // 记录允许引用不存在单位（历史语义），但结算时要落 unit.AdjustComposition ⇒ plan 前置具名拒。
    CombatOutcome ghostLoss =
        new CombatOutcome(
            new CombatOutcomeId("win"),
            "胜",
            1,
            List.of(new CombatUnitLoss(OTHER, List.of(new CompositionDelta("步兵", -1)), List.of())));
    try (Fixture fx = fixtureWithOutcomes(tempDir.resolve("missing"), List.of(ghostLoss))) {
      ToolResult result = fx.call(resolveArgs("c-1", "start", "win", null, null, null));
      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("BAD_REQUEST");
      assertThat(result.message()).contains("损失指向不存在的单位").contains("u-2");
      assertThat(fx.head()).as("前置具名拒 ⇒ 零 revision").isEqualTo(1L);
      assertThat(fx.handlerCalls).isEmpty();
    }
  }

  @Test
  void previewShowsTheSettlementWithoutWritingAnything() throws Exception {
    try (Fixture fx = singleStageFixture(tempDir.resolve("preview"))) {
      ToolResult result = fx.call(resolveArgs("c-1", "start", "win", null, null, null));
      assertThat(result.success()).as(result.message()).isTrue();
      JsonNode view = JSON.readTree(result.message());
      assertThat(view.get("preview").asBoolean()).isTrue();
      assertThat(view.get("submitted").asBoolean()).isFalse();
      assertThat(view.get("allStagesResolvedAfter").asBoolean()).isTrue();
      assertThat(values(view.get("clearedLinks"))).containsExactly("u-1");
      assertThat(deltas(view.get("losses").get(0).get("manpower"))).containsExactly("步兵=-30");
      assertThat(view.get("losses").get(0).get("unit").asText()).isEqualTo("u-1");
      assertThat(view.get("losses").get(0).get("empty").asBoolean()).isFalse();

      assertThat(fx.head()).isEqualTo(1L);
      assertThat(fx.handlerCalls).isEmpty();
      assertThat(entries(fx.unit(fx.stateAt(1L), U1).manpower())).containsExactly("步兵=100");
      assertThat(fx.unit(fx.stateAt(1L), U1).stateDescriptions())
          .containsExactly(Map.entry("combat", ADDRESS));
      assertThat(fx.record(fx.stateAt(1L), C1).stages().get(0).resolved()).isFalse();
    }
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static Fixture singleStageFixture(Path dir) {
    return fixtureWithDeclaredLosses(
        dir, List.of(new CompositionDelta("步兵", -30)), List.of(new CompositionDelta("步枪", -5)));
  }

  private static Fixture fixtureWithDeclaredLosses(
      Path dir, List<CompositionDelta> manpower, List<CompositionDelta> equipment) {
    List<CombatUnitLoss> losses = List.of(new CombatUnitLoss(U1, manpower, equipment));
    CombatOutcome win = new CombatOutcome(new CombatOutcomeId("win"), "胜", 1, losses);
    CombatOutcome lose =
        new CombatOutcome(
            new CombatOutcomeId("lose"),
            "败",
            1,
            List.of(new CombatUnitLoss(U1, List.of(), List.of())));
    return fixtureWithOutcomes(dir, List.of(win, lose));
  }

  private static Fixture fixtureWithOutcomes(Path dir, List<CombatOutcome> outcomes) {
    ArmyData army =
        armyWith(record(List.of(U1), List.of(stage("start", "初始阶段", List.of(U1), outcomes))));
    return Fixture.open(dir, unitState(Map.of("combat", ADDRESS)), army);
  }

  private static Fixture twoStageFixture(Path dir) {
    CombatStage start =
        stage(
            "start",
            "初始阶段",
            List.of(U1),
            List.of(outcome("o1", "接触", 1, List.of(new CompositionDelta("步兵", -5)))));
    CombatStage s2 =
        stage(
            "s2",
            "决战",
            List.of(U1),
            List.of(outcome("o2", "破城", 1, List.of(new CompositionDelta("步兵", -7)))));
    ArmyData army = armyWith(record(List.of(U1), List.of(start, s2)));
    return Fixture.open(dir, unitState(Map.of("combat", ADDRESS)), army);
  }

  private static Map<String, Object> resolveArgs(
      String combatId,
      String stageId,
      String outcomeId,
      Long seed,
      Long expectedRevision,
      Boolean preview) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("combatId", combatId);
    args.put("stageId", stageId);
    if (outcomeId != null) {
      args.put("outcomeId", outcomeId);
    }
    if (seed != null) {
      args.put("seed", seed);
    }
    if (expectedRevision != null) {
      args.put("expectedRevision", expectedRevision);
    }
    if (preview != null) {
      args.put("preview", preview);
    }
    return args;
  }

  /** 与设计文档同口径、独立实现的 FNV-1a 64（给"派生 seed"当外部预言机）。 */
  private static long fnv1a64(String text) {
    long hash = 0xcbf29ce484222325L;
    for (int i = 0; i < text.length(); i++) {
      hash ^= text.charAt(i);
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  private static ArmyData armyWith(CombatRecord... records) {
    Map<CombatRecordId, CombatRecord> byId = new LinkedHashMap<>();
    for (CombatRecord record : records) {
      byId.put(record.id(), record);
    }
    return new ArmyData(byId);
  }

  private static CombatRecord record(List<UnitId> participants, List<CombatStage> stages) {
    return new CombatRecord(C1, "野战", 7L, H11, participants, "自然语言过程", stages);
  }

  private static CombatStage stage(
      String id, String name, List<UnitId> participants, List<CombatOutcome> outcomes) {
    return new CombatStage(
        new CombatStageId(id),
        name,
        participants,
        "阶段过程",
        outcomes,
        Optional.empty(),
        Optional.empty());
  }

  private static CombatOutcome outcome(
      String id, String label, long weight, List<CompositionDelta> manpowerDeltas) {
    return new CombatOutcome(
        new CombatOutcomeId(id),
        label,
        weight,
        List.of(new CombatUnitLoss(U1, manpowerDeltas, List.of())));
  }

  private static UnitState unitState(Map<String, String> links) {
    Unit unit =
        new Unit(
            U1,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T7, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(List.of(new Segment<>(T7, Optional.of(H11))), List.of(), null),
            List.of(new CompositionEntry("步兵", 100)),
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty(),
            UnitStatus.MOVING,
            new SegmentedSeries<>(List.of(new Segment<>(T7, true)), List.of(), null),
            new SegmentedSeries<>(
                List.of(new Segment<>(T7, Optional.<RelativeOffset>empty())), List.of(), null),
            Optional.empty(),
            Unit.DEFAULT_VISION_RADIUS,
            Optional.empty(),
            Optional.empty(),
            links);
    return new UnitState(Map.of(U1, unit));
  }

  private static final class Fixture implements AutoCloseable {

    final CoreSimos core;
    final QueryService query;
    final List<String> handlerCalls = new ArrayList<>();
    final List<String> handlerPayloads = new ArrayList<>();
    final Set<String> rejectTypes = new java.util.LinkedHashSet<>();
    private final ResolveCombatTool tool;

    private Fixture(CoreSimos core) {
      this.core = core;
      this.query = new QueryService(core, new ResolverRegistry(), new FacetRegistry());
      this.tool = new ResolveCombatTool(core, query, "agent:t2b-resolve");
      core.register(recording(new AdjustCompositionHandler()));
      core.register(recording(new ResolveCombatStageHandler()));
      core.register(recording(new SetStateDescriptionHandler()));
      core.register(
          new MutationGuard() {
            @Override
            public String name() {
              return "t2b-reject-by-type";
            }

            @Override
            public Optional<String> rejection(
                SimulationState state, String commandType, String payloadJson) {
              return rejectTypes.contains(commandType)
                  ? Optional.of("T2b 测试守卫：故意拒 " + commandType)
                  : Optional.empty();
            }
          });
    }

    static Fixture open(Path storeDir, UnitState units, ArmyData army) {
      CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, new ObjectMapper()));
      core.register(new MapCodec())
          .register(new SocialCodec())
          .register(new UnitCodec())
          .register(new SdCodec())
          .register(new ArmyCodec())
          .register(new EconomyCodec())
          .register(new ActorCodec());
      Fixture fixture = new Fixture(core);
      core.bootstrapGenesis(genesis(units, army));
      return fixture;
    }

    ToolResult call(Map<String, Object> args) {
      AgentTool agentTool = tool;
      return agentTool.execute(gmContext(agentTool, args));
    }

    long head() {
      return core.head(main()).orElseThrow().value();
    }

    List<?> storedRevisions() {
      return core.revisions(main());
    }

    SimulationState stateAt(long revision) {
      return core.replay(new StateRef(main(), new RevisionId(revision)));
    }

    CombatRecord record(SimulationState state, CombatRecordId id) {
      return ((ArmySnapshot) state.module("army").orElseThrow()).data().combats().get(id);
    }

    Unit unit(SimulationState state, UnitId id) {
      return ((UnitSnapshot) state.module("unit").orElseThrow()).state().units().get(id);
    }

    /** 某类型 handler 收到的最后一份载荷（批内顺序相同；本类每个类型至多一条）。 */
    String callPayload(String type) {
      for (int i = handlerCalls.size() - 1; i >= 0; i--) {
        if (handlerCalls.get(i).equals(type)) {
          return handlerPayloads.get(i);
        }
      }
      throw new AssertionError("该类型 handler 没有被调用过: " + type);
    }

    private CommandHandler recording(CommandHandler delegate) {
      return new CommandHandler() {
        @Override
        public String type() {
          return delegate.type();
        }

        @Override
        public HandlerOutcome handle(SimulationState state, String payloadJson) {
          handlerCalls.add(delegate.type());
          handlerPayloads.add(payloadJson);
          return delegate.handle(state, payloadJson);
        }
      };
    }

    @Override
    public void close() {
      core.close();
    }
  }

  private static SimulationState genesis(UnitState units, ArmyData army) {
    StateRef ref = new StateRef(main(), new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, T7),
        Map.of(
            "map", new MapSnapshot(ref, T7, GameMap.empty()),
            "social", new SocialSnapshot(ref, T7, SocialData.empty()),
            "unit", new UnitSnapshot(ref, T7, units),
            "sd", new SdSnapshot(ref, T7, SdState.empty()),
            "army", new ArmySnapshot(ref, T7, army),
            "economy", new EconomySnapshot(ref, T7, EconomyData.empty()),
            "actor", new ActorSnapshot(ref, T7, ActorData.empty())),
        InMemoryInfoSystem.empty());
  }

  private static ToolContext gmContext(AgentTool tool, Map<String, Object> args) {
    ResourceScopeMap scopes =
        ResourceScopeMap.of(
            Map.of(
                ToolSupport.MAP_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.SOCIAL_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.UNIT_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.SD_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.ARMY_NAMESPACE, ResourceScope.unlimited(),
                ToolSupport.ACTOR_NAMESPACE, ResourceScope.unlimited()));
    AgentPermissionSet permissions =
        new AgentPermissionSet(
            AccessToken.DEFAULT,
            Set.of(AgentPermissionSet.ALL_TOOLS),
            Set.of(),
            true,
            true,
            false,
            scopes);
    return new ToolContext(AccessToken.DEFAULT, permissions, Map.of(), args)
        .withResources(ResourceAuthorizer.of(permissions, tool.resources()));
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static List<String> values(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(node -> out.add(node.asText()));
    return out;
  }

  private static List<String> deltas(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(node -> out.add(node.get("type").asText() + "=" + node.get("amount").asLong()));
    return out;
  }

  private static List<String> declaredAmounts(List<CompositionDelta> deltas) {
    List<String> out = new ArrayList<>();
    for (CompositionDelta delta : deltas) {
      out.add(delta.type() + "=" + delta.amount());
    }
    return out;
  }

  private static List<String> entries(List<CompositionEntry> entries) {
    List<String> out = new ArrayList<>();
    for (CompositionEntry entry : entries) {
      out.add(entry.type() + "=" + entry.amount());
    }
    return out;
  }
}
