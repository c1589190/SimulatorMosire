package io.mosire.simos.app.gov;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.app.testing.GovE2eWorld;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.tools.write.GovCreateOfficeTool;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>P0（2026-10-15 测试面）：{@code simos.gov.createOffice} 的非门控端到端守卫</b>——把 {@code OfficeProbe} /
 * {@code CreateOfficeAcceptProbe} 两支仓库外探针沉淀成仓内可跑用例。
 *
 * <h2>它守的是什么失效形态</h2>
 *
 * <p>2026-10-15 查出的第一个长期存活缺陷：{@code GovCreateOfficePlan} 在 {@code ga.CreateDecisionMaker} 的载荷里塞了
 * {@code affiliation}，而 handler <b>具名拒收该字段</b> ⇒ <b>整批被拒、0 落盘</b>（单位/家户/编制/决策人/归属行一件都没发生）。
 * 它长期没被发现的机制是 <b>三重死角</b>：
 *
 * <ul>
 *   <li>唯一碰到它的 e2e（{@code RealLlmGovScenarioTest}）整类门控 {@code SIMOS_REAL_LLM=1} ⇒ 默认跳过；
 *   <li>那条用例自己的 {@code seedGenesis()} <b>缺 army 片</b> ⇒ 就算开闸，worldgen 也在创世自检处抛「state 里没有 army 切片」⇒
 *       根本走不到 createOffice（<b>坏夹具</b>，已在本批一并修好）；
 *   <li>仓里没有任何<b>非门控</b>用例真调过这条工具。
 * </ul>
 *
 * ⇒ 本类<b>不挂任何门控</b>（不加 {@code @EnabledIfEnvironmentVariable}），默认 {@code mvn test} 就跑。
 *
 * <h2>判据（一律看 code / submission.result / 直接读态；不看 submitted）</h2>
 *
 * <ol>
 *   <li>命令批被接受：{@code code != REJECTED} 且 {@code submission.result == "committed"}；
 *   <li>批内容自洽：{@code commands} 里必须有 {@code ga.CreateDecisionMaker} 与 {@code
 *       gov.AssignDecisionMakerGov}（当年就是这两条的载荷/缺失出的问题）；
 *   <li><b>一批 = 一条 revision</b>（两个独立口径：{@code head} 增量 + {@code revisions} 行数增量，都不经工具自报）；
 *   <li><b>世界真的变了</b>：单位在、带 {@link GovernmentFormation}、编制 {@code SCRIBE=10}、政府家户在、家户账户在、
 *       决策人在、{@code GovState.govAffiliations()} 里有 {@code 决策人 → 单位} 那一行。
 * </ol>
 *
 * <p>★ 负向（§2.3 假绿修正的回归靶子）：陈旧 {@code expectedRevision} ⇒ 非 Committed 结局 ⇒ {@code
 * code=CONFLICT}、{@code submission.result=conflict}、<b>{@code submitted=false}</b>、head 不变。
 */
class GovCreateOfficeEndToEndTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String UNIT_ID = "e2e-gov-central";
  private static final String DM_ID = "e2e-dm-central";

  @TempDir Path tempDir;

  @Test
  void createOfficeIsAcceptedAsOneRevisionAndEveryPieceLandsOnDisk() throws Exception {
    try (GovE2eWorld world = GovE2eWorld.start(tempDir.resolve("store"))) {
      long headBefore = world.head();
      long rowsBefore = world.revisionRows();
      long sqlRowsBefore = world.revisionRowsViaSql();
      System.out.println(
          "[E2E] headBefore="
              + headBefore
              + " revisionRows="
              + rowsBefore
              + " sqlRows="
              + sqlRowsBefore);

      ToolResult result = world.callGm(GovCreateOfficeTool.NAME, args(headBefore));
      System.out.println("[E2E] code=" + result.code() + " success=" + result.success());
      assertThat(result.message()).as("★ 工具必须返回 JSON 视图（否则下面的字段判据全是空气）").isNotNull();
      JsonNode view = JSON.readTree(result.message());

      // 判据 1：命令批被接受。
      assertThat(result.success())
          .as("★★ createOffice 必须被接受（整批被拒 = 0 落盘，就是本类守的那个缺陷）: %s", result.message())
          .isTrue();
      assertThat(view.path("submission").path("result").asText())
          .as("★★ 只看 submission.result（★ 不看 submitted —— 那个字段当年在整批被拒时恒 true）")
          .isEqualTo("committed");

      // 判据 2：批内容自洽（决策人 + 归属两条命令都在批里）。
      // ★ createOffice 的 commands 是**字符串数组**（ProvinceApply 的是 {type,count} 对象数组，两者形状不同）。
      List<String> commands = new java.util.ArrayList<>();
      for (JsonNode command : view.path("commands")) {
        commands.add(command.asText());
      }
      System.out.println("[E2E] commands=" + commands);
      assertThat(commands)
          .as("★★ 批里必须有 ga.CreateDecisionMaker（建决策人）")
          .contains("ga.CreateDecisionMaker");
      assertThat(commands)
          .as("★★ 批里必须有 gov.AssignDecisionMakerGov（归属归模块；缺它 = 有决策人但没有归属行）")
          .contains("gov.AssignDecisionMakerGov");

      // 判据 3：一批 = 一条 revision（三个独立口径：head 指针 / revisions 读 API / 直接查库）。
      long headAfter = world.head();
      long rowsAfter = world.revisionRows();
      long sqlRowsAfter = world.revisionRowsViaSql();
      System.out.println(
          "[E2E] headAfter="
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
          .as("★★ 一批 = 恰好一条 revision（★ 直接查库口径，形制照 CreateOfficeAcceptProbe）")
          .isEqualTo(sqlRowsBefore + 1L);

      // 判据 4：直接读态（不经工具、不经 ToolResult）。
      SimulationState state = world.state();
      UnitState units = ToolSupport.unitState(state);
      Unit unit = units.units().get(new UnitId(UNIT_ID));
      assertThat(unit).as("★★ 目标 GOV 单位必须真的落盘（单位 id 是身份）").isNotNull();
      GovernmentFormation formation =
          unit.module().orElse(null) instanceof GovernmentFormation value ? value : null;
      assertThat(formation).as("★★ 单位必须带 GovernmentFormation（编制/岗位的载体）").isNotNull();
      assertThat(formation.staff().getOrDefault(StaffRole.SCRIBE, 0L))
          .as("★★ 编制必须真的落盘：SCRIBE=10")
          .isEqualTo(10L);

      SocialData social = ToolSupport.socialData(state);
      HouseholdId govHousehold = GovernmentHouseholds.of(UNIT_ID);
      assertThat(social.households())
          .as("★★ 政府家户 %s 必须在（每建一个 GOV 同批建它自己的家户）", govHousehold.value())
          .containsKey(govHousehold);

      ActorData actorData = ((ActorSnapshot) state.module("actor").orElseThrow()).data();
      assertThat(actorData.accounts())
          .as("★★ 政府家户的账户必须同批建出来（零余额也算落盘）")
          .containsKey(new HouseholdAccountKey(govHousehold));

      GaState ga = ToolSupport.gaState(state);
      DecisionMakerId dm = new DecisionMakerId(DM_ID);
      assertThat(ga.decisionMakers()).as("★★ 决策人必须真的落盘").containsKey(dm);

      GovState gov = ToolSupport.govState(state);
      System.out.println("[E2E] govAffiliations=" + gov.govAffiliations());
      assertThat(gov.govAffiliations())
          .as("★★ govAffiliations() 里必须有 决策人 → 单位 那一行（缺它 = 决策人无归属、范围函数 deny-all）")
          .containsEntry(dm, new UnitId(UNIT_ID));
    }
  }

  /**
   * ★★ <b>P3 回归靶子（假绿修正）</b>：非 Committed 结局时 {@code submitted} 必须 {@code false}。
   *
   * <p>旧代码里 {@code submitted} 恒 {@code true}（不管落没落盘）⇒ "按 submitted 判成功"的断言在整批被拒时是<b>假绿</b>。
   * 本用例用<b>陈旧 expectedRevision</b>（第一次提交用过的那个 base）真调第二次 ⇒ 判定结局是 {@code Conflict}（批根本进不去） ⇒ 必须
   * {@code code=CONFLICT} + {@code submission.result=conflict} + {@code submitted=false} + 世界一动不动。
   *
   * <p>★ 如实记（同 {@code CreateOfficeAcceptProbe} 的结论）：本夹具<b>没能</b>构造出 {@code REJECTED} 的整批拒（批内要拒必须有域层
   * 可拒之事，而 Plan 的前置具名拒都发生在批外）；{@code REJECTED} 与 {@code CONFLICT} 走的是<b>同一个</b> boolean （{@code
   * outcome instanceof Committed}），CONFLICT 已实测为 false。
   */
  @Test
  void aNonCommittedOutcomeReportsSubmittedFalseAndLeavesTheWorldUntouched() throws Exception {
    try (GovE2eWorld world = GovE2eWorld.start(tempDir.resolve("store"))) {
      long staleHead = world.head();
      ToolResult first = world.callGm(GovCreateOfficeTool.NAME, args(staleHead));
      assertThat(first.success()).as("第一次（新鲜 base）必须成功：%s", first.message()).isTrue();
      long headAfterFirst = world.head();
      long rowsAfterFirst = world.revisionRows();

      // 新 id（Plan 前置放行）+ 陈旧 base revision（就是第一次用过的那个）⇒ 批根本进不去，判定 Conflict。
      Map<String, Object> second = args(staleHead);
      second.put("unitId", "e2e-gov-conflict");
      second.put("decisionMakerId", "e2e-dm-conflict");
      ToolResult dup = world.callGm(GovCreateOfficeTool.NAME, second);
      System.out.println("[E2E-NEG] code=" + dup.code() + " message=" + dup.message());
      JsonNode view = JSON.readTree(dup.message());

      assertThat(dup.code())
          .as("★ 陈旧 base ⇒ 具名码 CONFLICT（不是 TOOL_ERROR、不是静默成功）")
          .isEqualTo("CONFLICT");
      assertThat(view.path("submission").path("result").asText())
          .as("★ 判据看 submission.result")
          .isEqualTo("conflict");
      assertThat(view.path("submitted").asBoolean())
          .as("★★ P3 回归靶子：非 Committed 结局时 submitted 必须 false（旧代码在这里恒 true = 假绿）")
          .isFalse();
      assertThat(world.head()).as("★ 没落盘 ⇒ head 不动").isEqualTo(headAfterFirst);
      assertThat(world.revisionRows()).as("★ 没落盘 ⇒ revision 行数不动").isEqualTo(rowsAfterFirst);
      assertThat(ToolSupport.unitState(world.state()).units())
          .as("★ 世界一动不动：那个冲突 id 的单位根本不存在")
          .doesNotContainKey(new UnitId("e2e-gov-conflict"));
    }
  }

  /** 真调参数（形制照 {@code CreateOfficeAcceptProbe.baseArgs}；只给必填 + staff）。 */
  private static Map<String, Object> args(long expectedRevision) {
    Map<String, Object> toolArgs = new LinkedHashMap<>();
    toolArgs.put("unitId", UNIT_ID);
    toolArgs.put("name", "端到端验收中央 GOV");
    toolArgs.put("q", 0);
    toolArgs.put("r", 0);
    toolArgs.put("level", "CENTRAL");
    toolArgs.put("staff", Map.of("SCRIBE", 10));
    toolArgs.put("decisionMakerId", DM_ID);
    toolArgs.put("reason", "P0 沉淀：createOffice 非门控端到端");
    toolArgs.put("preview", false);
    toolArgs.put("expectedRevision", expectedRevision);
    return toolArgs;
  }
}
