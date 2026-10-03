package io.mosire.simos.economy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>P0 黄金读数 fixture 的可执行验收</b>：把 {@code fixtures/probe-golden-readings.json} 当成
 * <b>只读规格</b>读一遍——结构齐全 + 关键读数逐值等于四份探针报告（P0 facet 的机器可读交付）。
 *
 * <p>★★ <b>本类不重跑探针</b>（P9 纪律与探针测试只读约定）：它只断言 fixture 自身；fixture 的每一个数字都从 {@code
 * docs/superpowers/reports/2026-10-06-probe-golden-readings.md} 的手工转抄中获得出处。判别力：任何 expected
 * 被悄悄改数（例如把 {@code totalDebtDeleted} 从 10881973 改成别的值）⇒ 本类当场红。
 */
class ProbeGoldenReadingsFixtureTest {

  private static final String FIXTURE = "/fixtures/probe-golden-readings.json";
  private static final ObjectMapper JSON = SimosObjectMapper.create();

  @Test
  void fixtureHasTheFrozenShapeAndEveryScenarioHasAllSixFields() throws IOException {
    JsonNode root = readFixture();

    assertThat(root.path("version").asInt()).as("P0 fixture 版本号").isEqualTo(1);
    JsonNode sourceReports = root.path("sourceReports");
    assertThat(sourceReports.isArray()).as("sourceReports 必须是数组").isTrue();
    assertThat(sourceReports).as("覆盖四份来源报告").hasSize(4);
    for (JsonNode report : sourceReports) {
      assertThat(report.asText()).as("sourceReports 元素必须是非空白路径").isNotBlank();
    }

    JsonNode scenarios = root.path("scenarios");
    assertThat(scenarios.isArray()).as("scenarios 必须是数组").isTrue();
    assertThat(scenarios).as("P0 冻结 24 个 scenario").hasSize(24);

    Set<String> ids = new HashSet<>();
    for (JsonNode scenario : scenarios) {
      String id = scenario.path("id").asText();
      assertThat(id).as("每个 scenario 必须有非空 id").isNotBlank();
      assertThat(ids.add(id)).as("scenario id 不得重复: %s", id).isTrue();
      assertThat(scenario.path("probeTest").asText()).as("%s.probeTest", id).contains("#");
      assertThat(scenario.path("inputs").isObject()).as("%s.inputs 必须是对象", id).isTrue();
      JsonNode expected = scenario.path("expected");
      assertThat(expected.isObject()).as("%s.expected 必须是对象", id).isTrue();
      assertThat(expected.size()).as("%s.expected 不得为空", id).isPositive();
      assertEveryExpectedLeafIsAnInteger(id, expected);
      JsonNode tolerance = scenario.path("tolerance");
      assertThat(tolerance.path("default").path("mode").asText())
          .as("%s.tolerance.default.mode", id)
          .isEqualTo("exact");
      assertThat(tolerance.path("default").path("absolute").asLong())
          .as("%s.tolerance.default.absolute", id)
          .isZero();
      assertThat(tolerance.path("default").path("relativePerMille").asLong())
          .as("%s.tolerance.default.relativePerMille", id)
          .isZero();
      assertThat(tolerance.path("unit").asText()).as("%s.tolerance.unit", id).isNotBlank();
      assertThat(scenario.path("notes").asText()).as("%s.notes", id).isNotBlank();
    }
  }

  @Test
  void keyGoldenReadingsMatchTheFrozenProbeReports() throws IOException {
    JsonNode root = readFixture();

    JsonNode famine = scenario(root, "long_run_famine_3000");
    assertExpected(famine, "round1Population", 510L);
    assertExpected(famine, "round3000Population", 29L);
    assertExpected(famine, "round3000DeathsThisRound", 0L);
    assertExpected(famine, "totalDeaths", 481L);
    assertExpected(famine, "totalDebtDeleted", 10_881_973L);
    assertExpected(famine, "totalRepaid", 100L);
    assertExpected(famine, "round3000Debt", 3_116_378_227L);
    assertExpected(famine, "round3000GrainRef", 10_000L);

    JsonNode circle = scenario(root, "circular_flow_credit_3000");
    assertExpected(circle, "round3000Population", 510L);
    assertExpected(circle, "round3000Debt", 200_000L);
    assertExpected(circle, "round3000Money", 200_000L);
    assertExpected(circle, "round3000Issuance", 200_000L);
    assertExpected(circle, "round3000Repayment", 200_000L);
    assertExpected(circle, "round3000GrainRef", 100L);
    assertExpected(circle, "totalDeaths", 0L);

    JsonNode city = scenario(root, "seven_hex_city_3650");
    assertExpected(city, "pop", 1_455L);
    assertExpected(city, "deaths", 0L);
    assertExpected(city, "migratedToCity", 175L);
    assertExpected(city, "cityLaborer", 175L);
    assertExpected(city, "cityCapacityInitial", 10L);
    assertExpected(city, "cityCapacityFinal", 370L);
    assertExpected(city, "cityUsedCapacity", 7_549L);
    assertExpected(city, "cityExpansions", 18L);
    assertExpected(city, "cityBuiltAreaPerMille", 360L);
    assertExpected(city, "arableC", 2_999L);
    assertExpected(city, "arableR0", 3_019L);
    assertExpected(city, "farmOutput", 1_370L);
    assertExpected(city, "rent", 410L);
    assertExpected(city, "cityPoolMoved", 1_965L);
    assertExpected(city, "cityPoolCapacity", 20_750L);
    // ★ P0 勘误（2026-10-06）：当前 HEAD 的 SevenHexCityMerchantProbeTest 实测打印
    //   cityPoolCapacity=20750、outerBuyerDebt=11341500；fixture/报告原抄 20780/11341300 已按可复现读数更正。
    assertExpected(city, "outerBuyerDebt", 11_341_500L);

    JsonNode transport = scenario(root, "transport_full_capacity");
    assertExpected(transport, "unitsMoved", 30L);
    assertExpected(transport, "feeEarned", 450L);
    assertExpected(transport, "carrierMoney", 450L);
    assertExpected(transport, "transportEscrow", 0L);

    JsonNode shortage = scenario(root, "transport_capacity_shortage");
    assertExpected(shortage, "unitsMoved", 10L);
    assertExpected(shortage, "feeEarned", 150L);
    assertExpected(shortage, "unservedQuantity", 30L);
    assertExpected(shortage, "transportEscrow", 450L);

    JsonNode mortality = scenario(root, "natural_mortality_1460");
    assertExpected(mortality, "naturalDeaths", 100L);
    assertExpected(mortality, "births", 120L);
    assertExpected(mortality, "finalPopulation", 1_020L);

    JsonNode diversified = scenario(root, "diversified_three_sector_1460");
    assertExpected(diversified, "initialPopulation", 1_030L);
    assertExpected(diversified, "finalPopulation", 1_038L);
    assertExpected(diversified, "deaths", 115L);
    assertExpected(diversified, "births", 123L);
    assertExpected(diversified, "totalDebt", 31_087_061L);
    assertExpected(diversified, "totalMoney", 32_798_486L);
    assertExpected(diversified, "deathDebtDeleted", 1_645_425L);
    assertExpected(diversified, "creditIssued", 221_884_895L);
    assertExpected(diversified, "creditRepaid", 189_152_409L);
  }

  private static JsonNode readFixture() throws IOException {
    try (InputStream in = ProbeGoldenReadingsFixtureTest.class.getResourceAsStream(FIXTURE)) {
      assertThat(in).as("classpath 必须有 %s", FIXTURE).isNotNull();
      return JSON.readTree(in);
    }
  }

  private static JsonNode scenario(JsonNode root, String id) {
    for (JsonNode scenario : root.path("scenarios")) {
      if (id.equals(scenario.path("id").asText())) {
        return scenario;
      }
    }
    throw new AssertionError("fixture 缺 scenario: " + id);
  }

  /** 逐值断言（只从 fixture 读，不重跑探针）。 */
  private static void assertExpected(JsonNode scenario, String key, long expected) {
    JsonNode node = scenario.path("expected").path(key);
    assertThat(node.isNumber())
        .as("%s.expected.%s 必须是 JSON number", scenario.path("id").asText(), key)
        .isTrue();
    assertThat(node.asLong())
        .as("%s.expected.%s（P0 报告黄金读数）", scenario.path("id").asText(), key)
        .isEqualTo(expected);
  }

  /** expected 的每个叶子都必须是整数（量纲口径：探针整数读数，不是浮点/字符串）。 */
  private static void assertEveryExpectedLeafIsAnInteger(String scenarioId, JsonNode expected) {
    Iterator<String> names = expected.fieldNames();
    while (names.hasNext()) {
      String key = names.next();
      JsonNode value = expected.get(key);
      assertThat(value.isNumber() || value.isInt() || value.isLong())
          .as("%s.expected.%s 必须是 JSON number", scenarioId, key)
          .isTrue();
      assertThat(value.isFloatingPointNumber())
          .as("%s.expected.%s 不得是浮点数（fixture 冻结整数读数）", scenarioId, key)
          .isFalse();
    }
  }
}
