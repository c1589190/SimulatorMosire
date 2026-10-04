package io.mosire.simos.app.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.testing.UnitHouseholdWorldFixture;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.lookup.PopulationLookup;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.social.household.SocialLookupAdapter;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>S3a 正式验收：unit/social 一致性组合工具</b>（S3a spec §3.3/§4.3/§7；任务书 B5/B6）。
 *
 * <p>判据：{@code simos.unit.assignHousehold} 同一批命令让 Social 的 {@code location=UNIT(unitId)} 与
 * {@code Unit.households} 同时变化（一条 revision）；{@code detachHousehold} 反向；preview 零写；未知 unit/household
 * 或一侧失败 ⇒ 整批不落 revision、无半更新；GOV 多群体示例的实时人口 = 两户成员数之和。
 */
class UnitHouseholdGmToolTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HouseholdId HH_A = HouseholdId.parse("hh-a");
  private static final HouseholdId HH_HINDU = HouseholdId.parse("hh-hindu-001");
  private static final HouseholdId HH_HAN = HouseholdId.parse("hh-han-001");
  private static final PeopleLotId LOT_A = PeopleLotId.parse("lot-a");

  @TempDir Path tempDir;

  private CoreSimos core;
  private QueryService query;
  private UnitAssignHouseholdTool assign;
  private UnitDetachHouseholdTool detach;

  @BeforeEach
  void setUp() {
    core = UnitHouseholdWorldFixture.core(tempDir, singleGovState(), oneHouseholdSocial());
    query = UnitHouseholdWorldFixture.query(core);
    assign = new UnitAssignHouseholdTool(core, query, "agent:s3a-test");
    detach = new UnitDetachHouseholdTool(core, query, "agent:s3a-test");
  }

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  // ── B5：assign / detach 两侧一致、一条 revision ─────────────────────────

  @Test
  void assignWritesSocialLocationAndUnitHouseholdsInOneRevision() {
    long before = UnitHouseholdWorldFixture.head(core);

    ToolResult result =
        assign(
            Map.of(
                "householdId", HH_A.value(),
                "unitId", gov().value(),
                "reason", "编入",
                "expectedRevision", before,
                "preview", false));

    assertThat(result.success()).as(result.message()).isTrue();
    long after = UnitHouseholdWorldFixture.head(core);
    assertThat(after).as("assign = 同批两条命令 ⇒ 恰好一条 revision").isEqualTo(before + 1);

    SimulationState at = UnitHouseholdWorldFixture.replay(core, after);
    assertThat(UnitHouseholdWorldFixture.socialSlice(at).requireHousehold(HH_A).location())
        .as("Social 侧 location 变 UNIT(unitId)")
        .isEqualTo(new HouseholdLocation.Unit(gov().value()));
    assertThat(UnitHouseholdWorldFixture.unit(at, gov()).households())
        .as("Unit 侧 households 含该家户")
        .containsExactly(HH_A);
    assertThat(UnitHouseholdWorldFixture.socialSlice(at).unitPopulation(gov().value()))
        .as("实时人口 = 家户成员数")
        .isEqualTo(7L);
  }

  @Test
  void detachMovesBackToHexAndRemovesFromUnitInOneRevision() {
    long headAfterAssign = assignOk(HH_A, gov());

    long before = UnitHouseholdWorldFixture.head(core);
    ToolResult result =
        detach(
            Map.of(
                "householdId", HH_A.value(),
                "unitId", gov().value(),
                "hex", Map.of("q", 1, "r", 2),
                "reason", "移出",
                "expectedRevision", headAfterAssign,
                "preview", false));

    assertThat(result.success()).as(result.message()).isTrue();
    long after = UnitHouseholdWorldFixture.head(core);
    assertThat(after).as("detach = 同批两条命令 ⇒ 恰好一条 revision").isEqualTo(before + 1);

    SimulationState at = UnitHouseholdWorldFixture.replay(core, after);
    assertThat(UnitHouseholdWorldFixture.socialSlice(at).requireHousehold(HH_A).location())
        .as("location 变回 HEX(1,2)")
        .isEqualTo(new HouseholdLocation.Hex(UnitHouseholdWorldFixture.H12));
    assertThat(UnitHouseholdWorldFixture.unit(at, gov()).households())
        .as("Unit.households 移除该家户")
        .isEmpty();
    assertThat(UnitHouseholdWorldFixture.socialSlice(at).unitPopulation(gov().value())).isZero();
  }

  @Test
  void previewWritesNoRevisionAndNoHalfUpdate() throws Exception {
    long before = UnitHouseholdWorldFixture.head(core);

    ToolResult result = assign(Map.of("householdId", HH_A.value(), "unitId", gov().value(), "reason", "预览"));

    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode view = JSON.readTree(result.message());
    assertThat(view.path("preview").asBoolean()).as("缺省 preview=true").isTrue();
    assertThat(view.path("submitted").asBoolean()).isFalse();
    assertThat(UnitHouseholdWorldFixture.head(core)).as("preview 一个 revision 都不写").isEqualTo(before);

    SimulationState at = UnitHouseholdWorldFixture.replay(core, before);
    assertThat(UnitHouseholdWorldFixture.socialSlice(at).requireHousehold(HH_A).location())
        .as("preview 不写 Social 侧")
        .isEqualTo(new HouseholdLocation.Hex(UnitHouseholdWorldFixture.H11));
    assertThat(UnitHouseholdWorldFixture.unit(at, gov()).households())
        .as("preview 不写 Unit 侧")
        .isEmpty();
  }

  @Test
  void unknownUnitOrHouseholdAndFailingSideLeaveNoRevisionAndNoHalfUpdate() {
    long head = UnitHouseholdWorldFixture.head(core);

    ToolResult unknownHousehold =
        assign(
            Map.of(
                "householdId", "hh-missing",
                "unitId", gov().value(),
                "reason", "x",
                "expectedRevision", head,
                "preview", false));
    assertThat(unknownHousehold.success()).isFalse();
    assertThat(UnitHouseholdWorldFixture.head(core)).as("未知 household 零 revision").isEqualTo(head);

    ToolResult unknownUnit =
        assign(
            Map.of(
                "householdId", HH_A.value(),
                "unitId", "u-missing",
                "reason", "x",
                "expectedRevision", head,
                "preview", false));
    assertThat(unknownUnit.success()).isFalse();
    assertThat(UnitHouseholdWorldFixture.head(core)).as("未知 unit 零 revision").isEqualTo(head);

    // 失败一侧：目标 unit 的 households 若含 "u-other"，会撞上现存的 unit id "u-other" ⇒ UnitState 构造期拒；
    //   同批的 social.SetHouseholdLocation 本可单独成功 ⇒ 这正是"整批回滚、不得半更新"的样本。
    ToolResult failingSide =
        assign(
            Map.of(
                "householdId", UnitHouseholdWorldFixture.OTHER.value(),
                "unitId", gov().value(),
                "reason", "x",
                "expectedRevision", head,
                "preview", false));
    assertThat(failingSide.success()).isFalse();
    assertThat(failingSide.message())
        .as("拒因必须指名撞名（不是被吞成空成功）")
        .contains("撞名");
    assertThat(UnitHouseholdWorldFixture.head(core)).as("失败一侧整批零 revision").isEqualTo(head);

    SimulationState at = UnitHouseholdWorldFixture.replay(core, head);
    assertThat(UnitHouseholdWorldFixture.socialSlice(at).requireHousehold(HH_A).location())
        .as("HH_A 没有任何半更新")
        .isEqualTo(new HouseholdLocation.Hex(UnitHouseholdWorldFixture.H11));
    assertThat(
            UnitHouseholdWorldFixture.socialSlice(at)
                .requireHousehold(HouseholdId.parse(UnitHouseholdWorldFixture.OTHER.value()))
                .location())
        .as("失败批的第一条 social 命令不得落盘")
        .isEqualTo(new HouseholdLocation.Hex(UnitHouseholdWorldFixture.H11));
    assertThat(UnitHouseholdWorldFixture.unit(at, gov()).households()).isEmpty();
  }

  // ── B6：GOV 多群体示例 ────────────────────────────────────────────────

  @Test
  void govUnitHoldsTwoHouseholdsAndPopulationIsTheSum() {
    core.close();
    core =
        UnitHouseholdWorldFixture.core(
            tempDir.resolve("gov-two"), twoHouseholdGovState(), twoHouseholdSocial());
    SocialLookupAdapter lookup =
        new SocialLookupAdapter(
            UnitHouseholdWorldFixture.socialSlice(UnitHouseholdWorldFixture.replay(core, 1)),
            0L,
            CalendarClock.julianDefault());

    assertThat(lookup.unitPopulation(gov().value()))
        .as("印度教户 5 + 华人户 3 = 8（家户成员数现算，不落第二本 headcount）")
        .isEqualTo(8L);
    assertThat(lookup.householdPopulation(HH_HINDU)).isEqualTo(5L);
    assertThat(lookup.householdPopulation(HH_HAN)).isEqualTo(3L);

    Unit gov = UnitHouseholdWorldFixture.unit(UnitHouseholdWorldFixture.replay(core, 1), gov());
    GovFormation formation = (GovFormation) gov.module().orElseThrow();
    assertThat(gov.households()).containsExactly(HH_HINDU, HH_HAN);
    assertThat(formation.households()).as("GovFormation 下辖家户读口一致").containsExactly(HH_HINDU, HH_HAN);

    Map<String, Object> view =
        ApiViews.unit(
            gov,
            UnitHouseholdWorldFixture.unitSlice(UnitHouseholdWorldFixture.replay(core, 1)),
            UnitHouseholdWorldFixture.T0,
            UnitHouseholdWorldFixture.map(),
            SdState.empty(),
            CalendarService.defaults(),
            lookup,
            lookup);
    assertThat(view.get("population")).as("读口 population 同样是两户之和").isEqualTo(8L);
    @SuppressWarnings("unchecked")
    List<String> unitHouseholds =
        ((List<Map<String, Object>>) view.get("households")).stream()
            .map(row -> (String) row.get("id"))
            .toList();
    assertThat(unitHouseholds).containsExactly(HH_HINDU.value(), HH_HAN.value());
    @SuppressWarnings("unchecked")
    Map<String, Object> module = (Map<String, Object>) view.get("module");
    assertThat((List<String>) module.get("households")).containsExactly(HH_HINDU.value(), HH_HAN.value());
  }

  // ── 装置 ──────────────────────────────────────────────────────────────

  private static UnitId gov() {
    return UnitHouseholdWorldFixture.GOV;
  }

  private static UnitState singleGovState() {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(gov(), UnitHouseholdWorldFixture.govUnit(List.of(), List.of(), Optional.empty()));
    units.put(
        UnitHouseholdWorldFixture.OTHER, UnitHouseholdWorldFixture.plainUnit(UnitHouseholdWorldFixture.OTHER));
    return new UnitState(units);
  }

  private static SocialData oneHouseholdSocial() {
    SocialData social = createHousehold(SocialData.empty(), HH_A, new HouseholdLocation.Hex(UnitHouseholdWorldFixture.H11));
    social = HouseholdBook.addMembers(social, HH_A, LOT_A, Sex.MALE, 7L, 0L, 0L, "seed");
    // 「失败一侧」样本：household id 与现存 unit id 撞名（social 不管 unit，故这里合法）。
    return createHousehold(
        social,
        HouseholdId.parse(UnitHouseholdWorldFixture.OTHER.value()),
        new HouseholdLocation.Hex(UnitHouseholdWorldFixture.H11));
  }

  private static UnitState twoHouseholdGovState() {
    List<HouseholdId> both = List.of(HH_HINDU, HH_HAN);
    return new UnitState(
        Map.of(gov(), UnitHouseholdWorldFixture.govUnit(both, both, Optional.empty())));
  }

  private static SocialData twoHouseholdSocial() {
    SocialData social =
        createHousehold(
            SocialData.empty(), HH_HINDU, new HouseholdLocation.Unit(gov().value()));
    social = HouseholdBook.addMembers(social, HH_HINDU, PeopleLotId.parse("lot-hindu"), Sex.MALE, 5L, 0L, 0L, "seed");
    social =
        createHousehold(social, HH_HAN, new HouseholdLocation.Unit(gov().value()));
    return HouseholdBook.addMembers(social, HH_HAN, PeopleLotId.parse("lot-han"), Sex.FEMALE, 3L, 0L, 0L, "seed");
  }

  private static SocialData createHousehold(
      SocialData base, HouseholdId id, HouseholdLocation location) {
    return HouseholdBook.create(
        base,
        id,
        location,
        new HouseholdProfile(id.value(), null, Map.of()),
        new HouseholdVitalRates(List.of()));
  }

  private long assignOk(HouseholdId household, UnitId unit) {
    long head = UnitHouseholdWorldFixture.head(core);
    ToolResult result =
        assign(
            Map.of(
                "householdId", household.value(),
                "unitId", unit.value(),
                "reason", "编入",
                "expectedRevision", head,
                "preview", false));
    assertThat(result.success()).as(result.message()).isTrue();
    return UnitHouseholdWorldFixture.head(core);
  }

  private ToolResult assign(Map<String, Object> args) {
    return assign.execute(context(assign, args));
  }

  private ToolResult detach(Map<String, Object> args) {
    return detach.execute(context(detach, args));
  }

  private static ToolContext context(AgentTool tool, Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
        .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources()));
  }
}
