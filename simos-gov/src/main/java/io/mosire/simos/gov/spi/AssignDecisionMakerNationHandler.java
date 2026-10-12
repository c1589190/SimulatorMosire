package io.mosire.simos.gov.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.ga.spi.DecisionMakerExistencePort;
import io.mosire.simos.gov.GovLog;
import io.mosire.simos.gov.GovLogSource;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.gov.nation.NationId;
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
 * ★★ {@code gov.AssignDecisionMakerNation}（2026-10-12 乙收尾）：<b>nation 归属的写入工单</b>——把某个决策人绑到某个国家，
 * 或者解绑（清除）。
 *
 * <pre>{@code
 * {"decisionMakerId":"dm-1","nationId":"n1"}   // 赋予 / 改绑
 * {"decisionMakerId":"dm-1"}                   // 清除（nationId 缺席/null/空串）
 * }</pre>
 *
 * <p>★★ <b>为什么这条命令存在</b>（用户原话「<b>归属肯定归模块啊</b>」＋「单个决策人的具体归属，交给调用 ga 的具体模块不行吗？<b>同样搞工单</b>」）： 3c/3d-1
 * 把 nation/army 归属的<b>裁定权</b>搬进了模块，但<b>写入</b>当时仍挂在 {@code ga.CreateDecisionMaker} 的 {@code
 * affiliation} 载荷上（规划 §18.2 的 D1 偏离）⇒ 本批把写入也归位：这张表（{@link GovState#nationAffiliations()}）只由本命令写。
 *
 * <p>★★ <b>幂等 / 可改 / 可清（用户原话「也可以创建/为已有决策人赋予、修改状态」）</b>：
 *
 * <ul>
 *   <li><b>赋予 / 改绑</b>：同一个人再赋一次同一个国家 = 一字未动的空变更集（幂等）；赋另一个国家 = 改绑（覆盖该键）；
 *   <li><b>清除</b>：{@code nationId} 缺席 / {@code null} / 空白 ⇒ 删掉该键；<b>本来就没有 ⇒ 空变更集</b>（幂等，不具名拒——
 *       重试不该失败）；
 *   <li>命令的语义<b>只</b>覆盖 nation 归属这一轴：{@code gov} 归属是<b>另一张表、另一条命令</b> （{@code
 *       gov.AssignDecisionMakerGov}），互不清理（跨模块/跨种类清理结构性做不到——每个模块只拥有自己的表）。
 * </ul>
 *
 * <p>★ <b>两条具名拒</b>：① {@code decisionMakerId} 不是本世界已知的决策人（决策人身份住 {@code ga}，gov 看不见 ⇒ 走 {@link
 * DecisionMakerExistencePort} 端口；这是本批把"写入"从 {@code ga.CreateDecisionMaker} 拆出来<b>必然新增</b>的一条判据——拆分前
 * 决策人正在被创建，不存在"绑到一个不存在的决策人"这回事）；② {@code nationId} 指向的国家不在本切片里。
 *
 * <p>★ <b>GM-only</b>（反默认口径）：不打 {@code DmUsableCommand} ⇒ 只进 catalog 与 GM 提交面，不进可嵌令 / 可入队 / 声明面
 * ——赋权是行政动作，不是决策人可以自己给自己发的令。
 */
public final class AssignDecisionMakerNationHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "gov.AssignDecisionMakerNation";

  /** 归属表所在资源路径的 kind 段（{@code CommandTargets} 的路径语法：{@code nation-affiliation/<决策人 id>}）。 */
  public static final String TARGET_KIND = "nation-affiliation";

  private final DecisionMakerExistencePort decisionMakers;

  public AssignDecisionMakerNationHandler(DecisionMakerExistencePort decisionMakers) {
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
    // ★ 路径不含命名空间：CommandTargets 的默认实现按**命令类型第一段**取命名空间 ⇒ 本命令写的是
    //   gov:nation-affiliation/<决策人 id>（归属表在 gov 切片里，目标就是"这个决策人的那一行"）。
    return List.of(ResourcePaths.sd(TARGET_KIND, id.value()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GovState base = GovSnapshots.of(state);
    String makerForLog = null;
    String nationForLog = null;
    try {
      JsonNode payload = GovPayloads.parse(payloadJson);
      DecisionMakerId decisionMaker =
          DecisionMakerId.parse(GovPayloads.requireText(payload, "decisionMakerId"));
      makerForLog = decisionMaker.value();
      if (!decisionMakers.exists(state, decisionMaker)) {
        return rejected(
            "决策人不存在: " + decisionMaker.value() + "（先 ga.CreateDecisionMaker）", "dm", makerForLog);
      }
      // ★ 清除：nationId 缺席 / null / 空白。（用 optionalText 而非 requireText：这正是"可清"的载荷语义。）
      String rawNation = GovPayloads.optionalText(payload, "nationId").orElse(null);
      if (rawNation != null && rawNation.isBlank()) {
        rawNation = null;
      }
      Map<DecisionMakerId, NationId> next = new LinkedHashMap<>(base.nationAffiliations());
      if (rawNation == null) {
        if (next.remove(decisionMaker) == null) {
          // ★ 幂等：本来就没有 ⇒ 空变更集（重试不该失败；见类注）。
          return new HandlerOutcome.Applied(GovChangeSet.between(base, base));
        }
        EventLog.channel(GovLog.affiliation())
            .info(
                LogEvent.of(
                    "GOV_NATION_AFFILIATION_CLEARED",
                    GovLogSource.GOV_AFFILIATION,
                    "dm",
                    makerForLog,
                    "nationAffiliations",
                    next.size()));
        return new HandlerOutcome.Applied(
            GovChangeSet.between(base, base.withNationAffiliations(next)));
      }
      NationId nation = NationId.parse(rawNation);
      nationForLog = nation.value();
      if (!base.nations().containsKey(nation)) {
        return rejected(
            "国家不存在: " + nation + "（先 gov.CreateNation）", "dm", makerForLog, "nation", nationForLog);
      }
      NationId previous = next.put(decisionMaker, nation);
      if (nation.equals(previous)) {
        // ★ 幂等：同一个人再赋同一个国家 ⇒ 一字未动（不产生"看起来变了"的空 revision 内容）。
        return new HandlerOutcome.Applied(GovChangeSet.between(base, base));
      }
      EventLog.channel(GovLog.affiliation())
          .info(
              LogEvent.of(
                  "GOV_NATION_AFFILIATION_ASSIGNED",
                  GovLogSource.GOV_AFFILIATION,
                  "dm",
                  makerForLog,
                  "nation",
                  nationForLog,
                  "previous",
                  previous == null ? "-" : previous.value(),
                  "nationAffiliations",
                  next.size()));
      return new HandlerOutcome.Applied(
          GovChangeSet.between(base, base.withNationAffiliations(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "dm", makerForLog, "nation", nationForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * GovPayloads} 的日志安全截断，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    GovPayloads.logRejected(
        GovLog.affiliation(),
        GovLogSource.GOV_AFFILIATION,
        "GOV_ASSIGN_NATION_REJECTED",
        reason,
        idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
