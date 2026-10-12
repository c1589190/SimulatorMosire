package io.mosire.simos.gov.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.spi.DecisionMakerExistencePort;
import io.mosire.simos.gov.GovAffiliations;
import io.mosire.simos.gov.GovLog;
import io.mosire.simos.gov.GovLogSource;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code gov.AssignDecisionMakerGov}（2026-10-12 乙收尾）：<b>gov 归属的写入工单</b>——把某个决策人绑到一个 GOV
 * 单位（"实际行政编制"那一轴），或者解绑。
 *
 * <pre>{@code
 * {"decisionMakerId":"dm-1","govUnitId":"g1"}   // 赋予 / 改绑
 * {"decisionMakerId":"dm-1"}                    // 清除（govUnitId 缺席/null/空串）
 * }</pre>
 *
 * <p>★★ <b>它接的正是收窄前 {@code Affiliation.Gov(UnitId)} 那一支</b>：那条支的"必须是存在且带 {@link
 * GovernmentFormation} 的单位"这条判据原本在 sd 的 {@code AffiliationTargets} 里（创建期由 {@code
 * ga.CreateDecisionMaker} 调用）⇒ 本批随"归属归模块" <b>整条搬进本命令</b>（判据逐条保留，拒因改成"归属目标…"的同一族文案）：表本体住 {@link
 * GovState#govAffiliations()}，写口只此一条。
 *
 * <p>★★ <b>幂等 / 可改 / 可清</b>：语义与 {@link AssignDecisionMakerNationHandler} 逐条同构（同值再赋 ⇒ 空变更集；改绑 ⇒
 * 覆盖该键；缺席/null/空白 ⇒ 清除；清一个本来没有的 ⇒ 空变更集）。★ 与 nation 归属<b>是两条独立的轴</b>：本命令不清理那张表，
 * 也不被那张表清理（每个模块/每张表只由自己的命令写）。
 *
 * <p>★ <b>单位被删 / 被摘掉编制不会让世界打不开</b>：命令期具名拒是"写的时候不存在"；写完之后单位没了 ⇒ 读侧 {@link GovAffiliations#resolve}
 * 把那一行裁掉（fail-closed：读不到归属 ⇒ deny-all），与收窄前的 {@code GovScope} 同一口径。
 *
 * <p>★ <b>GM-only</b>：同 {@link AssignDecisionMakerNationHandler}（赋权是行政动作，不进决策令面）。
 */
public final class AssignDecisionMakerGovHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "gov.AssignDecisionMakerGov";

  /** 归属表所在资源路径的 kind 段（{@code gov:gov-affiliation/<决策人 id>}）。 */
  public static final String TARGET_KIND = "gov-affiliation";

  private final DecisionMakerExistencePort decisionMakers;

  public AssignDecisionMakerGovHandler(DecisionMakerExistencePort decisionMakers) {
    this.decisionMakers = Objects.requireNonNull(decisionMakers, "decisionMakers");
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = GovPayloads.parse(payloadJson);
    DecisionMakerId id = DecisionMakerId.parse(GovPayloads.requireText(payload, "decisionMakerId"));
    return List.of(ResourcePaths.sd(TARGET_KIND, id.value()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GovState base = GovSnapshots.of(state);
    String makerForLog = null;
    String govForLog = null;
    try {
      JsonNode payload = GovPayloads.parse(payloadJson);
      DecisionMakerId decisionMaker =
          DecisionMakerId.parse(GovPayloads.requireText(payload, "decisionMakerId"));
      makerForLog = decisionMaker.value();
      if (!decisionMakers.exists(state, decisionMaker)) {
        return rejected(
            "决策人不存在: " + decisionMaker.value() + "（先 ga.CreateDecisionMaker）", "dm", makerForLog);
      }
      String rawGov = GovPayloads.optionalText(payload, "govUnitId").orElse(null);
      if (rawGov != null && rawGov.isBlank()) {
        rawGov = null;
      }
      Map<DecisionMakerId, UnitId> next = new LinkedHashMap<>(base.govAffiliations());
      if (rawGov == null) {
        if (next.remove(decisionMaker) == null) {
          return new HandlerOutcome.Applied(GovChangeSet.between(base, base)); // ★ 幂等（见类注）
        }
        EventLog.channel(GovLog.affiliation())
            .info(
                LogEvent.of(
                    "GOV_GOV_AFFILIATION_CLEARED",
                    GovLogSource.GOV_AFFILIATION,
                    "dm",
                    makerForLog,
                    "govAffiliations",
                    next.size()));
        return new HandlerOutcome.Applied(
            GovChangeSet.between(base, base.withGovAffiliations(next)));
      }
      UnitId govUnit = UnitId.parse(rawGov);
      govForLog = govUnit.value();
      // ★ 判据逐条来自收窄前的 AffiliationTargets（Gov 那一支）：单位必须存在、且带 GovernmentFormation。
      Unit unit = GovSnapshots.units(state).units().get(govUnit);
      if (unit == null) {
        return rejected("归属目标不存在: " + govUnit, "dm", makerForLog, "govUnit", govForLog);
      }
      if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
        return rejected(
            "归属单位 "
                + govForLog
                + " 没有 GovernmentFormation：GOV 决策人只能绑 GOV 单位（先 unit.SetGovFormation）",
            "dm",
            makerForLog,
            "govUnit",
            govForLog);
      }
      UnitId previous = next.put(decisionMaker, govUnit);
      if (govUnit.equals(previous)) {
        return new HandlerOutcome.Applied(GovChangeSet.between(base, base)); // ★ 幂等
      }
      EventLog.channel(GovLog.affiliation())
          .info(
              LogEvent.of(
                  "GOV_GOV_AFFILIATION_ASSIGNED",
                  GovLogSource.GOV_AFFILIATION,
                  "dm",
                  makerForLog,
                  "govUnit",
                  govForLog,
                  "previous",
                  previous == null ? "-" : previous.value(),
                  "govAffiliations",
                  next.size()));
      return new HandlerOutcome.Applied(GovChangeSet.between(base, base.withGovAffiliations(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "dm", makerForLog, "govUnit", govForLog);
    }
  }

  /** 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    GovPayloads.logRejected(
        GovLog.affiliation(),
        GovLogSource.GOV_AFFILIATION,
        "GOV_ASSIGN_GOV_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
