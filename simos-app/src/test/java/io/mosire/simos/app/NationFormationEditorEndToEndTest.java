package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.gov.nation.NationTag;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.MilitaryPayPolicy;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 国家/完整军队编制编辑的真 Shell + 固定 HTTP 桥端到端。
 *
 * <p>★ <b>判别力</b>：每次写入都走产品同一套 {@code Shell → GuiServer → RegistryEditorToolBridge → 领域工具 → Core
 * batch → handler/ChangeSet/Revision}；断言的是重放后的真实 {@code SimulationState}
 * （Nation/Region/Unit/Social/Army），不是工具回显。一次国家、一次整师各自只推一个 revision；任一后序 handler 拒绝时 head 与
 * map/social/unit/gov/army/economy/actor/ga 全部不变。PNG 必须真能解码、有真实像素，摘要数字必须与树一致。
 */
class NationFormationEditorEndToEndTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final BranchId MAIN = new BranchId("main");

  @TempDir Path tempDir;

  // ── 1. 一次建国家/GOV/整师，读回与图 ─────────────────────────────────

  @Test
  void createsNationGovernmentAndDivisionInSingleAtomicCallsThenReadsTreeAndPng() throws Exception {
    ShellConfig config = blankEditorConfig();
    try (Shell shell = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(shell)).isTrue();
      importMap(shell);

      SimulationState before = stateAt(shell, head(shell));
      var beforeEconomy = economy(before);
      var beforeActor = actor(before);
      var beforeGa = ga(before);

      long beforeNation = head(shell);
      JsonNode created = createNation(shell, beforeNation);
      long afterNation = head(shell);
      assertThat(afterNation).as("一次 editNation 只能推一个 revision").isEqualTo(beforeNation + 1);

      JsonNode nationResult = created.get("result");
      assertThat(nationResult.get("nationId").asText()).isEqualTo("demo-nation");
      assertThat(nationResult.get("hexCount").asInt()).isEqualTo(4);
      assertThat(nationResult.get("submission").get("ref").get("revision").asLong())
          .isEqualTo(afterNation);
      List<String> nationCommands = commandTypes(nationResult);
      assertThat(nationCommands)
          .as("一次 JSON 组出区域/国家/GOV/政府家户的完整有序批")
          .containsExactly(
              "map.CreateRegion",
              "gov.CreateNation",
              "unit.CreateUnit",
              "unit.SetGovFormation",
              "unit.SetJurisdiction",
              "social.SubmitHouseholdWorkOrder");

      SimulationState afterNationState = stateAt(shell, afterNation);
      GameMap map = gameMap(afterNationState);
      UnitState units = unitState(afterNationState);
      SocialData social = social(afterNationState);
      var gov = gov(afterNationState);
      Region home = map.regions().get(new RegionId("nation-home-demo-nation"));
      assertThat(home).as("国家 homeRegion 真实落在地图").isNotNull();
      assertThat(home.meta().tag()).isEqualTo(NationTag.tagFor(new NationId("demo-nation")));
      assertThat(home.hexes())
          .containsExactlyInAnyOrder(
              new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(2, 0), new HexCoord(0, 1));
      assertThat(home.meta().color()).isEqualTo("#3366CC");
      assertThat(gov.state().nations().get(new NationId("demo-nation")).name()).isEqualTo("演示国家");
      assertThat(gov.state().nations().get(new NationId("demo-nation")).homeRegion())
          .isEqualTo(home.id());

      Unit govUnit = units.units().get(new UnitId("demo-gov"));
      assertThat(govUnit).as("中央 GOV 是真实 Unit").isNotNull();
      assertThat(govUnit.module().orElseThrow()).isInstanceOf(GovernmentFormation.class);
      assertThat(govUnit.jurisdiction().orElseThrow().taxRatePerMilleByRegion())
          .containsKey(home.id());
      assertThat(govUnit.households())
          .as("SetGovFormation 自动挂入的政府家户必须在 Unit.households")
          .containsExactly(GovernmentHouseholds.of("demo-gov"));
      Household govHousehold = social.households().get(GovernmentHouseholds.of("demo-gov"));
      assertThat(govHousehold).as("同批建立的政府家户实体不得悬空").isNotNull();
      assertThat(govHousehold.members()).as("新 GOV 家户是 0 人空家户").isEmpty();
      assertThat(govHousehold.location()).isEqualTo(new HouseholdLocation.Unit("demo-gov"));

      assertThat(economy(afterNationState)).as("editNation 不碰 economy").isEqualTo(beforeEconomy);
      assertThat(actor(afterNationState)).as("editNation 不碰 actor").isEqualTo(beforeActor);
      assertThat(ga(afterNationState)).as("editNation 不碰 ga").isEqualTo(beforeGa);

      JsonNode nationRead =
          getJson(shell, "/api/editor/nation?branch=main&nationId=demo-nation&render=true");
      JsonNode nationView = nationRead.get("result").get("nations").get(0);
      assertThat(nationView.get("name").asText()).isEqualTo("演示国家");
      assertThat(nationView.get("color").asText()).isEqualTo("#3366CC");
      assertThat(nationView.get("hexCount").asInt()).isEqualTo(4);
      assertThat(nationView.get("regionIds"))
          .anySatisfy(id -> assertThat(id.asText()).isEqualTo("nation-home-demo-nation"));
      assertThat(nationView.get("govUnitIds"))
          .anySatisfy(id -> assertThat(id.asText()).isEqualTo("demo-gov"));
      assertPng(nationRead, "国家图");
      assertThat(nationRead.get("result").get("render").get("summary").asText())
          .contains("4 格")
          .contains("demo-nation");

      long beforeFormation = head(shell);
      JsonNode formation = createFormation(shell, beforeFormation);
      long afterFormation = head(shell);
      assertThat(afterFormation)
          .as("一次 editFormation 只能推一个 revision")
          .isEqualTo(beforeFormation + 1);
      JsonNode formationResult = formation.get("result");
      assertThat(formationResult.get("nodeCount").asInt()).isEqualTo(9);
      assertThat(formationResult.get("createdNodes").asInt()).isEqualTo(9);
      assertThat(formationResult.get("directPersonnel").asLong()).isEqualTo(60);
      assertThat(formationResult.get("subtreePersonnel").asLong()).isEqualTo(3040);
      assertThat(formationResult.get("submission").get("ref").get("revision").asLong())
          .isEqualTo(afterFormation);
      List<String> formationCommands = commandTypes(formationResult);
      assertThat(formationCommands.stream().filter("unit.CreateUnit"::equals).count())
          .as("每个节点一条 CreateUnit")
          .isEqualTo(9);
      assertThat(formationCommands.stream().filter("unit.SetArmyFormation"::equals).count())
          .isEqualTo(9);
      assertThat(
              formationCommands.stream().filter("social.SubmitHouseholdWorkOrder"::equals).count())
          .as("只有 personnel>0 的节点建 Social 家户工单")
          .isEqualTo(9);
      assertThat(formationCommands.stream().filter("unit.SetUnitHouseholds"::equals).count())
          .isEqualTo(9);
      assertThat(formationCommands).endsWith("army.CreateArmy");

      SimulationState afterFormationState = stateAt(shell, afterFormation);
      assertFormationState(afterFormationState);
      assertThat(economy(afterFormationState))
          .as("editFormation 不碰 economy")
          .isEqualTo(beforeEconomy);
      assertThat(actor(afterFormationState)).as("editFormation 不碰 actor").isEqualTo(beforeActor);
      assertThat(ga(afterFormationState)).as("editFormation 不碰 ga").isEqualTo(beforeGa);

      JsonNode formationRead =
          getJson(shell, "/api/editor/formation?branch=main&armyId=demo-army&render=true");
      JsonNode armyView = formationRead.get("result").get("armies").get(0);
      assertThat(armyView.get("nodeCount").asInt()).isEqualTo(9);
      assertThat(armyView.get("maxDepth").asInt()).isEqualTo(3);
      assertThat(armyView.get("directPersonnel").asLong())
          .as("FormationView.directPersonnel = root 本级人数")
          .isEqualTo(60);
      assertThat(armyView.get("subtreePersonnel").asLong()).isEqualTo(3040);
      JsonNode rootNode = node(armyView, "demo-div");
      assertThat(rootNode.get("parentId").isNull()).isTrue();
      assertThat(rootNode.get("attached").asBoolean()).as("新根默认 attached=false（独立单位）").isFalse();
      assertThat(rootNode.get("directPersonnel").asLong()).isEqualTo(60);
      assertThat(rootNode.get("subtreePersonnel").asLong()).isEqualTo(3040);
      JsonNode bn1 = node(armyView, "demo-bde-1-bn-1");
      assertThat(bn1.get("parentId").asText()).isEqualTo("demo-bde-1");
      assertThat(bn1.get("depth").asInt()).isEqualTo(3);
      assertThat(bn1.get("attached").asBoolean()).isTrue();
      assertThat(bn1.get("position").get("q").asInt()).isEqualTo(2);
      assertThat(bn1.get("position").get("r").asInt()).isEqualTo(0);
      assertThat(bn1.get("equipment")).hasSize(2);
      assertPng(formationRead, "编制图");
      assertThat(formationRead.get("result").get("render").get("summary").asText())
          .contains("9 节点")
          .contains("深度 3")
          .contains("直接人数 3040")
          .contains("子树合计 3040");

      // 冷启动：同一 store 重开，树/人员/领土/图仍可读。
      long finalRevision = head(shell);
      try (Shell reopened = Shell.start(config)) {
        assertThat(head(reopened)).isEqualTo(finalRevision);
        assertFormationState(stateAt(reopened, finalRevision));
        JsonNode readAgain =
            getJson(reopened, "/api/editor/formation?branch=main&armyId=demo-army&render=true");
        assertThat(readAgain.get("result").get("armies").get(0).get("nodeCount").asInt())
            .isEqualTo(9);
        assertPng(readAgain, "冷启动编制图");
        JsonNode nationAgain =
            getJson(reopened, "/api/editor/nation?branch=main&nationId=demo-nation");
        assertThat(nationAgain.get("result").get("nations").get(0).get("hexCount").asInt())
            .isEqualTo(4);
      }
    }
  }

  // ── 2. update：只改给定字段、保留未提及、加营/改隶属/detach、masterGov 扩散 ──

  @Test
  void updatePreservesUnmentionedNodesPayPolicyAndHouseholdsWhileReparentingAndDetaching()
      throws Exception {
    ShellConfig config = blankEditorConfig();
    try (Shell shell = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(shell)).isTrue();
      importMap(shell);
      createNation(shell, head(shell));
      createFormation(shell, head(shell));

      // 第二个国家 + GOV，作为 masterGov 迁移目标；同时给军俸政策一个真实落点。
      createRivalNation(shell, head(shell));
      long beforePolicy = head(shell);
      postCommand(
          shell,
          "unit.SetArmyFormation",
          "{\"unitId\":\"demo-bde-1-bn-3\",\"role\":\"BATTALION\","
              + "\"masterGov\":\"demo-gov\","
              + "\"militaryPayPolicy\":{\"periodDays\":30,\"phaseDay\":0,\"startsOnDay\":0,"
              + "\"grainPerHouseholdPerCycle\":{\"hh-unit:demo-bde-1-bn-3\":9}}}",
          beforePolicy);
      long afterPolicy = head(shell);
      assertThat(afterPolicy).isEqualTo(beforePolicy + 1);
      SimulationState policyState = stateAt(shell, afterPolicy);
      MilitaryPayPolicy policyBefore =
          ((ArmyFormation)
                  unitState(policyState)
                      .units()
                      .get(new UnitId("demo-bde-1-bn-3"))
                      .module()
                      .orElseThrow())
              .militaryPayPolicy();
      assertThat(policyBefore.enabled()).isTrue();

      // 完全未提及节点 demo-bde-1-bn-3：完整 Unit 记录与家户在更新前后对比。
      Unit unmentionedBefore = unitState(policyState).units().get(new UnitId("demo-bde-1-bn-3"));
      Region releasedRegion =
          gameMap(policyState).regions().get(new RegionId("nation-home-rival-nation"));
      assertThat(releasedRegion).as("rival 区域存在（用于非国家区域不误改断言）").isNotNull();

      long beforeUpdate = head(shell);
      JsonNode update = updateFormation(shell, beforeUpdate);
      long afterUpdate = head(shell);
      assertThat(afterUpdate).as("一次 update 一个 revision").isEqualTo(beforeUpdate + 1);
      JsonNode updateResult = update.get("result");
      assertThat(updateResult.get("submission").get("ref").get("revision").asLong())
          .isEqualTo(afterUpdate);
      assertThat(updateResult.get("createdNodes").asInt()).isEqualTo(1);
      assertThat(updateResult.get("nodeCount").asInt()).isEqualTo(10);

      SimulationState state = stateAt(shell, afterUpdate);
      UnitState units = unitState(state);
      SocialData social = social(state);
      // 改名 / 装备 / 人数。
      assertThat(legion(state, new ArmyId("demo-army")).name()).isEqualTo("演示师·改编");
      assertThat(units.units().get(new UnitId("demo-bde-1")).name()).isEqualTo("第1旅·主力");
      assertThat(units.units().get(new UnitId("demo-bde-1-bn-1")).equipment())
          .extracting(entry -> entry.type() + "=" + entry.amount())
          .containsExactlyInAnyOrder("步枪=400", "卡车=25");
      assertThat(social.unitPopulation("demo-bde-1-bn-2")).isEqualTo(520);
      assertThat(social.unitPopulation("demo-bde-2-bn-2")).isEqualTo(480);
      // 加营：未给坐标的新节点写实父位置，默认 attached。
      Unit added = units.units().get(new UnitId("demo-bde-1-bn-5"));
      assertThat(added).as("新营真实落成 Unit").isNotNull();
      assertThat(units.effectivePosition(added.id(), state.meta().timestamp()).orElseThrow())
          .isEqualTo(new HexCoord(2, 0));
      assertThat(added.attached().valueAt(state.meta().timestamp())).isTrue();
      assertThat(social.unitPopulation("demo-bde-1-bn-5")).isEqualTo(300);
      // 改隶属：bn4 从 bde2 改到 bde1。
      assertThat(parentOf(units, state, "demo-bde-2-bn-1")).contains(new UnitId("demo-bde-1"));
      // detach bde2 到 (2,1)：其未提及的营 bn2 必须跟着落到同格（只同步该节点附属后代）。
      Unit bde2 = units.units().get(new UnitId("demo-bde-2"));
      assertThat(bde2.attached().valueAt(state.meta().timestamp())).isFalse();
      assertThat(units.effectivePosition(bde2.id(), state.meta().timestamp()).orElseThrow())
          .isEqualTo(new HexCoord(2, 1));
      assertThat(
              units
                  .effectivePosition(new UnitId("demo-bde-2-bn-2"), state.meta().timestamp())
                  .orElseThrow())
          .as("detached 节点的 attached 后代跟随新位置")
          .isEqualTo(new HexCoord(2, 1));
      // 未提及节点：demo-bde-1-bn-3 的完整 Unit 记录逐字段保留（装备/速度/家户/状态/位置等）。
      Unit unmentionedAfter = units.units().get(new UnitId("demo-bde-1-bn-3"));
      assertThat(unmentionedAfter.name()).isEqualTo(unmentionedBefore.name());
      assertThat(unmentionedAfter.equipment()).isEqualTo(unmentionedBefore.equipment());
      assertThat(unmentionedAfter.speed()).isEqualTo(unmentionedBefore.speed());
      assertThat(unmentionedAfter.mobilityPerMille())
          .isEqualTo(unmentionedBefore.mobilityPerMille());
      assertThat(unmentionedAfter.jurisdiction()).isEqualTo(unmentionedBefore.jurisdiction());
      assertThat(unmentionedAfter.households()).isEqualTo(unmentionedBefore.households());
      assertThat(unmentionedAfter.status()).isEqualTo(unmentionedBefore.status());
      assertThat(unmentionedAfter.visionRadius()).isEqualTo(unmentionedBefore.visionRadius());
      assertThat(unmentionedAfter.stateDescriptions())
          .isEqualTo(unmentionedBefore.stateDescriptions());
      assertThat(unmentionedAfter.parent()).isEqualTo(unmentionedBefore.parent());
      assertThat(unmentionedAfter.position()).isEqualTo(unmentionedBefore.position());
      assertThat(unmentionedAfter.attached()).isEqualTo(unmentionedBefore.attached());
      // 另一个未提及的营 demo-bde-2-bn-3 只随 detached 父旅同步位置，其余字段保留。
      assertThat(
              units
                  .effectivePosition(new UnitId("demo-bde-2-bn-3"), state.meta().timestamp())
                  .orElseThrow())
          .as("detached 旅的未提及 attached 后代跟随新位置")
          .isEqualTo(new HexCoord(2, 1));
      assertThat(units.units().get(new UnitId("demo-bde-2-bn-3")).name()).isEqualTo("第6营");
      // masterGov 扩散：显式改 masterGov 后，未提及节点的 ArmyFormation.masterGov 与 Army 一致，政策不丢。
      assertThat(legion(state, new ArmyId("demo-army")).masterGovUnitId())
          .contains(new UnitId("rival-gov"));
      ArmyFormation unmentionedFormation =
          (ArmyFormation) units.units().get(new UnitId("demo-bde-1-bn-3")).module().orElseThrow();
      assertThat(unmentionedFormation.masterGov()).contains(new UnitId("rival-gov"));
      assertThat(unmentionedFormation.militaryPayPolicy())
          .as("军俸政策在扩散 masterGov 时保留")
          .isEqualTo(policyBefore);
      // 未提及的 rival 区域/其 meta 不被国家工具误改（本轮 updateFormation 不碰 map，但对照有意义）。
      assertThat(gameMap(state).regions().get(new RegionId("nation-home-rival-nation")).meta())
          .isEqualTo(releasedRegion.meta());
      // Social 人口与图树直接/子树人数不重复：逐节点 directPersonnel 之和 = 全树子树合计。
      JsonNode read = getJson(shell, "/api/editor/formation?branch=main&armyId=demo-army");
      JsonNode armyView = read.get("result").get("armies").get(0);
      long sumDirect = 0;
      for (JsonNode row : armyView.get("nodes")) {
        sumDirect += row.get("directPersonnel").asLong();
      }
      assertThat(sumDirect).isEqualTo(armyView.get("subtreePersonnel").asLong());
      assertThat(social.households()).containsKey(GovernmentHouseholds.of("demo-gov"));
      // 既有政府家户没有被人口工具重复覆盖。
      Household govHousehold = social.households().get(GovernmentHouseholds.of("demo-gov"));
      assertThat(govHousehold.members()).isEmpty();
    }
  }

  // ── 3. 后序 handler 拒绝 ⇒ 整批零 revision、各模块不变；Core 端口契约 ──

  @Test
  void laterHandlerRejectionRollsBackBothNewToolsAndCoreBatch() throws Exception {
    ShellConfig config = blankEditorConfig();
    try (Shell shell = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(shell)).isTrue();
      importMap(shell);

      // (a) editNation：plan 全过，但既有 unit 已悬空占用 hh-gov-clash-gov；新 GOV 的 SetGovFormation
      //     在 UnitState 构造期因“同一家户属于两个 unit”被 handler 拒（不是 plan 前置拒绝）。
      long beforeSeed = head(shell);
      postCommand(
          shell,
          "unit.CreateUnit",
          "{\"id\":\"seed-unit\",\"name\":\"占位家户单位\",\"position\":{\"q\":0,\"r\":0},"
              + "\"equipment\":[],\"speed\":1,\"mobilityPerMille\":1000,\"households\":[]}",
          beforeSeed);
      long seededUnit = head(shell);
      postCommand(
          shell,
          "unit.SetUnitHouseholds",
          "{\"unitId\":\"seed-unit\",\"households\":[\"hh-gov-clash-gov\"],"
              + "\"reason\":\"rollback 夹具：悬空占用政府家户\"}",
          seededUnit);
      long seeded = head(shell);
      SimulationState beforeNation = stateAt(shell, seeded);
      HttpResponse<String> nationFailure =
          postEditor(
              shell,
              "nation",
              "{\"mode\":\"create\",\"nationId\":\"rollback-nation\",\"name\":\"回滚国\","
                  + "\"color\":\"#112233\",\"hexes\":[{\"q\":0,\"r\":0}],"
                  + "\"government\":{\"unitId\":\"clash-gov\",\"name\":\"撞名 GOV\","
                  + "\"position\":{\"q\":0,\"r\":0}},"
                  + "\"reason\":\"后序 handler 拒绝\",\"branch\":\"main\",\"expectedRevision\":"
                  + seeded
                  + "}");
      assertThat(nationFailure.statusCode()).as(nationFailure.body()).isEqualTo(422);
      assertThat(nationFailure.body()).contains("unit.SetGovFormation");
      assertThat(head(shell)).as("整批回滚：零 revision").isEqualTo(seeded);
      assertUntouched(stateAt(shell, seeded), beforeNation);
      SimulationState afterFailedNation = stateAt(shell, seeded);
      assertThat(gov(afterFailedNation).state().nations())
          .doesNotContainKey(new NationId("rollback-nation"));

      // (b) editFormation：plan 全过，CREATE_HOUSEHOLD 因 hh-unit:<node> 已存在被 Social handler 拒。
      long beforeSeed2 = head(shell);
      postCommand(
          shell,
          "social.SubmitHouseholdWorkOrder",
          "{\"target\":\"hh-unit:rollback-bn\",\"reason\":\"rollback 夹具\","
              + "\"source\":{\"module\":\"test\"},"
              + "\"plan\":[{\"op\":\"CREATE_HOUSEHOLD\",\"household\":\"hh-unit:rollback-bn\","
              + "\"location\":{\"type\":\"HEX\",\"hex\":{\"q\":1,\"r\":0}},"
              + "\"profile\":{\"name\":\"已存在家户\"},\"vitalRates\":[]}]}",
          beforeSeed2);
      long seeded2 = head(shell);
      SimulationState beforeFormation = stateAt(shell, seeded2);
      HttpResponse<String> formationFailure =
          postEditor(
              shell,
              "formation",
              "{\"mode\":\"create\",\"armyId\":\"rollback-army\",\"name\":\"回滚师\","
                  + "\"root\":{\"id\":\"rollback-div\",\"name\":\"回滚师\",\"role\":\"DIVISION\","
                  + "\"position\":{\"q\":1,\"r\":0},\"personnel\":10,"
                  + "\"children\":[{\"id\":\"rollback-bn\",\"name\":\"回滚营\","
                  + "\"role\":\"BATTALION\",\"personnel\":10}]},"
                  + "\"reason\":\"后序 handler 拒绝\",\"branch\":\"main\",\"expectedRevision\":"
                  + seeded2
                  + "}");
      assertThat(formationFailure.statusCode()).as(formationFailure.body()).isEqualTo(422);
      assertThat(formationFailure.body()).contains("social.SubmitHouseholdWorkOrder");
      assertThat(head(shell)).as("整批回滚：零 revision").isEqualTo(seeded2);
      assertUntouched(stateAt(shell, seeded2), beforeFormation);
      assertThat(unitState(stateAt(shell, seeded2)).units())
          .doesNotContainKey(new UnitId("rollback-div"))
          .doesNotContainKey(new UnitId("rollback-bn"));
      assertThat(army(stateAt(shell, seeded2)).data().armies())
          .doesNotContainKey(new ArmyId("rollback-army"));

      // (c) 独立向 Core 提交“合法前半 + 非法后半”，直接证明端口批量语义。
      long beforeCore = head(shell);
      SimulationState beforeCoreState = stateAt(shell, beforeCore);
      BatchResult coreBatch =
          shell
              .coreSimos()
              .submitBatch(
                  List.of(
                      new CommandEnvelope(
                          "core-c1",
                          "core-c1",
                          "test",
                          MAIN,
                          new RevisionId(beforeCore),
                          "map.CreateRegion",
                          "{\"regionId\":\"core-half\",\"name\":\"Core 前半\","
                              + "\"hexes\":[{\"q\":2,\"r\":0}],"
                              + "\"meta\":{\"color\":\"#445566\",\"tag\":null,"
                              + "\"description\":null,\"annexedBy\":null}}"),
                      new CommandEnvelope(
                          "core-c2",
                          "core-c2",
                          "test",
                          MAIN,
                          new RevisionId(beforeCore),
                          "unit.SetJurisdiction",
                          "{\"unitId\":\"no-such-unit\",\"regions\":[]}")));
      assertThat(coreBatch)
          .as("合法前半 + 非法后半 ⇒ 整批 Rejected")
          .isInstanceOf(BatchResult.Rejected.class);
      assertThat(head(shell)).isEqualTo(beforeCore);
      assertUntouched(stateAt(shell, beforeCore), beforeCoreState);
      assertThat(gameMap(stateAt(shell, beforeCore)).regions())
          .doesNotContainKey(new RegionId("core-half"));
    }
  }

  // ── 4. 多区域国家：并集去重、解除 tag 保留身份/几何/meta、默认冲突拒绝、显式 transfer ──

  @Test
  void updatesMultiRegionNationMergesUnionReleasesTagsAndTransfersOnlyExplicitly()
      throws Exception {
    ShellConfig config = blankEditorConfig();
    try (Shell shell = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(shell)).isTrue();
      importMap(shell);
      createNation(shell, head(shell));
      // 第二个同 tag 区域（模拟旧的多区域国家表示）+ 一个非国家区域 + 邻国。
      long rev = head(shell);
      postCommand(
          shell,
          "map.CreateRegion",
          "{\"regionId\":\"outer-province\",\"name\":\"外省\","
              + "\"hexes\":[{\"q\":2,\"r\":0},{\"q\":2,\"r\":1}],"
              + "\"meta\":{\"color\":\"#3366CC\",\"tag\":\"nation:demo-nation\","
              + "\"description\":\"outer meta\",\"annexedBy\":null}}",
          rev);
      rev = head(shell);
      postCommand(
          shell,
          "map.CreateRegion",
          "{\"regionId\":\"neutral-zone\",\"name\":\"中立地带\","
              + "\"hexes\":[{\"q\":3,\"r\":0},{\"q\":3,\"r\":1}],"
              + "\"meta\":{\"color\":\"#999999\",\"tag\":\"province\","
              + "\"description\":\"keep me\",\"annexedBy\":null}}",
          rev);
      long rivalRev = head(shell);
      postCommand(
          shell,
          "map.CreateRegion",
          "{\"regionId\":\"rival-home\",\"name\":\"邻国边地\","
              + "\"hexes\":[{\"q\":1,\"r\":1},{\"q\":2,\"r\":2}],"
              + "\"meta\":{\"color\":\"#AA3333\",\"tag\":\"nation:rival-nation\","
              + "\"description\":\"rival meta\",\"annexedBy\":null}}",
          rivalRev);

      JsonNode before =
          getJson(shell, "/api/editor/nation?branch=main&nationId=demo-nation&render=true");
      JsonNode beforeView = before.get("result").get("nations").get(0);
      assertThat(beforeView.get("hexCount").asInt()).as("两个同 tag 区域并集（2,0 重叠只算一次）").isEqualTo(5);
      assertThat(beforeView.get("regionIds")).hasSize(2);
      assertThat(beforeView.get("colorConflict").asBoolean()).isFalse();

      long beforeReject = head(shell);
      String overlapBody =
          "{\"mode\":\"update\",\"nationId\":\"demo-nation\",\"name\":\"演示联合体\","
              + "\"color\":\"#224488\",\"hexes\":[{\"q\":1,\"r\":1}],"
              + "\"territoryMode\":\"add\",\"overlapPolicy\":\"reject\","
              + "\"reason\":\"默认冲突拒绝\",\"branch\":\"main\",\"expectedRevision\":"
              + beforeReject
              + "}";
      HttpResponse<String> rejected = postEditor(shell, "nation", overlapBody);
      assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(400);
      assertThat(rejected.body()).contains("领土冲突").contains("nation:rival-nation");
      assertThat(head(shell)).isEqualTo(beforeReject);

      String transferBody =
          "{\"mode\":\"update\",\"nationId\":\"demo-nation\",\"name\":\"演示联合体\","
              + "\"color\":\"#224488\","
              + "\"hexes\":[{\"q\":2,\"r\":0},{\"q\":2,\"r\":1},{\"q\":1,\"r\":1}],"
              + "\"territoryMode\":\"add\",\"overlapPolicy\":\"transfer\","
              + "\"reason\":\"显式 transfer + 合并多区域\",\"branch\":\"main\","
              + "\"expectedRevision\":"
              + beforeReject
              + "}";
      HttpResponse<String> updated = postEditor(shell, "nation", transferBody);
      assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
      assertThat(head(shell)).isEqualTo(beforeReject + 1);
      JsonNode result = JSON.readTree(updated.body()).get("result");
      assertThat(result.get("hexCount").asInt()).isEqualTo(6);
      assertThat(result.get("releasedRegionIds"))
          .anySatisfy(id -> assertThat(id.asText()).isEqualTo("outer-province"));
      assertThat(result.get("transferredFromRegionIds"))
          .anySatisfy(id -> assertThat(id.asText()).isEqualTo("rival-home"));

      SimulationState state = stateAt(shell, head(shell));
      GameMap map = gameMap(state);
      Region home = map.regions().get(new RegionId("nation-home-demo-nation"));
      assertThat(home.hexes())
          .containsExactlyInAnyOrder(
              new HexCoord(0, 0),
              new HexCoord(1, 0),
              new HexCoord(2, 0),
              new HexCoord(2, 1),
              new HexCoord(0, 1),
              new HexCoord(1, 1));
      assertThat(home.name()).isEqualTo("演示联合体");
      assertThat(home.meta().color()).isEqualTo("#224488");
      assertThat(home.meta().tag()).isEqualTo("nation:demo-nation");
      // 旧同标签区域：身份/几何/其他 meta 保留，只解除 tag。
      Region outer = map.regions().get(new RegionId("outer-province"));
      assertThat(outer).isNotNull();
      assertThat(outer.hexes()).containsExactlyInAnyOrder(new HexCoord(2, 0), new HexCoord(2, 1));
      assertThat(outer.name()).isEqualTo("外省");
      assertThat(outer.meta().tag()).isNull();
      assertThat(outer.meta().description()).isEqualTo("outer meta");
      // 非国家区域逐字不动。
      Region neutral = map.regions().get(new RegionId("neutral-zone"));
      assertThat(neutral.meta().tag()).isEqualTo("province");
      assertThat(neutral.meta().description()).isEqualTo("keep me");
      assertThat(neutral.hexes()).containsExactlyInAnyOrder(new HexCoord(3, 0), new HexCoord(3, 1));
      // transfer 只扣目标格，邻国区域仍非空且仍是邻国 tag。
      Region rival = map.regions().get(new RegionId("rival-home"));
      assertThat(rival.hexes()).containsExactly(new HexCoord(2, 2));
      assertThat(rival.meta().tag()).isEqualTo("nation:rival-nation");
      assertThat(rival.meta().description()).isEqualTo("rival meta");
      assertThat(gov(state).state().nations().get(new NationId("demo-nation")).name())
          .isEqualTo("演示联合体");

      JsonNode read =
          getJson(shell, "/api/editor/nation?branch=main&nationId=demo-nation&render=true");
      JsonNode view = read.get("result").get("nations").get(0);
      assertThat(view.get("name").asText()).isEqualTo("演示联合体");
      assertThat(view.get("hexCount").asInt()).isEqualTo(6);
      assertThat(view.get("regionIds")).hasSize(1);
      assertThat(read.get("result").get("render").get("summary").asText())
          .contains("6 格")
          .contains("演示联合体");
      assertPng(read, "多区域合并国家图");

      // 未知枚举即使没有再给 hexes 也必须拒；update 的 dryRun 同样要拒重复/未知格。
      long unchanged = head(shell);
      String[] invalidNationUpdates = {
        "{\"mode\":\"update\",\"nationId\":\"demo-nation\",\"territoryMode\":\"bogus\","
            + "\"reason\":\"未知 territoryMode\",\"dryRun\":true,\"branch\":\"main\",\"revision\":"
            + unchanged
            + "}",
        "{\"mode\":\"update\",\"nationId\":\"demo-nation\",\"overlapPolicy\":\"bogus\","
            + "\"reason\":\"未知 overlapPolicy\",\"dryRun\":true,\"branch\":\"main\",\"revision\":"
            + unchanged
            + "}",
        "{\"mode\":\"update\",\"nationId\":\"demo-nation\","
            + "\"hexes\":[{\"q\":0,\"r\":0},{\"q\":0,\"r\":0}],"
            + "\"reason\":\"重复格\",\"dryRun\":true,\"branch\":\"main\",\"revision\":"
            + unchanged
            + "}",
        "{\"mode\":\"update\",\"nationId\":\"demo-nation\","
            + "\"hexes\":[{\"q\":9,\"r\":9}],"
            + "\"reason\":\"未知格\",\"dryRun\":true,\"branch\":\"main\",\"revision\":"
            + unchanged
            + "}"
      };
      String[] reasons = {"territoryMode", "overlapPolicy", "重复格", "不在图上"};
      for (int index = 0; index < invalidNationUpdates.length; index++) {
        HttpResponse<String> response = postEditor(shell, "nation", invalidNationUpdates[index]);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).contains(reasons[index]);
      }
      assertThat(head(shell)).isEqualTo(unchanged);
    }
  }

  // ── 5. 具名负向：重复/循环/整数/溢出/超限/attached 跨格/GOV/位置/越其他 Army ──

  @Test
  void rejectsNamedInvalidFormationAndNationInputsWithoutAdvancingRevision() throws Exception {
    ShellConfig config = blankEditorConfig();
    try (Shell shell = Shell.start(config)) {
      assertThat(ShellMain.seedGenesisIfEmpty(shell)).isTrue();
      importMap(shell);
      createNation(shell, head(shell));
      createFormation(shell, head(shell));
      long base = head(shell);

      // 重复节点 id。
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"create\",\"armyId\":\"bad-dup\",\"name\":\"重复\","
              + "\"root\":{\"id\":\"bad-dup-root\",\"name\":\"根\",\"role\":\"DIVISION\","
              + "\"position\":{\"q\":2,\"r\":0},\"children\":["
              + "{\"id\":\"bad-same\",\"name\":\"甲\",\"role\":\"BATTALION\"},"
              + "{\"id\":\"bad-same\",\"name\":\"乙\",\"role\":\"BATTALION\"}]},"
              + "\"reason\":\"重复\",\"branch\":\"main\",\"expectedRevision\":"
              + base
              + "}",
          400,
          "重复");
      // 非整数 / 负数 / 溢出。
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"create\",\"armyId\":\"bad-frac\",\"name\":\"小数\","
              + "\"root\":{\"id\":\"bad-frac-root\",\"name\":\"根\",\"role\":\"DIVISION\","
              + "\"position\":{\"q\":2,\"r\":0},\"personnel\":1.5},"
              + "\"reason\":\"小数\",\"branch\":\"main\",\"expectedRevision\":"
              + base
              + "}",
          400,
          "整数");
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"create\",\"armyId\":\"bad-neg\",\"name\":\"负数\","
              + "\"root\":{\"id\":\"bad-neg-root\",\"name\":\"根\",\"role\":\"DIVISION\","
              + "\"position\":{\"q\":2,\"r\":0},\"personnel\":-1},"
              + "\"reason\":\"负数\",\"branch\":\"main\",\"expectedRevision\":"
              + base
              + "}",
          400,
          "personnel");
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"create\",\"armyId\":\"bad-overflow\",\"name\":\"溢出\","
              + "\"root\":{\"id\":\"bad-overflow-root\",\"name\":\"根\",\"role\":\"DIVISION\","
              + "\"position\":{\"q\":2,\"r\":0},\"personnel\":9223372036854775808},"
              + "\"reason\":\"溢出\",\"branch\":\"main\",\"expectedRevision\":"
              + base
              + "}",
          400,
          "personnel");
      // attached=true 显式坐标与父不同格 / 坐标不在图上。
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"create\",\"armyId\":\"bad-cross\",\"name\":\"跨格\","
              + "\"root\":{\"id\":\"bad-cross-root\",\"name\":\"根\",\"role\":\"DIVISION\","
              + "\"position\":{\"q\":2,\"r\":0},\"children\":["
              + "{\"id\":\"bad-cross-bn\",\"name\":\"营\",\"role\":\"BATTALION\","
              + "\"position\":{\"q\":2,\"r\":1}}]},"
              + "\"reason\":\"跨格\",\"branch\":\"main\",\"expectedRevision\":"
              + base
              + "}",
          400,
          "不同格");
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"create\",\"armyId\":\"bad-offmap\",\"name\":\"图外\","
              + "\"root\":{\"id\":\"bad-offmap-root\",\"name\":\"根\",\"role\":\"DIVISION\","
              + "\"position\":{\"q\":99,\"r\":99}},"
              + "\"reason\":\"图外\",\"branch\":\"main\",\"expectedRevision\":"
              + base
              + "}",
          400,
          "不在图上");
      // 深度 17 / 513 节点。
      String deep = nestedNode(17, "bad-deep");
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"create\",\"armyId\":\"bad-deep\",\"name\":\"深树\",\"root\":"
              + deep
              + ",\"reason\":\"深度\",\"branch\":\"main\",\"expectedRevision\":"
              + base
              + "}",
          400,
          "深度");
      StringBuilder wide = new StringBuilder();
      wide.append(
          "{\"id\":\"bad-wide-root\",\"name\":\"根\",\"role\":\"DIVISION\","
              + "\"position\":{\"q\":2,\"r\":0},\"children\":[");
      for (int index = 0; index < 512; index++) {
        if (index > 0) {
          wide.append(',');
        }
        wide.append("{\"id\":\"bad-wide-")
            .append(index)
            .append("\",\"name\":\"营\",\"role\":\"BATTALION\"}");
      }
      wide.append("]}");
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"create\",\"armyId\":\"bad-wide\",\"name\":\"宽树\",\"root\":"
              + wide
              + ",\"reason\":\"规模\",\"branch\":\"main\",\"expectedRevision\":"
              + base
              + "}",
          400,
          "上限");
      // GOV 不存在 / 非 GOV；root 更改；越其他 Army。
      String plainArmy =
          "{\"mode\":\"create\",\"armyId\":\"other-army\",\"name\":\"他军\","
              + "\"root\":{\"id\":\"other-div\",\"name\":\"他师\",\"role\":\"DIVISION\","
              + "\"position\":{\"q\":2,\"r\":0},\"children\":["
              + "{\"id\":\"other-bn\",\"name\":\"他营\",\"role\":\"BATTALION\"}]},"
              + "\"reason\":\"他军\",\"branch\":\"main\",\"expectedRevision\":"
              + base
              + "}";
      assertThat(postEditor(shell, "formation", plainArmy).statusCode()).isEqualTo(200);
      long withOther = head(shell);
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"update\",\"armyId\":\"demo-army\","
              + "\"root\":{\"id\":\"demo-div\",\"children\":[{\"id\":\"other-bn\"}]},"
              + "\"reason\":\"越其他 Army\",\"branch\":\"main\",\"expectedRevision\":"
              + withOther
              + "}",
          400,
          "其他 Army");
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"update\",\"armyId\":\"demo-army\","
              + "\"root\":{\"id\":\"demo-div\"},\"masterGov\":\"no-such-gov\","
              + "\"reason\":\"GOV 不存在\",\"branch\":\"main\",\"expectedRevision\":"
              + withOther
              + "}",
          400,
          "masterGov");
      assertRejectedEditor(
          shell,
          "formation",
          "{\"mode\":\"update\",\"armyId\":\"demo-army\","
              + "\"root\":{\"id\":\"wrong-root\"},"
              + "\"reason\":\"改 root\",\"branch\":\"main\",\"expectedRevision\":"
              + withOther
              + "}",
          400,
          "root");
      // 国家负向：坏色值/重复格/未知格/新 GOV 无位置/新 GOV 图外/未知 overlapPolicy。
      String[] invalidNation = {
        "{\"mode\":\"create\",\"nationId\":\"bad-color\",\"name\":\"坏色\",\"color\":\"red\","
            + "\"hexes\":[{\"q\":0,\"r\":0}],\"reason\":\"坏色\",\"branch\":\"main\","
            + "\"expectedRevision\":"
            + withOther
            + "}",
        "{\"mode\":\"create\",\"nationId\":\"bad-hex\",\"name\":\"重复格\",\"color\":\"#123456\","
            + "\"hexes\":[{\"q\":0,\"r\":0},{\"q\":0,\"r\":0}],\"reason\":\"重复\","
            + "\"branch\":\"main\",\"expectedRevision\":"
            + withOther
            + "}",
        "{\"mode\":\"create\",\"nationId\":\"bad-gov-none\",\"name\":\"无位置\","
            + "\"color\":\"#123456\",\"hexes\":[{\"q\":3,\"r\":2}],"
            + "\"government\":{\"unitId\":\"bad-gov-none\"},"
            + "\"reason\":\"GOV 无位置\",\"branch\":\"main\",\"expectedRevision\":"
            + withOther
            + "}",
        "{\"mode\":\"create\",\"nationId\":\"bad-gov-off\",\"name\":\"图外 GOV\","
            + "\"color\":\"#123456\",\"hexes\":[{\"q\":1,\"r\":2}],"
            + "\"government\":{\"unitId\":\"bad-gov-off\",\"position\":{\"q\":99,\"r\":99}},"
            + "\"reason\":\"GOV 图外\",\"branch\":\"main\",\"expectedRevision\":"
            + withOther
            + "}"
      };
      String[] nationReasons = {"color", "重复格", "position", "不在图上"};
      for (int index = 0; index < invalidNation.length; index++) {
        assertRejectedEditor(shell, "nation", invalidNation[index], 400, nationReasons[index]);
      }
      // stale revision 必须 409，且不推进。
      HttpResponse<String> stale =
          postEditor(
              shell,
              "formation",
              "{\"mode\":\"update\",\"armyId\":\"demo-army\",\"name\":\"迟到\","
                  + "\"root\":{\"id\":\"demo-div\"},\"reason\":\"stale\","
                  + "\"branch\":\"main\",\"expectedRevision\":"
                  + (withOther - 1)
                  + "}");
      assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
      assertThat(stale.body()).contains("conflict").contains(String.valueOf(withOther));
      assertThat(head(shell)).isEqualTo(withOther);
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private ShellConfig blankEditorConfig() {
    return ShellConfig.defaults(tempDir).withWorldId(WorldRegistry.BLANK_EDITOR).withPorts(0, 0);
  }

  private static void importMap(Shell shell) throws Exception {
    long revision = head(shell);
    JsonNode imported =
        postCommand(
            shell,
            "map.ImportRaster",
            "{\"mode\":\"merge\",\"cells\":["
                + cell(0, 0)
                + ","
                + cell(1, 0)
                + ","
                + cell(2, 0)
                + ","
                + cell(3, 0)
                + ","
                + cell(0, 1)
                + ","
                + cell(1, 1)
                + ","
                + cell(2, 1)
                + ","
                + cell(3, 1)
                + ","
                + cell(0, 2)
                + ","
                + cell(1, 2)
                + ","
                + cell(2, 2)
                + ","
                + cell(3, 2)
                + "]}",
            revision);
    assertThat(imported.get("result").asText()).isEqualTo("committed");
    assertThat(head(shell)).isEqualTo(revision + 1);
  }

  private static String cell(int q, int r) {
    return "{\"q\":" + q + ",\"r\":" + r + ",\"terrain\":\"plains\",\"height\":0.4}";
  }

  private static JsonNode createNation(Shell shell, long expectedRevision) throws Exception {
    String body =
        "{\"mode\":\"create\",\"nationId\":\"demo-nation\",\"name\":\"演示国家\","
            + "\"color\":\"#3366CC\","
            + "\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0},{\"q\":2,\"r\":0},{\"q\":0,\"r\":1}],"
            + "\"territoryMode\":\"replace\",\"overlapPolicy\":\"reject\","
            + "\"government\":{\"unitId\":\"demo-gov\",\"name\":\"演示中央政府\","
            + "\"position\":{\"q\":0,\"r\":0}},"
            + "\"reason\":\"E2E 建国家与中央 GOV\",\"branch\":\"main\",\"expectedRevision\":"
            + expectedRevision
            + ",\"dryRun\":false}";
    HttpResponse<String> response = postEditor(shell, "nation", body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static JsonNode createRivalNation(Shell shell, long expectedRevision) throws Exception {
    String body =
        "{\"mode\":\"create\",\"nationId\":\"rival-nation\",\"name\":\"邻国\","
            + "\"color\":\"#AA3333\","
            + "\"hexes\":[{\"q\":3,\"r\":0},{\"q\":3,\"r\":1}],"
            + "\"government\":{\"unitId\":\"rival-gov\",\"name\":\"邻国 GOV\","
            + "\"position\":{\"q\":3,\"r\":0}},"
            + "\"reason\":\"E2E 第二国家\",\"branch\":\"main\",\"expectedRevision\":"
            + expectedRevision
            + "}";
    HttpResponse<String> response = postEditor(shell, "nation", body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static JsonNode createFormation(Shell shell, long expectedRevision) throws Exception {
    String body =
        "{\"mode\":\"create\",\"armyId\":\"demo-army\",\"name\":\"演示师\","
            + "\"masterGov\":\"demo-gov\","
            + "\"root\":{\"id\":\"demo-div\",\"name\":\"第1师\",\"role\":\"DIVISION\","
            + "\"position\":{\"q\":2,\"r\":0},\"personnel\":60,\"equipment\":{\"步枪\":20},"
            + "\"children\":["
            + "{\"id\":\"demo-bde-1\",\"name\":\"第1旅\",\"role\":\"BRIGADE\",\"personnel\":40,"
            + "\"children\":["
            + "{\"id\":\"demo-bde-1-bn-1\",\"name\":\"第1营\",\"role\":\"BATTALION\","
            + "\"personnel\":500,\"equipment\":{\"步枪\":450,\"卡车\":20},"
            + "\"speed\":2,\"mobilityPerMille\":900},"
            + "{\"id\":\"demo-bde-1-bn-2\",\"name\":\"第2营\",\"role\":\"BATTALION\","
            + "\"personnel\":500},"
            + "{\"id\":\"demo-bde-1-bn-3\",\"name\":\"第3营\",\"role\":\"BATTALION\","
            + "\"personnel\":400,\"equipment\":{\"步枪\":100}}]},"
            + "{\"id\":\"demo-bde-2\",\"name\":\"第2旅\",\"role\":\"BRIGADE\",\"personnel\":40,"
            + "\"children\":["
            + "{\"id\":\"demo-bde-2-bn-1\",\"name\":\"第4营\",\"role\":\"BATTALION\","
            + "\"personnel\":500,\"equipment\":{\"步枪\":250},"
            + "\"speed\":3,\"mobilityPerMille\":800},"
            + "{\"id\":\"demo-bde-2-bn-2\",\"name\":\"第5营\",\"role\":\"BATTALION\","
            + "\"personnel\":500},"
            + "{\"id\":\"demo-bde-2-bn-3\",\"name\":\"第6营\",\"role\":\"BATTALION\","
            + "\"personnel\":500}]}]},"
            + "\"reason\":\"E2E 建整师\",\"branch\":\"main\",\"expectedRevision\":"
            + expectedRevision
            + "}";
    HttpResponse<String> response = postEditor(shell, "formation", body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static JsonNode updateFormation(Shell shell, long expectedRevision) throws Exception {
    // 完全未提及：demo-bde-1-bn-3（其军俸政策必须保留）与 demo-bde-2-bn-3（随 detached 旅同步位置）。
    String body =
        "{\"mode\":\"update\",\"armyId\":\"demo-army\",\"name\":\"演示师·改编\","
            + "\"masterGov\":\"rival-gov\","
            + "\"root\":{\"id\":\"demo-div\",\"name\":\"第1师\",\"role\":\"DIVISION\","
            + "\"position\":{\"q\":2,\"r\":0},\"children\":["
            + "{\"id\":\"demo-bde-1\",\"name\":\"第1旅·主力\",\"role\":\"BRIGADE\","
            + "\"children\":["
            + "{\"id\":\"demo-bde-1-bn-1\",\"equipment\":{\"步枪\":400,\"卡车\":25}},"
            + "{\"id\":\"demo-bde-1-bn-2\",\"personnel\":520},"
            + "{\"id\":\"demo-bde-2-bn-1\"},"
            + "{\"id\":\"demo-bde-1-bn-5\",\"name\":\"新营 5\",\"role\":\"BATTALION\","
            + "\"personnel\":300,\"equipment\":{\"步枪\":300}}]},"
            + "{\"id\":\"demo-bde-2\",\"attached\":false,\"position\":{\"q\":2,\"r\":1},"
            + "\"children\":["
            + "{\"id\":\"demo-bde-2-bn-2\",\"personnel\":480}]}]},"
            + "\"reason\":\"E2E 改编\",\"branch\":\"main\",\"expectedRevision\":"
            + expectedRevision
            + "}";
    HttpResponse<String> response = postEditor(shell, "formation", body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static void assertFormationState(SimulationState state) {
    UnitState units = unitState(state);
    SocialData social = social(state);
    Army army = legion(state, new ArmyId("demo-army"));
    assertThat(army.name()).isEqualTo("演示师");
    assertThat(army.masterGovUnitId()).contains(new UnitId("demo-gov"));
    assertThat(army.rootUnit()).isEqualTo(new UnitId("demo-div"));
    assertThat(army.contactWatch()).isFalse();

    Map<String, String> expectedParent = new LinkedHashMap<>();
    expectedParent.put("demo-div", null);
    expectedParent.put("demo-bde-1", "demo-div");
    expectedParent.put("demo-bde-2", "demo-div");
    expectedParent.put("demo-bde-1-bn-1", "demo-bde-1");
    expectedParent.put("demo-bde-1-bn-2", "demo-bde-1");
    expectedParent.put("demo-bde-1-bn-3", "demo-bde-1");
    expectedParent.put("demo-bde-2-bn-1", "demo-bde-2");
    expectedParent.put("demo-bde-2-bn-2", "demo-bde-2");
    expectedParent.put("demo-bde-2-bn-3", "demo-bde-2");
    Map<String, Boolean> expectedAttached = new LinkedHashMap<>();
    expectedAttached.put("demo-div", false);
    expectedAttached.put("demo-bde-1", true);
    expectedAttached.put("demo-bde-2", true);
    expectedAttached.put("demo-bde-1-bn-1", true);
    expectedAttached.put("demo-bde-1-bn-2", true);
    expectedAttached.put("demo-bde-1-bn-3", true);
    expectedAttached.put("demo-bde-2-bn-1", true);
    expectedAttached.put("demo-bde-2-bn-2", true);
    expectedAttached.put("demo-bde-2-bn-3", true);
    Map<String, Long> expectedPeople = new LinkedHashMap<>();
    expectedPeople.put("demo-div", 60L);
    expectedPeople.put("demo-bde-1", 40L);
    expectedPeople.put("demo-bde-2", 40L);
    expectedPeople.put("demo-bde-1-bn-1", 500L);
    expectedPeople.put("demo-bde-1-bn-2", 500L);
    expectedPeople.put("demo-bde-1-bn-3", 400L);
    expectedPeople.put("demo-bde-2-bn-1", 500L);
    expectedPeople.put("demo-bde-2-bn-2", 500L);
    expectedPeople.put("demo-bde-2-bn-3", 500L);
    for (Map.Entry<String, String> entry : expectedParent.entrySet()) {
      UnitId id = new UnitId(entry.getKey());
      Unit unit = units.units().get(id);
      assertThat(unit).as("节点 %s 必须存在", entry.getKey()).isNotNull();
      assertThat(unit.attached().valueAt(state.meta().timestamp()))
          .as("根默认 attached=false；新下级默认 attached=true")
          .isEqualTo(expectedAttached.get(entry.getKey()));
      assertThat(units.effectivePosition(id, state.meta().timestamp()).orElseThrow())
          .as("省略坐标的节点写实父位置")
          .isEqualTo(new HexCoord(2, 0));
      assertThat(parentOf(units, state, entry.getKey()))
          .as("节点 %s 的 parent", entry.getKey())
          .isEqualTo(Optional.ofNullable(entry.getValue()).map(UnitId::new));
      ArmyFormation formation = (ArmyFormation) unit.module().orElseThrow();
      assertThat(formation.masterGov()).contains(new UnitId("demo-gov"));
      assertThat(formation.role()).isNotBlank();
      assertThat(social.unitPopulation(entry.getKey()))
          .isEqualTo(expectedPeople.get(entry.getKey()));
      HouseholdId household = new HouseholdId("hh-unit:" + entry.getKey());
      assertThat(unit.households()).as("Unit.households 与 Social 一致").contains(household);
      Household socialHousehold = social.households().get(household);
      assertThat(socialHousehold).isNotNull();
      assertThat(socialHousehold.location()).isEqualTo(new HouseholdLocation.Unit(entry.getKey()));
    }
    assertThat(units.units().get(new UnitId("demo-bde-1-bn-1")).equipment())
        .extracting(entry -> entry.type() + "=" + entry.amount())
        .containsExactlyInAnyOrder("步枪=450", "卡车=20");
    assertThat(units.units().get(new UnitId("demo-bde-2-bn-1")).speed()).isEqualTo(3);
    assertThat(units.units().get(new UnitId("demo-bde-2-bn-1")).mobilityPerMille()).isEqualTo(800);
  }

  private static JsonNode node(JsonNode armyView, String id) {
    for (JsonNode row : armyView.get("nodes")) {
      if (id.equals(row.get("id").asText())) {
        return row;
      }
    }
    throw new AssertionError("视图里没有节点 " + id);
  }

  private static List<String> commandTypes(JsonNode result) {
    List<String> types = new ArrayList<>();
    for (JsonNode command : result.get("commands")) {
      types.add(command.get("type").asText());
    }
    return types;
  }

  private static void assertPng(JsonNode body, String what) throws Exception {
    JsonNode images = body.get("images");
    assertThat(images).as("%s 必须返回真 PNG 图", what).isNotEmpty();
    String base64 = images.get(0).get("base64").asText();
    assertThat(base64).isNotBlank();
    BufferedImage image =
        ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    assertThat(image).as("%s 必须能解码", what).isNotNull();
    assertThat(image.getWidth()).isPositive();
    assertThat(image.getHeight()).isPositive();
    JsonNode render = body.get("result").get("render");
    assertThat(render.get("assetId").asText()).as("assetId 必须是内容寻址 sha256").matches("[0-9a-f]{64}");
    assertThat(render.get("width").asInt()).isEqualTo(image.getWidth());
    assertThat(render.get("height").asInt()).isEqualTo(image.getHeight());
    assertThat(render.get("byteSize").asInt()).isPositive();
    assertThat(render.get("summary").asText()).isNotBlank();
    int distinct = 0;
    java.util.Set<Integer> colors = new java.util.HashSet<>();
    int nonBackground = 0;
    for (int y = 0; y < image.getHeight(); y += 3) {
      for (int x = 0; x < image.getWidth(); x += 3) {
        int rgb = image.getRGB(x, y) & 0xFFFFFF;
        colors.add(rgb);
        if (rgb != 0xF6F6F0 && rgb != 0xF9F8F2 && rgb != 0xFFFFFF) {
          nonBackground++;
        }
      }
    }
    distinct = colors.size();
    assertThat(distinct).as("%s 必须有真实绘制（不是占位纯色）", what).isGreaterThan(4);
    assertThat(nonBackground).as("%s 必须有非背景像素", what).isPositive();
  }

  private static void assertUntouched(SimulationState after, SimulationState before) {
    assertThat(gameMap(after)).as("map 不变").isEqualTo(gameMap(before));
    assertThat(social(after)).as("social 不变").isEqualTo(social(before));
    assertThat(unitState(after)).as("unit 不变").isEqualTo(unitState(before));
    assertThat(gov(after).state()).as("gov 不变").isEqualTo(gov(before).state());
    assertThat(army(after).data()).as("army 不变").isEqualTo(army(before).data());
    assertThat(economy(after)).as("economy 不变").isEqualTo(economy(before));
    assertThat(actor(after)).as("actor 不变").isEqualTo(actor(before));
    assertThat(ga(after)).as("ga 不变").isEqualTo(ga(before));
  }

  private static SimulationState stateAt(Shell shell, long revision) {
    return shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(revision)));
  }

  private static long head(Shell shell) {
    return shell.coreSimos().head(MAIN).orElseThrow().value();
  }

  private static GameMap gameMap(SimulationState state) {
    return ((MapSnapshot) state.module("map").orElseThrow()).map();
  }

  private static UnitState unitState(SimulationState state) {
    return ((UnitSnapshot) state.module("unit").orElseThrow()).state();
  }

  private static SocialData social(SimulationState state) {
    return ((SocialSnapshot) state.module("social").orElseThrow()).data();
  }

  private static GovSnapshot gov(SimulationState state) {
    return (GovSnapshot) state.module("gov").orElseThrow();
  }

  private static ArmySnapshot army(SimulationState state) {
    return (ArmySnapshot) state.module("army").orElseThrow();
  }

  private static EconomyData economy(SimulationState state) {
    return ((EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  private static Object actor(SimulationState state) {
    return state.module("actor").orElseThrow();
  }

  private static Object ga(SimulationState state) {
    return state.module("ga").orElseThrow();
  }

  private static Optional<UnitId> parentOf(UnitState units, SimulationState state, String id) {
    return units.units().get(new UnitId(id)).parent().valueAt(state.meta().timestamp());
  }

  private static Army legion(SimulationState state, ArmyId id) {
    Army army = ((ArmySnapshot) state.module("army").orElseThrow()).data().armies().get(id);
    assertThat(army).as("军队 %s", id.value()).isNotNull();
    return army;
  }

  private static JsonNode postCommand(
      Shell shell, String type, String payloadJson, long expectedRevision) throws Exception {
    HttpResponse<String> response =
        post(
            shell,
            "/api/command",
            JSON.writeValueAsString(
                Map.of(
                    "type", type,
                    "payloadJson", payloadJson,
                    "branch", "main",
                    "expectedRevision", expectedRevision)));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static HttpResponse<String> postEditor(Shell shell, String kind, String body)
      throws Exception {
    return post(shell, "/api/editor/" + kind, body);
  }

  private static JsonNode getJson(Shell shell, String path) throws Exception {
    HttpResponse<String> response = get(shell, path);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private static HttpResponse<String> post(Shell shell, String path, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(uri(shell, path))
            .timeout(WAIT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> get(Shell shell, String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(uri(shell, path)).timeout(WAIT).GET().build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static void assertRejectedEditor(
      Shell shell, String kind, String body, int status, String reason) throws Exception {
    long before = head(shell);
    HttpResponse<String> response = postEditor(shell, kind, body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(response.body()).contains(reason);
    assertThat(head(shell)).as("拒绝不得推进 revision").isEqualTo(before);
  }

  private static String nestedNode(int depth, String prefix) {
    StringBuilder builder = new StringBuilder();
    for (int level = 1; level <= depth; level++) {
      builder
          .append("{\"id\":\"")
          .append(prefix)
          .append('-')
          .append(level)
          .append("\",\"name\":\"层")
          .append(level)
          .append("\",\"role\":\"BATTALION\"");
      if (level == 1) {
        builder.append(",\"position\":{\"q\":2,\"r\":0}");
      }
      if (level < depth) {
        builder.append(",\"children\":[");
      }
    }
    builder.append('}');
    for (int level = 1; level < depth; level++) {
      builder.append("]}");
    }
    return builder.toString();
  }

  private static URI uri(Shell shell, String path) {
    return URI.create("http://127.0.0.1:" + shell.boundGuiPort() + path);
  }
}
