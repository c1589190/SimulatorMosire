package io.mosire.simos.army.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmyLogSource;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.ga.spi.DecisionMakerExistencePort;
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
import org.slf4j.Logger;

/**
 * ★★ {@code army.AssignDecisionMakerArmy}（2026-10-12 乙收尾）：<b>军队归属的写入工单</b>——把某个决策人绑到一个军队，或者解绑。
 *
 * <pre>{@code
 * {"decisionMakerId":"dm-1","armyId":"a1"}   // 赋予 / 改绑
 * {"decisionMakerId":"dm-1"}                 // 清除（armyId 缺席/null/空串）
 * }</pre>
 *
 * <p>★★ <b>为什么这条命令存在</b>（用户原话「<b>归属肯定归模块啊</b>」＋「单个决策人的具体归属，交给调用 ga 的具体模块不行吗？<b>同样搞工单</b>」）： 3d-1
 * 把军队归属的<b>裁定权</b>搬进了 army，但<b>写入</b>当时仍挂在 {@code ga.CreateDecisionMaker} 的 {@code affiliation}
 * 载荷上（规划 §18.2 的 D1 偏离）⇒ 本批把写入也归位：这张表（{@link ArmyData#armyAffiliations()}）只由本命令写。★ 同时兑现 3d-1 台账
 * §D.2 记下的「乙第 4 步在 army 自己的 {@code army.Assign*DecisionMaker} 命令里把<b>存在性校验补回</b>」。
 *
 * <p>★★ <b>幂等 / 可改 / 可清</b>（用户原话「也可以创建/为已有决策人赋予、修改状态」）：语义与 gov 的两条赋权命令逐字同构 ——
 *
 * <ul>
 *   <li><b>赋予 / 改绑</b>：同一个人再赋同一个军队 = 空变更集（幂等）；赋另一个军队 = 改绑（覆盖该键）；
 *   <li><b>清除</b>：{@code armyId} 缺席 / {@code null} / 空白 ⇒ 删掉该键；本来就没有 ⇒ 空变更集（不具名拒：重试不该失败）；
 *   <li>只覆盖 army 这一轴：nation / gov 归属是别的模块的表、别的命令（跨模块清理结构性做不到）。
 * </ul>
 *
 * <p>★ <b>两条具名拒</b>：① {@code decisionMakerId} 不是本世界已知的决策人（决策人身份住 {@code ga}，army 看不见 ⇒ 走 {@link
 * DecisionMakerExistencePort} 端口）；② {@code armyId} 指向的军队不在 {@code ArmyData.armies()} 里。
 *
 * <p>★ <b>GM-only</b>（反默认口径）：不打 {@code DmUsableCommand} ⇒ 只进 catalog 与 GM 提交面，不进可嵌令 / 可入队 / 声明面。
 */
public final class AssignDecisionMakerArmyHandler implements CommandHandler, CommandTargets {

  private static final Logger LOG = ArmyLog.armies();

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "army.AssignDecisionMakerArmy";

  /** 归属表所在资源路径的 kind 段（{@code army:army-affiliation/<决策人 id>}）。 */
  public static final String TARGET_KIND = "army-affiliation";

  private final DecisionMakerExistencePort decisionMakers;

  public AssignDecisionMakerArmyHandler(DecisionMakerExistencePort decisionMakers) {
    this.decisionMakers = Objects.requireNonNull(decisionMakers, "decisionMakers");
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = ArmyPayloads.parse(payloadJson);
    DecisionMakerId id =
        DecisionMakerId.parse(ArmyPayloads.requireText(payload, "decisionMakerId"));
    // ★ 路径不含命名空间：CommandTargets 的默认实现按**命令类型第一段**取命名空间 ⇒ 本命令写的是
    //   army:army-affiliation/<决策人 id>。
    return List.of(ResourcePaths.ga(TARGET_KIND, id.value()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ArmySnapshot snapshot = ArmySnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    String makerForLog = null;
    String armyForLog = null;
    try {
      JsonNode payload = ArmyPayloads.parse(payloadJson);
      DecisionMakerId decisionMaker =
          DecisionMakerId.parse(ArmyPayloads.requireText(payload, "decisionMakerId"));
      makerForLog = decisionMaker.value();
      if (!decisionMakers.exists(state, decisionMaker)) {
        return rejected(
            "决策人不存在: " + decisionMaker.value() + "（先 ga.CreateDecisionMaker）", "dm", makerForLog);
      }
      String rawArmy = ArmyPayloads.optionalTextAllowingBlank(payload, "armyId").orElse(null);
      if (rawArmy != null && rawArmy.isBlank()) {
        rawArmy = null;
      }
      ArmyData base = snapshot.data();
      Map<DecisionMakerId, ArmyId> next = new LinkedHashMap<>(base.armyAffiliations());
      if (rawArmy == null) {
        if (next.remove(decisionMaker) == null) {
          // ★ 幂等：本来就没有 ⇒ 空变更集（重试不该失败；见类注）。
          return new HandlerOutcome.Applied(ArmyChangeSet.between(base, base));
        }
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ARMY_AFFILIATION_CLEARED",
                    ArmyLogSource.ARMY_REGISTRY,
                    "dm",
                    makerForLog,
                    "armyAffiliations",
                    next.size()));
        return new HandlerOutcome.Applied(
            ArmyChangeSet.between(base, base.withArmyAffiliations(next)));
      }
      ArmyId armyId = ArmyId.parse(rawArmy);
      armyForLog = armyId.value();
      if (!base.armies().containsKey(armyId)) {
        return rejected(
            "军队不存在: " + armyId + "（先 army.CreateArmy）", "dm", makerForLog, "army", armyForLog);
      }
      ArmyId previous = next.put(decisionMaker, armyId);
      if (armyId.equals(previous)) {
        return new HandlerOutcome.Applied(ArmyChangeSet.between(base, base)); // ★ 幂等
      }
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ARMY_AFFILIATION_ASSIGNED",
                  ArmyLogSource.ARMY_REGISTRY,
                  "dm",
                  makerForLog,
                  "army",
                  armyForLog,
                  "previous",
                  previous == null ? "-" : previous.value(),
                  "armyAffiliations",
                  next.size()));
      return new HandlerOutcome.Applied(
          ArmyChangeSet.between(base, base.withArmyAffiliations(next)));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "dm", makerForLog, "army", armyForLog);
    }
  }

  /** 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    ArmyPayloads.logRejected(
        LOG, ArmyLogSource.ARMY_REGISTRY, "ARMY_ASSIGN_AFFILIATION_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}
