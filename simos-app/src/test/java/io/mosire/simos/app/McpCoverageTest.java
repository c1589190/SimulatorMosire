package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.app.tools.write.CommandSubmitTool;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Event;
import io.mosire.simos.util.time.EventMode;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.time.Duration;
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
 * ★★ **判据②（spec §1.1 / §10.2 / §11 R5）的端到端验收（M5 T11）**：{@code simos.command.catalog} 列出的**每一个**
 * 已注册命令类型，都能经 MCP 工具面（{@code simos.command.submit}）提交并真的生效；{@code simos.advance} 与 {@code
 * simos.fork} 同样可达且有效；反向：坏载荷 ⇒ {@code Rejected} 且**不留 revision**。
 *
 * <p>★ **判别力来源**：catalog 的 type 集合与"逐类提交后分支 head 恰好前进该类型数"两侧同时钉住——任何一条命令没到（工具面返回 unsupported /
 * 类型未注册 / 载荷约定不符）都会让 head 对不上或该条直接报错。每条提交的结局逐类记在 {@code [T11-COVERAGE]} 行里。
 *
 * <p>★ **执行序不是 catalog 序**（catalog 按字典序：CancelRoute 在 DisbandUnit 前）：{@code DisbandUnit} 会移除 {@code
 * u-1}， 之后的命令就查无此人 ⇒ 本用例按**语义合法序**跑（先建第二个单位，改名/编制/改编/定位/路线/取消都在 {@code u-1} 上，最后解散它）。
 *
 * <p>夹具与 {@code McpServerTest}/{@code ShellEndToEndTest} 同法：独立 store 种创世 {@code (main,1)} + 含
 * map/unit/social 三切片的创世 checkpoint（state 时间戳 {@code of(7)}）；端口全 0。
 */
class McpCoverageTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U3 = new UnitId("u-3");
  private static final UnitId U4 = new UnitId("u-4");
  private static final UnitId U5 = new UnitId("u-5");
  private static final int CHECKPOINT_INTERVAL = 100;

  private static final String TEST_INITIATOR = "agent:t11-coverage";

  /**
   * catalog 预期的 93 个已注册命令类型（与 {@code Shell} 注册的 handler 同源，T9 后 18 → 30，C 阶段 30 → 37，D 阶段 37 →
   * 40，T3 起 40 → 41，T10 起 41 → 42，M11 起 42 → 43，T11C 起 43 → 44，会话重置起 44 → 45，令状态翻转起 45 → 46， social
   * 起 46 → 49，economy/actor 全族补齐后 50 → 60，辖区阶段 5–8 起 60 → 65，阶段 9–12 起 65 → 71，P1b1/P1b2/P3/R3a 与
   * D1/D3a/D4/D5 起 71 → 85，S3a 的家户/人口 8 条起 85 → 93）。
   */
  private static final List<String> EXPECTED_COMMAND_TYPES =
      List.of(
          "actor.AdjustAccounts",
          "actor.ClearRegion",
          "actor.RemitGovTreasury",
          "actor.Seed",
          "army.AppendCombatStage",
          "army.RecordCombat",
          "army.ResolveCombatStage",
          "economy.AddDemand",
          "economy.CancelDemand",
          "economy.ClearRegion",
          "economy.GmAdjust",
          "economy.MigrateHousehold",
          "economy.RegisterCandidate",
          "economy.Seed",
          "economy.SetMarketPrice",
          "economy.SwitchMode",
          "economy.TransferAssetShare",
          "economy.UnitBorrow",
          "economy.UnitRepay",
          "map.CreateRegion",
          "map.DeleteRegion",
          "map.RandomizeRegion",
          "map.RegisterPathwayGroup",
          "map.RenameRegion",
          "map.SetEdge",
          "map.SetTerrain",
          "map.UpdateRegion",
          "sd.AddCombatStage",
          "sd.CancelEffect",
          "sd.CommitCombatOutcome",
          "sd.CreateArmy",
          "sd.CreateCombat",
          "sd.CreateDecisionMaker",
          "sd.CreateNation",
          "sd.DeleteDecisionMaker",
          "sd.IssueDirective",
          "sd.PutInfo",
          "sd.RecordCasualties",
          "sd.RecordDiplomaticEvent",
          "sd.RegisterEffect",
          "sd.ResetDecisionMakerConversation",
          "sd.RunDecision",
          "sd.SetArmyMasterGov",
          "sd.SetDecisionMakerAccess",
          "sd.SetDecisionMakerProvider",
          "sd.SetDiplomaticRelation",
          "sd.SetDirectiveStatus",
          "sd.SetStageOutcomeTable",
          "sd.StartDecision",
          "sd.SubmitVerdict",
          "social.AddHouseholdMembers",
          "social.AdjustHouseholdPopulation",
          "social.ClearRegion",
          "social.CreateCity",
          "social.CreateHousehold",
          "social.RemoveHouseholdMembers",
          "social.SeedGroups",
          "social.SetHouseholdLocation",
          "social.SetHouseholdVitalRates",
          "social.SetPopulation",
          "social.TransferHouseholdMembers",
          "social.UpdateCity",
          "unit.AdjustComposition",
          "unit.ApplyCasualties",
          "unit.AttachUnit",
          "unit.CancelRoute",
          "unit.CreateCommandChain",
          "unit.CreateUnit",
          "unit.DetachUnit",
          "unit.DisbandUnit",
          "unit.DismissStaff",
          "unit.MergeFormation",
          "unit.PlaceAt",
          "unit.PlanRoute",
          "unit.PlanSparseRoute",
          "unit.RecruitStaff",
          "unit.RenameUnit",
          "unit.ReparentSubtree",
          "unit.ReparentUnit",
          "unit.SetArmyFormation",
          "unit.SetComposition",
          "unit.SetFormationOffset",
          "unit.SetGovFormation",
          "unit.SetGovPolicy",
          "unit.SetGovSuperior",
          "unit.SetJurisdiction",
          "unit.SetRejoinTarget",
          "unit.SetStateDescription",
          "unit.SetStatus",
          "unit.SetTaxRate",
          "unit.SetUnitHouseholds",
          "unit.SplitFormation",
          "unit.UpdateCommandChain");

  /**
   * ★★ H1：{@code economy.Seed} 那条最小载荷在 (1,1) 落的那个家户 —— id 由 {@link HouseholdId#ofSeed} 拼 （家户 id
   * 的**唯一拼写点**；actor id 再由 {@link HouseholdActors#idOf(HouseholdId)} 拼，本文件不手写格式）。
   */
  private static final HouseholdId SEEDED_HOUSEHOLD =
      HouseholdIds.ofSeed(H11, ResidenceKind.RURAL, new SocialClassId("poor_peasant"));

  private static final String HOUSEHOLD_ID = HouseholdActors.idOf(SEEDED_HOUSEHOLD);

  /**
   * E6b + 辖区阶段 7 + 编制 v2 + P1b2：本夹具没有前置组织 / 债务合同 / class-first 放贷方，或命令已退役 / 会破坏引用完整性
   * ——这些类型形状合法的载荷也无法"提交成功"，单独断言其具名拒绝且不推 revision。
   *
   * <p>★ 其中 {@code actor.AdjustAccounts} **不在此列**：它可以用"纯正增量新建/追加一本账"合法提交（见 {@link
   * #MINIMAL_PAYLOADS}），故走正常覆盖路径。
   */
  private static final Set<String> PRECONDITION_REJECT_TYPES =
      Set.of(
          "economy.SwitchMode",
          "economy.GmAdjust",
          "economy.UnitBorrow",
          "economy.UnitRepay",
          // ★ R3a：actor.RemitGovTreasury 非 GmOnly，但本夹具没有两个带国库账的 GOV ⇒ 形状合法、前置缺失，
          //   走同一支具名拒（不推 revision）。
          "actor.RemitGovTreasury",
          // ★ 编制 v2（2026-09-24）：unit.SetFormationOffset 已退役（具名拒）⇒ 非 GmOnly 但本夹具无法"提交成功"，
          //   走同一支具名拒（不推 revision）。
          "unit.SetFormationOffset",
          // ★ P1b2：删除决策人前必须没有 Directive 引用；本夹具 dm-cov 已有 d-cov ⇒ 走同一支具名拒（不推 revision）。
          "sd.DeleteDecisionMaker");

  /** 每条 {@link #PRECONDITION_REJECT_TYPES} 的载荷：形状合法，但在本夹具必然具名拒（缺前置 / 已退役）且不推 revision。 */
  private static final Map<String, String> PRECONDITION_REJECT_PAYLOADS =
      Map.of(
          "economy.SwitchMode",
          "{\"organizationId\":\"org-missing\",\"toModeId\":\"mode-missing\","
              + "\"retainOriginalPerMille\":1000,\"effectiveDay\":7,\"reason\":\"coverage\"}",
          "economy.GmAdjust",
          "{\"adjustment\":\"forgiveDebt\","
              + "\"parameters\":{\"debtContractId\":\"missing-debt\"},\"reason\":\"coverage\"}",
          // class-first 未播种 ⇒ handler 在查放贷方之前先拒"只在 class-first 世界可用"。
          "economy.UnitBorrow",
          "{\"unitId\":\"u-2\",\"lenderId\":\"lender-missing\",\"unit\":\"money\",\"principal\":1,"
              + "\"interestRatePerMille\":0,\"nextDueTick\":400}",
          "economy.UnitRepay",
          "{\"unitId\":\"u-2\",\"lenderId\":\"lender-missing\",\"unit\":\"money\",\"amount\":1}",
          // actor.RemitGovTreasury：形状合法、源国库账在本夹具不存在 ⇒ 具名拒。
          "actor.RemitGovTreasury",
          "{\"fromUnitId\":\"u-missing\",\"fromQ\":1,\"fromR\":1,\"toUnitId\":\"u-missing-2\","
              + "\"toQ\":1,\"toR\":1,\"grain\":1}",
          // unit.SetFormationOffset：命令已退役，形状合法的载荷照样具名拒。
          "unit.SetFormationOffset",
          "{\"id\":\"u-2\",\"dq\":1,\"dr\":0}",
          // sd.DeleteDecisionMaker：dm-ghost 不存在 ⇒ 具名拒。
          "sd.DeleteDecisionMaker",
          "{\"decisionMakerId\":\"dm-ghost\"}");

  /** 真实播种归一化出的农地份额身份（{@code (farm@1_1, LAND, ESTATE:farm@1_1, OWNED, 0)}）。 */
  private static final AssetShareId SEEDED_LAND_SHARE =
      AssetShare.idOf(
          new IndustryId("farm@1_1"),
          AssetKind.LAND,
          RegimeOperators.defaultOperator(new RegimeId("feudal"), new IndustryId("farm@1_1")),
          RegimeOperators.defaultOperator(new RegimeId("feudal"), new IndustryId("farm@1_1")),
          AssetShare.RightKind.OWNED,
          0L);

  /** 每类的**最小合法载荷**（对夹具世界；顺序即语义合法序）。 */
  private static final Map<String, String> MINIMAL_PAYLOADS = new LinkedHashMap<>();

  static {
    MINIMAL_PAYLOADS.put(
        "unit.CreateUnit",
        "{\"id\":\"u-2\",\"name\":\"第二连\",\"position\":{\"q\":1,\"r\":2},"
            + "\"manpower\":[{\"type\":\"步兵\",\"amount\":50}],"
            + "\"equipment\":[{\"type\":\"步枪\",\"amount\":10}],\"speed\":2,\"mobilityPerMille\":500}");
    MINIMAL_PAYLOADS.put("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"一改\"}");
    MINIMAL_PAYLOADS.put(
        "unit.SetComposition",
        "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":120}],"
            + "\"equipment\":[{\"type\":\"步枪\",\"amount\":60}]}");
    // ★★ 编制 v2（2026-09-24）：**只有顶层能下路线** ⇒ 路线类命令必须排在 ReparentUnit **之前**
    //   （reparent 之后 u-1 就是 u-2 那一支的成员了，成员下路线会被域层正当拒绝）。
    //   起点也随之前移：此刻 u-1 还在创世格 (1,1)，PlaceAt 之后才到 (1,2)。
    MINIMAL_PAYLOADS.put(
        "unit.PlanRoute", "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2}]}");
    MINIMAL_PAYLOADS.put(
        "unit.PlanSparseRoute",
        "{\"id\":\"u-1\",\"waypoints\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":3}]}");
    MINIMAL_PAYLOADS.put("unit.CancelRoute", "{\"id\":\"u-1\"}");
    MINIMAL_PAYLOADS.put("unit.ReparentUnit", "{\"id\":\"u-1\",\"parent\":\"u-2\"}");
    MINIMAL_PAYLOADS.put("unit.PlaceAt", "{\"id\":\"u-1\",\"hex\":{\"q\":1,\"r\":2}}");
    // ── T9 新增的 12 条：顺序即语义合法序（须排在 DisbandUnit 之前，u-1 才还在）──
    MINIMAL_PAYLOADS.put("unit.SetStatus", "{\"id\":\"u-1\",\"status\":\"RESTING\"}");
    MINIMAL_PAYLOADS.put("unit.SetRejoinTarget", "{\"id\":\"u-1\",\"target\":\"u-2\"}");
    MINIMAL_PAYLOADS.put(
        "unit.ApplyCasualties",
        "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":-10}],"
            + "\"equipment\":[{\"type\":\"步枪\",\"amount\":-5}]}");
    MINIMAL_PAYLOADS.put(
        "unit.CreateCommandChain",
        "{\"chainId\":\"c-1\",\"name\":\"第一链\",\"commander\":\"u-2\",\"members\":[\"u-2\"]}");
    MINIMAL_PAYLOADS.put("unit.UpdateCommandChain", "{\"chainId\":\"c-1\",\"name\":\"第一链改\"}");
    MINIMAL_PAYLOADS.put("unit.AttachUnit", "{\"id\":\"u-3\",\"parent\":\"u-2\"}");
    MINIMAL_PAYLOADS.put("unit.ReparentSubtree", "{\"rootId\":\"u-4\",\"parent\":\"u-2\"}");
    MINIMAL_PAYLOADS.put("unit.DetachUnit", "{\"id\":\"u-4\"}");
    MINIMAL_PAYLOADS.put("unit.SplitFormation", "{\"rootId\":\"u-2\",\"subUnitIds\":[\"u-1\"]}");
    MINIMAL_PAYLOADS.put("unit.MergeFormation", "{\"childId\":\"u-5\",\"parentId\":\"u-2\"}");
    MINIMAL_PAYLOADS.put("unit.DisbandUnit", "{\"id\":\"u-1\"}");
    MINIMAL_PAYLOADS.put(
        "map.SetTerrain", "{\"hexes\":[{\"q\":1,\"r\":3}],\"terrain\":\"plains\"}");
    MINIMAL_PAYLOADS.put(
        "map.CreateRegion",
        "{\"regionId\":\"r-cov\",\"name\":\"覆盖区\",\"hexes\":[{\"q\":1,\"r\":1},{\"q\":1,\"r\":2}]}");
    MINIMAL_PAYLOADS.put(
        "map.UpdateRegion",
        "{\"regionId\":\"r-cov\",\"hexes\":[{\"q\":1,\"r\":2},{\"q\":1,\"r\":3}],"
            + "\"meta\":{\"color\":\"#abc\"}}");
    MINIMAL_PAYLOADS.put("map.DeleteRegion", "{\"regionId\":\"r-cov\"}");
    // ★★ P1b1（2026-10-01）：三个域的区域清空（GM-only 但可提交）。目标 r-nation 只含 (1,1)，此刻尚未播种
    //   economy/actor 数据；social 清掉后由后面的 social.SeedGroups 再种 ⇒ 不破坏后续 simos.advance 的前置。
    MINIMAL_PAYLOADS.put("social.ClearRegion", "{\"regionId\":\"r-nation\"}");
    MINIMAL_PAYLOADS.put("economy.ClearRegion", "{\"regionId\":\"r-nation\"}");
    MINIMAL_PAYLOADS.put("actor.ClearRegion", "{\"regionId\":\"r-nation\"}");
    MINIMAL_PAYLOADS.put(
        "map.SetEdge", "{\"kind\":\"river\",\"edges\":[\"1_1|1_2\"],\"mode\":\"merge\"}");
    MINIMAL_PAYLOADS.put(
        "map.RandomizeRegion",
        "{\"hexes\":[{\"q\":1,\"r\":1}],\"terrainA\":\"plains\",\"terrainB\":\"desert\",\"seed\":7}");
    MINIMAL_PAYLOADS.put(
        "sd.CreateNation",
        "{\"nationId\":\"n-cov\",\"name\":\"覆盖国\",\"homeRegionId\":\"r-nation\","
            + "\"adminBudgetPerTick\":1}");
    // ★ 阶段 12：sd.CreateArmy 去掉 nationId（发 nationId 会被具名拒）；masterGovUnitId 可省略 = 未认主子。
    MINIMAL_PAYLOADS.put(
        "sd.CreateArmy", "{\"armyId\":\"a-cov\",\"rootUnitId\":\"u-2\",\"name\":\"覆盖军\"}");
    MINIMAL_PAYLOADS.put(
        "sd.CreateDecisionMaker",
        "{\"id\":\"dm-cov\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n-cov\"},"
            + "\"allowedTools\":[\"sd.SubmitVerdict\"],\"cadence\":1}");
    // 「把会话换一段新的」：放在 CreateDecisionMaker **之后**（此刻 dm-cov 已存在），
    // 且**不挪动任何已有命令的位置**——本用例逐条断言 revision 号，位置敏感。
    MINIMAL_PAYLOADS.put("sd.ResetDecisionMakerConversation", "{\"decisionMakerId\":\"dm-cov\"}");
    MINIMAL_PAYLOADS.put("sd.PutInfo", "{\"address\":\"map:Map1\",\"key\":\"k\",\"value\":\"v\"}");
    MINIMAL_PAYLOADS.put(
        "sd.CreateCombat", "{\"combatId\":\"c-cov\",\"name\":\"覆盖交战\",\"participants\":[\"u-4\"]}");
    MINIMAL_PAYLOADS.put(
        "sd.AddCombatStage",
        "{\"combatId\":\"c-cov\",\"combatStateId\":\"cs-cov\",\"hex\":{\"q\":1,\"r\":1},"
            + "\"stage\":{\"stageId\":\"s-cov\",\"name\":\"阶段一\",\"participants\":[\"u-4\"],"
            + "\"entry\":[{\"@class\":\"at_or_after_tick\",\"tick\":0}],"
            + "\"exit\":[{\"@class\":\"at_or_after_tick\",\"tick\":9}],"
            + "\"outcomes\":{\"options\":[{\"id\":\"o-cov\",\"label\":\"胜\",\"weight\":1}]}}}");
    MINIMAL_PAYLOADS.put(
        "sd.SetStageOutcomeTable",
        "{\"combatId\":\"c-cov\",\"stageId\":\"s-cov\","
            + "\"outcomes\":{\"options\":[{\"id\":\"o-cov\",\"label\":\"胜\",\"weight\":2}]}}");
    MINIMAL_PAYLOADS.put(
        "sd.RecordCasualties",
        "{\"combatId\":\"c-cov\",\"stageId\":\"s-cov\","
            + "\"deltas\":[{\"unit\":\"u-4\",\"personnel\":-1,\"equipment\":{\"步枪\":-1},"
            + "\"lossClass\":\"PERMANENT\"}]}");
    MINIMAL_PAYLOADS.put(
        "sd.CommitCombatOutcome",
        "{\"combatId\":\"c-cov\",\"stageId\":\"s-cov\",\"selectedOutcomeId\":\"o-cov\"}");
    MINIMAL_PAYLOADS.put(
        "sd.RegisterEffect",
        "{\"effectId\":\"e-cov\",\"kind\":\"SCHEDULED\","
            + "\"trigger\":{\"@class\":\"at_or_after_tick\",\"tick\":9},"
            + "\"action\":{\"@class\":\"enqueue_unit_command\",\"type\":\"unit.ApplyCasualties\","
            + "\"payloadJson\":\"{\\\"id\\\":\\\"u-4\\\","
            + "\\\"manpower\\\":[{\\\"type\\\":\\\"步兵\\\",\\\"amount\\\":-1}],"
            + "\\\"equipment\\\":[{\\\"type\\\":\\\"步枪\\\",\\\"amount\\\":-1}]}\"}}");
    MINIMAL_PAYLOADS.put("sd.CancelEffect", "{\"effectId\":\"e-cov\"}");
    // ── D 阶段新增的 3 条（语义合法序：SetDecisionMakerAccess 与 IssueDirective 需 dm-cov，SubmitVerdict 只查地址形态）──
    MINIMAL_PAYLOADS.put(
        "sd.SetDecisionMakerAccess",
        "{\"decisionMakerId\":\"dm-cov\",\"accessLimit\":{\"map\":[\"Map1/region/r1\"]},"
            + "\"adjudicationDisclosure\":\"FULL\"}");
    MINIMAL_PAYLOADS.put(
        "sd.IssueDirective",
        "{\"directiveId\":\"d-cov\",\"decisionMakerId\":\"dm-cov\",\"tick\":0,"
            + "\"intentInfo\":\"推进\",\"commands\":[]}");
    MINIMAL_PAYLOADS.put(
        "sd.SubmitVerdict",
        "{\"verdictId\":\"v-cov\",\"breakpoint\":\"D1\",\"subject\":\"sd:combat.c-cov\","
            + "\"payload\":\"{\\\"stageId\\\":\\\"s-cov\\\",\\\"selectedOutcomeId\\\":\\\"o-cov\\\","
            + "\\\"casualtyDeltas\\\":[],\\\"rationaleText\\\":\\\"推进\\\"}\","
            + "\"meta\":{\"model\":\"m\",\"promptVersion\":\"p\",\"inputBriefDigest\":\"d\"}}");
    // T3：注册自定义连通性组（词表 = 默认 + 可自定义）。放最后 ⇒ 不移动前面各命令的 revision 号。
    MINIMAL_PAYLOADS.put(
        "map.RegisterPathwayGroup",
        "{\"id\":\"canal\",\"name\":\"运河\",\"color\":\"#3A7BD5\",\"description\":\"人工水道\"}");
    // T10：「开始决策」——同样放最后（dm-cov 已在 sd.CreateDecisionMaker 建好）⇒ 不移动前面各命令的 revision 号。
    MINIMAL_PAYLOADS.put("sd.StartDecision", "{\"decisionMakerId\":\"dm-cov\",\"note\":\"覆盖\"}");
    // M11：决策人绑定 provider（同样放最后；dm-cov 已在 sd.CreateDecisionMaker 建好）⇒ 不移动前面的 revision 号。
    MINIMAL_PAYLOADS.put(
        "sd.SetDecisionMakerProvider", "{\"decisionMakerId\":\"dm-cov\",\"providerId\":\"p-cov\"}");
    // T11C：触发一轮决策人 agent 的那条**命令**（窄工具走的是另一条路：命令落盘之后才真跑 LLM）。
    //   同样放最后（dm-cov 早已建好）⇒ 不移动前面各命令的 revision 号。
    MINIMAL_PAYLOADS.put("sd.RunDecision", "{\"decisionMakerId\":\"dm-cov\"}");
    // 第 3 波最后一块：令的状态翻转（sd.SetDirectiveStatus）。放最后，且必须排在 sd.IssueDirective 之后
    //   （d-cov 此刻已存在、状态 ISSUED）⇒ 不移动前面各命令的 revision 号。它是裁决内部编排用的命令类型。
    MINIMAL_PAYLOADS.put(
        "sd.SetDirectiveStatus", "{\"directiveId\":\"d-cov\",\"status\":\"EXECUTED\"}");
    // social（3 条）：逐格农村人口 + 城市节点。放最后 ⇒ 不移动前面各命令的 revision 号；
    //   三条都必须产生**非空**变更集（SetPopulation 设一格人口、CreateCity 建城、UpdateCity 改它）。
    MINIMAL_PAYLOADS.put(
        "social.SetPopulation", "{\"entries\":[{\"q\":1,\"r\":1,\"population\":1000}]}");
    // ★ R1（T5）：CreateCity 不再接受 population（城的城镇人口是派生量）⇒ 最小载荷里没有它。
    MINIMAL_PAYLOADS.put(
        "social.CreateCity", "{\"id\":\"city-cov\",\"name\":\"覆盖城\",\"at\":{\"q\":1,\"r\":1}}");
    MINIMAL_PAYLOADS.put(
        "social.UpdateCity", "{\"id\":\"city-cov\",\"name\":\"覆盖城改\",\"props\":{\"tier\":1}}");
    // ★ R1（T3）：人口批次。必须排在 social.SetPopulation 之后（批次只能落在**已有农村序列**的格上，
    //   设计稿 §十.7 的跨组件校验）；一格两条（两个性别）⇒ 变更集非空。
    MINIMAL_PAYLOADS.put(
        "social.SeedGroups",
        "{\"entries\":[{\"id\":\"rural:1_1:MALE\",\"q\":1,\"r\":1,\"sex\":\"MALE\",\"count\":600,"
            + "\"ageDays\":13505},{\"id\":\"rural:1_1:FEMALE\",\"q\":1,\"r\":1,"
            + "\"sex\":\"FEMALE\",\"count\":400,\"ageDays\":13505}]}");
    // ★ R2a（2026-09-25）：经济播种。放最后 ⇒ 不移动前面各命令的 revision 号；
    //   一格一产业一行（必须产生**非空**变更集）。
    //   ★ R3（V7）：配方多了两个分量（`capacityPerUnit` = "单位规模"的锚、`laborPerUnit` = 劳动那一路），
    //     且投入表的值侧带商品维度（`{"LAND":{"grain":8000}}`）—— 缺 `capacityPerUnit` 会被构造期守卫拒 ⇒ 命令 Rejected。
    //   ★★ H0（2026-09-27，K2/K3）：`classes` 从**产业节点内**搬到**格 entry 级**，行里带 `residence`
    //     （`rural`/`urban`，居住类型是家户身份的一维）、**删掉** `meansOfProduction`（那一项已搬到 `Industry.capacity`）；
    //     产业的 `capacity` = **本格该产业的产能总量**（LAND 千分亩，键必须是 `capacityPerUnit` 键的子集）。
    //     ★ 本载荷保持"必产非空变更集"的用意：一行仍落在 (1,1) 格的农村贫农上。
    MINIMAL_PAYLOADS.put(
        "economy.Seed",
        "{\"mapId\":\"Map1\",\"rulesVersion\":\"aggregate-v1\",\"entries\":[{\"q\":1,\"r\":1,"
            + "\"industries\":[{\"id\":\"farm@1_1\",\"name\":\"农业\",\"regime\":\"feudal\","
            + "\"cycleDays\":120,\"capacity\":{\"LAND\":1000000},"
            + "\"capacityPerUnit\":{\"LAND\":1000},\"laborPerUnit\":143,"
            + "\"outputPerUnit\":{\"grain\":7,\"fiber\":2},"
            + "\"cycleInputPerUnit\":{\"LAND\":{\"grain\":8000}},"
            + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":700,"
            + "\"laborWeightPerMille\":300},"
            + "\"slots\":[{\"id\":\"poor_peasant\",\"name\":\"贫农\","
            + "\"laborParticipationPerMille\":950}]}],"
            + "\"classes\":[{\"residence\":\"rural\",\"slot\":\"poor_peasant\",\"population\":1000,"
            + "\"laborMilli\":580000,\"participationPerMille\":950}]}]}");
    // ★ S1 阶段 2（2026-09-26）：actor 播种。放最后 ⇒ 不移动前面各命令的 revision 号；
    //   一格一主体 + 一本库存（必须产生**非空**变更集）。
    //   ★★ H0.5（2026-09-27，裁定 S3）：产权行 `holdings[]` 随 `AssetHolding` **整块退役**
    //     （载荷里多出来的那一键现在既不解析也不报错 —— 留着它就是"形状上说着一件模型里没有的事"）⇒ 本载荷删掉它；
    //     本切片里唯一的那本账是 `goods`（= `GoodsAccount`，键 = (owner, location)）。
    //   ★ 判据来自 ActorPayloads：goods 的 location 必须**等于所在 entry 的 (q,r)**（否则拒），
    //     owner 必须是载荷里声明的 actors ∪ 现有状态里已有的主体（悬空 owner 拒）——故这里 owner 就是
    //     同一条载荷里声明的 estate:1_1。
    //   ★★ H1（2026-09-27）：本载荷**必须**连家户 actor 一起播 —— 上面那条 economy.Seed 在 (1,1) 落了一行
    //     `rural|poor_peasant`，而 H1 起"日结算要读家户账"⇒ 少了它，随后的 {@code simos.advance} 会当场抛
    //     （"家户 actor / 账本缺失"，不静默当库存 0）。家户 id 由 {@link HouseholdActors} 拼（**唯一拼写点**，
    //     本载荷不手写那个格式）。
    MINIMAL_PAYLOADS.put(
        "actor.Seed",
        "{\"mapId\":\"Map1\",\"rulesVersion\":\"actor-v1\",\"entries\":[{\"q\":1,\"r\":1,"
            + "\"actors\":[{\"kind\":\"ESTATE\",\"id\":\"farm@1_1\",\"label\":\"农业庄园\"},"
            + "{\"kind\":\"HOUSEHOLD\",\"id\":\""
            + HOUSEHOLD_ID
            + "\",\"label\":\"农村贫农家户\"}],"
            + "\"goods\":[{\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@1_1\"},"
            + "\"location\":{\"q\":1,\"r\":1},\"balances\":{\"grain\":2241000}},"
            + "{\"owner\":{\"kind\":\"HOUSEHOLD\",\"id\":\""
            + HOUSEHOLD_ID
            + "\"},"
            + "\"location\":{\"q\":1,\"r\":1},\"balances\":{\"grain\":2241000}}]}]}");

    // ── E1–E6 的 economy 窄命令：追加在最后（不移动既有 revision 号）；每条都备最小合法载荷。──
    MINIMAL_PAYLOADS.put(
        "economy.SetMarketPrice", "{\"q\":1,\"r\":2,\"commodity\":\"grain\",\"price\":2}");
    MINIMAL_PAYLOADS.put(
        "economy.AddDemand",
        "{\"id\":\"demand-coverage\",\"scope\":\"HEX\",\"hex\":{\"q\":1,\"r\":2},"
            + "\"commodity\":\"grain\",\"kind\":\"RECURRING\",\"unit\":\"TOTAL\","
            + "\"quantityPerCycle\":1}");
    MINIMAL_PAYLOADS.put("economy.CancelDemand", "{\"demand\":\"demand-coverage\"}");
    MINIMAL_PAYLOADS.put(
        "economy.RegisterCandidate",
        "{\"id\":\"candidate-coverage\",\"version\":1,\"output\":\"grain\","
            + "\"outputPerUnit\":{\"grain\":1},\"inputPerUnit\":{},"
            + "\"requiredAssets\":{\"LAND\":1},\"laborPerUnit\":1,\"buildDays\":0,"
            + "\"cycleDays\":120,\"regime\":\"feudal\",\"laborSource\":\"SELF\","
            + "\"acceptedRightKinds\":[\"OWNED\"],\"name\":\"覆盖候选\"}");
    MINIMAL_PAYLOADS.put(
        "economy.MigrateHousehold",
        "{\"household\":\"" + SEEDED_HOUSEHOLD.value() + "\",\"toHex\":\"1_2\"}");
    MINIMAL_PAYLOADS.put(
        "economy.TransferAssetShare",
        "{\"share\":\""
            + SEEDED_LAND_SHARE.value()
            + "\",\"quantity\":1,\"toOwner\":{\"kind\":\"HOUSEHOLD\",\"id\":\"house-7\"},"
            + "\"toOperator\":{\"kind\":\"HOUSEHOLD\",\"id\":\"house-7\"}}");

    // ── 辖区阶段 5–8 的 3 条可提交命令：一律追加在最后 ⇒ 不移动上面任何命令的 revision 号。──
    //   顺序敏感：unit.SetTaxRate 要求该单位已把目标区域纳入管辖 ⇒ SetJurisdiction 必须先跑。
    //   目标单位取 u-2（被 DisbandUnit 解散的是 u-1；u-2 一直存活到覆盖结束）；区域取夹具里已有的 r-nation。
    MINIMAL_PAYLOADS.put("unit.SetJurisdiction", "{\"unitId\":\"u-2\",\"regions\":[\"r-nation\"]}");
    MINIMAL_PAYLOADS.put(
        "unit.SetTaxRate", "{\"unitId\":\"u-2\",\"regionId\":\"r-nation\",\"ratePerMille\":100}");
    // actor.AdjustAccounts：纯正增量打在 actor.Seed 已落好的既有 ESTATE 账（farm@1_1 @ (1,1)）上，无前置；
    //   grain +1 ⇒ 变更集非空。它是 GmOnly，但 GM 的 simos.command.submit 照常可提交。
    MINIMAL_PAYLOADS.put(
        "actor.AdjustAccounts",
        "{\"entries\":[{\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@1_1\"},\"q\":1,\"r\":1,"
            + "\"goods\":{\"grain\":1}}]}");

    // ── 阶段 9–12（2026-10-01）的 6 条新 unit 命令：一律追加在最后 ⇒ 不移动上面任何命令的 revision 号。──
    //   顺序敏感：SetGovPolicy/SetGovSuperior/RecruitStaff/DismissStaff 都要求 u-2 已是 GOV ⇒ SetGovFormation
    //   必须排在它们之前；SetArmyFormation 也认 u-2 为主子（masterGov），故它同样排在 SetGovFormation 之后。
    MINIMAL_PAYLOADS.put("unit.SetGovFormation", "{\"unitId\":\"u-2\",\"level\":\"PROVINCE\"}");
    MINIMAL_PAYLOADS.put(
        "unit.SetArmyFormation",
        "{\"unitId\":\"u-3\",\"masterGov\":\"u-2\",\"role\":\"garrison\"}");
    MINIMAL_PAYLOADS.put("unit.SetGovPolicy", "{\"unitId\":\"u-2\",\"moneyPerStaffPerTick\":1}");
    MINIMAL_PAYLOADS.put("unit.SetGovSuperior", "{\"unitId\":\"u-2\"}");
    MINIMAL_PAYLOADS.put(
        "unit.RecruitStaff", "{\"unitId\":\"u-2\",\"role\":\"SCRIBE\",\"count\":1}");
    MINIMAL_PAYLOADS.put(
        "unit.DismissStaff", "{\"unitId\":\"u-2\",\"role\":\"SCRIBE\",\"count\":1}");

    // ── D1/D3a/D4/D5（2026-10-02）的 7 条新命令：追加在最后 ⇒ 不移动上面任何命令的 revision 号。──
    //   顺序敏感：AdjustComposition 打的是 u-2（DisbandUnit 解散的是 u-1）；外交事件需 2 个参与国
    //   （形状校验不查存在性）；army 三连按 Record → Append → Resolve 的语义序。
    MINIMAL_PAYLOADS.put(
        "unit.SetStateDescription", "{\"id\":\"u-2\",\"state\":\"覆盖状态\",\"address\":\"map:Map1\"}");
    MINIMAL_PAYLOADS.put(
        "unit.AdjustComposition",
        "{\"id\":\"u-2\",\"manpower\":[{\"type\":\"步兵\",\"amount\":1}],"
            + "\"equipment\":[{\"type\":\"步枪\",\"amount\":1}]}");
    MINIMAL_PAYLOADS.put(
        "sd.SetDiplomaticRelation",
        "{\"from\":\"n-cov\",\"to\":\"n-seed\",\"kind\":\"称臣纳贡\",\"text\":\"覆盖关系\"}");
    MINIMAL_PAYLOADS.put(
        "sd.RecordDiplomaticEvent", "{\"participants\":[\"n-cov\",\"n-seed\"],\"text\":\"覆盖谈判\"}");
    MINIMAL_PAYLOADS.put(
        "army.RecordCombat",
        "{\"id\":\"c-army\",\"kind\":\"野战\",\"hex\":{\"q\":1,\"r\":1},"
            + "\"participants\":[\"u-2\"],\"text\":\"覆盖交战\","
            + "\"initialStage\":{\"id\":\"s1\",\"name\":\"初始\",\"text\":\"覆盖阶段\","
            + "\"outcomes\":[{\"id\":\"o1\",\"label\":\"胜\",\"weight\":1}]}}");
    MINIMAL_PAYLOADS.put(
        "army.AppendCombatStage",
        "{\"combatId\":\"c-army\",\"stage\":{\"id\":\"s2\",\"name\":\"第二阶段\","
            + "\"text\":\"覆盖阶段二\"}}");
    MINIMAL_PAYLOADS.put("army.ResolveCombatStage", "{\"combatId\":\"c-army\",\"stageId\":\"s1\"}");
    // ★ R4 / 阶段 12：改名 / 改派主子两条（追加在最后 ⇒ 不动任何已有 revision 号）。
    //   sd.SetArmyMasterGov 需要 a-cov 与 GOV u-2（前面已就位）；sd.DeleteDecisionMaker 因引用完整性走
    //   上面的具名拒分支（不推 revision）。
    MINIMAL_PAYLOADS.put("map.RenameRegion", "{\"regionId\":\"r-nation\",\"name\":\"覆盖改名\"}");
    MINIMAL_PAYLOADS.put(
        "sd.SetArmyMasterGov", "{\"armyId\":\"a-cov\",\"masterGovUnitId\":\"u-2\"}");

    // ── S3a（2026-10-09）的 8 条家户/人口命令：追加在最后 ⇒ 不移动上面任何命令的 revision 号。──
    //   语义序：先建家户 hh-cov（HEX）→ 加成员 → 设率 → 调人口 → 减员 → 转移给 social.SeedGroups
    //   自动建的 hh:hex:1_1 → 家户改挂 UNIT(u-2) → unit 侧列表加入它（两端一致）。
    //   每条都必须产生**非空**变更集（命令提交不允许空 revision）。
    MINIMAL_PAYLOADS.put(
        "social.CreateHousehold",
        "{\"householdId\":\"hh-cov\",\"location\":{\"type\":\"HEX\",\"hex\":{\"q\":1,\"r\":3}},"
            + "\"profile\":{\"name\":\"覆盖户\"},\"reason\":\"coverage\"}");
    MINIMAL_PAYLOADS.put(
        "social.AddHouseholdMembers",
        "{\"householdId\":\"hh-cov\",\"lotId\":\"lot-cov-1\",\"sex\":\"MALE\",\"count\":10,"
            + "\"ageAtAnchorDays\":0,\"reason\":\"coverage\"}");
    MINIMAL_PAYLOADS.put(
        "social.SetHouseholdVitalRates",
        "{\"householdId\":\"hh-cov\",\"rates\":[{\"bracketId\":\"0-14\",\"sex\":\"MALE\","
            + "\"birthRatePerMillePerTick\":5,\"deathRatePerMillePerTick\":3}],"
            + "\"reason\":\"coverage\"}");
    MINIMAL_PAYLOADS.put(
        "social.AdjustHouseholdPopulation",
        "{\"householdId\":\"hh-cov\",\"sex\":\"MALE\",\"ageBracketId\":\"0-14\",\"delta\":3,"
            + "\"reason\":\"coverage\"}");
    MINIMAL_PAYLOADS.put(
        "social.RemoveHouseholdMembers",
        "{\"householdId\":\"hh-cov\",\"lotId\":\"lot-cov-1\",\"count\":4,\"reason\":\"coverage\"}");
    MINIMAL_PAYLOADS.put(
        "social.TransferHouseholdMembers",
        "{\"from\":\"hh-cov\",\"to\":\"hh:hex:1_1\",\"lotId\":\"lot-cov-1\",\"count\":2,"
            + "\"reason\":\"coverage\"}");
    MINIMAL_PAYLOADS.put(
        "social.SetHouseholdLocation",
        "{\"householdId\":\"hh-cov\",\"location\":{\"type\":\"UNIT\",\"unitId\":\"u-2\"},"
            + "\"reason\":\"coverage\"}");
    MINIMAL_PAYLOADS.put(
        "unit.SetUnitHouseholds",
        "{\"unitId\":\"u-2\",\"households\":[\"hh-cov\"],\"reason\":\"coverage\"}");
  }

  private static final Duration WAIT = Duration.ofSeconds(10);

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Shell shell;
  private McpSyncClient client;

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
    client = newClient();
  }

  @AfterEach
  void stopShell() {
    if (client != null) {
      try {
        client.closeGracefully();
      } catch (Exception ignored) {
        // 关停清理失败不影响用例判定（server 侧仍会被 shell.close() 收掉）
      }
    }
    if (shell != null) {
      shell.close();
    }
  }

  @Test
  void everyCatalogTypeIsReachableThroughMcpAndTakesEffect() throws Exception {
    client.initialize();

    // 1. catalog 经 MCP 读回，与注册面一致（R5 的载体）。
    List<String> catalogTypes = catalogTypes();
    assertThat(catalogTypes)
        .as(
            "catalog 列出的 type 与 Shell 注册的 93 个 handler 同源（R4/E6 后含 economy/actor 全族 + P1b1/P1b2/P3/R3a + 辖区阶段 5–12 + D1/D3a/D4/D5 + S3a 家户）")
        .containsExactlyInAnyOrderElementsOf(EXPECTED_COMMAND_TYPES);
    List<String> committableCatalogTypes = new ArrayList<>(catalogTypes);
    committableCatalogTypes.removeAll(PRECONDITION_REJECT_TYPES);
    assertThat(MINIMAL_PAYLOADS.keySet())
        .as("除 7 条需要前置状态 / 已退役的命令外，每个 catalog type 都备了载荷（%s）", PRECONDITION_REJECT_TYPES)
        .containsExactlyInAnyOrderElementsOf(committableCatalogTypes);

    // 2. 逐类经 MCP 提交（每条都过审批 APPROVE_ONCE），断言全部 commit 且 head 逐条前进。
    List<String> coverage = new ArrayList<>();
    long expectedRevision = 1L;
    for (Map.Entry<String, String> entry : MINIMAL_PAYLOADS.entrySet()) {
      McpSchema.CallToolResult result =
          submitViaMcp(entry.getKey(), entry.getValue(), expectedRevision);
      assertThat(result.isError())
          .as("type=%s 必须经 MCP 可提交并生效: %s", entry.getKey(), wireText(result))
          .isFalse();
      JsonNode body = JSON.readTree(wireText(result));
      assertThat(body.get("result").asText()).as("type=%s", entry.getKey()).isEqualTo("committed");
      expectedRevision++;
      assertThat(body.get("ref").get("revision").asLong())
          .as("type=%s 的提交落在 (main,%d)", entry.getKey(), expectedRevision)
          .isEqualTo(expectedRevision);
      coverage.add(
          "[T11-COVERAGE] type="
              + entry.getKey()
              + " result=committed revision="
              + expectedRevision);
    }
    for (String line : coverage) {
      System.out.println(line);
    }
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .as("每条可提交命令各推一格；需前置状态 / 已退役的 7 条留在下一段验证具名拒绝")
        .isEqualTo(1L + MINIMAL_PAYLOADS.size());

    // 2a. MigrateHousehold 在上面的覆盖里把家户迁到了 (1,2)，但 actor 账仍在 (1,1)
    //     ⇒ 后续 simos.advance 会 fail-closed（家户账 location 对不上）。这里再迁回 (1,1)：
    //     仍走真 MCP + 真命令，作为覆盖序列之后的**恢复步**（不是额外类型覆盖）。
    long headBeforeRestore = shell.coreSimos().head(main()).orElseThrow().value();
    McpSchema.CallToolResult restore =
        submitViaMcp(
            "economy.MigrateHousehold",
            "{\"household\":\"" + SEEDED_HOUSEHOLD.value() + "\",\"toHex\":\"1_1\"}",
            headBeforeRestore);
    assertThat(restore.isError()).as(wireText(restore)).isFalse();
    assertThat(JSON.readTree(wireText(restore)).get("result").asText()).isEqualTo("committed");

    // 2b. 需前置状态 / 已退役的 7 条（economy.SwitchMode / economy.GmAdjust / economy.UnitBorrow /
    //     economy.UnitRepay 缺组织/债务合同/class-first 放贷方；actor.RemitGovTreasury 缺 GOV 国库账；
    //     unit.SetFormationOffset 已退役；sd.DeleteDecisionMaker 被 Directive 引用）⇒ 必须经 MCP 可提交但被
    //     **具名拒绝**，且不推 revision。
    for (String rejectType : PRECONDITION_REJECT_TYPES) {
      long headBeforeGmOnly = shell.coreSimos().head(main()).orElseThrow().value();
      String rejectPayload = PRECONDITION_REJECT_PAYLOADS.get(rejectType);
      assertThat(rejectPayload).as("%s 必须备一条形状合法的载荷（缺项会让下面的提交退化成 NPE）", rejectType).isNotNull();
      McpSchema.CallToolResult rejected = submitViaMcp(rejectType, rejectPayload, headBeforeGmOnly);
      assertThat(rejected.isError())
          .as("%s 在缺前置状态时必须是具名拒绝: %s", rejectType, wireText(rejected))
          .isTrue();
      String wire = wireText(rejected);
      assertThat(wire).startsWith("[mosire:code=REJECTED]");
      JsonNode body = JSON.readTree(wire.substring("[mosire:code=REJECTED]".length()));
      assertThat(body.get("result").asText()).isEqualTo("rejected");
      assertThat(body.get("reason").asText()).as("%s 的拒绝原因", rejectType).isNotBlank();
      assertThat(shell.coreSimos().head(main()).orElseThrow().value())
          .as("%s 被拒不得推 revision", rejectType)
          .isEqualTo(headBeforeGmOnly);
      System.out.println("[T11-COVERAGE] type=" + rejectType + " result=rejected");
    }

    // 3. 世界真的变了（不是"没报错"）：u-1 被解散；CreateUnit 建的 u-2 与三条编制命令的
    //    u-3/u-4/u-5 都还在（T9 新增：编制命令各挂在不同单位上，避免同一时刻对同一条段序列重复落段）。
    //    ★ "DisbandUnit 的落点 revision"从 MINIMAL_PAYLOADS 的插入序**现算**（不再写死 21）：名单的增删
    //      只会改变它自己的编号，不会让这条断言变成"对着旧编号的假红/假绿"。
    long afterUnitCommandsRevision = 1L; // 创世 = (main,1)；每条命令 +1 格
    for (String type : MINIMAL_PAYLOADS.keySet()) {
      afterUnitCommandsRevision++;
      if (type.equals("unit.DisbandUnit")) {
        break;
      }
    }
    SimulationState afterUnitCommands =
        shell.coreSimos().replay(ref("main", afterUnitCommandsRevision));
    UnitState units = unitSlice(afterUnitCommands);
    assertThat(units.units().keySet())
        .as("u-1 已被 DisbandUnit 解散，只剩 u-2/u-3/u-4/u-5")
        .containsExactlyInAnyOrder(
            new UnitId("u-2"), new UnitId("u-3"), new UnitId("u-4"), new UnitId("u-5"));
    assertThat(units.units().get(new UnitId("u-2")).name()).isEqualTo("第二连");
    assertThat(units.units().get(new UnitId("u-2")).manpower())
        .containsExactly(new CompositionEntry("步兵", 50));

    // 4. simos.advance 经 MCP 可达且有效。
    // ★ 期望值从 **head 现取**（不写字面量）：上面的命令条数一变，写死的 revision 就会整条链错位，而症状是
    //   "advance 冲突"——看起来像 advance 坏了，其实是这里过期了（本任务实测踩过：加一条命令后这里红）。
    // ★ 日制裁定：to 必须 = from + 1（一次推进恰好一天）；世界时间戳创世即为 7 ⇒ 7 → 8。
    long headBeforeAdvance = shell.coreSimos().head(main()).orElseThrow().value();
    McpSchema.CallToolResult advance = advanceViaMcp(headBeforeAdvance, 7L, 8L);
    assertThat(advance.isError()).as(wireText(advance)).isFalse();
    JsonNode advanceBody = JSON.readTree(wireText(advance));
    assertThat(advanceBody.get("result").asText()).isEqualTo("committed");
    assertThat(advanceBody.get("ref").get("revision").asLong()).isEqualTo(headBeforeAdvance + 1);
    System.out.println(
        "[T11-COVERAGE] tool=simos.advance result=committed revision=" + (headBeforeAdvance + 1));
    assertThat(shell.coreSimos().head(main()).orElseThrow().value())
        .isEqualTo(headBeforeAdvance + 1);

    // 5. simos.fork 经 MCP 可达且有效（新分支 head = 1）。
    McpSchema.CallToolResult fork = forkViaMcp("main", headBeforeAdvance + 1, "mcp-branch");
    assertThat(fork.isError()).as(wireText(fork)).isFalse();
    JsonNode forkBody = JSON.readTree(wireText(fork));
    assertThat(forkBody.get("result").asText()).isEqualTo("committed");
    assertThat(forkBody.get("ref").get("branch").asText()).isEqualTo("mcp-branch");
    assertThat(forkBody.get("ref").get("revision").asLong()).isEqualTo(1L);
    System.out.println(
        "[T11-COVERAGE] tool=simos.fork result=committed branch=mcp-branch revision=1");
    assertThat(shell.coreSimos().branches().stream().map(BranchId::value).toList())
        .as("fork 真的建出了新分支")
        .contains("main", "mcp-branch");
    assertThat(shell.coreSimos().head(new BranchId("mcp-branch")).orElseThrow().value())
        .isEqualTo(1L);

    // 6. 反向：坏载荷 ⇒ REJECTED 且不留 revision（按行数计）。
    long revisionsBefore = revisionRowCount();
    long headBefore = shell.coreSimos().head(main()).orElseThrow().value();
    McpSchema.CallToolResult bad = submitViaMcp("unit.RenameUnit", "{\"id\":\"u-2\"}", headBefore);
    assertThat(bad.isError()).as("缺 name 的载荷必须被拒（不得静默提交）: %s", wireText(bad)).isTrue();
    assertThat(wireText(bad)).startsWith("[mosire:code=REJECTED]");
    JsonNode badBody = JSON.readTree(wireText(bad).substring("[mosire:code=REJECTED]".length()));
    assertThat(badBody.get("result").asText()).isEqualTo("rejected");
    assertThat(badBody.get("reason").asText()).isNotBlank();
    System.out.println("[T11-COVERAGE] reverse=bad-payload result=rejected");
    assertThat(revisionRowCount()).as("被拒的命令不得多留一行 revision").isEqualTo(revisionsBefore);
    assertThat(shell.coreSimos().head(main()).orElseThrow().value()).isEqualTo(headBefore);
  }

  // ────────────────────────────── MCP 助手 ──────────────────────────────

  private List<String> catalogTypes() throws Exception {
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest("simos.command.catalog", Map.of()));
    assertThat(result.isError()).as(wireText(result)).isFalse();
    JsonNode types = JSON.readTree(wireText(result)).get("types");
    List<String> out = new ArrayList<>();
    types.forEach(node -> out.add(node.asText()));
    return out;
  }

  private McpSchema.CallToolResult submitViaMcp(
      String type, String payloadJson, long expectedRevision) throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("type", type);
    args.put("payloadJson", payloadJson);
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return callViaMcp(CommandSubmitTool.NAME, args);
  }

  private McpSchema.CallToolResult advanceViaMcp(long expectedRevision, long from, long to)
      throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    args.put("from", from);
    args.put("to", to);
    return callViaMcp("simos.advance", args);
  }

  private McpSchema.CallToolResult forkViaMcp(String source, long expectedRevision, String target)
      throws Exception {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("source", source);
    args.put("expectedRevision", expectedRevision);
    args.put("newBranch", target);
    return callViaMcp("simos.fork", args);
  }

  /**
   * 经真 MCP 传输调用写工具（**GM 面 ⇒ 无脑过**，2026-09-24 用户裁定）：直接取结果，并**自证没登记待批**。
   *
   * <p>★ 原实现是"等它进审批 ⇒ 批一次 ⇒ 取结果"——那条口径已被用户裁定作废；现在这一层保护反过来： 一旦哪条 MCP 写又被挂进待批（链配错 / 有人把
   * GmAutoApproveGate 摘了），本方法当场红。
   */
  private McpSchema.CallToolResult callViaMcp(String toolName, Map<String, Object> args)
      throws Exception {
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest(toolName, args));
    assertThat(shell.pendingApprovals().pending())
        .as("%s：GM 面不得登记待批项（MCP/GM Agent 无脑过；要批的那条链是决策人链）", toolName)
        .isEmpty();
    return result;
  }

  /** 独立 store 读 {@code revisions} 行数（"拒绝不留 revision"的按行断言）。 */
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

  private McpSyncClient newClient() {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + shell.boundMcpPort())
            .endpoint(shell.config().mcpPath())
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    return McpClient.sync(transport).build();
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent text ? text.text() : content.toString();
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private static UnitState unitSlice(SimulationState state) {
    UnitSnapshot slice =
        (UnitSnapshot) state.module("unit").orElseThrow(() -> new AssertionError("状态里没有 unit 切片"));
    return slice.state();
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
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    UnitState units =
        new UnitState(
            new LinkedHashMap<>(
                Map.of(
                    U1, unit(),
                    // ★ 2026-09-24：u-3 与 u-2（H12）同格只是历史遗留（attach 曾要求同格，现已撤销、改为偏移式加入）；
                    //   保留同格不影响任何断言。u-3 无其它位置相关断言。
                    U3, genesisUnit(U3, "第三连", H12),
                    U4, genesisUnit(U4, "第四连", H11),
                    U5, genesisUnit(U5, "第五连", H12))));
    SocialData social =
        new SocialData(new LinkedHashMap<>(Map.of(H11, populationSeries())), Map.of(), Map.of());
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref("main", 1), T7),
            Map.of(
                "map", new MapSnapshot(ref("main", 1), T7, corridorMap()),
                "unit", new UnitSnapshot(ref("main", 1), T7, units),
                "social", new SocialSnapshot(ref("main", 1), T7, social),
                // ★ D5：sd 切片预置**第二个 Nation**（n-seed）——sd.SetDiplomaticRelation /
                // RecordDiplomaticEvent
                //   的 "对方国家" 必须真实存在，否则覆盖里只能撞具名拒、证不到命令真的落地。
                "sd",
                    new SdSnapshot(
                        ref("main", 1),
                        T7,
                        SdState.empty()
                            .withNations(
                                Map.of(
                                    new NationId("n-seed"),
                                    new Nation(
                                        new NationId("n-seed"),
                                        "种子国二号",
                                        new RegionId("r-nation"),
                                        0)))),
                // ★ R2a：经济切片在场（economy.Seed 要往它上面施加变更集）。
                "economy", new EconomySnapshot(ref("main", 1), T7, EconomyData.empty()),
                // ★ S1 阶段 2：actor 切片在场（actor.Seed 要往它上面施加变更集；同 economy 的先例）。
                "actor", new ActorSnapshot(ref("main", 1), T7, ActorData.empty()),
                // ★ D1/D4：army 切片在场（army.RecordCombat 等三条命令要往它上面施加变更集）。
                "army", new ArmySnapshot(ref("main", 1), T7, ArmyData.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(
            ref("main", 1),
            CheckpointEncoder.encode(
                genesis,
                List.of(
                    new MapCodec(),
                    new SocialCodec(),
                    new UnitCodec(),
                    new SdCodec(),
                    new EconomyCodec(),
                    new ActorCodec(),
                    new ArmyCodec())));
  }

  private static Unit unit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        List.of(new CompositionEntry("步兵", 100)),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty());
  }

  /** T9 新增的创世单位（锚在 {@code T0}）：给编制类命令提供**互不冲突的段序列**（同刻只能落一段）。 */
  private static Unit genesisUnit(UnitId id, String name, HexCoord position) {
    return new Unit(
        id,
        name,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(position))), List.of(), null),
        List.of(new CompositionEntry("步兵", 100)),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty());
  }

  private static PopulationSeries populationSeries() {
    return new PopulationSeries(
        new Segment<>(T0, 10000L),
        new SegmentedSeries<>(
            List.of(
                new Segment<>(SimosTimestamp.of(0), 0.02),
                new Segment<>(SimosTimestamp.of(20), 0.01),
                new Segment<>(SimosTimestamp.of(70), -0.03)),
            List.of(),
            null),
        List.of(new Event<>(SimosTimestamp.of(45), -800L, EventMode.ADD)));
  }

  private static GameMap corridorMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    RegionId nationRegion = new RegionId("r-nation");
    regions.put(
        nationRegion,
        Region.of(
            nationRegion, "种子国区域", Set.of(H11), new RegionMeta(null, "nation:seed", null, null)));
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        PathwayGroup.defaults(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private Path dbFile() {
    return tempDir.resolve(CoreSimos.DB_FILE_NAME);
  }

  private static BranchId main() {
    return new BranchId("main");
  }

  private static StateRef ref(String branch, long revision) {
    return new StateRef(new BranchId(branch), new RevisionId(revision));
  }
}
