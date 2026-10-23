package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.model.DecisionCallMeta;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.tools.GaProposalHost;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 决策包条目的校验与格式化（设计书 §7.3；原 {@code ProposalCatalog} 的 ga 侧消费点）。
 *
 * <p>唯一职责：对一条“真实工具名 + 参数”跑 真 preview → 目标提取 → scope 校验，并产出 AgentLib {@link PackageItem} 与 Simos
 * {@link DecisionCallMeta}。它<b>不回答“能否入包”</b>——那由 {@code ToolGate.Ask} 与权限链决定；这里只为已被收进决策包的调用生成可裁决载荷。
 *
 * <p>本类不是状态机：replace 等状态转移仍只由 {@code DecisionPackageTransitions} 执行。
 */
final class DecisionCallValidator {

  private final String mapId;
  private final GaProposalHost previewer;
  private final GaToolIdentity identity;

  DecisionCallValidator(String mapId, GaProposalHost previewer, GaToolIdentity identity) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    this.previewer = Objects.requireNonNull(previewer, "previewer");
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  /**
   * 校验一条调用并生成包条目。
   *
   * <p>{@code error != null} ⇒ 调用方原样返回（具名拒的码与文案由这里统一）。
   */
  ValidatedCall validate(
      DecisionMaker dm,
      SimulationState state,
      String toolName,
      Map<String, Object> args,
      int index) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(args, "args");
    if (!dm.allowedTools().isEmpty() && !dm.allowedTools().contains(toolName)) {
      return ValidatedCall.failed(
          ToolResult.error("FORBIDDEN", "工具不在该决策人的 allowedTools 白名单: " + toolName));
    }
    Map<String, Object> preview = previewer.preview(toolName, state, args);
    List<CommandTarget> targets = previewer.targets(toolName, state, args, preview);
    if (targets.isEmpty()) {
      return ValidatedCall.failed(
          ToolResult.error("BAD_REQUEST", "未能从参数/预览提取到任何可寻址目标，拒绝入包: " + toolName));
    }
    Optional<String> violation = identity.scopeViolation(dm.id(), state, targets, mapId);
    if (violation.isPresent()) {
      return ValidatedCall.failed(ToolResult.error("FORBIDDEN", violation.get()));
    }
    PackageItem item =
        new PackageItem(
            index,
            toolName,
            args,
            DecisionItemDigest.argsDigest(args),
            DecisionItemDigest.itemSummary(toolName, args));
    DecisionCallMeta meta =
        new DecisionCallMeta(
            targets,
            ToolResults.json(preview),
            List.of("scope-ok", "preview-ok"),
            Optional.empty(),
            Optional.empty());
    return ValidatedCall.ok(item, meta, preview);
  }

  /** 可选对象参数（{@code null}/缺省 ⇒ null；键必须是非空文本）。工具面唯一解析点。 */
  static Map<String, Object> optionalMap(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是对象");
    }
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new IllegalArgumentException("参数 " + name + " 的键必须是非空文本");
      }
      out.put(key, entry.getValue());
    }
    return out;
  }

  /** 一条通过校验的调用；{@code error != null} 时其余字段不可用。 */
  record ValidatedCall(
      ToolResult error, PackageItem item, DecisionCallMeta meta, Map<String, Object> preview) {

    static ValidatedCall failed(ToolResult error) {
      return new ValidatedCall(Objects.requireNonNull(error, "error"), null, null, Map.of());
    }

    static ValidatedCall ok(PackageItem item, DecisionCallMeta meta, Map<String, Object> preview) {
      return new ValidatedCall(
          null,
          Objects.requireNonNull(item, "item"),
          Objects.requireNonNull(meta, "meta"),
          Objects.requireNonNull(preview, "preview"));
    }
  }
}
