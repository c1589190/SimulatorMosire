package io.mosire.simos.app.tools.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.army.stop.StopReportBoard;
import io.mosire.simos.army.stop.StopReportViews;
import io.mosire.simos.army.tools.read.StopReportTool;
import io.mosire.simos.util.spi.Incident;
import io.mosire.simos.util.spi.StopReport;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>原因系统的 GM 读口</b>（2026-10-13 急停协议 §6.2 P5，口径 2b）：{@code simos.army.stopReport} 把 {@link
 * StopReportBoard} 上最近一次急停读回成 GM 看到的形状 {@code {present, stops, atTick, worldRef,
 * reasons:[{namespace,kind,payload,logRefs[]}]}}。
 *
 * <p>★ 为什么这条要单独钉：{@code SimosToolsTest} 的读工具表里那条只覆盖了 <b>{@code present=false}</b>（进程内还没急停过）——
 * 而"急停之后读得回来"才是用户原话「方便 <b>GM 用 MCP 工具查</b>」要的那一半。此处直接给板子记一份报告， 逐字段核读口形状（与 {@link StopReportViews}
 * 同一拼写点：推进工具当次的返回也走它）。
 *
 * <p>★★ <b>进程重启即失</b>是**有意**的取舍（照 {@code DecisionRunRegistry}）：{@code present=false} 只表示本进程没记录，
 * **不等于**世界从没停过 —— 该文案在工具 {@code description()}、板子类注与 {@code note} 三处都写明，本用例钉住 {@code note} 在场。
 */
class StopReportToolTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static ToolResult call(StopReportTool tool) {
    AgentPermissionSet gm = AgentPermissionSet.system();
    return tool.execute(
        new ToolContext(AccessToken.SYSTEM, gm, Map.of(), Map.of())
            .withResources(ResourceAuthorizer.of(gm, tool.resources())));
  }

  /** ★ P5：记下一次急停 ⇒ GM 读回 {@code atTick / worldRef / reasons[]}（payload 与 logRefs 逐值）。 */
  @Test
  void aRecordedStopIsReadBackWithTheFullReasonSystemShape() throws Exception {
    StopReportBoard board = new StopReportBoard();
    StopReportTool tool = new StopReportTool(board);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("armies", List.of("a-army", "b-army"));
    payload.put("hex", "5_0");
    board.record(
        StopReport.of(
            5L,
            new StateRef(new BranchId("main"), new RevisionId(12L)),
            List.of(
                new Incident(
                    "army.contact",
                    5L,
                    "army",
                    payload,
                    List.of("ARMY_CONTACT day=5 a=a-army b=b-army")))));

    ToolResult result = call(tool);

    assertThat(result.success()).as("读口不是错误码：没停过/停过都是正常读数").isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.get("present").asBoolean()).as("★ 有记录 ⇒ present=true").isTrue();
    assertThat(body.get("stops").asInt()).as("本进程见过的急停次数").isEqualTo(1);
    assertThat(body.get("atTick").asLong()).as("★ 停在哪一天").isEqualTo(5L);
    assertThat(body.get("worldRef").get("branch").asText()).isEqualTo("main");
    assertThat(body.get("worldRef").get("revision").asLong())
        .as("★ GM 据此 replay 到停下的那一刻")
        .isEqualTo(12L);
    JsonNode reason = body.get("reasons").get(0);
    assertThat(reason.get("namespace").asText()).as("哪个模块要求停").isEqualTo("army");
    assertThat(reason.get("kind").asText()).isEqualTo("army.contact");
    assertThat(reason.get("payload").get("hex").asText()).isEqualTo("5_0");
    assertThat(reason.get("payload").get("armies").get(0).asText()).isEqualTo("a-army");
    assertThat(reason.get("logRefs").get(0).asText())
        .as("★ 日志锚由模块自报 —— GM 可拿它去服务日志里直接搜")
        .isEqualTo("ARMY_CONTACT day=5 a=a-army b=b-army");
    assertThat(body.has("note")).as("有记录时不该再附「没记录过」的注").isFalse();
  }

  /** 空板子 ⇒ {@code present=false} + {@code note}（★ "读不到 ≠ 没发生过"必须让 GM 看见）。 */
  @Test
  void anEmptyBoardReadsBackAsNotPresentWithTheRestartCaveat() throws Exception {
    ToolResult result = call(new StopReportTool(new StopReportBoard()));

    assertThat(result.success()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.get("present").asBoolean()).isFalse();
    assertThat(body.get("stops").asInt()).isZero();
    assertThat(body.get("note").asText())
        .as("★ 进程重启即失是有意取舍：note 必须把「读不到 ≠ 没发生过」说清楚")
        .contains("重启")
        .contains("不等于");
    assertThat(body.has("atTick")).as("没有记录 ⇒ 不编坐标").isFalse();
  }

  /** 后写覆盖前写：GM 关心的是**最近一次**（计数则累加）。 */
  @Test
  void theLatestStopWinsWhileTheCounterAccumulates() throws Exception {
    StopReportBoard board = new StopReportBoard();
    StopReportTool tool = new StopReportTool(board);
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(3L));
    board.record(
        StopReport.of(
            5L,
            ref,
            List.of(new Incident("army.contact", 5L, "army", Map.of("hex", "5_0"), List.of()))));
    board.record(
        StopReport.of(
            9L,
            new StateRef(new BranchId("main"), new RevisionId(7L)),
            List.of(new Incident("army.contact", 9L, "army", Map.of("hex", "9_0"), List.of()))));

    JsonNode body = JSON.readTree(call(tool).message());

    assertThat(body.get("atTick").asLong()).as("最近一次 = 第 9 天").isEqualTo(9L);
    assertThat(body.get("stops").asInt()).as("次数累加").isEqualTo(2);
  }
}
