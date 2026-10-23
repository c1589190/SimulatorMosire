package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code simos.ga.request}（DM 面，设计书 §7.3）：把纯 NL 请求作为包内一条 {@code PackageItem}。
 *
 * <p>它<b>必须是 Ask</b>：由 {@code DecisionTurnScope} 绑定的 {@code PendingDecisionQueue} 接住，回合末由 {@code
 * DecisionTurnFinalizer} 转成 {@code simos.ga.request} 条目；工具自身没有世界写，也不应被直接执行。
 * 这条路径保证“纯文本不静默丢失”，同时不制造空包。
 */
public final class RequestTool implements SimosTool {

  public static final String NAME = "simos.ga.request";

  private static final ResourceManifest NO_RESOURCES = ResourceManifest.NONE;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策人提交一条纯自然语言请求：参数 {text(必填非空白)}。该调用由回合作用域的待裁决队列接住，"
        + "回合末进入本 tick 的同一个决策包（一条 simos.ga.request 条目），不产生任何世界写。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("text", ToolSchema.prop("string", "纯文本请求（必填非空白）"));
    return ToolSchema.schema(props, List.of("text"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return NO_RESOURCES;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Object text = context.arguments().get("text");
    String summary = text instanceof String value && !value.isBlank() ? value : "（缺 text）";
    return new ToolGate.Ask(name(), "纯文本请求: " + summary, AskKind.SENSITIVE);
  }

  /** Ask 的工具在绑定队列时不会执行到这里；若没有队列却被直接执行，必须 fail-closed，而不是静默当作成功。 */
  @Override
  public ToolResult execute(ToolContext context) {
    return ToolResult.error(
        "UNAVAILABLE", NAME + " 只允许在绑定 DecisionTurnScope 的决策人回合里使用；当前没有待裁决队列，拒绝静默丢失请求");
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM);
  }
}
