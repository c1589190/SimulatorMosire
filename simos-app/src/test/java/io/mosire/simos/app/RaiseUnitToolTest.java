package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.write.CommandSubmitTool;
import io.mosire.simos.app.tools.write.RaiseUnitTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.Sex;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.ModuleCodec;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.unit.raiseUnit} 的**真 Shell 端到端**（收尾期 T4b）：preview 零写入、apply 一批 = 一条
 * revision、批内四片命令的类型与顺序、 新单位逐值、★★ 守恒式（家户粮/钱减少 == 新单位国库增加 == 请求量；Σ各批次被抓人数 == member == 请求人力）、★
 * 不丢失（被抽批次的 {@code ageDays}/{@code anchorTick}/{@code stress} 与税前逐值一致、未涉及批次整条原样）、{@code sd.PutInfo}
 * 落点、失败零 revision。
 *
 * <p>★ <b>批内顺序怎么读</b>：批的 revision 行 {@code command_type} 恒为 {@code core.Batch}（composition 不落盘）⇒
 * 用一条**必然撞 id** 的 {@code sd.PutInfo} 把整批逼成 {@code REJECTED}，再读工具结果里 {@code
 * submission.commands[i].type} —— 那是真 {@code submitBatch} 收到的同一批（按批内序），不是复制出来的清单；同时顺带钉住"整批拒 ⇒ 零
 * revision"。
 *
 * <p>★ 夹具 = 手搭创世 checkpoint（map/social/unit/sd/economy/actor 六切片）：region 覆盖 {@code at}、家户账、非 0
 * stress 的批次、且 无 {@code u-new}；工具本身一步都没绕（真 {@code CoreSimos.submitBatch}）。
 */
class RaiseUnitToolTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  /** 与缺省 initiator 不同，让"写死成别的值"这类变异当场现形。 */
  private static final String TEST_INITIATOR = "agent:t4b-raise";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U_PARENT = new UnitId("u-parent");
  private static final UnitId NEW_UNIT = new UnitId("u-new");
  private static final RegionId NATION = new RegionId("r-nation");

  private static final ActorRef HH1 = new ActorRef(ActorKind.HOUSEHOLD, "house@1_1");
  private static final ActorRef HH2 = new ActorRef(ActorKind.HOUSEHOLD, "house@1_2");
  private static final ActorRef HH_OUT = new ActorRef(ActorKind.HOUSEHOLD, "house@1_3");
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "e-1");
  private static final ActorRef NEW_TREASURY = new ActorRef(ActorKind.UNIT, NEW_UNIT.value());

  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private static final String REASON = "T4b 组军守恒与保真";

  /** 被抽批次的非 0 保真样本：锚点 tick 也一个非 0。 */
  private static final long G1_AGE_DAYS = 20L * 365L;

  private static final long G1_ANCHOR_TICK = 0L;
  private static final long G1_STRESS = 4L;
  private static final long G2_AGE_DAYS = 30L * 365L;
  private static final long G2_ANCHOR_TICK = 5L;
  private static final long G2_STRESS = 11L;

  private static final String DECOY_INFO_ID = "unit:u-new#0";

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final List<ModuleCodec> GENESIS_CODECS =
      List.of(
          new MapCodec(),
          new SocialCodec(),
          new UnitCodec(),
          new SdCodec(),
          new EconomyCodec(),
          new ActorCodec());

  @TempDir Path tempDir;

  private Shell shell;

  @BeforeEach
  void startShell() {
    seedGenesis();
    ShellConfig base = ShellConfig.defaults(tempDir).withPorts(0, 0, 0);
    shell =
        Shell.start(
            new ShellConfig(
                base.storeDir(),
                base.checkpointInterval(),
                base.guiPort(),
                base.mcpPort(),
                base.mcpPath(),
                base.approvalPort(),
                TEST_INITIATOR,
                base.mapId(),
                base.bindAddress()));
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  // ── preview：零写入 ──────────────────────────────────────────────────────────────────

  @Test
  void previewComputesTheFullViewWithoutWritingAnything() throws Exception {
    long headBefore = head();
    long rowsBefore = revisionRowCount();
    UnitState unitsBefore = unitState(stateAt(headBefore));
    ActorData actorsBefore = actorData(stateAt(headBefore));
    SocialData socialBefore = socialData(stateAt(headBefore));

    ToolResult result = call(validArgs(true, -1L));

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode view = JSON.readTree(result.message());
    assertThat(view.get("preview").asBoolean()).isTrue();
    assertThat(view.get("submitted").asBoolean()).isFalse();
    assertThat(view.get("tick").asLong()).isEqualTo(7L);
    assertThat(view.get("unitId").asText()).isEqualTo(NEW_UNIT.value());
    assertThat(view.get("name").asText()).isEqualTo("新军");
    assertThat(view.get("regionId").asText()).isEqualTo(NATION.value());
    assertThat(view.get("at").get("q").asInt()).isEqualTo(1);
    assertThat(view.get("at").get("r").asInt()).isEqualTo(1);
    assertThat(view.get("manpower").get(0).get("type").asText()).isEqualTo("人员");
    assertThat(view.get("manpower").get(0).get("amount").asLong()).isEqualTo(40L);
    assertThat(view.get("speed").asInt()).isEqualTo(4);
    assertThat(view.get("mobilityPerMille").asInt()).isEqualTo(700);
    assertThat(view.get("equipment").get(0).get("type").asText()).isEqualTo("rifle");
    assertThat(view.get("equipment").get(0).get("amount").asInt()).isEqualTo(12);
    assertThat(view.get("equipment").get(1).get("type").asText()).isEqualTo("shield");
    assertThat(view.get("equipment").get(1).get("amount").asInt()).isEqualTo(3);
    assertThat(view.get("grain").get("requested").asLong()).isEqualTo(120L);
    assertThat(view.get("grain").get("available").asLong()).isEqualTo(120L);
    assertThat(view.get("grain").get("sources")).hasSize(2);
    assertThat(view.get("grain").get("sources").get(0).get("amount").asLong()).isEqualTo(80L);
    assertThat(view.get("grain").get("sources").get(1).get("amount").asLong()).isEqualTo(40L);
    assertThat(view.get("money").get("requested").asLong()).isEqualTo(100L);
    assertThat(view.get("money").get("available").asLong()).isEqualTo(130L);
    assertThat(view.get("money").get("sources").get(0).get("amount").asLong()).isEqualTo(80L);
    assertThat(view.get("money").get("sources").get(1).get("amount").asLong()).isEqualTo(20L);
    // ★ D3a：`manpower` 键已改成"新单位的目标表"（array）；抽取来源挪到 `manpowerAllocation`（避免同名字段
    //   两个形状）——按新口径逐值断言。
    assertThat(view.get("manpowerAllocation").get("requested").asLong()).isEqualTo(40L);
    assertThat(view.get("manpowerAllocation").get("available").asLong()).isEqualTo(50L);
    assertThat(view.get("manpowerAllocation").get("sources").get(0).get("id").asText())
        .isEqualTo("g1");
    assertThat(view.get("manpowerAllocation").get("sources").get(0).get("before").asLong())
        .isEqualTo(30L);
    assertThat(view.get("manpowerAllocation").get("sources").get(0).get("taken").asLong())
        .isEqualTo(30L);
    assertThat(view.get("manpowerAllocation").get("sources").get(0).get("after").asLong()).isZero();
    assertThat(view.get("manpowerAllocation").get("sources").get(1).get("id").asText())
        .isEqualTo("g2");
    assertThat(view.get("manpowerAllocation").get("sources").get(1).get("before").asLong())
        .isEqualTo(20L);
    assertThat(view.get("manpowerAllocation").get("sources").get(1).get("taken").asLong())
        .isEqualTo(10L);
    assertThat(view.get("manpowerAllocation").get("sources").get(1).get("after").asLong())
        .isEqualTo(10L);
    assertThat(commandTypes(view))
        .containsExactly(
            "unit.CreateUnit", "actor.AdjustAccounts", "social.SeedGroups", "sd.PutInfo");

    assertThat(head()).as("preview 不得推 head").isEqualTo(headBefore);
    assertThat(revisionRowCount()).as("preview 不得留 revision").isEqualTo(rowsBefore);
    SimulationState still = stateAt(headBefore);
    assertThat(unitState(still)).as("unit 切片一字未动（u-new 不得出现）").isEqualTo(unitsBefore);
    assertThat(actorData(still)).as("actor 账一字未动").isEqualTo(actorsBefore);
    assertThat(socialData(still)).as("social 批次一字未动").isEqualTo(socialBefore);
    assertThat(sdState(still).info()).as("preview 不得落 INFO").isEmpty();
  }

  // ── apply：一批 = 一条 revision + 逐值 + 守恒 + 保真 ─────────────────────────────────

  @Test
  void applyWritesOneRevisionAndConservesEveryDimension() throws Exception {
    long rowsBefore = revisionRowCount();
    SimulationState before = stateAt(head());
    ActorData actorsBefore = actorData(before);
    SocialData socialBefore = socialData(before);

    ToolResult result = call(validArgs(false, head()));

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode view = JSON.readTree(result.message());
    assertThat(view.get("preview").asBoolean()).isFalse();
    assertThat(view.get("submitted").asBoolean()).isTrue();
    assertThat(view.get("submission").get("result").asText()).isEqualTo("committed");
    assertThat(view.get("submission").get("ref").get("branch").asText()).isEqualTo("main");
    assertThat(view.get("submission").get("ref").get("revision").asLong())
        .as("apply 落一条 revision ⇒ (main,2)")
        .isEqualTo(2L);
    assertThat(commandTypes(view))
        .as("可执行前的 plan 视图也给出同一批命令序")
        .containsExactly(
            "unit.CreateUnit", "actor.AdjustAccounts", "social.SeedGroups", "sd.PutInfo");

    assertThat(head()).as("一批只前进一格").isEqualTo(2L);
    assertThat(revisionRowCount()).as("一批只多一行 revision").isEqualTo(rowsBefore + 1L);

    SimulationState after = stateAt(2L);
    Unit created = unitState(after).units().get(NEW_UNIT);
    assertThat(created).as("新单位必须出现").isNotNull();
    assertThat(created.name()).isEqualTo("新军");
    assertThat(created.manpower())
        .as("manpower == 实抽人力 == 请求人力")
        .containsExactly(new CompositionEntry("人员", 40));
    assertThat(created.equipment())
        .as("equipment 原样进新单位（按 Map 迭代序转成有序表）")
        .containsExactly(new CompositionEntry("rifle", 12), new CompositionEntry("shield", 3));
    assertThat(created.position().valueAt(T7)).as("position == at").contains(H11);
    assertThat(created.speed()).isEqualTo(4);
    assertThat(created.mobilityPerMille()).isEqualTo(700);
    assertThat(created.parent().valueAt(T7)).as("无 parent ⇒ 顶层").isEmpty();
    assertThat(created.jurisdiction()).as("新单位不得发明管辖").isEmpty();
    assertThat(created.status()).isEqualTo(UnitStatus.MOVING);

    assertGrainAndMoneyConservation(actorsBefore, actorData(after));
    assertManpowerConservationAndFidelity(socialBefore, socialData(after), 40L);
    assertInfoRecord(after);

    // 失败方向也钉住：国库不在 H12、未涉及的家户/批次不得动。
    assertThat(grainOf(actorData(after), new GoodsAccountKey(NEW_TREASURY, H12))).isZero();
    assertThat(grainOf(actorData(after), new GoodsAccountKey(ESTATE, H11))).isEqualTo(1000L);
    assertThat(grainOf(actorData(after), new GoodsAccountKey(HH_OUT, H13))).isEqualTo(1000L);
  }

  @Test
  void applyManpowerOnlySkipsAdjustAccountsEntirely() throws Exception {
    SimulationState before = stateAt(head());
    ActorData actorsBefore = actorData(before);
    SocialData socialBefore = socialData(before);

    ToolResult result =
        call(
            raiseArgs(
                NEW_UNIT.value(),
                NATION.value(),
                H11,
                40L,
                0L,
                0L,
                4,
                700,
                equipment(),
                null,
                false,
                head()));

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode view = JSON.readTree(result.message());
    assertThat(commandTypes(view))
        .as("粮钱都是 0 ⇒ 批内没有 actor.AdjustAccounts")
        .containsExactly("unit.CreateUnit", "social.SeedGroups", "sd.PutInfo");
    assertThat(head()).isEqualTo(2L);

    SimulationState after = stateAt(2L);
    assertThat(unitState(after).units().get(NEW_UNIT).manpower())
        .containsExactly(new CompositionEntry("人员", 40));
    assertThat(actorData(after)).as("纯人力不得碰 actor 账").isEqualTo(actorsBefore);
    long taken = 0L;
    for (Map.Entry<PeopleLotId, PopulationGroup> entry : socialBefore.groups().entrySet()) {
      PopulationGroup afterGroup = socialData(after).groups().get(entry.getKey());
      taken += entry.getValue().count() - afterGroup.count();
    }
    assertThat(taken).as("Σ各批次被抓人数 == 请求人力").isEqualTo(40L);
    assertThat(actorData(after).accounts().keySet())
        .as("不得给新单位建空国库账（没有 AdjustAccounts 就没有国库条目）")
        .noneMatch(
            key ->
                key.owner().kind() == ActorKind.UNIT && key.owner().id().equals(NEW_UNIT.value()));
    assertInfoRecord(after, 0L, 0L, 0, 0, 2);
  }

  @Test
  void applyWithParentOnSameHexLinksTheNewUnitIntoThatFormation() throws Exception {
    SimulationState before = stateAt(head());
    Unit parentBefore = unitState(before).units().get(U_PARENT);

    ToolResult result =
        call(
            raiseArgs(
                NEW_UNIT.value(),
                NATION.value(),
                H11,
                40L,
                0L,
                0L,
                4,
                700,
                equipment(),
                U_PARENT.value(),
                false,
                head()));

    assertThat(result.success()).as(result.message()).isTrue();
    SimulationState after = stateAt(2L);
    Unit created = unitState(after).units().get(NEW_UNIT);
    assertThat(created.parent().valueAt(T7)).as("parent 原样进 CreateUnit 载荷").contains(U_PARENT);
    assertThat(created.attached().valueAt(T7)).as("有父 ⇒ 编入该支").isTrue();
    assertThat(created.position().valueAt(T7)).contains(H11);
    assertThat(unitState(after).units().get(U_PARENT)).as("既有父单位逐字段不得被改写").isEqualTo(parentBefore);
  }

  /**
   * ★ <b>批内顺序的真读数</b>：先用通用写工具种一条显式 id = {@value #DECOY_INFO_ID} 的 decoy INFO；组军的 {@code sd.PutInfo}
   * 合成 id 正好是同一个 ⇒ 必然拒 ⇒ 整批 {@code REJECTED}。从结果读四条命令类型（按批内序），并钉住整批拒 ⇒ 四切片零变化、head / revision 行数不动。
   */
  @Test
  void rejectedBatchExposesTheExactCommandOrderAndLeavesEverythingUntouched() throws Exception {
    ToolResult decoy = call(CommandSubmitTool.NAME, decoyArgs());
    assertThat(decoy.success()).as(decoy.message()).isTrue();
    long headAfterDecoy = head();
    long rowsAfterDecoy = revisionRowCount();
    SimulationState beforeRaise = stateAt(headAfterDecoy);

    ToolResult rejected = call(validArgs(false, headAfterDecoy));

    assertThat(rejected.success()).as(rejected.message()).isFalse();
    assertThat(rejected.code()).isEqualTo("REJECTED");
    JsonNode view = JSON.readTree(rejected.message());
    JsonNode submission = view.get("submission");
    assertThat(submission.get("result").asText()).isEqualTo("rejected");
    assertThat(commandTypesFromSubmission(submission))
        .as("真 submitBatch 收到的批内顺序")
        .containsExactly(
            "unit.CreateUnit", "actor.AdjustAccounts", "social.SeedGroups", "sd.PutInfo");
    JsonNode commands = submission.get("commands");
    assertThat(commands).hasSize(4);
    assertThat(commands.get(3).get("type").asText()).isEqualTo("sd.PutInfo");
    assertThat(commands.get(3).get("reason").asText())
        .as("逼整批拒的那条必须报真拒因")
        .contains("INFO 条目 id 已存在")
        .contains(DECOY_INFO_ID);

    assertThat(head()).as("整批拒不得推 head").isEqualTo(headAfterDecoy);
    assertThat(revisionRowCount()).as("整批拒不得留 revision").isEqualTo(rowsAfterDecoy);
    SimulationState afterRaise = stateAt(headAfterDecoy);
    assertThat(unitState(afterRaise)).as("unit 切片零变化").isEqualTo(unitState(beforeRaise));
    assertThat(actorData(afterRaise)).as("actor 切片零变化").isEqualTo(actorData(beforeRaise));
    assertThat(socialData(afterRaise)).as("social 切片零变化").isEqualTo(socialData(beforeRaise));
    assertThat(sdState(afterRaise)).as("sd 切片零变化").isEqualTo(sdState(beforeRaise));
  }

  // ── 失败：具名拒 + 零 revision ──────────────────────────────────────────────────────

  @Test
  void namedFailuresAreBadRequestWithZeroRevision() throws Exception {
    long headBefore = head();
    long rowsBefore = revisionRowCount();

    // tools 键（非 null）：具名拒，不静默忽略。
    Map<String, Object> withTools = validArgs(true, -1L);
    withTools.put("tools", Map.of("axe", 1));
    ToolResult tools = call(withTools);
    assertThat(tools.success()).as(tools.message()).isFalse();
    assertThat(tools.code()).isEqualTo("BAD_REQUEST");
    assertThat(tools.message()).contains("载荷不支持 tools 键").contains("本批不做用具来源");
    assertStillZeroRevision(headBefore, rowsBefore);

    ToolResult atOutside =
        call(
            raiseArgs(
                NEW_UNIT.value(),
                NATION.value(),
                H13,
                40L,
                120L,
                100L,
                4,
                700,
                equipment(),
                null,
                true,
                -1L));
    assertThat(atOutside.success()).isFalse();
    assertThat(atOutside.code()).isEqualTo("BAD_REQUEST");
    assertThat(atOutside.message()).contains("at (1,3)").contains("不在区域 r-nation");
    assertStillZeroRevision(headBefore, rowsBefore);

    ToolResult grainShort =
        call(
            raiseArgs(
                NEW_UNIT.value(),
                NATION.value(),
                H11,
                40L,
                121L,
                100L,
                4,
                700,
                equipment(),
                null,
                true,
                -1L));
    assertThat(grainShort.success()).isFalse();
    assertThat(grainShort.code()).isEqualTo("BAD_REQUEST");
    assertThat(grainShort.message())
        .contains("粮总量不足")
        .contains("requested=121")
        .contains("available=120")
        .contains("缺口=1");
    assertStillZeroRevision(headBefore, rowsBefore);

    ToolResult moneyShort =
        call(
            raiseArgs(
                NEW_UNIT.value(),
                NATION.value(),
                H11,
                40L,
                120L,
                131L,
                4,
                700,
                equipment(),
                null,
                true,
                -1L));
    assertThat(moneyShort.success()).isFalse();
    assertThat(moneyShort.code()).isEqualTo("BAD_REQUEST");
    assertThat(moneyShort.message())
        .contains("钱总量不足")
        .contains("requested=131")
        .contains("available=130")
        .contains("缺口=1");
    assertStillZeroRevision(headBefore, rowsBefore);

    ToolResult manpowerShort =
        call(
            raiseArgs(
                NEW_UNIT.value(),
                NATION.value(),
                H11,
                51L,
                120L,
                100L,
                4,
                700,
                equipment(),
                null,
                true,
                -1L));
    assertThat(manpowerShort.success()).isFalse();
    assertThat(manpowerShort.code()).isEqualTo("BAD_REQUEST");
    assertThat(manpowerShort.message())
        .contains("人力总量不足")
        .contains("requested=51")
        .contains("available=50")
        .contains("缺口=1");
    assertStillZeroRevision(headBefore, rowsBefore);

    ToolResult existing =
        call(
            raiseArgs(
                U_PARENT.value(),
                NATION.value(),
                H11,
                40L,
                120L,
                100L,
                4,
                700,
                equipment(),
                null,
                true,
                -1L));
    assertThat(existing.success()).isFalse();
    assertThat(existing.code()).isEqualTo("BAD_REQUEST");
    assertThat(existing.message()).contains("单位 id 已存在").contains(U_PARENT.value());
    assertStillZeroRevision(headBefore, rowsBefore);
  }

  @Test
  void toolIsOnlyInTheGmBucket() {
    assertThat(shell.toolsFor(SimosToolSource.Role.GM).stream().map(tool -> tool.name()).toList())
        .contains(RaiseUnitTool.NAME);
    assertThat(
            shell.toolsFor(SimosToolSource.Role.DECISION_AGENT).stream()
                .map(tool -> tool.name())
                .toList())
        .doesNotContain(RaiseUnitTool.NAME);
  }

  // ── 装置 / 夹具 ─────────────────────────────────────────────────────────────────────

  /** 经真 GM 工具链执行一次工具（GM 面 ⇒ GmAutoApproveGate 直接批准）。 */
  private ToolResult call(Map<String, Object> arguments) {
    return call(RaiseUnitTool.NAME, arguments);
  }

  private ToolResult call(String toolName, Map<String, Object> arguments) {
    ToolContext gm = Shell.gmCaller();
    ToolContext context =
        new ToolContext(gm.caller(), gm.permissions(), gm.config(), arguments, gm.identity());
    return shell.gmToolAuthorizer().execute(shell.toolRegistry(), toolName, context);
  }

  private void assertStillZeroRevision(long headBefore, long rowsBefore) {
    assertThat(head()).as("失败不得推 head").isEqualTo(headBefore);
    assertThat(revisionRowCount()).as("失败不得留 revision").isEqualTo(rowsBefore);
  }

  /** ★ 守恒式：Σ家户粮减少 == 国库粮增加 == 请求粮；钱同款；逐户差额钉在哪一户、扣了多少。 */
  private static void assertGrainAndMoneyConservation(ActorData before, ActorData after) {
    long grainReduction = 0L;
    long moneyReduction = 0L;
    for (GoodsAccountKey key : before.accounts().keySet()) {
      if (key.owner().kind() != ActorKind.HOUSEHOLD) {
        continue;
      }
      grainReduction += grainOf(before, key) - grainOf(after, key);
      moneyReduction += moneyOf(before, key) - moneyOf(after, key);
    }
    long treasuryGrainIncrease =
        grainOf(after, new GoodsAccountKey(NEW_TREASURY, H11))
            - grainOf(before, new GoodsAccountKey(NEW_TREASURY, H11));
    long treasuryMoneyIncrease =
        moneyOf(after, new GoodsAccountKey(NEW_TREASURY, H11))
            - moneyOf(before, new GoodsAccountKey(NEW_TREASURY, H11));

    assertThat(grainReduction).as("Σ家户粮减少 == 请求粮").isEqualTo(120L);
    assertThat(treasuryGrainIncrease).as("新单位国库粮 == 请求粮").isEqualTo(120L);
    assertThat(grainReduction).as("守恒式：Σ家户粮减少 == 新单位国库粮").isEqualTo(treasuryGrainIncrease);
    assertThat(moneyReduction).as("Σ家户钱减少 == 请求钱").isEqualTo(100L);
    assertThat(treasuryMoneyIncrease).as("新单位国库钱 == 请求钱").isEqualTo(100L);
    assertThat(moneyReduction).as("守恒式：Σ家户钱减少 == 新单位国库钱").isEqualTo(treasuryMoneyIncrease);

    assertThat(grainOf(after, new GoodsAccountKey(HH1, H11))).as("hh-1 粮：100−80").isEqualTo(20L);
    assertThat(grainOf(after, new GoodsAccountKey(HH2, H12))).as("hh-2 粮：40−40（落到 0 保留）").isZero();
    assertThat(moneyOf(after, new GoodsAccountKey(HH2, H12))).as("hh-2 钱：80−80").isZero();
    assertThat(moneyOf(after, new GoodsAccountKey(HH1, H11))).as("hh-1 钱：50−20").isEqualTo(30L);
    assertThat(after.accounts().get(new GoodsAccountKey(HH1, H11)).frozenBalances())
        .as("冻结额不许被抽走（粮冻结 20 原样）")
        .containsEntry(GRAIN, 20L);
  }

  /** ★ 守恒 + 不丢失：Σ各批次被抓人数 == member == 请求人力；被动批次只准 count 变，锚点/压力逐值保真；未涉及批次整条原样。 */
  private static void assertManpowerConservationAndFidelity(
      SocialData before, SocialData after, long member) {
    long taken = 0L;
    for (Map.Entry<PeopleLotId, PopulationGroup> entry : before.groups().entrySet()) {
      PeopleLotId id = entry.getKey();
      PopulationGroup beforeGroup = entry.getValue();
      PopulationGroup afterGroup = after.groups().get(id);
      assertThat(afterGroup).as("批次不得丢失: %s", id.value()).isNotNull();
      taken += beforeGroup.count() - afterGroup.count();

      if (id.value().equals("g1") || id.value().equals("g2")) {
        assertThat(afterGroup.id()).as("id 保真").isEqualTo(beforeGroup.id());
        assertThat(afterGroup.residence()).as("居所保真").isEqualTo(beforeGroup.residence());
        assertThat(afterGroup.sex()).as("性别保真").isEqualTo(beforeGroup.sex());
        assertThat(afterGroup.ageAtAnchorDays())
            .as("stage ageDays 保真（不是当前 tick 重写）")
            .isEqualTo(beforeGroup.ageAtAnchorDays());
        assertThat(afterGroup.anchorTick())
            .as("anchorTick 保真（阶段锚点不是当前 tick）")
            .isEqualTo(beforeGroup.anchorTick());
        assertThat(afterGroup.physiologicalStress())
            .as("stress 保真（不得静默清零）")
            .isEqualTo(beforeGroup.physiologicalStress());
      } else {
        assertThat(afterGroup).as("未涉及批次逐字段原样: %s", id.value()).isEqualTo(beforeGroup);
      }
    }

    assertThat(taken).as("Σ各批次被抓人数 == 请求人力").isEqualTo(40L);
    assertThat(member).as("member == 请求人力").isEqualTo(40L);
    assertThat(taken).as("抓走人数 == member").isEqualTo(member);

    PopulationGroup g1Before = before.groups().get(new PeopleLotId("g1"));
    PopulationGroup g1After = after.groups().get(new PeopleLotId("g1"));
    assertThat(g1After.count()).as("g1：30−30=0（countAfter 可为 0）").isZero();
    assertThat(g1After.ageAtAnchorDays()).isEqualTo(G1_AGE_DAYS);
    assertThat(g1After.anchorTick()).isEqualTo(G1_ANCHOR_TICK);
    assertThat(g1After.physiologicalStress()).as("g1 非 0 stress 原样").isEqualTo(G1_STRESS);
    assertThat(g1Before.physiologicalStress()).as("样本本身必须非 0（假绿防线）").isNotZero();

    PopulationGroup g2After = after.groups().get(new PeopleLotId("g2"));
    assertThat(g2After.count()).as("g2：20−10=10（部分抽，只动 count）").isEqualTo(10L);
    assertThat(g2After.ageAtAnchorDays()).isEqualTo(G2_AGE_DAYS);
    assertThat(g2After.anchorTick()).as("非 0 anchorTick 原样").isEqualTo(G2_ANCHOR_TICK);
    assertThat(g2After.physiologicalStress()).isEqualTo(G2_STRESS);
    assertThat(G2_ANCHOR_TICK).as("样本锚点 tick 必须非 0（假绿防线）").isNotZero();
    assertThat(G2_STRESS).as("样本压力必须非 0（假绿防线）").isNotZero();
  }

  /**
   * {@code sd.PutInfo} 逐值：address=unit:&lt;newUnitId&gt;、key=raiseUnit、value=JSON 字符串、tick=当前世界日。
   */
  private static void assertInfoRecord(SimulationState after) throws Exception {
    assertInfoRecord(after, 120L, 100L, 2, 2, 2);
  }

  private static void assertInfoRecord(
      SimulationState after,
      long grain,
      long money,
      int grainSources,
      int moneySources,
      int manpowerSources)
      throws Exception {
    Map<String, List<SdInfoEntry>> info = sdState(after).info();
    assertThat(info).containsOnlyKeys("unit:u-new");
    List<SdInfoEntry> entries = info.get("unit:u-new");
    assertThat(entries).hasSize(1);
    SdInfoEntry entry = entries.get(0);
    assertThat(entry.key()).isEqualTo(RaiseUnitTool.INFO_KEY).isEqualTo("raiseUnit");
    assertThat(entry.tick()).isEqualTo(7L);
    assertThat(entry.value()).as("sd 里 value 是 JSON 字符串").isInstanceOf(String.class);
    JsonNode value = JSON.readTree((String) entry.value());
    assertThat(value.get("unitId").asText()).isEqualTo(NEW_UNIT.value());
    assertThat(value.get("regionId").asText()).isEqualTo(NATION.value());
    assertThat(value.get("at").get("q").asInt()).isEqualTo(1);
    assertThat(value.get("at").get("r").asInt()).isEqualTo(1);
    assertThat(value.get("manpower").get(0).get("type").asText()).isEqualTo("人员");
    assertThat(value.get("manpower").get(0).get("amount").asLong()).isEqualTo(40L);
    assertThat(value.get("manpowerRequested").asLong()).isEqualTo(40L);
    assertThat(value.get("grain").asLong()).isEqualTo(grain);
    assertThat(value.get("money").asLong()).isEqualTo(money);
    assertThat(value.get("sourceCounts").get("grain").asInt()).isEqualTo(grainSources);
    assertThat(value.get("sourceCounts").get("money").asInt()).isEqualTo(moneySources);
    assertThat(value.get("sourceCounts").get("manpower").asInt()).isEqualTo(manpowerSources);
    assertThat(value.get("reason").asText()).isEqualTo(REASON);
    assertThat(entry.note()).isPresent();
    assertThat(entry.note().orElseThrow()).contains(NEW_UNIT.value()).contains(REASON);
  }

  private static List<String> commandTypes(JsonNode node) {
    List<String> types = new ArrayList<>();
    node.get("commands").forEach(command -> types.add(command.asText()));
    return types;
  }

  private static List<String> commandTypesFromSubmission(JsonNode submission) {
    List<String> types = new ArrayList<>();
    submission.get("commands").forEach(command -> types.add(command.get("type").asText()));
    return types;
  }

  private static Map<String, Object> validArgs(boolean preview, long expectedRevision) {
    return raiseArgs(
        NEW_UNIT.value(),
        NATION.value(),
        H11,
        40L,
        120L,
        100L,
        4,
        700,
        equipment(),
        null,
        preview,
        expectedRevision);
  }

  private static Map<String, Object> raiseArgs(
      String newUnitId,
      String regionId,
      HexCoord at,
      long manpower,
      long grain,
      long money,
      int speed,
      int mobilityPerMille,
      Map<String, Integer> equipment,
      String parent,
      boolean preview,
      long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("newUnitId", newUnitId);
    args.put("name", "新军");
    args.put("regionId", regionId);
    args.put("at", Map.of("q", at.q(), "r", at.r()));
    args.put("manpower", manpower);
    args.put("grain", grain);
    args.put("money", money);
    args.put("speed", speed);
    args.put("mobilityPerMille", mobilityPerMille);
    args.put("equipment", equipment);
    if (parent != null) {
      args.put("parent", parent);
    }
    args.put("reason", REASON);
    args.put("branch", "main");
    args.put("preview", preview);
    if (!preview) {
      args.put("expectedRevision", expectedRevision);
    }
    return args;
  }

  /** decoy 载荷：显式 id {@value #DECOY_INFO_ID}（= 组军 PutInfo 下次合成出的同一个 id）⇒ 目标批必然拒。 */
  private Map<String, Object> decoyArgs() throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", "map:Map1");
    payload.put("key", "decoy");
    payload.put("value", "decoy");
    payload.put("id", DECOY_INFO_ID);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "sd.PutInfo");
    args.put("payloadJson", JSON.writeValueAsString(payload));
    args.put("branch", "main");
    args.put("expectedRevision", head());
    return args;
  }

  private static Map<String, Integer> equipment() {
    Map<String, Integer> equipment = new LinkedHashMap<>();
    equipment.put("rifle", 12);
    equipment.put("shield", 3);
    return equipment;
  }

  // ── 创世夹具 ─────────────────────────────────────────────────────────────────────────

  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(dbFile())) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  main(),
                  new RevisionId(1),
                  Optional.empty(),
                  T7,
                  "cmd-genesis",
                  "corr-genesis",
                  TEST_INITIATOR,
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref(1L), T7),
            Map.of(
                "map", new MapSnapshot(ref(1L), T7, map()),
                "unit", new UnitSnapshot(ref(1L), T7, unitState()),
                "social", new SocialSnapshot(ref(1L), T7, social()),
                "sd", new SdSnapshot(ref(1L), T7, SdState.empty()),
                "economy", new EconomySnapshot(ref(1L), T7, EconomyData.empty()),
                "actor", new ActorSnapshot(ref(1L), T7, actors())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir).write(ref(1L), CheckpointEncoder.encode(genesis, GENESIS_CODECS));
  }

  private static UnitState unitState() {
    Unit parent =
        new Unit(
            U_PARENT,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            List.of(new CompositionEntry("步兵", 100)),
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty(),
            UnitStatus.MOVING,
            new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
            Optional.empty(),
            Unit.DEFAULT_VISION_RADIUS,
            Optional.empty());
    return new UnitState(new LinkedHashMap<>(Map.of(U_PARENT, parent)));
  }

  private static ActorData actors() {
    return ActorData.empty()
        .withAccount(account(HH1, H11, 100L, 20L, 50L))
        .withAccount(account(HH2, H12, 40L, 0L, 80L))
        .withAccount(account(HH_OUT, H13, 1000L, 0L, 1000L))
        .withAccount(account(ESTATE, H11, 1000L, 0L, 1000L));
  }

  private static GoodsAccount account(
      ActorRef owner, HexCoord at, long grain, long frozenGrain, long silver) {
    return new GoodsAccount(
        new GoodsAccountKey(owner, at),
        Map.of(GRAIN, grain),
        Map.of(SILVER, silver),
        frozenGrain == 0L ? Map.of() : Map.of(GRAIN, frozenGrain),
        Map.of());
  }

  private static SocialData social() {
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(lot("g1"), group("g1", H11, Sex.MALE, 30L, G1_AGE_DAYS, G1_ANCHOR_TICK, G1_STRESS));
    groups.put(lot("g2"), group("g2", H12, Sex.MALE, 20L, G2_AGE_DAYS, G2_ANCHOR_TICK, G2_STRESS));
    groups.put(lot("g-female"), group("g-female", H11, Sex.FEMALE, 1000L, 20L * 365L, 0L, 1L));
    groups.put(lot("g-child"), group("g-child", H11, Sex.MALE, 100L, 10L * 365L, 0L, 2L));
    groups.put(lot("g-elder"), group("g-elder", H12, Sex.MALE, 7L, 70L * 365L, 0L, 3L));
    groups.put(lot("g-out"), group("g-out", H13, Sex.MALE, 1000L, 20L * 365L, 0L, 6L));
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    for (HexCoord at : List.of(H11, H12, H13)) {
      populations.put(at, populationSeries());
    }
    return new SocialData(populations, Map.of(), groups);
  }

  private static PeopleLotId lot(String id) {
    return new PeopleLotId(id);
  }

  private static PopulationGroup group(
      String id,
      HexCoord at,
      Sex sex,
      long count,
      long ageAtAnchorDays,
      long anchorTick,
      long stress) {
    return new PopulationGroup(lot(id), at, sex, count, ageAtAnchorDays, anchorTick, stress);
  }

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 1000L),
        new SegmentedSeries<>(List.of(new Segment<>(T0, 0.0)), List.of(), null),
        List.of());
  }

  /** 三格地图 + 一个覆盖 (1,1)/(1,2) 的区域（at=H11 合法；H13 区域外）。 */
  private static GameMap map() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(NATION, Region.of(NATION, "国家区域", Set.of(H11, H12), RegionMeta.empty()));
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
  }

  private SimulationState stateAt(long revision) {
    return shell.coreSimos().replay(ref(revision));
  }

  private long revisionRowCount() {
    try (SqliteStore store = SqliteStore.open(dbFile())) {
      return store.inTransaction(
          connection -> {
            try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM revisions")) {
              return rows.next() ? rows.getLong(1) : 0L;
            }
          });
    }
  }

  private static UnitState unitState(SimulationState state) {
    return ((UnitSnapshot) state.module("unit").orElseThrow()).state();
  }

  private static ActorData actorData(SimulationState state) {
    return ((ActorSnapshot) state.module("actor").orElseThrow()).data();
  }

  private static SocialData socialData(SimulationState state) {
    return ((SocialSnapshot) state.module("social").orElseThrow()).data();
  }

  private static SdState sdState(SimulationState state) {
    return ((SdSnapshot) state.module("sd").orElseThrow()).state();
  }

  private static long grainOf(ActorData data, GoodsAccountKey key) {
    GoodsAccount account = data.accounts().get(key);
    return account == null ? 0L : account.balances().getOrDefault(GRAIN, 0L);
  }

  private static long moneyOf(ActorData data, GoodsAccountKey key) {
    GoodsAccount account = data.accounts().get(key);
    return account == null ? 0L : account.money().getOrDefault(SILVER, 0L);
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(long revision) {
    return new StateRef(main(), new RevisionId(revision));
  }
}
