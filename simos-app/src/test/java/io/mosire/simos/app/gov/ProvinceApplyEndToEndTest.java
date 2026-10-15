package io.mosire.simos.app.gov;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.testing.GovE2eWorld;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.world.CompactThreeNationsWorld;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.tools.write.ProvinceApplyTool;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>P0（2026-10-15 测试面）：{@code simos.province.apply} 的非门控端到端守卫</b>——把 {@code ProvinceProbe}
 * 沉淀成仓内可跑用例。
 *
 * <h2>它守的是什么失效形态</h2>
 *
 * <p>与 {@code createOffice} <b>逐字同因</b>的第二条长期存活缺陷：{@code ProvinceApplyPlan} 逐 GOV 在 {@code
 * ga.CreateDecisionMaker} 的载荷里塞 {@code affiliation}，而 handler 具名拒收 ⇒ <b>整批被拒、0 落盘</b>（3 个 GOV
 * 一个都没建、3 条归属行一条都没有）。它的存活机制与第一条完全相同：唯一门控 e2e 走不到 + 全仓无非门控用例。
 *
 * <h2>判据（一律看 code / submission.result / 直接读态）</h2>
 *
 * <ol>
 *   <li>命令批被接受：{@code code != REJECTED} 且 {@code submission.result == "committed"}；
 *   <li><b>3/3 个 GOV 真的落盘</b>：中央辖区 + 两个省，逐个核「决策人在 + 单位在 + 带 GovernmentFormation + {@code
 *       govAffiliations()} 有那一行」（★ 这是"逐 GOV 报账"的判据，不是只看批结论）；
 *   <li><b>一批 = 一条 revision</b>（{@code head} 增量 + {@code revisions} 行数增量，两个独立口径）；
 *   <li>批里 {@code gov.AssignDecisionMakerGov} 恰好 3 条、{@code unit.SetJurisdiction} 恰好 3 条（逐 GOV 一份）。
 * </ol>
 *
 * <p>★ 负向（§2.3 假绿修正的回归靶子）：陈旧 {@code expectedRevision} ⇒ {@code code=CONFLICT} + {@code
 * submission.result=conflict} + <b>{@code submitted=false}</b> + 世界一动不动。
 */
class ProvinceApplyEndToEndTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void provinceApplyLandsThreeGovsWithAffiliationsInExactlyOneRevision() throws Exception {
    try (GovE2eWorld world = GovE2eWorld.start(tempDir.resolve("store"))) {
      long headBefore = world.head();
      long rowsBefore = world.revisionRows();
      long sqlRowsBefore = world.revisionRowsViaSql();
      System.out.println(
          "[PROV] headBefore="
              + headBefore
              + " revisionRows="
              + rowsBefore
              + " sqlRows="
              + sqlRowsBefore);

      ToolResult result =
          world.callGm(
              ProvinceApplyTool.NAME, args(CompactThreeNationsWorld.GRANARY.value(), headBefore));
      assertThat(result.message()).as("★ 工具必须返回 JSON 视图").isNotNull();
      JsonNode view = JSON.readTree(result.message());
      System.out.println("[PROV] code=" + result.code() + " success=" + result.success());

      // 判据 1：命令批被接受。
      assertThat(result.success())
          .as("★★ province.apply 必须被接受（整批被拒 = 0 落盘，就是本类守的那个缺陷）: %s", result.message())
          .isTrue();
      assertThat(view.path("submission").path("result").asText())
          .as("★★ 只看 submission.result（★ 不看 submitted）")
          .isEqualTo("committed");

      // 判据 4：批里逐 GOV 一份归属 + 一份辖区。
      Map<String, Integer> commandCounts = new LinkedHashMap<>();
      for (JsonNode command : view.path("commands")) {
        commandCounts.merge(
            command.path("type").asText(), command.path("count").asInt(1), Integer::sum);
      }
      System.out.println("[PROV] commands=" + commandCounts);
      assertThat(commandCounts.getOrDefault("gov.AssignDecisionMakerGov", 0))
          .as("★★ 每个 GOV 同批一条归属命令（3 个 GOV ⇒ 3 条）")
          .isEqualTo(3);
      assertThat(commandCounts.getOrDefault("ga.CreateDecisionMaker", 0))
          .as("★★ 每个 GOV 同批一个决策人（3 个 GOV ⇒ 3 条）")
          .isEqualTo(3);

      // 判据 2：3/3 个 GOV 直接读态核账（中央辖区 + 两个省）。
      SimulationState state = world.state();
      GaState ga = ToolSupport.gaState(state);
      GovState gov = ToolSupport.govState(state);
      UnitState units = ToolSupport.unitState(state);
      List<String[]> govs = govRecords(view);
      System.out.println("[PROV] govRecords=" + govs.size());
      assertThat(govs).as("★ 视图必须报出中央辖区 + 两个省（3 个 GOV）").hasSize(3);
      int ok = 0;
      for (String[] record : govs) {
        String unitId = record[0];
        String dmId = record[1];
        Unit unit = units.units().get(new UnitId(unitId));
        boolean formation =
            unit != null && unit.module().orElse(null) instanceof GovernmentFormation;
        boolean dm = ga.decisionMakers().containsKey(new DecisionMakerId(dmId));
        boolean affiliation =
            new UnitId(unitId).equals(gov.govAffiliations().get(new DecisionMakerId(dmId)));
        System.out.println(
            "[PROV] gov="
                + unitId
                + " dm="
                + dmId
                + " unit="
                + (unit != null)
                + " formation="
                + formation
                + " decisionMaker="
                + dm
                + " affiliationRow="
                + affiliation);
        assertThat(unit).as("★★ GOV 单位 %s 必须真的落盘", unitId).isNotNull();
        assertThat(formation).as("★★ %s 必须带 GovernmentFormation", unitId).isTrue();
        assertThat(dm).as("★★ 决策人 %s 必须真的落盘", dmId).isTrue();
        assertThat(affiliation).as("★★ govAffiliations() 里必须有 %s → %s 那一行", dmId, unitId).isTrue();
        ok++;
      }
      assertThat(ok).as("★★ 3/3 个 GOV 全部齐备（少一个 = 半截态）").isEqualTo(3);
      System.out.println("[PROV] govAffiliations=" + gov.govAffiliations());

      // 判据 3：一批 = 一条 revision（两个独立口径）。
      long headAfter = world.head();
      long rowsAfter = world.revisionRows();
      long sqlRowsAfter = world.revisionRowsViaSql();
      System.out.println(
          "[PROV] headAfter="
              + headAfter
              + " revisionRows="
              + rowsAfter
              + " sqlRows="
              + sqlRowsAfter);
      assertThat(headAfter).as("★★ 一批 = 恰好一条 revision（head 口径）").isEqualTo(headBefore + 1L);
      assertThat(rowsAfter)
          .as("★★ 一批 = 恰好一条 revision（revisions 行数口径，与 head 独立）")
          .isEqualTo(rowsBefore + 1L);
      assertThat(sqlRowsAfter)
          .as("★★ 一批 = 恰好一条 revision（★ 直接查库口径，形制照 ProvinceProbe）")
          .isEqualTo(sqlRowsBefore + 1L);
    }
  }

  /**
   * ★★ <b>P3 回归靶子（假绿修正）</b>：非 Committed 结局时 {@code submitted} 必须 {@code false}。
   *
   * <p>与 {@code createOffice} 同一条修法、同一个 boolean（{@code outcome instanceof Committed}）；这里用陈旧 {@code
   * expectedRevision} 真调第二次 ⇒ {@code Conflict} ⇒ {@code submitted=false} 且世界一动不动。
   *
   * <p>★ 如实记：本夹具没能构造出 {@code REJECTED} 整批拒（Plan 的前置具名拒都发生在批外）；{@code REJECTED} 与 {@code CONFLICT}
   * 走同一个 boolean。
   */
  @Test
  void aNonCommittedOutcomeReportsSubmittedFalseAndLeavesTheWorldUntouched() throws Exception {
    try (GovE2eWorld world = GovE2eWorld.start(tempDir.resolve("store"))) {
      String region = CompactThreeNationsWorld.GRANARY.value();
      long staleHead = world.head();
      ToolResult first = world.callGm(ProvinceApplyTool.NAME, args(region, staleHead));
      assertThat(first.success()).as("第一次（新鲜 base）必须成功：%s", first.message()).isTrue();
      long headAfterFirst = world.head();
      long rowsAfterFirst = world.revisionRows();

      ToolResult dup = world.callGm(ProvinceApplyTool.NAME, args(region, staleHead));
      System.out.println("[PROV-NEG] code=" + dup.code() + " message=" + dup.message());
      JsonNode view = JSON.readTree(dup.message());
      assertThat(dup.code()).as("★ 陈旧 base ⇒ 具名码 CONFLICT").isEqualTo("CONFLICT");
      assertThat(view.path("submission").path("result").asText())
          .as("★ 判据看 submission.result")
          .isEqualTo("conflict");
      assertThat(view.path("submitted").asBoolean())
          .as("★★ P3 回归靶子：非 Committed 结局时 submitted 必须 false（旧代码恒 true = 假绿）")
          .isFalse();
      assertThat(world.head()).as("★ 没落盘 ⇒ head 不动").isEqualTo(headAfterFirst);
      assertThat(world.revisionRows()).as("★ 没落盘 ⇒ revision 行数不动").isEqualTo(rowsAfterFirst);
    }
  }

  /** 视图里的 3 个 GOV（中央辖区 + 各省）→ {@code [unitId, dmId]} 列表。 */
  private static List<String[]> govRecords(JsonNode view) {
    List<String[]> out = new ArrayList<>();
    JsonNode capital = view.path("capitalDistrict");
    if (capital.hasNonNull("unitId")) {
      out.add(new String[] {capital.path("unitId").asText(), capital.path("dmId").asText()});
    }
    for (JsonNode province : view.path("provinces")) {
      out.add(new String[] {province.path("unitId").asText(), province.path("dmId").asText()});
    }
    return out;
  }

  /**
   * 真调参数（逐字照 {@code ProvinceProbe.baseArgs}：min/max=10/20 + 首都格 + overrideOverlaps ⇒ 恰好 3 个 GOV）。
   */
  private static Map<String, Object> args(String regionId, long expectedRevision) {
    Map<String, Object> toolArgs = new LinkedHashMap<>();
    toolArgs.put("regionId", regionId);
    toolArgs.put("minHexPerProvince", 10);
    toolArgs.put("maxHexPerProvince", 20);
    toolArgs.put("capitalHex", Map.of("q", 0, "r", 0));
    toolArgs.put("capitalDistrictRadius", 1);
    toolArgs.put("staff", Map.of("SCRIBE", 5));
    toolArgs.put("cadence", 1);
    toolArgs.put("overrideOverlaps", true);
    toolArgs.put("reason", "P0 沉淀：province.apply 非门控端到端");
    toolArgs.put("preview", false);
    toolArgs.put("expectedRevision", expectedRevision);
    return toolArgs;
  }
}
