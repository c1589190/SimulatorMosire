package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.testing.GmActAsWorldFixture;
import io.mosire.simos.app.testing.GovE2eWorld;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.write.GovWorldBootstrap;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.tools.write.UnitSetJurisdictionTool;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>P1（2026-10-15 测试面）：{@code ProposalCatalog} 每一条成员的 {@code planOnly} 行为覆盖</b> —— 在 {@code
 * simos-gov/src/test} 里 {@code planOnly} 是 <b>0 覆盖</b>，这正是那个"双写"缺陷长期存活的直接原因。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>{@code DecisionEffectExecutor} 的规划阶段对每条 call 注入 {@code preview=false + planOnly=true}，期望工具
 * <b>只推导、只交批、一个字节都不写</b>。任何一条 catalog 成员忽略 {@code planOnly} ⇒ 规划期就真落盘（一条 revision 已经产生）， 执行器还会因拿不到
 * {@code plannedCommands} 报错、GM 重试 ⇒ <b>世界被写两次且 outcome 从不回写</b>。
 *
 * <h2>判据（每条成员都过一遍，逐条报账）</h2>
 *
 * <ol>
 *   <li>{@code planOnly=true} 调用<b>成功</b>；
 *   <li>返回视图里<b>有 {@code plannedCommands}</b>（执行器就是靠它组批的；缺它 = 执行器报 {@code TOOL_ERROR}）；
 *   <li><b>零落盘</b>：head 与 {@code revisions} 行数<b>都不动</b>（★ 直接读态，不看工具自报的 {@code submitted}）。
 * </ol>
 *
 * <p>★ 扫描面自证：遍历的就是 {@code catalog.toolNames()}（不手抄清单），并断言条数 == 16、逐条都跑到 ⇒ 不可能"空 == 空"。
 * 结构性的那一半（下一条进清单的工具漏 {@code planOnly} 会当场红）在 {@link ProposalCatalogPlanOnlyScanTest}。
 *
 * <p>★ 世界 = {@link GmActAsWorldFixture} 的真壳小世界（{@code gov-central} 有编制/家户/辖区/国库），调用形态 = 执行器的
 * <b>系统上下文</b>（{@code DecisionEffectExecutor.planCall} 同形）。
 */
class ProposalCatalogPlanOnlyTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 清单条数实测值（2026-10-15：7 条原有 + 3b 的 9 条 = 16）。 */
  private static final int CATALOG_SIZE = 16;

  private static final String GOV_CENTRAL = GovWorldBootstrap.CENTRAL_GOV_ID;
  private static final String REGION_CAPITAL = "capital-province";

  @TempDir Path tempDir;

  private GmActAsWorldFixture.World world;
  private ProposalCatalog catalog;
  private CoreSimos core;
  private String externalHousehold;

  @BeforeEach
  void startWorld() {
    world = GmActAsWorldFixture.start(tempDir.resolve("world"), new GmActAsWorldFixture.Fake());
    core = world.shell().coreSimos();
    catalog =
        new ProposalCatalog(
            core,
            CalendarService.defaults(),
            world.shell().queryService(),
            "test:planonly",
            world.mapId());
    externalHousehold = pickExternal(world.state());
    seedJurisdictionCaps();
    System.out.println(
        "[P1-SETUP] externalHousehold=" + externalHousehold + " head=" + world.head());
  }

  @AfterEach
  void stopWorld() {
    if (world != null) {
      world.close();
      world = null;
    }
  }

  @Test
  void everyCatalogMemberPlansWithoutWritingAnything() {
    List<String> members = catalog.toolNames();
    System.out.println("[P1] catalog(" + members.size() + ")=" + members);
    assertThat(members)
        .as("★ 扫描面自证：清单条数必须是实测值 %d（清单被清空 ⇒ 下面的循环空转 = 判据归零）", CATALOG_SIZE)
        .hasSize(CATALOG_SIZE);

    Map<String, String> failures = new LinkedHashMap<>();
    Map<String, Integer> plannedCounts = new LinkedHashMap<>();
    long rowsBeforeAll = world.shell().coreSimos().revisions(GmActAsWorldFixture.MAIN).size();
    int scanned = 0;
    for (String name : members) {
      long headBefore = world.head();
      long rowsBefore = world.shell().coreSimos().revisions(GmActAsWorldFixture.MAIN).size();
      Map<String, Object> args = new LinkedHashMap<>(innerArgs(name));
      // ★ 逐字照 DecisionEffectExecutor.planCall 的注入形态。
      args.put("preview", false);
      args.put("planOnly", true);
      args.put("branch", GmActAsWorldFixture.MAIN.value());
      args.put("revision", headBefore);
      args.put("expectedRevision", headBefore);

      ToolResult result = GovE2eWorld.exec(catalog.requireTool(name), args);
      long headDelta = world.head() - headBefore;
      long rowDelta =
          world.shell().coreSimos().revisions(GmActAsWorldFixture.MAIN).size() - rowsBefore;
      int planned = -1;
      boolean hasField = false;
      if (result.message() != null && result.message().trim().startsWith("{")) {
        try {
          JsonNode view = JSON.readTree(result.message());
          hasField = view.has("plannedCommands");
          if (hasField) {
            planned = view.path("plannedCommands").size();
          }
        } catch (Exception e) {
          hasField = false;
        }
      }
      plannedCounts.put(name, planned);
      System.out.println(
          "[P1] tool="
              + name
              + " code="
              + result.code()
              + " success="
              + result.success()
              + " plannedCommands="
              + planned
              + " headDelta="
              + headDelta
              + " revisionRowDelta="
              + rowDelta);
      if (!result.success()) {
        failures.put(
            name, "调用失败 code=" + result.code() + " message=" + truncate(result.message(), 300));
      } else if (!hasField) {
        failures.put(
            name,
            "返回里没有 plannedCommands（执行器会报 TOOL_ERROR 并让 GM 重试）: " + truncate(result.message(), 200));
      } else if (headDelta != 0L || rowDelta != 0L) {
        failures.put(name, "planOnly 真落盘！headDelta=" + headDelta + " revisionRowDelta=" + rowDelta);
      } else {
        scanned++;
      }
    }
    System.out.println("[P1] plannedCounts=" + plannedCounts);
    assertThat(failures).as("★★ P1：catalog 每条成员的 planOnly 都必须「有 plannedCommands 且零落盘」").isEmpty();
    assertThat(scanned)
        .as("★ 扫描面自证：必须逐条跑完全部 %d 条（少一条 = 判据有洞）", CATALOG_SIZE)
        .isEqualTo(CATALOG_SIZE);
    assertThat(world.shell().coreSimos().revisions(GmActAsWorldFixture.MAIN).size())
        .as("★★ 16 条 planOnly 之后整世界的 revision 行数必须一条都没多")
        .isEqualTo(rowsBeforeAll);
  }

  // ────────────────────────────── 逐条成员的真调参数 ──────────────────────────────

  /**
   * 每条 catalog 成员的合法参数（世界里的事实取自 SmallWorld 创世：{@code gov-central} 的编制/家户/辖区/国库都在）。
   *
   * <p>★ 参数只求"能走到规划路径"；工具内部的领域判据照旧生效（不是绕过它们）。
   */
  private Map<String, Object> innerArgs(String tool) {
    Map<String, Object> args = new LinkedHashMap<>();
    switch (tool) {
      case "simos.unit.raiseUnit" -> {
        args.put("newUnitId", "planonly-unit-1");
        args.put("name", "planOnly 新单位");
        args.put("regionId", REGION_CAPITAL);
        args.put("at", Map.of("q", 0, "r", 0));
        args.put("manpower", 1L);
        args.put("speed", 1L);
        args.put("mobilityPerMille", 1000L);
        args.put("reason", "P1 planOnly 覆盖：组军");
      }
      case "simos.gov.recruit" -> {
        args.put("unitId", GOV_CENTRAL);
        args.put("role", "POST");
        args.put("count", 1L);
        args.put("reason", "P1 planOnly 覆盖：招募");
      }
      case "simos.gov.retireStaff" -> {
        args.put("unitId", GOV_CENTRAL);
        args.put("role", "POST");
        args.put("count", 1L);
        // ★ 人不能凭空消失：退休必须给目标家户（toHouseholdId 精确指定，或 reinsertQ/reinsertR 在该格取第一户）。
        args.put("toHouseholdId", externalHousehold);
        args.put("reason", "P1 planOnly 覆盖：退休");
      }
      case "simos.social.household.members" -> {
        args.put("action", "add");
        args.put("householdId", externalHousehold);
        args.put("sex", "MALE");
        args.put("count", 1L);
        args.put("reason", "P1 planOnly 覆盖：家户加人");
      }
      case "simos.unit.levyRegion" -> {
        args.put("unitId", GOV_CENTRAL);
        args.put("regionId", REGION_CAPITAL);
        args.put("grain", 1L);
        args.put("reason", "P1 planOnly 覆盖：辖区抽取");
      }
      case "simos.gov.selectExaminees" -> {
        args.put("unitId", GOV_CENTRAL);
        args.put("count", 1L);
        args.put("reason", "P1 planOnly 覆盖：科举选人");
      }
      case "simos.gov.dispatchTeam" -> {
        args.put("unitId", GOV_CENTRAL);
        args.put("count", 1L);
        args.put("role", "POST");
        args.put("armed", false);
        args.put("reason", "P1 planOnly 覆盖：派出调查组");
      }
      case "simos.gov.setEstablishment" -> {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("unitId", GOV_CENTRAL);
        payload.put("securityPlannedLaborMilli", 3000L);
        payload.put("paperworkPlannedLaborMilli", 3000L);
        args.put("payloadJson", json(payload));
        args.put("reason", "P1 planOnly 覆盖：编制计划");
      }
      case "simos.gov.setBudgetPolicy" -> {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("unitId", GOV_CENTRAL);
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("category", "ADMIN_SALARY");
        category.put("minPerCycle", 0L);
        category.put("capPerCycle", 5_000_000L);
        payload.put("orderedCategories", List.of(category));
        payload.put("mode", "PATCH");
        args.put("payloadJson", json(payload));
        args.put("reason", "P1 planOnly 覆盖：预算政策");
      }
      case "simos.gov.assignPosts" -> {
        args.put("govUnitId", GOV_CENTRAL);
        args.put("householdId", "hh-unit:" + GOV_CENTRAL);
        args.put("role", "POST");
        args.put("reason", "P1 planOnly 覆盖：自家户挂岗");
      }
      case "simos.gov.expandHousehold" -> {
        args.put("govUnitId", GOV_CENTRAL);
        args.put("role", "SCRIBE");
        args.put("count", 1L);
        args.put("reason", "P1 planOnly 覆盖：扩充官吏户");
      }
      case "simos.gov.openPostsToMarket" -> {
        args.put("govUnitId", GOV_CENTRAL);
        args.put("householdId", externalHousehold);
        args.put("role", "POST");
        args.put("reason", "P1 planOnly 覆盖：外部岗位");
      }
      case "simos.gov.defineCurrency" -> {
        args.put("govUnitId", GOV_CENTRAL);
        args.put("currencyId", "planonly-coin");
        args.put("scale", 3L);
        args.put("displayName", "planOnly 币");
        args.put("reason", "P1 planOnly 覆盖：定义币种");
      }
      case "simos.gov.renameCurrency" -> {
        args.put("govUnitId", GOV_CENTRAL);
        args.put("currencyId", "copper");
        args.put("displayName", "铜（planOnly 改名）");
        args.put("reason", "P1 planOnly 覆盖：改币种显示名");
      }
      case "simos.gov.issueMoney" -> {
        args.put("govUnitId", GOV_CENTRAL);
        args.put("currency", "copper");
        args.put("amountMilli", 1000L);
        args.put("reason", "P1 planOnly 覆盖：发钞");
      }
      case "simos.gov.setFxRate" -> {
        args.put("govUnitId", GOV_CENTRAL);
        args.put("base", "copper");
        args.put("quote", "silver");
        args.put("buyPerMille", 1000L);
        args.put("sellPerMille", 1000L);
        args.put("reason", "P1 planOnly 覆盖：定官方汇率");
      }
      default -> throw new IllegalArgumentException("P1 覆盖表缺这条 catalog 成员的参数: " + tool);
    }
    return args;
  }

  /** 中央辖区（{@code capital-province}，(0,0)）内、有社会人口、且不属于 GOV 的外部家户。 */
  private static String pickExternal(SimulationState state) {
    SocialData social = ToolSupport.socialData(state);
    Unit gov = ToolSupport.unitState(state).units().get(new UnitId(GOV_CENTRAL));
    List<HouseholdId> owned = gov == null ? List.of() : gov.households();
    for (Map.Entry<HouseholdId, io.mosire.simos.social.household.Household> entry :
        social.households().entrySet()) {
      HouseholdId id = entry.getKey();
      if (owned.contains(id)
          || id.value().startsWith("hh-unit:")
          || GovernmentHouseholds.isGovernment(id)) {
        continue;
      }
      if (!(entry.getValue().location() instanceof HouseholdLocation.Hex hex)) {
        continue;
      }
      if (hex.hex().q() != 0 || hex.hex().r() != 0) {
        continue;
      }
      if (social.householdPopulation(id) <= 0L) {
        continue;
      }
      return id.value();
    }
    throw new IllegalStateException("夹具世界里找不到中央辖区内的外部家户（P1 覆盖表的前提）");
  }

  /** 真写一条命令（★ 只在夹具装配阶段用：给出人源家户补成年男丁、开辖区抽取额度）。 */
  private long submitCommand(String type, String payloadJson, long expectedRevision) {
    String batchId = UUID.randomUUID().toString();
    CommandEnvelope envelope =
        new CommandEnvelope(
            UUID.randomUUID().toString(),
            batchId,
            "test:planonly-seed",
            GmActAsWorldFixture.MAIN,
            new RevisionId(expectedRevision),
            type,
            payloadJson);
    BatchResult result = core.submitBatch(List.of(envelope));
    if (result instanceof BatchResult.Committed committed) {
      return committed.ref().revision().value();
    }
    throw new IllegalStateException("夹具装配命令未落盘: " + result);
  }

  /**
   * 夹具装配：给中央 GOV 的辖区开三个 {@code levy*CapPerCommand} 额度。
   *
   * <p>★ 为什么必须：SmallWorld 创世把三个额度都留成 {@code 0}（"0 = 该类无额度、拒"），于是 {@code simos.unit.levyRegion}
   * 的规划路径在领域判据处就被拒 —— 那是<b>世界事实</b>不是缺陷。开额度走真命令（{@code unit.SetJurisdiction}）， 与 {@code
   * PlanOnlyProbe} 的 {@code [E0]} 同一形制、同一载荷形状。
   */
  private void seedJurisdictionCaps() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", GOV_CENTRAL);
    payload.put("regions", List.of(REGION_CAPITAL));
    payload.put("levyGrainCapPerCommand", 1000L);
    payload.put("levyMoneyCapPerCommand", 1000L);
    payload.put("levyManpowerCapPerCommand", 1000L);
    long next = submitCommand(UnitSetJurisdictionTool.NAME, json(payload), world.head());
    System.out.println("[P1-SETUP] jurisdiction caps on " + GOV_CENTRAL + " head -> " + next);
  }

  private static String json(Map<String, Object> value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON 序列化失败", e);
    }
  }

  private static String truncate(String text, int max) {
    if (text == null) {
      return "(null)";
    }
    return text.substring(0, Math.min(max, text.length()));
  }
}
