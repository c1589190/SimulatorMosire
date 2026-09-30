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
import io.mosire.simos.app.tools.write.IssueDebtTool;
import io.mosire.simos.app.tools.write.RepayDebtTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.ClassFirstAccountId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.classfirst.ClassFirstAccount;
import io.mosire.simos.economy.classfirst.ClassFirstMeta;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.unit.issueDebt} / {@code simos.unit.repayDebt} 的**真 Shell 端到端**（收尾期 T4a）：preview
 * 零写入、apply 一批 = 一条 revision、批内命令类型与顺序、{@code sd.PutInfo} 落点 / key / value（JSON 字符串）/ tick、以及 ★★
 * 放贷方 ↔ 单位国库之间的**守恒式**（lender 余额减少 == 国库增加；双腿 Σ=0 且 |net| == principal）；还款批的国库减少 == 放贷方增加 ==
 * 腿上净额收回量、全额 ⇒ SETTLED；失败零 revision + stale ⇒ CONFLICT。
 *
 * <p>★ <b>本类在 {@code io.mosire.simos.app} 包</b>：{@code Shell#gmCaller()} 是包内可见的装配自检口径（{@code
 * LevyRegionToolTest} 的先例）。工具经真 {@code ToolRegistry} + 真 {@code gmToolAuthorizer} 调用（GM 面 ⇒ 审批无脑过）。
 *
 * <p>★★ <b>批内顺序怎么读</b>：批的 revision 行 {@code command_type} 恒为 {@code core.SubmitBatch}（composition
 * 不落盘） ⇒ 用一条**必然拒**的 {@code sd.PutInfo}（同地址已存在下一个合成 id 的条目）把整批逼成 {@code REJECTED}，再读工具结果里 {@code
 * submission.commands[i].type} —— 那是同一份真批的逐条类型（按批内序），不是复制出来的清单；同时顺带钉住"整批拒 ⇒ 零 revision"。
 *
 * <p>★ 夹具 = 手搭创世 checkpoint（map/social/unit/sd/economy/actor 六切片）：unit + classFirst（lender /
 * 可带既存双腿）+ 单位国库账。工具本身一步都没绕（真 {@code CoreSimos.submitBatch}）。
 */
class UnitDebtToolsTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final int CHECKPOINT_INTERVAL = 100;

  /** 与缺省 initiator 不同，让"写死成别的值"这类变异当场现形。 */
  private static final String TEST_INITIATOR = "agent:t4a-debt";

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final UnitId U1 = new UnitId("u-1");
  private static final ActorRef TREASURY = new ActorRef(ActorKind.UNIT, "u-1");

  private static final String LENDER_ID = "GOV-LENDER";
  private static final String MONEY = PilotModel.MONEY;
  private static final String GRAIN = PilotModel.GRAIN;

  private static final CommodityId GRAIN_ID = new CommodityId(GRAIN);
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private static final ClassFirstAccountId UNIT_MONEY_ID =
      ClassFirstAccountId.idOf("u-1", LENDER_ID, MONEY);
  private static final ClassFirstAccountId MIRROR_MONEY_ID =
      ClassFirstAccountId.idOf(LENDER_ID, "u-1", MONEY);
  private static final ClassFirstAccountId UNIT_GRAIN_ID =
      ClassFirstAccountId.idOf("u-1", LENDER_ID, GRAIN);
  private static final ClassFirstAccountId MIRROR_GRAIN_ID =
      ClassFirstAccountId.idOf(LENDER_ID, "u-1", GRAIN);

  private static final String REASON = "T4a 地方债守恒";

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final List<io.mosire.simos.util.spi.ModuleCodec> GENESIS_CODECS =
      List.of(
          new MapCodec(),
          new SocialCodec(),
          new UnitCodec(),
          new SdCodec(),
          new EconomyCodec(),
          new ActorCodec());

  @TempDir Path tempDir;

  private final List<Shell> shells = new ArrayList<>();
  private int worldSeq;

  @AfterEach
  void stopShells() {
    for (Shell shell : shells) {
      shell.close();
    }
  }

  // ── preview：零写入 ─────────────────────────────────────────────────────────────────

  /**
   * ★ preview（缺省 preview=true，不传 expectedRevision）只算不写：视图数字齐（debtBefore/After、lender before/after、
   * 国库落点），但 head / revision 行数 / actor 账 / classFirst 账 / sd INFO 一字不动。
   */
  @Test
  void issuePreviewComputesTheViewWithoutWritingAnything() throws Exception {
    World world = issueWorld();
    long headBefore = head(world);
    long rowsBefore = revisionRowCount(world);
    ActorData actorsBefore = actorData(stateAt(world, headBefore));
    ClassFirstState classFirstBefore = economyData(stateAt(world, headBefore)).classFirst();

    ToolResult result =
        execute(
            world,
            IssueDebtTool.NAME,
            issueArgs("u-1", LENDER_ID, MONEY, 1000L, 20L, 50L, null, null));

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode view = JSON.readTree(result.message());
    assertThat(view.get("preview").asBoolean()).as("preview 缺省 true").isTrue();
    assertThat(view.get("submitted").asBoolean()).isFalse();
    assertThat(view.get("tick").asLong()).isEqualTo(7L);
    assertThat(view.get("unitId").asText()).isEqualTo("u-1");
    assertThat(view.get("lenderId").asText()).isEqualTo(LENDER_ID);
    assertThat(view.get("unit").asText()).isEqualTo(MONEY);
    assertThat(view.get("principal").asLong()).isEqualTo(1000L);
    assertThat(view.get("debtBefore").asLong()).isZero();
    assertThat(view.get("debtAfter").asLong()).isEqualTo(1000L);
    assertThat(view.get("lenderAvailableBefore").asLong()).isEqualTo(10_000L);
    assertThat(view.get("lenderAvailableAfter").asLong()).isEqualTo(9000L);
    assertThat(view.get("treasuryLocation").get("q").asInt()).isEqualTo(1);
    assertThat(view.get("treasuryLocation").get("r").asInt()).isEqualTo(1);

    assertThat(head(world)).as("preview 不得推 head").isEqualTo(headBefore);
    assertThat(revisionRowCount(world)).as("preview 不得留 revision").isEqualTo(rowsBefore);
    SimulationState still = stateAt(world, headBefore);
    assertThat(actorData(still)).as("actor 账一字未动").isEqualTo(actorsBefore);
    assertThat(economyData(still).classFirst()).as("classFirst 账一字未动").isEqualTo(classFirstBefore);
    assertThat(sdState(still).info()).as("preview 不得落 INFO").isEmpty();
  }

  // ── issue apply：一批 = 一条 revision + 落点 / value / 守恒 ───────────────────────────

  /**
   * ★ money apply 结构面 + 守恒式：head 1→2、revision 行数 +1；放贷方 money 10000→8000、国库 silver 500→2500，且
   * <b>放贷方减少量 == 国库增加量 == principal</b>；双腿 Σ=0 且 |net| == principal；{@code sd.PutInfo} 的
   * address/key/value/tick/note 逐值。
   */
  @Test
  void issueApplyMoneyWritesOneRevisionAndConservesTreasury() throws Exception {
    World world = issueWorld();
    long rowsBefore = revisionRowCount(world);

    ToolResult result =
        execute(
            world,
            IssueDebtTool.NAME,
            issueArgs("u-1", LENDER_ID, MONEY, 2000L, 20L, 50L, "unit-debt", head(world)));

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode view = JSON.readTree(result.message());
    assertThat(view.get("preview").asBoolean()).isFalse();
    assertThat(view.get("submitted").asBoolean()).isTrue();
    assertThat(view.get("submission").get("result").asText()).isEqualTo("committed");
    assertThat(view.get("submission").get("ref").get("revision").asLong()).isEqualTo(2L);

    assertThat(head(world)).as("一批只前进一格").isEqualTo(2L);
    assertThat(revisionRowCount(world)).as("一批只多一行 revision").isEqualTo(rowsBefore + 1L);

    SimulationState after = stateAt(world, 2L);
    ClassFirstState classFirst = economyData(after).classFirst();
    PilotModel.Lender lenderBefore =
        economyData(stateAt(world, 1L)).classFirst().lenders().get(ExternalLenderId.of(LENDER_ID));
    PilotModel.Lender lenderAfter = classFirst.lenders().get(ExternalLenderId.of(LENDER_ID));
    assertThat(lenderAfter.money())
        .as("放贷方 money 逐值 −principal")
        .isEqualTo(lenderBefore.money() - 2000L);
    assertThat(lenderAfter.money()).isEqualTo(8000L);

    ClassFirstAccount debt = classFirst.accounts().get(UNIT_MONEY_ID);
    ClassFirstAccount mirror = classFirst.accounts().get(MIRROR_MONEY_ID);
    assertThat(debt).as("借款腿").isNotNull();
    assertThat(debt.id()).isEqualTo(ClassFirstAccountId.idOf("u-1", LENDER_ID, MONEY));
    assertThat(debt.ownerId()).isEqualTo("u-1");
    assertThat(debt.counterpartyId()).isEqualTo(LENDER_ID);
    assertThat(debt.unit()).isEqualTo(MONEY);
    assertThat(debt.terms()).isEqualTo("unit-debt");
    assertThat(debt.interestRatePerMille()).isEqualTo(20L);
    assertThat(debt.nextDueTick()).isEqualTo(50L);
    assertThat(debt.cumulativeNet()).as("单位负债为负").isEqualTo(-2000L);
    assertThat(debt.interestAccrued()).isZero();
    assertThat(debt.status()).isEqualTo(PilotModel.AccountStatus.ACTIVE);
    assertThat(mirror.cumulativeNet()).as("镜像腿 = +principal").isEqualTo(2000L);
    assertThat(mirror.ownerId()).isEqualTo(LENDER_ID);
    assertThat(mirror.counterpartyId()).isEqualTo("u-1");
    assertThat(debt.cumulativeNet() + mirror.cumulativeNet()).as("双腿 Σ=0").isZero();
    assertThat(Math.abs(debt.cumulativeNet())).as("|net| == principal").isEqualTo(2000L);

    ActorData actorsBefore = actorData(stateAt(world, 1L));
    ActorData actorsAfter = actorData(after);
    GoodsAccountKey treasuryKey = new GoodsAccountKey(TREASURY, H11);
    long silverBefore = silverOf(actorsBefore, treasuryKey);
    long silverAfter = silverOf(actorsAfter, treasuryKey);
    assertThat(silverAfter).as("国库 silver 500 + 2000").isEqualTo(2500L);
    assertThat(lenderBefore.money() - lenderAfter.money())
        .as("守恒式：放贷方余额减少量 == 国库账增加量")
        .isEqualTo(silverAfter - silverBefore);
    assertThat(silverAfter - silverBefore).as("差额 == principal").isEqualTo(2000L);

    SdState sd = sdState(after);
    assertThat(sd.info()).containsOnlyKeys("unit:u-1");
    List<SdInfoEntry> entries = sd.info().get("unit:u-1");
    assertThat(entries).hasSize(1);
    SdInfoEntry entry = entries.get(0);
    assertThat(entry.key()).isEqualTo(IssueDebtTool.INFO_KEY);
    assertThat(entry.key()).isEqualTo("issueDebt");
    assertThat(entry.tick()).isEqualTo(7L);
    assertThat(entry.value()).as("sd 里 value 是 JSON 字符串").isInstanceOf(String.class);
    JsonNode value = JSON.readTree((String) entry.value());
    assertThat(value.get("unitId").asText()).isEqualTo("u-1");
    assertThat(value.get("lenderId").asText()).isEqualTo(LENDER_ID);
    assertThat(value.get("unit").asText()).isEqualTo(MONEY);
    assertThat(value.get("principal").asLong()).isEqualTo(2000L);
    assertThat(value.get("interestRatePerMille").asLong()).isEqualTo(20L);
    assertThat(value.get("nextDueTick").asLong()).isEqualTo(50L);
    assertThat(value.get("terms").asText()).isEqualTo("unit-debt");
    assertThat(value.get("tick").asLong()).isEqualTo(7L);
    assertThat(value.get("debtBefore").asLong()).isZero();
    assertThat(value.get("debtAfter").asLong()).isEqualTo(2000L);
    assertThat(value.get("lenderAvailableBefore").asLong()).isEqualTo(10_000L);
    assertThat(value.get("lenderAvailableAfter").asLong()).isEqualTo(8000L);
    assertThat(value.get("reason").asText()).isEqualTo(REASON);
    assertThat(entry.note()).isPresent();
    assertThat(entry.note().orElseThrow()).contains("u-1").contains(LENDER_ID).contains(REASON);
  }

  /** ★ grain apply 守恒式：放贷方 goods.grain 500→300、国库 grain 300→500，减少量 == 增加量 == principal。 */
  @Test
  void issueApplyGrainConservesTreasuryGoods() throws Exception {
    World world = issueWorld();
    long rowsBefore = revisionRowCount(world);

    ToolResult result =
        execute(
            world,
            IssueDebtTool.NAME,
            issueArgs("u-1", LENDER_ID, GRAIN, 200L, 7L, 44L, "harvest-loan", head(world)));

    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(head(world)).isEqualTo(2L);
    assertThat(revisionRowCount(world)).isEqualTo(rowsBefore + 1L);

    SimulationState before = stateAt(world, 1L);
    SimulationState after = stateAt(world, 2L);
    PilotModel.Lender lenderBefore =
        economyData(before).classFirst().lenders().get(ExternalLenderId.of(LENDER_ID));
    PilotModel.Lender lenderAfter =
        economyData(after).classFirst().lenders().get(ExternalLenderId.of(LENDER_ID));
    long lenderGrainBefore = lenderBefore.goods().getOrDefault(GRAIN, 0L);
    long lenderGrainAfter = lenderAfter.goods().getOrDefault(GRAIN, 0L);
    assertThat(lenderGrainAfter).as("放贷方 grain 500 − 200").isEqualTo(300L);

    GoodsAccountKey treasuryKey = new GoodsAccountKey(TREASURY, H11);
    long treasuryGrainBefore = grainOf(actorData(before), treasuryKey);
    long treasuryGrainAfter = grainOf(actorData(after), treasuryKey);
    assertThat(treasuryGrainAfter).as("国库 grain 300 + 200").isEqualTo(500L);
    assertThat(lenderGrainBefore - lenderGrainAfter)
        .as("守恒式（grain）：放贷方减少量 == 国库增加量")
        .isEqualTo(treasuryGrainAfter - treasuryGrainBefore);
    assertThat(treasuryGrainAfter - treasuryGrainBefore).isEqualTo(200L);

    ClassFirstState classFirst = economyData(after).classFirst();
    ClassFirstAccount debt = classFirst.accounts().get(UNIT_GRAIN_ID);
    ClassFirstAccount mirror = classFirst.accounts().get(MIRROR_GRAIN_ID);
    assertThat(debt.cumulativeNet()).isEqualTo(-200L);
    assertThat(mirror.cumulativeNet()).isEqualTo(200L);
    assertThat(debt.cumulativeNet() + mirror.cumulativeNet()).isZero();
    assertThat(Math.abs(debt.cumulativeNet())).isEqualTo(200L);
    assertThat(debt.terms()).as("显式 terms 逐字落账").isEqualTo("harvest-loan");
  }

  // ── repay apply：顺序 / 守恒 / 全额 SETTLED ──────────────────────────────────────────

  /**
   * ★ repay 部分还款 + 守恒：head 1→2；批内顺序（从被甩落的拒绝视图读）{@code AdjustAccounts → UnitRepay → PutInfo}；国库
   * silver 1000→600、放贷方 money 10000→10400、双腿 −1000/+1000 → −600/+600；<b>国库减少 == 放贷方增加 == 腿上净额收回量 ==
   * amount</b>；{@code sd.PutInfo} key=repayDebt、value 数字齐、settled=false。
   */
  @Test
  void repayApplyIsOrderedAndConservesTreasuryAgainstTheLegs() throws Exception {
    World world = debtWorld(1000L, 1000L, 300L);
    // 批内顺序：sd.PutInfo 被一条同 id 的已存在条目逼拒 ⇒ 整批 REJECTED，但 submission.commands 按真批内序逐条摆出。
    ToolResult decoy = submitDecoy(world);
    assertThat(decoy.success()).as("前置 decoy 必须成功").isTrue();
    long rowsAfterDecoy = revisionRowCount(world);
    ToolResult rejected =
        execute(
            world,
            RepayDebtTool.NAME,
            repayArgs("u-1", LENDER_ID, MONEY, 400L, false, head(world)));
    assertThat(rejected.success()).isFalse();
    assertThat(rejected.code()).as("整批拒走 REJECTED（不是 BAD_REQUEST）").isEqualTo("REJECTED");
    assertThat(commandTypesOf(rejected))
        .as("批内顺序：先出国库款、再销债、最后行动记录")
        .containsExactly("actor.AdjustAccounts", "economy.UnitRepay", "sd.PutInfo");
    assertThat(head(world)).as("整批拒 ⇒ 零 revision").isEqualTo(2L);
    assertThat(revisionRowCount(world)).isEqualTo(rowsAfterDecoy);

    // 重新从 decoy 前的世界来一次成功 repay：重新起一个干净 world（避免 decoy 的 SD 条目污染成功路径）。
    World clean = debtWorld(1000L, 1000L, 300L);
    long cleanRowsBefore = revisionRowCount(clean);
    ToolResult result =
        execute(
            clean,
            RepayDebtTool.NAME,
            repayArgs("u-1", LENDER_ID, MONEY, 400L, false, head(clean)));
    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(head(clean)).isEqualTo(2L);
    assertThat(revisionRowCount(clean)).isEqualTo(cleanRowsBefore + 1L);

    SimulationState before = stateAt(clean, 1L);
    SimulationState after = stateAt(clean, 2L);
    PilotModel.Lender lenderBefore =
        economyData(before).classFirst().lenders().get(ExternalLenderId.of(LENDER_ID));
    PilotModel.Lender lenderAfter =
        economyData(after).classFirst().lenders().get(ExternalLenderId.of(LENDER_ID));
    ClassFirstAccount debtBefore = economyData(before).classFirst().accounts().get(UNIT_MONEY_ID);
    ClassFirstAccount debtAfter = economyData(after).classFirst().accounts().get(UNIT_MONEY_ID);
    ClassFirstAccount mirrorAfter = economyData(after).classFirst().accounts().get(MIRROR_MONEY_ID);
    long recoveredByLegs = debtAfter.cumulativeNet() - debtBefore.cumulativeNet();
    assertThat(recoveredByLegs).as("腿上净额收回量：借款腿 −1000 → −600").isEqualTo(400L);

    GoodsAccountKey treasuryKey = new GoodsAccountKey(TREASURY, H11);
    long treasuryBefore = silverOf(actorData(before), treasuryKey);
    long treasuryAfter = silverOf(actorData(after), treasuryKey);
    long treasuryDecrease = treasuryBefore - treasuryAfter;
    long lenderIncrease = lenderAfter.money() - lenderBefore.money();
    assertThat(treasuryAfter).as("国库 silver 1000 − 400").isEqualTo(600L);
    assertThat(lenderAfter.money()).isEqualTo(10_400L);
    assertThat(treasuryDecrease).as("国库减少 == amount").isEqualTo(400L);
    assertThat(lenderIncrease).as("放贷方增加 == amount").isEqualTo(400L);
    assertThat(recoveredByLegs).as("腿上净额收回量 == amount").isEqualTo(400L);
    assertThat(treasuryDecrease - lenderIncrease).as("国库减少 == 放贷方增加").isZero();
    assertThat(recoveredByLegs - treasuryDecrease).as("腿上收回量 == 国库减少").isZero();
    assertThat(debtAfter.cumulativeNet()).isEqualTo(-600L);
    assertThat(mirrorAfter.cumulativeNet()).isEqualTo(600L);
    assertThat(debtAfter.cumulativeNet() + mirrorAfter.cumulativeNet()).isZero();

    JsonNode view = JSON.readTree(result.message());
    assertThat(view.get("outstandingBefore").asLong()).isEqualTo(1000L);
    assertThat(view.get("outstandingAfter").asLong()).isEqualTo(600L);
    assertThat(view.get("settled").asBoolean()).isFalse();
    assertThat(view.get("treasuryAvailableBefore").asLong()).isEqualTo(1000L);
    assertThat(view.get("treasuryAvailableAfter").asLong()).isEqualTo(600L);
    assertThat(view.get("lenderAvailableBefore").asLong()).isEqualTo(10_000L);
    assertThat(view.get("lenderAvailableAfter").asLong()).isEqualTo(10_400L);

    SdState sd = sdState(after);
    List<SdInfoEntry> entries = sd.info().get("unit:u-1");
    assertThat(entries).hasSize(1);
    SdInfoEntry entry = entries.get(0);
    assertThat(entry.key()).isEqualTo(RepayDebtTool.INFO_KEY);
    assertThat(entry.key()).isEqualTo("repayDebt");
    assertThat(entry.tick()).isEqualTo(7L);
    assertThat(entry.value()).isInstanceOf(String.class);
    JsonNode value = JSON.readTree((String) entry.value());
    assertThat(value.get("amount").asLong()).isEqualTo(400L);
    assertThat(value.get("outstandingBefore").asLong()).isEqualTo(1000L);
    assertThat(value.get("outstandingAfter").asLong()).isEqualTo(600L);
    assertThat(value.get("settled").asBoolean()).isFalse();
    assertThat(value.get("treasuryAvailableBefore").asLong()).isEqualTo(1000L);
    assertThat(value.get("treasuryAvailableAfter").asLong()).isEqualTo(600L);
    assertThat(value.get("reason").asText()).isEqualTo(REASON);
  }

  /** ★ 全额还款 ⇒ 双腿 {@code SETTLED}、净额 0；国库减少 == 放贷方增加 == 全额；视图 settled=true。 */
  @Test
  void fullRepaySettlesBothLegsAndConservesTheWholeAmount() throws Exception {
    World world = debtWorld(1000L, 1000L, 300L);

    ToolResult result =
        execute(
            world,
            RepayDebtTool.NAME,
            repayArgs("u-1", LENDER_ID, MONEY, 1000L, false, head(world)));
    assertThat(result.success()).as(result.message()).isTrue();

    SimulationState before = stateAt(world, 1L);
    SimulationState after = stateAt(world, 2L);
    PilotModel.Lender lenderBefore =
        economyData(before).classFirst().lenders().get(ExternalLenderId.of(LENDER_ID));
    PilotModel.Lender lenderAfter =
        economyData(after).classFirst().lenders().get(ExternalLenderId.of(LENDER_ID));
    GoodsAccountKey treasuryKey = new GoodsAccountKey(TREASURY, H11);
    long treasuryBefore = silverOf(actorData(before), treasuryKey);
    long treasuryAfter = silverOf(actorData(after), treasuryKey);

    assertThat(treasuryBefore - treasuryAfter).as("国库减少 == 全额").isEqualTo(1000L);
    assertThat(lenderAfter.money() - lenderBefore.money()).as("放贷方增加 == 全额").isEqualTo(1000L);
    assertThat(lenderAfter.money()).isEqualTo(11_000L);
    assertThat(treasuryAfter).isZero();

    ClassFirstAccount debt = economyData(after).classFirst().accounts().get(UNIT_MONEY_ID);
    ClassFirstAccount mirror = economyData(after).classFirst().accounts().get(MIRROR_MONEY_ID);
    assertThat(debt.cumulativeNet()).isZero();
    assertThat(mirror.cumulativeNet()).isZero();
    assertThat(debt.status()).isEqualTo(PilotModel.AccountStatus.SETTLED);
    assertThat(mirror.status()).isEqualTo(PilotModel.AccountStatus.SETTLED);

    JsonNode view = JSON.readTree(result.message());
    assertThat(view.get("outstandingAfter").asLong()).isZero();
    assertThat(view.get("settled").asBoolean()).isTrue();
  }

  /** ★ repay 的 preview 分支同样零写入：视图数字齐，head / 行数 / classFirst / actor / sd 一字不动。 */
  @Test
  void repayPreviewComputesTheViewWithoutWritingAnything() throws Exception {
    World world = debtWorld(1000L, 1000L, 300L);
    long headBefore = head(world);
    long rowsBefore = revisionRowCount(world);
    ClassFirstState classFirstBefore = economyData(stateAt(world, headBefore)).classFirst();
    ActorData actorsBefore = actorData(stateAt(world, headBefore));

    ToolResult result =
        execute(world, RepayDebtTool.NAME, repayArgs("u-1", LENDER_ID, MONEY, 400L, true, -1L));

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode view = JSON.readTree(result.message());
    assertThat(view.get("preview").asBoolean()).isTrue();
    assertThat(view.get("submitted").asBoolean()).isFalse();
    assertThat(view.get("outstandingBefore").asLong()).isEqualTo(1000L);
    assertThat(view.get("outstandingAfter").asLong()).isEqualTo(600L);
    assertThat(view.get("settled").asBoolean()).isFalse();
    assertThat(view.get("treasuryAvailableBefore").asLong()).isEqualTo(1000L);
    assertThat(view.get("treasuryAvailableAfter").asLong()).isEqualTo(600L);
    assertThat(view.get("lenderAvailableBefore").asLong()).isEqualTo(10_000L);
    assertThat(view.get("lenderAvailableAfter").asLong()).isEqualTo(10_400L);

    assertThat(head(world)).as("preview 不得推 head").isEqualTo(headBefore);
    assertThat(revisionRowCount(world)).as("preview 不得留 revision").isEqualTo(rowsBefore);
    SimulationState still = stateAt(world, headBefore);
    assertThat(economyData(still).classFirst()).as("classFirst 账一字未动").isEqualTo(classFirstBefore);
    assertThat(actorData(still)).as("actor 账一字未动").isEqualTo(actorsBefore);
    assertThat(sdState(still).info()).as("preview 不得落 INFO").isEmpty();
  }

  // ── 批内顺序：issue（sd.PutInfo 被逼拒）──────────────────────────────────────────────

  @Test
  void issueBatchOrderIsBorrowThenAdjustThenPutInfo() throws Exception {
    World world = issueWorld();
    ToolResult decoy = submitDecoy(world);
    assertThat(decoy.success()).isTrue();
    long rowsAfterDecoy = revisionRowCount(world);

    ToolResult rejected =
        execute(
            world,
            IssueDebtTool.NAME,
            issueArgs("u-1", LENDER_ID, MONEY, 1000L, 20L, 50L, "unit-debt", 2L));

    assertThat(rejected.success()).isFalse();
    assertThat(rejected.code()).isEqualTo("REJECTED");
    assertThat(commandTypesOf(rejected))
        .as("批内顺序：借入 → 国库入账 → 行动记录")
        .containsExactly("economy.UnitBorrow", "actor.AdjustAccounts", "sd.PutInfo");
    assertThat(head(world)).as("整批拒 ⇒ 零 revision").isEqualTo(2L);
    assertThat(revisionRowCount(world)).isEqualTo(rowsAfterDecoy);
  }

  // ── 失败：零 revision ──────────────────────────────────────────────────────────────

  @Test
  void issueWithoutClassFirstIsBadRequestWithZeroRevision() {
    World world =
        startWorld(
            "no-classfirst", EconomyData.empty(), treasury(500L, 0L, 300L, 0L), SdState.empty());
    long rowsBefore = revisionRowCount(world);

    ToolResult result =
        execute(
            world,
            IssueDebtTool.NAME,
            issueArgs("u-1", LENDER_ID, MONEY, 100L, 20L, 50L, null, 1L));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("BAD_REQUEST");
    assertThat(result.message()).contains("class-first").contains("economy.UnitBorrow");
    assertThat(head(world)).isEqualTo(1L);
    assertThat(revisionRowCount(world)).as("失败不得留 revision").isEqualTo(rowsBefore);
  }

  @Test
  void issueAboveLendableAmountIsBadRequestWithZeroRevision() {
    World world = issueWorld();
    long rowsBefore = revisionRowCount(world);

    ToolResult result =
        execute(
            world,
            IssueDebtTool.NAME,
            issueArgs("u-1", LENDER_ID, MONEY, 10_001L, 20L, 50L, null, 1L));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("BAD_REQUEST");
    assertThat(result.message()).contains("放贷方可贷").contains("money").contains("available=10000");
    assertThat(head(world)).isEqualTo(1L);
    assertThat(revisionRowCount(world)).isEqualTo(rowsBefore);
  }

  @Test
  void repayOverpaymentIsBadRequestWithZeroRevision() {
    World world = debtWorld(1000L, 1000L, 300L);
    long rowsBefore = revisionRowCount(world);

    ToolResult result =
        execute(world, RepayDebtTool.NAME, repayArgs("u-1", LENDER_ID, MONEY, 1001L, false, 1L));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("BAD_REQUEST");
    assertThat(result.message()).contains("超过未结清负债").contains("1001").contains("1000");
    assertThat(head(world)).isEqualTo(1L);
    assertThat(revisionRowCount(world)).isEqualTo(rowsBefore);
  }

  @Test
  void repayWithInsufficientTreasuryIsBadRequestWithZeroRevision() {
    World world = debtWorld(1000L, 100L, 300L);
    long rowsBefore = revisionRowCount(world);

    ToolResult result =
        execute(world, RepayDebtTool.NAME, repayArgs("u-1", LENDER_ID, MONEY, 1000L, false, 1L));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("BAD_REQUEST");
    assertThat(result.message()).contains("国库可支配").contains("money").contains("available=100");
    assertThat(head(world)).isEqualTo(1L);
    assertThat(revisionRowCount(world)).isEqualTo(rowsBefore);
  }

  /** ★ 过期 expectedRevision ⇒ CONFLICT（报真实 head 2），零 revision —— 乐观并发不许静默落盘。 */
  @Test
  void staleExpectedRevisionIsConflictWithTheRealHead() throws Exception {
    World world = issueWorld();
    ToolResult first =
        execute(
            world,
            IssueDebtTool.NAME,
            issueArgs("u-1", LENDER_ID, MONEY, 1000L, 20L, 50L, null, 1L));
    assertThat(first.success()).as(first.message()).isTrue();
    assertThat(head(world)).isEqualTo(2L);
    long rowsAfterFirst = revisionRowCount(world);

    ToolResult stale =
        execute(
            world,
            IssueDebtTool.NAME,
            issueArgs("u-1", LENDER_ID, MONEY, 1000L, 20L, 50L, null, 1L));

    assertThat(stale.success()).isFalse();
    assertThat(stale.code()).isEqualTo("CONFLICT");
    JsonNode view = JSON.readTree(stale.message());
    assertThat(view.get("submission").get("current").get("revision").asLong())
        .as("冲突必须报真实 head（不是回显调用方的 1）")
        .isEqualTo(2L);
    assertThat(head(world)).as("冲突不得推 head").isEqualTo(2L);
    assertThat(revisionRowCount(world)).as("冲突不得留 revision").isEqualTo(rowsAfterFirst);
  }

  // ── 装置 / 夹具 ─────────────────────────────────────────────────────────────────────

  /** 一次工具调用的世界（真 Shell + store 路径；rev 行数用后者数）。 */
  private record World(Shell shell, Path storeDir) {}

  /** 经真 GM 工具链执行一次工具（GM 面 ⇒ GmAutoApproveGate 直接批准）。 */
  private ToolResult execute(World world, String toolName, Map<String, Object> arguments) {
    ToolContext gm = Shell.gmCaller();
    ToolContext context =
        new ToolContext(gm.caller(), gm.permissions(), gm.config(), arguments, gm.identity());
    return world
        .shell()
        .gmToolAuthorizer()
        .execute(world.shell().toolRegistry(), toolName, context);
  }

  private World issueWorld() {
    return startWorld(
        "issue", economyWith(lender()), treasury(500L, 0L, 300L, 0L), SdState.empty());
  }

  private World debtWorld(long principal, long silver, long grain) {
    ClassFirstAccount debt =
        leg(
            UNIT_MONEY_ID,
            "u-1",
            LENDER_ID,
            MONEY,
            -principal,
            50L,
            PilotModel.AccountStatus.ACTIVE);
    ClassFirstAccount mirror =
        leg(
            MIRROR_MONEY_ID,
            LENDER_ID,
            "u-1",
            MONEY,
            principal,
            50L,
            PilotModel.AccountStatus.ACTIVE);
    return startWorld(
        "debt-money-" + principal,
        economyWith(lender(), debt, mirror),
        treasury(silver, 0L, grain, 0L),
        SdState.empty());
  }

  private World startWorld(String label, EconomyData economy, ActorData actors, SdState sd) {
    Path storeDir = tempDir.resolve(label + "-" + worldSeq++);
    seedGenesis(storeDir, economy, actors, sd);
    ShellConfig base = ShellConfig.defaults(storeDir).withPorts(0, 0, 0);
    Shell shell =
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
    shells.add(shell);
    return new World(shell, storeDir);
  }

  /** 手搭创世 checkpoint：六切片（map/social/unit/sd/economy/actor）落 revision 1。 */
  private void seedGenesis(Path storeDir, EconomyData economy, ActorData actors, SdState sd) {
    try {
      java.nio.file.Files.createDirectories(storeDir);
    } catch (IOException e) {
      throw new AssertionError("创建测试 store 目录失败: " + storeDir, e);
    }
    try (SqliteStore seedStore = SqliteStore.open(storeDir.resolve(CoreSimos.DB_FILE_NAME))) {
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
                "map", new MapSnapshot(ref(1L), T7, GameMap.empty()),
                "unit", new UnitSnapshot(ref(1L), T7, unitState()),
                "social", new SocialSnapshot(ref(1L), T7, SocialData.empty()),
                "sd", new SdSnapshot(ref(1L), T7, sd),
                "economy", new EconomySnapshot(ref(1L), T7, economy),
                "actor", new ActorSnapshot(ref(1L), T7, actors)),
            InMemoryInfoSystem.empty());
    new CheckpointStore(storeDir).write(ref(1L), CheckpointEncoder.encode(genesis, GENESIS_CODECS));
  }

  private static PilotModel.Lender lender() {
    return new PilotModel.Lender(
        LENDER_ID, 10_000L, Map.of(GRAIN, 500L, "cloth", 7L), 20L, 60L, 1000L);
  }

  private static ClassFirstAccount leg(
      ClassFirstAccountId id,
      String owner,
      String counterparty,
      String unit,
      long net,
      long interestAccrued,
      PilotModel.AccountStatus status) {
    return new ClassFirstAccount(
        id, owner, counterparty, unit, "unit-debt", 20L, 50L, net, interestAccrued, status);
  }

  private static EconomyData economyWith(PilotModel.Lender lender, ClassFirstAccount... legs) {
    LinkedHashMap<ClassFirstAccountId, ClassFirstAccount> accounts = new LinkedHashMap<>();
    for (ClassFirstAccount leg : legs) {
      accounts.put(leg.id(), leg);
    }
    ClassFirstState classFirst =
        new ClassFirstState(
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            accounts,
            Map.of(ExternalLenderId.of(lender.id()), lender),
            ClassFirstMeta.empty());
    return EconomyData.empty().withClassFirst(classFirst);
  }

  private static ActorData treasury(long silver, long frozenSilver, long grain, long frozenGrain) {
    GoodsAccount account =
        new GoodsAccount(
            new GoodsAccountKey(TREASURY, H11),
            Map.of(GRAIN_ID, grain),
            Map.of(SILVER, silver),
            frozenGrain == 0L ? Map.of() : Map.of(GRAIN_ID, frozenGrain),
            frozenSilver == 0L ? Map.of() : Map.of(SILVER, frozenSilver));
    return ActorData.empty().withAccount(account);
  }

  private static UnitState unitState() {
    Unit unit =
        new Unit(
            U1,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
            100,
            Map.of("步枪", 50),
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
    return new UnitState(Map.of(U1, unit));
  }

  private static Map<String, Object> issueArgs(
      String unitId,
      String lenderId,
      String unit,
      long principal,
      long interestRatePerMille,
      long nextDueTick,
      String terms,
      Long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", unitId);
    args.put("lenderId", lenderId);
    args.put("unit", unit);
    args.put("principal", principal);
    args.put("interestRatePerMille", interestRatePerMille);
    args.put("nextDueTick", nextDueTick);
    args.put("reason", REASON);
    args.put("branch", "main");
    if (terms != null) {
      args.put("terms", terms);
    }
    if (expectedRevision != null) {
      args.put("preview", false);
      args.put("expectedRevision", expectedRevision);
    }
    return args;
  }

  private static Map<String, Object> repayArgs(
      String unitId,
      String lenderId,
      String unit,
      long amount,
      boolean preview,
      long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", unitId);
    args.put("lenderId", lenderId);
    args.put("unit", unit);
    args.put("amount", amount);
    args.put("reason", REASON);
    args.put("branch", "main");
    args.put("preview", preview);
    if (!preview) {
      args.put("expectedRevision", expectedRevision);
    }
    return args;
  }

  /** 经通用写工具（真 GM 路径）种一条 decoy INFO，令目标工具的 {@code sd.PutInfo} 必然撞 id。 */
  private ToolResult submitDecoy(World world) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", "sd.PutInfo");
    args.put("payloadJson", decoyPutInfoPayload());
    args.put("branch", "main");
    args.put("expectedRevision", head(world));
    return execute(world, "simos.command.submit", args);
  }

  /**
   * decoy INFO 载荷：显式 id {@code unit:u-1#0}（= 目标工具下次合成出来的同一个 id）⇒ 目标工具的 {@code sd.PutInfo}
   * 必然拒，整批因此可以读出逐条命令序。
   */
  private static String decoyPutInfoPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", "map:Map1");
    payload.put("key", "decoy");
    payload.put("value", "decoy");
    payload.put("id", "unit:u-1#0");
    try {
      return JSON.writeValueAsString(payload);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError(e);
    }
  }

  private static List<String> commandTypesOf(ToolResult rejected) throws Exception {
    JsonNode commands = JSON.readTree(rejected.message()).get("submission").get("commands");
    List<String> types = new ArrayList<>();
    commands.forEach(command -> types.add(command.get("type").asText()));
    return types;
  }

  private long head(World world) {
    return world.shell().coreSimos().head(main()).orElseThrow().value();
  }

  private SimulationState stateAt(World world, long revision) {
    return world.shell().coreSimos().replay(ref(revision));
  }

  private long revisionRowCount(World world) {
    try (SqliteStore store = SqliteStore.open(world.storeDir().resolve(CoreSimos.DB_FILE_NAME))) {
      return store.inTransaction(
          connection -> {
            try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM revisions")) {
              return rows.next() ? rows.getLong(1) : 0L;
            }
          });
    }
  }

  private static ActorData actorData(SimulationState state) {
    return ((ActorSnapshot) state.module("actor").orElseThrow()).data();
  }

  private static EconomyData economyData(SimulationState state) {
    return ((EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  private static SdState sdState(SimulationState state) {
    return ((SdSnapshot) state.module("sd").orElseThrow()).state();
  }

  private static long silverOf(ActorData data, GoodsAccountKey key) {
    GoodsAccount account = data.accounts().get(key);
    return account == null ? 0L : account.money().getOrDefault(SILVER, 0L);
  }

  private static long grainOf(ActorData data, GoodsAccountKey key) {
    GoodsAccount account = data.accounts().get(key);
    return account == null ? 0L : account.balances().getOrDefault(GRAIN_ID, 0L);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(long revision) {
    return new StateRef(main(), new RevisionId(revision));
  }
}
