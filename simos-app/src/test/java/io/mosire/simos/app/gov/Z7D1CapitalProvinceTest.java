package io.mosire.simos.app.gov;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.testing.AppLogCapture;
import io.mosire.simos.app.testing.GovZ6WorldFixture;
import io.mosire.simos.app.testing.GovZ6WorldFixture.World;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>Z7a / run7 D1：首都直辖省</b>（设计书 §2/§8-要点1、§9；z7a 台账 §5）。
 *
 * <ul>
 *   <li>tick0：{@code capital-province} 与 {@code small-world} 平级、互斥、并集 = 全图；首都城归首都省、镇归省；
 *   <li>辖区/税率：中央 = {capital-province:0‰}，省 = {small-world:100‰}；
 *   <li>逐日：{@code reason=jurisdiction_tax} 永不命中 {@code hh-gov-gov-central}，也永不命中任何 {@code (0,0)} 家户
 *       （含 {@code hh-gov-world-silver}）；省仍实收（{@code TAX_DAILY_END.unitsCharged==1,
 *       grainCollected>0}）；
 *   <li>负向：把中央 rate 改成 &gt;0 后，中央**只**抽 capital-province（{@code (0,0)}）内的民户，仍不抽自己的 国库户，也不抽
 *       small-world。
 * </ul>
 */
class Z7D1CapitalProvinceTest {

  private static final UnitId CENTRAL = UnitId.parse("gov-central");
  private static final UnitId PROVINCE = UnitId.parse("gov-province");
  private static final String CENTRAL_TREASURY = "hh-gov-gov-central";
  private static final String PROVINCE_TREASURY = "hh-gov-gov-province";
  private static final HexCoord CAPITAL_AT = SmallWorld.CAPITAL_AT;
  private static final RegionId SMALL_WORLD = new RegionId(SmallWorld.REGION_ID);
  private static final RegionId CAPITAL_PROVINCE = new RegionId(SmallWorld.CAPITAL_PROVINCE_ID);

  @TempDir Path tempDir;

  @Test
  void genesisRegionsAreDisjointAndCitiesAndJurisdictionsMatchDesign() {
    SimulationState state = GovZ6WorldFixture.smallWorldState("Map1");
    GameMap map = ((MapSnapshot) state.module("map").orElseThrow()).map();
    Region capital = map.regions().get(CAPITAL_PROVINCE);
    Region province = map.regions().get(SMALL_WORLD);
    assertThat(capital).as("capital-province 必须是省级 Region（与 small-world 平级）").isNotNull();
    assertThat(province).isNotNull();
    assertThat(capital.hexes()).as("首都省恰含座位格").containsExactly(CAPITAL_AT);
    assertThat(province.hexes()).as("省只留其余 18 格").hasSize(18).doesNotContain(CAPITAL_AT);
    assertThat(Collections.disjoint(capital.hexes(), province.hexes())).as("两个省级辖区互斥").isTrue();
    Set<HexCoord> union = new LinkedHashSet<>(capital.hexes());
    union.addAll(province.hexes());
    assertThat(union).as("并集 = 全图 19 格").isEqualTo(map.hexes().keySet());

    SocialData social = GovZ6WorldFixture.socialSlice(state);
    SocialCity capitalCity = social.cities().get(new CityId(SmallWorld.CAPITAL_ID));
    SocialCity town = social.cities().get(new CityId(SmallWorld.TOWN_ID));
    assertThat(capitalCity.region()).contains(CAPITAL_PROVINCE);
    assertThat(town.region()).contains(SMALL_WORLD);

    UnitState units = GovZ6WorldFixture.unitSlice(state);
    Unit central = units.units().get(CENTRAL);
    Unit govProvince = units.units().get(PROVINCE);
    assertThat(central.jurisdiction().orElseThrow().taxRatePerMilleByRegion())
        .as("中央直辖首都省、初始 rate=0")
        .containsExactly(Map.entry(CAPITAL_PROVINCE, 0L));
    assertThat(govProvince.jurisdiction().orElseThrow().taxRatePerMilleByRegion())
        .as("省辖区 = small-world:100‰")
        .containsExactly(Map.entry(SMALL_WORLD, 100L));
  }

  @Test
  void provinceTaxNeverHitsSeatOrCentralTreasuryAndStillCollects() {
    try (World world = GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve("d1-pos"), "d1-pos");
        AppLogCapture trace = AppLogCapture.logger(AppLog.TRACE_LOGGER_NAME, Level.TRACE);
        AppLogCapture info = AppLogCapture.appTime()) {
      world.advance(0L, 3L);

      assertThat(taxLinesTo(trace, CENTRAL_TREASURY)).as("中央 rate=0 ⇒ 小长跑内不得有税落在中央国库").isEmpty();
      List<String> provinceLines = taxLinesTo(trace, PROVINCE_TREASURY);
      assertThat(provinceLines).as("省仍在从 18 格实收（正对照）").isNotEmpty();
      EconomyData economy = GovZ6WorldFixture.economySlice(world.state());
      for (String line : provinceLines) {
        String household = fieldOf(line, "household");
        assertThat(hexOf(economy, household))
            .as("省税永不命中 (0,0) 家户: %s", line)
            .isNotEqualTo(CAPITAL_AT);
        assertThat(household)
            .as("省税永不命中中央/世界级国库户")
            .isNotEqualTo(CENTRAL_TREASURY)
            .isNotEqualTo("hh-gov-world-silver");
      }

      assertThat(info.messages())
          .as("逐日仍只有省在征（小长跑 3 天 × unitsCharged=1）")
          .filteredOn(
              line -> line.contains("event=TAX_DAILY_END") && line.contains("unitsCharged=1"))
          .hasSize(3);
      assertThat(grainCollectedOf(info)).as("省仍实收粮 > 0").isPositive();
    }
  }

  @Test
  void centralRateAboveZeroOnlyTaxesCapitalProvincePrivateHouseholds() throws Exception {
    try (World world = GovZ6WorldFixture.smallWorldGenesis(tempDir.resolve("d1-neg"), "d1-neg");
        AppLogCapture trace = AppLogCapture.logger(AppLog.TRACE_LOGGER_NAME, Level.TRACE);
        AppLogCapture info = AppLogCapture.appTime()) {
      world.advance(0L, 1L);
      long head = world.head();

      ToolResult rateChanged =
          executeRaw(
              world.shell().toolRegistry().find("unit.SetTaxRate").orElseThrow(),
              Map.of(
                  "payloadJson",
                  ToolSupport.json(
                      Map.of(
                          "unitId", CENTRAL.value(),
                          "regionId", CAPITAL_PROVINCE.value(),
                          "ratePerMille", 500L)),
                  "branch",
                  "main",
                  "expectedRevision",
                  head));
      assertThat(rateChanged.success()).as(rateChanged.message()).isTrue();

      trace.clear();
      info.clear();
      world.advance(1L, 2L);
      EconomyData economy = GovZ6WorldFixture.economySlice(world.state());

      List<String> centralTaxLines = taxLinesTo(trace, CENTRAL_TREASURY);
      assertThat(centralTaxLines).as("中央 rate>0 后确实开征").isNotEmpty();
      for (String line : centralTaxLines) {
        String household = fieldOf(line, "household");
        assertThat(hexOf(economy, household))
            .as("中央只抽 capital-province（(0,0)）内的民户: %s", line)
            .isEqualTo(CAPITAL_AT);
        assertThat(household).as("自征自返排除：不抽自己的国库户").isNotEqualTo(CENTRAL_TREASURY);
      }

      List<String> provinceLines = taxLinesTo(trace, PROVINCE_TREASURY);
      assertThat(provinceLines).as("省税仍在（负向对照）").isNotEmpty();
      assertThat(provinceLines)
          .as("中央抽 (0,0) 不影响省只抽 small-world")
          .allMatch(line -> !CAPITAL_AT.equals(hexOf(economy, fieldOf(line, "household"))));

      assertThat(info.hasInfo("TAX_DAILY_END", "unitsCharged=2"))
          .as("两级都实收 ⇒ unitsCharged=2")
          .isTrue();
    }
  }

  // ── 夹具 ──

  private static List<String> taxLinesTo(AppLogCapture trace, String treasuryHousehold) {
    return trace.messages().stream()
        .filter(line -> line.contains("event=HOUSEHOLD_STOCK_DEDUCTED"))
        .filter(line -> line.contains("reason=jurisdiction_tax"))
        .filter(line -> line.contains("to=" + treasuryHousehold))
        .toList();
  }

  private static HexCoord hexOf(EconomyData economy, String householdId) {
    CohortKey key =
        economy.classes().get(io.mosire.simos.social.api.id.HouseholdId.parse(householdId)).view();
    return key.hex();
  }

  private static String fieldOf(String line, String field) {
    Matcher matcher = Pattern.compile("\\b" + field + "=(\\S+)").matcher(line);
    if (!matcher.find()) {
      throw new AssertionError("日志缺字段 " + field + ": " + line);
    }
    return matcher.group(1);
  }

  private static long grainCollectedOf(AppLogCapture info) {
    for (String line : info.messages()) {
      if (line.contains("event=TAX_DAILY_END")) {
        return Long.parseLong(fieldOf(line, "grainCollected"));
      }
    }
    throw new AssertionError("没有 TAX_DAILY_END 行: " + info.messages());
  }

  private static ToolResult executeRaw(AgentTool tool, Map<String, Object> args) {
    return tool.execute(
        new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args)
            .withResources(ResourceAuthorizer.of(AgentPermissionSet.system(), tool.resources())));
  }
}
