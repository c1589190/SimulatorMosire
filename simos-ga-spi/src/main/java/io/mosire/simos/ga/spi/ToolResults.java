package io.mosire.simos.ga.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.StateRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具面的**结果折叠**：视图 → JSON → {@code ToolResult}，以及窄写结局（{@link ToolWritePort.Outcome}）的三分支折叠。
 *
 * <p>★ 迁自 {@code io.mosire.simos.app.tools.ToolSupport} 的 {@code json} / {@code ok} / {@code
 * stateRef} / {@code committedView} / {@code conflictView} / {@code rejectedView} / {@code
 * fold}（2026-10-12 B3 试点）： 前六个是纯函数（只依赖 util 类型），{@code fold} 换成吃 {@link ToolWritePort.Outcome} ——
 * 那是**本模块自己的**类型， 故它的折叠helper 与它同住（否则 7 个域各写一份 ⇒ 7 处拒绝码与字段名，正是 I-G1 要防的漂移）。
 *
 * <p>★★ <b>字段名与顺序逐字照抄 app 侧原实现</b>（{@code result}/{@code ref}/{@code current}/{@code reason}/
 * {@code commandId}/{@code correlationId}）与序列化器（{@link SimosObjectMapper#create()}）：工具结果的 JSON
 * 是**对外载荷**，本次搬迁不允许改一个字节（I-G1）。旧的 {@code ToolSupport} **一行未改**（其余 161 个工具仍在用）。
 *
 * <p>★ <b>这里没有的</b>：{@code plannedCommandsView}（吃 core 的 {@code CommandEnvelope}）与 {@code
 * timestamp}（吃 app 的 {@code CalendarService}）——两者都留在 app（引 core/app 就破 I-G2）。
 */
public final class ToolResults {

  private ToolResults() {}

  /** 与 app 侧同一份建造方式（{@link SimosObjectMapper#create()}）：配置固定，输出逐字节可比。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /** 视图 → JSON（序列化失败 ⇒ {@link IllegalStateException}，不静默发半个载荷）。 */
  public static String json(Object view) {
    try {
      return MAPPER.writeValueAsString(view);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("JSON 序列化失败: " + e.getOriginalMessage(), e);
    }
  }

  /** 成功结果（视图落成 JSON 文本）。 */
  public static ToolResult ok(Object view) {
    return ToolResult.ok(json(view));
  }

  /** {@code {branch, revision}} 的只读视图（提交结局里要给真实坐标）。 */
  public static Map<String, Object> stateRef(StateRef ref) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branch", ref.branch().value());
    view.put("revision", ref.revision().value());
    return view;
  }

  /**
   * 提交成功的那种视图（{@code result/ref/commandId/correlationId}）。
   *
   * <p>★ **公开的理由**（照 app 侧 {@code ToolSupport.committedView} 的同一条）：子类可能要在**同一条结果**里
   * 既报提交结局、又报自己的附加轨迹 ⇒ 它要把这四个字段当**构造块**嵌进自己的视图，而不是再手拼一遍（手拼一条就会与 这里漂移：字段名、id 取哪个，两处各说各话且没有症状）。
   */
  public static Map<String, Object> committedView(
      StateRef ref, String commandId, String correlationId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "committed");
    view.put("ref", stateRef(ref));
    view.put("commandId", commandId);
    view.put("correlationId", correlationId);
    return view;
  }

  /**
   * {@link ToolWritePort.Outcome} 三结局的统一折叠：{@code Committed → ok}（带新坐标）、 {@code Conflict/Rejected →
   * error}（带真实 head / 理由）。
   *
   * <p>★ 拒绝码与 app 侧 {@code ToolSupport.fold(CommandResult, …)} **逐字相同**：{@code CONFLICT} / {@code
   * REJECTED}；{@code Committed} 走 {@code ok}。
   *
   * @param outcome 窄写口给的三结局之一
   * @param commandId 本次提交的命令 id（由写口适配器生成并回报，见 {@link ToolWritePort.Receipt}）
   * @param correlationId 调用链 id（单命令链 = {@code commandId}，与今天同口径）
   */
  public static ToolResult fold(
      ToolWritePort.Outcome outcome, String commandId, String correlationId) {
    Objects.requireNonNull(outcome, "outcome");
    return switch (outcome) {
      case ToolWritePort.Outcome.Committed committed ->
          ToolResult.ok(json(committedView(committed.ref(), commandId, correlationId)));
      case ToolWritePort.Outcome.Conflict conflict ->
          ToolResult.error(
              "CONFLICT", json(conflictView(conflict.current(), commandId, correlationId)));
      case ToolWritePort.Outcome.Rejected rejected ->
          ToolResult.error(
              "REJECTED", json(rejectedView(rejected.reason(), commandId, correlationId)));
    };
  }

  private static Map<String, Object> conflictView(
      StateRef current, String commandId, String correlationId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "conflict");
    view.put("current", stateRef(current));
    view.put("commandId", commandId);
    view.put("correlationId", correlationId);
    return view;
  }

  private static Map<String, Object> rejectedView(
      String reason, String commandId, String correlationId) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "rejected");
    view.put("reason", reason);
    view.put("commandId", commandId);
    view.put("correlationId", correlationId);
    return view;
  }
}
