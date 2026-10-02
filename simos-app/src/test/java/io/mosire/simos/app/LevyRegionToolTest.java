package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.AgentTool;
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
import io.mosire.simos.app.tools.write.LevyRegionTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
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
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.Method;
import java.nio.file.Path;
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
 * {@code simos.unit.levyRegion} 的**真 Shell 端到端**（收尾期 T2）：preview 零写入、apply 一批 = 一条
 * revision、批内命令类型/顺序、 {@code sd.PutInfo} 落点与 value JSON、守恒式（粮 / 钱 / **布** / 人力）、失败零 revision。 ★ 阶段
 * 11b 追加：cloth-only apply（逐户扣后 / 两张冻结表 / 守恒 / INFO 逐值）、cloth 不足整条拒、cloth=0 整段跳过、 cloth 无第四条
 * cap（u-cap-1 三条 cap=1 对照）。
 *
 * <p>★ **本类在 {@code io.mosire.simos.app} 包**：{@code Shell#gmCaller()} 是包内可见的装配自检口径（{@code
 * GmPermissionGroupTest} / {@code ActorResolveVisibilityTest} 的先例）。工具经真 {@code ToolRegistry} + 真
 * {@code gmToolAuthorizer} 调用（GM 面 ⇒ 无脑过）。
 *
 * <p>★★ **批内命令类型 / 顺序**不能从 revision 行读出（批的 {@code command_type} 恒为 {@code core.Batch}，见 {@code
 * CommandBus#commitBatch}），故那几条断言取**工具自己的**私有 {@code buildBatch}（反射调真方法，见 {@link
 * #batchOf}）——它不是替代品， 是同一份组批逻辑的直接读数；效果面仍由真 apply 的守恒式与逐值差额把守。
 *
 * <p>★ 夹具是**手搭创世 checkpoint**（六切片：map/social/unit/sd/economy/actor），不经过 {@code core.submitBatch}
 * 造前置——真 Shell 世界里"家户账 + 管辖 + region"用状态构造更直、更可复现；工具本身一步都没绕。
 */
class LevyRegionToolTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  /** 与缺省 initiator 不同，让"写死成别的值"这类变异当场现形。 */
  private static final String TEST_INITIATOR = "agent:t2-levy";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U_NO_JURISDICTION = new UnitId("u-nj");

  /** ★ cap 全 = 1 的对照单位（cloth 无上限用例）；位置 H13，国库落点自成一键。 */
  private static final UnitId U_SMALL_CAPS = new UnitId("u-cap-1");

  private static final RegionId NATION = new RegionId("r-nation");

  private static final ActorRef HH1 = new ActorRef(ActorKind.HOUSEHOLD, "house@1_1");
  private static final ActorRef HH2 = new ActorRef(ActorKind.HOUSEHOLD, "house@1_2");
  private static final ActorRef HH_OUT = new ActorRef(ActorKind.HOUSEHOLD, "house@1_3");
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "e-1");
  private static final ActorRef TREASURY = new ActorRef(ActorKind.UNIT, "u-1");
  private static final ActorRef TREASURY_SMALL_CAPS = new ActorRef(ActorKind.UNIT, "u-cap-1");

  private static final CommodityId GRAIN = new CommodityId(PilotModel.GRAIN);
  private static final CommodityId CLOTH = new CommodityId(PilotModel.CLOTH);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private static final String REASON = "第一轮军粮与兵源";

  private static final ObjectMapper JSON = new ObjectMapper();

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

  /** ★ preview 只算不写：结果视图逐值齐（三项 requested/available/来源与国库落点），head 不动、零新 revision、三张表逐值未变。 */
  @Test
  void previewComputesTheFullViewWithoutWritingAnything() throws Exception {
    long headBefore = head();
    long revisionsBefore = revisionRowCount();
    ActorData actorsBefore = actorData(stateAt(headBefore));
    SocialData socialBefore = socialData(stateAt(headBefore));

    ToolResult result = levy(args(120L, 100L, 40L, true, -1L));

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode view = JSON.readTree(result.message());
    assertThat(view.get("preview").asBoolean()).isTrue();
    assertThat(view.get("submitted").asBoolean()).as("preview 必须 submitted=false").isFalse();
    assertThat(view.get("tick").asLong()).isEqualTo(7L);
    assertThat(view.get("unitId").asText()).isEqualTo("u-1");
    assertThat(view.get("regionId").asText()).isEqualTo("r-nation");
    assertThat(view.get("treasuryLocation").get("q").asInt()).isEqualTo(1);
    assertThat(view.get("treasuryLocation").get("r").asInt()).isEqualTo(1);

    assertThat(view.get("grain").get("requested").asLong()).isEqualTo(120L);
    assertThat(view.get("grain").get("available").asLong()).isEqualTo(120L);
    assertThat(view.get("grain").get("sources")).hasSize(2);
    assertThat(view.get("grain").get("sources").get(0).get("amount").asLong()).isEqualTo(80L);
    assertThat(view.get("grain").get("sources").get(1).get("amount").asLong()).isEqualTo(40L);

    assertThat(view.get("money").get("requested").asLong()).isEqualTo(100L);
    assertThat(view.get("money").get("available").asLong()).isEqualTo(130L);
    assertThat(view.get("money").get("sources").get(0).get("amount").asLong()).isEqualTo(80L);
    assertThat(view.get("money").get("sources").get(1).get("amount").asLong()).isEqualTo(20L);

    assertThat(view.get("manpower").get("requested").asLong()).isEqualTo(40L);
    assertThat(view.get("manpower").get("available").asLong()).isEqualTo(50L);
    assertThat(view.get("manpower").get("sources").get(0).get("taken").asLong()).isEqualTo(30L);
    assertThat(view.get("manpower").get("sources").get(0).get("after").asLong()).isZero();
    assertThat(view.get("manpower").get("sources").get(1).get("taken").asLong()).isEqualTo(10L);
    assertThat(view.get("manpower").get("sources").get(1).get("after").asLong()).isEqualTo(10L);

    // ★ 阶段 11b：cloth=0 ⇒ 视图有规范空维度（requested/available=0、来源空）。
    assertThat(view.get("cloth").get("requested").asLong()).isZero();
    assertThat(view.get("cloth").get("available").asLong()).as("0 = 未求值").isZero();
    assertThat(view.get("cloth").get("sources")).isEmpty();

    assertThat(head()).as("preview 不得推 head").isEqualTo(headBefore);
    assertThat(revisionRowCount()).as("preview 不得留 revision").isEqualTo(revisionsBefore);
    SimulationState still = stateAt(headBefore);
    assertThat(actorData(still)).as("actor 账一字未动").isEqualTo(actorsBefore);
    assertThat(socialData(still)).as("social 批次一字未动").isEqualTo(socialBefore);
    assertThat(sdState(still).info()).as("preview 不得落 INFO").isEmpty();
  }

  // ── apply：一批 = 一条 revision + 批内命令 + 守恒 ─────────────────────────────────────

  /**
   * ★★ apply 的结构面：一批 = **一条 revision**（head 1→2，revisions 行数 +1）、批内命令类型/顺序（粮钱有 AdjustAccounts、人力有
   * SeedGroups、恒有 PutInfo）、索引里三条共享 correlationId、{@code sd.PutInfo} 的 address/key/value/tick 逐值。
   */
  @Test
  void applySubmitsOneRevisionWithTheExpectedBatchAndInfoRecord() throws Exception {
    // 批内命令类型 / 顺序：反射调工具自己的 buildBatch（batch 行读不出 composition，见类注）。
    List<CommandEnvelope> batch = batchOf(120L, 100L, 0L, 40L);
    assertThat(batch)
        .extracting(CommandEnvelope::type)
        .containsExactly(
            LevyRegionTool.ADJUST_ACCOUNTS_TYPE,
            LevyRegionTool.SEED_GROUPS_TYPE,
            LevyRegionTool.PUT_INFO_TYPE);
    assertThat(batch)
        .allMatch(envelope -> envelope.correlationId().equals(batch.get(0).correlationId()));
    assertThat(batch).allMatch(envelope -> envelope.branch().equals(main()));
    assertThat(batch).allMatch(envelope -> envelope.expectedRevision().equals(new RevisionId(1)));

    JsonNode adjust = JSON.readTree(batch.get(0).payloadJson());
    JsonNode entries = adjust.get("entries");
    assertThat(entries).as("粮钱来源合并 + 国库一条：hh1 / hh2 / u-1").hasSize(3);
    assertTreasurySource(entries.get(0), "house@1_1", -80L, -20L);
    assertTreasurySource(entries.get(1), "house@1_2", -40L, -80L);
    JsonNode treasury = entries.get(2);
    assertThat(treasury.get("owner").get("kind").asText()).isEqualTo("UNIT");
    assertThat(treasury.get("owner").get("id").asText()).isEqualTo("u-1");
    assertThat(treasury.get("q").asInt()).isEqualTo(1);
    assertThat(treasury.get("r").asInt()).isEqualTo(1);
    assertThat(treasury.get("goods").get("grain").asLong()).as("国库粮 = 请求量（恒在最后一条）").isEqualTo(120L);
    assertThat(treasury.get("money").get("silver").asLong()).as("国库钱 = 请求量").isEqualTo(100L);
    // ★ 阶段 11b：cloth=0 ⇒ AdjustAccounts 的 goods 表里不出现 cloth 键（也不为它建条目）。
    assertThat(treasury.get("goods").has("cloth")).as("国库 goods 不得有 cloth 键").isFalse();
    assertThat(entries.get(0).get("goods").has("cloth")).as("家户 goods 不得有 cloth 键").isFalse();

    JsonNode seedGroups = JSON.readTree(batch.get(1).payloadJson());
    JsonNode groupEntries = seedGroups.get("entries");
    assertThat(groupEntries).hasSize(2);
    assertSeedGroup(groupEntries.get(0), "g1", 0L);
    assertSeedGroup(groupEntries.get(1), "g2", 10L);

    JsonNode putInfo = JSON.readTree(batch.get(2).payloadJson());
    assertThat(putInfo.get("address").asText()).as("单位 canonical 地址").isEqualTo("unit:u-1");
    assertThat(putInfo.get("key").asText()).isEqualTo(LevyRegionTool.INFO_KEY);
    assertThat(putInfo.get("value").isTextual()).as("value 必须是 JSON **字符串**（不是对象）").isTrue();
    assertThat(putInfo.get("tick").asLong()).isEqualTo(7L);
    JsonNode value = JSON.readTree(putInfo.get("value").asText());
    assertThat(value.get("unitId").asText()).isEqualTo("u-1");
    assertThat(value.get("regionId").asText()).isEqualTo("r-nation");
    assertThat(value.get("tick").asLong()).isEqualTo(7L);
    assertThat(value.get("grain").asLong()).isEqualTo(120L);
    assertThat(value.get("money").asLong()).isEqualTo(100L);
    assertThat(value.get("cloth").asLong()).isZero();
    assertThat(value.get("manpower").asLong()).isEqualTo(40L);
    assertThat(value.get("sourceCounts").get("grain").asInt()).isEqualTo(2);
    assertThat(value.get("sourceCounts").get("money").asInt()).isEqualTo(2);
    assertThat(value.get("sourceCounts").get("cloth").asInt()).isZero();
    assertThat(value.get("sourceCounts").get("manpower").asInt()).isEqualTo(2);
    assertThat(value.get("reason").asText()).isEqualTo(REASON);

    long revisionsBefore = revisionRowCount();
    ToolResult result = levy(args(120L, 100L, 40L, false, head()));
    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode view = JSON.readTree(result.message());
    assertThat(view.get("preview").asBoolean()).isFalse();
    assertThat(view.get("submitted").asBoolean()).as("apply 必须 submitted=true").isTrue();
    assertThat(view.get("submission").get("result").asText()).isEqualTo("committed");
    assertThat(view.get("submission").get("ref").get("branch").asText()).isEqualTo("main");
    assertThat(view.get("submission").get("ref").get("revision").asLong())
        .as("apply 落一条 revision ⇒ (main,2)")
        .isEqualTo(2L);

    assertThat(head()).as("一批只前进一格").isEqualTo(2L);
    assertThat(revisionRowCount()).as("一批只多一行 revision").isEqualTo(revisionsBefore + 1L);

    SimulationState after = stateAt(2L);
    assertThat(sdState(after).info()).containsOnlyKeys("unit:u-1");
    List<SdInfoEntry> infos = sdState(after).info().get("unit:u-1");
    assertThat(infos).hasSize(1);
    SdInfoEntry entry = infos.get(0);
    assertThat(entry.key()).isEqualTo("levyRegion");
    assertThat(entry.tick()).isEqualTo(7L);
    assertThat(entry.value()).as("sd 里 value 是 JSON 字符串").isInstanceOf(String.class);
    JsonNode stored = JSON.readTree((String) entry.value());
    assertThat(stored.get("grain").asLong()).isEqualTo(120L);
    assertThat(stored.get("money").asLong()).isEqualTo(100L);
    assertThat(stored.get("manpower").asLong()).isEqualTo(40L);
    assertThat(entry.note()).isPresent();
    assertThat(entry.note().orElseThrow()).contains("r-nation").contains(REASON);
  }

  /**
   * ★★ **守恒式**（apply 后从 (main,2) 读回，与 (main,1) 逐账作差）：
   *
   * <pre>
   * 粮：Σ(区内 HOUSEHOLD 账的粮增量) + (国库粮增量) == 0，且国库粮增量 == 请求量 120
   * 钱：Σ(区内 HOUSEHOLD 账的钱增量) + (国库钱增量) == 0，且国库钱增量 == 请求量 100
   * 人：Σ(各批次 before.count − after.count) == 请求量 40
   * </pre>
   *
   * <p>判别力：漏记一笔来源、国库多记/少记、某批次少抽/多抽，都会让对应的等式当场不成立；再叠加两条**逐来源**断言（hh-1 粮 −80、hh-2 粮 −40；hh-2 钱
   * −80、hh-1 钱 −20），"记错方向/记错户"也跑不掉。
   */
  @Test
  void applyConservesGrainMoneyAndManpower() throws Exception {
    ToolResult result = levy(args(120L, 100L, 40L, false, 1L));
    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(head()).isEqualTo(2L);

    ActorData before = actorData(stateAt(1L));
    ActorData after = actorData(stateAt(2L));

    long grainFromHouseholds = 0L;
    long silverFromHouseholds = 0L;
    for (Map.Entry<GoodsAccountKey, GoodsAccount> account : before.accounts().entrySet()) {
      GoodsAccountKey key = account.getKey();
      if (key.owner().kind() != ActorKind.HOUSEHOLD || !isInNation(key.location())) {
        continue;
      }
      grainFromHouseholds += grainOf(after, key) - grainOf(before, key);
      silverFromHouseholds += silverOf(after, key) - silverOf(before, key);
    }
    long grainToTreasury =
        grainOf(after, new GoodsAccountKey(TREASURY, H11))
            - grainOf(before, new GoodsAccountKey(TREASURY, H11));
    long silverToTreasury =
        silverOf(after, new GoodsAccountKey(TREASURY, H11))
            - silverOf(before, new GoodsAccountKey(TREASURY, H11));

    assertThat(grainToTreasury).as("国库粮正增量 == 请求量").isEqualTo(120L);
    assertThat(grainFromHouseholds).as("Σ区内家户粮增量（含符号） == −120").isEqualTo(-120L);
    assertThat(grainFromHouseholds + grainToTreasury).as("粮守恒：来源负增量之和 + 国库正增量 == 0").isZero();
    assertThat(silverToTreasury).as("国库钱正增量 == 请求量").isEqualTo(100L);
    assertThat(silverFromHouseholds).as("Σ区内家户钱增量（含符号） == −100").isEqualTo(-100L);
    assertThat(silverFromHouseholds + silverToTreasury).as("钱守恒").isZero();

    assertThat(grainOf(after, new GoodsAccountKey(HH1, H11))).as("hh-1 粮：100−80").isEqualTo(20L);
    assertThat(grainOf(after, new GoodsAccountKey(HH2, H12))).as("hh-2 粮：40−40（落到 0 保留）").isZero();
    assertThat(silverOf(after, new GoodsAccountKey(HH2, H12))).as("hh-2 钱：80−80").isZero();
    assertThat(silverOf(after, new GoodsAccountKey(HH1, H11))).as("hh-1 钱：50−20").isEqualTo(30L);
    assertThat(after.accounts().get(new GoodsAccountKey(HH1, H11)).frozenBalances())
        .as("冻结额不许被抽走（粮冻结 20 原样）")
        .containsEntry(GRAIN, 20L);

    SocialData socialBefore = socialData(stateAt(1L));
    SocialData socialAfter = socialData(stateAt(2L));
    long taken = 0L;
    for (Map.Entry<PeopleLotId, PopulationGroup> group : socialBefore.groups().entrySet()) {
      PopulationGroup beforeGroup = group.getValue();
      PopulationGroup afterGroup = socialAfter.groups().get(group.getKey());
      taken += beforeGroup.count() - afterGroup.count();
    }
    assertThat(taken).as("Σ各批次被抽走人数 == 请求量 40").isEqualTo(40L);
    assertThat(socialAfter.groups().get(new PeopleLotId("g1")).count()).as("g1：30−30=0").isZero();
    assertThat(socialAfter.groups().get(new PeopleLotId("g2")).count())
        .as("g2：20−10=10")
        .isEqualTo(10L);
    assertThat(socialAfter.groups().get(new PeopleLotId("g-female")).count())
        .as("女性批次不动")
        .isEqualTo(1000L);
  }

  /**
   * ★★ <b>cloth-only apply 的逐值 + 守恒</b>：一次只抽布的请求经真 GM 工具链（preview=false）落一条 revision； 国库 {@code
   * (UNIT,u-1)@H11} 的 cloth 正增量 == 请求量；Σ本区家户 cloth 增量 == −请求量；{@code Σ家户 + 国库 == 0}； 逐户扣后 = 扣前 −
   * 计划来源额；两张冻结表逐值不动；{@code sd.PutInfo} 的 value.cloth / sourceCounts.cloth 逐值。
   *
   * <p>装置：hh-1 cloth 100（冻结 20 ⇒ 可用 80）、hh-2 cloth 40（可用 40）；请求 100 ⇒ 瀑布 hh-1 80 + hh-2 20。
   */
  @Test
  void applyClothOnlyMovesHouseholdClothToTreasuryAndConserves() throws Exception {
    long headBefore = head();
    long revisionsBefore = revisionRowCount();
    ActorData before = actorData(stateAt(headBefore));

    ToolResult result = levy(args(0L, 0L, 100L, 0L, false, headBefore));
    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(head()).as("一批 = 一条 revision：head 恰好 +1").isEqualTo(headBefore + 1L);
    assertThat(revisionRowCount())
        .as("一批 = 一条 revision：revisions 行数恰好 +1")
        .isEqualTo(revisionsBefore + 1L);

    SimulationState afterState = stateAt(headBefore + 1L);
    ActorData after = actorData(afterState);

    GoodsAccountKey treasuryKey = new GoodsAccountKey(TREASURY, H11);
    long clothToTreasury = clothOf(after, treasuryKey) - clothOf(before, treasuryKey);
    assertThat(clothToTreasury).as("国库 (UNIT,u-1)@H11 的 cloth 正增量 == 请求量 100").isEqualTo(100L);

    long clothFromHouseholds = 0L;
    for (Map.Entry<GoodsAccountKey, GoodsAccount> account : before.accounts().entrySet()) {
      GoodsAccountKey key = account.getKey();
      if (key.owner().kind() != ActorKind.HOUSEHOLD || !isInNation(key.location())) {
        continue;
      }
      clothFromHouseholds += clothOf(after, key) - clothOf(before, key);
    }
    assertThat(clothFromHouseholds).as("Σ本区家户 cloth 增量（含符号）== −100").isEqualTo(-100L);
    assertThat(clothFromHouseholds + clothToTreasury).as("cloth 守恒：Σ本区家户负增量 + 国库正增量 == 0").isZero();

    GoodsAccountKey hh1Key = new GoodsAccountKey(HH1, H11);
    GoodsAccountKey hh2Key = new GoodsAccountKey(HH2, H12);
    long hh1Before = clothOf(before, hh1Key);
    long hh2Before = clothOf(before, hh2Key);
    assertThat(hh1Before).as("hh-1 抽前 cloth 余额 = 100").isEqualTo(100L);
    assertThat(hh2Before).as("hh-2 抽前 cloth 余额 = 40").isEqualTo(40L);
    assertThat(clothOf(after, hh1Key))
        .as("hh-1 扣后 = 100 − 80（计划来源额；可用量 = 余额 100 − 冻结 20）")
        .isEqualTo(hh1Before - 80L);
    assertThat(clothOf(after, hh2Key))
        .as("hh-2 扣后 = 40 − 20（计划来源额；可用量 = 余额 40 − 冻结 0）")
        .isEqualTo(hh2Before - 20L);
    assertThat(clothOf(after, new GoodsAccountKey(HH_OUT, H13)))
        .as("区外 hh-out 的 cloth 一字不动")
        .isEqualTo(1000L);
    assertThat(clothOf(after, new GoodsAccountKey(ESTATE, H11)))
        .as("ESTATE 的 cloth 一字不动")
        .isEqualTo(1000L);

    GoodsAccount hh1After = after.accounts().get(hh1Key);
    GoodsAccount hh1BeforeBook = before.accounts().get(hh1Key);
    assertThat(hh1After.frozenBalances())
        .as("hh-1 商品冻结表逐值不动（粮 20 + 布 20）")
        .isEqualTo(hh1BeforeBook.frozenBalances())
        .containsEntry(GRAIN, 20L)
        .containsEntry(CLOTH, 20L);
    assertThat(hh1After.frozenMoney()).as("hh-1 货币冻结表逐值不动").isEqualTo(hh1BeforeBook.frozenMoney());
    GoodsAccount hh2After = after.accounts().get(hh2Key);
    assertThat(hh2After.frozenBalances())
        .as("hh-2 商品冻结表逐值不动（空）")
        .isEqualTo(before.accounts().get(hh2Key).frozenBalances())
        .isEmpty();

    List<SdInfoEntry> infos = sdState(afterState).info().get("unit:u-1");
    assertThat(infos).as("sd 里恰有一条 levyRegion 行动记录").hasSize(1);
    JsonNode stored = JSON.readTree((String) infos.get(0).value());
    assertThat(stored.get("cloth").asLong())
        .as("sd.PutInfo value.cloth == 请求量 100")
        .isEqualTo(100L);
    assertThat(stored.get("sourceCounts").get("cloth").asInt())
        .as("sourceCounts.cloth == 来源数 2（hh-1 / hh-2）")
        .isEqualTo(2);
    assertThat(stored.get("grain").asLong()).as("cloth-only ⇒ value.grain=0").isZero();
    assertThat(stored.get("money").asLong()).as("cloth-only ⇒ value.money=0").isZero();
    assertThat(stored.get("manpower").asLong()).as("cloth-only ⇒ value.manpower=0").isZero();
    assertThat(stored.get("sourceCounts").get("grain").asInt())
        .as("cloth-only ⇒ sourceCounts.grain=0")
        .isZero();
    assertThat(stored.get("sourceCounts").get("money").asInt())
        .as("cloth-only ⇒ sourceCounts.money=0")
        .isZero();
    assertThat(stored.get("sourceCounts").get("manpower").asInt())
        .as("cloth-only ⇒ sourceCounts.manpower=0")
        .isZero();
  }

  /** ★ requested=0 的维度不产生批内命令（且 AdjustAccounts 载荷里没有该维度）：只粮 / 只人两种形态各钉一次。 */
  @Test
  void zeroRequestedDimensionsProduceNoCommandAndNoEntry() throws Exception {
    List<CommandEnvelope> grainOnly = batchOf(120L, 0L, 0L, 0L);
    assertThat(grainOnly)
        .extracting(CommandEnvelope::type)
        .containsExactly(LevyRegionTool.ADJUST_ACCOUNTS_TYPE, LevyRegionTool.PUT_INFO_TYPE);
    JsonNode adjust = JSON.readTree(grainOnly.get(0).payloadJson());
    assertThat(adjust.get("entries")).as("粮来源 2 户 + 国库 1 条").hasSize(3);
    assertThat(adjust.get("entries").get(2).has("money")).as("钱 0 ⇒ 国库条目没有 money 键").isFalse();
    JsonNode grainPutInfo = JSON.readTree(grainOnly.get(1).payloadJson());
    JsonNode grainValue = JSON.readTree(grainPutInfo.get("value").asText());
    assertThat(grainValue.get("manpower").asLong()).as("人力 0 进行动记录，但不进批内命令").isZero();
    assertThat(grainValue.get("sourceCounts").get("manpower").asInt()).isZero();

    List<CommandEnvelope> moneyOnly = batchOf(0L, 100L, 0L, 0L);
    assertThat(moneyOnly)
        .extracting(CommandEnvelope::type)
        .containsExactly(LevyRegionTool.ADJUST_ACCOUNTS_TYPE, LevyRegionTool.PUT_INFO_TYPE);
    JsonNode moneyAdjust = JSON.readTree(moneyOnly.get(0).payloadJson());
    assertThat(moneyAdjust.get("entries")).as("钱来源 2 户 + 国库 1 条").hasSize(3);
    assertThat(moneyAdjust.get("entries").get(2).has("goods")).as("粮 0 ⇒ 国库条目没有 goods 键").isFalse();
    assertThat(moneyAdjust.get("entries").get(2).get("money").get("silver").asLong())
        .isEqualTo(100L);

    List<CommandEnvelope> manpowerOnly = batchOf(0L, 0L, 0L, 40L);
    assertThat(manpowerOnly)
        .extracting(CommandEnvelope::type)
        .containsExactly(LevyRegionTool.SEED_GROUPS_TYPE, LevyRegionTool.PUT_INFO_TYPE);
    JsonNode putInfo = JSON.readTree(manpowerOnly.get(1).payloadJson());
    JsonNode value = JSON.readTree(putInfo.get("value").asText());
    assertThat(value.get("grain").asLong()).isZero();
    assertThat(value.get("money").asLong()).isZero();
    assertThat(value.get("sourceCounts").get("grain").asInt()).isZero();
    assertThat(value.get("sourceCounts").get("money").asInt()).isZero();
  }

  /**
   * ★ cloth=0 维度整段跳过（端到端）：AdjustAccounts 的每条 goods 表里都没有 cloth 键，PutInfo 照落且 cloth=0；只 cloth&gt;0
   * 时批内命令恰为 {@code AdjustAccounts + PutInfo}（无 SeedGroups）；真 apply 一次 grain-only（cloth=0）后逐户 cloth
   * 未动。
   */
  @Test
  void zeroClothDimensionLeavesNoClothEntryAndClothOnlyBatchHasTwoCommands() throws Exception {
    List<CommandEnvelope> grainOnly = batchOf(120L, 0L, 0L, 0L);
    assertThat(grainOnly)
        .extracting(CommandEnvelope::type)
        .as("cloth=0 ⇒ 仍走 AdjustAccounts + PutInfo 两条命令")
        .containsExactly(LevyRegionTool.ADJUST_ACCOUNTS_TYPE, LevyRegionTool.PUT_INFO_TYPE);
    JsonNode grainAdjust = JSON.readTree(grainOnly.get(0).payloadJson());
    JsonNode grainEntries = grainAdjust.get("entries");
    assertThat(grainEntries).as("粮来源 2 户 + 国库 1 条").hasSize(3);
    for (JsonNode entry : grainEntries) {
      assertThat(entry.get("goods").has("cloth"))
          .as("cloth=0 ⇒ 每条家户 / 国库条目都不得出现 cloth 键")
          .isFalse();
    }
    JsonNode grainPutInfo = JSON.readTree(grainOnly.get(1).payloadJson());
    JsonNode grainValue = JSON.readTree(grainPutInfo.get("value").asText());
    assertThat(grainValue.get("cloth").asLong()).as("grain-only：PutInfo value.cloth=0").isZero();
    assertThat(grainValue.get("sourceCounts").get("cloth").asInt())
        .as("grain-only：sourceCounts.cloth=0")
        .isZero();

    List<CommandEnvelope> clothOnly = batchOf(0L, 0L, 100L, 0L);
    assertThat(clothOnly)
        .extracting(CommandEnvelope::type)
        .as("只 cloth>0：AdjustAccounts + PutInfo，没有 SeedGroups")
        .containsExactly(LevyRegionTool.ADJUST_ACCOUNTS_TYPE, LevyRegionTool.PUT_INFO_TYPE);
    JsonNode clothAdjust = JSON.readTree(clothOnly.get(0).payloadJson());
    JsonNode clothEntries = clothAdjust.get("entries");
    assertThat(clothEntries).as("cloth 来源 2 户 + 国库 1 条").hasSize(3);
    assertThat(clothEntries.get(0).get("owner").get("id").asText())
        .as("第一条家户来源 owner = house@1_1")
        .isEqualTo("house@1_1");
    assertThat(clothEntries.get(0).get("goods").get("cloth").asLong())
        .as("hh-1 cloth −80")
        .isEqualTo(-80L);
    assertThat(clothEntries.get(1).get("owner").get("id").asText())
        .as("第二条家户来源 owner = house@1_2")
        .isEqualTo("house@1_2");
    assertThat(clothEntries.get(1).get("goods").get("cloth").asLong())
        .as("hh-2 cloth −20")
        .isEqualTo(-20L);
    JsonNode nodeTreasury = clothEntries.get(2);
    assertThat(nodeTreasury.get("owner").get("id").asText())
        .as("国库条目 owner = u-1")
        .isEqualTo("u-1");
    assertThat(nodeTreasury.get("goods").get("cloth").asLong()).as("国库 cloth +100").isEqualTo(100L);
    assertThat(nodeTreasury.get("goods").has("grain"))
        .as("cloth-only ⇒ 国库 goods 无 grain 键")
        .isFalse();
    assertThat(nodeTreasury.has("money")).as("cloth-only ⇒ 国库条目无 money 键").isFalse();
    JsonNode clothPutInfo = JSON.readTree(clothOnly.get(1).payloadJson());
    JsonNode clothValue = JSON.readTree(clothPutInfo.get("value").asText());
    assertThat(clothValue.get("cloth").asLong()).as("PutInfo value.cloth=100").isEqualTo(100L);
    assertThat(clothValue.get("sourceCounts").get("cloth").asInt())
        .as("sourceCounts.cloth=2")
        .isEqualTo(2);
    assertThat(clothValue.get("grain").asLong()).as("cloth-only：value.grain=0").isZero();
    assertThat(clothValue.get("money").asLong()).as("cloth-only：value.money=0").isZero();
    assertThat(clothValue.get("manpower").asLong()).as("cloth-only：value.manpower=0").isZero();

    // 真 apply 一次 grain-only（cloth=0）：PutInfo 照落，逐户 cloth 一字未动。
    ActorData before = actorData(stateAt(head()));
    ToolResult applied = levy(args(120L, 0L, 0L, false, head()));
    assertThat(applied.success()).as(applied.message()).isTrue();
    SimulationState afterState = stateAt(head());
    ActorData after = actorData(afterState);
    for (Map.Entry<GoodsAccountKey, GoodsAccount> account : before.accounts().entrySet()) {
      assertThat(clothOf(after, account.getKey()))
          .as("cloth=0 时每本账的 cloth 余额都不动")
          .isEqualTo(clothOf(before, account.getKey()));
    }
    List<SdInfoEntry> infos = sdState(afterState).info().get("unit:u-1");
    assertThat(infos).as("grain-only apply 后 sd 里恰有一条行动记录").hasSize(1);
    JsonNode stored = JSON.readTree((String) infos.get(0).value());
    assertThat(stored.get("cloth").asLong()).as("grain-only apply 的 PutInfo 仍写 cloth=0").isZero();
    assertThat(stored.get("sourceCounts").get("cloth").asInt())
        .as("grain-only apply：sourceCounts.cloth=0")
        .isZero();
  }

  /** ★ 工具只在 GM 桶：GM 面有、决策人面无。 */
  @Test
  void toolIsOnlyInTheGmBucket() {
    assertThat(shell.toolsFor(SimosToolSource.Role.GM).stream().map(AgentTool::name).toList())
        .contains(LevyRegionTool.NAME);
    assertThat(
            shell.toolsFor(SimosToolSource.Role.DECISION_AGENT).stream()
                .map(AgentTool::name)
                .toList())
        .doesNotContain(LevyRegionTool.NAME);
  }

  // ── 失败：零 revision ────────────────────────────────────────────────────────────────

  /** ★ 超单命令上限 ⇒ BAD_REQUEST，且 head / revision 行数一个都不动。 */
  @Test
  void overCapIsBadRequestWithZeroRevision() throws Exception {
    long revisionsBefore = revisionRowCount();
    ToolResult result = levy(args(1001L, 0L, 0L, false, 1L));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("BAD_REQUEST");
    assertThat(result.message()).contains("超过 levyGrainCapPerCommand=1000").contains("1001");
    assertThat(head()).isEqualTo(1L);
    assertThat(revisionRowCount()).as("失败不得留 revision").isEqualTo(revisionsBefore);
  }

  /**
   * ★ cloth 没有第四条 cap（端到端）：u-cap-1 的三条 cap 都 = 1，cloth 80 ≤ 可用量 120 ⇒ apply 成功且国库 cloth +80；
   * 同一单位粮请求 2 &gt; cap 1 ⇒ BAD_REQUEST（上限仍只作用于粮 / 钱 / 人）。
   */
  @Test
  void clothIgnoresTheThreeLevyCapsWhileGrainStillRespectsThem() throws Exception {
    long revisionsBefore = revisionRowCount();

    ToolResult overGrainCap = levy(argsFor("u-cap-1", "r-nation", 2L, 0L, 0L, false, 1L));
    assertThat(overGrainCap.success()).as("粮 2 > cap 1 ⇒ 不成功").isFalse();
    assertThat(overGrainCap.code()).as("粮 2 > cap 1 ⇒ BAD_REQUEST").isEqualTo("BAD_REQUEST");
    assertThat(overGrainCap.message())
        .as("拒因逐值带字段名 / requested / cap")
        .contains("粮 requested=2 超过 levyGrainCapPerCommand=1");
    assertThat(head()).as("超粮 cap 不得推 head").isEqualTo(1L);
    assertThat(revisionRowCount()).as("超粮 cap 不得留 revision").isEqualTo(revisionsBefore);

    List<CommandEnvelope> batch = batchOfFor("u-cap-1", 0L, 0L, 80L, 0L);
    assertThat(batch)
        .extracting(CommandEnvelope::type)
        .as("cloth-only ⇒ AdjustAccounts + PutInfo（无 SeedGroups）")
        .containsExactly(LevyRegionTool.ADJUST_ACCOUNTS_TYPE, LevyRegionTool.PUT_INFO_TYPE);

    ActorData before = actorData(stateAt(1L));
    ToolResult applied = levy(argsFor("u-cap-1", "r-nation", 0L, 0L, 80L, 0L, false, 1L));
    assertThat(applied.success()).as(applied.message()).isTrue();
    assertThat(head()).as("cloth 80 ≫ 三条 cap=1 仍成功 ⇒ 恰好一条 revision").isEqualTo(2L);

    ActorData after = actorData(stateAt(2L));
    GoodsAccountKey treasuryKey = new GoodsAccountKey(TREASURY_SMALL_CAPS, H13);
    long clothToTreasury = clothOf(after, treasuryKey) - clothOf(before, treasuryKey);
    assertThat(clothToTreasury).as("国库 (UNIT,u-cap-1)@H13 的 cloth 增量 == 请求量 80").isEqualTo(80L);

    long clothFromHouseholds = 0L;
    for (Map.Entry<GoodsAccountKey, GoodsAccount> account : before.accounts().entrySet()) {
      GoodsAccountKey key = account.getKey();
      if (key.owner().kind() != ActorKind.HOUSEHOLD || !isInNation(key.location())) {
        continue;
      }
      clothFromHouseholds += clothOf(after, key) - clothOf(before, key);
    }
    assertThat(clothFromHouseholds).as("Σ本区家户 cloth 增量 == −80").isEqualTo(-80L);
    assertThat(clothFromHouseholds + clothToTreasury)
        .as("cap=1 下的 cloth 守恒：Σ家户 + 国库 == 0")
        .isZero();

    assertThat(clothOf(after, new GoodsAccountKey(HH1, H11)))
        .as("hh-1 扣后 = 100 − 80 = 20（可用量 = 余额 100 − 冻结 20）")
        .isEqualTo(20L);
    assertThat(clothOf(after, new GoodsAccountKey(HH2, H12)))
        .as("请求 80 落在 hh-1 可用量内 ⇒ hh-2 的 40 不动")
        .isEqualTo(40L);
    assertThat(after.accounts().get(new GoodsAccountKey(HH1, H11)).frozenBalances())
        .as("冻结表逐值不动")
        .containsEntry(GRAIN, 20L)
        .containsEntry(CLOTH, 20L);
  }

  /** ★ 来源不足 ⇒ BAD_REQUEST，拒因带 requested / available / 缺口，零 revision。 */
  @Test
  void insufficientSourcesIsBadRequestWithZeroRevision() throws Exception {
    long revisionsBefore = revisionRowCount();
    ToolResult result = levy(args(1000L, 0L, 0L, false, 1L));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("BAD_REQUEST");
    assertThat(result.message())
        .contains("粮总量不足")
        .contains("requested=1000")
        .contains("available=120")
        .contains("缺口=880");
    assertThat(head()).isEqualTo(1L);
    assertThat(revisionRowCount()).isEqualTo(revisionsBefore);
  }

  /** ★ cloth 不足整条拒：requested = 可用量 + 1 ⇒ BAD_REQUEST，消息带 requested / available / 缺口，零 revision。 */
  @Test
  void clothInsufficientIsBadRequestWithZeroRevision() throws Exception {
    long revisionsBefore = revisionRowCount();
    ToolResult result = levy(args(0L, 0L, 121L, 0L, false, 1L));

    assertThat(result.success()).as("cloth 121 > 可用量 120 ⇒ 不成功").isFalse();
    assertThat(result.code()).as("cloth 不足 ⇒ BAD_REQUEST").isEqualTo("BAD_REQUEST");
    assertThat(result.message())
        .as("拒因逐值带 requested / available / 缺口")
        .contains("布总量不足")
        .contains("requested=121")
        .contains("available=120")
        .contains("缺口=1");
    assertThat(head()).as("布不足不得推 head").isEqualTo(1L);
    assertThat(revisionRowCount()).as("布不足不得留 revision").isEqualTo(revisionsBefore);
  }

  /** ★ 单位无管辖 ⇒ BAD_REQUEST（指路 unit.SetJurisdiction），零 revision。 */
  @Test
  void unitWithoutJurisdictionIsBadRequestWithZeroRevision() throws Exception {
    long revisionsBefore = revisionRowCount();
    ToolResult result = levy(argsFor("u-nj", "r-nation", 1L, 0L, 0L, false, 1L));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("BAD_REQUEST");
    assertThat(result.message()).contains("jurisdiction").contains("unit.SetJurisdiction");
    assertThat(head()).isEqualTo(1L);
    assertThat(revisionRowCount()).isEqualTo(revisionsBefore);
  }

  /** ★ 区域不在管辖 ⇒ BAD_REQUEST，零 revision。 */
  @Test
  void regionOutsideJurisdictionIsBadRequestWithZeroRevision() throws Exception {
    long revisionsBefore = revisionRowCount();
    ToolResult result = levy(argsFor("u-1", "r-other", 1L, 0L, 0L, false, 1L));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("BAD_REQUEST");
    assertThat(result.message()).contains("管辖不含区域 r-other");
    assertThat(head()).isEqualTo(1L);
    assertThat(revisionRowCount()).isEqualTo(revisionsBefore);
  }

  /** ★ 过期 expectedRevision ⇒ CONFLICT（报真实 head），零 revision —— 乐观并发不许静默落盘。 */
  @Test
  void staleExpectedRevisionIsConflictWithZeroRevision() throws Exception {
    ToolResult first = levy(args(1L, 0L, 0L, false, 1L));
    assertThat(first.success()).as(first.message()).isTrue();
    assertThat(head()).isEqualTo(2L);
    long revisionsAfterFirst = revisionRowCount();

    ToolResult stale = levy(args(1L, 0L, 0L, false, 1L));

    assertThat(stale.success()).isFalse();
    assertThat(stale.code()).isEqualTo("CONFLICT");
    JsonNode view = JSON.readTree(stale.message());
    assertThat(view.get("submission").get("current").get("revision").asLong())
        .as("冲突必须报真实 head（不是回显调用方的 1）")
        .isEqualTo(2L);
    assertThat(head()).as("冲突不得推 head").isEqualTo(2L);
    assertThat(revisionRowCount()).as("冲突不得留 revision").isEqualTo(revisionsAfterFirst);
  }

  // ── 装置 ────────────────────────────────────────────────────────────────────────────

  /** 经真 GM 工具链执行一次 {@code simos.unit.levyRegion}（GM 面 ⇒ GmAutoApproveGate 直接批准）。 */
  private ToolResult levy(Map<String, Object> arguments) {
    ToolContext gm = Shell.gmCaller();
    ToolContext context =
        new ToolContext(gm.caller(), gm.permissions(), gm.config(), arguments, gm.identity());
    return shell.gmToolAuthorizer().execute(shell.toolRegistry(), LevyRegionTool.NAME, context);
  }

  /** 旧 5 参调用保持兼容（cloth 缺省 0）。 */
  private static Map<String, Object> args(
      long grain, long money, long manpower, boolean preview, long expectedRevision) {
    return argsFor("u-1", "r-nation", grain, money, 0L, manpower, preview, expectedRevision);
  }

  /** 带 cloth 的 6 参调用（grain / money / cloth / manpower 顺序与计划一致）。 */
  private static Map<String, Object> args(
      long grain, long money, long cloth, long manpower, boolean preview, long expectedRevision) {
    return argsFor("u-1", "r-nation", grain, money, cloth, manpower, preview, expectedRevision);
  }

  /** 旧 7 参调用保持兼容（cloth 缺省 0）。 */
  private static Map<String, Object> argsFor(
      String unitId,
      String regionId,
      long grain,
      long money,
      long manpower,
      boolean preview,
      long expectedRevision) {
    return argsFor(unitId, regionId, grain, money, 0L, manpower, preview, expectedRevision);
  }

  private static Map<String, Object> argsFor(
      String unitId,
      String regionId,
      long grain,
      long money,
      long cloth,
      long manpower,
      boolean preview,
      long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", unitId);
    args.put("regionId", regionId);
    args.put("grain", grain);
    args.put("money", money);
    args.put("cloth", cloth);
    args.put("manpower", manpower);
    args.put("reason", REASON);
    args.put("branch", "main");
    args.put("preview", preview);
    args.put("expectedRevision", expectedRevision);
    return args;
  }

  /**
   * 反射调**工具自己的** {@code buildBatch}（私有；批 composition 的唯一可读点）：先生成 {@code LevyRegionPlan.Plan}，再按
   * 真方法组批。参数类型逐字对应生产签名，不复制任何组批逻辑。
   */
  private List<CommandEnvelope> batchOf(long grain, long money, long cloth, long manpower) {
    return batchOfFor("u-1", grain, money, cloth, manpower);
  }

  /** 见 {@link #batchOf}；本重载换抽取主体（u-cap-1 的 cap=1 用例需要）。 */
  private List<CommandEnvelope> batchOfFor(
      String unitId, long grain, long money, long cloth, long manpower) {
    try {
      Class<?> planClass = Class.forName("io.mosire.simos.app.tools.write.LevyRegionPlan");
      Method planMethod =
          planClass.getDeclaredMethod(
              "plan",
              SimulationState.class,
              String.class,
              String.class,
              long.class,
              long.class,
              long.class,
              long.class);
      planMethod.setAccessible(true);
      SimulationState state = stateAt(head());
      Object plan =
          planMethod.invoke(null, state, unitId, "r-nation", grain, money, cloth, manpower);
      Method buildBatch =
          LevyRegionTool.class.getDeclaredMethod(
              "buildBatch",
              String.class,
              plan.getClass(),
              String.class,
              BranchId.class,
              RevisionId.class);
      buildBatch.setAccessible(true);
      // ★ 注册表里的条目可能是包装器；组批方法声明在 LevyRegionTool 上 ⇒ 从 GM 桶取**原始工具实例**。
      LevyRegionTool tool =
          (LevyRegionTool)
              shell.toolsFor(SimosToolSource.Role.GM).stream()
                  .filter(candidate -> LevyRegionTool.NAME.equals(candidate.name()))
                  .findFirst()
                  .orElseThrow();
      @SuppressWarnings("unchecked")
      List<CommandEnvelope> batch =
          (List<CommandEnvelope>)
              buildBatch.invoke(tool, "batch-t2", plan, REASON, main(), new RevisionId(head()));
      return batch;
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("反射调用工具自己的 plan/buildBatch 失败（签名漂移？）", e);
    }
  }

  private static void assertTreasurySource(
      JsonNode entry, String ownerId, long grainDelta, long silverDelta) {
    assertThat(entry.get("owner").get("kind").asText()).isEqualTo("HOUSEHOLD");
    assertThat(entry.get("owner").get("id").asText()).isEqualTo(ownerId);
    assertThat(entry.get("goods").get("grain").asLong()).isEqualTo(grainDelta);
    assertThat(entry.get("money").get("silver").asLong()).isEqualTo(silverDelta);
  }

  private static void assertSeedGroup(JsonNode entry, String id, long countAfter) {
    assertThat(entry.get("id").asText()).isEqualTo(id);
    assertThat(entry.get("sex").asText()).isEqualTo(Sex.MALE.name());
    assertThat(entry.get("count").asLong()).as("整组覆盖为扣后 count").isEqualTo(countAfter);
    assertThat(entry.get("ageDays").asLong()).as("锚点年龄保真").isEqualTo(20L * 365L);
    assertThat(entry.get("anchorTick").asLong()).as("锚点 tick 保真").isZero();
    assertThat(entry.get("stress").asLong()).as("生理压力保真").isZero();
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private long head() {
    return shell.coreSimos().head(main()).orElseThrow().value();
  }

  private SimulationState stateAt(long revision) {
    return shell.coreSimos().replay(ref(revision));
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

  private static boolean isInNation(HexCoord at) {
    return at.equals(H11) || at.equals(H12);
  }

  private static long grainOf(ActorData data, GoodsAccountKey key) {
    GoodsAccount account = data.accounts().get(key);
    return account == null ? 0L : account.balances().getOrDefault(GRAIN, 0L);
  }

  private static long silverOf(ActorData data, GoodsAccountKey key) {
    GoodsAccount account = data.accounts().get(key);
    return account == null ? 0L : account.money().getOrDefault(SILVER, 0L);
  }

  private static long clothOf(ActorData data, GoodsAccountKey key) {
    GoodsAccount account = data.accounts().get(key);
    return account == null ? 0L : account.balances().getOrDefault(CLOTH, 0L);
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
    UnitState units =
        new UnitState(
            new LinkedHashMap<>(
                Map.of(
                    U1, unit(U1, H11, Optional.of(nationJurisdiction())),
                    U_NO_JURISDICTION, unit(U_NO_JURISDICTION, H12, Optional.empty()),
                    U_SMALL_CAPS, unit(U_SMALL_CAPS, H13, Optional.of(smallCapsJurisdiction())))));
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref(1L), T7),
            Map.of(
                "map", new MapSnapshot(ref(1L), T7, map()),
                "unit", new UnitSnapshot(ref(1L), T7, units),
                "social", new SocialSnapshot(ref(1L), T7, social()),
                "sd", new SdSnapshot(ref(1L), T7, SdState.empty()),
                "economy", new EconomySnapshot(ref(1L), T7, EconomyData.empty()),
                "actor", new ActorSnapshot(ref(1L), T7, actors())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref(1L),
            CheckpointEncoder.encode(
                genesis,
                List.of(
                    new MapCodec(),
                    new SocialCodec(),
                    new UnitCodec(),
                    new SdCodec(),
                    new EconomyCodec(),
                    new ActorCodec())));
  }

  private static ActorData actors() {
    return ActorData.empty()
        .withAccount(account(HH1, H11, 100L, 20L, 50L, 100L, 20L))
        .withAccount(account(HH2, H12, 40L, 0L, 80L, 40L, 0L))
        .withAccount(account(HH_OUT, H13, 1000L, 0L, 1000L, 1000L, 0L))
        .withAccount(account(ESTATE, H11, 1000L, 0L, 1000L, 1000L, 0L));
  }

  private static GoodsAccount account(
      ActorRef owner,
      HexCoord at,
      long grain,
      long frozenGrain,
      long silver,
      long cloth,
      long frozenCloth) {
    Map<CommodityId, Long> frozenBalances = new LinkedHashMap<>();
    if (frozenGrain != 0L) {
      frozenBalances.put(GRAIN, frozenGrain);
    }
    if (frozenCloth != 0L) {
      frozenBalances.put(CLOTH, frozenCloth);
    }
    return new GoodsAccount(
        new GoodsAccountKey(owner, at),
        Map.of(GRAIN, grain, CLOTH, cloth),
        Map.of(SILVER, silver),
        frozenBalances,
        Map.of());
  }

  private static SocialData social() {
    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>();
    groups.put(lot("g1"), group("g1", H11, Sex.MALE, 30L));
    groups.put(lot("g2"), group("g2", H12, Sex.MALE, 20L));
    groups.put(lot("g-female"), group("g-female", H11, Sex.FEMALE, 1000L));
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    for (HexCoord at : List.of(H11, H12, H13)) {
      populations.put(at, populationSeries());
    }
    return new SocialData(populations, Map.of(), groups);
  }

  private static PeopleLotId lot(String id) {
    return new PeopleLotId(id);
  }

  private static PopulationGroup group(String id, HexCoord at, Sex sex, long count) {
    return new PopulationGroup(lot(id), at, sex, count, 20L * 365L, 0L);
  }

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 1000L),
        new SegmentedSeries<>(List.of(new Segment<>(T0, 0.0)), List.of(), null),
        List.of());
  }

  private static Jurisdiction nationJurisdiction() {
    return new Jurisdiction(Map.of(NATION, 100L), 1000L, 1000L, 1000L, 0L);
  }

  /** ★ 三条 {@code levy*CapPerCommand} 全是 1：cloth 不受它们约束，粮 / 钱 / 人 仍受。 */
  private static Jurisdiction smallCapsJurisdiction() {
    return new Jurisdiction(Map.of(NATION, 100L), 1L, 1L, 1L, 0L);
  }

  private static Unit unit(UnitId id, HexCoord position, Optional<Jurisdiction> jurisdiction) {
    return new Unit(
        id,
        "第 " + id.value() + " 连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(position))), List.of(), null),
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
        jurisdiction);
  }

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
