package io.mosire.simos.army.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.army.stop.StopReportBoard;
import io.mosire.simos.army.stop.StopReportViews;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.util.spi.StopReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.army.stopReport}（2026-10-13 急停协议，口径 2b <b>原因系统</b>）：<b>GM 读回最近一次急停"哪些模块要求停下"</b>。
 *
 * <p>用户原话：「甚至要做一个<b>原因系统</b>，明确是<b>哪些模块</b>（可能不止一个模块要求停下呢？），最好<b>附上日志之类的</b>，方便 <b>GM 用 MCP
 * 工具查</b>，也方便你调试」。
 *
 * <p>★ <b>返回</b>：{@code {present, stops, atTick, worldRef:{branch,revision},
 * reasons:[{namespace,kind,payload,logRefs[]}]}}； 本进程没有记录过 ⇒ {@code
 * present=false}（**不是**错误码：没停过是正常状态）。
 *
 * <p>★★ <b>它是过程观测，不是世界状态</b>（照 {@code DecisionRunRegistry} 的取舍）：不进 revision、不落 DB 表 ⇒
 * <b>进程重启即失</b>。这条必须让读者看得见，故 {@link #description()} 里明写"读不到 ≠ 没发生过"—— 否则 GM
 * 会把"重启后读不到"读成"世界从没停过"，那是一个比没有工具更坏的失败。
 *
 * <p>★ <b>只在 GM 桶</b>（{@link GmOnlyRead}）：原因里含<b>视野之外</b>的军队位置（{@code payload.hex}）——那是中央视角的信息，
 * 决策人看不见（用户裁定：中央不设全视野，D-002）。★ 住 {@code simos-army} 是设计书 §7.2 的指定落点（那里同时是判据与注册的住所）。
 */
public final class StopReportTool implements SimosTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.army.stopReport";

  private final StopReportBoard board;

  public StopReportTool(StopReportBoard board) {
    this.board = Objects.requireNonNull(board, "board");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "读回最近一次急停的原因系统：{present, stops, atTick, worldRef:{branch,revision},"
        + " reasons:[{namespace,kind,payload,logRefs}]}。atTick=世界停在的那一天（那天已推进完并落盘，可从它续推）；"
        + "reasons=当天**每一个**要求停下的模块各一条（如 namespace=army, kind=army.contact,"
        + " payload={armies:[a,b],hex:\"q_r\"}）；logRefs=模块自报的日志锚，可在服务日志里直接搜"
        + "（如 \"ARMY_CONTACT day=5 a=a1 b=a2\"）。★ 这是**进程内**的过程观测（照 DecisionRunRegistry：不进 revision、"
        + "不落库）⇒ **进程重启即失**：present=false 只表示本进程没有记录，**不等于**世界从没停过（重启前的急停查不回来）。"
        + "stops=本进程见过的急停次数。急停只在走 CoreSimos.submitAdvance 的入口产生（本批：simos.emergency.advance）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    // ★ 无参数：原因系统只有"最近一次"这一份读数（历史请从 store 的 revision + 日志里查）。
    return ToolSchema.schema(ToolSchema.targetProps(), List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    Optional<StopReport> latest = board.latest();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("present", latest.isPresent());
    view.put("stops", board.stops());
    latest.ifPresent(report -> view.putAll(StopReportViews.view(report)));
    if (latest.isEmpty()) {
      view.put(
          "note",
          "本进程没有记录过急停（进程重启即失；present=false 不等于世界从没停过）。"
              + "急停只在走 CoreSimos.submitAdvance 的入口（本批：simos.emergency.advance）时产生。");
    }
    return ToolResults.ok(view);
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
