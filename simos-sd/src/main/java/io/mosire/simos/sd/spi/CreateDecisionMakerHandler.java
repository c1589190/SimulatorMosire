package io.mosire.simos.sd.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code sd.CreateDecisionMaker} 命令的处理器（spec §四）。
 *
 * <pre>{@code
 * {"id":"dm1","affiliation":{"kind":"nation","id":"n1"},
 *  "allowedTools":["sd.SubmitVerdict"],"cadence":1}
 * }</pre>
 *
 * <p>★ 拒绝：id 已存在；{@code affiliation} 目标不存在；{@code allowedTools} 含**通用写**（{@link
 * SdCommandNames#SIMOS_COMMAND_SUBMIT}）⇒ 拒绝（N9）。
 *
 * <p>★ **创建期 {@code accessLimit} 恒为"无额外限制"**（{@link AccessLimit#empty()}）：可见范围由 app 层的范围函数 现算，GM
 * 配的是**额外收紧**——创建期不解析它，配权由 GM 专用的 {@code sd.SetDecisionMakerAccess} 写入（spec §4.2）。 这**不是**旧 {@code
 * viewScope.empty()} 那种 deny-all：新语义下空限制 = 不收紧，否则新建的决策人当场变瞎。
 */
public final class CreateDecisionMakerHandler implements CommandHandler {

  @Override
  public String type() {
    return "sd.CreateDecisionMaker";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SdState base = SdSnapshots.of(state).state();
    String dmForLog = null;
    try {
      JsonNode payload = SdPayloads.parse(payloadJson);
      DecisionMakerId id = DecisionMakerId.parse(SdPayloads.requireText(payload, "id"));
      dmForLog = id.value();
      Affiliation affiliation = SdPayloads.requireAffiliation(payload, "affiliation");
      Set<String> allowedTools = SdPayloads.requireTextSet(payload, "allowedTools");
      long cadence = SdPayloads.requireLong(payload, "cadence");
      if (base.decisionMakers().containsKey(id)) {
        return rejected("决策人已存在: " + id, "dm", dmForLog);
      }
      Optional<String> affiliationProblem = affiliationProblem(state, base, affiliation);
      if (affiliationProblem.isPresent()) {
        return rejected(affiliationProblem.get(), "dm", dmForLog);
      }
      if (allowedTools.contains(SdCommandNames.SIMOS_COMMAND_SUBMIT)) {
        return rejected(
            "allowedTools 不得含通用写 " + SdCommandNames.SIMOS_COMMAND_SUBMIT + "（N9：决策 Agent 只用窄工具）",
            "dm",
            dmForLog,
            "allowedTools",
            allowedTools.size());
      }
      Map<DecisionMakerId, DecisionMaker> next = new LinkedHashMap<>(base.decisionMakers());
      next.put(id, new DecisionMaker(id, affiliation, allowedTools, AccessLimit.empty(), cadence));
      EventLog.channel(SdLog.decision())
          .info(
              LogEvent.of(
                  "SD_DECISION_MAKER_CREATED",
                  SdLogSource.SD_NATION,
                  "id",
                  id.value(),
                  "affiliation",
                  affiliation,
                  "cadence",
                  cadence,
                  "allowedTools",
                  allowedTools.size(),
                  "decisionMakers",
                  next.size()));
      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withDecisionMakers(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "dm", dmForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * SdPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    SdPayloads.logRejected(
        SdLog.decision(),
        SdLogSource.SD_NATION,
        "SD_CREATE_DECISION_MAKER_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }

  /**
   * 归属目标的存在性/形态校验（创建期强绑，计划 §0 裁定 10 / 阶段 10 验收）：Nation 要在 {@code sd.nations()}、Army 要在 {@code
   * sd.armies()}、<b>Gov 要在 unit 切片里存在且带 {@code GovernmentFormation}</b>——GOV 决策人只绑 GOV 单位。
   *
   * <p>★ 返回具名理由而不是 boolean：Gov 的"单位不存在"与"单位存在但不是 GOV"是两条不同的纠正方向（先建单位 vs 先 {@code
   * unit.SetGovFormation}），合成一句"目标不存在"会把后者说成谎。★ 运行期缺失由 {@code GovScope} 的 deny-all
   * 兜底（fail-closed，见计划 §5 风险表）。
   */
  private static Optional<String> affiliationProblem(
      SimulationState state, SdState base, Affiliation affiliation) {
    return switch (affiliation) {
      case Affiliation.Nation nation ->
          base.nations().containsKey(nation.nationId())
              ? Optional.empty()
              : Optional.of("affiliation 目标不存在: " + affiliation);
      case Affiliation.Army army ->
          base.armies().containsKey(army.armyId())
              ? Optional.empty()
              : Optional.of("affiliation 目标不存在: " + affiliation);
      case Affiliation.Gov gov -> {
        Unit unit = SdSnapshots.units(state).units().get(gov.govUnit());
        if (unit == null) {
          yield Optional.of("affiliation 目标不存在: " + affiliation);
        }
        if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
          yield Optional.of(
              "affiliation 单位 "
                  + gov.govUnit().value()
                  + " 没有 GovernmentFormation：GOV 决策人只能绑 GOV 单位（先 unit.SetGovFormation）");
        }
        yield Optional.empty();
      }
    };
  }
}
