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
import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.app.tools.write.CommandSubmitTool;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.codec.EconomyCodec;
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
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationSeries;
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
   * catalog 预期的 50 个已注册命令类型（与 {@code Shell} 注册的 handler 同源，T9 后 18 → 30，C 阶段 30 → 37，D 阶段 37 →
   * 40，T3 起 40 → 41，T10 起 41 → 42，M11 起 42 → 43，T11C 起 43 → 44，会话重置起 44 → 45，令状态翻转起 45 → 46， social
   * 起 46 → 49，economy 起 49 → 50）。
   */
  private static final List<String> EXPECTED_COMMAND_TYPES =
      List.of(
          "unit.RenameUnit",
          "unit.CreateUnit",
          "unit.ReparentUnit",
          "unit.SetStrength",
          "unit.PlaceAt",
          "unit.PlanRoute",
          "unit.CancelRoute",
          "unit.DisbandUnit",
          "unit.SetStatus",
          "unit.AttachUnit",
          "unit.DetachUnit",
          "unit.ReparentSubtree",
          "unit.SetFormationOffset",
          "unit.SplitFormation",
          "unit.MergeFormation",
          "unit.PlanSparseRoute",
          "unit.SetRejoinTarget",
          "unit.CreateCommandChain",
          "unit.UpdateCommandChain",
          "unit.ApplyCasualties",
          "map.SetTerrain",
          "map.CreateRegion",
          "map.UpdateRegion",
          "map.DeleteRegion",
          "map.SetEdge",
          "map.RegisterPathwayGroup",
          "map.RandomizeRegion",
          "sd.CreateNation",
          "sd.CreateArmy",
          "sd.CreateDecisionMaker",
          "sd.PutInfo",
          "sd.CreateCombat",
          "sd.AddCombatStage",
          "sd.SetStageOutcomeTable",
          "sd.CommitCombatOutcome",
          "sd.RecordCasualties",
          "sd.RegisterEffect",
          "sd.CancelEffect",
          "sd.IssueDirective",
          "sd.SubmitVerdict",
          "sd.SetDecisionMakerAccess",
          "sd.StartDecision",
          "sd.SetDecisionMakerProvider",
          "sd.ResetDecisionMakerConversation",
          "sd.RunDecision",
          "sd.SetDirectiveStatus",
          "social.SetPopulation",
          "social.CreateCity",
          "social.UpdateCity",
          // ★ R1（T3）：人口批次的创世入口。
          "social.SeedGroups",
          // ★ R2a：经济播种（一次种一格；放最后 ⇒ 不移动前面各命令的 revision 号）。
          "economy.Seed",
          // ★ S1 阶段 2：actor 播种（同 economy，放最后 ⇒ 不移动前面各命令的 revision 号）。
          "actor.Seed");

  /** 每类的**最小合法载荷**（对夹具世界；顺序即语义合法序）。 */
  private static final Map<String, String> MINIMAL_PAYLOADS = new LinkedHashMap<>();

  static {
    MINIMAL_PAYLOADS.put(
        "unit.CreateUnit",
        "{\"id\":\"u-2\",\"name\":\"第二连\",\"position\":{\"q\":1,\"r\":2},\"member\":50,"
            + "\"equipment\":{\"步枪\":10},\"speed\":2,\"mobilityPerMille\":500}");
    MINIMAL_PAYLOADS.put("unit.RenameUnit", "{\"id\":\"u-1\",\"name\":\"一改\"}");
    MINIMAL_PAYLOADS.put(
        "unit.SetStrength", "{\"id\":\"u-1\",\"member\":120,\"equipment\":{\"步枪\":60}}");
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
        "unit.ApplyCasualties", "{\"id\":\"u-1\",\"personnel\":-10,\"equipment\":{\"步枪\":-5}}");
    MINIMAL_PAYLOADS.put(
        "unit.CreateCommandChain",
        "{\"chainId\":\"c-1\",\"name\":\"第一链\",\"commander\":\"u-2\",\"members\":[\"u-2\"]}");
    MINIMAL_PAYLOADS.put("unit.UpdateCommandChain", "{\"chainId\":\"c-1\",\"name\":\"第一链改\"}");
    MINIMAL_PAYLOADS.put("unit.AttachUnit", "{\"id\":\"u-3\",\"parent\":\"u-2\"}");
    MINIMAL_PAYLOADS.put("unit.ReparentSubtree", "{\"rootId\":\"u-4\",\"parent\":\"u-2\"}");
    MINIMAL_PAYLOADS.put("unit.DetachUnit", "{\"id\":\"u-4\"}");
    MINIMAL_PAYLOADS.put("unit.SplitFormation", "{\"rootId\":\"u-2\",\"subUnitIds\":[\"u-1\"]}");
    MINIMAL_PAYLOADS.put("unit.MergeFormation", "{\"childId\":\"u-5\",\"parentId\":\"u-2\"}");
    MINIMAL_PAYLOADS.put("unit.SetFormationOffset", "{\"id\":\"u-3\",\"dq\":1,\"dr\":0}");
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
    MINIMAL_PAYLOADS.put(
        "map.SetEdge", "{\"kind\":\"river\",\"edges\":[\"1_1|1_2\"],\"mode\":\"merge\"}");
    MINIMAL_PAYLOADS.put(
        "map.RandomizeRegion",
        "{\"hexes\":[{\"q\":1,\"r\":1}],\"terrainA\":\"plains\",\"terrainB\":\"desert\",\"seed\":7}");
    MINIMAL_PAYLOADS.put(
        "sd.CreateNation",
        "{\"nationId\":\"n-cov\",\"name\":\"覆盖国\",\"homeRegionId\":\"r-nation\","
            + "\"adminBudgetPerTick\":1}");
    MINIMAL_PAYLOADS.put(
        "sd.CreateArmy",
        "{\"armyId\":\"a-cov\",\"nationId\":\"n-cov\",\"rootUnitId\":\"u-2\",\"name\":\"覆盖军\"}");
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
            + "\"payloadJson\":\"{\\\"id\\\":\\\"u-4\\\",\\\"personnel\\\":-1,"
            + "\\\"equipment\\\":{\\\"步枪\\\":-1}}\"}}");
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
            + "\"classes\":[{\"residence\":\"rural\",\"slot\":\"poor_peasant\",\"population\":100,"
            + "\"laborMilli\":58000,\"participationPerMille\":950}]}]}");
    // ★ S1 阶段 2（2026-09-26）：actor 播种。放最后 ⇒ 不移动前面各命令的 revision 号；
    //   一格一主体 + 一本库存（必须产生**非空**变更集）。
    //   ★★ H0.5（2026-09-27，裁定 S3）：产权行 `holdings[]` 随 `AssetHolding` **整块退役**
    //     （载荷里多出来的那一键现在既不解析也不报错 —— 留着它就是"形状上说着一件模型里没有的事"）⇒ 本载荷删掉它；
    //     本切片里唯一的那本账是 `goods`（= `GoodsAccount`，键 = (owner, location)）。
    //   ★ 判据来自 ActorPayloads：goods 的 location 必须**等于所在 entry 的 (q,r)**（否则拒），
    //     owner 必须是载荷里声明的 actors ∪ 现有状态里已有的主体（悬空 owner 拒）——故这里 owner 就是
    //     同一条载荷里声明的 estate:1_1。
    MINIMAL_PAYLOADS.put(
        "actor.Seed",
        "{\"mapId\":\"Map1\",\"rulesVersion\":\"actor-v1\",\"entries\":[{\"q\":1,\"r\":1,"
            + "\"actors\":[{\"kind\":\"ESTATE\",\"id\":\"farm@1_1\",\"label\":\"农业庄园\"}],"
            + "\"goods\":[{\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@1_1\"},"
            + "\"location\":{\"q\":1,\"r\":1},\"balances\":{\"grain\":2241000}}]}]}");
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
        .as("catalog 列出的 type 与 Shell 注册的 50 个 handler 同源")
        .containsExactlyInAnyOrderElementsOf(EXPECTED_COMMAND_TYPES);
    assertThat(MINIMAL_PAYLOADS.keySet())
        .as("用例为每个 catalog type 都备了载荷（漏一个就会在这里红）")
        .containsExactlyInAnyOrderElementsOf(catalogTypes);

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
        .as(
            "52 条命令各推一格（R1 起 +1 = social.SeedGroups；R2a 起 +1 = economy.Seed；S1 阶段 2 起 +1 = actor.Seed）")
        .isEqualTo(53L);

    // 3. 世界真的变了（不是"没报错"）：u-1 被解散；CreateUnit 建的 u-2 与三条编制命令的
    //    u-3/u-4/u-5 都还在（T9 新增：编制命令各挂在不同单位上，避免同一时刻对同一条段序列重复落段）。
    SimulationState afterUnitCommands = shell.coreSimos().replay(ref("main", 21));
    UnitState units = unitSlice(afterUnitCommands);
    assertThat(units.units().keySet())
        .as("u-1 已被 DisbandUnit 解散，只剩 u-2/u-3/u-4/u-5")
        .containsExactlyInAnyOrder(
            new UnitId("u-2"), new UnitId("u-3"), new UnitId("u-4"), new UnitId("u-5"));
    assertThat(units.units().get(new UnitId("u-2")).name()).isEqualTo("第二连");
    assertThat(units.units().get(new UnitId("u-2")).member()).isEqualTo(50);

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
                "sd", new SdSnapshot(ref("main", 1), T7, SdState.empty()),
                // ★ R2a：经济切片在场（economy.Seed 要往它上面施加变更集）。
                "economy", new EconomySnapshot(ref("main", 1), T7, EconomyData.empty()),
                // ★ S1 阶段 2：actor 切片在场（actor.Seed 要往它上面施加变更集；同 economy 的先例）。
                "actor", new ActorSnapshot(ref("main", 1), T7, ActorData.empty())),
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
                    new ActorCodec())));
  }

  private static Unit unit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        100,
        Map.of("步枪", 50),
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
        100,
        Map.of("步枪", 50),
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
